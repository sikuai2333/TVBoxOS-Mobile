package com.github.tvbox.osc.dlna;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.LocalDevice;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.ServiceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.registry.DefaultRegistryListener;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.avtransport.callback.SetAVTransportURI;
import org.fourthline.cling.support.avtransport.callback.Play;
import org.fourthline.cling.support.avtransport.callback.Stop;
import org.fourthline.cling.model.action.ActionInvocation;

import com.github.tvbox.osc.bean.CastVideo;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * DLNA 投屏管理器
 */
public class DLNACastManager {
    private static final String TAG = "DLNACastManager";
    private static volatile DLNACastManager instance;

    private AndroidUpnpService upnpService;
    private Context context;
    private volatile boolean isBound = false;
    private final List<OnDeviceRegistryListener> listeners = new CopyOnWriteArrayList<>();
    private BrowseRegistryListener registryListener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private CastCallback castCallback;

    public static final ServiceType AV_TRANSPORT_SERVICE = new UDAServiceType("AVTransport");

    private static final String DIDL_LITE_HEADER = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
            "<DIDL-Lite xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" " +
            "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
            "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\">";
    private static final String DIDL_LITE_FOOTER = "</DIDL-Lite>";

    public interface CastCallback {
        void onSuccess();
        void onError(String message);
    }

    public interface OnDeviceRegistryListener {
        void onDeviceAdded(Device<?, ?, ?> device);
        void onDeviceUpdated(Device<?, ?, ?> device);
        void onDeviceRemoved(Device<?, ?, ?> device);
    }

    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            upnpService = (AndroidUpnpService) service;
            registryListener = new BrowseRegistryListener();
            upnpService.getRegistry().addListener(registryListener);

            // 搜索已有设备
            for (Device device : upnpService.getRegistry().getDevices()) {
                if (device.findService(AV_TRANSPORT_SERVICE) != null) {
                    final Device foundDevice = device;
                    mainHandler.post(() -> {
                        for (OnDeviceRegistryListener listener : listeners) {
                            listener.onDeviceAdded(foundDevice);
                        }
                    });
                }
            }

