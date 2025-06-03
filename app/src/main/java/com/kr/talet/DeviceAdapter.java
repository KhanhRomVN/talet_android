package com.kr.talet;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import java.util.List;

public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.ViewHolder> {
    private final List<DeviceItem> deviceList;
    private OnItemClickListener listener;

    // Giao diện callback
    public interface OnItemClickListener {
        void onItemClick(DeviceItem device);
    }

    public void setOnItemClickListener(OnItemClickListener l) {
        this.listener = l;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        public TextView deviceName;
        public TextView deviceIp;

        public ViewHolder(View view) {
            super(view);
            deviceName = view.findViewById(R.id.device_name);
            deviceIp = view.findViewById(R.id.device_ip);
        }
    }

    public DeviceAdapter(List<DeviceItem> deviceList) {
        this.deviceList = deviceList;
    }

    @Override
    public ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.device_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(ViewHolder holder, int position) {
        DeviceItem item = deviceList.get(position);
        holder.deviceName.setText(item.getName());
        holder.deviceIp.setText(item.getIp());

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onItemClick(item);
        });
    }

    @Override
    public int getItemCount() {
        return deviceList.size();
    }
}