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

public class DownloadAdapter extends RecyclerView.Adapter<DownloadAdapter.ViewHolder> {
    private List<ClientConnectionManager.DownloadTask> downloads = new ArrayList<>();

    public void setDownloads(List<ClientConnectionManager.DownloadTask> newDownloads) {
        this.downloads = newDownloads;
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
        ClientConnectionManager.DownloadTask task = downloads.get(position);
        
        java.io.File f = new java.io.File(task.path);
        String name = f.getName().toLowerCase();
        holder.textFilename.setText(f.getName());
        holder.textStatus.setText(task.status);
        holder.textTime.setText("Just now");
        holder.imgStatusIcon.setImageResource(R.drawable.ic_download_round);
        
        if (name.endsWith(".pdf")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_pdf);
        else if (name.endsWith(".doc") || name.endsWith(".docx")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_word);
        else if (name.endsWith(".xls") || name.endsWith(".xlsx")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_excel);
        else if (name.endsWith(".ppt") || name.endsWith(".pptx")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_powerpoint);
        else if (name.endsWith(".txt") || name.endsWith(".log")) holder.imgActivityIcon.setImageResource(R.drawable.ic_file_text);
        else if (name.endsWith(".jpg") || name.endsWith(".png") || name.endsWith(".jpeg")) holder.imgActivityIcon.setImageResource(R.drawable.ic_image_category);
        else holder.imgActivityIcon.setImageResource(R.drawable.ic_file_text);
        
        if (task.size > 0) {
            int progress = (int) ((task.downloaded * 100) / task.size);
            holder.progressDownload.setProgress(progress);
            
            float downloadedMB = task.downloaded / (1024f * 1024f);
            float totalMB = task.size / (1024f * 1024f);
            holder.textBytes.setText(String.format("%.1f MB / %.1f MB", downloadedMB, totalMB));
        } else {
            holder.progressDownload.setProgress(0);
            holder.textBytes.setText(task.downloaded + " bytes");
        }

        if (task.status.equals("Downloading")) {
            long speed = task.speed;
            if (speed > 1024 * 1024) {
                holder.textSpeed.setText(String.format("%.1f MB/s", speed / (1024f * 1024f)));
            } else {
                holder.textSpeed.setText((speed / 1024) + " KB/s");
            }
            holder.textSpeed.setVisibility(View.VISIBLE);
            
            holder.btnPauseResume.setVisibility(View.VISIBLE);
            holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_pause);
            holder.btnPauseResume.setOnClickListener(v -> {
                ClientConnectionManager.getInstance().pauseDownload(task.path);
            });
            holder.btnCancel.setVisibility(View.VISIBLE);
            holder.btnCancel.setOnClickListener(v -> {
                ClientConnectionManager.getInstance().cancelDownload(task.path);
            });
        } else if (task.status.equals("Paused")) {
            holder.btnPauseResume.setVisibility(View.VISIBLE);
            holder.btnPauseResume.setImageResource(android.R.drawable.ic_media_play);
            holder.btnPauseResume.setOnClickListener(v -> {
                ClientConnectionManager.getInstance().resumeDownload(task.path);
            });
            holder.btnCancel.setVisibility(View.VISIBLE);
            holder.btnCancel.setOnClickListener(v -> {
                ClientConnectionManager.getInstance().cancelDownload(task.path);
            });
            holder.textSpeed.setVisibility(View.GONE);
        } else {
            holder.btnPauseResume.setVisibility(View.GONE);
            holder.btnCancel.setVisibility(View.GONE);
            holder.textSpeed.setVisibility(View.GONE);
        }
        
        holder.itemView.setOnClickListener(v -> {
            if ("Completed".equals(task.status)) {
                try {
                    android.net.Uri uri = androidx.core.content.FileProvider.getUriForFile(
                            v.getContext(), v.getContext().getApplicationContext().getPackageName() + ".fileprovider", f);
                    
                    String mimeType = "*/*";
                    String ext = android.webkit.MimeTypeMap.getFileExtensionFromUrl(f.getAbsolutePath());
                    if (ext != null && !ext.isEmpty()) {
                        String possibleMime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.toLowerCase());
                        if (possibleMime != null) mimeType = possibleMime;
                    }
                    
                    android.content.Intent intent = new android.content.Intent(android.content.Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, mimeType);
                    intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    v.getContext().startActivity(intent);
                } catch (Exception ex) {
                    android.widget.Toast.makeText(v.getContext(), "No app found to open this file", android.widget.Toast.LENGTH_SHORT).show();
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return downloads.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textFilename, textStatus, textBytes, textTime, textSpeed;
        ProgressBar progressDownload;
        ImageView btnPauseResume, btnCancel, imgActivityIcon, imgStatusIcon;

        ViewHolder(View view) {
            super(view);
            textFilename = view.findViewById(R.id.textFilename);
            textStatus = view.findViewById(R.id.textStatus);
            textBytes = view.findViewById(R.id.textBytes);
            textSpeed = view.findViewById(R.id.textSpeed);
            progressDownload = view.findViewById(R.id.progressDownload);
            btnPauseResume = view.findViewById(R.id.btnPauseResume);
            btnCancel = view.findViewById(R.id.btnCancel);
            imgActivityIcon = view.findViewById(R.id.imgActivityIcon);
            imgStatusIcon = view.findViewById(R.id.imgStatusIcon);
            textTime = view.findViewById(R.id.textTime);
        }
    }
}
