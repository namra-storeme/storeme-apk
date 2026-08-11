package com.storeme;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class TransferAdapter extends RecyclerView.Adapter<TransferAdapter.ViewHolder> {
    private List<Object> tasks = new ArrayList<>();

    public void setTasks(List<Object> newTasks) {
        this.tasks = newTasks;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_download, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Object item = tasks.get(position);
        
        String name = "";
        String status = "";
        long size = 0;
        long transferred = 0;
        long speed = 0;
        boolean isUpload = false;
        String path = "";

        if (item instanceof ClientConnectionManager.DownloadTask) {
            ClientConnectionManager.DownloadTask t = (ClientConnectionManager.DownloadTask) item;
            java.io.File f = new java.io.File(t.path);
            name = f.getName();
            status = t.status;
            size = t.size;
            transferred = t.downloaded;
            speed = t.speed;
            isUpload = false;
            path = t.path;
        } else if (item instanceof ClientConnectionManager.UploadTask) {
            ClientConnectionManager.UploadTask t = (ClientConnectionManager.UploadTask) item;
            name = t.localPath;
            status = t.status;
            size = t.size;
            transferred = t.uploaded;
            speed = t.speed;
            isUpload = true;
            path = t.remotePath;
        }

        holder.textFilename.setText(name);
        holder.textStatus.setText(isUpload ? "Upload: " + status : "Download: " + status);
        holder.textTime.setText("Just now");
        holder.imgStatusIcon.setImageResource(isUpload ? android.R.drawable.ic_menu_upload : R.drawable.ic_download_round);
        
        String lname = name.toLowerCase();
        if (lname.endsWith(".pdf")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_pdf);
        else if (lname.endsWith(".doc") || lname.endsWith(".docx")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_word);
        else if (lname.endsWith(".xls") || lname.endsWith(".xlsx")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_excel);
        else if (lname.endsWith(".ppt") || lname.endsWith(".pptx")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_powerpoint);
        else if (lname.endsWith(".txt") || lname.endsWith(".log")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_text);
        else if (lname.endsWith(".jpg") || lname.endsWith(".png") || lname.endsWith(".jpeg")) holder.imgActivityIcon.setImageResource(R.drawable.ic_image_category);
        else holder.imgActivityIcon.setImageResource(R.drawable.ic_file_text);
        
        if (size > 0) {
            int progress = (int) ((transferred * 100) / size);
            holder.progressDownload.setProgress(progress);
            
            float mb = transferred / (1024f * 1024f);
            float tmb = size / (1024f * 1024f);
            holder.textBytes.setText(String.format("%.1f MB / %.1f MB", mb, tmb));
        } else {
            holder.progressDownload.setProgress(0);
            holder.textBytes.setText(transferred + " bytes");
        }

        if (status.equals("Downloading") || status.equals("Uploading")) {
            if (speed > 1024 * 1024) holder.textSpeed.setText(String.format("%.1f MB/s", speed / (1024f * 1024f)));
            else holder.textSpeed.setText((speed / 1024) + " KB/s");
            holder.textSpeed.setVisibility(View.VISIBLE);
            
            holder.btnPauseResume.setVisibility(View.VISIBLE);
            holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_pause);
            boolean up = isUpload;
            String p = path;
            holder.btnPauseResume.setOnClickListener(v -> {
                if (up) ClientConnectionManager.getInstance().pauseUpload(p);
                else ClientConnectionManager.getInstance().pauseDownload(p);
            });
            holder.btnCancel.setVisibility(View.VISIBLE);
            holder.btnCancel.setOnClickListener(v -> {
                if (up) ClientConnectionManager.getInstance().cancelUpload(p);
                else ClientConnectionManager.getInstance().cancelDownload(p);
            });
        } else if (status.equals("Paused")) {
            holder.btnPauseResume.setVisibility(View.VISIBLE);
            holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_play);
            boolean up = isUpload;
            String p = path;
            holder.btnPauseResume.setOnClickListener(v -> {
                if (up) {
                    // Upload resume isn't explicitly defined as a separate method, startUpload handles it
                    // To resume upload, you trigger upload_start again. But we can just send action upload_start.
                    try {
                        org.json.JSONObject req = new org.json.JSONObject();
                        req.put("action", "upload_start");
                        req.put("path", p);
                        req.put("size", 0);
                        ClientConnectionManager.getInstance().sendData(req.toString());
                        ClientConnectionManager.UploadTask ut = (ClientConnectionManager.UploadTask) item;
                        ut.status = "Uploading";
                    } catch (Exception e){}
                }
                else ClientConnectionManager.getInstance().resumeDownload(p);
            });
            holder.btnCancel.setVisibility(View.VISIBLE);
            holder.btnCancel.setOnClickListener(v -> {
                if (up) ClientConnectionManager.getInstance().cancelUpload(p);
                else ClientConnectionManager.getInstance().cancelDownload(p);
            });
            holder.textSpeed.setVisibility(View.GONE);
        } else {
            holder.btnPauseResume.setVisibility(View.GONE);
            holder.btnCancel.setVisibility(View.GONE);
            holder.textSpeed.setVisibility(View.GONE);
        }
    }

    @Override
    public int getItemCount() {
        return tasks.size();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView imgActivityIcon, imgStatusIcon, btnPauseResume, btnCancel;
        TextView textFilename, textTime, textStatus, textSpeed, textBytes;
        ProgressBar progressDownload;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            imgActivityIcon = itemView.findViewById(R.id.imgActivityIcon);
            imgStatusIcon = itemView.findViewById(R.id.imgStatusIcon);
            btnPauseResume = itemView.findViewById(R.id.btnPauseResume);
            btnCancel = itemView.findViewById(R.id.btnCancel);
            textFilename = itemView.findViewById(R.id.textFilename);
            textTime = itemView.findViewById(R.id.textTime);
            textStatus = itemView.findViewById(R.id.textStatus);
            textSpeed = itemView.findViewById(R.id.textSpeed);
            textBytes = itemView.findViewById(R.id.textBytes);
            progressDownload = itemView.findViewById(R.id.progressDownload);
        }
    }
}
