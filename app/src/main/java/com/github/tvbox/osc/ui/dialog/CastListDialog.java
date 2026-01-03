package com.github.tvbox.osc.ui.dialog;

import android.content.Context;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.CastVideo;
import com.github.tvbox.osc.dlna.DLNACastManager;
import com.github.tvbox.osc.ui.adapter.CastDevicesAdapter;
import com.lxj.xpopup.core.CenterPopupView;

import org.jetbrains.annotations.NotNull;
import org.fourthline.cling.model.meta.Device;

import java.util.ArrayList;

public class CastListDialog extends CenterPopupView {

    private final CastVideo castVideo;
    private CastDevicesAdapter adapter;
    private boolean isSearching = false;

    public CastListDialog(@NonNull @NotNull Context context, CastVideo castVideo) {
        super(context);
        this.castVideo = castVideo;
    }

    @Override
    protected int getImplLayoutId() {
        return R.layout.dialog_cast;
    }

    @Override
    protected void onCreate() {
        super.onCreate();

        // 验证视频信息
        if (castVideo == null || !castVideo.isValid()) {
            Toast.makeText(getContext(), "无效的视频信息", Toast.LENGTH_SHORT).show();
            dismiss();
            return;
        }

        DLNACastManager.getInstance().bindCastService(App.getInstance());

        findViewById(R.id.btn_cancel).setOnClickListener(view -> dismiss());

        findViewById(R.id.btn_confirm).setOnClickListener(view -> {
            if (!isSearching) {
                refreshDevices();
            }
        });

        RecyclerView rv = findViewById(R.id.rv);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CastDevicesAdapter();
        rv.setAdapter(adapter);
        DLNACastManager.getInstance().registerDeviceListener(adapter);

        adapter.setOnItemClickListener((adapter, view, position) -> {
            Device item = (Device) adapter.getItem(position);
            if (item != null) {
                castToDevice(item);
            }
        });

        // 自动开始搜索
        refreshDevices();
    }

    private void refreshDevices() {
        isSearching = true;
        adapter.setNewData(new ArrayList<>());
        DLNACastManager.getInstance().search(null, 1);
        Toast.makeText(getContext(), "正在搜索设备...", Toast.LENGTH_SHORT).show();

        // 3秒后重置搜索状态
        postDelayed(() -> isSearching = false, 3000);
    }

    private void castToDevice(Device device) {
        String deviceName = "未知设备";
        if (device.getDetails() != null && device.getDetails().getFriendlyName() != null) {
            deviceName = device.getDetails().getFriendlyName();
        }

        final String finalDeviceName = deviceName;
        Toast.makeText(getContext(), "正在投屏到: " + deviceName, Toast.LENGTH_SHORT).show();

        DLNACastManager.getInstance().cast(device, castVideo, new DLNACastManager.CastCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(getContext(), "投屏成功: " + finalDeviceName, Toast.LENGTH_SHORT).show();
                dismiss();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(getContext(), "投屏失败: " + message, Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDismiss() {
        super.onDismiss();
        if (adapter != null) {
            DLNACastManager.getInstance().unregisterListener(adapter);
        }
        DLNACastManager.getInstance().unbindCastService(App.getInstance());
    }
}
