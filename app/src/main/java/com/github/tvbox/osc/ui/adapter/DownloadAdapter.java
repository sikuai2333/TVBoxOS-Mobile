package com.github.tvbox.osc.ui.adapter;

import android.graphics.Color;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.chad.library.adapter.base.BaseQuickAdapter;
import com.chad.library.adapter.base.BaseViewHolder;
import com.github.tvbox.osc.R;
import com.github.tvbox.osc.cache.DownloadTask;
import com.github.tvbox.osc.util.DownloadManager;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/**
 * 下载任务列表适配器
 * 优化刷新策略，减少页面闪烁
 */
public class DownloadAdapter extends BaseQuickAdapter<DownloadTask, BaseViewHolder> {

    private OnItemActionListener actionListener;

    // 进度刷新节流：记录每个任务的上次刷新时间
    private final Map<Integer, Long> lastRefreshTimeMap = new HashMap<>();
    // 进度刷新节流：记录每个任务的上次进度值
    private final Map<Integer, Integer> lastProgressMap = new HashMap<>();
    // 最小刷新间隔（毫秒）
    private static final long MIN_REFRESH_INTERVAL = 500;
    // 进度变化阈值（百分比），小于此值不刷新
    private static final int PROGRESS_CHANGE_THRESHOLD = 1;

    public interface OnItemActionListener {
        void onPauseResume(DownloadTask task);
        void onDelete(DownloadTask task);
        void onItemClick(DownloadTask task);
        void onItemLongClick(DownloadTask task);
    }

    public DownloadAdapter() {
        super(R.layout.item_download, new ArrayList<>());
    }

    public void setOnItemActionListener(OnItemActionListener listener) {
        this.actionListener = listener;
    }

