package com.storeme;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import fi.iki.elonen.NanoHTTPD;
import org.json.JSONObject;

public class LocalStreamServer extends NanoHTTPD {

    private BlockingQueue<byte[]> dataQueue;
    private long fileSize = 0;
    private boolean isStreaming = false;
    private String currentStreamPath = null;

    public LocalStreamServer(int port) {
        super(port);
    }

    public void startStream(String path, long size) {
        this.currentStreamPath = path;
        this.fileSize = size;
        this.isStreaming = true;
        this.dataQueue = new LinkedBlockingQueue<>();
    }

    public void feedBinary(byte[] data) {
        if (isStreaming && dataQueue != null) {
            dataQueue.offer(data);
        }
    }

    public void stopStream() {
        isStreaming = false;
        if (dataQueue != null) {
            dataQueue.offer(new byte[0]); // EOF marker
        }
    }

    @Override
    public Response serve(IHTTPSession session) {
        Map<String, String> parms = session.getParms();
        String path = parms.get("path");
        if (path == null) path = currentStreamPath;
        
        String sizeStr = parms.get("size");
        if (sizeStr != null) {
            try { fileSize = Long.parseLong(sizeStr); } catch (Exception e) {}
        }

        Map<String, String> headers = session.getHeaders();
        long startOffset = 0;
        String rangeHeader = headers.get("range");
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            String[] split = rangeHeader.substring(6).split("-");
            try {
                startOffset = Long.parseLong(split[0]);
            } catch (Exception e) {}
        }

        // Request WebRTC stream from Host
        try {
            JSONObject req = new JSONObject();
            req.put("action", "stream");
            req.put("path", path);
            req.put("offset", startOffset);
            
            // Prepare queue immediately to prevent race condition
            isStreaming = true;
            if (dataQueue == null) dataQueue = new LinkedBlockingQueue<>();
            dataQueue.clear();
            
            ClientConnectionManager.getInstance().sendData(req.toString());
        } catch (Exception e) {
            e.printStackTrace();
        }

        InputStream queueStream = new InputStream() {
            private byte[] currentBuffer = null;
            private int bufferPos = 0;

            @Override
            public int read() throws IOException {
                byte[] b = new byte[1];
                int read = read(b, 0, 1);
                if (read == -1) return -1;
                return b[0] & 0xFF;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                if (currentBuffer == null || bufferPos >= currentBuffer.length) {
                    try {
                        if (!isStreaming && (dataQueue == null || dataQueue.isEmpty())) return -1;
                        currentBuffer = dataQueue.poll(5, TimeUnit.SECONDS); // 5 sec timeout
                        if (currentBuffer == null) return -1; // Timeout or stream ended
                        if (currentBuffer.length == 0) return -1; // EOF marker
                        bufferPos = 0;
                    } catch (InterruptedException e) {
                        return -1;
                    }
                }

                int available = currentBuffer.length - bufferPos;
                int toRead = Math.min(len, available);
                System.arraycopy(currentBuffer, bufferPos, b, off, toRead);
                bufferPos += toRead;
                return toRead;
            }
        };

        String mime = "application/octet-stream";
        if (path != null) {
            if (path.toLowerCase().endsWith(".mp4")) mime = "video/mp4";
            else if (path.toLowerCase().endsWith(".jpg") || path.toLowerCase().endsWith(".jpeg")) mime = "image/jpeg";
            else if (path.toLowerCase().endsWith(".png")) mime = "image/png";
            else if (path.toLowerCase().endsWith(".mp3")) mime = "audio/mpeg";
            else if (path.toLowerCase().endsWith(".pdf")) mime = "application/pdf";
        }

        Response response = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mime, queueStream, fileSize - startOffset);
        response.addHeader("Accept-Ranges", "bytes");
        response.addHeader("Content-Range", "bytes " + startOffset + "-" + (fileSize - 1) + "/" + fileSize);
        return response;
    }
}
