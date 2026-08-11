package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import org.json.JSONObject;
import java.util.Timer;
import java.util.TimerTask;

public class ClientDashboardActivity extends AppCompatActivity {
    
    private Timer speedTimer;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_client_dashboard);

        String hostId = getIntent().getStringExtra("HOST_ID");
        setupBottomNav(hostId);

        // Listen for storage stats from host
        ClientConnectionManager.getInstance().addListener(new ClientConnectionManager.ClientListener() {
            @Override
            public void onConnected() {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "get_storage_stats");
                    ClientConnectionManager.getInstance().sendData(req.toString());
                } catch (Exception e) { e.printStackTrace(); }
            }

            @Override
            public void onMessage(JSONObject res) {
                if ("storage_stats".equals(res.optString("action"))) {
                    runOnUiThread(() -> renderRealStorage(res));
                }
            }

            @Override
            public void onBinary(java.nio.ByteBuffer buffer) {}

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    Toast.makeText(ClientDashboardActivity.this, "Connection lost or host shutdown.", Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });
        
        TextView textClientSpeed = findViewById(R.id.textClientSpeed);
        if (textClientSpeed != null) {
            speedTimer = new Timer();
            speedTimer.scheduleAtFixedRate(new TimerTask() {
                @Override
                public void run() {
                    long totalSpeed = 0;
                    for (ClientConnectionManager.DownloadTask t : ClientConnectionManager.getInstance().getDownloads()) {
                        if ("Downloading".equals(t.status)) {
                            totalSpeed += t.speed;
                        }
                    }
                    String speedStr = (totalSpeed / 1024) + " KB/s";
                    if (totalSpeed > 1024 * 1024) {
                        speedStr = String.format("%.1f MB/s", (float)totalSpeed / (1024 * 1024));
                    }
                    final String s = speedStr;
                    runOnUiThread(() -> textClientSpeed.setText(s));
                }
            }, 0, 1000);
        }
        
    }
    
    @Override
    protected void onDestroy() {
        if (speedTimer != null) speedTimer.cancel();
        super.onDestroy();
    }

    private void renderRealStorage(JSONObject stats) {
        try {
            long total = stats.optLong("total", 1);
            long used  = stats.optLong("used", 0);
            if (total == 0) total = 1; // avoid divide-by-zero

            long images = stats.optLong("images", 0);
            long videos = stats.optLong("videos", 0);
            long docs   = stats.optLong("docs",   0);
            long other  = stats.optLong("other",  0);
            long free   = total - used;

            TextView textClientStorage = findViewById(R.id.textClientStorage);
            if (textClientStorage != null) {
                textClientStorage.setText(used + " GB / " + total + " GB Used");
            }

            View barImages = findViewById(R.id.barImages);
            View barVideos = findViewById(R.id.barVideos);
            View barDocs   = findViewById(R.id.barDocs);
            View barOther  = findViewById(R.id.barOther);
            View barFree   = findViewById(R.id.barFree);

            setWeight(barImages, (float) images / total);
            setWeight(barVideos, (float) videos / total);
            setWeight(barDocs,   (float) docs   / total);
            setWeight(barOther,  (float) other  / total);
            setWeight(barFree,   (float) free   / total);

            setTextSafe(R.id.legendImages, "🔵 Images: "   + images + " GB");
            setTextSafe(R.id.legendVideos, "🔴 Videos: "   + videos + " GB");
            setTextSafe(R.id.legendDocs,   "🟠 Documents: " + docs  + " GB");
            setTextSafe(R.id.legendOther,  "🟢 Other: "    + other  + " GB");
            setTextSafe(R.id.legendFree,   "⚪ Free: "      + free   + " GB");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void setTextSafe(int id, String text) {
        TextView tv = findViewById(id);
        if (tv != null) tv.setText(text);
    }

    private void setWeight(View view, float weight) {
        if (view == null || weight <= 0) return;
        try {
            LinearLayout.LayoutParams params = (LinearLayout.LayoutParams) view.getLayoutParams();
            params.weight = Math.max(weight, 0.01f);
            view.setLayoutParams(params);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void setupBottomNav(String hostId) {
        // Bottom nav tabs (IDs come from included layout_bottom_nav.xml)
        View navHome = findViewById(R.id.navHome);
        if (navHome != null) navHome.setOnClickListener(v -> { /* already here, no-op */ });

        View navFiles = findViewById(R.id.navFiles);
        if (navFiles != null) {
            navFiles.setOnClickListener(v -> {
                navigateTo(FileManagerActivity.class, hostId);
                finish();
            });
        }

        View navActivity = findViewById(R.id.navActivity);
        if (navActivity != null) {
            navActivity.setOnClickListener(v -> {
                navigateTo(ActivityLogActivity.class, hostId);
                finish();
            });
        }

        View navSettings = findViewById(R.id.navSettings);
        if (navSettings != null) {
            navSettings.setOnClickListener(v -> {
                navigateTo(SettingsActivity.class, hostId);
                finish();
            });
        }
    }

    private void navigateTo(Class<?> cls, String hostId) {
        Intent intent = new Intent(this, cls);
        intent.putExtra("HOST_ID", hostId);
        startActivity(intent);
        overridePendingTransition(0, 0);
    }

    @Override
    public void onBackPressed() {
        android.widget.Toast.makeText(this,
                "Stop using server in Settings to exit.", android.widget.Toast.LENGTH_SHORT).show();
    }
}
