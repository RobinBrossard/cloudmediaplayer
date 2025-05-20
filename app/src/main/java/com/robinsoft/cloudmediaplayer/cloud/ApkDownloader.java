package com.robinsoft.cloudmediaplayer.cloud; // 请替换为你的实际包名

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicBoolean;

public class ApkDownloader {

    private static final String TAG = "ApkDownloader";
    private static final String MIME_TYPE_APK = "application/vnd.android.package-archive";
    private static ApkDownloader instance;
    private final Context appContext;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean isDownloading = new AtomicBoolean(false);
    private Callback currentCallback;
    // currentActivityContext is not strictly needed by the downloader itself
    // but kept if you intend to use it for UI interaction directly from here in future.
    // For runtime permissions, the calling Activity should handle it before calling download.
    // private Activity currentActivityContext;
    // private String currentDownloadUrl;
    // private String currentDownloadFileName;


    private ApkDownloader(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
        Log.d(TAG, "ApkDownloader initialized.");
    }

    public static synchronized ApkDownloader getInstance(@NonNull Context context) {
        if (instance == null) {
            instance = new ApkDownloader(context.getApplicationContext());
        }
        return instance;
    }

    public void downloadAndInstallApk(String url, String fileName, @NonNull Activity activity, @NonNull Callback callback) {
        // this.currentActivityContext = activity; // Store activity if needed for other purposes
        this.currentCallback = callback;
        // this.currentDownloadUrl = url;
        // this.currentDownloadFileName = fileName;

        if (!isExternalStorageWritable()) {
            Log.e(TAG, "External storage is not writable.");
            mainHandler.post(() -> callback.onFailure("外部存储不可用，无法下载"));
            return;
        }

        if (isDownloading.getAndSet(true)) { // Atomically set to true and get previous value
            Log.w(TAG, "Another download is in progress. Ignoring new request for URL: " + url);
            mainHandler.post(() -> callback.onFailure("当前有下载任务正在进行，请稍后再试"));
            return;
        }


        Log.d(TAG, "Starting download for URL: " + url + ", file name: " + fileName);

        new Thread(() -> {
            File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
            // 确保 Downloads 目录存在 (通常系统会保证，但创建一下无妨)
            if (!downloadDir.exists()) {
                if (!downloadDir.mkdirs()) {
                    Log.e(TAG, "Failed to create Downloads directory: " + downloadDir.getAbsolutePath());
                    // Even if mkdirs returns false, the directory might already exist,
                    // so we proceed, and file operations will fail later if it's truly an issue.
                }
            }
            File outputFile = new File(downloadDir, fileName);

            Log.d(TAG, "Target download file: " + outputFile.getAbsolutePath());

            // 检查并删除已存在的文件
            if (outputFile.exists()) {
                Log.w(TAG, "File exists. Attempting to delete: " + outputFile.getAbsolutePath());
                if (!outputFile.delete()) {
                    Log.e(TAG, "Failed to delete existing file: " + outputFile.getAbsolutePath());
                    mainHandler.post(() -> {
                        isDownloading.set(false);
                        currentCallback.onFailure("目标文件已存在且无法删除，请清理 Downloads 文件夹后重试");
                    });
                    return;
                } else {
                    Log.i(TAG, "Existing file deleted successfully: " + outputFile.getAbsolutePath());
                }
            }

            File downloadedFile = null;
            try {
                downloadedFile = downloadFileHttp(url, outputFile, currentCallback);
                File finalDownloadedFile = downloadedFile; // For lambda
                if (finalDownloadedFile != null && finalDownloadedFile.exists()) {
                    mainHandler.post(() -> {
                        Log.i(TAG, "APK Downloaded Successfully. File: " + finalDownloadedFile.getAbsolutePath());
                        currentCallback.onSuccess(finalDownloadedFile);
                        installApk(finalDownloadedFile);
                        isDownloading.set(false);
                    });
                } else {
                    mainHandler.post(() -> {
                        currentCallback.onFailure("下载失败，文件未成功保存或为空");
                        isDownloading.set(false);
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Download IO Exception: " + e.getMessage(), e);
                final String errorMessage = "下载过程中发生IO错误: " + e.getMessage();
                mainHandler.post(() -> {
                    currentCallback.onFailure(errorMessage);
                    isDownloading.set(false);
                });
            } catch (SecurityException e) {
                Log.e(TAG, "Download Security Exception: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    currentCallback.onFailure("下载权限错误: " + e.getMessage());
                    isDownloading.set(false);
                });
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "Download Argument Exception: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    currentCallback.onFailure("下载参数错误: " + e.getMessage());
                    isDownloading.set(false);
                });
            } catch (Exception e) {
                Log.e(TAG, "Unknown download error: " + e.getMessage(), e);
                mainHandler.post(() -> {
                    currentCallback.onFailure("下载过程中发生未知错误: " + e.getMessage());
                    isDownloading.set(false);
                });
            }
        }).start();
    }

