package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

public class UseServerActivity extends AppCompatActivity {

    private Button btnConnect;
    private ProgressBar progressLoading;
    private TextView textLoading;
    private boolean isConnecting = false;
    private Button btnRequestAccess;
    private ValueEventListener requestListener;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_use_server);

        EditText editConnectId = findViewById(R.id.editConnectId);
        EditText editConnectUsername = findViewById(R.id.editConnectUsername);
        EditText editConnectPassword = findViewById(R.id.editConnectPassword);
        btnConnect = findViewById(R.id.btnConnect);
        btnRequestAccess = findViewById(R.id.btnRequestAccess);
        progressLoading = findViewById(R.id.progressLoading);
        textLoading = findViewById(R.id.textLoading);

        // Back button goes back to landing
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> finish());

        btnConnect.setOnClickListener(v -> {
            if (isConnecting) return;
            String hostId = editConnectId.getText().toString().trim();
            String tempUsername = editConnectUsername.getText().toString().trim();
            final String finalUsername = tempUsername.isEmpty() ? "admin" : tempUsername;
            String password = editConnectPassword.getText().toString().trim();

            if (hostId.isEmpty()) {
                editConnectId.setError("Please enter a server address");
                return;
            }
            if (password.isEmpty()) {
                editConnectPassword.setError("Please enter a password");
                btnRequestAccess.setVisibility(View.VISIBLE);
                return;
            }

            btnRequestAccess.setVisibility(View.GONE);
            connectToServer(hostId, finalUsername, password);
        });

        btnRequestAccess.setOnClickListener(v -> {
            if (isConnecting) return;
            String hostId = editConnectId.getText().toString().trim();
            String tempUsername = editConnectUsername.getText().toString().trim();
            final String finalUsername = tempUsername.isEmpty() ? "guest" : tempUsername;

            if (hostId.isEmpty()) {
                editConnectId.setError("Please enter a server address");
                return;
            }

            requestAccessFromHost(hostId, finalUsername);
        });
    }

    private void requestAccessFromHost(String hostId, String username) {
        setLoadingState(true);
        textLoading.setText("Waiting for host to accept...");
        
        String requestId = java.util.UUID.randomUUID().toString();
        DatabaseReference reqRef = FirebaseDatabase.getInstance()
                .getReference("rooms").child(hostId).child("requests").child(requestId);
        
        java.util.Map<String, Object> reqData = new java.util.HashMap<>();
        reqData.put("username", username);
        reqData.put("status", "pending");
        reqRef.setValue(reqData);

        requestListener = new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                if (!snapshot.exists()) return;
                String status = snapshot.child("status").getValue(String.class);
                if ("accepted".equals(status)) {
                    reqRef.removeEventListener(this);
                    String token = snapshot.child("token").getValue(String.class);
                    if (token != null) {
                        runOnUiThread(() -> {
                            Toast.makeText(UseServerActivity.this, "Request Accepted!", Toast.LENGTH_SHORT).show();
                            connectToServer(hostId, username, token);
                        });
                    }
                } else if ("rejected".equals(status)) {
                    reqRef.removeEventListener(this);
                    runOnUiThread(() -> {
                        setLoadingState(false);
                        Toast.makeText(UseServerActivity.this, "❌ Host rejected your request.", Toast.LENGTH_LONG).show();
                    });
                }
            }

            @Override
            public void onCancelled(DatabaseError error) {
                runOnUiThread(() -> {
                    setLoadingState(false);
                    Toast.makeText(UseServerActivity.this, "Request failed: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                });
            }
        };
        reqRef.addValueEventListener(requestListener);
    }

    private void connectToServer(String hostId, String finalUsername, String password) {
        setLoadingState(true);
        textLoading.setText("Connecting securely...");

        android.os.Handler timeoutHandler = new android.os.Handler(android.os.Looper.getMainLooper());
        Runnable timeoutRunnable = () -> {
            if (isConnecting) {
                setLoadingState(false);
                Toast.makeText(UseServerActivity.this, "Connection timed out. Check your internet or host server status.", Toast.LENGTH_LONG).show();
            }
        };
        timeoutHandler.postDelayed(timeoutRunnable, 10000);

        DatabaseReference ref = FirebaseDatabase.getInstance()
                .getReference("rooms").child(hostId);

        ref.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (!isConnecting) return;

                if (!snapshot.exists()) {
                    runOnUiThread(() -> {
                        setLoadingState(false);
                        Toast.makeText(UseServerActivity.this,
                                "❌ Server not found or is offline. Check the address.",
                                Toast.LENGTH_LONG).show();
                    });
                    return;
                }

                runOnUiThread(() -> {
                    android.content.SharedPreferences prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
                    prefs.edit()
                            .putString("active_role", "client")
                            .putString("host_id", hostId)
                            .putString("username", finalUsername)
                            .putString("password", password)
                            .apply();

                    ClientConnectionManager.getInstance().connect(
                            getApplicationContext(), hostId, finalUsername, password);

                    Intent intent = new Intent(UseServerActivity.this, ClientDashboardActivity.class);
                    intent.putExtra("HOST_ID", hostId);
                    intent.putExtra("PASSWORD", password);
                    startActivity(intent);
                    finish();
                });
            }

            @Override
            public void onCancelled(DatabaseError error) {
                timeoutHandler.removeCallbacks(timeoutRunnable);
                if (!isConnecting) return;
                
                runOnUiThread(() -> {
                    setLoadingState(false);
                    Toast.makeText(UseServerActivity.this,
                            "Connection failed: " + error.getMessage(),
                            Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void setLoadingState(boolean loading) {
        isConnecting = loading;
        btnConnect.setEnabled(!loading);
        if (btnRequestAccess != null) btnRequestAccess.setEnabled(!loading);
        btnConnect.setText(loading ? "Connecting..." : "ESTABLISH CONNECTION");
        if (progressLoading != null) progressLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
        if (textLoading != null) textLoading.setVisibility(loading ? View.VISIBLE : View.GONE);
    }
}
