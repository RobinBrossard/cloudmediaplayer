package com.robinsoft.cloudmediaplayer.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import java.util.ArrayList;
import java.util.List;

public class MediaAdapter extends RecyclerView.Adapter<MediaAdapter.VH> {

    private final List<CloudMediaItem> data = new ArrayList<>();
    private final OnItemClickListener listener;

    /** 点击回调接口 */
    public interface OnItemClickListener {
        void onItemClick(CloudMediaItem item);
    }

    public MediaAdapter(OnItemClickListener listener) {
        this.listener = listener;
    }

    /** 更新数据 */
    public void setData(List<CloudMediaItem> list) {
        data.clear();
        if (list != null) data.addAll(list);
        notifyDataSetChanged();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_media, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        CloudMediaItem item = data.get(position);
        holder.tvName.setText(item.getName());
        holder.itemView.setOnClickListener(v -> listener.onItemClick(item));
    }

    @Override public int getItemCount() { return data.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvName;
        VH(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvName);
        }
    }
}
