package com.github.tvbox.osc.dlna;

import org.fourthline.cling.DefaultUpnpServiceConfiguration;
import org.fourthline.cling.transport.impl.DatagramIOConfigurationImpl;
import org.fourthline.cling.transport.impl.DatagramIOImpl;
import org.fourthline.cling.transport.impl.DatagramProcessorImpl;
import org.fourthline.cling.transport.impl.GENAEventProcessorImpl;
import org.fourthline.cling.transport.impl.MulticastReceiverConfigurationImpl;
import org.fourthline.cling.transport.impl.MulticastReceiverImpl;
import org.fourthline.cling.transport.impl.NetworkAddressFactoryImpl;
import org.fourthline.cling.transport.impl.SOAPActionProcessorImpl;
import org.fourthline.cling.transport.impl.StreamClientConfigurationImpl;
import org.fourthline.cling.transport.impl.StreamClientImpl;
import org.fourthline.cling.transport.spi.DatagramIO;
import org.fourthline.cling.transport.spi.DatagramProcessor;
import org.fourthline.cling.transport.spi.GENAEventProcessor;
import org.fourthline.cling.transport.spi.InitializationException;
import org.fourthline.cling.transport.spi.MulticastReceiver;
import org.fourthline.cling.transport.spi.NetworkAddressFactory;
import org.fourthline.cling.transport.spi.SOAPActionProcessor;
import org.fourthline.cling.transport.spi.StreamClient;
import org.fourthline.cling.transport.spi.StreamServer;
import org.fourthline.cling.transport.spi.StreamServerConfiguration;

import java.net.InetAddress;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 不使用 StreamServer 的 UPnP 配置
 * 适用于 Android 环境，避免 Jetty/Servlet 依赖问题
 */
public class NoStreamServerConfiguration extends DefaultUpnpServiceConfiguration {

    private final ExecutorService defaultExecutorService;

    public NoStreamServerConfiguration() {
        this(0);
    }

    public NoStreamServerConfiguration(int streamListenPort) {
        super(streamListenPort, false);
        defaultExecutorService = createDefaultExecutorService();
    }

    @Override
    protected NetworkAddressFactory createNetworkAddressFactory(int streamListenPort) {
        return new NetworkAddressFactoryImpl(streamListenPort);
    }

    @Override
    protected DatagramProcessor createDatagramProcessor() {
        return new DatagramProcessorImpl();
    }

    @Override
    protected SOAPActionProcessor createSOAPActionProcessor() {
        return new SOAPActionProcessorImpl();
    }

    @Override
    protected GENAEventProcessor createGENAEventProcessor() {
        return new GENAEventProcessorImpl();
    }

    @Override
    public StreamClient createStreamClient() {
        return new StreamClientImpl(
                new StreamClientConfigurationImpl(getSyncProtocolExecutorService())
        );
    }

    @Override
    public MulticastReceiver createMulticastReceiver(NetworkAddressFactory networkAddressFactory) {
        return new MulticastReceiverImpl(
                new MulticastReceiverConfigurationImpl(
                        networkAddressFactory.getMulticastGroup(),
                        networkAddressFactory.getMulticastPort()
                )
        );
    }

    @Override
    public DatagramIO createDatagramIO(NetworkAddressFactory networkAddressFactory) {
        return new DatagramIOImpl(new DatagramIOConfigurationImpl());
    }

    @Override
    public StreamServer createStreamServer(NetworkAddressFactory networkAddressFactory) {
        // 返回一个空实现，不启动 HTTP 服务器
        // 作为控制点（客户端）使用时不需要 StreamServer
        return new NoOpStreamServer();
    }

    @Override
    public Executor getMulticastReceiverExecutor() {
        return defaultExecutorService;
    }

    @Override
    public Executor getDatagramIOExecutor() {
        return defaultExecutorService;
    }

    @Override
    public ExecutorService getStreamServerExecutorService() {
        return defaultExecutorService;
    }

    @Override
    public Executor getAsyncProtocolExecutor() {
        return defaultExecutorService;
    }

    @Override
    public ExecutorService getSyncProtocolExecutorService() {
        return defaultExecutorService;
    }

    @Override
    public Executor getRegistryMaintainerExecutor() {
        return defaultExecutorService;
    }

    @Override
    public Executor getRegistryListenerExecutor() {
        return defaultExecutorService;
    }

    @Override
    public void shutdown() {
        defaultExecutorService.shutdown();
        try {
            defaultExecutorService.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    protected ExecutorService createDefaultExecutorService() {
        return Executors.newCachedThreadPool();
    }

    /**
     * 空实现的 StreamServer，不启动任何 HTTP 服务
     */
    private static class NoOpStreamServer implements StreamServer<StreamServerConfiguration> {

        @Override
        public void init(InetAddress bindAddress, org.fourthline.cling.transport.Router router) throws InitializationException {
            // 空实现
        }

        @Override
        public int getPort() {
            return 0;
        }

        @Override
        public void stop() {
            // 空实现
        }

        @Override
        public StreamServerConfiguration getConfiguration() {
            return null;
        }

        @Override
        public void run() {
            // 空实现
        }
    }
}
