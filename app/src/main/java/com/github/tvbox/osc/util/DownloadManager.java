package com.github.tvbox.osc.util;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;

import com.blankj.utilcode.util.GsonUtils;
import com.blankj.utilcode.util.ToastUtils;
import com.github.tvbox.osc.base.App;
import com.github.tvbox.osc.bean.VideoInfo;
import com.github.tvbox.osc.cache.DownloadTask;
import com.github.tvbox.osc.cache.DownloadTaskDao;
import com.github.tvbox.osc.data.AppDataManager;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.service.DownloadService;
import com.github.tvbox.osc.ui.activity.LocalPlayActivity;
import com.orhanobut.hawk.Hawk;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 下载管理器
 * 单例模式，管理所有下载任务
 */
public class DownloadManager {

    private static volatile DownloadManager instance;
    private final DownloadTaskDao taskDao;
    private final ExecutorService executor;
    private final OkHttpClient okHttpClient;
    private File downloadDir;

    private DownloadManager() {
        taskDao = AppDataManager.get().getDownloadTaskDao();
        executor = Executors.newSingleThreadExecutor();
        okHttpClient = OkGoHelper.getDefaultClient();
        initDownloadDir();
    }

    public static DownloadManager getInstance() {
        if (instance == null) {
            synchronized (DownloadManager.class) {
                if (instance == null) {
                    instance = new DownloadManager();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化下载目录
     */
    private void initDownloadDir() {
        File externalDir = App.getInstance().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (externalDir != null) {
            downloadDir = new File(externalDir, "TVBox");
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
        } else {
            // 回退到内部存储
            downloadDir = new File(App.getInstance().getFilesDir(), "downloads");
            if (!downloadDir.exists()) {
                downloadDir.mkdirs();
            }
        }
    }

    /**
     * 获取下载目录
     */
    public File getDownloadDir() {
        return downloadDir;
    }

    /**
     * 添加下载任务
     */
    public void addTask(String url, String vodName, String episodeName) {
        executor.execute(() -> {
            // 检查是否已存在
            DownloadTask existingTask = taskDao.getByUrl(url);
            if (existingTask != null) {
                // 任务已存在
                return;
            }

            // 检测是否是M3U8
            boolean isM3u8 = M3U8Parser.isM3u8Url(url);
            String m3u8Content = null;
            int totalSegments = 0;

            if (isM3u8) {
                // 尝试获取M3U8内容
                try {
                    m3u8Content = fetchM3u8Content(url);
                    if (m3u8Content != null && M3U8Parser.isM3u8Content(m3u8Content)) {
                        M3U8Parser.M3U8Info m3u8Info = M3U8Parser.parse(m3u8Content, url);

                        // 如果是主播放列表（多码率），选择第一个变体流
                        if (m3u8Info.isVariantPlaylist && !m3u8Info.variantUrls.isEmpty()) {
                            String variantUrl = m3u8Info.variantUrls.get(0);
                            m3u8Content = fetchM3u8Content(variantUrl);
                            if (m3u8Content != null) {
                                m3u8Info = M3U8Parser.parse(m3u8Content, variantUrl);
                            }
                        }

                        totalSegments = m3u8Info.getTotalSegments();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    // 解析失败，当作普通文件处理
                    isM3u8 = false;
                    m3u8Content = null;
                }
            }

            // 生成文件名
            String fileName = generateFileName(url, vodName, episodeName, isM3u8);
            String filePath = new File(downloadDir, fileName).getAbsolutePath();

            // 创建任务
            DownloadTask task = new DownloadTask();
            task.setUrl(url);
            task.setFileName(fileName);
            task.setFilePath(filePath);
            task.setVodName(vodName);
            task.setEpisodeName(episodeName);
            task.setStatus(DownloadTask.STATUS_WAITING);
            task.setTotalSize(0);
            task.setDownloadedSize(0);
            task.setCreateTime(System.currentTimeMillis());
            task.setUpdateTime(System.currentTimeMillis());
            task.setM3u8(isM3u8);
            task.setTotalSegments(totalSegments);
            task.setDownloadedSegments(0);
            task.setM3u8Content(m3u8Content);

            // 保存到数据库
            long id = taskDao.insert(task);
            task.setId((int) id);

            // 发送事件
            EventBus.getDefault().post(DownloadEvent.add(task.getId()));

            // 启动下载服务
            startDownloadService(task.getId());
        });
    }

    /**
     * 获取M3U8内容
     */
    private String fetchM3u8Content(String url) {
        try {
            Request request = new Request.Builder()
                    .url(url)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build();
            Response response = okHttpClient.newCall(request).execute();
            if (response.isSuccessful() && response.body() != null) {
                return response.body().string();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    /**
     * 生成文件名
     */
    private String generateFileName(String url, String vodName, String episodeName, boolean isM3u8) {
        // 获取文件扩展名
        String extension;
        if (isM3u8) {
            extension = "mp4"; // M3U8合并后输出为mp4
        } else {
            extension = getFileExtension(url);
            if (TextUtils.isEmpty(extension)) {
                extension = "mp4";
            }
        }

        // 清理文件名中的非法字符
        String cleanVodName = cleanFileName(vodName);
        String cleanEpisodeName = cleanFileName(episodeName);

        // 生成唯一文件名
        String baseName = cleanVodName + "_" + cleanEpisodeName;
        if (baseName.length() > 100) {
            baseName = baseName.substring(0, 100);
        }

        return baseName + "_" + System.currentTimeMillis() + "." + extension;
    }

    /**
     * 获取文件扩展名
     */
    private String getFileExtension(String url) {
        try {
            String path = Uri.parse(url).getPath();
            if (path != null) {
                int lastDot = path.lastIndexOf('.');
                if (lastDot > 0 && lastDot < path.length() - 1) {
                    String ext = path.substring(lastDot + 1).toLowerCase();
                    // 只接受常见视频格式
                    if (ext.matches("mp4|mkv|avi|flv|wmv|mov|m4v|ts|m3u8")) {
                        return ext;
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return "mp4";
    }

    /**
     * 清理文件名中的非法字符
     */
    private String cleanFileName(String name) {
        if (TextUtils.isEmpty(name)) {
            return "video";
        }
        return name.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
    }

    /**
     * 暂停任务
     */
    public void pauseTask(int taskId) {
        executor.execute(() -> {
            DownloadTask task = taskDao.getById(taskId);
            if (task != null && task.canPause()) {
                taskDao.updateStatus(taskId, DownloadTask.STATUS_PAUSED, System.currentTimeMillis());
                EventBus.getDefault().post(DownloadEvent.pause(taskId));
            }
        });
    }

    /**
     * 恢复任务
     */
    public void resumeTask(int taskId) {
        executor.execute(() -> {
            DownloadTask task = taskDao.getById(taskId);
            if (task != null && task.canStart()) {
                taskDao.updateStatus(taskId, DownloadTask.STATUS_WAITING, System.currentTimeMillis());
                EventBus.getDefault().post(DownloadEvent.resume(taskId));
                startDownloadService(taskId);
            }
        });
    }

    /**
     * 删除任务
     */
    public void deleteTask(int taskId, boolean deleteFile) {
        executor.execute(() -> {
            DownloadTask task = taskDao.getById(taskId);
            if (task != null) {
                // 如果正在下载，先暂停
                if (task.isDownloading()) {
                    taskDao.updateStatus(taskId, DownloadTask.STATUS_PAUSED, System.currentTimeMillis());
                }

                // 删除文件
                if (deleteFile && !TextUtils.isEmpty(task.getFilePath())) {
                    File file = new File(task.getFilePath());
                    if (file.exists()) {
                        file.delete();
                    }
                }

                // 删除数据库记录
                taskDao.deleteById(taskId);
                EventBus.getDefault().post(DownloadEvent.delete(taskId));
            }
        });
    }

    /**
     * 获取所有任务
     */
    public List<DownloadTask> getAllTasks() {
        return taskDao.getAll();
    }

    /**
     * 获取任务
     */
    public DownloadTask getTask(int taskId) {
        return taskDao.getById(taskId);
    }

    /**
     * 检查URL是否已存在
     */
    public boolean isTaskExists(String url) {
        return taskDao.getByUrl(url) != null;
    }

    /**
     * 获取等待中和下载中的任务
     */
    public List<DownloadTask> getPendingTasks() {
        return taskDao.getByStatuses(DownloadTask.STATUS_WAITING, DownloadTask.STATUS_DOWNLOADING);
    }

    /**
     * 获取下载中的任务数量
     */
    public int getDownloadingCount() {
        return taskDao.countByStatus(DownloadTask.STATUS_DOWNLOADING);
    }

    /**
     * 更新任务进度
     */
    public void updateProgress(int taskId, int status, long downloadedSize) {
        taskDao.updateProgress(taskId, status, downloadedSize, System.currentTimeMillis());
    }

    /**
     * 更新任务状态
     */
    public void updateStatus(int taskId, int status) {
        taskDao.updateStatus(taskId, status, System.currentTimeMillis());
    }

    /**
     * 更新任务状态（带错误信息）
     */
    public void updateStatusWithError(int taskId, int status, String errorMsg) {
        taskDao.updateStatusWithError(taskId, status, errorMsg, System.currentTimeMillis());
    }

    /**
     * 更新任务总大小
     */
    public void updateTotalSize(int taskId, long totalSize) {
        taskDao.updateTotalSize(taskId, totalSize, System.currentTimeMillis());
    }

    /**
     * 更新M3U8下载进度
     */
    public void updateM3u8Progress(int taskId, int downloadedSegments) {
        taskDao.updateM3u8Progress(taskId, downloadedSegments, System.currentTimeMillis());
    }

    /**
     * 更新M3U8信息
     */
    public void updateM3u8Info(int taskId, boolean isM3u8, int totalSegments, String m3u8Content) {
        taskDao.updateM3u8Info(taskId, isM3u8, totalSegments, m3u8Content, System.currentTimeMillis());
    }

    /**
     * 获取M3U8分片临时目录
     */
    public File getM3u8TempDir(int taskId) {
        File tempDir = new File(downloadDir, ".m3u8_temp_" + taskId);
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }
        return tempDir;
    }

    /**
     * 清理M3U8分片临时目录
     */
    public void cleanM3u8TempDir(int taskId) {
        File tempDir = new File(downloadDir, ".m3u8_temp_" + taskId);
        if (tempDir.exists()) {
            deleteDirectory(tempDir);
        }
    }

    /**
     * 递归删除目录
     */
    private void deleteDirectory(File dir) {
        if (dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    deleteDirectory(file);
                }
            }
        }
        dir.delete();
    }

    /**
     * 启动下载服务
     */
    private void startDownloadService(int taskId) {
        Context context = App.getInstance();
        Intent intent = new Intent(context, DownloadService.class);
        intent.putExtra("taskId", taskId);
        intent.setAction(DownloadService.ACTION_START);
        ContextCompat.startForegroundService(context, intent);
    }

    /**
     * 打开已下载的文件（使用内置播放器）
     */
    public void openFile(Context context, DownloadTask task) {
        if (task == null || TextUtils.isEmpty(task.getFilePath())) {
            ToastUtils.showShort("文件路径无效");
            return;
        }

        File file = new File(task.getFilePath());
        if (!file.exists()) {
            ToastUtils.showShort("文件不存在");
            return;
        }

        try {
            // 使用内置播放器播放
            VideoInfo videoInfo = new VideoInfo();
            videoInfo.setPath(task.getFilePath());
            videoInfo.setDisplayName(task.getVodName() + " - " + task.getEpisodeName());
            videoInfo.setBucketDisplayName(task.getVodName());

            List<VideoInfo> videoList = new ArrayList<>();
            videoList.add(videoInfo);

            Bundle bundle = new Bundle();
            bundle.putString("videoList", GsonUtils.toJson(videoList));
            bundle.putInt("position", 0);

            Intent intent = new Intent(context, LocalPlayActivity.class);
            intent.putExtras(bundle);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            ToastUtils.showShort("播放失败: " + e.getMessage());
        }
    }

    /**
     * 使用系统播放器打开文件
     */
    public void openFileWithSystem(Context context, DownloadTask task) {
        if (task == null || TextUtils.isEmpty(task.getFilePath())) {
            return;
        }

        File file = new File(task.getFilePath());
        if (!file.exists()) {
            return;
        }

        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            Uri uri = FileProvider.getUriForFile(context,
                    context.getPackageName() + ".fileprovider", file);
            String mimeType = getMimeType(task.getFileName());
            intent.setDataAndType(uri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 检查是否是移动网络
     */
    public boolean isMobileNetwork() {
        ConnectivityManager cm = (ConnectivityManager) App.getInstance()
                .getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
        return activeNetwork != null && activeNetwork.getType() == ConnectivityManager.TYPE_MOBILE;
    }

    /**
     * 检查是否允许在移动网络下载
     */
    public boolean canDownloadOnMobile() {
        return Hawk.get(HawkConfig.DOWNLOAD_ON_MOBILE, false);
    }

    /**
     * 设置允许移动网络下载
     */
    public void setDownloadOnMobile(boolean allow) {
        Hawk.put(HawkConfig.DOWNLOAD_ON_MOBILE, allow);
    }

    /**
     * 获取 MIME 类型
     */
    private String getMimeType(String fileName) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(fileName);
        if (!TextUtils.isEmpty(extension)) {
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (!TextUtils.isEmpty(mimeType)) {
                return mimeType;
            }
        }
        return "video/*";
    }

    /**
     * 格式化文件大小
     */
    public static String formatFileSize(long size) {
        if (size <= 0) {
            return "0 B";
        }
        final String[] units = {"B", "KB", "MB", "GB", "TB"};
        int digitGroups = (int) (Math.log10(size) / Math.log10(1024));
        digitGroups = Math.min(digitGroups, units.length - 1);
        return String.format("%.2f %s", size / Math.pow(1024, digitGroups), units[digitGroups]);
    }

    /**
     * 格式化下载速度
     */
    public static String formatSpeed(long bytesPerSecond) {
        return formatFileSize(bytesPerSecond) + "/s";
    }

    /**
     * 格式化剩余时间
     */
    public static String formatRemainingTime(long seconds) {
        if (seconds <= 0) {
            return "";
        }
        if (seconds < 60) {
            return seconds + "秒";
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            long secs = seconds % 60;
            return minutes + "分" + (secs > 0 ? secs + "秒" : "");
        } else {
            long hours = seconds / 3600;
            long minutes = (seconds % 3600) / 60;
            return hours + "小时" + (minutes > 0 ? minutes + "分" : "");
        }
    }

    /**
     * 恢复未完成的下载任务
     * 应用启动时调用，将所有"下载中"状态的任务重新启动
     * @return 恢复的任务数量
     */
    public int resumeUnfinishedTasks() {
        // 获取所有状态为"下载中"的任务，这些任务可能因为应用被杀死而中断
        List<DownloadTask> downloadingTasks = taskDao.getByStatuses(
                DownloadTask.STATUS_DOWNLOADING, DownloadTask.STATUS_WAITING);

        if (downloadingTasks == null || downloadingTasks.isEmpty()) {
            return 0;
        }

        int count = 0;
        for (DownloadTask task : downloadingTasks) {
            // 将状态重置为等待中，然后重新启动
            taskDao.updateStatus(task.getId(), DownloadTask.STATUS_WAITING, System.currentTimeMillis());
            startDownloadService(task.getId());
            count++;
        }

        return count;
    }

    /**
     * 获取未完成任务的数量
     */
    public int getUnfinishedTaskCount() {
        List<DownloadTask> tasks = taskDao.getByStatuses(
                DownloadTask.STATUS_DOWNLOADING, DownloadTask.STATUS_WAITING);
        return tasks != null ? tasks.size() : 0;
    }
}
