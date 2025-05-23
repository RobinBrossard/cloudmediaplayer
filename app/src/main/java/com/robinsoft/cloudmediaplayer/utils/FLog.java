package com.robinsoft.cloudmediaplayer.utils;

import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.util.Log;

import androidx.core.content.FileProvider;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 文件日志记录器 (File Logger)
 * - 支持日志级别 (d, i, w, e)
 * - 自动同步输出到 Logcat
 * - 日志文件大于4MB时自动轮转 (Rotation)
 * - App启动时自动清理7天前的旧日志 (Cleanup)
 */
public class FLog {

    // --- 配置常量 ---
    private static final String LOG_SUB_DIRECTORY = "CloudMediaPlayer";
    private static final String LOG_FILE_BASE_NAME = "CloudMediaPlayer_log";
    private static final String LOG_FILE_EXTENSION = ".txt";
    private static final String LOG_FILE_NAME = LOG_FILE_BASE_NAME + LOG_FILE_EXTENSION;
    private static final String TAG = "FLog";
    /**
     * 当个日志文件的最大体积 (4MB)
     */
    private static final long MAX_FILE_SIZE_BYTES = 4 * 1024 * 1024;
    /**
     * 日志归档文件的最长保留天数
     */
    private static final int LOG_RETENTION_DAYS = 7;


    private static volatile FLog instance;

    private final ExecutorService executor;
    private File logFile;
    private File logDir;
    private Context appContext;


    private FLog() {
        // 使用单线程线程池确保所有文件操作（写入、轮转、清理）按顺序执行
        this.executor = Executors.newSingleThreadExecutor();
    }

    public static FLog getInstance() {
        if (instance == null) {
            synchronized (FLog.class) {
                if (instance == null) {
                    instance = new FLog();
                }
            }
        }
        return instance;
    }

    /**
     * 初始化Logger，必须在Application的onCreate中调用一次。
     *
     * @param context Application context
     */
    public static void init(Context context) {
        getInstance().internalInit(context.getApplicationContext());
    }

    public static void d(String tag, String message) {
        getInstance().log("DEBUG", tag, message, null);
    }

    public static void i(String tag, String message) {
        getInstance().log("INFO", tag, message, null);
    }

    public static void w(String tag, String message) {
        getInstance().log("WARN", tag, message, null);
    }

    public static void w(String tag, String message, Throwable throwable) {
        getInstance().log("WARN", tag, message, throwable);
    }

    public static void e(String tag, String message) {
        getInstance().log("ERROR", tag, message, null);
    }

    public static void e(String tag, String message, Throwable throwable) {
        getInstance().log("ERROR", tag, message, throwable);
    }

    public static Uri getLogFileUri() {
        return getInstance().internalGetLogFileUri();
    }


    private void internalInit(Context context) {
        if (this.appContext != null) return;

        this.appContext = context;

        File downloadDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        this.logDir = new File(downloadDir, LOG_SUB_DIRECTORY);
        if (!logDir.exists()) {
            logDir.mkdirs();
        }
        this.logFile = new File(logDir, LOG_FILE_NAME);

        // 在后台线程执行旧日志清理任务，避免阻塞App启动
        executor.execute(this::cleanupOldLogs);
    }

    private Uri internalGetLogFileUri() {
        if (appContext == null || logFile == null) {
            Log.e(TAG, "Logger not initialized.");
            return null;
        }

        if (!logFile.exists()) {
            try {
                logFile.createNewFile();
            } catch (IOException e) {
                Log.e(TAG, "Could not create new log file.", e);
                return null;
            }
        }

        String authority = appContext.getPackageName() + ".fileprovider";
        return FileProvider.getUriForFile(appContext, authority, logFile);
    }

    private void log(final String level, final String tag, final String message, final Throwable throwable) {
        if (appContext == null) {
            Log.e(TAG, "Logger not initialized. Call FLog.init(context) first.");
            return;
        }

        // 步骤1: 同步输出到Logcat
        printToLogcat(level, tag, message, throwable);

        // 步骤2: 将文件IO操作（轮转检查、写入）提交到后台线程
        executor.execute(() -> {
            try {
                // 写入前检查是否需要轮转日志文件
                if (logFile.exists() && logFile.length() > MAX_FILE_SIZE_BYTES) {
                    rotateLogFile();
                }

                // 写入日志内容
                try (FileWriter fileWriter = new FileWriter(logFile, true);
                     BufferedWriter bufferedWriter = new BufferedWriter(fileWriter)) {

                    String timeStamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(new Date());
                    String logMessage = timeStamp + " [" + level + "/" + tag + "]: " + message + "\n";
                    bufferedWriter.write(logMessage);

                    if (throwable != null) {
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        throwable.printStackTrace(pw);
                        bufferedWriter.write(sw.toString());
                        bufferedWriter.write("\n");
                        pw.close();
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to write to log file", e);
            }
        });
    }

    /**
     * 执行日志轮转。将当前日志文件重命名并附上时间戳。
     * 新的日志将自动写入到新创建的同名文件中。
     */
    private void rotateLogFile() {
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date());
        String archiveFileName = LOG_FILE_BASE_NAME + "_" + timestamp + LOG_FILE_EXTENSION;
        File archiveFile = new File(logDir, archiveFileName);

        if (logFile.renameTo(archiveFile)) {
            Log.i(TAG, "Log file rotated to: " + archiveFile.getName());
        } else {
            Log.w(TAG, "Could not rotate log file. Appending to existing oversized file.");
        }
    }

    /**
     * 清理旧的日志归档文件。
     * 此方法在App启动时被调用。
     */
    private void cleanupOldLogs() {
        if (logDir == null || !logDir.isDirectory()) {
            return;
        }

        File[] files = logDir.listFiles();
        if (files == null || files.length == 0) {
            return;
        }

        long cutoffTime = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(LOG_RETENTION_DAYS);

        Log.i(TAG, "Running cleanup for logs older than " + LOG_RETENTION_DAYS + " days...");
        for (File file : files) {
            // 跳过当前正在使用的日志文件
            if (file.getName().equals(LOG_FILE_NAME)) {
                continue;
            }

            // 删除超过保留期限的旧归档文件
            if (file.lastModified() < cutoffTime) {
                if (file.delete()) {
                    Log.d(TAG, "Deleted old log file: " + file.getName());
                } else {
                    Log.w(TAG, "Failed to delete old log file: " + file.getName());
                }
            }
        }
        Log.i(TAG, "Log cleanup finished.");
    }

    private void printToLogcat(final String level, final String tag, final String message, final Throwable throwable) {
        switch (level) {
            case "DEBUG":
                Log.d(tag, message);
                break;
            case "INFO":
                Log.i(tag, message);
                break;
            case "WARN":
                if (throwable != null) {
                    Log.w(tag, message, throwable);
                } else {
                    Log.w(tag, message);
                }
                break;
            case "ERROR":
                if (throwable != null) {
                    Log.e(tag, message, throwable);
                } else {
                    Log.e(tag, message);
                }
                break;
            default:
                Log.v(tag, message);
                break;
        }
    }
}