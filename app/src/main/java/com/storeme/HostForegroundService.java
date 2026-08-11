package com.storeme;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class HostForegroundService extends Service {
    private static final String CHANNEL_ID = "StoreMeNativeChannel";
    private DatabaseReference dbRef;
    private String hostId;
    private String password;
    private PowerManager.WakeLock wakeLock;
    private android.net.wifi.WifiManager.WifiLock wifiLock;
    private HostStreamServer streamServer;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        hostId = intent.getStringExtra("HOST_ID");
        password = intent.getStringExtra("PASSWORD");

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("StoreMe Native Server")
                .setContentText("Listening on ID: " + hostId)
                .setSmallIcon(android.R.drawable.ic_menu_upload)
                .build();

        startForeground(1, notification);
        
        Log.i("StoreMeNative", "Native Background Service Started. Host: " + hostId);

        // Acquire WakeLock for robust background transfers
        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        if (powerManager != null) {
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StoreMe::TransferWakeLock");
            wakeLock.acquire();
        }
        
        android.net.wifi.WifiManager wifiManager = (android.net.wifi.WifiManager) getApplicationContext().getSystemService(android.content.Context.WIFI_SERVICE);
        if (wifiManager != null) {
            wifiLock = wifiManager.createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "StoreMe::WifiLock");
            wifiLock.acquire();
        }
        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        if (prefs.getLong("host_start_time", 0) == 0) {
            prefs.edit().putLong("host_start_time", System.currentTimeMillis()).commit();
        }

        try {
            streamServer = new HostStreamServer(8081);
            streamServer.start();
        } catch (Exception e) {}

        // Initialize Firebase Listeners
        dbRef = FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("connections");
        
        // Broadcast that the host is online, and remove it when host disconnects
        DatabaseReference statusRef = FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("status");
        statusRef.setValue("online");
        statusRef.onDisconnect().removeValue();
        dbRef.getParent().child("connections").onDisconnect().removeValue();

        listenForClients();

        return START_STICKY;
    }

    private void listenForClients() {
        Log.i("StoreMeNative", "Listening for incoming WebRTC connections...");
        
        dbRef.addChildEventListener(new com.google.firebase.database.ChildEventListener() {
            @Override
            public void onChildAdded(DataSnapshot clientSnapshot, String previousChildName) {
                String clientId = clientSnapshot.getKey();
                if (clientId == null) return;
                Log.i("StoreMeNative", "New client detected: " + clientId);
                handleClientConnection(clientId);
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {}
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {}
        });
    }

    private java.util.Map<String, Thread> activeTransfers = new java.util.concurrent.ConcurrentHashMap<>();
    private java.util.Map<String, Boolean> transferStatus = new java.util.concurrent.ConcurrentHashMap<>();
    private static java.util.Map<String, WebRTCManager> activePeers = new java.util.concurrent.ConcurrentHashMap<>();

    public static int getActiveConnectionCount() {
        return activePeers.size();
    }

    private void handleClientConnection(String clientId) {
        final WebRTCManager[] rtcHolder = new WebRTCManager[1];
        final String[] connectedUsername = new String[1];
        
        WebRTCManager rtcManager = new WebRTCManager(this, new WebRTCManager.WebRTCListener() {
            @Override
            public void onIceCandidate(org.webrtc.IceCandidate candidate) {
                dbRef.child(clientId).child("candidates").child("host").push().setValue(new CandidatePayload(
                        candidate.sdp, candidate.sdpMid, candidate.sdpMLineIndex
                ));
            }

            private boolean isPathAllowed(String checkPath) {
                if (connectedUsername[0] == null) return false;
                
                android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                String usersJson = prefs.getString("host_users", "[]");
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                    for (int i=0; i<arr.length(); i++) {
                        org.json.JSONObject u = arr.getJSONObject(i);
                        if (u.optString("username").equals(connectedUsername[0])) {
                            org.json.JSONArray perms = u.optJSONArray("permissions");
                            if (perms != null) {
                                for (int j=0; j<perms.length(); j++) {
                                    String allowed = perms.getString(j);
                                    if (allowed.equals("/") || checkPath.startsWith(allowed)) return true;
                                }
                            }
                            break;
                        }
                    }
                } catch (Exception e) {}
                return false;
            }

            @Override
            public void onDataChannelMessage(String message) {
                Log.i("StoreMeNative", "Message from client: " + message);
                try {
                    org.json.JSONObject req = new org.json.JSONObject(message);
                    String action = req.optString("action");
                    String path = req.optString("path");

                    if (path.isEmpty() || path.equals("/")) {
                        // Resolve empty or root path to user's first allowed path (or external storage)
                        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                        boolean foundPerm = false;
                        try {
                            org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("host_users", "[]"));
                            for (int i=0; i<arr.length(); i++) {
                                org.json.JSONObject u = arr.getJSONObject(i);
                                if (u.optString("username").equals(connectedUsername[0])) {
                                    org.json.JSONArray perms = u.optJSONArray("permissions");
                                    if (perms != null && perms.length() > 0) {
                                        path = perms.getString(0);
                                        if (path.equals("/")) path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                                        foundPerm = true;
                                    }
                                    break;
                                }
                            }
                        } catch (Exception e) {}
                        if (!foundPerm || path.isEmpty() || path.equals("/")) {
                            path = android.os.Environment.getExternalStorageDirectory().getAbsolutePath();
                        }
                    }

                    final String effectivePath = path;

                    // Enforce permissions for read/write actions
                    if (!isPathAllowed(effectivePath)) {
                        org.json.JSONObject res = new org.json.JSONObject();
                        res.put("action", "access_denied");
                        res.put("path", effectivePath);
                        if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        return;
                    }

                    if ("list_dir".equals(action)) {
                        java.io.File dir = new java.io.File(effectivePath);
                        if (dir.exists() && dir.isDirectory()) {
                            java.io.File[] files = dir.listFiles();
                            if (files != null) {
                                int chunkSize = 50; // 50 files per message
                                int totalChunks = (int) Math.ceil((double) files.length / chunkSize);
                                if (totalChunks == 0) totalChunks = 1;
                                
                                for (int c = 0; c < totalChunks; c++) {
                                    org.json.JSONArray batchArray = new org.json.JSONArray();
                                    int start = c * chunkSize;
                                    int end = Math.min(start + chunkSize, files.length);
                                    
                                    for (int i = start; i < end; i++) {
                                        java.io.File f = files[i];
                                        org.json.JSONObject fileObj = new org.json.JSONObject();
                                        fileObj.put("name", f.getName());
                                        fileObj.put("path", f.getAbsolutePath());
                                        fileObj.put("isDir", f.isDirectory());
                                        fileObj.put("size", f.length());
                                        fileObj.put("lastModified", f.lastModified());
                                        batchArray.put(fileObj);
                                    }
                                    
                                    org.json.JSONObject res = new org.json.JSONObject();
                                    res.put("action", "directory_listing");
                                    res.put("path", dir.getAbsolutePath());
                                    res.put("files", batchArray);
                                    res.put("chunk", c + 1);
                                    res.put("total_chunks", totalChunks);
                                    if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                                }
                            } else {
                                // Permission denied or empty
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "directory_listing");
                                res.put("path", dir.getAbsolutePath());
                                res.put("files", new org.json.JSONArray());
                                res.put("chunk", 1);
                                res.put("total_chunks", 1);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            }
                        } else if (dir.exists() && dir.isFile()) {
                            try {
                                org.json.JSONArray batchArray = new org.json.JSONArray();
                                org.json.JSONObject fileObj = new org.json.JSONObject();
                                fileObj.put("name", dir.getName());
                                fileObj.put("path", dir.getAbsolutePath());
                                fileObj.put("isDir", false);
                                fileObj.put("size", dir.length());
                                fileObj.put("lastModified", dir.lastModified());
                                batchArray.put(fileObj);
                                
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "directory_listing");
                                res.put("path", dir.getParent() != null ? dir.getParent() : dir.getAbsolutePath());
                                res.put("files", batchArray);
                                res.put("chunk", 1);
                                res.put("total_chunks", 1);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            } catch (Exception e) {}
                        }
                    } else if ("stream".equals(action) && !effectivePath.isEmpty()) {
                        long offset = req.optLong("offset", 0);
                        if (rtcHolder[0] != null) {
                            transferStatus.put(effectivePath, true);
                            Thread t = new Thread(() -> streamFileOverWebRTC(effectivePath, rtcHolder[0], "stream", offset));
                            activeTransfers.put(effectivePath, t);
                            t.start();
                        }
                    
                    } else if ("upload_start".equals(action) && !effectivePath.isEmpty()) {
                        if (connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) {
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "access_denied");
                            res.put("path", effectivePath);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            return;
                        }
                        try {
                            java.io.File f = new java.io.File(effectivePath);
                            if (!f.exists()) {
                                f.getParentFile().mkdirs();
                                f.createNewFile();
                            }
                            long existingSize = f.length();
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "upload_ack");
                            res.put("path", effectivePath);
                            res.put("offset", existingSize); // Tell client to resume from end of file
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    } else if ("upload_chunk".equals(action) && !effectivePath.isEmpty()) {
                        if (connectedUsername[0] != null && connectedUsername[0].startsWith("share_user_")) {
                            return;
                        }
                        try {
                            long offset = req.optLong("offset");
                            String base64 = req.optString("data");
                            byte[] data = android.util.Base64.decode(base64, android.util.Base64.NO_WRAP);
                            
                            java.io.RandomAccessFile raf = new java.io.RandomAccessFile(effectivePath, "rw");
                            raf.seek(offset);
                            raf.write(data);
                            raf.close();
                            
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "upload_ack");
                            res.put("path", effectivePath);
                            res.put("offset", offset + data.length);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}

                    } else if ("download".equals(action) && !effectivePath.isEmpty()) {
                        long offset = req.optLong("offset", 0);
                        if (rtcHolder[0] != null) {
                            transferStatus.put(effectivePath, true);
                            Thread t = new Thread(() -> streamFileOverWebRTC(effectivePath, rtcHolder[0], "download", offset));
                            activeTransfers.put(effectivePath, t);
                            t.start();
                        }
                    } else if ("pause_transfer".equals(action) && !effectivePath.isEmpty()) {
                        transferStatus.put(effectivePath, false);
                    } else if ("move".equals(action) && !effectivePath.isEmpty()) {
                        String newPath = req.optString("newPath");
                        if (newPath != null && !newPath.isEmpty()) {
                            java.io.File oldFile = new java.io.File(effectivePath);
                            java.io.File newFile = new java.io.File(newPath);
                            
                            if (newFile.getParentFile() != null && !newFile.getParentFile().exists()) {
                                newFile.getParentFile().mkdirs();
                            }
                            
                            if (oldFile.exists() && oldFile.renameTo(newFile)) {
                                Log.i("StoreMeNative", "Moved " + oldFile + " to " + newFile);
                            }
                        }
                    } else if ("copy".equals(action) && !effectivePath.isEmpty()) {
                        String newPath = req.optString("newPath");
                        if (newPath != null && !newPath.isEmpty()) {
                            java.io.File oldFile = new java.io.File(effectivePath);
                            java.io.File newFile = new java.io.File(newPath);
                            
                            if (newFile.getParentFile() != null && !newFile.getParentFile().exists()) {
                                newFile.getParentFile().mkdirs();
                            }
                            
                            if (oldFile.exists()) {
                                try {
                                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                                        java.nio.file.Files.copy(oldFile.toPath(), newFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                                    } else {
                                        java.io.InputStream in = new java.io.FileInputStream(oldFile);
                                        java.io.OutputStream out = new java.io.FileOutputStream(newFile);
                                        byte[] buf = new byte[1024];
                                        int len;
                                        while ((len = in.read(buf)) > 0) out.write(buf, 0, len);
                                        in.close();
                                        out.close();
                                    }
                                    Log.i("StoreMeNative", "Copied " + oldFile + " to " + newFile);
                                } catch (Exception e) {}
                            }
                        }
                    } else if ("delete".equals(action) && !effectivePath.isEmpty()) {
                        java.io.File f = new java.io.File(effectivePath);
                        if (f.exists()) {
                            java.io.File trashDir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), ".storeme_trash");
                            if (!trashDir.exists()) trashDir.mkdirs();
                            f.renameTo(new java.io.File(trashDir, f.getName()));
                        }
                    } else if ("delete_permanent".equals(action) && !effectivePath.isEmpty()) {
                        java.io.File f = new java.io.File(effectivePath);
                        if (f.exists()) {
                            deleteRecursive(f);
                        }
                    } else if ("restore".equals(action) && !effectivePath.isEmpty()) {
                        java.io.File f = new java.io.File(effectivePath);
                        if (f.exists()) {
                            java.io.File parent = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Restored");
                            if (!parent.exists()) parent.mkdirs();
                            f.renameTo(new java.io.File(parent, f.getName()));
                        }
                    } else if ("rename".equals(action) && !effectivePath.isEmpty()) {
                        String newName = req.optString("newName");
                        java.io.File oldFile = new java.io.File(effectivePath);
                        if (oldFile.exists() && !newName.isEmpty()) {
                            oldFile.renameTo(new java.io.File(oldFile.getParent(), newName));
                        }
                    } else if ("move".equals(action) && !effectivePath.isEmpty()) {
                        String newPath = req.optString("newPath");
                        java.io.File oldFile = new java.io.File(effectivePath);
                        if (oldFile.exists() && !newPath.isEmpty()) {
                            oldFile.renameTo(new java.io.File(newPath));
                        }
                    } else if ("zip".equals(action) && !effectivePath.isEmpty()) {
                        new Thread(() -> zipDirectory(new java.io.File(effectivePath))).start();
                    } else if ("unzip".equals(action) && !effectivePath.isEmpty()) {
                        new Thread(() -> unzipFile(new java.io.File(effectivePath))).start();
                    } else if ("create_link".equals(action) && !effectivePath.isEmpty()) {
                        String token = java.util.UUID.randomUUID().toString().substring(0, 8);
                        long expiresAt = System.currentTimeMillis() + (24L * 60 * 60 * 1000); // 24 hours
                        
                        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", android.content.Context.MODE_PRIVATE);
                        org.json.JSONArray links;
                        try { links = new org.json.JSONArray(prefs.getString("share_links", "[]")); }
                        catch (Exception e) { links = new org.json.JSONArray(); }
                        
                        try {
                            org.json.JSONObject linkObj = new org.json.JSONObject();
                            linkObj.put("token", token);
                            linkObj.put("path", effectivePath);
                            linkObj.put("expiresAt", expiresAt);
                            links.put(linkObj);
                            prefs.edit().putString("share_links", links.toString()).apply();
                            
                            if (streamServer != null) {
                                streamServer.addShareToken(token, effectivePath);
                            }
                            
                            String publicUrl = "https://storeme-web1.vercel.app/share?host=" + hostId + "&token=" + token;
                            
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "link_created");
                            res.put("token", publicUrl);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    } else if ("sync_diff".equals(action) && !effectivePath.isEmpty()) {
                        new Thread(() -> {
                            try {
                                org.json.JSONArray files = new org.json.JSONArray();
                                java.io.File root = new java.io.File(effectivePath);
                                buildSyncManifest(root, root.getAbsolutePath(), files);
                                
                                org.json.JSONObject res = new org.json.JSONObject();
                                res.put("action", "sync_manifest");
                                res.put("path", effectivePath);
                                res.put("files", files);
                                if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                            } catch (Exception e) {}
                        }).start();

                    } else if ("search".equals(action)) {
                        String query = req.optString("query", "");
                        if (!query.isEmpty() && rtcHolder[0] != null) {
                            new Thread(() -> performSearch(query, rtcHolder[0])).start();
                        }
                    } else if ("get_device_info".equals(action)) {
                        try {
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "device_info");
                            res.put("device_name", android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
                            
                            android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                            android.content.Intent batteryStatus = registerReceiver(null, ifilter);
                            if (batteryStatus != null) {
                                int level = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1);
                                int scale = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1);
                                float batteryPct = level * 100 / (float)scale;
                                res.put("battery", (int)batteryPct);
                                
                                int status = batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1);
                                boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                                     status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                                res.put("charging", isCharging);
                            }
                            
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    } else if ("get_storage_stats".equals(action)) {
                        try {
                            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
                            long totalBytes = stat.getTotalBytes();
                            long freeBytes = stat.getAvailableBytes();
                            long usedBytes = totalBytes - freeBytes;
                            
                            long imageBytes = getMediaStoreSize(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null);
                            long videoBytes = getMediaStoreSize(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null);
                            long audioBytes = getMediaStoreSize(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null);
                            long docsBytes  = getMediaStoreSize(android.provider.MediaStore.Files.getContentUri("external"), "mime_type LIKE 'application/pdf' OR mime_type LIKE 'text/%'");
                            
                            long otherBytes = usedBytes - (imageBytes + videoBytes + audioBytes + docsBytes);
                            if (otherBytes < 0) otherBytes = 0;

                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "storage_stats");
                            res.put("total", totalBytes / (1024*1024*1024));
                            res.put("used", usedBytes / (1024*1024*1024));
                            res.put("images", imageBytes / (1024*1024*1024));
                            res.put("videos", videoBytes / (1024*1024*1024));
                            res.put("docs", (docsBytes + audioBytes) / (1024*1024*1024));
                            res.put("other", otherBytes / (1024*1024*1024));
                            
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e){}
                    } else if ("get_host_info".equals(action)) {
                        try {
                            android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                            android.content.Intent batteryStatus = registerReceiver(null, ifilter);
                            int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
                            int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) : -1;
                            int status = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) : -1;
                            boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
                                                 status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                            float batteryPct = level * 100 / (float)scale;
                            
                            org.json.JSONObject res = new org.json.JSONObject();
                            res.put("action", "host_info");
                            res.put("manufacturer", android.os.Build.MANUFACTURER);
                            res.put("model", android.os.Build.MODEL);
                            res.put("batteryLevel", (int)batteryPct);
                            res.put("isCharging", isCharging);
                            if (rtcHolder[0] != null) rtcHolder[0].sendData(res.toString());
                        } catch (Exception e) {}
                    }
                } catch (Exception e) {
                    Log.e("StoreMeNative", "Failed to parse WebRTC msg", e);
                }
            }

            @Override
            public void onDataChannelBinary(java.nio.ByteBuffer buffer) {
                // Handle incoming binary if client uploads
            }

            @Override
            public void onDataChannelOpen() {
                activePeers.put(clientId, rtcHolder[0]);
                Log.i("StoreMeNative", "DataChannel Open for client: " + clientId + " | Total: " + activePeers.size());
            }

            @Override
            public void onDisconnected() {
                activePeers.remove(clientId);
                Log.i("StoreMeNative", "DataChannel Closed for client: " + clientId);
            }
        });

        rtcHolder[0] = rtcManager;
        rtcManager.createPeerConnection();

        // Listen for Client ICE Candidates
        dbRef.child(clientId).child("candidates").child("client").addChildEventListener(new com.google.firebase.database.ChildEventListener() {
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

        // Listen for Client Offer
        dbRef.child(clientId).child("offer").addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                String clientPassword = snapshot.child("password").getValue(String.class);
                String clientUsername = snapshot.child("username").getValue(String.class);
                if (clientUsername == null) clientUsername = "admin"; // backwards compatibility
                
                boolean authenticated = false;
                
                android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                
                if (clientPassword != null && clientPassword.equals(password)) {
                    authenticated = true;
                    connectedUsername[0] = clientUsername;
                    
                    // Add admin to Existing Users
                    try {
                        String usersJson = prefs.getString("host_users", "[]");
                        org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                        boolean found = false;
                        for (int i=0; i<arr.length(); i++) {
                            if (arr.getJSONObject(i).optString("username").equals(clientUsername)) {
                                found = true; break;
                            }
                        }
                        if (!found) {
                            org.json.JSONObject newAdmin = new org.json.JSONObject();
                            newAdmin.put("username", clientUsername);
                            newAdmin.put("password", clientPassword);
                            org.json.JSONArray perms = new org.json.JSONArray();
                            perms.put("/");
                            newAdmin.put("permissions", perms);
                            arr.put(newAdmin);
                            prefs.edit().putString("host_users", arr.toString()).apply();
                        }
                    } catch (Exception e) {}
                } else {
                    String usersJson = prefs.getString("host_users", "[]");
                    try {
                        org.json.JSONArray arr = new org.json.JSONArray(usersJson);
                        for (int i=0; i<arr.length(); i++) {
                            org.json.JSONObject u = arr.getJSONObject(i);
                            if (u.optString("username").equals(clientUsername) && u.optString("password").equals(clientPassword)) {
                                authenticated = true;
                                connectedUsername[0] = clientUsername;
                                break;
                            }
                        }
                    } catch (Exception e) {}
                }
                
                // 3. Check Share Links
                if (!authenticated) {
                    try {
                        org.json.JSONArray links = new org.json.JSONArray(prefs.getString("share_links", "[]"));
                        long now = System.currentTimeMillis();
                        org.json.JSONArray newLinks = new org.json.JSONArray();
                        for (int i=0; i<links.length(); i++) {
                            org.json.JSONObject link = links.getJSONObject(i);
                            if (now > link.optLong("expiresAt", 0)) continue; // Expired, remove it
                            
                            if (link.optString("token").equals(clientPassword)) {
                                authenticated = true;
                                connectedUsername[0] = "share_user_" + clientPassword;
                                // Add virtual temporary user
                                org.json.JSONArray arr = new org.json.JSONArray(prefs.getString("host_users", "[]"));
                                org.json.JSONObject vUser = new org.json.JSONObject();
                                vUser.put("username", connectedUsername[0]);
                                org.json.JSONArray perms = new org.json.JSONArray();
                                perms.put(link.optString("path"));
                                vUser.put("permissions", perms);
                                arr.put(vUser);
                                prefs.edit().putString("host_users", arr.toString()).apply();
                            }
                            newLinks.put(link);
                        }
                        prefs.edit().putString("share_links", newLinks.toString()).apply();
                    } catch (Exception e) {}
                }

                if (authenticated) {
                    Log.i("StoreMeNative", "User authenticated: " + clientUsername + " for client: " + clientId);
                    String sdp = snapshot.child("sdp").getValue(String.class);
                    String type = snapshot.child("type").getValue(String.class);
                    
                    org.webrtc.SessionDescription offer = new org.webrtc.SessionDescription(
                            org.webrtc.SessionDescription.Type.fromCanonicalForm(type), sdp);
                            
                    rtcManager.setRemoteOfferAndCreateAnswer(offer, new org.webrtc.SdpObserver() {
                        @Override
                        public void onCreateSuccess(org.webrtc.SessionDescription answer) {
                            dbRef.child(clientId).child("answer").setValue(new SessionPayload(answer.type.canonicalForm(), answer.description));
                        }
                        @Override public void onSetSuccess() {}
                        @Override public void onSetFailure(String s) {}
                        @Override public void onCreateFailure(String s) {}
                    });
                } else {
                    Log.e("StoreMeNative", "Invalid credentials from client: " + clientId);
                }
            }
            @Override
            public void onCancelled(DatabaseError error) {}
        });
    }

    private void streamFileOverWebRTC(String path, WebRTCManager rtcManager, String intentStr, long offset) {
        java.io.File file = new java.io.File(path);
        try {
            org.json.JSONObject meta = new org.json.JSONObject();
            meta.put("action", "file_transfer_start");
            meta.put("name", file.getName());
            meta.put("path", path);
            meta.put("size", file.length());
            meta.put("offset", offset);
            meta.put("intent", intentStr);
            rtcManager.sendData(meta.toString());
            
            // Sleep slightly to ensure JSON arrives before binary chunks
            Thread.sleep(100);
            
            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r")) {
                if (offset > 0) {
                    raf.seek(offset);
                }
                
                byte[] buffer = new byte[65535]; // WebRTC DataChannel max chunk size
                int bytesRead;
                while ((bytesRead = raf.read(buffer)) != -1) {
                    if (Boolean.FALSE.equals(transferStatus.get(path))) {
                        Log.i("StoreMeNative", "Stream paused/stopped: " + path);
                        break;
                    }
                    
                    if (bytesRead < buffer.length) {
                        byte[] exactChunk = new byte[bytesRead];
                        System.arraycopy(buffer, 0, exactChunk, 0, bytesRead);
                        rtcManager.sendBinaryData(exactChunk);
                    } else {
                        rtcManager.sendBinaryData(buffer);
                    }
                    // Yield thread to prevent overflowing DataChannel buffer natively
                    Thread.sleep(5); 
                }
                
                org.json.JSONObject endMeta = new org.json.JSONObject();
                endMeta.put("action", "file_transfer_end");
                endMeta.put("path", path);
                endMeta.put("intent", intentStr);
                rtcManager.sendData(endMeta.toString());
                
                Log.i("StoreMeNative", "File streaming complete: " + path);
            }
        } catch (Exception e) {
            Log.e("StoreMeNative", "Streaming failed", e);
        } finally {
            activeTransfers.remove(path);
        }
    }

    public static class CandidatePayload {
        public String candidate;
        public String sdpMid;
        public int sdpMLineIndex;
        public CandidatePayload(String candidate, String sdpMid, int sdpMLineIndex) {
            this.candidate = candidate; this.sdpMid = sdpMid; this.sdpMLineIndex = sdpMLineIndex;
        }
    }

    public static class SessionPayload {
        public String type;
        public String sdp;
        public SessionPayload(String type, String sdp) {
            this.type = type; this.sdp = sdp;
        }
    }

    private long getMediaStoreSize(android.net.Uri uri, String selection) {
        long size = 0;
        try {
            android.database.Cursor c = getContentResolver().query(uri, new String[] { "_size" }, selection, null, null);
            if (c != null) {
                int idx = c.getColumnIndex("_size");
                if (idx != -1) {
                    while (c.moveToNext()) {
                        size += c.getLong(idx);
                    }
                }
                c.close();
            }
        } catch(Exception e) {}
        return size;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "StoreMe Native Service",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) {
                manager.createNotificationChannel(serviceChannel);
            }
        }
    }

    @Nullable
    private void deleteRecursively(java.io.File f) {
        if (f.isDirectory()) {
            for (java.io.File child : f.listFiles()) {
                deleteRecursively(child);
            }
        }
        f.delete();
    }

    private void zipDirectory(java.io.File dirToZip) {
        if (!dirToZip.exists() || !dirToZip.isDirectory()) return;
        try {
            java.io.File zipFile = new java.io.File(dirToZip.getParent(), dirToZip.getName() + ".zip");
            java.io.FileOutputStream fos = new java.io.FileOutputStream(zipFile);
            java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(fos);
            zipDirRecursive(dirToZip, dirToZip, zos);
            zos.close();
            fos.close();
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    private void zipDirRecursive(java.io.File rootDir, java.io.File currDir, java.util.zip.ZipOutputStream zos) throws Exception {
        java.io.File[] files = currDir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.isDirectory()) {
                zipDirRecursive(rootDir, f, zos);
            } else {
                String path = f.getAbsolutePath().substring(rootDir.getAbsolutePath().length() + 1);
                java.util.zip.ZipEntry ze = new java.util.zip.ZipEntry(path);
                zos.putNextEntry(ze);
                java.io.FileInputStream fis = new java.io.FileInputStream(f);
                byte[] buffer = new byte[8192];
                int len;
                while ((len = fis.read(buffer)) > 0) zos.write(buffer, 0, len);
                fis.close();
                zos.closeEntry();
            }
        }
    }

    private void unzipFile(java.io.File zipFile) {
        if (!zipFile.exists() || !zipFile.getName().endsWith(".zip")) return;
        try {
            java.io.File destDir = new java.io.File(zipFile.getParent(), zipFile.getName().replace(".zip", ""));
            if (!destDir.exists()) destDir.mkdirs();
            
            java.io.FileInputStream fis = new java.io.FileInputStream(zipFile);
            java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(fis);
            java.util.zip.ZipEntry ze = zis.getNextEntry();
            
            while (ze != null) {
                java.io.File newFile = new java.io.File(destDir, ze.getName());
                if (ze.isDirectory()) {
                    newFile.mkdirs();
                } else {
                    new java.io.File(newFile.getParent()).mkdirs();
                    java.io.FileOutputStream fos = new java.io.FileOutputStream(newFile);
                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = zis.read(buffer)) > 0) fos.write(buffer, 0, len);
                    fos.close();
                }
                zis.closeEntry();
                ze = zis.getNextEntry();
            }
            zis.close();
            fis.close();
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void performSearch(String query, WebRTCManager rtcManager) {
        try {
            java.util.List<java.io.File> results = new java.util.ArrayList<>();
            searchRecursive(android.os.Environment.getExternalStorageDirectory(), query.toLowerCase(), results);
            
            int chunkSize = 50;
            int totalChunks = (int) Math.ceil((double) results.size() / chunkSize);
            if (totalChunks == 0) totalChunks = 1;
            
            for (int c = 0; c < totalChunks; c++) {
                org.json.JSONArray batchArray = new org.json.JSONArray();
                int start = c * chunkSize;
                int end = Math.min(start + chunkSize, results.size());
                for (int i = start; i < end; i++) {
                    java.io.File f = results.get(i);
                    org.json.JSONObject fileObj = new org.json.JSONObject();
                    fileObj.put("name", f.getName());
                    fileObj.put("path", f.getAbsolutePath());
                    fileObj.put("isDir", f.isDirectory());
                    fileObj.put("size", f.length());
                                        fileObj.put("lastModified", f.lastModified());
                    batchArray.put(fileObj);
                }
                
                org.json.JSONObject res = new org.json.JSONObject();
                res.put("action", "search_results");
                res.put("query", query);
                res.put("files", batchArray);
                res.put("chunk", c + 1);
                res.put("total_chunks", totalChunks);
                rtcManager.sendData(res.toString());
            }
        } catch (Exception e) { e.printStackTrace(); }
    }
    
    private void searchRecursive(java.io.File dir, String query, java.util.List<java.io.File> results) {
        if (results.size() >= 200) return; // Limit search results
        java.io.File[] files = dir.listFiles();
        if (files == null) return;
        for (java.io.File f : files) {
            if (f.getName().toLowerCase().contains(query)) results.add(f);
            if (f.isDirectory() && results.size() < 200) {
                searchRecursive(f, query, results);
            }
        }
    }

    private void buildSyncManifest(java.io.File dir, String rootPath, org.json.JSONArray files) {
        java.io.File[] list = dir.listFiles();
        if (list == null) return;
        for (java.io.File f : list) {
            if (f.isDirectory()) {
                buildSyncManifest(f, rootPath, files);
            } else {
                try {
                    org.json.JSONObject obj = new org.json.JSONObject();
                    String relativePath = f.getAbsolutePath().substring(rootPath.length());
                    if (relativePath.startsWith("/")) relativePath = relativePath.substring(1);
                    obj.put("relPath", relativePath);
                    obj.put("size", f.length());
                    obj.put("lastModified", f.lastModified());
                    files.put(obj);
                } catch (Exception e) {}
            }
        }
    }

    private void scanFilesForCleaning(java.io.File dir, org.json.JSONArray files, long now) {
        if (files.length() > 50) return; // Limit for remote
        java.io.File[] list = dir.listFiles();
        if (list == null) return;
        
        long sixMonths = 180L * 24 * 60 * 60 * 1000;
        
        for (java.io.File f : list) {
            if (f.isDirectory()) {
                if (f.getName().equalsIgnoreCase("thumbnails") || f.getName().equalsIgnoreCase("cache") || f.getName().equalsIgnoreCase("tmp")) {
                    try {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "App Cache / Junk Directory");
                        files.put(obj);
                    } catch (Exception e) {}
                } else {
                    scanFilesForCleaning(f, files, now);
                }
            } else {
                String name = f.getName().toLowerCase();
                try {
                    if (name.endsWith(".tmp") || name.endsWith(".log") || name.endsWith(".bak")) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "Temporary / Junk File");
                        files.put(obj);
                    } else if (f.length() > 50 * 1024 * 1024 && (now - f.lastModified()) > sixMonths) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "Large old file (>50MB, >6 months)");
                        files.put(obj);
                    } else if (name.contains("copy") || name.contains("(1)")) {
                        org.json.JSONObject obj = new org.json.JSONObject();
                        obj.put("path", f.getAbsolutePath());
                        obj.put("reason", "Likely Duplicate");
                        files.put(obj);
                    }
                } catch (Exception e) {}
            }
        }
    }

    private void deleteRecursive(java.io.File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            java.io.File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (java.io.File child : children) deleteRecursive(child);
            }
        }
        fileOrDirectory.delete();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
    
    @Override
    public void onDestroy() {
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        if (wifiLock != null && wifiLock.isHeld()) {
            wifiLock.release();
        }
        if (streamServer != null) {
            streamServer.stop();
        }
        if (hostId != null) {
            FirebaseDatabase.getInstance().getReference("rooms").child(hostId).removeValue();
        }
        super.onDestroy();
        Log.i("StoreMeNative", "Service Destroyed");
    }
}
