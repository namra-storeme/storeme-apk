package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;
import android.content.Context;
import java.util.HashSet;
import java.util.Set;
import java.util.ArrayList;

public class FileManagerActivity extends AppCompatActivity {

    // State
    private JSONArray allFiles    = new JSONArray();
    private JSONArray accumulated = new JSONArray();
    private String    currentPath = "";
    private String    activeChip  = "all";
    private boolean   sortAZ      = true;
    private boolean   gridMode    = false;
    private boolean   searchOpen  = false;
    
    // Selection state
    private boolean isSelectionMode = false;
    private Set<String> selectedFiles = new HashSet<>();
    private ArrayList<String> pendingMovePaths = new ArrayList<>();
    private boolean isMoveOperation = false; // true = move, false = copy

    // Views
    private RecyclerView recycler;
    private FileAdapter  adapter;
    private TextView     textStatus, textCount, textSortMode;
    private View         searchBar;
    private EditText     editSearch;
    
    // New UI Views
    private View topBarNormal, topBarSelection, layoutSelectionBottomBar, layoutFabs, bannerMove;
    private TextView textSelectedCount, textBreadcrumb;
    private ImageView imgSelectAll;

    // Download state
    private java.io.File currentFile;
    private String currentIntent;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_manager);

        String hostId = getIntent().getStringExtra("HOST_ID");

        // Bind views
        textStatus = findViewById(R.id.textStatus);
        editSearch = findViewById(R.id.editSearch);
        recycler   = findViewById(R.id.recyclerFiles);
        textSortMode = findViewById(R.id.textSortMode);
        
        topBarNormal = findViewById(R.id.topBarNormal);
        topBarSelection = findViewById(R.id.topBarSelection);
        layoutSelectionBottomBar = findViewById(R.id.layoutSelectionBottomBar);
        layoutFabs = findViewById(R.id.layoutFabs);
        textSelectedCount = findViewById(R.id.textSelectedCount);
        textBreadcrumb = findViewById(R.id.textBreadcrumb);
        imgSelectAll = findViewById(R.id.imgSelectAll);
        bannerMove = findViewById(R.id.bannerMove);

        recycler.setLayoutManager(new LinearLayoutManager(this));

        // Adapter
        adapter = new FileAdapter(new FileAdapter.OnFileClickListener() {
            @Override
            public void onFileClick(JSONObject file) {
                if (isSelectionMode) {
                    toggleFileSelection(file.optString("path"));
                } else {
                    if (file.optBoolean("isDir")) {
                        loadDirectory(file.optString("path"));
                    } else {
                        openFile(file);
                    }
                }
            }
            @Override
            public void onMenuClick(View anchor, JSONObject file) {
                // Not used in new design, files don't have individual 3-dots
            }
        });
        
        // Add long click listener for selection mode via adapter
        adapter.setOnFileLongClickListener(file -> {
            if (!isSelectionMode) {
                toggleSelectionMode(true);
                toggleFileSelection(file.optString("path"));
            }
        });
        
        recycler.setAdapter(adapter);

        // Path bar back button
        View btnBack = findViewById(R.id.btnBack);
        if (btnBack != null) btnBack.setOnClickListener(v -> goUp());

        // Search toggle
        View btnSearchIcon = findViewById(R.id.btnSearch);
        if (btnSearchIcon != null) {
            btnSearchIcon.setOnClickListener(v -> {
                searchOpen = !searchOpen;
                editSearch.setVisibility(searchOpen ? View.VISIBLE : View.GONE);
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

        // Global 3-dots Menu
        View btnMoreOptions = findViewById(R.id.btnMoreOptions);
        if (btnMoreOptions != null) {
            btnMoreOptions.setOnClickListener(v -> {
                android.widget.PopupMenu popup = new android.widget.PopupMenu(this, v);
                popup.getMenu().add(0, 1, 0, "Select");
                popup.getMenu().add(0, 2, 0, "View (Grid/List)");
                popup.getMenu().add(0, 3, 0, "Trash");
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == 1) {
                        toggleSelectionMode(true);
                    } else if (item.getItemId() == 2) {
                        gridMode = !gridMode;
                        recycler.setLayoutManager(gridMode
                                ? new GridLayoutManager(this, 3)
                                : new LinearLayoutManager(this));
                        refresh();
                    } else if (item.getItemId() == 3) {
                        loadDirectory(android.os.Environment.getExternalStorageDirectory().getAbsolutePath() + "/.storeme_trash");
                    }
                    return true;
                });
                popup.show();
            });
        }

        // Selection Mode Controls
        findViewById(R.id.btnCancelSelection).setOnClickListener(v -> toggleSelectionMode(false));
        findViewById(R.id.btnSelectAll).setOnClickListener(v -> {
            if (selectedFiles.size() == allFiles.length()) {
                selectedFiles.clear();
            } else {
                for (int i=0; i<allFiles.length(); i++) {
                    selectedFiles.add(allFiles.optJSONObject(i).optString("path"));
                }
            }
            updateSelectionUI();
            adapter.notifyDataSetChanged();
        });

        // Floating Bottom Bar Controls
        findViewById(R.id.btnSelectionMove).setOnClickListener(v -> {
            isMoveOperation = true;
            pendingMovePaths = new ArrayList<>(selectedFiles);
            toggleSelectionMode(false);
            bannerMove.setVisibility(View.VISIBLE);
            TextView txt = findViewById(R.id.textMoveFileName);
            txt.setText("Moving " + pendingMovePaths.size() + " items...");
        });
        
        findViewById(R.id.btnSelectionCopy).setOnClickListener(v -> {
            isMoveOperation = false;
            pendingMovePaths = new ArrayList<>(selectedFiles);
            toggleSelectionMode(false);
            bannerMove.setVisibility(View.VISIBLE);
            TextView txt = findViewById(R.id.textMoveFileName);
            txt.setText("Copying " + pendingMovePaths.size() + " items...");
        });
        
        findViewById(R.id.btnSelectionDelete).setOnClickListener(v -> {
            for (String p : selectedFiles) {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "delete");
                    req.put("path", p);
                    ClientConnectionManager.getInstance().sendData(req.toString());
                } catch (Exception e) {}
            }
            Toast.makeText(this, "Deleted " + selectedFiles.size() + " items", Toast.LENGTH_SHORT).show();
            toggleSelectionMode(false);
            new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1000);
        });
        
        findViewById(R.id.btnSelectionShare).setOnClickListener(v -> {
            if (selectedFiles.size() == 1) {
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "create_link");
                    req.put("path", selectedFiles.iterator().next());
                    ClientConnectionManager.getInstance().sendData(req.toString());
                } catch (Exception e) {}
                toggleSelectionMode(false);
            } else {
                Toast.makeText(this, "Please select exactly one item to share.", Toast.LENGTH_SHORT).show();
            }
        });
        
        findViewById(R.id.btnSelectionMore).setOnClickListener(v -> {
            if (selectedFiles.size() == 1) {
                // Zip functionality mapped to More for a single item for now
                try {
                    JSONObject req = new JSONObject();
                    req.put("action", "zip");
                    req.put("path", selectedFiles.iterator().next());
                    ClientConnectionManager.getInstance().sendData(req.toString());
                    Toast.makeText(this, "Zipping item...", Toast.LENGTH_SHORT).show();
                    toggleSelectionMode(false);
                    new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 2000);
                } catch (Exception e) {}
            } else {
                Toast.makeText(this, "More options available for single selection only.", Toast.LENGTH_SHORT).show();
            }
        });

        // Sort toggle
        View btnSortView = findViewById(R.id.btnSort);
        if (btnSortView != null) btnSortView.setOnClickListener(v -> {
            sortAZ = !sortAZ;
            textSortMode.setText(sortAZ ? "Type   |  ↑" : "Type   |  ↓");
            refresh();
        });
        
        // Move/Copy Banner setup
        findViewById(R.id.btnCancelMove).setOnClickListener(v -> {
            pendingMovePaths.clear();
            bannerMove.setVisibility(View.GONE);
        });
        findViewById(R.id.btnMoveHere).setOnClickListener(v -> {
            if (!pendingMovePaths.isEmpty()) {
                String action = isMoveOperation ? "move" : "copy";
                for (String p : pendingMovePaths) {
                    try {
                        JSONObject req = new JSONObject();
                        req.put("action", action);
                        req.put("path", p);
                        java.io.File oldFile = new java.io.File(p);
                        req.put("newPath", currentPath + "/" + oldFile.getName());
                        ClientConnectionManager.getInstance().sendData(req.toString());
                    } catch (Exception e) {}
                }
                Toast.makeText(this, (isMoveOperation ? "Moving " : "Copying ") + pendingMovePaths.size() + " items...", Toast.LENGTH_SHORT).show();
                pendingMovePaths.clear();
                bannerMove.setVisibility(View.GONE);
                new android.os.Handler().postDelayed(() -> loadDirectory(currentPath), 1500);
            }
        });

        // WebRTC listener
        ClientConnectionManager.getInstance().addListener(new ClientConnectionManager.ClientListener() {
            @Override
            public void onConnected() {
                runOnUiThread(() -> {
                    loadDirectory(currentPath);
                });
            }

            @Override
            public void onMessage(JSONObject msg) {
                runOnUiThread(() -> handleMessage(msg));
            }

            @Override
            public void onBinary(java.nio.ByteBuffer buf) {}
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

        wireNav(hostId);
    }
    

    private void safeText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }
    
    private void setupChips() {
        // Dummy implementation since chips are removed from UI in new design, but keep it for compile safety
        // or just leave empty if it's called in onCreate
    }

    private void toggleSelectionMode(boolean active) {
        isSelectionMode = active;
        selectedFiles.clear();
        
        topBarNormal.setVisibility(active ? View.GONE : View.VISIBLE);
        topBarSelection.setVisibility(active ? View.VISIBLE : View.GONE);
        layoutSelectionBottomBar.setVisibility(active ? View.VISIBLE : View.GONE);
        layoutFabs.setVisibility(active ? View.GONE : View.VISIBLE);
        
        // Hide standard bottom nav when in selection mode
        View bottomNav = findViewById(R.id.bottomNavInclude);
        if (bottomNav != null) bottomNav.setVisibility(active ? View.GONE : View.VISIBLE);
        
        updateSelectionUI();
        adapter.setSelectionMode(active);
        adapter.notifyDataSetChanged();
    }
    
    private void toggleFileSelection(String path) {
        if (selectedFiles.contains(path)) {
            selectedFiles.remove(path);
        } else {
            selectedFiles.add(path);
        }
        updateSelectionUI();
        adapter.notifyDataSetChanged();
    }
    
    private void updateSelectionUI() {
        textSelectedCount.setText(selectedFiles.size() + " selected");
        if (selectedFiles.size() == allFiles.length() && allFiles.length() > 0) {
            imgSelectAll.setImageResource(R.drawable.ic_check_circle_solid);
        } else {
            imgSelectAll.setImageResource(R.drawable.ic_uncheck_circle_outline);
        }
    }

    private void loadDirectory(String path) {
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
            
            if ("access_denied".equals(action)) {
                String deniedPath = msg.optString("path");
                Toast.makeText(this, "Access Denied: " + deniedPath, Toast.LENGTH_SHORT).show();
                if (deniedPath.equals("/") || deniedPath.equals(currentPath)) {
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
                Toast.makeText(this, "Share link copied!", Toast.LENGTH_SHORT).show();
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
                    
                    // Update breadcrumb
                    String[] parts = path.split("/");
                    String folderName = parts.length > 0 ? parts[parts.length - 1] : "Root";
                    if (path.equals(android.os.Environment.getExternalStorageDirectory().getAbsolutePath())) {
                        folderName = "Internal storage";
                    }
                    if (textBreadcrumb != null) textBreadcrumb.setText(folderName);
                }
            } else if ("search_results".equals(action)) {
                int chunk  = msg.optInt("chunk", 1);
                int total  = msg.optInt("total_chunks", 1);
                
                if (chunk == 1) accumulated = new JSONArray();
                JSONArray files = msg.optJSONArray("files");
                if (files != null) for (int i = 0; i < files.length(); i++) {
                    accumulated.put(files.getJSONObject(i));
                }
                if (chunk == total) {
                    buildMasterList();
                    refresh();
                }
            } else if ("file_transfer_start".equals(action)) {
                String name = msg.optString("name");
                currentIntent = msg.optString("intent");
                currentFile = "preview".equals(currentIntent)
                        ? new java.io.File(getCacheDir(), name)
                        : new java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS), name);
            } else if ("file_transfer_end".equals(action)) {
                if ("download".equals(currentIntent)) {
                    Toast.makeText(this, "Saved to Downloads", Toast.LENGTH_SHORT).show();
                }
            }
        } catch (Exception e) {}
    }

    private void buildMasterList() {
        allFiles = new JSONArray();
        for (int i = 0; i < accumulated.length(); i++) {
            allFiles.put(accumulated.optJSONObject(i));
        }
    }

    private void refresh() {
        findViewById(R.id.layoutLoading).setVisibility(View.GONE);
        String q = editSearch != null ? editSearch.getText().toString().toLowerCase() : "";

        java.util.List<JSONObject> filtered = new java.util.ArrayList<>();
        for (int i = 0; i < allFiles.length(); i++) {
            JSONObject f = allFiles.optJSONObject(i);
            if (f == null) continue;

            String name = f.optString("name").toLowerCase();
            boolean isDir = f.optBoolean("isDir");

            // Type filter
            if (!activeChip.equals("all")) {
                if (isDir) continue;
                if (activeChip.equals("images") && !(name.endsWith(".jpg") || name.endsWith(".png"))) continue;
                if (activeChip.equals("videos") && !name.endsWith(".mp4")) continue;
                if (activeChip.equals("docs") && !(name.endsWith(".pdf") || name.endsWith(".txt"))) continue;
                if (activeChip.equals("audio") && !name.endsWith(".mp3")) continue;
            }

            // Search filter
            if (!q.isEmpty() && !name.contains(q)) continue;

            filtered.add(f);
        }

        // Sort
        java.util.Collections.sort(filtered, (a, b) -> {
            boolean aDir = a.optBoolean("isDir");
            boolean bDir = b.optBoolean("isDir");
            if (aDir && !bDir) return -1;
            if (!aDir && bDir) return 1;
            int cmp = a.optString("name").compareToIgnoreCase(b.optString("name"));
            return sortAZ ? cmp : -cmp;
        });

        adapter.setFiles(filtered, selectedFiles);
        findViewById(R.id.layoutEmpty).setVisibility(filtered.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void goUp() {
        if (currentPath.equals("/") || currentPath.equals(android.os.Environment.getExternalStorageDirectory().getAbsolutePath())) {
            Toast.makeText(this, "Already at root", Toast.LENGTH_SHORT).show();
            return;
        }
        int lastSlash = currentPath.lastIndexOf('/');
        if (lastSlash > 0) {
            String parent = currentPath.substring(0, lastSlash);
            loadDirectory(parent);
        } else {
            loadDirectory("/");
        }
    }

    private void openFile(JSONObject file) {
        try {
            String url = "http://127.0.0.1:8080/stream?path=" + android.net.Uri.encode(file.optString("path"));
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
        } catch (Exception ex) {
            Toast.makeText(this, "Failed to open file", Toast.LENGTH_SHORT).show();
        }
    }

    private void wireNav(String hostId) {
        View btnHome = findViewById(R.id.navHome);
        View btnFiles = findViewById(R.id.navFiles);
        View btnActivity = findViewById(R.id.navActivity);
        View btnSettings = findViewById(R.id.navSettings);

        // ImageView imgFiles = findViewById(R.id.navImgFiles);
        // TextView txtFiles = findViewById(R.id.navTxtFiles);
        //
        //

        if (btnHome != null) btnHome.setOnClickListener(v -> {
            startActivity(new Intent(this, DashboardActivity.class).putExtra("HOST_ID", hostId));
            finish();
        });
        if (btnActivity != null) btnActivity.setOnClickListener(v -> {
            startActivity(new Intent(this, ActivityLogActivity.class).putExtra("HOST_ID", hostId));
            finish();
        });
        if (btnSettings != null) btnSettings.setOnClickListener(v -> {
            startActivity(new Intent(this, SettingsActivity.class).putExtra("HOST_ID", hostId));
            finish();
        });
    }

    // Unused FileAdapter class included internally
    static class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
        private java.util.List<JSONObject> files = new java.util.ArrayList<>();
        private Set<String> selectedPaths = new HashSet<>();
        private boolean isSelectionMode = false;
        
        public interface OnFileClickListener {
            void onFileClick(JSONObject file);
            void onMenuClick(View anchor, JSONObject file);
        }
        
        public interface OnFileLongClickListener {
            void onFileLongClick(JSONObject file);
        }
        
        private OnFileClickListener listener;
        private OnFileLongClickListener longClickListener;
        
        public FileAdapter(OnFileClickListener listener) {
            this.listener = listener;
        }
        
        public void setOnFileLongClickListener(OnFileLongClickListener listener) {
            this.longClickListener = listener;
        }
        
        public void setFiles(java.util.List<JSONObject> files, Set<String> selectedPaths) {
            this.files = files;
            this.selectedPaths = selectedPaths;
            notifyDataSetChanged();
        }
        
        public void setSelectionMode(boolean active) {
            this.isSelectionMode = active;
        }
        
        @Override
        public ViewHolder onCreateViewHolder(android.view.ViewGroup parent, int viewType) {
            View view = android.view.LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_file, parent, false);
            return new ViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(ViewHolder holder, int position) {
            JSONObject file = files.get(position);
            String name = file.optString("name");
            boolean isDir = file.optBoolean("isDir");
            long size = file.optLong("size");
            String path = file.optString("path");
            
            holder.textFileName.setText(name);
            
            // Icon
            if (isDir) {
                holder.imgFileIcon.setImageResource(R.drawable.ic_folder);
                holder.imgFileIcon.setColorFilter(android.graphics.Color.parseColor("#FFC107"));
                holder.textItemCount.setText("Directory");
                
                // Add tiny overlays for known folders like DCIM, Download
                holder.imgOverlay.setVisibility(View.VISIBLE);
                if (name.equalsIgnoreCase("DCIM") || name.equalsIgnoreCase("Camera") || name.equalsIgnoreCase("Pictures")) {
                    holder.imgOverlay.setImageResource(R.drawable.ic_image);
                } else if (name.equalsIgnoreCase("Download") || name.equalsIgnoreCase("Downloads")) {
                    holder.imgOverlay.setImageResource(R.drawable.ic_download);
                } else {
                    holder.imgOverlay.setVisibility(View.GONE);
                }
            } else {
                holder.imgFileIcon.setImageResource(R.drawable.ic_file);
                holder.imgFileIcon.setColorFilter(android.graphics.Color.parseColor("#757575"));
                holder.textItemCount.setText(android.text.format.Formatter.formatFileSize(holder.itemView.getContext(), size));
                holder.imgOverlay.setVisibility(View.GONE);
            }
            
            holder.textFileDate.setText("Unknown Date"); // Fake date for now, would need backend support for real lastModified
            
            // Selection UI
            if (isSelectionMode || selectedPaths.contains(path)) {
                holder.imgSelect.setVisibility(View.VISIBLE);
                if (selectedPaths.contains(path)) {
                    holder.imgSelect.setImageResource(R.drawable.ic_check_circle_solid);
                } else {
                    holder.imgSelect.setImageResource(R.drawable.ic_uncheck_circle_outline);
                }
            } else {
                holder.imgSelect.setVisibility(View.GONE);
            }
            
            holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
            holder.itemView.setOnLongClickListener(v -> {
                if (longClickListener != null) longClickListener.onFileLongClick(file);
                return true;
            });
        }
        
        @Override
        public int getItemCount() {
            return files.size();
        }
        
        class ViewHolder extends RecyclerView.ViewHolder {
            TextView textFileName, textFileDate, textItemCount;
            ImageView imgFileIcon, imgOverlay, imgSelect;
            
            ViewHolder(View itemView) {
                super(itemView);
                textFileName = itemView.findViewById(R.id.textFileName);
                textFileDate = itemView.findViewById(R.id.textFileDate);
                textItemCount = itemView.findViewById(R.id.textItemCount);
                imgFileIcon = itemView.findViewById(R.id.imgFileIcon);
                imgOverlay = itemView.findViewById(R.id.imgOverlay);
                imgSelect = itemView.findViewById(R.id.imgSelect);
            }
        }
    }
}
