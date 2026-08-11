package com.storeme;

import fi.iki.elonen.NanoHTTPD;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class HostStreamServer extends NanoHTTPD {
    
    // Maps token -> actual absolute file path
    private Map<String, String> shareTokens = new ConcurrentHashMap<>();

    public HostStreamServer(int port) {
        super(port);
    }
    
    public void addShareToken(String token, String path) {
        shareTokens.put(token, path);
    }
    
    public void removeShareToken(String token) {
        shareTokens.remove(token);
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> parms = session.getParms();
        
        if ("/share".equals(uri)) {
            String token = parms.get("token");
            if (token != null && shareTokens.containsKey(token)) {
                String path = shareTokens.get(token);
                File file = new File(path);
                if (file.exists() && !file.isDirectory()) {
                    try {
                        String mimeType = "*/*";
                        int dotIndex = file.getName().lastIndexOf('.');
                        if (dotIndex > 0) {
                            String ext = file.getName().substring(dotIndex + 1).toLowerCase();
                            String possibleMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext);
                            if (possibleMime != null) mimeType = possibleMime;
                        }
                        
                        Map<String, String> headers = session.getHeaders();
                        long startOffset = 0;
                        String rangeHeader = headers.get("range");
                        long length = file.length();
                        
                        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                            String[] split = rangeHeader.substring(6).split("-");
                            try { startOffset = Long.parseLong(split[0]); } catch (Exception e) {}
                            FileInputStream fis = new FileInputStream(file);
                            fis.skip(startOffset);
                            Response res = newFixedLengthResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis, length - startOffset);
                            res.addHeader("Content-Range", "bytes " + startOffset + "-" + (length - 1) + "/" + length);
                            res.addHeader("Accept-Ranges", "bytes");
                            return res;
                        } else {
                            FileInputStream fis = new FileInputStream(file);
                            Response res = newFixedLengthResponse(Response.Status.OK, mimeType, fis, length);
                            res.addHeader("Accept-Ranges", "bytes");
                            res.addHeader("Content-Disposition", "attachment; filename=\"" + file.getName() + "\"");
                            return res;
                        }
                    } catch (Exception e) {
                        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, NanoHTTPD.MIME_PLAINTEXT, "Internal Error");
                    }
                } else {
                    return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "File Not Found or Access Denied");
                }
            } else {
                return newFixedLengthResponse(Response.Status.UNAUTHORIZED, NanoHTTPD.MIME_PLAINTEXT, "Invalid or Expired Token");
            }
        }
        
        return newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "Not Found");
    }
}
