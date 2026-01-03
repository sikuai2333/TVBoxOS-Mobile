package com.github.tvbox.osc.cache;

import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.Ignore;
import androidx.room.PrimaryKey;

import java.io.Serializable;

/**
 * 下载任务实体类
 */
@Entity(tableName = "downloadTask")
public class DownloadTask implements Serializable {

    // 下载状态常量
    public static final int STATUS_WAITING = 0;     // 等待中
    public static final int STATUS_DOWNLOADING = 1; // 下载中
    public static final int STATUS_PAUSED = 2;      // 已暂停
    public static final int STATUS_COMPLETED = 3;   // 已完成
    public static final int STATUS_FAILED = 4;      // 下载失败

    @PrimaryKey(autoGenerate = true)
    private int id;

    @ColumnInfo(name = "url")
    public String url;              // 下载地址

    @ColumnInfo(name = "fileName")
    public String fileName;         // 文件名

    @ColumnInfo(name = "filePath")
    public String filePath;         // 保存路径

    @ColumnInfo(name = "vodName")
    public String vodName;          // 视频名称

    @ColumnInfo(name = "episodeName")
    public String episodeName;      // 集数名称

    @ColumnInfo(name = "status")
    public int status;              // 状态

    @ColumnInfo(name = "totalSize")
    public long totalSize;          // 总大小 (bytes)

    @ColumnInfo(name = "downloadedSize")
    public long downloadedSize;     // 已下载大小 (bytes)

    @ColumnInfo(name = "createTime")
    public long createTime;         // 创建时间

    @ColumnInfo(name = "updateTime")
    public long updateTime;         // 更新时间

    @ColumnInfo(name = "errorMsg")
    public String errorMsg;         // 错误信息

    @ColumnInfo(name = "isM3u8")
    public boolean isM3u8;          // 是否是m3u8格式

    @ColumnInfo(name = "totalSegments")
    public int totalSegments;       // m3u8总分片数

    @ColumnInfo(name = "downloadedSegments")
    public int downloadedSegments;  // 已下载分片数

    @ColumnInfo(name = "m3u8Content")
    public String m3u8Content;      // m3u8文件内容（用于恢复下载）

    // 运行时临时字段（不存储到数据库）
    @Ignore
    public long currentSpeed;       // 当前下载速度 (bytes/s)

    public DownloadTask() {
    }

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFilePath() {
        return filePath;
    }

    public void setFilePath(String filePath) {
        this.filePath = filePath;
    }

    public String getVodName() {
        return vodName;
    }

    public void setVodName(String vodName) {
        this.vodName = vodName;
    }

    public String getEpisodeName() {
        return episodeName;
    }

    public void setEpisodeName(String episodeName) {
        this.episodeName = episodeName;
    }

    public int getStatus() {
        return status;
    }

    public void setStatus(int status) {
        this.status = status;
    }

    public long getTotalSize() {
        return totalSize;
    }

    public void setTotalSize(long totalSize) {
        this.totalSize = totalSize;
    }

    public long getDownloadedSize() {
        return downloadedSize;
    }

    public void setDownloadedSize(long downloadedSize) {
        this.downloadedSize = downloadedSize;
    }

    public long getCreateTime() {
        return createTime;
    }

    public void setCreateTime(long createTime) {
        this.createTime = createTime;
    }

    public long getUpdateTime() {
        return updateTime;
    }

    public void setUpdateTime(long updateTime) {
        this.updateTime = updateTime;
    }

    public String getErrorMsg() {
        return errorMsg;
    }

    public void setErrorMsg(String errorMsg) {
        this.errorMsg = errorMsg;
    }

    public boolean isM3u8() {
        return isM3u8;
    }

    public void setM3u8(boolean m3u8) {
        isM3u8 = m3u8;
    }

    public int getTotalSegments() {
        return totalSegments;
    }

    public void setTotalSegments(int totalSegments) {
        this.totalSegments = totalSegments;
    }

    public int getDownloadedSegments() {
        return downloadedSegments;
    }

    public void setDownloadedSegments(int downloadedSegments) {
        this.downloadedSegments = downloadedSegments;
    }

    public String getM3u8Content() {
        return m3u8Content;
    }

    public void setM3u8Content(String m3u8Content) {
        this.m3u8Content = m3u8Content;
    }

    /**
     * 获取下载进度百分比
     */
    public int getProgress() {
        if (isM3u8) {
            if (totalSegments <= 0) {
                return 0;
            }
            return (int) (downloadedSegments * 100 / totalSegments);
        }
        if (totalSize <= 0) {
            return 0;
        }
        return (int) (downloadedSize * 100 / totalSize);
    }

    /**
     * 获取状态文本
     */
    public String getStatusText() {
        switch (status) {
            case STATUS_WAITING:
                return "等待中";
            case STATUS_DOWNLOADING:
                return "下载中";
            case STATUS_PAUSED:
                return "已暂停";
            case STATUS_COMPLETED:
                return "已完成";
            case STATUS_FAILED:
                return "下载失败";
            default:
                return "未知";
        }
    }

    /**
     * 是否可以开始/恢复下载
     */
    public boolean canStart() {
        return status == STATUS_WAITING || status == STATUS_PAUSED || status == STATUS_FAILED;
    }

    /**
     * 是否可以暂停
     */
    public boolean canPause() {
        return status == STATUS_DOWNLOADING;
    }

    /**
     * 是否正在下载
     */
    public boolean isDownloading() {
        return status == STATUS_DOWNLOADING;
    }

    /**
     * 是否已完成
     */
    public boolean isCompleted() {
        return status == STATUS_COMPLETED;
    }

    /**
     * 是否已暂停
     */
    public boolean isPaused() {
        return status == STATUS_PAUSED;
    }

    /**
     * 获取当前下载速度
     */
    public long getCurrentSpeed() {
        return currentSpeed;
    }

    /**
     * 设置当前下载速度
     */
    public void setCurrentSpeed(long speed) {
        this.currentSpeed = speed;
    }
}
