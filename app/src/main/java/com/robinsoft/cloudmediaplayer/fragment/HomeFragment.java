package com.robinsoft.cloudmediaplayer.fragment;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.SeekBar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.fragment.app.Fragment;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DecoderCounters;
import androidx.media3.exoplayer.DecoderReuseEvaluation;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.analytics.AnalyticsListener;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.adapter.CloudMediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.net.HttpURLConnection;
import java.net.URL;

@UnstableApi
public class HomeFragment extends Fragment {

    private static final String TAG = "MP_PLAYBACK";
    private final CloudMediaService mediaService = new OneDriveMediaService();
    private DefaultTrackSelector trackSelector;
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private SeekBar volumeSeekBar;
    private ImageView imageView;
    private RecyclerView recyclerView;
    private Button btnLogin, btnRoot, btnGo;
    private boolean isFullscreen = false;

    // 声明 ApkDownloader 成员变量
    private ApkDownloader apkDownloader;

    // 在 onAttach 中初始化 ApkDownloader，这是一个好时机，因为 Context 在此时可用
    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        // 使用 context 初始化 ApkDownloader，它内部会获取 ApplicationContext
        apkDownloader = new ApkDownloader(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @UnstableApi
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // UI 绑定
        btnLogin = view.findViewById(R.id.btnLogin);
        btnRoot = view.findViewById(R.id.btnRoot);
        btnGo = view.findViewById(R.id.btnGo);
        recyclerView = view.findViewById(R.id.recyclerView);
        imageView = view.findViewById(R.id.imageView);
        playerView = view.findViewById(R.id.player_view);
        volumeSeekBar = view.findViewById(R.id.volume_seekbar);

        // TrackSelector & ExoPlayer 初始化 (您的代码保持不变)
        trackSelector = new DefaultTrackSelector(requireContext());
        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(requireContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true);
        exoPlayer = new ExoPlayer.Builder(requireContext(), renderersFactory)
                .setTrackSelector(trackSelector).build();

        // ExoPlayer 监听器等 (您的代码保持不变)
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                if (isAdded() && getContext() != null) { // 检查 Fragment 状态
                    Toast.makeText(getContext(), "播放错误: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
                appendLog("播放错误: " + error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    MappingTrackSelector.MappedTrackInfo info = trackSelector.getCurrentMappedTrackInfo();
                    if (info != null) {
                        for (int i = 0; i < info.getRendererCount(); i++) {
                            String name = info.getRendererName(i);
                            appendLog("渲染器[" + i + "] = " + name);
                        }
                    }
                }
            }

            private void appendLog(String s) {
                // 确保 getActivity() 不为 null 并且 Fragment isAdded()
                if (!isAdded() || getActivity() == null) return;
                TestFragment logFrag = (TestFragment) getActivity().getSupportFragmentManager().findFragmentByTag("test_fr");
                if (logFrag != null) {
                    String ts = logFrag.getTimestamp();
                    logFrag.appendLog(ts + s);
                    Log.d(TAG, s);
                }
            }
        });

        exoPlayer.addAnalyticsListener(new AnalyticsListener() {
            // ... (您的 AnalyticsListener 代码保持不变) ...
            @Override
            public void onAudioEnabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                String line = eventTime.eventPlaybackPositionMs + " 音频解码器已启用  dropped=" + counters.maxConsecutiveDroppedBufferCount;
                Log.d(TAG, line);
                appendLog(line);
            }

            @Override
            public void onVideoEnabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                String line = eventTime.eventPlaybackPositionMs + " 视频解码器已启用  dropped=" + counters.maxConsecutiveDroppedBufferCount;
                Log.d(TAG, line);
                appendLog(line);
            }

            @Override
            public void onAudioInputFormatChanged(AnalyticsListener.EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
                String line = eventTime.eventPlaybackPositionMs + " 音频输入格式切换  mime=" + format.sampleMimeType;
                Log.d(TAG, line);
                appendLog(line);
            }

            @Override
            public void onVideoInputFormatChanged(AnalyticsListener.EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation decoderReuseEvaluation) {
                String line = eventTime.eventPlaybackPositionMs + " 视频输入格式切换  mime=" + format.sampleMimeType + " 分辨率=" + format.width + "x" + format.height;
                Log.d(TAG, line);
                appendLog(line);
            }

