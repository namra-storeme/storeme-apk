package com.storeme;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.database.ValueEventListener;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

public class ManageUsersActivity extends AppCompatActivity {

    private LinearLayout containerRequests;
    private LinearLayout containerUsers;
    private SharedPreferences prefs;
    private DatabaseReference requestsRef;
    
    private static final int REQUEST_CODE_PICK_FOLDER = 101;
    
    // State tracking for folder picker returns
    private String pendingRequestId = null;
    private String pendingRequestUsername = null;
    private String pendingEditUsername = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_manage_users);

        prefs = getSharedPreferences("StoreMePrefs", MODE_PRIVATE);
        String hostId = prefs.getString("host_id", "");

        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        
        containerRequests = findViewById(R.id.containerRequests);
        containerUsers = findViewById(R.id.containerUsers);

        requestsRef = FirebaseDatabase.getInstance().getReference("rooms").child(hostId).child("requests");
        
        // Listen to requests
        requestsRef.addValueEventListener(new ValueEventListener() {
            @Override
            public void onDataChange(DataSnapshot snapshot) {
                renderRequests(snapshot);
            }
            @Override
            public void onCancelled(DatabaseError error) {}
        });

        renderUsers();
    }

    private void renderRequests(DataSnapshot snapshot) {
        containerRequests.removeAllViews();
        boolean hasPending = false;
        
        for (DataSnapshot reqSnap : snapshot.getChildren()) {
            String status = reqSnap.child("status").getValue(String.class);
            if (!"pending".equals(status)) continue;
            
            hasPending = true;
            String reqId = reqSnap.getKey();
            String username = reqSnap.child("username").getValue(String.class);

            View itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_1, containerRequests, false);
            itemView.setBackgroundColor(0xFFFFFFFF);
            itemView.setPadding(32, 32, 32, 32);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, 16);
            itemView.setLayoutParams(lp);

            TextView text1 = itemView.findViewById(android.R.id.text1);
            text1.setText("Request from: " + username);
            text1.setTextColor(0xFF0F172A);
            text1.setTextSize(16);

            itemView.setOnClickListener(v -> showAcceptDialog(reqId, username));
            
            containerRequests.addView(itemView);
        }
        
        if (!hasPending) {
            TextView tv = new TextView(this);
            tv.setText("No pending requests");
            tv.setTextColor(0xFF94A3B8);
            containerRequests.addView(tv);
        }
    }

    private void showAcceptDialog(String reqId, String username) {
        CharSequence[] options = {"Access entire storage", "Access specific storage", "Reject Request"};
        new AlertDialog.Builder(this)
            .setTitle("Accept Request for " + username)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    approveRequest(reqId, username, "/");
                } else if (which == 1) {
                    pendingRequestId = reqId;
                    pendingRequestUsername = username;
                    pendingEditUsername = null; // ensure not in edit mode
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
                } else if (which == 2) {
                    requestsRef.child(reqId).child("status").setValue("rejected");
                }
            })
            .show();
    }

    private void showEditUserDialog(String username) {
        CharSequence[] options = {"Change to Entire Storage", "Change to Specific Storage", "Remove User"};
        new AlertDialog.Builder(this)
            .setTitle("Manage User: " + username)
            .setItems(options, (dialog, which) -> {
                if (which == 0) {
                    updateUserPermission(username, "/");
                } else if (which == 1) {
                    pendingEditUsername = username;
                    pendingRequestId = null;
                    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                    startActivityForResult(intent, REQUEST_CODE_PICK_FOLDER);
                } else if (which == 2) {
                    removeUser(username);
                }
            })
            .show();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_PICK_FOLDER && resultCode == RESULT_OK && data != null) {
            Uri treeUri = data.getData();
            if (treeUri != null) {
                String path = getFullPathFromTreeUri(treeUri);
                if (path == null) {
                    Toast.makeText(this, "Failed to resolve folder path. Ensure it's on internal storage.", Toast.LENGTH_LONG).show();
                    return;
                }
                
                if (pendingRequestId != null) {
                    approveRequest(pendingRequestId, pendingRequestUsername, path);
                    pendingRequestId = null;
                    pendingRequestUsername = null;
                } else if (pendingEditUsername != null) {
                    updateUserPermission(pendingEditUsername, path);
                    pendingEditUsername = null;
                }
            }
        }
    }

    private void approveRequest(String reqId, String username, String path) {
        String token = UUID.randomUUID().toString().substring(0, 8); // random password
        
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            
            // Remove existing user if same name
            JSONArray newArr = new JSONArray();
            for (int i=0; i<usersArr.length(); i++) {
                if (!usersArr.getJSONObject(i).optString("username").equals(username)) {
                    newArr.put(usersArr.getJSONObject(i));
                }
            }
            
            JSONObject newUser = new JSONObject();
            newUser.put("username", username);
            newUser.put("password", token);
            JSONArray permsArray = new JSONArray();
            permsArray.put(path);
            newUser.put("permissions", permsArray);
            
            newArr.put(newUser);
            prefs.edit().putString("host_users", newArr.toString()).apply();
            
            // Notify client via Firebase
            requestsRef.child(reqId).child("token").setValue(token);
            requestsRef.child(reqId).child("status").setValue("accepted");
            
            Toast.makeText(this, "User accepted!", Toast.LENGTH_SHORT).show();
            renderUsers();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateUserPermission(String username, String path) {
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            for (int i=0; i<usersArr.length(); i++) {
                JSONObject u = usersArr.getJSONObject(i);
                if (u.optString("username").equals(username)) {
                    JSONArray permsArray = new JSONArray();
                    permsArray.put(path);
                    u.put("permissions", permsArray);
                    break;
                }
            }
            prefs.edit().putString("host_users", usersArr.toString()).apply();
            Toast.makeText(this, "Permissions updated", Toast.LENGTH_SHORT).show();
            renderUsers();
        } catch (Exception e) {}
    }

    private void removeUser(String username) {
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            JSONArray newArr = new JSONArray();
            for (int i=0; i<usersArr.length(); i++) {
                if (!usersArr.getJSONObject(i).optString("username").equals(username)) {
                    newArr.put(usersArr.getJSONObject(i));
                }
            }
            prefs.edit().putString("host_users", newArr.toString()).apply();
            Toast.makeText(this, "User removed", Toast.LENGTH_SHORT).show();
            renderUsers();
        } catch (Exception e) {}
    }

    private void renderUsers() {
        containerUsers.removeAllViews();
        try {
            JSONArray usersArr = new JSONArray(prefs.getString("host_users", "[]"));
            for (int i=0; i<usersArr.length(); i++) {
                JSONObject u = usersArr.getJSONObject(i);
                final String username = u.optString("username");
                JSONArray perms = u.optJSONArray("permissions");
                StringBuilder permStr = new StringBuilder();
                if (perms != null) {
                    for (int j=0; j<perms.length(); j++) {
                        if (j > 0) permStr.append(", ");
                        permStr.append(perms.getString(j));
                    }
                }

                View itemView = LayoutInflater.from(this).inflate(android.R.layout.simple_list_item_2, containerUsers, false);
                itemView.setBackgroundColor(0xFFFFFFFF);
                itemView.setPadding(32, 32, 32, 32);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, 16);
                itemView.setLayoutParams(lp);

                TextView text1 = itemView.findViewById(android.R.id.text1);
                TextView text2 = itemView.findViewById(android.R.id.text2);
                
                text1.setText(username);
                text1.setTextColor(0xFF0F172A);
                text1.setTextSize(16);
                
                text2.setText("Allowed: " + (permStr.toString().equals("/") ? "Entire Storage" : permStr.toString()));
                text2.setTextColor(0xFF64748B);

                if (!"admin".equals(username)) {
                    itemView.setOnClickListener(v -> showEditUserDialog(username));
                }

                containerUsers.addView(itemView);
            }
            
            if (usersArr.length() == 0 || (usersArr.length() == 1 && usersArr.getJSONObject(0).optString("username").equals("admin"))) {
                TextView tv = new TextView(this);
                tv.setText("No external users added yet");
                tv.setTextColor(0xFF94A3B8);
                containerUsers.addView(tv);
            }
            
        } catch (Exception e) {}
    }

    private String getFullPathFromTreeUri(Uri treeUri) {
        if (treeUri == null) return null;
        String docId = DocumentsContract.getTreeDocumentId(treeUri);
        String[] split = docId.split(":");
        String type = split[0];
        String path = "";
        if (split.length > 1) {
            path = split[1];
        }

        if ("primary".equalsIgnoreCase(type)) {
            return Environment.getExternalStorageDirectory() + "/" + path;
        } else {
            // Emulated external storage devices
            return "/storage/" + type + "/" + path;
        }
    }
}
