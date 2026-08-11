package com.storeme;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

public class MainActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative_DarkAuth);
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String role = prefs.getString("active_role", null);
        String hostId = prefs.getString("host_id", "");
        String password = prefs.getString("password", "");
        String username = prefs.getString("username", "admin");

        boolean requireBiometric = prefs.getBoolean("require_biometric", false);

        if ("host".equals(role) || "client".equals(role)) {
            if (requireBiometric) {
                showBiometricPrompt(role, hostId, username, password);
            } else {
                proceedToApp(role, hostId, username, password);
            }
            return;
        }

        setContentView(R.layout.activity_landing);

        findViewById(R.id.btnCreateServer).setOnClickListener(v -> {
            startActivity(new Intent(this, CreateServerActivity.class));
        });

        findViewById(R.id.btnUseServer).setOnClickListener(v -> {
            startActivity(new Intent(this, UseServerActivity.class));
        });
    }

    private void showBiometricPrompt(String role, String hostId, String username, String password) {
        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(MainActivity.this,
                executor, new BiometricPrompt.AuthenticationCallback() {
            @Override
            public void onAuthenticationError(int errorCode, CharSequence errString) {
                super.onAuthenticationError(errorCode, errString);
                // On error, let them re-try or clear preferences to force manual login
                android.widget.Toast.makeText(getApplicationContext(),
                        "Authentication error: " + errString, android.widget.Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onAuthenticationSucceeded(BiometricPrompt.AuthenticationResult result) {
                super.onAuthenticationSucceeded(result);
                proceedToApp(role, hostId, username, password);
            }

            @Override
            public void onAuthenticationFailed() {
                super.onAuthenticationFailed();
                android.widget.Toast.makeText(getApplicationContext(), "Authentication failed",
                        android.widget.Toast.LENGTH_SHORT).show();
            }
        });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock StoreMe")
                .setSubtitle("Enterprise Biometric Security")
                .setNegativeButtonText("Cancel")
                .build();

        biometricPrompt.authenticate(promptInfo);
    }

    private void proceedToApp(String role, String hostId, String username, String password) {
        if ("host".equals(role)) {
            Intent intent = new Intent(this, DashboardActivity.class);
            intent.putExtra("HOST_ID", hostId);
            startActivity(intent);
            finish();
        } else if ("client".equals(role)) {
            ClientConnectionManager.getInstance().connect(getApplicationContext(), hostId, username, password);
            

            Intent intent = new Intent(this, ClientDashboardActivity.class);
            intent.putExtra("HOST_ID", hostId);
            intent.putExtra("PASSWORD", password);
            startActivity(intent);
            finish();
        }
    }
}
