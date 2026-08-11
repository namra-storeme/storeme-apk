package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Context;

public class FileManagerActivity extends AppCompatActivity {

    // State
    private JSONArray allFiles    = new JSONArray();
    private JSONArray accumulated = new JSONArray();
    private String    currentPath = "";
    private String    activeChip  = "all";
    private boolean   sortAZ      = true;
    private boolean   gridMode    = false;
    private boolean   searchOpen  = false;

    // Views
    private RecyclerView recycler;
    private FileAdapter  adapter;
    private TextView     textStatus, textCount, btnSort;
    private View         searchBar;
    private EditText     editSearch;

    // Download state
    private java.io.File             currentFile;
    private String                   currentIntent;
    private String                   pendingMovePath = null;
    private String                   pendingMoveName = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        String hostId = getIntent().getStringExtra("HOST_ID");

        // Bind views
        textStatus = findViewById(R.id.textStatus);
        searchBar  = findViewById(R.id.searchBar);
        editSearch = findViewById(R.id.editSearch);
        recycler   = findViewById(R.id.recyclerFiles);

        recycler.setLayoutManager(new LinearLayoutManager(this));

        // Adapter
        adapter = new FileAdapter(new FileAdapter.OnFileClickListener() {
            @Override
            public void onFileClick(JSONObject file) {
                if (file.optBoolean("isDir")) {
                    loadDirectory(file.optString("path"));
                } else {
                    openFile(file);
                }
            }
            @Override
            public void onMenuClick(View anchor, JSONObject file) {
                showFileMenu(anchor, file);
            }
        });
        recycler.setAdapter(adapter);