            @Override
            public void onAudioDisabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                String line = eventTime.eventPlaybackPositionMs + " 音频解码器已禁用";
                Log.d(TAG, line);
                appendLog(line);
            }

            @Override
            public void onVideoDisabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                String line = eventTime.eventPlaybackPositionMs + " 视频解码器已禁用";
                Log.d(TAG, line);
                appendLog(line);
            }

            private void appendLog(String s) {
                if (!isAdded() || getActivity() == null) return;
                TestFragment logFrag = (TestFragment)
                        getActivity().getSupportFragmentManager().findFragmentByTag("test_fr");
                if (logFrag != null) {
                    String ts = logFrag.getTimestamp();
                    logFrag.appendLog(ts + s);
                    Log.d(TAG, s);
                }
            }
        });


        playerView.setPlayer(exoPlayer);
        playerView.setControllerShowTimeoutMs(1000);
        playerView.setOnClickListener(v -> toggleFullscreen());

        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (exoPlayer != null) {
                    exoPlayer.setVolume(progress / 100f);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });

        CloudMediaAdapter adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        btnLogin.setOnClickListener(v -> mediaService.authenticate(requireActivity(), new CloudMediaService.AuthCallback() {
            @Override
            public void onSuccess() {
                mediaService.listMedia(null).observe(getViewLifecycleOwner(), items -> {
                    adapter.submitList(items);
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                });
            }

            @Override
            public void onError(Throwable error) {
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "登录失败: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }));

        btnRoot.setOnClickListener(v -> mediaService.listMedia(null)
                .observe(getViewLifecycleOwner(), adapter::submitList));

        adapter.setOnItemClickListener(item -> {
            if (!isAdded() || getContext() == null) return; // 防止 Context 为 null

            switch (item.getType()) {
                case IMAGE:
                    if (exoPlayer != null) exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    String imgUrl = item.getUrl();
                    if (imgUrl.contains("1drv.com")) {
                        imgUrl += (imgUrl.contains("?") ? "&" : "?") + "download=1";
                    }
                    loadImageAsync(imgUrl, true); // 使用修改后的 URL
                    imageView.setOnClickListener(v -> toggleFullscreen());
                    
                    break;
                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                    String url = item.getUrl();
                    if (url.contains("1drv.com"))
                        url += (url.contains("?") ? "&" : "?") + "download=1";
                    if (exoPlayer != null) {
                        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                        exoPlayer.prepare();
                        exoPlayer.play();
                    }
                    enterFullscreen();
                    break;
                case FOLDER:
                    if (exoPlayer != null) exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                    mediaService.listMedia(item.getId()).observe(getViewLifecycleOwner(), adapter::submitList);
                    break;
                case APK:
                    Toast.makeText(getContext(), "准备安装：" + item.getName(), Toast.LENGTH_SHORT).show();
                    String apkUrl = item.getUrl();
                    if (apkUrl.contains("1drv.com")) {
                        apkUrl += (apkUrl.contains("?") ? "&" : "?") + "download=1";
                    }
                    // 调用 ApkDownloader 的方法
                    downloadAndInstallApk(apkUrl, item.getName());
                    break;
                default:
                    Toast.makeText(getContext(), "无法播放该文件类型", Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void loadImageAsync(String url, boolean needFullscreen) {
        // ... (您的 loadImageAsync 代码保持不变) ...
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL u = new URL(url);
                conn = (HttpURLConnection) u.openConnection();
                conn.setDoInput(true);
                conn.connect();
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
                conn.disconnect(); // Important to disconnect here before making new connection

                // Ensure imageView is available and fragment is added
                if (!isAdded() || imageView == null) return;

                int reqW = imageView.getWidth() > 0 ? imageView.getWidth() : Resources.getSystem().getDisplayMetrics().widthPixels;
                int reqH = imageView.getHeight() > 0 ? imageView.getHeight() : Resources.getSystem().getDisplayMetrics().heightPixels;
                opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
                opts.inJustDecodeBounds = false;

                // Re-establish connection
                conn = (HttpURLConnection) u.openConnection();
                conn.setDoInput(true);
                conn.connect();
                final Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
                conn.disconnect(); // Disconnect after decoding

                if (isAdded() && getActivity() != null && imageView != null && bmp != null) {
                    getActivity().runOnUiThread(() -> {
                                imageView.setImageBitmap(bmp);
                                if (needFullscreen)
                                    // 确保 imageView 完成测量和布局
                                    imageView.post(this::enterFullscreen);
                            }
                    );

                } else if (bmp == null && isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "解码图片失败", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (isAdded() && getActivity() != null && getContext() != null) { // Check context for Toast
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "加载图片失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }).start();
    }


    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        // ... (您的 calculateInSampleSize 代码保持不变) ...
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfH = height / 2;
            final int halfW = width / 2;
            while ((halfH / inSampleSize) >= reqHeight && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    private void enterFullscreen() {
        // ... (您的 enterFullscreen 代码保持不变, 但请确保 getActivity() 和 findViewById 的安全性检查) ...
        if (isFullscreen) return;
        isFullscreen = true;
        Activity activity = getActivity();
        if (activity == null) return;
        Window window = activity.getWindow();
        // ... rest of your code
        View controls = activity.findViewById(R.id.controls_container);
        if (controls != null) {
            controls.setVisibility(View.GONE);
        }
        View bottomNav = activity.findViewById(R.id.bottom_nav);
        if (bottomNav != null) bottomNav.setVisibility(View.GONE);
        View bottom = activity.findViewById(R.id.bottom_container);
        if (bottom != null) {
            ConstraintLayout.LayoutParams lp =
                    (ConstraintLayout.LayoutParams) bottom.getLayoutParams();
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
            bottom.setLayoutParams(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = window.getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ic.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                );
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            );
        }

        if (imageView == null || playerView == null) return; // Add null checks for views
        View toFs = imageView.getVisibility() == View.VISIBLE
                ? imageView : playerView;
        ViewGroup.LayoutParams lp = toFs.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        toFs.setLayoutParams(lp);
        toFs.requestLayout();
        toFs.bringToFront();

    }

    private void exitFullscreen() {
        // ... (您的 exitFullscreen 代码保持不变, 但请确保 getActivity() 和 findViewById 的安全性检查) ...
        if (!isFullscreen) return;
        isFullscreen = false;
        Activity activity = getActivity();
        if (activity == null) return;
        Window window = activity.getWindow();
        // ... rest of your code
        View controls = activity.findViewById(R.id.controls_container);
        if (controls != null) {
            controls.setVisibility(View.VISIBLE);
        }
        View bottomNav = activity.findViewById(R.id.bottom_nav);
        if (bottomNav != null) {
            bottomNav.setVisibility(View.VISIBLE);
        }
        View bottom = activity.findViewById(R.id.bottom_container);
        if (bottom != null) {
            ConstraintLayout.LayoutParams lp =
                    (ConstraintLayout.LayoutParams) bottom.getLayoutParams();
            lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            lp.topToBottom = R.id.guideline_half; // Make sure R.id.guideline_half is valid
            bottom.setLayoutParams(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = window.getInsetsController();
            if (ic != null) {
                ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            }
        } else {
            window.getDecorView()
                    .setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }


    private void toggleFullscreen() {
        if (isFullscreen) exitFullscreen();
        else enterFullscreen();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        // 在 onDestroyView 中调用 ApkDownloader 的 cleanup 方法
        if (apkDownloader != null) {
            //Gemini说要在这里清除，但是Chatgpt说不要，暂时听chatgpt的
            //apkDownloader.cleanup();
        }
        // 将视图成员变量置空，帮助GC，防止内存泄漏
        playerView = null;
        imageView = null;
        recyclerView = null;
        volumeSeekBar = null;
        btnLogin = null;
        btnRoot = null;
        btnGo = null;
        // trackSelector 可能也需要某种形式的清理或置空，具体取决于其实现
        trackSelector = null;
    }

    private void downloadAndInstallApk(String url, String fileName) {
        if (apkDownloader != null) {
            // 使用 requireActivity() 作为 Context，因为它是一个 Activity Context，
            // ApkDownloader 中的 downloadAndInstallApk 方法需要它来显示 Toast 和启动安装程序。
            if (isAdded() && getActivity() != null) { // 确保 Fragment 仍然附加到 Activity
                apkDownloader.downloadAndInstallApk(url, fileName, requireActivity());
            } else {
                Log.e(TAG, "Fragment not attached to an activity, cannot start download.");
            }
        } else {
            // 这种情况理论上不应该发生，因为我们在 onAttach 中初始化了 apkDownloader
            Log.e(TAG, "ApkDownloader not initialized!");
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "下载器服务未准备好", Toast.LENGTH_SHORT).show();
            }
        }
    }
}