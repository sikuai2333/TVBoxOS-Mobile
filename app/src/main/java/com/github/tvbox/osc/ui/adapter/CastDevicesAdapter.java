package com.github.tvbox.osc.ui.adapter;

import android.os.Handler;
import android.os.Looper;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.dlna.DLNACastManager;

import org.fourthline.cling.model.meta.Device;

import java.util.List;

/**
 * @author pj567
 * @date :2020/12/23
 * @description: DLNA 投屏设备适配器
 */
public class CastDevicesAdapter extends BaseQuickAdapter<Device, BaseViewHolder>
        implements DLNACastManager.OnDeviceRegistryListener {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public CastDevicesAdapter() {
        super(R.layout.item_title);
    }

    @Override
    protected void convert(BaseViewHolder helper, Device item) {
        if (item != null && item.getDetails() != null) {
            String friendlyName = item.getDetails().getFriendlyName();
            helper.setText(R.id.title, friendlyName != null ? friendlyName : "未知设备");
        } else {
            helper.setText(R.id.title, "未知设备");
        }
    }

    @Override
    public void onDeviceAdded(Device<?, ?, ?> device) {
        mainHandler.post(() -> {
            // 检查是否已存在相同设备
            List<Device> data = getData();
            for (Device d : data) {
                if (d.getIdentity().getUdn().equals(device.getIdentity().getUdn())) {
                    return; // 设备已存在，不重复添加
                }
            }
            addData(device);
        });
    }

    @Override
    public void onDeviceUpdated(Device<?, ?, ?> device) {
        mainHandler.post(() -> {
            List<Device> data = getData();
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).getIdentity().getUdn().equals(device.getIdentity().getUdn())) {
                    data.set(i, device);
                    notifyItemChanged(i);
                    return;
                }
            }
        });
    }

    @Override
    public void onDeviceRemoved(Device<?, ?, ?> device) {
        mainHandler.post(() -> {
            List<Device> data = getData();
            for (int i = 0; i < data.size(); i++) {
                if (data.get(i).getIdentity().getUdn().equals(device.getIdentity().getUdn())) {
                    data.remove(i);
                    notifyItemRemoved(i);
                    return;
                }
            }
        });
    }
}
