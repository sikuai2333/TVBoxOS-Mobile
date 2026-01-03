package com.github.tvbox.osc.ui.activity;

import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.github.tvbox.osc.R;
import com.github.tvbox.osc.base.BaseVbActivity;
import com.github.tvbox.osc.cache.DownloadTask;
import com.github.tvbox.osc.databinding.ActivityDownloadBinding;
import com.github.tvbox.osc.event.DownloadEvent;
import com.github.tvbox.osc.ui.adapter.DownloadAdapter;
import com.github.tvbox.osc.util.DownloadManager;
import com.lxj.xpopup.XPopup;
import com.github.tvbox.osc.util.Utils;
import com.blankj.utilcode.util.ToastUtils;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.io.File;
import java.util.List;

/**
 * 下载管理页面
 */
public class DownloadActivity extends BaseVbActivity<ActivityDownloadBinding> {

    private DownloadAdapter downloadAdapter;

    @Override
    protected void init() {
        initView();
        initData();
        // 避免重复注册
        if (!EventBus.getDefault().isRegistered(this)) {
            EventBus.getDefault().register(this);
        }
    }

    private void initView() {
        // 标题栏
        mBinding.titleBar.getRightView().setOnClickListener(v -> {
            showClearDialog();
        });

        // 存储位置
        File downloadDir = DownloadManager.getInstance().getDownloadDir();
        mBinding.tvStorageInfo.setText("存储位置: " + downloadDir.getAbsolutePath());

        // 列表
        mBinding.rvDownloadList.setLayoutManager(new LinearLayoutManager(this));
        downloadAdapter = new DownloadAdapter();
        mBinding.rvDownloadList.setAdapter(downloadAdapter);

        // 设置监听
        downloadAdapter.setOnItemActionListener(new DownloadAdapter.OnItemActionListener() {
            @Override
            public void onPauseResume(DownloadTask task) {
                handlePauseResume(task);
            }

            @Override
            public void onDelete(DownloadTask task) {
                showDeleteDialog(task);
            }

            @Override
            public void onItemClick(DownloadTask task) {
                if (task.isCompleted()) {
                    // 默认使用内置播放器打开
                    DownloadManager.getInstance().openFile(DownloadActivity.this, task);
                }
            }

            @Override
            public void onItemLongClick(DownloadTask task) {
                if (task.isCompleted()) {
                    // 长按显示播放器选择
                    showPlayerSelectDialog(task);
                }
            }
        });
    }

    /**
     * 显示播放器选择对话框
     */
    private void showPlayerSelectDialog(DownloadTask task) {
        new XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asCenterList("选择播放器", new String[]{"内置播放器", "外部播放器"},
                        (position, text) -> {
                            if (position == 0) {
                                // 内置播放器
                                DownloadManager.getInstance().openFile(DownloadActivity.this, task);
                            } else {
                                // 外部播放器
                                DownloadManager.getInstance().openFileWithSystem(DownloadActivity.this, task);
                            }
                        })
                .show();
    }

    private void initData() {
        loadTasks();
    }

    private void loadTasks() {
        List<DownloadTask> tasks = DownloadManager.getInstance().getAllTasks();
        downloadAdapter.setNewData(tasks);
        updateEmptyState();
    }

    private void updateEmptyState() {
        if (downloadAdapter.getData().isEmpty()) {
            mBinding.llEmpty.setVisibility(View.VISIBLE);
            mBinding.rvDownloadList.setVisibility(View.GONE);
        } else {
            mBinding.llEmpty.setVisibility(View.GONE);
            mBinding.rvDownloadList.setVisibility(View.VISIBLE);
        }
    }

    /**
     * 处理暂停/恢复
     */
    private void handlePauseResume(DownloadTask task) {
        if (task.isCompleted()) {
            // 打开文件
            DownloadManager.getInstance().openFile(this, task);
        } else if (task.isDownloading()) {
            // 暂停
            DownloadManager.getInstance().pauseTask(task.getId());
        } else if (task.canStart()) {
            // 恢复
            DownloadManager.getInstance().resumeTask(task.getId());
        }
    }

    /**
     * 显示删除确认对话框
     */
    private void showDeleteDialog(DownloadTask task) {
        new XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("删除下载", "是否同时删除已下载的文件？",
                        "保留文件", "删除文件",
                        () -> {
                            // 删除文件
                            DownloadManager.getInstance().deleteTask(task.getId(), true);
                        }, () -> {
                            // 保留文件
                            DownloadManager.getInstance().deleteTask(task.getId(), false);
                        }, false)
                .show();
    }

    /**
     * 显示清空确认对话框
     */
    private void showClearDialog() {
        if (downloadAdapter.getData().isEmpty()) {
            return;
        }

        new XPopup.Builder(this)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("清空下载", "确定清空所有下载记录？\n已下载的文件将被保留。",
                        () -> {
                            // 清空所有任务（保留文件）
                            for (DownloadTask task : downloadAdapter.getData()) {
                                DownloadManager.getInstance().deleteTask(task.getId(), false);
                            }
                            downloadAdapter.setNewData(null);
                            updateEmptyState();
                        })
                .show();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onDownloadEvent(DownloadEvent event) {
        switch (event.type) {
            case DownloadEvent.TYPE_ADD:
                // 刷新列表
                loadTasks();
                break;

            case DownloadEvent.TYPE_PROGRESS:
                // 更新进度
                downloadAdapter.updateProgress(event.taskId, event.downloadedSize, event.totalSize, event.speed, event.remainingSeconds);
                break;

            case DownloadEvent.TYPE_M3U8_PROGRESS:
                // 更新M3U8进度
                downloadAdapter.updateM3u8Progress(event.taskId, event.downloadedSegments, event.totalSegments, event.speed);
                break;

            case DownloadEvent.TYPE_START:
            case DownloadEvent.TYPE_PAUSE:
            case DownloadEvent.TYPE_RESUME:
            case DownloadEvent.TYPE_COMPLETE:
            case DownloadEvent.TYPE_FAILED:
                // 更新状态
                DownloadTask task = DownloadManager.getInstance().getTask(event.taskId);
                if (task != null) {
                    downloadAdapter.addOrUpdateTask(task);
                }
                break;

            case DownloadEvent.TYPE_DELETE:
                // 移除任务
                downloadAdapter.removeTask(event.taskId);
                updateEmptyState();
                break;
        }
    }

    @Override
    protected void onDestroy() {
        EventBus.getDefault().unregister(this);
        super.onDestroy();
    }
}