    private File downloadFileHttp(String fileUrl, File destinationFile, Callback callback) throws IOException {
        URL url = new URL(fileUrl);
        HttpURLConnection connection = null;
        InputStream inputStream = null;
        OutputStream outputStream = null;

        try {
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000); // 15 seconds
            connection.setReadTimeout(15000);    // 15 seconds
            connection.setInstanceFollowRedirects(true); // Follow redirects
            // connection.setRequestProperty("User-Agent", "YourApp/1.0"); // Optional: Set User-Agent

            Log.d(TAG, "Connecting to URL: " + fileUrl);
            connection.connect();

            int responseCode = connection.getResponseCode();
            Log.d(TAG, "Response Code: " + responseCode);

            if (responseCode != HttpURLConnection.HTTP_OK && responseCode != HttpURLConnection.HTTP_CREATED && responseCode != HttpURLConnection.HTTP_ACCEPTED) {
                String errorMsg = "HTTP error: " + responseCode + " " + connection.getResponseMessage();
                try (InputStream errorStream = connection.getErrorStream();
                     java.util.Scanner s = new java.util.Scanner(errorStream).useDelimiter("\\A")) {
                    if (s.hasNext()) {
                        errorMsg += " - " + s.next();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to read error stream: " + e.getMessage());
                }
                throw new IOException(errorMsg);
            }

            long fileSize = connection.getContentLengthLong(); // For progress, -1 if unknown
            Log.d(TAG, "File size: " + fileSize + " bytes");

            inputStream = connection.getInputStream();
            // No need to create parent directory for Environment.DIRECTORY_DOWNLOADS usually,
            // but if using a sub-folder, this would be important:
            // if (!destinationFile.getParentFile().exists()) {
            //     destinationFile.getParentFile().mkdirs();
            // }
            outputStream = new FileOutputStream(destinationFile);

            byte[] buffer = new byte[8192]; // Increased buffer size
            int bytesRead;
            long totalBytesRead = 0;

            Log.i(TAG, "Starting file write to: " + destinationFile.getAbsolutePath());
            while ((bytesRead = inputStream.read(buffer)) != -1) {
                outputStream.write(buffer, 0, bytesRead);
                totalBytesRead += bytesRead;
                if (fileSize > 0 && callback != null) {
                    final int progress = (int) (totalBytesRead * 100 / fileSize);
                    mainHandler.post(() -> callback.onProgress(progress));
                }
            }
            outputStream.flush(); // Ensure all data is written
            Log.i(TAG, "File downloaded successfully: " + destinationFile.getAbsolutePath() + ", Size: " + totalBytesRead + " bytes");
            return destinationFile;

        } finally {
            if (outputStream != null) {
                try {
                    outputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing output stream", e);
                }
            }
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    Log.e(TAG, "Error closing input stream", e);
                }
            }
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private void installApk(File apkFile) {
        Log.i(TAG, "Initiating APK installation for file: " + apkFile.getAbsolutePath());
        if (!apkFile.exists()) {
            Log.e(TAG, "APK file does not exist at path: " + apkFile.getAbsolutePath());
            mainHandler.post(() -> {
                if (currentCallback != null) {
                    currentCallback.onFailure("安装失败，APK文件不存在");
                }
                Toast.makeText(appContext, "安装失败，APK文件找不到", Toast.LENGTH_LONG).show();
            });
            return;
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        Uri apkUri;
        String authority = appContext.getPackageName() + ".fileprovider";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) { // Android 7.0 (API 24) and above
                apkUri = FileProvider.getUriForFile(appContext, authority, apkFile);
                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                Log.d(TAG, "Using FileProvider for APK URI: " + apkUri);
            } else {
                apkUri = Uri.fromFile(apkFile);
                Log.d(TAG, "Using Uri.fromFile for APK URI: " + apkUri);
            }
            intent.setDataAndType(apkUri, MIME_TYPE_APK);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Important for launching from non-Activity context like ApplicationContext
            appContext.startActivity(intent);
            Log.i(TAG, "APK installation intent sent.");
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Error with FileProvider (check authority and paths XML): " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (currentCallback != null) {
                    currentCallback.onFailure("安装程序启动失败: FileProvider配置错误");
                }
                Toast.makeText(appContext, "无法打开安装程序: FileProvider配置错误", Toast.LENGTH_LONG).show();
            });
        } catch (Exception e) { // Catch any other exception like ActivityNotFoundException
            Log.e(TAG, "Error starting APK installation activity: " + e.getMessage(), e);
            mainHandler.post(() -> {
                if (currentCallback != null) {
                    currentCallback.onFailure("安装程序启动失败: " + e.getMessage());
                }
                Toast.makeText(appContext, "无法打开安装程序: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            });
        }
    }

    /* Checks if external storage is available for read and write */
    public boolean isExternalStorageWritable() {
        String state = Environment.getExternalStorageState();
        return Environment.MEDIA_MOUNTED.equals(state);
    }

    public void cleanup() {
        Log.d(TAG, "ApkDownloader cleanup called. No specific resources to clean other than ongoing download state.");
        // If you had more complex resources, you'd clean them here.
        // The isDownloading flag is reset on download completion/failure.
    }

    public interface Callback {
        void onSuccess(File apkFile);

        void onFailure(String errorMessage);

        void onProgress(int progressPercentage); // Added for progress updates
    }
}