        // Back button → go up directory
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> goUp());

        // Search toggle
        View btnSearchIcon = findViewById(R.id.btnSearch);
        if (btnSearchIcon != null) {
            btnSearchIcon.setOnClickListener(v -> {
                searchOpen = !searchOpen;
                searchBar.setVisibility(searchOpen ? View.VISIBLE : View.GONE);
                if (!searchOpen) { editSearch.setText(""); refresh(); }
            });
        }
        if (editSearch != null) {
            editSearch.addTextChangedListener(new TextWatcher() {
                public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
                public void afterTextChanged(Editable s) {}
                public void onTextChanged(CharSequence s, int a, int b, int c) { refresh(); }
            });
        }

        // Grid/List toggle
        View btnGrid = findViewById(R.id.btnGridList);
        if (btnGrid != null) btnGrid.setOnClickListener(v -> {
            gridMode = !gridMode;
            recycler.setLayoutManager(gridMode
                    ? new GridLayoutManager(this, 3)
                    : new LinearLayoutManager(this));
            refresh();
        });

        // Sort
        if (btnSort != null) btnSort.setOnClickListener(v -> {
            sortAZ = !sortAZ;
            btnSort.setText("Sort: " + (sortAZ ? "A→Z" : "Z→A"));
            refresh();
        });

        // Filter chips
        setupChips();
        
        // Move Banner setup
        View bannerMove = findViewById(R.id.bannerMove);
        TextView textMoveFileName = findViewById(R.id.textMoveFileName);
        findViewById(R.id.btnCancelMove).setOnClickListener(v -> {
            pendingMovePath = null;
            pendingMoveName = null;
            bannerMove.setVisibility(View.GONE);
        });
        findViewById(R.id.btnMoveHere).setOnClickListener(v -> {
            if (pendingMovePath != null) {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "move");
                    req.put("path", pendingMovePath); // Old path
                    req.put("newPath", currentPath + "/" + pendingMoveName);
                    ClientConnectionManager.getInstance().sendData(req.toString());
                    Toast.makeText(this, "Moving...", Toast.LENGTH_SHORT).show();
                    pendingMovePath = null;
                    pendingMoveName = null;
                    bannerMove.setVisibility(View.GONE);
                    new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1000);
                } catch (Exception e) {}
            }
        });

        // WebRTC listener
        ClientConnectionManager.getInstance().addListener(new ClientConnectionManager.ClientListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    safeText(textStatus, "Connected ✓");
                    loadDirectory(currentPath);
                });
            }

            @Override
            public void onMessage(JSONObject msg) {
                runOnUiThread(() -> handleMessage(msg));
            }

            @Override
            public void onBinary(java.nio.ByteBuffer buf) {
                // Handled in ClientConnectionManager
            }

            @Override
            public void onDisconnected() {
                runOnUiThread(() -> {
                    Toast.makeText(FileManagerActivity.this, "Connection lost or host shutdown.", Toast.LENGTH_LONG).show();
                    finish();
                });
            }
        });

        if (ClientConnectionManager.getInstance().isConnected()) {
            loadDirectory(currentPath);
        }

        // Bottom nav
        wireNav(hostId);
    }

    // ─── Directory handling ──────────────────────────────────────────────────

    private void loadDirectory(String path) {
        safeText(textStatus, "Loading...");
        try {
            JSONObject req = new JSONObject();
            req.put("action", "list_dir");
            req.put("path", path);
            ClientConnectionManager.getInstance().sendData(req.toString());
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void handleMessage(JSONObject msg) {
        try {
            String action = msg.optString("action");
            
            if ("file_transfer_start".equals(action)) {
                String intentStr = msg.optString("intent");
                if ("stream".equals(intentStr)) {
                    String url = "http://127.0.0.1:8080/stream?path=" + android.net.Uri.encode(msg.optString("path"));
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setData(android.net.Uri.parse(url)); // No MIME type to skip chooser
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    intent.setPackage("com.android.chrome");
                    try {
                        startActivity(intent);
                    } catch (Exception ex) {
                        intent.setPackage(null);
                        startActivity(intent);
                    }
                }
                return;
            }

            if ("access_denied".equals(action)) {
                String deniedPath = msg.optString("path");
                Toast.makeText(this, "Access Denied: " + deniedPath, Toast.LENGTH_SHORT).show();
                if (deniedPath.equals("/") || deniedPath.equals(currentPath)) {
                    // Host revoked access to root or current folder. Terminate session.
                    ClientConnectionManager.getInstance().disconnect();
                    Toast.makeText(this, "Your access has been revoked by the host.", Toast.LENGTH_SHORT).show();
                    finish();
                }
                return;
            }
            
            if ("link_created".equals(action)) {
                String token = msg.optString("token");
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                android.content.ClipData clip = android.content.ClipData.newPlainText("Share Token", token);
                clipboard.setPrimaryClip(clip);
                
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Share Link Created")
                    .setMessage(token + "\n\n(Valid for 24 hours)")
                    .setPositiveButton("Copy", (dialog, which) -> {
                        clipboard.setPrimaryClip(clip);
                        Toast.makeText(this, "Copied!", Toast.LENGTH_SHORT).show();
                    })
                    .setNegativeButton("Close", null)
                    .show();
                return;
            }

            if ("directory_listing".equals(action)) {
                int chunk  = msg.optInt("chunk", 1);
                int total  = msg.optInt("total_chunks", 1);
                String path = msg.optString("path", currentPath);
                currentPath = path;

                if (chunk == 1) accumulated = new JSONArray();
                JSONArray files = msg.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    accumulated.put(files.getJSONObject(i));
                }

                if (chunk == total) {
                    buildMasterList();
                    refresh();
                    safeText(textStatus, path);
                } else {
                    safeText(textStatus, "Loading " + chunk + "/" + total + "...");
                }

            } else if ("search_results".equals(action)) {
                int chunk  = msg.optInt("chunk", 1);
                int total  = msg.optInt("total_chunks", 1);
                String query = msg.optString("query");
                
                if (chunk == 1) accumulated = new JSONArray();
                JSONArray files = msg.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    accumulated.put(files.getJSONObject(i));
                }

                if (chunk == total) {
                    allFiles = accumulated; // Directly use as master list since we don't have "parent folder" for search
                    refresh();
                    safeText(textStatus, "Search: " + query + " (" + allFiles.length() + ")");
                } else {
                    safeText(textStatus, "Searching " + chunk + "/" + total + "...");
                }

            } else if ("file_transfer_start".equals(action)) {
                String name = msg.optString("name");
                currentIntent = msg.optString("intent");
                currentFile = "preview".equals(currentIntent)
                        ? new java.io.File(getCacheDir(), name)
                        : new java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS), name);
                if ("preview".equals(currentIntent)) safeText(textStatus, "↓ " + name);
            } else if ("file_transfer_end".equals(action)) {
                if ("preview".equals(currentIntent) && currentFile != null) {
                    previewFile(currentFile);
                } else if ("download".equals(currentIntent)) {
                    Toast.makeText(this, "✓ Saved to Downloads", Toast.LENGTH_SHORT).show();
                }
            } else if ("link_created".equals(action)) {
                String tokenUrl = msg.optString("token");
                runOnUiThread(() -> {
                    Toast.makeText(FileManagerActivity.this, "Link Generated: " + tokenUrl, Toast.LENGTH_LONG).show();
                    // Copy to clipboard
                    android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    android.content.ClipData clip = android.content.ClipData.newPlainText("StoreMe Link", tokenUrl);
                    clipboard.setPrimaryClip(clip);
                    Toast.makeText(FileManagerActivity.this, "Link copied to clipboard!", Toast.LENGTH_SHORT).show();
                });
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void buildMasterList() {
        allFiles = new JSONArray();
        try {
            boolean isRoot = currentPath.equals("/storage/emulated/0/")
                    || currentPath.equals("/storage/emulated/0")
                    || currentPath.equals("/");
            if (!isRoot) {
                java.io.File f = new java.io.File(currentPath);
                String parent = f.getParent();
                if (parent == null) parent = "/storage/emulated/0/";
                JSONObject back = new JSONObject();
                back.put("name", "⬅  Parent folder");
                back.put("path", parent);
                back.put("isDir", true);
                back.put("isBack", true);
                allFiles.put(back);
            }
            for (int i = 0; i < accumulated.length(); i++) {
                allFiles.put(accumulated.getJSONObject(i));
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    private void goUp() {
        if (!currentPath.equals("/storage/emulated/0/") && !currentPath.equals("/")) {
            java.io.File f = new java.io.File(currentPath);
            String parent = f.getParent();
            if (parent != null) { loadDirectory(parent); return; }
        }
        Toast.makeText(this, "Already at root directory", Toast.LENGTH_SHORT).show();
    }

    // ─── Filter + Sort ──────────────────────────────────────────────────────

    private void refresh() {
        String query = (editSearch != null && editSearch.getText() != null)
                ? editSearch.getText().toString().toLowerCase().trim() : "";

        java.util.List<JSONObject> dirs  = new java.util.ArrayList<>();
        java.util.List<JSONObject> files = new java.util.ArrayList<>();
        JSONObject backEntry = null;

        try {
            for (int i = 0; i < allFiles.length(); i++) {
                JSONObject f = allFiles.getJSONObject(i);
                if (f.optBoolean("isBack")) { backEntry = f; continue; }

                String name  = f.optString("name").toLowerCase();
                boolean isDir = f.optBoolean("isDir");

                // Search
                if (!query.isEmpty() && !name.contains(query)) continue;

                // Category
                if (!passesFilter(name, isDir)) continue;

                if (isDir) dirs.add(f); else files.add(f);
            }
        } catch (Exception e) { e.printStackTrace(); }

        java.util.Comparator<JSONObject> byName = (a, b) -> {
            String na = a.optString("name");
            String nb = b.optString("name");
            return sortAZ ? na.compareToIgnoreCase(nb) : nb.compareToIgnoreCase(na);
        };
        dirs.sort(byName);
        files.sort(byName);

        JSONArray result = new JSONArray();
        try {
            if (backEntry != null) result.put(backEntry);
            for (JSONObject d : dirs)  result.put(d);
            for (JSONObject f : files) result.put(f);
        } catch (Exception e) { e.printStackTrace(); }

        adapter.setFiles(result);

        int count = result.length() - (backEntry != null ? 1 : 0);
        safeText(textCount, count + " item" + (count == 1 ? "" : "s"));
    }

    private boolean passesFilter(String name, boolean isDir) {
        switch (activeChip) {
            case "images": return isDir || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".png") || name.endsWith(".gif")
                    || name.endsWith(".webp") || name.endsWith(".bmp") || name.endsWith(".heic");
            case "videos": return isDir || name.endsWith(".mp4") || name.endsWith(".mkv")
                    || name.endsWith(".avi") || name.endsWith(".mov")
                    || name.endsWith(".3gp") || name.endsWith(".webm");
            case "docs":   return isDir || name.endsWith(".pdf") || name.endsWith(".doc")
                    || name.endsWith(".docx") || name.endsWith(".xls") || name.endsWith(".xlsx")
                    || name.endsWith(".ppt") || name.endsWith(".pptx") || name.endsWith(".txt")
                    || name.endsWith(".csv");
            case "audio":  return isDir || name.endsWith(".mp3") || name.endsWith(".aac")
                    || name.endsWith(".flac") || name.endsWith(".wav")
                    || name.endsWith(".ogg") || name.endsWith(".m4a");
            default: return true;
        }
    }

    // ─── Chips ──────────────────────────────────────────────────────────────

    private void setupChips() {
        int[][] chips = {
            {R.id.chipAll,    0},
            {R.id.chipImages, 0},
            {R.id.chipVideos, 0},
            {R.id.chipDocs,   0},
            {R.id.chipAudio,  0}
        };
        String[] keys = {"all", "images", "videos", "docs", "audio"};
        int[] chipIds = {R.id.chipAll, R.id.chipImages, R.id.chipVideos, R.id.chipDocs, R.id.chipAudio};

        for (int i = 0; i < chipIds.length; i++) {
            TextView chip = findViewById(chipIds[i]);
            if (chip == null) continue;
            final String key = keys[i];
            chip.setOnClickListener(v -> {
                activeChip = key;
                for (int id : chipIds) {
                    TextView c = findViewById(id);
                    if (c == null) continue;
                    boolean active = c == v;
                    c.setBackgroundColor(active ? 0xFF0F172A : 0xFFF1F5F9);
                    c.setTextColor(active ? 0xFFFFFFFF : 0xFF475569);
                }
                refresh();
            });
        }
        
        TextView chipTrash = findViewById(R.id.chipTrash);
        if (chipTrash != null) {
            chipTrash.setOnClickListener(v -> {
                loadDirectory("/storage/emulated/0/.storeme_trash");
                activeChip = "all";
                refresh();
            });
        }
        
        View btnUpload = findViewById(R.id.btnUpload);
        if (btnUpload != null) {
            btnUpload.setOnClickListener(v -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                startActivityForResult(intent, 1002);
            });
        }
        
        View btnSearch = findViewById(R.id.btnSearch);
        View searchBar = findViewById(R.id.searchBar);
        android.widget.EditText editSearch = findViewById(R.id.editSearch);
        
        if (btnSearch != null && searchBar != null && editSearch != null) {
            btnSearch.setOnClickListener(v -> {
                if (searchBar.getVisibility() == View.VISIBLE) {
                    searchBar.setVisibility(View.GONE);
                    editSearch.setText("");
                    loadDirectory(currentPath); // Reset to current dir
                } else {
                    searchBar.setVisibility(View.VISIBLE);
                    editSearch.requestFocus();
                }
            });
            
            editSearch.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
                @Override public void afterTextChanged(android.text.Editable s) {
                    String query = s.toString().trim();
                    if (query.length() >= 2) {
                        try {
                            JSONObject req = new JSONObject();
                            req.put("action", "search");
                            req.put("query", query);
                            ClientConnectionManager.getInstance().sendData(req.toString());
                            safeText(textStatus, "Searching...");
                        } catch (Exception e) {}
                    } else if (query.isEmpty()) {
                        loadDirectory(currentPath);
                    }
                }
            });
        }
    }

    // ─── File Actions ────────────────────────────────────────────────────────

    private void openFile(JSONObject file) {
        try {
            JSONObject req = new JSONObject();
            req.put("action", "stream");
            req.put("path", file.optString("path"));
            ClientConnectionManager.getInstance().sendData(req.toString());
            Toast.makeText(this, "Starting stream...", Toast.LENGTH_SHORT).show();
        } catch (Exception ex) {
            Toast.makeText(this, "Failed to start stream", Toast.LENGTH_SHORT).show();
        }
    }

    private void showFileMenu(View anchor, JSONObject file) {
        if (file.optBoolean("isBack")) return;
        
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_file_menu, null);
        dialog.setContentView(view);
        
        TextView textTitle = view.findViewById(R.id.textMenuTitle);
        textTitle.setText(file.optString("name"));
        
        boolean inTrash = currentPath.contains(".storeme_trash");
        boolean isDir = file.optBoolean("isDir");
        
        View btnDownload = view.findViewById(R.id.btnMenuDownload);
        View btnShare = view.findViewById(R.id.btnMenuShare);
        View btnZip = view.findViewById(R.id.btnMenuZip);
        TextView textZip = view.findViewById(R.id.textMenuZip);
        View btnDetails = view.findViewById(R.id.btnMenuDetails);
        View btnRestore = view.findViewById(R.id.btnMenuRestore);
        View btnTrash = view.findViewById(R.id.btnMenuTrash);
        TextView textTrash = view.findViewById(R.id.textMenuTrash);
        
        if (inTrash) {
            textTrash.setText("Delete Permanently");
            btnZip.setVisibility(View.GONE);
            btnShare.setVisibility(View.GONE);
            btnDownload.setVisibility(View.GONE);
            btnRestore.setVisibility(View.VISIBLE);
        } else {
            if (isDir) {
                textZip.setText("Compress to Zip");
                btnDownload.setVisibility(View.GONE);
            } else {
                if (file.optString("name", "").toLowerCase().endsWith(".zip")) {
                    textZip.setText("Extract Zip");
                } else {
                    btnZip.setVisibility(View.GONE);
                }
            }
        }

        btnDownload.setOnClickListener(v -> {
            dialog.dismiss();
            String fileName = file.optString("name");
            String mimeType = "*/*";
            int dotIndex = fileName.lastIndexOf('.');
            if (dotIndex > 0) mimeType = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(fileName.substring(dotIndex + 1).toLowerCase());
            if (mimeType == null) mimeType = "*/*";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType(mimeType);
            intent.putExtra(Intent.EXTRA_TITLE, fileName);
            getSharedPreferences("StoreMePrefs", MODE_PRIVATE).edit().putString("pending_download_path", file.optString("path")).apply();
            startActivityForResult(intent, 1001);
        });

        btnShare.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", "create_link");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, "Generating secure link...", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
        });
        
        btnZip.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", isDir ? "zip" : "unzip");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, isDir ? "Compression started on host" : "Extraction started on host", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {}
        });

        View btnMove = view.findViewById(R.id.btnMenuMove);
        if (btnMove != null) {
            btnMove.setVisibility(inTrash ? View.GONE : View.VISIBLE);
            btnMove.setOnClickListener(v -> {
                dialog.dismiss();
                pendingMovePath = file.optString("path");
                pendingMoveName = file.optString("name");
                View bannerMove = findViewById(R.id.bannerMove);
                TextView textMoveFileName = findViewById(R.id.textMoveFileName);
                if (bannerMove != null && textMoveFileName != null) {
                    textMoveFileName.setText("Move: " + pendingMoveName);
                    bannerMove.setVisibility(View.VISIBLE);
                }
            });
        }
        
        btnRestore.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", "restore");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, "Restoring file...", Toast.LENGTH_SHORT).show();
                new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1000);
            } catch (Exception e) {}
        });

        btnTrash.setOnClickListener(v -> {
            dialog.dismiss();
            try {
                JSONObject req = new JSONObject();
                req.put("action", inTrash ? "delete_permanent" : "delete");
                req.put("path", file.optString("path"));
                ClientConnectionManager.getInstance().sendData(req.toString());
                Toast.makeText(this, inTrash ? "Deleted Permanently" : "Moved to Trash", Toast.LENGTH_SHORT).show();
                new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1000);
            } catch (Exception e) {}
        });

        btnDetails.setOnClickListener(v -> {
            dialog.dismiss();
            showFileDetails(file);
        });
        
        dialog.show();
    }
    
    private void showFileDetails(JSONObject file) {
        com.google.android.material.bottomsheet.BottomSheetDialog dialog = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View view = getLayoutInflater().inflate(R.layout.dialog_file_details, null);
        dialog.setContentView(view);
        
        TextView txtName = view.findViewById(R.id.detailName);
        TextView txtType = view.findViewById(R.id.detailType);
        TextView txtSize = view.findViewById(R.id.detailSize);
        TextView txtPath = view.findViewById(R.id.detailPath);
        
        txtName.setText(file.optString("name"));
        txtPath.setText(file.optString("path"));
        if (file.optBoolean("isDir")) {
            txtType.setText("Folder");
            txtSize.setText("--");
        } else {
            String name = file.optString("name");
            int dotIndex = name.lastIndexOf('.');
            txtType.setText((dotIndex > 0) ? name.substring(dotIndex + 1).toUpperCase() + " File" : "Unknown File");
            
            long sizeBytes = file.optLong("size");
            if (sizeBytes > 1024 * 1024 * 1024) txtSize.setText(String.format("%.2f GB", sizeBytes / (1024f * 1024f * 1024f)));
            else if (sizeBytes > 1024 * 1024) txtSize.setText(String.format("%.2f MB", sizeBytes / (1024f * 1024f)));
            else if (sizeBytes > 1024) txtSize.setText(String.format("%.2f KB", sizeBytes / 1024f));
            else txtSize.setText(sizeBytes + " Bytes");
        }
        
        view.findViewById(R.id.btnCloseDetails).setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }

    private void previewFile(java.io.File file) {
        try {
            android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                    this, getApplicationContext().getPackageName() + ".fileprovider", file);
            
            String mimeType = "*/*";
            String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.getAbsolutePath());
            if (ext != null && !ext.isEmpty()) {
                String possibleMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                if (possibleMime != null) mimeType = possibleMime;
            }

            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(intent);
        } catch (Exception ex) {
            Toast.makeText(this, "No app to preview this file", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Navigation ──────────────────────────────────────────────────────────

    private void wireNav(String hostId) {
        View navHome = findViewById(R.id.navHome);
        if (navHome != null) navHome.setOnClickListener(v -> go(ClientDashboardActivity.class, hostId));

        View navActivity = findViewById(R.id.navActivity);
        if (navActivity != null) navActivity.setOnClickListener(v -> go(ActivityLogActivity.class, hostId));

        View navSettings = findViewById(R.id.navSettings);
        if (navSettings != null) navSettings.setOnClickListener(v -> go(SettingsActivity.class, hostId));
    }

    private void go(Class<?> cls, String hostId) {
        Intent intent = new Intent(this, cls);
        intent.putExtra("HOST_ID", hostId);
        startActivity(intent);
        overridePendingTransition(0, 0);
        finish();
    }

    @Override
    public void onBackPressed() {
        if (!currentPath.equals("/storage/emulated/0/") && !currentPath.equals("/")) {
            goUp();
        } else {
            Toast.makeText(this, "Stop using server in Settings to exit.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1001 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            String path = getSharedPreferences("StoreMePrefs", MODE_PRIVATE)
                    .getString("pending_download_path", null);
            if (path != null) {
                ClientConnectionManager.getInstance().startDownload(this, path, data.getData());
                Toast.makeText(this, "Download started!", Toast.LENGTH_SHORT).show();
                go(ActivityLogActivity.class, getIntent().getStringExtra("HOST_ID"));
            }
        } else if (requestCode == 1002 && resultCode == RESULT_OK && data != null && data.getData() != null) {
            android.net.Uri uri = data.getData();
            String fileName = "upload_" + System.currentTimeMillis();
            try {
                android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    fileName = cursor.getString(nameIndex);
                    cursor.close();
                }
            } catch (Exception e) {}
            ClientConnectionManager.getInstance().startUpload(this, uri, fileName, currentPath);
            Toast.makeText(this, "Upload started!", Toast.LENGTH_SHORT).show();
            go(ActivityLogActivity.class, getIntent().getStringExtra("HOST_ID"));
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private void safeText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }
}
