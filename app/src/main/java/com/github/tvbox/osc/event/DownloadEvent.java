package com.github.tvbox.osc.event;

/**
 * 下载事件类
 * 用于 EventBus 通信
 */
public class DownloadEvent {

    public static final int TYPE_ADD = 0;        // 添加任务
    public static final int TYPE_START = 1;      // 开始下载
    public static final int TYPE_PROGRESS = 2;   // 进度更新
    public static final int TYPE_PAUSE = 3;      // 暂停
    public static final int TYPE_RESUME = 4;     // 恢复
    public static final int TYPE_COMPLETE = 5;   // 完成
    public static final int TYPE_FAILED = 6;     // 失败
    public static final int TYPE_DELETE = 7;     // 删除
    public static final int TYPE_M3U8_PROGRESS = 8; // M3U8分片进度

    public int type;
    public int taskId;
    public long downloadedSize;
    public long totalSize;
    public long speed;           // 下载速度 bytes/s
    public String errorMsg;

    // M3U8相关字段
    public int downloadedSegments;  // 已下载分片数
    public int totalSegments;       // 总分片数

    // 剩余时间（秒）
    public long remainingSeconds;

    public DownloadEvent(int type) {
        this.type = type;
    }

    public DownloadEvent(int type, int taskId) {
        this.type = type;
        this.taskId = taskId;
    }

    public DownloadEvent(int type, int taskId, long downloadedSize, long totalSize, long speed) {
        this.type = type;
        this.taskId = taskId;
        this.downloadedSize = downloadedSize;
        this.totalSize = totalSize;
        this.speed = speed;
    }

    public static DownloadEvent progress(int taskId, long downloadedSize, long totalSize, long speed) {
        DownloadEvent event = new DownloadEvent(TYPE_PROGRESS, taskId, downloadedSize, totalSize, speed);
        // 计算剩余时间
        if (speed > 0 && totalSize > downloadedSize) {
            event.remainingSeconds = (totalSize - downloadedSize) / speed;
        }
        return event;
    }

    public static DownloadEvent complete(int taskId) {
        return new DownloadEvent(TYPE_COMPLETE, taskId);
    }

    public static DownloadEvent failed(int taskId, String errorMsg) {
        DownloadEvent event = new DownloadEvent(TYPE_FAILED, taskId);
        event.errorMsg = errorMsg;
        return event;
    }

    public static DownloadEvent add(int taskId) {
        return new DownloadEvent(TYPE_ADD, taskId);
    }

    public static DownloadEvent start(int taskId) {
        return new DownloadEvent(TYPE_START, taskId);
    }

    public static DownloadEvent pause(int taskId) {
        return new DownloadEvent(TYPE_PAUSE, taskId);
    }

    public static DownloadEvent resume(int taskId) {
        return new DownloadEvent(TYPE_RESUME, taskId);
    }

    public static DownloadEvent delete(int taskId) {
        return new DownloadEvent(TYPE_DELETE, taskId);
    }

    /**
     * M3U8分片进度事件
     */
    public static DownloadEvent m3u8Progress(int taskId, int downloadedSegments, int totalSegments, long speed) {
        DownloadEvent event = new DownloadEvent(TYPE_M3U8_PROGRESS, taskId);
        event.downloadedSegments = downloadedSegments;
        event.totalSegments = totalSegments;
        event.speed = speed;
        return event;
    }
}
