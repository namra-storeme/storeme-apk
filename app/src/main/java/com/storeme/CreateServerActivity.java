package com.storeme;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;

import androidx.appcompat.app.AppCompatActivity;

public class CreateServerActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative_DarkAuth);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_create_server);

        EditText editHostId = findViewById(R.id.editHostId);
        EditText editPassword = findViewById(R.id.editPassword);
        Button btnCreate = findViewById(R.id.btnCreate);

        btnCreate.setOnClickListener(v -> {
            String hostId = editHostId.getText().toString().trim();
            String password = editPassword.getText().toString().trim();

            if (hostId.isEmpty() || password.isEmpty()) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!android.os.Environment.isExternalStorageManager()) {
                    try {
                        Intent intent = new Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.addCategory("android.intent.category.DEFAULT");
                        intent.setData(android.net.Uri.parse(String.format("package:%s", getApplicationContext().getPackageName())));
                        startActivity(intent);
                    } catch (Exception e) {
                        Intent intent = new Intent();
                        intent.setAction(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                        startActivity(intent);
                    }
                    return; // Stop and wait for them to grant it
                }
            }

            Intent serviceIntent = new Intent(this, HostForegroundService.class);
            serviceIntent.putExtra("HOST_ID", hostId);
            serviceIntent.putExtra("PASSWORD", password);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }

            android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
            
            try {
                org.json.JSONArray usersArr = new org.json.JSONArray();
                org.json.JSONObject adminUser = new org.json.JSONObject();
                adminUser.put("username", "admin");
                adminUser.put("password", password);
                org.json.JSONArray perms = new org.json.JSONArray();
                perms.put("/"); // Root access
                adminUser.put("permissions", perms);
                usersArr.put(adminUser);
                prefs.edit().putString("host_users", usersArr.toString()).apply();
            } catch (Exception e) {}

            prefs.edit().putString("active_role", "host")
                        .putString("host_id", hostId)
                        .putString("password", password).apply();

            Intent dashIntent = new Intent(this, DashboardActivity.class);
            dashIntent.putExtra("HOST_ID", hostId);
            startActivity(dashIntent);
            finish();
        });
    }
}
