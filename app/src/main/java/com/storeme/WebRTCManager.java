package com.storeme;

import android.content.Context;
import android.util.Log;

import org.webrtc.DataChannel;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class WebRTCManager {
    private static final String TAG = "WebRTCManager";
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private DataChannel dataChannel;
    private WebRTCListener listener;
    private static boolean isInitialized = false;
    
    private List<IceCandidate> queuedRemoteCandidates = new ArrayList<>();
    private boolean isRemoteDescriptionSet = false;

    public interface WebRTCListener {
        void onIceCandidate(IceCandidate candidate);
        void onDataChannelMessage(String message);
        void onDataChannelBinary(ByteBuffer buffer);
        void onDataChannelOpen();
        void onDisconnected();
    }


    public WebRTCManager(Context context, WebRTCListener listener) {
        this.listener = listener;
        initWebRTC(context);
    }


    private void initWebRTC(Context context) {
        if (!isInitialized) {
            PeerConnectionFactory.InitializationOptions initOptions =
                    PeerConnectionFactory.InitializationOptions.builder(context)
                            .setEnableInternalTracer(true)
                            .createInitializationOptions();
            PeerConnectionFactory.initialize(initOptions);
            isInitialized = true;
        }

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();
    }

    public void createPeerConnection() {
        List<PeerConnection.IceServer> iceServers = new ArrayList<>();
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer());
        iceServers.add(PeerConnection.IceServer.builder("stun:global.stun.twilio.com:3478").createIceServer());

        PeerConnection.RTCConfiguration rtcConfig = new PeerConnection.RTCConfiguration(iceServers);

        peerConnection = factory.createPeerConnection(rtcConfig, new PeerConnection.Observer() {
            @Override public void onSignalingChange(PeerConnection.SignalingState signalingState) {}
            
            @Override 
            public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
                if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED ||
                    iceConnectionState == PeerConnection.IceConnectionState.FAILED ||
                    iceConnectionState == PeerConnection.IceConnectionState.CLOSED) {
                    if (listener != null) listener.onDisconnected();
                }
            }
            
            @Override public void onIceConnectionReceivingChange(boolean b) {}
            @Override public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {}
            
            @Override
            public void onIceCandidate(IceCandidate iceCandidate) {
                if (listener != null) listener.onIceCandidate(iceCandidate);
            }

            @Override public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}
            @Override public void onAddStream(org.webrtc.MediaStream mediaStream) {}
            @Override public void onRemoveStream(org.webrtc.MediaStream mediaStream) {}
            
            @Override
            public void onDataChannel(DataChannel channel) {
                dataChannel = channel;
                setupDataChannel();
            }
            
            @Override public void onRenegotiationNeeded() {}
        });
    }

    private void setupDataChannel() {
        dataChannel.registerObserver(new DataChannel.Observer() {
            @Override
            public void onBufferedAmountChange(long previousAmount) {}

            @Override
            public void onStateChange() {
                if (dataChannel.state() == DataChannel.State.OPEN) {
                    Log.i(TAG, "DataChannel is OPEN!");
                    if (listener != null) listener.onDataChannelOpen();
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                if (listener != null) {
                    if (buffer.binary) {
                        listener.onDataChannelBinary(buffer.data);
                    } else {
                        byte[] data = new byte[buffer.data.capacity()];
                        buffer.data.get(data);
                        listener.onDataChannelMessage(new String(data));
                    }
                }
            }
        });
    }

    public void setRemoteOfferAndCreateAnswer(SessionDescription offer, SdpObserver sdpObserver) {
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                isRemoteDescriptionSet = true;
                drainCandidates();
            }
        }, offer);
        peerConnection.createAnswer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription answer) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), answer);
                sdpObserver.onCreateSuccess(answer);
            }
        }, new MediaConstraints());
    }

    public void createOfferAndSetLocal(SdpObserver sdpObserver) {
        DataChannel.Init init = new DataChannel.Init();
        dataChannel = peerConnection.createDataChannel("fileChannel", init);
        setupDataChannel();

        peerConnection.createOffer(new SimpleSdpObserver() {
            @Override
            public void onCreateSuccess(SessionDescription offer) {
                peerConnection.setLocalDescription(new SimpleSdpObserver(), offer);
                sdpObserver.onCreateSuccess(offer);
            }
        }, new MediaConstraints());
    }

    public void setRemoteAnswer(SessionDescription answer) {
        peerConnection.setRemoteDescription(new SimpleSdpObserver() {
            @Override
            public void onSetSuccess() {
                isRemoteDescriptionSet = true;
                drainCandidates();
            }
        }, answer);
    }

    public void addRemoteIceCandidate(IceCandidate candidate) {
        if (isRemoteDescriptionSet) {
            if (peerConnection != null) {
                peerConnection.addIceCandidate(candidate);
            }
        } else {
            queuedRemoteCandidates.add(candidate);
        }
    }
    
    private void drainCandidates() {
        if (peerConnection != null) {
            for (IceCandidate c : queuedRemoteCandidates) {
                peerConnection.addIceCandidate(c);
            }
            queuedRemoteCandidates.clear();
        }
    }

    public void sendData(String text) {
        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            ByteBuffer buffer = ByteBuffer.wrap(text.getBytes());
            dataChannel.send(new DataChannel.Buffer(buffer, false));
        }
    }

    public void sendBinaryData(byte[] bytes) {
        if (dataChannel != null && dataChannel.state() == DataChannel.State.OPEN) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            dataChannel.send(new DataChannel.Buffer(buffer, true));
        }
    }

    public void close() {
        if (dataChannel != null) {
            dataChannel.close();
            dataChannel = null;
        }
        if (peerConnection != null) {
            peerConnection.close();
            peerConnection = null;
        }
        queuedRemoteCandidates.clear();
        isRemoteDescriptionSet = false;
    }

    private static class SimpleSdpObserver implements SdpObserver {
        @Override public void onCreateSuccess(SessionDescription sessionDescription) {}
        @Override public void onSetSuccess() {}
        @Override public void onCreateFailure(String s) {}
        @Override public void onSetFailure(String s) {}
    }
}
