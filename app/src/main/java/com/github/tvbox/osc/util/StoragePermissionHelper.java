package com.github.tvbox.osc.util;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.github.tvbox.osc.base.App;
import com.lxj.xpopup.XPopup;

/**
 * 存储权限帮助类
 * 用于检查和请求下载所需的存储权限
 *
 * 权限策略:
 * - Android 13+ (API 33+): 需要 READ_MEDIA_VIDEO 权限（用于读取已下载的视频）
 * - Android 11-12 (API 30-32): 需要 MANAGE_EXTERNAL_STORAGE 或使用应用私有目录
 * - Android 10 (API 29): 使用分区存储，应用私有目录不需要权限
 * - Android 9及以下 (API 28-): 需要 READ/WRITE_EXTERNAL_STORAGE 权限
 */
public class StoragePermissionHelper {

    public static final int REQUEST_CODE_STORAGE = 1001;
    public static final int REQUEST_CODE_MANAGE_STORAGE = 1002;

    /**
     * 检查是否有存储权限
     */
    public static boolean hasStoragePermission(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 检查媒体权限
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: 检查是否有所有文件访问权限，或者应用私有目录可写
            return Environment.isExternalStorageManager() || isAppPrivateDirWritable();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10: 分区存储，应用私有目录不需要权限
            return isAppPrivateDirWritable();
        } else {
            // Android 9及以下: 检查传统存储权限
            return ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 检查应用私有目录是否可写
     */
    private static boolean isAppPrivateDirWritable() {
        try {
            java.io.File dir = App.getInstance().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
            if (dir == null) {
                return false;
            }
            if (!dir.exists()) {
                return dir.mkdirs();
            }
            // 尝试创建测试文件
            java.io.File testFile = new java.io.File(dir, ".test_write");
            if (testFile.createNewFile()) {
                testFile.delete();
                return true;
            }
            return testFile.exists();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 请求存储权限
     */
    public static void requestStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ 请求媒体权限
            ActivityCompat.requestPermissions(activity,
                    new String[]{
                            Manifest.permission.READ_MEDIA_VIDEO
                    },
                    REQUEST_CODE_STORAGE);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: 请求所有文件访问权限
            requestManageStoragePermission(activity);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10: 使用应用私有目录，一般不需要额外权限
            // 但如果私有目录不可用，尝试请求传统权限
            if (!isAppPrivateDirWritable()) {
                ActivityCompat.requestPermissions(activity,
                        new String[]{
                                Manifest.permission.READ_EXTERNAL_STORAGE,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                        },
                        REQUEST_CODE_STORAGE);
            }
        } else {
            // Android 9及以下: 请求传统存储权限
            ActivityCompat.requestPermissions(activity,
                    new String[]{
                            Manifest.permission.READ_EXTERNAL_STORAGE,
                            Manifest.permission.WRITE_EXTERNAL_STORAGE
                    },
                    REQUEST_CODE_STORAGE);
        }
    }

    /**
     * 请求 MANAGE_EXTERNAL_STORAGE 权限 (Android 11+)
     */
    public static void requestManageStoragePermission(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                Uri uri = Uri.fromParts("package", activity.getPackageName(), null);
                intent.setData(uri);
                activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
            } catch (Exception e) {
                // 某些设备可能不支持直接跳转到应用的权限页面
                try {
                    Intent intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
                    activity.startActivityForResult(intent, REQUEST_CODE_MANAGE_STORAGE);
                } catch (Exception ex) {
                    // 如果都不支持，打开应用设置页面
                    openAppSettings(activity);
                }
            }
        }
    }

    /**
     * 显示权限说明对话框
     */
    public static void showPermissionRationaleDialog(Activity activity) {
        String message = getPermissionRationaleMessage();

        new XPopup.Builder(activity)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("需要存储权限", message,
                        "取消", "授权",
                        () -> {
                            // 点击授权
                            requestStoragePermission(activity);
                        },
                        null, false)
                .show();
    }

    /**
     * 获取权限说明文本
     */
    private static String getPermissionRationaleMessage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return "下载影视内容需要媒体访问权限，用于保存和管理视频文件。\n\n请授予媒体权限以使用下载功能。";
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return "下载影视内容需要文件管理权限，用于保存视频文件到本地。\n\n请在接下来的页面中开启\"所有文件访问权限\"。";
        } else {
            return "下载影视内容需要存储权限，用于保存视频文件到本地。\n\n请授予存储权限以使用下载功能。";
        }
    }

    /**
     * 检查权限是否被永久拒绝（用户选择了"不再询问"）
     */
    public static boolean isPermissionPermanentlyDenied(Activity activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+
            return !ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.READ_MEDIA_VIDEO) &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.READ_MEDIA_VIDEO)
                            != PackageManager.PERMISSION_GRANTED;
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: MANAGE_EXTERNAL_STORAGE 需要去设置页面开启
            return !Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10: 一般不会永久拒绝
            return false;
        } else {
            // Android 9及以下
            return !ActivityCompat.shouldShowRequestPermissionRationale(activity,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE) &&
                    ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            != PackageManager.PERMISSION_GRANTED;
        }
    }

    /**
     * 显示引导用户去设置页面的对话框
     */
    public static void showGoToSettingsDialog(Activity activity) {
        String message = getGoToSettingsMessage();
        String buttonText = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ? "去开启" : "去设置";

        new XPopup.Builder(activity)
                .isDarkTheme(Utils.isDarkTheme())
                .asConfirm("需要存储权限", message,
                        "取消", buttonText,
                        () -> {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                // Android 11+ 直接打开文件管理权限设置
                                requestManageStoragePermission(activity);
                            } else {
                                // 打开应用设置页面
                                openAppSettings(activity);
                            }
                        },
                        null, false)
                .show();
    }

    /**
     * 获取设置引导文本
     */
    private static String getGoToSettingsMessage() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return "下载功能需要文件管理权限，请开启\"所有文件访问权限\"。\n\n点击\"去开启\"后，请找到并开启该权限。";
        } else {
            return "下载功能需要存储权限，但权限已被拒绝。\n\n请前往设置页面手动开启存储权限，否则无法使用下载功能。";
        }
    }

    /**
     * 打开应用设置页面
     */
    public static void openAppSettings(Context context) {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            Uri uri = Uri.fromParts("package", context.getPackageName(), null);
            intent.setData(uri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
            // 如果无法打开应用设置，尝试打开通用设置
            try {
                Intent intent = new Intent(Settings.ACTION_SETTINGS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } catch (Exception ex) {
                ex.printStackTrace();
            }
        }
    }

    /**
     * 检查下载目录是否可写
     */
    public static boolean isDownloadDirWritable() {
        try {
            java.io.File downloadDir = DownloadManager.getInstance().getDownloadDir();
            if (downloadDir == null) {
                return false;
            }
            if (!downloadDir.exists()) {
                return downloadDir.mkdirs();
            }
            // 尝试创建测试文件
            java.io.File testFile = new java.io.File(downloadDir, ".test_write");
            if (testFile.createNewFile()) {
                testFile.delete();
                return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在下载前检查权限，如果没有权限则显示相应对话框
     * @return true 如果有权限可以继续下载，false 需要先获取权限
     */
    public static boolean checkAndRequestPermissionForDownload(Activity activity) {
        // 首先检查是否有权限
        if (hasStoragePermission(activity)) {
            // 再检查下载目录是否可写
            if (isDownloadDirWritable()) {
                return true;
            }
        }

        // 根据不同Android版本处理权限请求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+: 检查媒体权限
            if (isPermissionPermanentlyDenied(activity)) {
                showGoToSettingsDialog(activity);
            } else {
                showPermissionRationaleDialog(activity);
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11-12: 需要 MANAGE_EXTERNAL_STORAGE 权限
            showPermissionRationaleDialog(activity);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Android 10: 一般使用应用私有目录，如果不可用则提示
            if (!isAppPrivateDirWritable()) {
                showPermissionRationaleDialog(activity);
            } else {
                // 私有目录可用但下载目录不可写，可能是其他问题
                return true;
            }
        } else {
            // Android 9及以下: 传统存储权限
            if (isPermissionPermanentlyDenied(activity)) {
                showGoToSettingsDialog(activity);
            } else {
                showPermissionRationaleDialog(activity);
            }
        }

        return false;
    }

    /**
     * 处理权限请求结果
     * 在 Activity 的 onRequestPermissionsResult 中调用
     * @return true 如果权限已授予
     */
    public static boolean handlePermissionResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_CODE_STORAGE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                return true;
            }
        }
        return false;
    }

    /**
     * 处理 MANAGE_EXTERNAL_STORAGE 权限结果
     * 在 Activity 的 onActivityResult 中调用
     * @return true 如果权限已授予
     */
    public static boolean handleManageStorageResult(int requestCode) {
        if (requestCode == REQUEST_CODE_MANAGE_STORAGE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return Environment.isExternalStorageManager();
            }
        }
        return false;
    }
}
