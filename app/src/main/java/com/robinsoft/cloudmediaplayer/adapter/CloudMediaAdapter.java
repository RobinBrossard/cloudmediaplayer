package com.robinsoft.cloudmediaplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;

public class CloudMediaAdapter
        extends ListAdapter<CloudMediaItem, CloudMediaAdapter.ViewHolder> {

    private OnItemClickListener listener;

    public CloudMediaAdapter() {
        super(DIFF_CALLBACK);
    }

    private static final DiffUtil.ItemCallback<CloudMediaItem> DIFF_CALLBACK =
            new DiffUtil.ItemCallback<CloudMediaItem>() {
                @Override
                public boolean areItemsTheSame(@NonNull CloudMediaItem oldItem,
                                               @NonNull CloudMediaItem newItem) {
                    return oldItem.getId().equals(newItem.getId());
                }
                @Override
                @SuppressWarnings("SuspiciousEquality")
                public boolean areContentsTheSame(@NonNull CloudMediaItem oldItem,
                                                  @NonNull CloudMediaItem newItem) {
                    return oldItem.getName().equals(newItem.getName())
                            && oldItem.getUrl().equals(newItem.getUrl())
                            && oldItem.getType().equals(newItem.getType());
                }
            };

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
                                         int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int pos) {
        CloudMediaItem item = getItem(pos);
        holder.tvName.setText(item.getName());
        // … 如果需要缩略图可在这里加载 ivThumbnail …

        String label;
        switch (item.getType()) {
            case IMAGE:  label = "图片";  break;
            case VIDEO:  label = "视频";  break;
            case FOLDER: label = "目录";  break;
            case FILE:   label = "文件";  break;
            default:     label = "";     break;
        }
        holder.tvType.setText(label);

        // —— 添加点击回调 ——
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onItemClick(item);
            }
        });
    }

    /**
     * 设置点击回调
     */
    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    /**
     * 点击事件接口
     */
    public interface OnItemClickListener {
        void onItemClick(CloudMediaItem item);
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        ImageView ivThumbnail;
        TextView tvName, tvType;

        ViewHolder(View v) {
            super(v);
            ivThumbnail = v.findViewById(R.id.ivThumbnail);
            tvName      = v.findViewById(R.id.tvName);
            tvType      = v.findViewById(R.id.tvType);
        }
    }
}
