package com.github.tvbox.osc.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.cache.DownloadTask;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.activity.DownloadActivity;
import com.github.tvbox.osc.util.DownloadManager;
import com.github.tvbox.osc.util.HawkConfig;
import com.github.tvbox.osc.util.M3U8Parser;
import com.github.tvbox.osc.util.OkGoHelper;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载服务
 * 前台服务，负责执行下载任务
 */
public class DownloadService extends Service {

    public static final String ACTION_START = "action_start";
    public static final String ACTION_PAUSE = "action_pause";
    public static final String ACTION_RESUME = "action_resume";
    public static final String ACTION_CANCEL = "action_cancel";
    public static final String ACTION_PAUSE_ALL = "action_pause_all";

    private static final String CHANNEL_ID = "DownloadChannel";
    private static final int NOTIFICATION_ID = 100;
    private static final int MAX_CONCURRENT_DOWNLOADS = 2;
    private static final int BUFFER_SIZE = 8192;
    private static final long PROGRESS_UPDATE_INTERVAL = 800; // 进度更新间隔 (ms)，增加间隔减少UI刷新频率
    private static final int MAX_RETRY_COUNT = 3; // 最大重试次数
    private static final long RETRY_DELAY_MS = 3000; // 重试延迟 (ms)
    private static final int M3U8_THREAD_COUNT = 3; // M3U8分片并发下载数

    private NotificationManager notificationManager;
    private ExecutorService downloadExecutor;
    private ExecutorService m3u8SegmentExecutor; // M3U8分片下载线程池
    private Handler mainHandler;
    private OkHttpClient okHttpClient;
    private ConnectivityManager connectivityManager;

    // 正在下载的任务
    private final Map<Integer, Runnable> downloadingTasks = new ConcurrentHashMap<>();
    // 任务重试次数
    private final Map<Integer, AtomicInteger> taskRetryCount = new ConcurrentHashMap<>();
    // 是否正在运行
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Override
    public void onCreate() {
        super.onCreate();
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        downloadExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_DOWNLOADS);
        m3u8SegmentExecutor = Executors.newFixedThreadPool(M3U8_THREAD_COUNT);
        mainHandler = new Handler(Looper.getMainLooper());
        okHttpClient = OkGoHelper.getDefaultClient();

        createNotificationChannel();
        EventBus.getDefault().register(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        int taskId = intent.getIntExtra("taskId", -1);

        // 启动前台服务
        startForeground(NOTIFICATION_ID, buildNotification(null, 0, 0));
        isRunning.set(true);

        if (ACTION_START.equals(action)) {
            if (taskId > 0) {
                startDownload(taskId);
            } else {
                // 检查并启动等待中的任务
                checkAndStartPendingTasks();
            }
        } else if (ACTION_PAUSE.equals(action) && taskId > 0) {
            pauseDownload(taskId);
        } else if (ACTION_RESUME.equals(action) && taskId > 0) {
            resumeDownload(taskId);
        } else if (ACTION_CANCEL.equals(action) && taskId > 0) {
            cancelDownload(taskId);
        } else if (ACTION_PAUSE_ALL.equals(action)) {
            pauseAllDownloads();
        }

        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        isRunning.set(false);
        EventBus.getDefault().unregister(this);
        pauseAllDownloads();
        downloadExecutor.shutdownNow();
        m3u8SegmentExecutor.shutdownNow();
        super.onDestroy();
    }

