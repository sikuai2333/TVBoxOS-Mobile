package com.github.tvbox.osc.cache;

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * 下载任务 DAO 接口
 */
@Dao
public interface DownloadTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(DownloadTask task);

    @Update
    void update(DownloadTask task);

    @Delete
    void delete(DownloadTask task);

    @Query("SELECT * FROM downloadTask ORDER BY createTime DESC")
    List<DownloadTask> getAll();

    @Query("SELECT * FROM downloadTask WHERE id = :id")
    DownloadTask getById(int id);

    @Query("SELECT * FROM downloadTask WHERE status = :status ORDER BY createTime DESC")
    List<DownloadTask> getByStatus(int status);

    @Query("SELECT * FROM downloadTask WHERE url = :url LIMIT 1")
    DownloadTask getByUrl(String url);

    @Query("SELECT * FROM downloadTask WHERE status IN (:statuses) ORDER BY createTime ASC")
    List<DownloadTask> getByStatuses(int... statuses);

    @Query("UPDATE downloadTask SET status = :status, downloadedSize = :downloadedSize, updateTime = :updateTime WHERE id = :id")
    void updateProgress(int id, int status, long downloadedSize, long updateTime);

    @Query("UPDATE downloadTask SET status = :status, updateTime = :updateTime WHERE id = :id")
    void updateStatus(int id, int status, long updateTime);

    @Query("UPDATE downloadTask SET status = :status, errorMsg = :errorMsg, updateTime = :updateTime WHERE id = :id")
    void updateStatusWithError(int id, int status, String errorMsg, long updateTime);

    @Query("UPDATE downloadTask SET totalSize = :totalSize, updateTime = :updateTime WHERE id = :id")
    void updateTotalSize(int id, long totalSize, long updateTime);

    @Query("UPDATE downloadTask SET isM3u8 = :isM3u8, totalSegments = :totalSegments, m3u8Content = :m3u8Content, updateTime = :updateTime WHERE id = :id")
    void updateM3u8Info(int id, boolean isM3u8, int totalSegments, String m3u8Content, long updateTime);

    @Query("UPDATE downloadTask SET downloadedSegments = :downloadedSegments, updateTime = :updateTime WHERE id = :id")
    void updateM3u8Progress(int id, int downloadedSegments, long updateTime);

    @Query("DELETE FROM downloadTask WHERE id = :id")
    void deleteById(int id);

    @Query("DELETE FROM downloadTask WHERE status = :status")
    void deleteByStatus(int status);

    @Query("SELECT COUNT(*) FROM downloadTask WHERE status = :status")
    int countByStatus(int status);

    @Query("SELECT COUNT(*) FROM downloadTask")
    int countAll();
}
