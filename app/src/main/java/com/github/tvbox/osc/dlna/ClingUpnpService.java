package com.github.tvbox.osc.dlna;

import android.content.Intent;
import android.os.IBinder;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceConfiguration;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.android.AndroidUpnpService;
import org.fourthline.cling.android.AndroidUpnpServiceConfiguration;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.protocol.ProtocolFactory;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.transport.Router;

/**
 * 自定义 DLNA UPnP 服务
 * 使用不依赖 Jetty StreamServer 的配置
 */
public class ClingUpnpService extends android.app.Service {

    protected UpnpService upnpService;
    protected Binder binder = new Binder();

    @Override
    public void onCreate() {
        super.onCreate();
        upnpService = new UpnpServiceImpl(createConfiguration()) {
            @Override
            protected Router createRouter(ProtocolFactory protocolFactory, Registry registry) {
                return ClingUpnpService.this.createRouter(
                        getConfiguration(),
                        protocolFactory,
                        ClingUpnpService.this);
            }

            @Override
            public synchronized void shutdown() {
                // 优雅关闭
                super.shutdown(true);
            }
        };
    }

    protected UpnpServiceConfiguration createConfiguration() {
        return new NoStreamServerConfiguration();
    }

    protected Router createRouter(UpnpServiceConfiguration configuration,
                                  ProtocolFactory protocolFactory,
                                  android.app.Service service) {
        return new org.fourthline.cling.android.AndroidRouter(configuration, protocolFactory, service);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        if (upnpService != null) {
            upnpService.shutdown();
        }
        super.onDestroy();
    }

    public class Binder extends android.os.Binder implements AndroidUpnpService {

        @Override
        public UpnpService get() {
            return upnpService;
        }

        @Override
        public UpnpServiceConfiguration getConfiguration() {
            return upnpService.getConfiguration();
        }

        @Override
        public Registry getRegistry() {
            return upnpService.getRegistry();
        }

        @Override
        public ControlPoint getControlPoint() {
            return upnpService.getControlPoint();
        }
    }
}
