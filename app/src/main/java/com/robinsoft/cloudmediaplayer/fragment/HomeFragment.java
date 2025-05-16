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
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

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

    // 自动播放相关
    private String currentDirId;
    private String autoRootId;
    private Deque<String> autoPlayStack = new ArrayDeque<>();
    private boolean isAutoPlaying = false;

    private CloudMediaAdapter adapter;

    // 声明 ApkDownloader 成员变量
    private ApkDownloader apkDownloader;

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        apkDownloader = new ApkDownloader(context);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    private void appendLog(String s) {
        if (!isAdded() || getActivity() == null) return;
        TestFragment logFrag = (TestFragment) getActivity()
                .getSupportFragmentManager()
                .findFragmentByTag("test_fr");
        if (logFrag != null) {
            String ts = logFrag.getTimestamp();
            logFrag.appendLog(ts + s);
            Log.d(TAG, s);
        }
    }

    // 刷新当前目录列表
    private void refreshDirectory() {
        mediaService.listMedia(currentDirId)
                .observe(getViewLifecycleOwner(), items -> adapter.submitList(items));
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

        // ExoPlayer 初始化
        trackSelector = new DefaultTrackSelector(requireContext());
        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(requireContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true);
        exoPlayer = new ExoPlayer.Builder(requireContext(), renderersFactory)
                .setTrackSelector(trackSelector)
                .build();

        // 播放器监听
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "播放错误: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
                appendLog("播放错误: " + error.getMessage());
            }

            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_READY) {
                    MappingTrackSelector.MappedTrackInfo info =
                            trackSelector.getCurrentMappedTrackInfo();
                    if (info != null) {
                        for (int i = 0; i < info.getRendererCount(); i++) {
                            String name = info.getRendererName(i);
                            appendLog("渲染器[" + i + "] = " + name);
                        }
                    }
                }
            }
        });

        exoPlayer.addAnalyticsListener(new AnalyticsListener() {
            @Override
            public void onAudioEnabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                String line = eventTime.eventPlaybackPositionMs + " 音频解码器已启用  dropped=" + counters.maxConsecutiveDroppedBufferCount;
                appendLog(line);
            }

            @Override
            public void onVideoEnabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                String line = eventTime.eventPlaybackPositionMs + " 视频解码器已启用  dropped=" + counters.maxConsecutiveDroppedBufferCount;
                appendLog(line);
            }

            @Override
            public void onAudioInputFormatChanged(AnalyticsListener.EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation evaluation) {
                String line = eventTime.eventPlaybackPositionMs + " 音频输入格式切换  mime=" + format.sampleMimeType;
                appendLog(line);
            }

            @Override
            public void onVideoInputFormatChanged(AnalyticsListener.EventTime eventTime, Format format, @Nullable DecoderReuseEvaluation evaluation) {
                String line = eventTime.eventPlaybackPositionMs + " 视频输入格式切换  mime=" + format.sampleMimeType
                        + " 分辨率=" + format.width + "x" + format.height;
                appendLog(line);
            }

            @Override
            public void onAudioDisabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                appendLog(eventTime.eventPlaybackPositionMs + " 音频解码器已禁用");
            }

            @Override
            public void onVideoDisabled(AnalyticsListener.EventTime eventTime, DecoderCounters counters) {
                appendLog(eventTime.eventPlaybackPositionMs + " 视频解码器已禁用");
            }
        });

        playerView.setPlayer(exoPlayer);
        playerView.setControllerShowTimeoutMs(1000);

        // 初始化适配器
        adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        // 条目点击
        adapter.setOnItemClickListener(item -> {
            if (!isAdded() || getContext() == null) return;
            if (item.getType() == CloudMediaItem.MediaType.FOLDER) {
                if (exoPlayer != null) exoPlayer.pause();
                currentDirId = item.getId();
                refreshDirectory();
                return;
            }
            switch (item.getType()) {
                case IMAGE:
                    if (exoPlayer != null) exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    String imgUrl = item.getUrl();
                    if (imgUrl.contains("1drv.com")) {
                        imgUrl += (imgUrl.contains("?") ? "&" : "?") + "download=1";
                    }
                    loadImageAsync(imgUrl, true);
                    break;
                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                    String url = item.getUrl();
                    if (url.contains("1drv.com")) {
                        url += (url.contains("?") ? "&" : "?") + "download=1";
                    }
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                    exoPlayer.prepare();
                    exoPlayer.play();
                    enterFullscreen();
                    break;
                case APK:
                    Toast.makeText(getContext(), "准备安装：" + item.getName(), Toast.LENGTH_SHORT).show();
                    String apkUrl = item.getUrl();
                    if (apkUrl.contains("1drv.com")) {
                        apkUrl += (apkUrl.contains("?") ? "&" : "?") + "download=1";
                    }
                    downloadAndInstallApk(apkUrl, item.getName());
                    break;
                default:
                    Toast.makeText(getContext(), "无法播放该文件类型", Toast.LENGTH_SHORT).show();
            }
        });

        // 登录按钮
        btnLogin.setOnClickListener(v -> mediaService.authenticate(requireActivity(), new CloudMediaService.AuthCallback() {
            @Override
            public void onSuccess() {
                currentDirId = null;
                mediaService.listMedia(null)
                        .observe(getViewLifecycleOwner(), items -> adapter.submitList(items));
            }

            @Override
            public void onError(Throwable error) {
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "登录失败: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }));

        // 根目录按钮
        btnRoot.setOnClickListener(v -> {
            currentDirId = null;
            refreshDirectory();
        });

        // 自动播放按钮
        btnGo.setOnClickListener(v -> {
            if (!isAutoPlaying) {
                autoRootId = currentDirId;
                autoPlayStack.clear();
                autoPlayStack.push(autoRootId);
                isAutoPlaying = true;
                btnGo.setText("停止");
                enterFullscreen();
                startAutoPlay();
            } else {
                exitFullscreen();
                stopAutoPlay();
                btnGo.setText("播放");
                refreshDirectory();
            }
        });

        // 播放器及图片点击：停止自动播放 & 退出全屏
        View.OnClickListener stopOnClick = v -> {
            if (isAutoPlaying && isFullscreen) {
                exitFullscreen();
                stopAutoPlay();
                btnGo.setText("播放");
                refreshDirectory();
            } else {
                toggleFullscreen();
            }
        };
        playerView.setOnClickListener(stopOnClick);
        imageView.setOnClickListener(stopOnClick);

        // 音量进度条
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                if (exoPlayer != null) exoPlayer.setVolume(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });
    }

    /**
     * 自动播放流程
     */
    private void startAutoPlay() {
        if (!isAutoPlaying) return;
        if (autoPlayStack.isEmpty()) {
            autoPlayStack.push(autoRootId);
        }
        String dirId = autoPlayStack.pop();
        mediaService.listMedia(dirId)
                .observe(getViewLifecycleOwner(), items -> {
                    List<String> childDirs = new ArrayList<>();
                    List<CloudMediaItem> files = new ArrayList<>();
                    for (CloudMediaItem it : items) {
                        if (it.getType() == CloudMediaItem.MediaType.FOLDER) {
                            childDirs.add(it.getId());
                        } else if (it.getType() == CloudMediaItem.MediaType.IMAGE || it.getType() == CloudMediaItem.MediaType.VIDEO) {
                            files.add(it);
                        }
                    }
                    // 按修改时间升序
                    Comparator<CloudMediaItem> byTime = Comparator.comparing(CloudMediaItem::getLastModifiedDateTime);
                    childDirs.sort((a, b) -> 0); // 无时间字段时保持顺序
                    files.sort(byTime);
                    // 逆序入栈
                    for (int i = childDirs.size() - 1; i >= 0; i--) {
                        autoPlayStack.push(childDirs.get(i));
                    }
                    playFilesSequentially(files, this::startAutoPlay);
                });
    }

    private void playFilesSequentially(List<CloudMediaItem> files, Runnable onAllDone) {
        playFileAtIndex(files, 0, onAllDone);
    }

    private void playFileAtIndex(List<CloudMediaItem> files, int idx, Runnable onAllDone) {
        if (!isAutoPlaying) return;
        if (idx >= files.size()) {
            onAllDone.run();
            return;
        }
        CloudMediaItem item = files.get(idx);
        String url = item.getUrl() + (item.getUrl().contains("?") ? "&" : "?") + "download=1";
        if (item.getType() == CloudMediaItem.MediaType.IMAGE) {
            playerView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            loadImageAsync(url, false);
            imageView.postDelayed(() -> playFileAtIndex(files, idx + 1, onAllDone), 3_000);
        } else {
            imageView.setVisibility(View.GONE);
            playerView.setVisibility(View.VISIBLE);
            exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
            exoPlayer.prepare();
            exoPlayer.play();
            exoPlayer.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) {
                        exoPlayer.removeListener(this);
                        playFileAtIndex(files, idx + 1, onAllDone);
                    }
                }
            });
        }
    }

    private void stopAutoPlay() {
        isAutoPlaying = false;
        autoPlayStack.clear();
        if (exoPlayer != null) exoPlayer.pause();
    }

    private void loadImageAsync(String url, boolean needFullscreen) {
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
                conn.disconnect();

                if (!isAdded() || imageView == null) return;
                int reqW = imageView.getWidth() > 0 ? imageView.getWidth() : Resources.getSystem().getDisplayMetrics().widthPixels;
                int reqH = imageView.getHeight() > 0 ? imageView.getHeight() : Resources.getSystem().getDisplayMetrics().heightPixels;
                opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
                opts.inJustDecodeBounds = false;

                conn = (HttpURLConnection) u.openConnection();
                conn.setDoInput(true);
                conn.connect();
                final Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
                conn.disconnect();

                if (isAdded() && getActivity() != null && imageView != null && bmp != null) {
                    getActivity().runOnUiThread(() -> {
                        imageView.setImageBitmap(bmp);
                        if (needFullscreen) imageView.post(this::enterFullscreen);
                    });
                } else if (bmp == null && isAdded() && getActivity() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "解码图片失败", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                e.printStackTrace();
                if (isAdded() && getActivity() != null && getContext() != null) {
                    getActivity().runOnUiThread(() -> Toast.makeText(getContext(), "加载图片失败: " + e.getMessage(), Toast.LENGTH_SHORT).show());
                }
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
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
        if (isFullscreen) return;
        isFullscreen = true;
        Activity activity = getActivity();
        if (activity == null) return;
        Window window = activity.getWindow();

        View controls = activity.findViewById(R.id.controls_container);
        if (controls != null) controls.setVisibility(View.GONE);
        View bottomNav = activity.findViewById(R.id.bottom_nav);
        if (bottomNav != null) bottomNav.setVisibility(View.GONE);
        View bottom = activity.findViewById(R.id.bottom_container);
        if (bottom != null) {
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottom.getLayoutParams();
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
            bottom.setLayoutParams(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = window.getInsetsController();
            if (ic != null) {
                ic.hide(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
                ic.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        View toFs = imageView.getVisibility() == View.VISIBLE ? imageView : playerView;
        ViewGroup.LayoutParams lp = toFs.getLayoutParams();
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT;
        lp.height = ViewGroup.LayoutParams.MATCH_PARENT;
        toFs.setLayoutParams(lp);
        toFs.requestLayout();
        toFs.bringToFront();
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;
        isFullscreen = false;
        Activity activity = getActivity();
        if (activity == null) return;
        Window window = activity.getWindow();

        View controls = activity.findViewById(R.id.controls_container);
        if (controls != null) controls.setVisibility(View.VISIBLE);
        View bottomNav = activity.findViewById(R.id.bottom_nav);
        if (bottomNav != null) bottomNav.setVisibility(View.VISIBLE);
        View bottom = activity.findViewById(R.id.bottom_container);
        if (bottom != null) {
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottom.getLayoutParams();
            lp.topToTop = ConstraintLayout.LayoutParams.UNSET;
            lp.topToBottom = R.id.guideline_half;
            bottom.setLayoutParams(lp);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsetsController ic = window.getInsetsController();
            if (ic != null)
                ic.show(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
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
        if (apkDownloader != null) {
            // apkDownloader.cleanup();
        }
        playerView = null;
        imageView = null;
        recyclerView = null;
        volumeSeekBar = null;
        btnLogin = null;
        btnRoot = null;
        btnGo = null;
        trackSelector = null;
    }

    private void downloadAndInstallApk(String url, String fileName) {
        if (apkDownloader != null) {
            if (isAdded() && getActivity() != null) {
                apkDownloader.downloadAndInstallApk(url, fileName, requireActivity());
            } else {
                Log.e(TAG, "Fragment not attached to an activity, cannot start download.");
            }
        } else {
            Log.e(TAG, "ApkDownloader not initialized!");
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "下载器服务未准备好", Toast.LENGTH_SHORT).show();
            }
        }
    }
}