            // 开始搜索
            upnpService.getControlPoint().search();
            Log.d(TAG, "DLNA Service connected");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            upnpService = null;
            Log.d(TAG, "DLNA Service disconnected");
        }
    };

    private DLNACastManager() {}

    public static DLNACastManager getInstance() {
        if (instance == null) {
            synchronized (DLNACastManager.class) {
                if (instance == null) {
                    instance = new DLNACastManager();
                }
            }
        }
        return instance;
    }

    public void bindCastService(Context context) {
        this.context = context.getApplicationContext();
        if (!isBound) {
            Intent intent = new Intent(this.context, ClingUpnpService.class);
            this.context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
            isBound = true;
        }
    }

    public void unbindCastService(Context context) {
        if (isBound && this.context != null) {
            try {
                if (upnpService != null && registryListener != null) {
                    upnpService.getRegistry().removeListener(registryListener);
                }
                this.context.unbindService(serviceConnection);
            } catch (Exception e) {
                Log.e(TAG, "Error unbinding service: " + e.getMessage());
            }
            isBound = false;
            upnpService = null;
        }
    }

    public void search(ServiceType serviceType, int maxSeconds) {
        if (upnpService != null) {
            upnpService.getControlPoint().search();
        }
    }

    public void registerDeviceListener(OnDeviceRegistryListener listener) {
        if (!listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void unregisterListener(OnDeviceRegistryListener listener) {
        listeners.remove(listener);
    }

    private String createMetadata(CastVideo video) {
        String title = escapeXml(video.getName());
        String url = escapeXml(video.getUri());
        return DIDL_LITE_HEADER +
                "<item id=\"" + video.getId() + "\" parentID=\"0\" restricted=\"1\">" +
                "<dc:title>" + title + "</dc:title>" +
                "<upnp:class>object.item.videoItem</upnp:class>" +
                "<res protocolInfo=\"http-get:*:video/*:*\">" + url + "</res>" +
                "</item>" +
                DIDL_LITE_FOOTER;
    }

    private String escapeXml(String text) {
        if (text == null) return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    @SuppressWarnings("unchecked")
    public void cast(Device<?, ?, ?> device, CastVideo video) {
        cast(device, video, null);
    }

    @SuppressWarnings("unchecked")
    public void cast(Device<?, ?, ?> device, CastVideo video, CastCallback callback) {
        this.castCallback = callback;
        if (upnpService == null || device == null || video == null) {
            Log.e(TAG, "Cannot cast: service or device or video is null");
            notifyError("投屏服务未就绪");
            return;
        }

        Service<?, ?> avTransportService = device.findService(AV_TRANSPORT_SERVICE);
        if (avTransportService == null) {
            Log.e(TAG, "Device does not support AVTransport service");
            notifyError("设备不支持投屏");
            return;
        }

        // 设置媒体 URI，使用 DIDL-Lite metadata
        String metadata = createMetadata(video);
        SetAVTransportURI setAVTransportURI = new SetAVTransportURI(avTransportService, video.getUri(), metadata) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "SetAVTransportURI success, now playing...");
                // 开始播放
                play(device);
            }

            @Override
            public void failure(ActionInvocation invocation, org.fourthline.cling.model.message.UpnpResponse operation, String defaultMsg) {
                Log.e(TAG, "SetAVTransportURI failed: " + defaultMsg);
                notifyError("设置播放地址失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(setAVTransportURI);
    }

    private void notifyError(String message) {
        if (castCallback != null) {
            mainHandler.post(() -> castCallback.onError(message));
        }
    }

    private void notifySuccess() {
        if (castCallback != null) {
            mainHandler.post(() -> castCallback.onSuccess());
        }
    }

    @SuppressWarnings("unchecked")
    private void play(Device<?, ?, ?> device) {
        Service<?, ?> avTransportService = device.findService(AV_TRANSPORT_SERVICE);
        if (avTransportService == null) {
            notifyError("设备不支持播放");
            return;
        }

        Play play = new Play(avTransportService) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Play success");
                notifySuccess();
            }

            @Override
            public void failure(ActionInvocation invocation, org.fourthline.cling.model.message.UpnpResponse operation, String defaultMsg) {
                Log.e(TAG, "Play failed: " + defaultMsg);
                notifyError("播放失败: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(play);
    }

    @SuppressWarnings("unchecked")
    public void stop(Device<?, ?, ?> device) {
        if (upnpService == null || device == null) return;

        Service<?, ?> avTransportService = device.findService(AV_TRANSPORT_SERVICE);
        if (avTransportService == null) return;

        Stop stop = new Stop(avTransportService) {
            @Override
            public void success(ActionInvocation invocation) {
                Log.d(TAG, "Stop success");
            }

            @Override
            public void failure(ActionInvocation invocation, org.fourthline.cling.model.message.UpnpResponse operation, String defaultMsg) {
                Log.e(TAG, "Stop failed: " + defaultMsg);
            }
        };

        upnpService.getControlPoint().execute(stop);
    }

    private class BrowseRegistryListener extends DefaultRegistryListener {
        @Override
        public void remoteDeviceAdded(Registry registry, RemoteDevice device) {
            if (device.findService(AV_TRANSPORT_SERVICE) != null) {
                mainHandler.post(() -> {
                    for (OnDeviceRegistryListener listener : listeners) {
                        listener.onDeviceAdded(device);
                    }
                });
            }
        }

        @Override
        public void remoteDeviceUpdated(Registry registry, RemoteDevice device) {
            if (device.findService(AV_TRANSPORT_SERVICE) != null) {
                mainHandler.post(() -> {
                    for (OnDeviceRegistryListener listener : listeners) {
                        listener.onDeviceUpdated(device);
                    }
                });
            }
        }

        @Override
        public void remoteDeviceRemoved(Registry registry, RemoteDevice device) {
            mainHandler.post(() -> {
                for (OnDeviceRegistryListener listener : listeners) {
                    listener.onDeviceRemoved(device);
                }
            });
        }

        @Override
        public void localDeviceAdded(Registry registry, LocalDevice device) {
            // 忽略本地设备
        }

        @Override
        public void localDeviceRemoved(Registry registry, LocalDevice device) {
            // 忽略本地设备
        }
    }
}
