package com.storeme;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class ActivityLogActivity extends AppCompatActivity {

    private TransferAdapter adapter;
    private java.util.Timer updateTimer;
    private String activeFilter = "all"; // all, downloads, completed, inprogress

    // Stat views
    private TextView statTotalFiles, statDone, statActive, statMB, textActiveCount;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        setTheme(R.style.Theme_StoreMeNative);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        String hostId = getIntent().getStringExtra("HOST_ID");

        // Bind stat views
        statTotalFiles  = findViewById(R.id.statTotalFiles);
        statDone        = findViewById(R.id.statDone);
        statActive      = findViewById(R.id.statActive);
        statMB          = findViewById(R.id.statMB);
        textActiveCount = findViewById(R.id.textActiveCount);

        // RecyclerView
        RecyclerView recycler = findViewById(R.id.recyclerDownloads);
        if (recycler != null) {
            recycler.setLayoutManager(new LinearLayoutManager(this));
            adapter = new TransferAdapter();
            recycler.setAdapter(adapter);
        }

        // Filter chips
        setupChips();

        // Poll every 500ms
        updateTimer = new java.util.Timer();
        updateTimer.scheduleAtFixedRate(new java.util.TimerTask() {
            @Override
            public void run() {

                List<Object> all = new ArrayList<>();
                all.addAll(ClientConnectionManager.getInstance().getDownloads());
                all.addAll(ClientConnectionManager.getInstance().getUploads());
                runOnUiThread(() -> {
                    updateStats(all);
                    applyFilter(all);
                });

            }
        }, 0, 500);

        wireNav(hostId);
    }


    private void updateStats(List<Object> tasks) {
        int completed  = 0;
        int inProgress = 0;
        long totalBytes = 0;
        for (Object item : tasks) {
            if (item instanceof ClientConnectionManager.DownloadTask) {
                ClientConnectionManager.DownloadTask t = (ClientConnectionManager.DownloadTask) item;
                totalBytes += t.downloaded;
                if ("Completed".equals(t.status))  completed++;
                if ("Downloading".equals(t.status)) inProgress++;
            } else if (item instanceof ClientConnectionManager.UploadTask) {
                ClientConnectionManager.UploadTask t = (ClientConnectionManager.UploadTask) item;
                totalBytes += t.uploaded;
                if ("Completed".equals(t.status))  completed++;
                if ("Uploading".equals(t.status)) inProgress++;
            }
        }

        long mb = totalBytes / (1024 * 1024);

        setText(statTotalFiles,  String.valueOf(tasks.size()));
        setText(statDone,        String.valueOf(completed));
        setText(statActive,      String.valueOf(inProgress));
        setText(statMB,          mb + " MB");
        setText(textActiveCount, inProgress > 0 ? inProgress + " active" : "");
    }


    private void applyFilter(List<Object> all) {
        List<Object> filtered = new ArrayList<>();
        for (Object item : all) {
            String status = "";
            if (item instanceof ClientConnectionManager.DownloadTask) {
                status = ((ClientConnectionManager.DownloadTask) item).status;
            } else if (item instanceof ClientConnectionManager.UploadTask) {
                status = ((ClientConnectionManager.UploadTask) item).status;
            }
            
            switch (activeFilter) {
                case "downloads":  filtered.add(item); break;
                case "completed":  if ("Completed".equals(status)) filtered.add(item); break;
                case "inprogress": if ("Downloading".equals(status) || "Uploading".equals(status) || "Paused".equals(status)) filtered.add(item); break;
                default: filtered.add(item); break; // "all"
            }
        }
        if (adapter != null) adapter.setTasks(filtered);
    }


    private void setupChips() {
        int[] chipIds = {
            R.id.chipAllActivity, R.id.chipDownloads,
            R.id.chipCompleted,   R.id.chipInProgress
        };
        String[] keys = {"all", "downloads", "completed", "inprogress"};

        for (int i = 0; i < chipIds.length; i++) {
            TextView chip = findViewById(chipIds[i]);
            if (chip == null) continue;
            final String key = keys[i];
            chip.setOnClickListener(v -> {
                activeFilter = key;
                for (int id : chipIds) {
                    TextView c = findViewById(id);
                    if (c == null) continue;
                    boolean active = c == v;
                    c.setBackgroundColor(active ? 0xFF0F172A : 0xFFF1F5F9);
                    c.setTextColor(active ? 0xFFFFFFFF : 0xFF475569);
                }
            });
        }
    }

    private void wireNav(String hostId) {
        View navHome = findViewById(R.id.navHome);
        if (navHome != null) navHome.setOnClickListener(v -> go(ClientDashboardActivity.class, hostId));

        View navFiles = findViewById(R.id.navFiles);
        if (navFiles != null) navFiles.setOnClickListener(v -> go(FileManagerActivity.class, hostId));

        View navSettings = findViewById(R.id.navSettings);
        if (navSettings != null) navSettings.setOnClickListener(v -> go(SettingsActivity.class, hostId));
    }

    private void go(Class<?> cls, String hostId) {
        Intent i = new Intent(this, cls);
        i.putExtra("HOST_ID", hostId);
        startActivity(i);
        overridePendingTransition(0, 0);
        finish();
    }

    private void setText(TextView tv, String text) {
        if (tv != null) tv.setText(text);
    }

    @Override
    protected void onDestroy() {
        if (updateTimer != null) updateTimer.cancel();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        Toast.makeText(this, "Stop using server in Settings to exit.", Toast.LENGTH_SHORT).show();
    }
}
