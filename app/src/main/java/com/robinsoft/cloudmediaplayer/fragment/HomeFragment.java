package com.robinsoft.cloudmediaplayer.fragment;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.Button;
import android.widget.FrameLayout;
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
    private Button btnLogin, btnRoot;
    private boolean isFullscreen = false;

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
        recyclerView = view.findViewById(R.id.recyclerView);
        imageView = view.findViewById(R.id.imageView);
        playerView = view.findViewById(R.id.player_view);
        volumeSeekBar = view.findViewById(R.id.volume_seekbar);

        // TrackSelector & ExoPlayer 初始化
        trackSelector = new DefaultTrackSelector(requireContext());

        //优选编码
        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(requireContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true);

        // 构造 ExoPlayer，上面两种构造方法选一
        exoPlayer = new ExoPlayer.Builder(requireContext(), renderersFactory)
                .setTrackSelector(trackSelector).build();

        // 普通播放状态 & 错误监听
        exoPlayer.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "播放错误: " + error.getMessage());
                Toast.makeText(requireContext(), "播放错误: " + error.getMessage(), Toast.LENGTH_LONG).show();
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
                TestFragment logFrag = (TestFragment) requireActivity().getSupportFragmentManager().findFragmentByTag("test_fr");
                if (logFrag != null) {
                    String ts = logFrag.getTimestamp();
                    logFrag.appendLog(ts + s);
                    Log.d(TAG, s);
                }
            }
        });

        // 细粒度解码跟踪：使用新的 Audio/Video 回调
        exoPlayer.addAnalyticsListener(new AnalyticsListener() {
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
                //requireActivity().getSupportFragmentManager().findFragmentByTag("test_fr");
                if (logFrag != null) {
                    String ts = logFrag.getTimestamp();
                    logFrag.appendLog(ts + s);
                    Log.d(TAG, s);
                }
            }
        });

        // 3. 给 PlayerView 设置 player
        playerView.setPlayer(exoPlayer);
        playerView.setControllerShowTimeoutMs(1000);
        //playerView.showController();
        // 点击退出全屏
        playerView.setOnClickListener(v -> toggleFullscreen());

        // 4. （可选）音量滑杆监听
        volumeSeekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar sb, int progress, boolean fromUser) {
                exoPlayer.setVolume(progress / 100f);
            }

            @Override
            public void onStartTrackingTouch(SeekBar sb) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar sb) {
            }
        });


        // RecyclerView
        CloudMediaAdapter adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        // 登录
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
                Toast.makeText(requireContext(), "登录失败: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));
        // 回根目录
        btnRoot.setOnClickListener(v -> mediaService.listMedia(null)
                .observe(getViewLifecycleOwner(), adapter::submitList));

        // 列表点击
        adapter.setOnItemClickListener(item -> {
            switch (item.getType()) {
                case IMAGE:
                    exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    loadImageAsync(item.getUrl());
                    enterFullscreen();
                    imageView.setOnClickListener(v -> toggleFullscreen());
                    break;
                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                    String url = item.getUrl();
                    if (url.contains("1drv.com"))
                        url += (url.contains("?") ? "&" : "?") + "download=1";
                    //Log.d(TAG, "Exo 播放直链: " + url);
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                    exoPlayer.prepare();
                    exoPlayer.play();
                    enterFullscreen();
                    break;
                case FOLDER:
                    exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                    mediaService.listMedia(item.getId()).observe(getViewLifecycleOwner(), adapter::submitList);
                    break;
                default:
                    Toast.makeText(requireContext(), "无法播放该文件类型", Toast.LENGTH_SHORT).show();
            }
        });
    }


    // —— 图片加载 ——
    private void loadImageAsync(String url) {
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

                int reqW = imageView.getWidth() > 0 ? imageView.getWidth() : Resources.getSystem().getDisplayMetrics().widthPixels;
                int reqH = imageView.getHeight() > 0 ? imageView.getHeight() : Resources.getSystem().getDisplayMetrics().heightPixels;
                opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
                opts.inJustDecodeBounds = false;

                conn = (HttpURLConnection) u.openConnection();
                conn.setDoInput(true);
                conn.connect();
                final Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
                conn.disconnect();
                requireActivity().runOnUiThread(() -> imageView.setImageBitmap(bmp));
            } catch (Exception e) {
                e.printStackTrace();
                requireActivity().runOnUiThread(() -> Toast.makeText(requireContext(), "加载图片失败", Toast.LENGTH_SHORT).show());
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
        isFullscreen = true;
        // 隐藏顶部列表区
        View topContainer = requireActivity().findViewById(R.id.top_container);
        if (topContainer != null) topContainer.setVisibility(View.GONE);

        // 扩展下半区到底部全屏
        ConstraintLayout.LayoutParams blp =
                (ConstraintLayout.LayoutParams) requireActivity()
                        .findViewById(R.id.bottom_container)
                        .getLayoutParams();
        // 取消顶部“连接到 guideline”
        blp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
        blp.topToBottom = ConstraintLayout.LayoutParams.UNSET;
        requireActivity().findViewById(R.id.bottom_container)
                .setLayoutParams(blp);

        // 隐藏系统 UI（StatusBar & Navigation）
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().getWindow().getInsetsController().hide(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            requireActivity().getWindow().getInsetsController()
                    .setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            requireActivity().getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN |
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
        // 隐藏其他控件
        btnLogin.setVisibility(View.GONE);
        btnRoot.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
        // 隐藏底部导航/分页按钮（假设ID为 bottom_nav）
        View bottomNav = requireActivity().findViewById(R.id.bottom_nav);
        if (bottomNav != null) bottomNav.setVisibility(View.GONE);
        // 将播放器View上下居中
        if (playerView.getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) playerView.getLayoutParams();
            flp.gravity = Gravity.CENTER;
            playerView.setLayoutParams(flp);
        }
        if (isFullscreen) return;
        isFullscreen = true;
        // 隐藏系统 UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().getWindow().getInsetsController().hide(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
            requireActivity().getWindow().getInsetsController()
                    .setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            View decor = requireActivity().getWindow().getDecorView();
            int flags = View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;
            decor.setSystemUiVisibility(flags);
        }
        // 隐藏其余 UI
        btnLogin.setVisibility(View.GONE);
        btnRoot.setVisibility(View.GONE);
        recyclerView.setVisibility(View.GONE);
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;
        isFullscreen = false;

        // 恢复顶部列表区
        View topContainer = requireActivity().findViewById(R.id.top_container);
        if (topContainer != null) topContainer.setVisibility(View.VISIBLE);

        // 恢复下半区原始约束
        ConstraintLayout.LayoutParams blp =
                (ConstraintLayout.LayoutParams) requireActivity()
                        .findViewById(R.id.bottom_container)
                        .getLayoutParams();
        blp.topToTop = ConstraintLayout.LayoutParams.UNSET;
        blp.topToBottom = R.id.guideline_half;
        requireActivity().findViewById(R.id.bottom_container)
                .setLayoutParams(blp);

        // 恢复系统 UI
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            requireActivity().getWindow().getInsetsController().show(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        } else {
            requireActivity().getWindow().getDecorView()
                    .setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
        // 恢复其他 UI
        btnLogin.setVisibility(View.VISIBLE);
        btnRoot.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.VISIBLE);
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
    }

}
