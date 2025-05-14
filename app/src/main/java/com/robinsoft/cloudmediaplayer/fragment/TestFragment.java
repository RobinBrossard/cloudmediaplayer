package com.robinsoft.cloudmediaplayer.fragment;

import android.content.Context;
import android.content.res.Configuration;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaCodecInfo.CodecCapabilities;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.robinsoft.cloudmediaplayer.R;
import android.opengl.GLES20;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

public class TestFragment extends Fragment {

    private Button btnTestVideo, btnUnused2, btnUnused3, btnUnused4, btnLog;
    private TextView tvLog;
    private ScrollView scrollView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_test, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view,
                              @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        scrollView   = view.findViewById(R.id.scroll_view);
        tvLog        = view.findViewById(R.id.tv_log);
        btnTestVideo = view.findViewById(R.id.btn_test_video);
        btnUnused2   = view.findViewById(R.id.btn_unused_2);
        btnUnused3   = view.findViewById(R.id.btn_unused_3);
        btnUnused4   = view.findViewById(R.id.btn_unused_4);
        btnLog       = view.findViewById(R.id.btn_log);

        btnUnused2.setEnabled(false);
        btnUnused3.setEnabled(false);
        btnUnused4.setEnabled(false);

        btnTestVideo.setOnClickListener(v -> {
            appendLog(getTimestamp() + " —— 兼容性测试开始 ——");
            performVideoCompatibilityTest();
            appendLog(getTimestamp() + " —— 系统信息收集 ——");
            collectSystemInfo();
            appendLog(getTimestamp() + " —— 兼容性测试结束 ——\n");
        });

        btnLog.setOnClickListener(v -> {
            appendLog(getTimestamp() + " 导出日志到文件");
            exportLogToFile();
        });
    }

    /** 视频解码器/编码器测试（不变） */
    private void performVideoCompatibilityTest() {
        String[] mimes = {
                "video/avc", "video/hevc", "video/mp4v-es",
                "video/3gpp", "video/x-vnd.on2.vp8",
                "video/x-vnd.on2.vp9", "video/x-ms-wmv"
        };
        for (String mime : mimes) {
            listCodecSupport(mime);
        }
    }

    private void listCodecSupport(String mimeType) {
        appendLog(getTimestamp() + " 检查 MIME: " + mimeType);
        MediaCodecList list = new MediaCodecList(MediaCodecList.ALL_CODECS);
        MediaCodecInfo[] infos = list.getCodecInfos();

        // Decoder
        appendLog(getTimestamp() + "  解码器:");
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) {
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        logCodecInfo(info, mimeType, false);
                    }
                }
            }
        }
        // Encoder
        appendLog(getTimestamp() + "  编码器:");
        for (MediaCodecInfo info : infos) {
            if (info.isEncoder()) {
                for (String type : info.getSupportedTypes()) {
                    if (type.equalsIgnoreCase(mimeType)) {
                        logCodecInfo(info, mimeType, true);
                    }
                }
            }
        }
    }

    private void logCodecInfo(MediaCodecInfo info, String mime, boolean isEnc) {
        String role = isEnc ? "Encoder" : "Decoder";
        String name = info.getName();
        boolean sw = Build.VERSION.SDK_INT >= 29
                ? info.isSoftwareOnly()
                : name.toLowerCase(Locale.ROOT).contains("google");
        appendLog(getTimestamp() + String.format("    [%s] %s (%s)", role, name, sw ? "软件" : "硬件"));
        try {
            CodecCapabilities caps = info.getCapabilitiesForType(mime);
            int[] colors = caps.colorFormats;
            StringBuilder sb = new StringBuilder();
            for (int c : colors) sb.append(c).append(", ");
            if (sb.length() > 2) sb.setLength(sb.length()-2);
            appendLog(getTimestamp() + "      颜色格式: " + sb);
        } catch (IllegalArgumentException e) {
            appendLog(getTimestamp() + "      无法获取颜色格式");
        }
    }

    /** 收集并输出系统信息：GPU、ROM、磁盘总量/可用，及当前屏幕分辨率和方向 */
    private void collectSystemInfo() {
        // 1. EGL 信息
        EGLDisplay display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] ver = new int[2];
        EGL14.eglInitialize(display, ver, 0, ver, 1);
        appendLog(getTimestamp() + " EGL 厂商    : " + EGL14.eglQueryString(display, EGL14.EGL_VENDOR));
        appendLog(getTimestamp() + " EGL 版本    : " + EGL14.eglQueryString(display, EGL14.EGL_VERSION));

        // 2. GLES 信息
        appendLog(getTimestamp() + " GL 厂商     : " + GLES20.glGetString(GLES20.GL_VENDOR));
        appendLog(getTimestamp() + " GL 渲染器   : " + GLES20.glGetString(GLES20.GL_RENDERER));
        appendLog(getTimestamp() + " GL 版本     : " + GLES20.glGetString(GLES20.GL_VERSION));

        // 3. ROM 信息
        appendLog(getTimestamp() + " ROM 版本    : " + Build.DISPLAY);
        appendLog(getTimestamp() + " ROM 指纹    : " + Build.FINGERPRINT);

        // 4. 本地存储
        StatFs sf = new StatFs(Environment.getDataDirectory().getPath());
        long total = sf.getBlockCountLong() * sf.getBlockSizeLong();
        long avail = sf.getAvailableBlocksLong() * sf.getBlockSizeLong();
        appendLog(getTimestamp() + " 存储总量    : " + formatSize(total));
        appendLog(getTimestamp() + " 存储可用    : " + formatSize(avail));

        // 5. 屏幕分辨率 & 方向
        WindowManager wm = (WindowManager) requireContext().getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics dm = new DisplayMetrics();
        Display d = wm.getDefaultDisplay();
        // API 30+ 建议用： requireActivity().getDisplay().getRealMetrics(dm);
        d.getRealMetrics(dm);
        int width  = dm.widthPixels;
        int height = dm.heightPixels;
        int ori = getResources().getConfiguration().orientation;
        String orientation = (ori == Configuration.ORIENTATION_LANDSCAPE) ? "横屏" : "竖屏";
        appendLog(getTimestamp() + " 屏幕分辨率  : " + width + "×" + height + "，方向：" + orientation);
    }

    /** 格式化字节数到可读字符串 */
    private String formatSize(long bytes) {
        double kb = bytes/1024.0, mb = kb/1024.0, gb = mb/1024.0;
        if (gb >= 1) return String.format(Locale.getDefault(), "%.2f GB", gb);
        if (mb >= 1) return String.format(Locale.getDefault(), "%.2f MB", mb);
        if (kb >= 1) return String.format(Locale.getDefault(), "%.2f KB", kb);
        return bytes + " B";
    }

    /** 追加日志并滚动 */
    public void appendLog(String msg) {
        tvLog.append(msg);
        tvLog.append("\n");
        scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
    }

    /** 时间戳 */
    public String getTimestamp() {
        return new SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(new Date());
    }

    /** 导出并记录 */
    private void exportLogToFile() {
        String logs = tvLog.getText().toString();
        String fname = "log_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(new Date()) + ".txt";
        File dir = requireContext().getExternalFilesDir(null);
        if (dir == null) {
            appendLog(getTimestamp() + " 无法访问外部存储");
            return;
        }
        File file = new File(dir, fname);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            fos.write(logs.getBytes());
            appendLog(getTimestamp() + " 日志保存至：" + file.getAbsolutePath());
        } catch (IOException e) {
            appendLog(getTimestamp() + " 保存失败：" + e.getMessage());
        }
    }
}
