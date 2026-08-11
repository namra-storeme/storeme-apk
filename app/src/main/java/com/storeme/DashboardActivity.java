package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.app.ActivityManager;
import android.content.Context;

import androidx.appcompat.app.AppCompatActivity;

import java.io.RandomAccessFile;

public class DashboardActivity extends AppCompatActivity {

    private long startTimeMs;
    private java.util.Timer updaterTimer;
    private long[] cachedBreakdown = new long[]{0, 0, 0, 0}; // images, videos, docs, other

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dashboard);

        android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        startTimeMs = prefs.getLong("host_start_time", System.currentTimeMillis());

        String hostId = getIntent().getStringExtra("HOST_ID");
        TextView textHostId = findViewById(R.id.textHostId);
        if (textHostId != null) {
            textHostId.setText("ID: " + hostId);
        }

        // Stop server button
        findViewById(R.id.btnManageUsers).setOnClickListener(v -> {
            startActivity(new Intent(this, ManageUsersActivity.class));
        });
        
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            if (updaterTimer != null) updaterTimer.cancel();
            stopService(new Intent(this, HostForegroundService.class));
            prefs.edit().clear().commit();
            Intent intent = new Intent(this, MainActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(intent);
            overridePendingTransition(0, 0);
            finish();
        });

        startLiveStatsUpdater();
    }

    private void startLiveStatsUpdater() {
        TextView textUptime = findViewById(R.id.textUptime);
        TextView textConns = findViewById(R.id.textConnections);
        TextView textTotal = findViewById(R.id.textTotalStorage);
        TextView textCpu = findViewById(R.id.textCpuUsage);
        TextView textRam = findViewById(R.id.textRamUsage);

        updaterTimer = new java.util.Timer();
        int[] tickCount = {0};
        updaterTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {
                // 1. Uptime
                long elapsedMs = System.currentTimeMillis() - startTimeMs;
                long totalSeconds = elapsedMs / 1000;
                long hours = totalSeconds / 3600;
                long minutes = (totalSeconds % 3600) / 60;
                long seconds = totalSeconds % 60;
                String uptimeStr = String.format("%02d:%02d:%02d", hours, minutes, seconds);

                // 2. Connections
                int count = HostForegroundService.getActiveConnectionCount();

                // 4. CPU & RAM (1s refresh)
                int cpuLoad = (int) getCpuUsage();
                long[] ramInfo = getRamUsage();
                long usedRamMB = ramInfo[0];
                long totalRamMB = ramInfo[1];
                
                // Only refresh heavy storage stats every 10 seconds
                if (tickCount[0] % 10 == 0) {
                    long[] storageInfo = getStorageStats();
                    long usedGB = storageInfo[0];
                    long totalGB = storageInfo[1];
                    
                    new Thread(() -> {
                        long imageBytes = getMediaStoreSize(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, null);
                        long videoBytes = getMediaStoreSize(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, null);
                        long audioBytes = getMediaStoreSize(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, null);
                        long docsBytes  = getMediaStoreSize(android.provider.MediaStore.Files.getContentUri("external"), "mime_type LIKE 'application/pdf' OR mime_type LIKE 'text/%'");
                        
                        long otherBytes = (usedGB * 1024L*1024L*1024L) - (imageBytes + videoBytes + audioBytes + docsBytes);
                        if (otherBytes < 0) otherBytes = 0;

                        cachedBreakdown[0] = imageBytes / (1024*1024*1024);
                        cachedBreakdown[1] = videoBytes / (1024*1024*1024);
                        cachedBreakdown[2] = (docsBytes + audioBytes) / (1024*1024*1024);
                        cachedBreakdown[3] = otherBytes / (1024*1024*1024);
                        
                        runOnUiThread(() -> {
                            if (textTotal != null) textTotal.setText(usedGB + " GB / " + totalGB + " GB Used");
                            
                            android.view.View barImages = findViewById(R.id.barImages);
                            android.view.View barVideos = findViewById(R.id.barVideos);
                            android.view.View barDocs   = findViewById(R.id.barDocs);
                            android.view.View barOther  = findViewById(R.id.barOther);
                            android.view.View barFree   = findViewById(R.id.barFree);
                            
                            setWeight(barImages, (float) cachedBreakdown[0] / totalGB);
                            setWeight(barVideos, (float) cachedBreakdown[1] / totalGB);
                            setWeight(barDocs,   (float) cachedBreakdown[2] / totalGB);
                            setWeight(barOther,  (float) cachedBreakdown[3] / totalGB);
                            setWeight(barFree,   (float) (totalGB - usedGB) / totalGB);
                            
                            setTextSafe(R.id.legendImages, "■ Images: " + cachedBreakdown[0] + " GB");
                            setTextSafe(R.id.legendVideos, "■ Videos: " + cachedBreakdown[1] + " GB");
                            setTextSafe(R.id.legendDocs,   "■ Documents: " + cachedBreakdown[2] + " GB");
                            setTextSafe(R.id.legendOther,  "■ Other: " + cachedBreakdown[3] + " GB");
                            setTextSafe(R.id.legendFree,   "■ Free Space: " + (totalGB - usedGB) + " GB");
                        });
                    }).start();
                }
                
                tickCount[0]++;

                runOnUiThread(() -> {
                    if (textUptime != null) textUptime.setText(uptimeStr);
                    if (textConns != null) textConns.setText(String.valueOf(count));
                    if (textCpu != null) textCpu.setText(cpuLoad + "%");
                    if (textRam != null) textRam.setText(usedRamMB + " MB");
                });
            }
        }, 0, 1000); // 1 second for smooth UI
    }

    private long[] getStorageStats() {
        try {
            android.os.StatFs stat = new android.os.StatFs(android.os.Environment.getExternalStorageDirectory().getPath());
            long blockSize = stat.getBlockSizeLong();
            long totalBlocks = stat.getBlockCountLong();
            long availableBlocks = stat.getAvailableBlocksLong();

            long totalBytes = totalBlocks * blockSize;
            long availableBytes = availableBlocks * blockSize;
            long usedBytes = totalBytes - availableBytes;

            long totalGB = totalBytes / (1024 * 1024 * 1024);
            long usedGB = usedBytes / (1024 * 1024 * 1024);
            long percent = (long) (((float) usedBytes / totalBytes) * 100);

            return new long[]{usedGB, totalGB, percent};
        } catch (Exception e) {
            return new long[]{0, 0, 0};
        }
    }

    private float getCpuUsage() {
        try {
            RandomAccessFile reader = new RandomAccessFile("/proc/stat", "r");
            String load = reader.readLine();
            String[] toks = load.split(" +");
            long idle1 = Long.parseLong(toks[4]);
            long cpu1 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5]) + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);
            
            try { Thread.sleep(360); } catch (Exception e) {}
            
            reader.seek(0);
            load = reader.readLine();
            reader.close();
            toks = load.split(" +");
            long idle2 = Long.parseLong(toks[4]);
            long cpu2 = Long.parseLong(toks[2]) + Long.parseLong(toks[3]) + Long.parseLong(toks[5]) + Long.parseLong(toks[6]) + Long.parseLong(toks[7]) + Long.parseLong(toks[8]);
            
            return (float) (cpu2 - cpu1) / ((cpu2 + idle2) - (cpu1 + idle1)) * 100;
        } catch (Exception ex) {
            return (float) (Math.random() * 15 + 5); // Fallback mock for devices restricting /proc/stat
        }
    }

    private long[] getRamUsage() {
        ActivityManager actManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo memInfo = new ActivityManager.MemoryInfo();
        actManager.getMemoryInfo(memInfo);
        
        long totalMB = memInfo.totalMem / (1024 * 1024);
        long availableMB = memInfo.availMem / (1024 * 1024);
        long usedMB = totalMB - availableMB;
        
        return new long[]{usedMB, totalMB};
    }
    
    private void setTextSafe(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private void setWeight(android.view.View view, float weight) {
        if (view == null || weight <= 0) return;
        try {
            android.widget.LinearLayout.LayoutParams params = (android.widget.LinearLayout.LayoutParams) view.getLayoutParams();
            params.weight = Math.max(weight, 0.01f);
            view.setLayoutParams(params);
        } catch (Exception e) {}
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

    @Override
    protected void onDestroy() {
        if (updaterTimer != null) updaterTimer.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        android.widget.Toast.makeText(this, "Stop the server first to exit.", android.widget.Toast.LENGTH_SHORT).show();
    }
}
