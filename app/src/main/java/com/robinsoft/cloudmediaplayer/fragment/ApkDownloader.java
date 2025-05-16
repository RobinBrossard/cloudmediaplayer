package com.robinsoft.cloudmediaplayer.fragment;

import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
// 如果您的 targetSdkVersion >= 24 并且需要在应用间共享文件URI（如下载的APK），
// FileProvider 是推荐的方式。但 DownloadManager.getUriForDownloadedFile() 通常返回一个
// 系统可以处理的 content:// URI，所以对于安装APK可能不是必需的，除非您从应用私有目录安装。
// import androidx.core.content.FileProvider;

// 假设此代码位于 Fragment 或 Activity 中

public class ApkDownloader {

    private BroadcastReceiver downloadReceiver;
    private long currentDownloadId = -1L;
    private Context appContext; // 存储ApplicationContext以供注销时使用

    // 当在Fragment中使用时，可以在onAttach中获取Context
    public ApkDownloader(@NonNull Context context) {
        // 使用 Application Context 避免内存泄漏，尤其是在 BroadcastReceiver 的生命周期可能比 Activity/Fragment 长的情况下
        this.appContext = context.getApplicationContext();
    }

    public void downloadAndInstallApk(String url, String fileName, @NonNull Context activityOrFragmentContext) {
        // activityOrFragmentContext 用于UI操作如Toast，以及启动Activity
        // appContext 用于注册/注销BroadcastReceiver等后台操作

        // 防止重复下载或重复注册接收器 (简化逻辑)
        if (currentDownloadId != -1L && downloadReceiver != null) {
            Toast.makeText(activityOrFragmentContext, "已有一个下载任务正在进行", Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(activityOrFragmentContext, "无法获取下载服务", Toast.LENGTH_SHORT).show();
            return;
        }

        Uri uriToDownload = Uri.parse(url);
        final String apkFileNameWithExtension = fileName + ".apk"; // 确保文件名安全，如果来自外部输入

        DownloadManager.Request request = new DownloadManager.Request(uriToDownload)
                .setTitle(fileName) // 通知栏标题
                .setDescription("正在下载 " + fileName) // 通知栏描述
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, apkFileNameWithExtension)
                .setAllowedOverMetered(false) // 允许在计费网络下载（按需配置）
                .setAllowedOverRoaming(false); // 允许在漫游网络下载（按需配置）

        try {
            currentDownloadId = dm.enqueue(request);
        } catch (Exception e) { // 例如：DownloadManager被禁用，或存储空间问题
            currentDownloadId = -1L; // 重置ID
            Toast.makeText(activityOrFragmentContext, "无法开始下载: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
            return;
        }

        downloadReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L);

                if (id == currentDownloadId) {
                    // 下载完成，立即注销接收器
                    unregisterDownloadReceiverInternal();

                    Uri downloadedFileUri = dm.getUriForDownloadedFile(currentDownloadId);
                    currentDownloadId = -1L; // 重置ID，允许新的下载

                    if (downloadedFileUri != null) {
                        installApk(activityOrFragmentContext, downloadedFileUri); // 使用传入的Activity/Fragment Context启动安装
                    } else {
                        Toast.makeText(context, "下载失败或文件未找到", Toast.LENGTH_SHORT).show();
                    }
                }
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);

        // 使用 ContextCompat 注册广播接收器，它会处理好版本兼容性问题
        // RECEIVER_NOT_EXPORTED 表示此接收器仅接收来自系统或应用自身组件的广播，这是此场景下的正确选择
        ContextCompat.registerReceiver(
                appContext, // 使用 Application Context 注册
                downloadReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED // 明确指定为不导出
        );

        Toast.makeText(activityOrFragmentContext, "开始下载 " + fileName, Toast.LENGTH_SHORT).show();
    }

    private void installApk(@NonNull Context context, @NonNull Uri apkUri) {
        // 对于 Android O (API 26) 及更高版本，安装未知来源的应用需要用户授权
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!context.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(context, "请为此应用授权“安装未知应用”权限", Toast.LENGTH_LONG).show();
                // 引导用户到设置页面 (可选)
                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                intent.setData(Uri.parse(String.format("package:%s", context.getPackageName())));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 如果 context 不是 Activity，需要此标志
                try {
                    context.startActivity(intent);
                } catch (Exception e) {
                    e.printStackTrace();
                    Toast.makeText(context, "无法打开权限设置页面", Toast.LENGTH_SHORT).show();
                }
                return; // 等待用户授权后，可能需要用户手动重新触发安装
            }
        }

        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE)
                .setData(apkUri)
                .addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION |
                                Intent.FLAG_ACTIVITY_NEW_TASK
                );

        try {
            context.startActivity(installIntent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(context, "未找到可以安装APK的应用", Toast.LENGTH_SHORT).show();
            e.printStackTrace();
        } catch (Exception e) { // 捕获其他可能的异常，如 SecurityException
            Toast.makeText(context, "启动安装时出错: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
        }
    }

    // 内部方法，用于注销接收器
    private void unregisterDownloadReceiverInternal() {
        if (downloadReceiver != null) {
            try {
                appContext.unregisterReceiver(downloadReceiver);
                Toast.makeText(appContext, "下载完成", Toast.LENGTH_SHORT).show();
            } catch (IllegalArgumentException e) {
                // 如果接收器未注册或已注销，则会发生此异常，可以安全地忽略
                e.printStackTrace();
            }
            downloadReceiver = null; // 清理引用
        }
    }

    /**
     * 公共方法，用于在组件销毁时（例如 Fragment.onDestroyView 或 Activity.onDestroy）确保接收器被注销。
     */
    public void cleanup() {
        unregisterDownloadReceiverInternal();
        // 如果有正在进行的下载，也可以考虑取消它
        if (currentDownloadId != -1L) {
            DownloadManager dm = (DownloadManager) appContext.getSystemService(Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.remove(currentDownloadId);
            }
            currentDownloadId = -1L;
        }
    }
}