    /**
     * 创建通知渠道
     */
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "下载服务",
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("视频下载通知");
            channel.setShowBadge(false);
            notificationManager.createNotificationChannel(channel);
        }
    }

    /**
     * 构建通知
     */
    private Notification buildNotification(DownloadTask task, int progress, long speed) {
        Intent intent = new Intent(this, DownloadActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true);

        if (task != null) {
            String title = task.getVodName();
            String content;
            // 使用 progress > 0 || speed > 0 判断是否在下载中，而不是依赖 task 状态
            boolean isActivelyDownloading = progress > 0 || speed > 0 || downloadingTasks.containsKey(task.getId());
            if (isActivelyDownloading && progress < 100) {
                content = task.getEpisodeName() + " - " + progress + "% - " +
                        DownloadManager.formatSpeed(speed);
                builder.setProgress(100, progress, false);
            } else if (progress >= 100 || task.isCompleted()) {
                content = task.getEpisodeName() + " - 下载完成";
                builder.setProgress(0, 0, false);
            } else {
                content = task.getEpisodeName() + " - " + task.getStatusText();
                builder.setProgress(0, 0, false);
            }
            builder.setContentTitle(title);
            builder.setContentText(content);
        } else {
            int downloadingCount = downloadingTasks.size();
            if (downloadingCount > 0) {
                builder.setContentTitle("正在下载");
                builder.setContentText(downloadingCount + " 个任务下载中");
                builder.setProgress(0, 0, true);
            } else {
                builder.setContentTitle("下载服务");
                builder.setContentText("等待下载任务");
            }
        }

        // 添加暂停全部按钮
        if (!downloadingTasks.isEmpty()) {
            Intent pauseAllIntent = new Intent(this, DownloadService.class);
            pauseAllIntent.setAction(ACTION_PAUSE_ALL);
            PendingIntent pauseAllPendingIntent = PendingIntent.getService(
                    this, 1, pauseAllIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            builder.addAction(android.R.drawable.ic_media_pause, "暂停全部", pauseAllPendingIntent);
        }

        return builder.build();
    }

    /**
     * 更新通知
     */
    private void updateNotification(DownloadTask task, int progress, long speed) {
        if (isRunning.get()) {
            notificationManager.notify(NOTIFICATION_ID, buildNotification(task, progress, speed));
        }
    }

    /**
     * 开始下载
     */
    private void startDownload(int taskId) {
        // 检查并发数
        if (downloadingTasks.size() >= MAX_CONCURRENT_DOWNLOADS) {
            return;
        }

        // 检查是否已在下载
        if (downloadingTasks.containsKey(taskId)) {
            return;
        }

        DownloadTask task = DownloadManager.getInstance().getTask(taskId);
        if (task == null || !task.canStart()) {
            return;
        }

        // 更新状态为下载中
        DownloadManager.getInstance().updateStatus(taskId, DownloadTask.STATUS_DOWNLOADING);

        // 根据任务类型创建不同的下载任务
        Runnable runnable;
        if (task.isM3u8()) {
            runnable = new M3U8DownloadRunnable(task);
        } else {
            runnable = new DownloadRunnable(task);
        }

        downloadingTasks.put(taskId, runnable);
        downloadExecutor.execute(runnable);

        EventBus.getDefault().post(DownloadEvent.start(taskId));
    }

    /**
     * 暂停下载
     */
    private void pauseDownload(int taskId) {
        Runnable runnable = downloadingTasks.get(taskId);
        if (runnable != null) {
            if (runnable instanceof DownloadRunnable) {
                ((DownloadRunnable) runnable).pause();
            } else if (runnable instanceof M3U8DownloadRunnable) {
                ((M3U8DownloadRunnable) runnable).pause();
            }
        }
        DownloadManager.getInstance().updateStatus(taskId, DownloadTask.STATUS_PAUSED);
        downloadingTasks.remove(taskId);
        EventBus.getDefault().post(DownloadEvent.pause(taskId));

        // 检查是否还有下载任务
        checkServiceState();
    }

    /**
     * 恢复下载
     */
    private void resumeDownload(int taskId) {
        startDownload(taskId);
    }

    /**
     * 取消下载
     */
    private void cancelDownload(int taskId) {
        Runnable runnable = downloadingTasks.get(taskId);
        if (runnable != null) {
            if (runnable instanceof DownloadRunnable) {
                ((DownloadRunnable) runnable).cancel();
            } else if (runnable instanceof M3U8DownloadRunnable) {
                ((M3U8DownloadRunnable) runnable).cancel();
            }
        }
        downloadingTasks.remove(taskId);

        // 检查是否还有下载任务
        checkServiceState();
    }

    /**
     * 暂停所有下载
     */
    private void pauseAllDownloads() {
        for (Map.Entry<Integer, Runnable> entry : downloadingTasks.entrySet()) {
            Runnable runnable = entry.getValue();
            if (runnable instanceof DownloadRunnable) {
                ((DownloadRunnable) runnable).pause();
            } else if (runnable instanceof M3U8DownloadRunnable) {
                ((M3U8DownloadRunnable) runnable).pause();
            }
            DownloadManager.getInstance().updateStatus(entry.getKey(), DownloadTask.STATUS_PAUSED);
            EventBus.getDefault().post(DownloadEvent.pause(entry.getKey()));
        }
        downloadingTasks.clear();
        checkServiceState();
    }

    /**
     * 检查并启动等待中的任务
     */
    private void checkAndStartPendingTasks() {
        if (downloadingTasks.size() >= MAX_CONCURRENT_DOWNLOADS) {
            return;
        }

        List<DownloadTask> pendingTasks = DownloadManager.getInstance().getPendingTasks();
        for (DownloadTask task : pendingTasks) {
            if (downloadingTasks.size() >= MAX_CONCURRENT_DOWNLOADS) {
                break;
            }
            if (task.getStatus() == DownloadTask.STATUS_WAITING &&
                    !downloadingTasks.containsKey(task.getId())) {
                startDownload(task.getId());
            }
        }
    }

    /**
     * 检查服务状态
     */
    private void checkServiceState() {
        if (downloadingTasks.isEmpty()) {
            // 检查是否还有等待中的任务
            List<DownloadTask> pendingTasks = DownloadManager.getInstance().getPendingTasks();
            if (pendingTasks.isEmpty()) {
                // 没有任务了，停止服务
                stopForeground(true);
                stopSelf();
            } else {
                // 还有等待中的任务，继续启动
                checkAndStartPendingTasks();
            }
        }
    }

    /**
     * 下载完成回调
     */
    private void onDownloadComplete(int taskId) {
        downloadingTasks.remove(taskId);
        DownloadManager.getInstance().updateStatus(taskId, DownloadTask.STATUS_COMPLETED);
        EventBus.getDefault().post(DownloadEvent.complete(taskId));

        // 更新通知
        DownloadTask task = DownloadManager.getInstance().getTask(taskId);
        updateNotification(task, 100, 0);

        // 检查并启动下一个任务
        checkAndStartPendingTasks();
        checkServiceState();
    }

    /**
     * 下载失败回调
     */
    private void onDownloadFailed(int taskId, String errorMsg) {
        downloadingTasks.remove(taskId);

        // 检查是否需要重试
        AtomicInteger retryCount = taskRetryCount.computeIfAbsent(taskId, k -> new AtomicInteger(0));
        int currentRetry = retryCount.incrementAndGet();

        if (currentRetry <= MAX_RETRY_COUNT && isNetworkAvailable()) {
            // 延迟重试
            mainHandler.postDelayed(() -> {
                DownloadTask task = DownloadManager.getInstance().getTask(taskId);
                if (task != null && !task.isCompleted() && !task.isPaused()) {
                    DownloadManager.getInstance().updateStatus(taskId, DownloadTask.STATUS_WAITING);
                    startDownload(taskId);
                }
            }, RETRY_DELAY_MS * currentRetry); // 递增延迟
            return;
        }

        // 重试次数用尽，标记为失败
        taskRetryCount.remove(taskId);
        DownloadManager.getInstance().updateStatusWithError(taskId, DownloadTask.STATUS_FAILED, errorMsg);
        EventBus.getDefault().post(DownloadEvent.failed(taskId, errorMsg));

        // 检查并启动下一个任务
        checkAndStartPendingTasks();
        checkServiceState();
    }

    /**
     * 检查网络是否可用
     */
    private boolean isNetworkAvailable() {
        if (connectivityManager == null) {
            return true;
        }
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.isConnected();
    }

    /**
     * 检查是否是移动网络
     */
    private boolean isMobileNetwork() {
        if (connectivityManager == null) {
            return false;
        }
        NetworkInfo activeNetwork = connectivityManager.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE;
    }

    /**
     * 检查是否允许在移动网络下载
     */
    private boolean canDownloadOnCurrentNetwork() {
        if (!isNetworkAvailable()) {
            return false;
        }
        // 如果是移动网络，检查用户设置
        if (isMobileNetwork()) {
            return Hawk.get(HawkConfig.DOWNLOAD_ON_MOBILE, false);
        }
        return true;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        // 处理来自 DownloadManager 的事件
        if (event.type == DownloadEvent.TYPE_ADD) {
            checkAndStartPendingTasks();
        }
    }

    /**
     * 下载任务 Runnable
     */
    private class DownloadRunnable implements Runnable {
        private final DownloadTask task;
        private final AtomicBoolean isPaused = new AtomicBoolean(false);
        private final AtomicBoolean isCancelled = new AtomicBoolean(false);
        private Call currentCall;

        DownloadRunnable(DownloadTask task) {
            this.task = task;
        }

        void pause() {
            isPaused.set(true);
            if (currentCall != null) {
                currentCall.cancel();
            }
        }

        void cancel() {
            isCancelled.set(true);
            if (currentCall != null) {
                currentCall.cancel();
            }
        }

        @Override
        public void run() {
            InputStream inputStream = null;
            RandomAccessFile randomAccessFile = null;

            try {
                File file = new File(task.getFilePath());
                File parentDir = file.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                // 获取已下载的大小
                long downloadedSize = task.getDownloadedSize();
                if (file.exists() && downloadedSize > 0) {
                    // 断点续传
                    if (file.length() != downloadedSize) {
                        downloadedSize = file.length();
                    }
                } else {
                    downloadedSize = 0;
                }

                // 构建请求
                Request.Builder requestBuilder = new Request.Builder()
                        .url(task.getUrl())
                        .addHeader("User-Agent", "Mozilla/5.0");

                // 断点续传
                if (downloadedSize > 0) {
                    requestBuilder.addHeader("Range", "bytes=" + downloadedSize + "-");
                }

                Request request = requestBuilder.build();
                currentCall = okHttpClient.newCall(request);
                Response response = currentCall.execute();

                if (!response.isSuccessful()) {
                    throw new IOException("下载失败: " + response.code());
                }

                // 获取文件总大小
                long contentLength = response.body().contentLength();
                long totalSize;
                if (response.code() == 206) {
                    // 断点续传响应
                    totalSize = downloadedSize + contentLength;
                } else {
                    // 完整下载
                    totalSize = contentLength;
                    downloadedSize = 0;
                }

                // 更新总大小
                if (task.getTotalSize() != totalSize) {
                    DownloadManager.getInstance().updateTotalSize(task.getId(), totalSize);
                    task.setTotalSize(totalSize);
                }

                // 打开文件
                randomAccessFile = new RandomAccessFile(file, "rw");
                randomAccessFile.seek(downloadedSize);

                inputStream = response.body().byteStream();
                byte[] buffer = new byte[BUFFER_SIZE];
                int len;
                long lastUpdateTime = System.currentTimeMillis();
                long lastDownloadedSize = downloadedSize;

                while ((len = inputStream.read(buffer)) != -1) {
                    if (isPaused.get() || isCancelled.get()) {
                        break;
                    }

                    randomAccessFile.write(buffer, 0, len);
                    downloadedSize += len;

                    // 更新进度
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastUpdateTime >= PROGRESS_UPDATE_INTERVAL) {
                        long speed = (downloadedSize - lastDownloadedSize) * 1000 / (currentTime - lastUpdateTime);
                        lastUpdateTime = currentTime;
                        lastDownloadedSize = downloadedSize;

                        // 更新数据库
                        final long finalDownloadedSize = downloadedSize;
                        final long finalSpeed = speed;
                        DownloadManager.getInstance().updateProgress(
                                task.getId(),
                                DownloadTask.STATUS_DOWNLOADING,
                                finalDownloadedSize
                        );

                        // 发送进度事件
                        mainHandler.post(() -> {
                            EventBus.getDefault().post(DownloadEvent.progress(
                                    task.getId(),
                                    finalDownloadedSize,
                                    totalSize,
                                    finalSpeed
                            ));
                            updateNotification(task, (int) (finalDownloadedSize * 100 / totalSize), finalSpeed);
                        });
                    }
                }

                // 检查是否完成
                if (!isPaused.get() && !isCancelled.get()) {
                    // 最终更新
                    DownloadManager.getInstance().updateProgress(
                            task.getId(),
                            DownloadTask.STATUS_DOWNLOADING,
                            downloadedSize
                    );

                    mainHandler.post(() -> onDownloadComplete(task.getId()));
                } else if (isPaused.get()) {
                    // 保存当前进度
                    DownloadManager.getInstance().updateProgress(
                            task.getId(),
                            DownloadTask.STATUS_PAUSED,
                            downloadedSize
                    );
                }

            } catch (Exception e) {
                e.printStackTrace();
                if (!isPaused.get() && !isCancelled.get()) {
                    final String errorMsg = e.getMessage();
                    mainHandler.post(() -> onDownloadFailed(task.getId(), errorMsg));
                }
            } finally {
                try {
                    if (inputStream != null) {
                        inputStream.close();
                    }
                    if (randomAccessFile != null) {
                        randomAccessFile.close();
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    /**
     * M3U8 下载任务 Runnable
     * 支持多线程分片下载、AES-128解密、TS合并
     */
    private class M3U8DownloadRunnable implements Runnable {
        private final DownloadTask task;
        private final AtomicBoolean isPaused = new AtomicBoolean(false);
        private final AtomicBoolean isCancelled = new AtomicBoolean(false);
        private final AtomicBoolean hasError = new AtomicBoolean(false);
        private volatile String errorMessage;
        private byte[] encryptionKey;  // AES密钥缓存

        // 速度计算相关
        private final AtomicLong totalDownloadedBytes = new AtomicLong(0);
        private final AtomicInteger completedSegments = new AtomicInteger(0);
        private final LinkedList<Long> speedSamples = new LinkedList<>(); // 速度采样（平滑算法）
        private static final int SPEED_SAMPLE_COUNT = 5; // 采样数量

        // 分片下载状态
        private final ConcurrentHashMap<Integer, Boolean> segmentStatus = new ConcurrentHashMap<>();
        private final ConcurrentLinkedQueue<Integer> pendingSegments = new ConcurrentLinkedQueue<>();

        M3U8DownloadRunnable(DownloadTask task) {
            this.task = task;
        }

        void pause() {
            isPaused.set(true);
        }

        void cancel() {
            isCancelled.set(true);
        }

        @Override
        public void run() {
            try {
                // 解析M3U8
                String m3u8Content = task.getM3u8Content();
                if (m3u8Content == null || m3u8Content.isEmpty()) {
                    m3u8Content = fetchContent(task.getUrl());
                    if (m3u8Content == null) {
                        throw new IOException("无法获取M3U8内容");
                    }
                }

                M3U8Parser.M3U8Info m3u8Info = M3U8Parser.parse(m3u8Content, task.getUrl());

                // 如果是主播放列表，获取第一个变体流
                if (m3u8Info.isVariantPlaylist && !m3u8Info.variantUrls.isEmpty()) {
                    String variantUrl = m3u8Info.variantUrls.get(0);
                    m3u8Content = fetchContent(variantUrl);
                    if (m3u8Content == null) {
                        throw new IOException("无法获取变体流内容");
                    }
                    m3u8Info = M3U8Parser.parse(m3u8Content, variantUrl);
                }

                final List<M3U8Parser.Segment> segments = m3u8Info.segments;
                final int totalSegments = segments.size();

                if (totalSegments == 0) {
                    throw new IOException("M3U8没有分片");
                }

                // 更新总分片数
                if (task.getTotalSegments() != totalSegments) {
                    DownloadManager.getInstance().updateM3u8Info(
                            task.getId(), true, totalSegments, m3u8Content);
                    task.setTotalSegments(totalSegments);
                }

                // 获取临时目录
                final File tempDir = DownloadManager.getInstance().getM3u8TempDir(task.getId());

                // 下载加密密钥（如果有）
                final M3U8Parser.M3U8Info finalM3u8Info = m3u8Info;
                if (m3u8Info.encryptionInfo != null && m3u8Info.encryptionInfo.isEncrypted()) {
                    encryptionKey = fetchEncryptionKey(m3u8Info.encryptionInfo.keyUrl);
                    if (encryptionKey == null) {
                        throw new IOException("无法获取加密密钥");
                    }
                }

                // 初始化分片状态
                int startSegment = task.getDownloadedSegments();
                for (int i = 0; i < totalSegments; i++) {
                    File segmentFile = new File(tempDir, String.format("%05d.ts", i));
                    if (i < startSegment && segmentFile.exists() && segmentFile.length() > 0) {
                        segmentStatus.put(i, true);
                        completedSegments.incrementAndGet();
                        totalDownloadedBytes.addAndGet(segmentFile.length());
                    } else {
                        segmentStatus.put(i, false);
                        pendingSegments.add(i);
                    }
                }

                // 启动进度更新线程
                final AtomicBoolean progressRunning = new AtomicBoolean(true);
                Thread progressThread = new Thread(() -> {
                    long lastBytes = totalDownloadedBytes.get();
                    long lastTime = System.currentTimeMillis();

                    while (progressRunning.get() && !isPaused.get() && !isCancelled.get()) {
                        try {
                            Thread.sleep(PROGRESS_UPDATE_INTERVAL);
                        } catch (InterruptedException e) {
                            break;
                        }

                        long currentBytes = totalDownloadedBytes.get();
                        long currentTime = System.currentTimeMillis();
                        long timeDiff = currentTime - lastTime;

                        if (timeDiff > 0) {
                            long instantSpeed = (currentBytes - lastBytes) * 1000 / timeDiff;
                            long smoothSpeed = calculateSmoothSpeed(instantSpeed);

                            lastBytes = currentBytes;
                            lastTime = currentTime;

                            int completed = completedSegments.get();
                            int progress = completed * 100 / totalSegments;

                            // 更新数据库
                            DownloadManager.getInstance().updateM3u8Progress(task.getId(), completed);

                            // 发送进度事件
                            final long finalSpeed = smoothSpeed;
                            final int finalCompleted = completed;
                            mainHandler.post(() -> {
                                EventBus.getDefault().post(DownloadEvent.m3u8Progress(
                                        task.getId(),
                                        finalCompleted,
                                        totalSegments,
                                        finalSpeed
                                ));
                                updateNotification(task, progress, finalSpeed);
                            });
                        }
                    }
                });
                progressThread.start();

                // 分片重试计数
                final ConcurrentHashMap<Integer, AtomicInteger> segmentRetryCount = new ConcurrentHashMap<>();
                final int MAX_SEGMENT_RETRY = 5; // 每个分片最大重试次数

                // 使用多线程下载分片
                List<Future<?>> futures = new ArrayList<>();
                for (int t = 0; t < M3U8_THREAD_COUNT; t++) {
                    Future<?> future = m3u8SegmentExecutor.submit(() -> {
                        while (!isPaused.get() && !isCancelled.get() && !hasError.get()) {
                            Integer segmentIndex = pendingSegments.poll();
                            if (segmentIndex == null) {
                                // 队列为空，检查是否还有其他线程在工作
                                // 短暂等待后再检查一次，避免竞态条件
                                try {
                                    Thread.sleep(100);
                                } catch (InterruptedException e) {
                                    break;
                                }
                                segmentIndex = pendingSegments.poll();
                                if (segmentIndex == null) {
                                    break;
                                }
                            }

                            try {
                                M3U8Parser.Segment segment = segments.get(segmentIndex);
                                File segmentFile = new File(tempDir, String.format("%05d.ts", segmentIndex));

                                // 下载分片
                                byte[] segmentData = downloadSegment(segment.url);
                                if (segmentData == null) {
                                    if (!isPaused.get() && !isCancelled.get()) {
                                        // 检查重试次数
                                        AtomicInteger retryCount = segmentRetryCount.computeIfAbsent(
                                                segmentIndex, k -> new AtomicInteger(0));
                                        if (retryCount.incrementAndGet() <= MAX_SEGMENT_RETRY) {
                                            // 重新加入队列重试
                                            pendingSegments.add(segmentIndex);
                                        } else {
                                            // 超过重试次数，标记错误
                                            hasError.set(true);
                                            errorMessage = "分片 " + segmentIndex + " 下载失败，已重试 " + MAX_SEGMENT_RETRY + " 次";
                                        }
                                    }
                                    continue;
                                }

                                // 解密（如果需要）
                                M3U8Parser.EncryptionInfo encInfo = segment.encryptionInfo != null ?
                                        segment.encryptionInfo : finalM3u8Info.encryptionInfo;
                                if (encInfo != null && encInfo.isEncrypted()) {
                                    byte[] key = encryptionKey;
                                    if (segment.encryptionInfo != null &&
                                            segment.encryptionInfo.keyUrl != null &&
                                            !segment.encryptionInfo.keyUrl.equals(finalM3u8Info.encryptionInfo.keyUrl)) {
                                        key = fetchEncryptionKey(segment.encryptionInfo.keyUrl);
                                    }
                                    segmentData = decryptSegment(segmentData, key, encInfo.iv, segmentIndex);
                                }

                                // 保存分片
                                saveSegment(segmentFile, segmentData);

                                // 更新状态
                                segmentStatus.put(segmentIndex, true);
                                completedSegments.incrementAndGet();
                                totalDownloadedBytes.addAndGet(segmentData.length);

                            } catch (Exception e) {
                                e.printStackTrace();
                                if (!isPaused.get() && !isCancelled.get()) {
                                    // 检查重试次数
                                    AtomicInteger retryCount = segmentRetryCount.computeIfAbsent(
                                            segmentIndex, k -> new AtomicInteger(0));
                                    if (retryCount.incrementAndGet() <= MAX_SEGMENT_RETRY) {
                                        pendingSegments.add(segmentIndex);
                                    } else {
                                        hasError.set(true);
                                        errorMessage = "分片 " + segmentIndex + " 处理失败: " + e.getMessage();
                                    }
                                }
                            }
                        }
                    });
                    futures.add(future);
                }

                // 等待所有下载线程完成
                for (Future<?> future : futures) {
                    try {
                        future.get();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                // 停止进度线程
                progressRunning.set(false);
                progressThread.interrupt();

                // 检查是否所有分片都下载完成
                int finalCompleted = completedSegments.get();

                // 额外检查：验证所有分片文件是否存在
                int missingSegments = 0;
                for (int i = 0; i < totalSegments; i++) {
                    File segmentFile = new File(tempDir, String.format("%05d.ts", i));
                    if (!segmentFile.exists() || segmentFile.length() == 0) {
                        missingSegments++;
                    }
                }

                if (!isPaused.get() && !isCancelled.get() && !hasError.get() &&
                        finalCompleted == totalSegments && missingSegments == 0) {
                    // 合并分片
                    mainHandler.post(() -> updateNotification(task, 99, 0));

                    boolean mergeSuccess = mergeSegments(tempDir, task.getFilePath(), totalSegments);
                    if (!mergeSuccess) {
                        throw new IOException("合并分片失败");
                    }

                    // 清理临时文件
                    DownloadManager.getInstance().cleanM3u8TempDir(task.getId());

                    // 完成
                    mainHandler.post(() -> onDownloadComplete(task.getId()));
                } else if (isPaused.get()) {
                    // 保存当前进度
                    DownloadManager.getInstance().updateM3u8Progress(task.getId(), finalCompleted);
                } else if (hasError.get()) {
                    throw new IOException(errorMessage != null ? errorMessage : "下载失败");
                } else if (missingSegments > 0 || finalCompleted < totalSegments) {
                    // 有分片缺失，标记为失败
                    int actualMissing = totalSegments - finalCompleted;
                    throw new IOException("下载未完成，缺少 " + actualMissing + " 个分片");
                }

            } catch (Exception e) {
                e.printStackTrace();
                if (!isPaused.get() && !isCancelled.get()) {
                    final String errorMsg = e.getMessage();
                    mainHandler.post(() -> onDownloadFailed(task.getId(), errorMsg));
                }
            }
        }

        /**
         * 计算平滑速度（移动平均）
         */
        private synchronized long calculateSmoothSpeed(long instantSpeed) {
            speedSamples.addLast(instantSpeed);
            if (speedSamples.size() > SPEED_SAMPLE_COUNT) {
                speedSamples.removeFirst();
            }

            long sum = 0;
            for (Long sample : speedSamples) {
                sum += sample;
            }
            return sum / speedSamples.size();
        }

        /**
         * 获取内容
         */
        private String fetchContent(String url) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build();
                Call call = okHttpClient.newCall(request);
                Response response = call.execute();
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().string();
                }
            } catch (Exception e) {
                if (!isPaused.get() && !isCancelled.get()) {
                    e.printStackTrace();
                }
            }
            return null;
        }

        /**
         * 下载分片（带重试）
         */
        private byte[] downloadSegment(String url) {
            int retryCount = 0;
            while (retryCount < 3 && !isPaused.get() && !isCancelled.get()) {
                try {
                    Request request = new Request.Builder()
                            .url(url)
                            .addHeader("User-Agent", "Mozilla/5.0")
                            .build();
                    Call call = okHttpClient.newCall(request);
                    Response response = call.execute();
                    if (response.isSuccessful() && response.body() != null) {
                        return response.body().bytes();
                    }
                } catch (Exception e) {
                    if (isPaused.get() || isCancelled.get()) {
                        return null;
                    }
                    retryCount++;
                    if (retryCount < 3) {
                        try {
                            Thread.sleep(500 * retryCount);
                        } catch (InterruptedException ie) {
                            return null;
                        }
                    }
                }
            }
            return null;
        }

        /**
         * 获取加密密钥
         */
        private byte[] fetchEncryptionKey(String keyUrl) {
            try {
                Request request = new Request.Builder()
                        .url(keyUrl)
                        .addHeader("User-Agent", "Mozilla/5.0")
                        .build();
                Call call = okHttpClient.newCall(request);
                Response response = call.execute();
                if (response.isSuccessful() && response.body() != null) {
                    return response.body().bytes();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return null;
        }

        /**
         * 解密分片 (AES-128-CBC)
         */
        private byte[] decryptSegment(byte[] data, byte[] key, String ivString, int segmentIndex) {
            try {
                byte[] iv;
                if (ivString != null && !ivString.isEmpty()) {
                    iv = parseIV(ivString);
                } else {
                    iv = new byte[16];
                    iv[15] = (byte) (segmentIndex & 0xFF);
                    iv[14] = (byte) ((segmentIndex >> 8) & 0xFF);
                    iv[13] = (byte) ((segmentIndex >> 16) & 0xFF);
                    iv[12] = (byte) ((segmentIndex >> 24) & 0xFF);
                }

                Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
                SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
                IvParameterSpec ivSpec = new IvParameterSpec(iv);
                cipher.init(Cipher.DECRYPT_MODE, keySpec, ivSpec);

                return cipher.doFinal(data);
            } catch (Exception e) {
                e.printStackTrace();
                return data;
            }
        }

        /**
         * 解析IV字符串
         */
        private byte[] parseIV(String ivString) {
            byte[] iv = new byte[16];
            try {
                String hex = ivString;
                if (hex.startsWith("0x") || hex.startsWith("0X")) {
                    hex = hex.substring(2);
                }
                while (hex.length() < 32) {
                    hex = "0" + hex;
                }
                for (int i = 0; i < 16; i++) {
                    iv[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            return iv;
        }

        /**
         * 保存分片到文件
         */
        private void saveSegment(File file, byte[] data) throws IOException {
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(file);
                fos.write(data);
                fos.flush();
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        }

        /**
         * 合并所有分片为最终文件
         */
        private boolean mergeSegments(File tempDir, String outputPath, int totalSegments) {
            FileOutputStream fos = null;
            FileInputStream fis = null;
            try {
                File outputFile = new File(outputPath);
                File parentDir = outputFile.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    parentDir.mkdirs();
                }

                fos = new FileOutputStream(outputFile);
                byte[] buffer = new byte[BUFFER_SIZE * 4];

                for (int i = 0; i < totalSegments; i++) {
                    if (isCancelled.get()) {
                        fos.close();
                        outputFile.delete();
                        return false;
                    }

                    File segmentFile = new File(tempDir, String.format("%05d.ts", i));
                    if (!segmentFile.exists()) {
                        return false;
                    }

                    fis = new FileInputStream(segmentFile);
                    int len;
                    while ((len = fis.read(buffer)) != -1) {
                        fos.write(buffer, 0, len);
                    }
                    fis.close();
                    fis = null;
                }

                fos.flush();
                return true;
            } catch (Exception e) {
                e.printStackTrace();
                return false;
            } finally {
                try {
                    if (fis != null) fis.close();
                    if (fos != null) fos.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