    @Override
    protected void convert(BaseViewHolder helper, DownloadTask item) {
        // 视频名称
        helper.setText(R.id.tv_vod_name, item.getVodName());
        // 集数名称
        helper.setText(R.id.tv_episode_name, item.getEpisodeName());

        // 更新进度相关UI
        updateProgressUI(helper, item);

        // 按钮点击事件
        helper.getView(R.id.btn_pause_resume).setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onPauseResume(item);
            }
        });

        helper.getView(R.id.btn_delete).setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onDelete(item);
            }
        });

        // 整项点击
        helper.itemView.setOnClickListener(v -> {
            if (actionListener != null) {
                actionListener.onItemClick(item);
            }
        });

        // 整项长按
        helper.itemView.setOnLongClickListener(v -> {
            if (actionListener != null) {
                actionListener.onItemLongClick(item);
            }
            return true;
        });
    }

    /**
     * 更新进度相关的UI元素
     */
    private void updateProgressUI(BaseViewHolder helper, DownloadTask item) {
        // 状态
        TextView tvStatus = helper.getView(R.id.tv_status);
        tvStatus.setText(item.getStatusText());
        updateStatusBackground(tvStatus, item.getStatus());

        // 进度条
        ProgressBar progressBar = helper.getView(R.id.progress_bar);
        int progress = item.getProgress();
        progressBar.setProgress(progress);

        // 进度信息
        TextView tvProgressInfo = helper.getView(R.id.tv_progress_info);
        String progressInfo;
        if (item.isM3u8()) {
            // M3U8显示百分比 + 分片进度
            if (item.getTotalSegments() > 0) {
                progressInfo = progress + "% (" + item.getDownloadedSegments() + "/" + item.getTotalSegments() + " 分片)";
            } else {
                progressInfo = "解析中...";
            }
        } else {
            // 普通文件显示百分比 + 大小进度
            if (item.getTotalSize() > 0) {
                progressInfo = progress + "% (" + DownloadManager.formatFileSize(item.getDownloadedSize()) +
                        "/" + DownloadManager.formatFileSize(item.getTotalSize()) + ")";
            } else {
                progressInfo = DownloadManager.formatFileSize(item.getDownloadedSize());
            }
        }
        tvProgressInfo.setText(progressInfo);

        // 速度信息（只在下载中显示）
        TextView tvSpeed = helper.getView(R.id.tv_speed);
        if (item.isDownloading() && item.getCurrentSpeed() > 0) {
            tvSpeed.setVisibility(View.VISIBLE);
            tvSpeed.setText(DownloadManager.formatSpeed(item.getCurrentSpeed()));
        } else {
            tvSpeed.setVisibility(View.GONE);
        }

        // 暂停/恢复按钮
        TextView btnPauseResume = helper.getView(R.id.btn_pause_resume);
        if (item.isCompleted()) {
            btnPauseResume.setText("打开");
            btnPauseResume.setTextColor(Color.parseColor("#4CAF50"));
        } else if (item.isDownloading()) {
            btnPauseResume.setText("暂停");
            btnPauseResume.setTextColor(Color.parseColor("#FF9800"));
        } else if (item.canStart()) {
            btnPauseResume.setText("继续");
            btnPauseResume.setTextColor(Color.parseColor("#4CAF50"));
        } else {
            btnPauseResume.setText("等待");
            btnPauseResume.setTextColor(Color.parseColor("#999999"));
        }
    }

    /**
     * 更新状态背景颜色
     */
    private void updateStatusBackground(TextView tvStatus, int status) {
        int color;
        switch (status) {
            case DownloadTask.STATUS_DOWNLOADING:
                color = Color.parseColor("#4CAF50"); // 绿色
                break;
            case DownloadTask.STATUS_PAUSED:
                color = Color.parseColor("#FF9800"); // 橙色
                break;
            case DownloadTask.STATUS_COMPLETED:
                color = Color.parseColor("#2196F3"); // 蓝色
                break;
            case DownloadTask.STATUS_FAILED:
                color = Color.parseColor("#F44336"); // 红色
                break;
            case DownloadTask.STATUS_WAITING:
            default:
                color = Color.parseColor("#9E9E9E"); // 灰色
                break;
        }
        tvStatus.getBackground().setTint(color);
    }

    /**
     * 检查是否需要刷新（节流）
     * @return true 需要刷新，false 跳过本次刷新
     */
    private boolean shouldRefresh(int taskId, int newProgress) {
        long currentTime = System.currentTimeMillis();

        // 检查时间间隔
        Long lastTime = lastRefreshTimeMap.get(taskId);
        if (lastTime != null && currentTime - lastTime < MIN_REFRESH_INTERVAL) {
            return false;
        }

        // 检查进度变化
        Integer lastProgress = lastProgressMap.get(taskId);
        if (lastProgress != null && Math.abs(newProgress - lastProgress) < PROGRESS_CHANGE_THRESHOLD) {
            // 进度变化太小，但如果距离上次刷新超过2秒，强制刷新一次（更新速度）
            if (lastTime == null || currentTime - lastTime > 2000) {
                lastRefreshTimeMap.put(taskId, currentTime);
                return true;
            }
            return false;
        }

        // 记录本次刷新
        lastRefreshTimeMap.put(taskId, currentTime);
        lastProgressMap.put(taskId, newProgress);
        return true;
    }

    /**
     * 更新任务进度（带节流）
     */
    public void updateProgress(int taskId, long downloadedSize, long totalSize, long speed, long remainingSeconds) {
        for (int i = 0; i < getData().size(); i++) {
            DownloadTask task = getData().get(i);
            if (task.getId() == taskId) {
                // 更新数据
                task.setDownloadedSize(downloadedSize);
                task.setTotalSize(totalSize);
                task.setStatus(DownloadTask.STATUS_DOWNLOADING);
                task.setCurrentSpeed(speed);

                int progress = task.getProgress();

                // 节流检查
                if (!shouldRefresh(taskId, progress)) {
                    return;
                }

                // 尝试直接更新UI（不触发整个item重绘）
                if (getRecyclerView() != null) {
                    BaseViewHolder holder = (BaseViewHolder) getRecyclerView().findViewHolderForAdapterPosition(i);
                    if (holder != null) {
                        // 直接更新UI元素
                        updateHolderProgress(holder, task, progress, speed, remainingSeconds, false);
                    } else {
                        // holder为null时，通知刷新
                        notifyItemChanged(i);
                    }
                } else {
                    notifyItemChanged(i);
                }
                break;
            }
        }
    }

    /**
     * 更新M3U8任务进度（带节流）
     */
    public void updateM3u8Progress(int taskId, int downloadedSegments, int totalSegments, long speed) {
        for (int i = 0; i < getData().size(); i++) {
            DownloadTask task = getData().get(i);
            if (task.getId() == taskId) {
                // 更新数据
                task.setDownloadedSegments(downloadedSegments);
                task.setTotalSegments(totalSegments);
                task.setStatus(DownloadTask.STATUS_DOWNLOADING);
                task.setCurrentSpeed(speed);

                int progress = task.getProgress();

                // 节流检查
                if (!shouldRefresh(taskId, progress)) {
                    return;
                }

                // 尝试直接更新UI
                if (getRecyclerView() != null) {
                    BaseViewHolder holder = (BaseViewHolder) getRecyclerView().findViewHolderForAdapterPosition(i);
                    if (holder != null) {
                        // 直接更新UI元素
                        updateHolderProgress(holder, task, progress, speed, 0, true);
                    } else {
                        // holder为null时，通知刷新
                        notifyItemChanged(i);
                    }
                } else {
                    notifyItemChanged(i);
                }
                break;
            }
        }
    }

    /**
     * 直接更新ViewHolder中的进度UI（避免整项重绘）
     */
    private void updateHolderProgress(BaseViewHolder holder, DownloadTask task, int progress,
                                      long speed, long remainingSeconds, boolean isM3u8) {
        // 状态标签
        TextView tvStatus = holder.getView(R.id.tv_status);
        tvStatus.setText(task.getStatusText());
        updateStatusBackground(tvStatus, DownloadTask.STATUS_DOWNLOADING);

        // 进度条
        ProgressBar progressBar = holder.getView(R.id.progress_bar);
        progressBar.setProgress(progress);

        // 进度信息
        TextView tvProgressInfo = holder.getView(R.id.tv_progress_info);
        String progressInfo;
        if (isM3u8) {
            progressInfo = progress + "% (" + task.getDownloadedSegments() + "/" + task.getTotalSegments() + " 分片)";
        } else {
            progressInfo = progress + "% (" + DownloadManager.formatFileSize(task.getDownloadedSize()) +
                    "/" + DownloadManager.formatFileSize(task.getTotalSize()) + ")";
        }
        tvProgressInfo.setText(progressInfo);

        // 速度和剩余时间
        TextView tvSpeed = holder.getView(R.id.tv_speed);
        tvSpeed.setVisibility(View.VISIBLE);
        String speedText = DownloadManager.formatSpeed(speed);
        if (!isM3u8 && remainingSeconds > 0) {
            speedText += " · 剩余" + DownloadManager.formatRemainingTime(remainingSeconds);
        }
        tvSpeed.setText(speedText);

        // 更新暂停/恢复按钮
        TextView btnPauseResume = holder.getView(R.id.btn_pause_resume);
        btnPauseResume.setText("暂停");
        btnPauseResume.setTextColor(Color.parseColor("#FF9800"));
    }

    /**
     * 更新任务状态（状态变更时强制刷新）
     */
    public void updateTaskStatus(int taskId, int status) {
        for (int i = 0; i < getData().size(); i++) {
            DownloadTask task = getData().get(i);
            if (task.getId() == taskId) {
                task.setStatus(status);
                // 状态变更时清除节流记录，确保下次进度更新能刷新
                lastRefreshTimeMap.remove(taskId);
                lastProgressMap.remove(taskId);
                notifyItemChanged(i);
                break;
            }
        }
    }

    /**
     * 移除任务
     */
    public void removeTask(int taskId) {
        for (int i = 0; i < getData().size(); i++) {
            if (getData().get(i).getId() == taskId) {
                // 清除节流记录
                lastRefreshTimeMap.remove(taskId);
                lastProgressMap.remove(taskId);
                remove(i);
                break;
            }
        }
    }

    /**
     * 添加或更新任务
     */
    public void addOrUpdateTask(DownloadTask task) {
        for (int i = 0; i < getData().size(); i++) {
            if (getData().get(i).getId() == task.getId()) {
                getData().set(i, task);
                notifyItemChanged(i);
                return;
            }
        }
        // 新任务添加到顶部
        addData(0, task);
    }

    /**
     * 清除所有节流记录
     */
    public void clearThrottleCache() {
        lastRefreshTimeMap.clear();
        lastProgressMap.clear();
    }
}
