package com.storeme;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import org.json.JSONArray;
import org.json.JSONObject;

public class FileAdapter extends RecyclerView.Adapter<FileAdapter.ViewHolder> {
    private JSONArray filesArray = new JSONArray();
    private OnFileClickListener listener;

    public interface OnFileClickListener {
        void onFileClick(JSONObject file);
        void onMenuClick(View anchor, JSONObject file);
    }

    public FileAdapter(OnFileClickListener listener) {
        this.listener = listener;
    }

    public void setFiles(JSONArray files) {
        this.filesArray = files;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_file, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        try {
            JSONObject file = filesArray.getJSONObject(position);
            boolean isDir = file.optBoolean("isDir");
            boolean isBack = file.optBoolean("isBack");
            holder.textName.setText(file.optString("name"));
            
            if (isBack) {
                holder.textDetails.setText("");
                holder.imgIcon.setImageResource(R.drawable.ic_folder);
                holder.btnAction.setVisibility(View.GONE);
            } else if (isDir) {
                holder.textDetails.setText("Directory");
                holder.imgIcon.setImageResource(R.drawable.ic_folder);
                holder.btnAction.setVisibility(View.VISIBLE);
            } else {
                long sizeMB = file.optLong("size") / (1024 * 1024);
                long sizeKB = file.optLong("size") / 1024;
                if (sizeMB > 0) holder.textDetails.setText(sizeMB + " MB");
                else if (sizeKB > 0) holder.textDetails.setText(sizeKB + " KB");
                else holder.textDetails.setText(file.optLong("size") + " B");
                
                String name = file.optString("name").toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png") || name.endsWith(".gif")) holder.imgIcon.setImageResource(R.drawable.ic_image);
                else if (name.endsWith(".mp4") || name.endsWith(".mkv") || name.endsWith(".avi")) holder.imgIcon.setImageResource(R.drawable.ic_video);
                else if (name.endsWith(".mp3") || name.endsWith(".wav") || name.endsWith(".aac")) holder.imgIcon.setImageResource(R.drawable.ic_audio);
                else if (name.endsWith(".pdf") || name.endsWith(".doc") || name.endsWith(".docx") || name.endsWith(".txt")) holder.imgIcon.setImageResource(R.drawable.ic_document);
                else if (name.endsWith(".zip") || name.endsWith(".rar") || name.endsWith(".tar") || name.endsWith(".gz")) holder.imgIcon.setImageResource(R.drawable.ic_archive);
                else holder.imgIcon.setImageResource(R.drawable.ic_unknown);
                
                holder.btnAction.setVisibility(View.VISIBLE);
            }

            holder.itemView.setOnClickListener(v -> listener.onFileClick(file));
            holder.btnAction.setOnClickListener(v -> listener.onMenuClick(v, file));
        } catch (Exception e) {}
    }

    @Override
    public int getItemCount() {
        return filesArray.length();
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textName, textDetails;
        ImageView imgIcon, btnAction;
        public ViewHolder(View itemView) {
            super(itemView);
            textName = itemView.findViewById(R.id.textName);
            textDetails = itemView.findViewById(R.id.textDetails);
            btnAction = itemView.findViewById(R.id.btnAction);
            imgIcon = itemView.findViewById(R.id.imgIcon);
        }
    }
}
