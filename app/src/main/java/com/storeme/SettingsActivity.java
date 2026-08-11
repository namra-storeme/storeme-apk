package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        String hostId = getIntent().getStringExtra("HOST_ID");

        // Host ID card - safe
        TextView textHostId = findViewById(R.id.textHostId);
        if (textHostId != null) textHostId.setText("Host ID: " + hostId);

        // Phone model - safe
        TextView textHostPhone = findViewById(R.id.textHostPhone);
        TextView textHostBattery = findViewById(R.id.textHostBattery);
        
        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String activeRole = prefs.getString("active_role", null);
        
        if ("host".equals(activeRole)) {
            if (textHostPhone != null) textHostPhone.setText(android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL);
            if (textHostBattery != null) {
                try {
                    android.content.IntentFilter ifilter = new android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED);
                    android.content.Intent batteryStatus = registerReceiver(null, ifilter);
                    int level = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) : -1;
                    int scale = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1) : -1;
                    int status = batteryStatus != null ? batteryStatus.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) : -1;
                    boolean isCharging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING || status == android.os.BatteryManager.BATTERY_STATUS_FULL;
                    float pct = level * 100 / (float)scale;
                    textHostBattery.setText((int)pct + "% " + (isCharging ? "⚡ Charging" : ""));
                } catch (Exception e) {}
            }
        } else {
            // Client - fetch from Host
            ClientConnectionManager.getInstance().addListener(new ClientConnectionManager.ClientListener() {
                @Override
                public void onConnected() {
                    try {
                        org.json.JSONObject req = new org.json.JSONObject();
                        req.put("action", "get_host_info");
                        ClientConnectionManager.getInstance().sendData(req.toString());
                    } catch (Exception e) {}
                }

                @Override
                public void onMessage(org.json.JSONObject msg) {
                    if ("host_info".equals(msg.optString("action"))) {
                        runOnUiThread(() -> {
                            String mfg = msg.optString("manufacturer", "");
                            String mdl = msg.optString("model", "");
                            int bat = msg.optInt("batteryLevel", -1);
                            boolean chr = msg.optBoolean("isCharging", false);
                            
                            if (textHostPhone != null) textHostPhone.setText(mfg + " " + mdl);
                            if (textHostBattery != null) {
                                textHostBattery.setText(bat + "% " + (chr ? "⚡ Charging" : ""));
                            }
                        });
                    }
                }
                
                @Override public void onBinary(java.nio.ByteBuffer buffer) {}
                @Override public void onDisconnected() {}
            });
        }

        // Stop server button
        View btnStop = findViewById(R.id.btnStopServer);
        if (btnStop != null) {
            btnStop.setOnClickListener(v -> {
                SharedPreferences btnPrefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                String role = btnPrefs.getString("active_role", null);
                if ("host".equals(role)) {
                    stopService(new Intent(this, HostForegroundService.class));
                } else {
                    // Client: disconnect WebRTC
                    ClientConnectionManager.getInstance().disconnect();
                }
                btnPrefs.edit().clear().commit();
                Intent intent = new Intent(this, MainActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                overridePendingTransition(0, 0);
                finish();
            });
        }

        android.widget.Switch switchBiometric = findViewById(R.id.switchBiometric);
        if (switchBiometric != null) {
            switchBiometric.setChecked(prefs.getBoolean("require_biometric", false));
            switchBiometric.setOnCheckedChangeListener((buttonView, isChecked) -> {
                prefs.edit().putBoolean("require_biometric", isChecked).apply();
            });
        }

        setupBottomNav(hostId);
    }

    /**
     * The bottom nav in activity_settings.xml is an <include> with id bottomNavInclude.
     * When you use <include android:id="...">, the child IDs from the included layout
     * are still findable via the activity's findViewById because Android merges them.
     * We add null checks as safety net.
     */
    private void setupBottomNav(String hostId) {
        View navHome = findViewById(R.id.navHome);
        if (navHome != null) {
            navHome.setOnClickListener(v -> {
                SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                String role = prefs.getString("active_role", null);
                Intent intent;
                if ("host".equals(role)) {
                    intent = new Intent(this, DashboardActivity.class);
                } else {
                    intent = new Intent(this, ClientDashboardActivity.class);
                }
                intent.putExtra("HOST_ID", hostId);
                startActivity(intent);
                overridePendingTransition(0, 0);
                finish();
            });
        }

        View navFiles = findViewById(R.id.navFiles);
        if (navFiles != null) {
            navFiles.setOnClickListener(v -> {
                Intent intent = new Intent(this, FileManagerActivity.class);
                intent.putExtra("HOST_ID", hostId);
                startActivity(intent);
                overridePendingTransition(0, 0);
                finish();
            });
        }

        View navActivity = findViewById(R.id.navActivity);
        if (navActivity != null) {
            navActivity.setOnClickListener(v -> {
                Intent intent = new Intent(this, ActivityLogActivity.class);
                intent.putExtra("HOST_ID", hostId);
                startActivity(intent);
                overridePendingTransition(0, 0);
                finish();
            });
        }
    }

    @Override
    public void onBackPressed() {
        android.widget.Toast.makeText(this,
                "Tap 'Stop Using Server' to disconnect.", android.widget.Toast.LENGTH_SHORT).show();
    }
}
