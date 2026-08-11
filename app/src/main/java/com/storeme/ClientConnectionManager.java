package com.storeme;

import android.content.Context;
import android.util.Log;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class ClientConnectionManager {
    private static ClientConnectionManager instance;
    private WebRTCManager rtcManager;
    private DatabaseReference dbRef;
    private String clientId;
    private boolean isConnected = false;
    private LocalStreamServer streamServer;
    private Context applicationContext;
    
    public static class DownloadTask {
        public String path;
        public long size = 0;
        public long downloaded = 0;
        public android.net.Uri uri;
        public String status = "Pending"; // Pending, Downloading, Paused, Completed
        public long speed = 0; // bytes per second
        public long lastTime = System.currentTimeMillis();
        public long lastDownloaded = 0;
    }
    
    public static class UploadTask {
        public String localPath;
        public String remoteDir;
        public String remotePath;
        public long size = 0;
        public long uploaded = 0;
        public android.net.Uri uri;
        public String status = "Pending"; // Pending, Uploading, Paused, Completed
        public long speed = 0;
        public long lastTime = System.currentTimeMillis();
        public long lastUploaded = 0;
    }
    private java.util.Map<String, UploadTask> uploads = new java.util.concurrent.ConcurrentHashMap<>();

    private java.util.Map<String, DownloadTask> downloads = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.Map<String, String> syncPaths = new java.util.concurrent.ConcurrentHashMap<>();
    private String currentDownloadPath = null;
    private java.io.OutputStream currentDownloadStream = null;
    
    public void requestSyncDownload(String remotePath, String localPath) {
        syncPaths.put(remotePath, localPath);
    }
    
    private List<ClientListener> listeners = new ArrayList<>();

    public boolean isConnected() {
        return isConnected;
    }

    public interface ClientListener {
        void onConnected();
        void onMessage(JSONObject message);
        void onBinary(ByteBuffer buffer);
        void onDisconnected();
    }

    private ClientConnectionManager() {}

    public static synchronized ClientConnectionManager getInstance() {
        if (instance == null) {
            instance = new ClientConnectionManager();
        }
        return instance;
    }

    public void addListener(ClientListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
        if (isConnected) {
            listener.onConnected();
        }
    }

    public void removeListener(ClientListener listener) {
        listeners.remove(listener);
    }

    public void sendData(String data) {
        if (rtcManager != null && isConnected) {
            rtcManager.sendData(data);
        }
    }

    public java.util.Collection<DownloadTask> getDownloads() {
        return downloads.values();
    }

    private void saveDownloads() {
        if (applicationContext == null) return;
        try {
            org.json.JSONArray arr = new org.json.JSONArray();
            for (DownloadTask t : downloads.values()) {
                org.json.JSONObject obj = new org.json.JSONObject();
                obj.put("path", t.path);
                obj.put("size", t.size);
                obj.put("downloaded", t.downloaded);
                obj.put("status", t.status);
                if (t.uri != null) obj.put("uri", t.uri.toString());
                arr.put(obj);
            }
            applicationContext.getSharedPreferences("StoreMePrefs", Context.MODE_PRIVATE)
                .edit().putString("saved_downloads", arr.toString()).apply();
        } catch (Exception e) {}
    }

    private void loadDownloads(Context ctx) {
        try {
            String json = ctx.getSharedPreferences("StoreMePrefs", Context.MODE_PRIVATE)
                    .getString("saved_downloads", "[]");
            org.json.JSONArray arr = new org.json.JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                org.json.JSONObject obj = arr.getJSONObject(i);
                DownloadTask t = new DownloadTask();
                t.path = obj.optString("path");
                t.size = obj.optLong("size", 0);
                t.downloaded = obj.optLong("downloaded", 0);
                t.status = obj.optString("status");
                if ("Downloading".equals(t.status)) t.status = "Paused"; // Paused if app closed
                String uriStr = obj.optString("uri", null);
                if (uriStr != null) t.uri = android.net.Uri.parse(uriStr);
                downloads.put(t.path, t);
            }
        } catch (Exception e) {}
    }

    public void startDownload(Context ctx, String path, android.net.Uri uri) {
        DownloadTask task = new DownloadTask();
        task.path = path;
        task.uri = uri;
        task.status = "Downloading";
        downloads.put(path, task);
        saveDownloads();
        JSONObject json = new JSONObject();
        try {
            json.put("action", "download");
            json.put("path", task.path);
            json.put("offset", task.downloaded);
            sendData(json.toString());
        } catch (Exception e) {}
    }

    
    public java.util.Collection<UploadTask> getUploads() {
        return uploads.values();
    }

    public void startUpload(Context ctx, android.net.Uri uri, String localName, String remoteDir) {
        UploadTask task = new UploadTask();
        task.uri = uri;
        task.localPath = localName;
        task.remoteDir = remoteDir;
        task.remotePath = remoteDir + "/" + localName;
        task.status = "Uploading";
        
        try {
            android.database.Cursor cursor = ctx.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE);
                task.size = cursor.getLong(sizeIndex);
                cursor.close();
            }
        } catch (Exception e) {}
        
        uploads.put(task.remotePath, task);
        
        try {
            org.json.JSONObject req = new org.json.JSONObject();
            req.put("action", "upload_start");
            req.put("path", task.remotePath);
            req.put("size", task.size);
            sendData(req.toString());
        } catch (Exception e) {}
    }

    public void pauseUpload(String remotePath) {
        UploadTask task = uploads.get(remotePath);
        if (task != null && task.status.equals("Uploading")) {
            task.status = "Paused";
            task.speed = 0;
        }
    }

    public void cancelUpload(String remotePath) {
        uploads.remove(remotePath);
    }
    
    private void sendNextUploadChunk(String remotePath, long offset) {
        UploadTask task = uploads.get(remotePath);
        if (task == null || !task.status.equals("Uploading")) return;
        
        new Thread(() -> {
            try {
                java.io.InputStream is = applicationContext.getContentResolver().openInputStream(task.uri);
                if (is != null) {
                    is.skip(offset);
                    byte[] buffer = new byte[32 * 1024]; // 32KB chunk for safe WebRTC transfer
                    int read = is.read(buffer);
                    is.close();
                    
                    if (read > 0) {
                        String base64 = android.util.Base64.encodeToString(buffer, 0, read, android.util.Base64.NO_WRAP);
                        org.json.JSONObject req = new org.json.JSONObject();
                        req.put("action", "upload_chunk");
                        req.put("path", remotePath);
                        req.put("offset", offset);
                        req.put("data", base64);
                        sendData(req.toString());
                        
                        task.uploaded = offset + read;
                        long now = System.currentTimeMillis();
                        if (now - task.lastTime >= 1000) {
                            task.speed = (task.uploaded - task.lastUploaded) * 1000 / (now - task.lastTime);
                            task.lastTime = now;
                            task.lastUploaded = task.uploaded;
                        }
                    } else {
                        task.status = "Completed";
                        task.speed = 0;
                    }
                }
            } catch (Exception e) {}
        }).start();
    }

    public void pauseDownload(String path) {
        DownloadTask task = downloads.get(path);
        if (task != null) {
            task.status = "Paused";
            task.speed = 0;
            saveDownloads();
            if (currentDownloadPath != null && currentDownloadPath.equals(path)) {
                try {
                    if (currentDownloadStream != null) currentDownloadStream.close();
                } catch (Exception e) {}
                currentDownloadStream = null;
                currentDownloadPath = null;
            }
            
            JSONObject json = new JSONObject();
            try {
                json.put("action", "pause_transfer");
                json.put("path", path);
                sendData(json.toString());
            } catch (Exception e) {}
        }
    }

    public void resumeDownload(String path) {
        DownloadTask task = downloads.get(path);
        if (task != null && task.status.equals("Paused")) {
            task.status = "Downloading";
            task.lastTime = System.currentTimeMillis();
            task.lastDownloaded = task.downloaded;
            saveDownloads();
            try {
                currentDownloadPath = path;
                currentDownloadStream = applicationContext.getContentResolver().openOutputStream(task.uri, "wa");
            } catch (Exception e) {}
            
            JSONObject json = new JSONObject();
            try {
                json.put("action", "download");
                json.put("path", task.path);
                json.put("offset", task.downloaded);
                sendData(json.toString());
            } catch (Exception e) {}
        }
    }

    public void cancelDownload(String path) {
        DownloadTask task = downloads.get(path);
        if (task != null) {
            task.status = "Cancelled";
            task.speed = 0;
            if (currentDownloadPath != null && currentDownloadPath.equals(path)) {
                try {
                    if (currentDownloadStream != null) currentDownloadStream.close();
                } catch (Exception e) {}
                currentDownloadStream = null;
                currentDownloadPath = null;
            }
            
            // Delete partial file
            java.io.File f = new java.io.File(path);
            if (f.exists()) f.delete();

            downloads.remove(path);
            saveDownloads();
        }
    }

    public void connect(Context context, String hostId, String username, String password) {
        if (rtcManager != null) return; // Already initialized

        applicationContext = context.getApplicationContext();
        loadDownloads(applicationContext);

        try {
            if (streamServer == null) {
                streamServer = new LocalStreamServer(8080);
                streamServer.start();
            }
        } catch (Exception e) {}

        clientId = "native_client_" + System.currentTimeMillis();
        dbRef = FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("connections").child(clientId);

        rtcManager = new WebRTCManager(context, new WebRTCManager.WebRTCListener() {
            @Override
            public void onIceCandidate(org.webrtc.IceCandidate candidate) {
                dbRef.child("candidates").child("client").push().setValue(
                        new HostForegroundService.CandidatePayload(candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex)
                );
            }

            @Override
            public void onDataChannelMessage(String message) {
                try {
                    JSONObject res = new JSONObject(message);
                    
                    
                    if ("upload_ack".equals(res.optString("action"))) {
                        String remotePath = res.optString("path");
                        long offset = res.optLong("offset");
                        sendNextUploadChunk(remotePath, offset);
                    }

                    if ("file_transfer_start".equals(res.optString("action"))) {
                        String intentStr = res.optString("intent");
                        String path = res.optString("path");
                        if ("stream".equals(intentStr)) {
                            if (streamServer != null) {
                                streamServer.startStream(path, res.optLong("size"));
                            }
                        } else if ("download".equals(intentStr)) {
                            currentDownloadPath = path;
                            DownloadTask task = downloads.get(path);
                            if (task != null) {
                                task.size = res.optLong("size");
                                task.status = "Downloading";
                                try {
                                    // Open appending stream if resuming
                                    currentDownloadStream = applicationContext.getContentResolver().openOutputStream(task.uri, "wa");
                                } catch (Exception e) {}
                            }
                        } else if ("sync".equals(intentStr)) {
                            currentDownloadPath = path;
                            String localPath = syncPaths.get(path);
                            if (localPath != null) {
                                try {
                                    java.io.File localFile = new java.io.File(localPath);
                                    if (res.optLong("offset") == 0 && localFile.exists()) localFile.delete();
                                    currentDownloadStream = new java.io.FileOutputStream(localFile, true);
                                } catch (Exception e) {}
                            }
                        } else if ("preview".equals(intentStr)) {
                            currentDownloadPath = path;
                            java.io.File cacheFile = new java.io.File(applicationContext.getCacheDir(), res.optString("name"));
                            try {
                                currentDownloadStream = new java.io.FileOutputStream(cacheFile);
                            } catch (Exception e) {}
                        }
                    } else if ("file_transfer_end".equals(res.optString("action"))) {
                        String intentStr = res.optString("intent");
                        String path = res.optString("path");
                        if ("stream".equals(intentStr)) {
                            if (streamServer != null) {
                                streamServer.stopStream();
                            }
                        } else if ("download".equals(intentStr)) {
                            if (currentDownloadStream != null) {
                                try { currentDownloadStream.close(); } catch (Exception e) {}
                                currentDownloadStream = null;
                            }
                            DownloadTask task = downloads.get(path);
                            if (task != null && task.status.equals("Downloading")) {
                                task.status = "Completed";
                                task.speed = 0;
                                saveDownloads();
                            }
                            currentDownloadPath = null;
                        } else if ("preview".equals(intentStr)) {
                            if (currentDownloadStream != null) {
                                try { currentDownloadStream.close(); } catch (Exception e) {}
                                currentDownloadStream = null;
                            }
                            currentDownloadPath = null;
                        } else if ("sync".equals(intentStr)) {
                            if (currentDownloadStream != null) {
                                try { currentDownloadStream.close(); } catch (Exception e) {}
                                currentDownloadStream = null;
                            }
                            currentDownloadPath = null;
                        }
                    }

                    for (ClientListener l : listeners) {
                        l.onMessage(res);
                    }
                } catch (Exception e) {}
            }

            @Override
            public void onDataChannelBinary(ByteBuffer buffer) {
                if (streamServer != null) {
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    streamServer.feedBinary(bytes);
                    // Reset buffer position for listeners
                    buffer.position(0);
                }
                
                if (currentDownloadStream != null && currentDownloadPath != null) {
                    try {
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        currentDownloadStream.write(bytes);
                        
                        DownloadTask task = downloads.get(currentDownloadPath);
                        if (task != null) {
                            task.downloaded += bytes.length;
                            long now = System.currentTimeMillis();
                            if (now - task.lastTime >= 1000) {
                                task.speed = (task.downloaded - task.lastDownloaded) * 1000 / (now - task.lastTime);
                                task.lastTime = now;
                                task.lastDownloaded = task.downloaded;
                                // Removed saveDownloads() here to prevent UI thread lock contention
                            }
                        }
                        buffer.position(0);
                    } catch (Exception e) {}
                }
                
                for (ClientListener l : listeners) {
                    l.onBinary(buffer);
                }
            }

            @Override
            public void onDataChannelOpen() {
                isConnected = true;
                for (ClientListener l : listeners) {
                    l.onConnected();
                }
            }

            @Override
            public void onDisconnected() {
                isConnected = false;
                for (ClientListener l : listeners) {
                    l.onDisconnected();
                }
            }
        });

        rtcManager.createPeerConnection();
        rtcManager.createOfferAndSetLocal(new org.webrtc.SdpObserver() {
            @Override
            public void onCreateSuccess(org.webrtc.SessionDescription offer) {
                try {
                    java.util.Map<String, Object> map = new java.util.HashMap<>();
                    map.put("type", offer.type.canonicalForm());
                    map.put("sdp", offer.description);
                    map.put("username", username);
                    map.put("password", password);
                    dbRef.child("offer").setValue(map);
                } catch (Exception e) {}
            }
            @Override public void onSetSuccess() {}
            @Override public void onCreateFailure(String s) {}
            @Override public void onSetFailure(String s) {}
        });

        // Listen for Answer
        dbRef.child("answer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (snapshot.exists()) {
                    String sdp = snapshot.child("sdp").getValue(String.class);
                    String type = snapshot.child("type").getValue(String.class);
                    org.webrtc.SessionDescription answer = new org.webrtc.SessionDescription(
                            org.webrtc.SessionDescription.Type.fromCanonicalForm(type), sdp);
                    rtcManager.setRemoteAnswer(answer);
                }
            }
            @Override public void onCancelled(DatabaseError error) {}
        });

        // Listen for Host ICE
        dbRef.child("candidates").child("host").addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot snapshot, String prev) {
                String candidate = snapshot.child("candidate").getValue(String.class);
                String sdpMid = snapshot.child("sdpMid").getValue(String.class);
                Integer sdpMLineIndex = snapshot.child("sdpMLineIndex").getValue(Integer.class);
                if (candidate != null && sdpMid != null && sdpMLineIndex != null) {
                    rtcManager.addRemoteIceCandidate(new org.webrtc.IceCandidate(sdpMid, sdpMLineIndex, candidate));
                }
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    public void disconnect() {
        if (rtcManager != null) {
            rtcManager.close();
            rtcManager = null;
        }
        if (streamServer != null) {
            streamServer.stop();
            streamServer = null;
        }
        isConnected = false;
        instance = null; // Reset singleton
    }
}
