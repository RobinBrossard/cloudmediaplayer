package com.robinsoft.cloudmediaplayer.fragment;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo;
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector;
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.exoplayer.trackselection.MappingTrackSelector;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.common.collect.ImmutableList;
import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.adapter.CloudMediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

@UnstableApi
public class HomeFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private static final String TAG = "MP_PLAYBACK";
    private final CloudMediaService mediaService = new OneDriveMediaService();
    private DefaultTrackSelector trackSelector;
    private ExoPlayer exoPlayer;
    private MediaPlayer mediaPlayer;
    private boolean isSurfaceReady = false;
    private TextureView textureView;
    private ImageView imageView;
    private RecyclerView recyclerView;
    private Button btnLogin, btnRoot;

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
        textureView = view.findViewById(R.id.texture_view);
        imageView = view.findViewById(R.id.imageView);

        textureView.setSurfaceTextureListener(this);

        // TrackSelector
        trackSelector = new DefaultTrackSelector(requireContext());

        // 只用软件解码器的 MediaCodecSelector
        MediaCodecSelector softwareOnlySelector = new MediaCodecSelector() {
            @Override
            public ImmutableList<MediaCodecInfo> getDecoderInfos(String mimeType, boolean requiresSecureDecoder, boolean requiresTunnelingDecoder) {
                List<MediaCodecInfo> all;
                try {
                    all = MediaCodecSelector.DEFAULT.getDecoderInfos(mimeType, requiresSecureDecoder, requiresTunnelingDecoder);
                } catch (MediaCodecUtil.DecoderQueryException e) {
                    Log.e(TAG, "DecoderQueryException 查询解码器列表失败", e);
                    return ImmutableList.of();
                }
                ImmutableList.Builder<MediaCodecInfo> software = ImmutableList.builder();
                for (MediaCodecInfo info : all) {
                    if (info.softwareOnly) {
                        software.add(info);
                    }
                }
                return software.build();
            }
        };


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
                TestFragment logFrag = (TestFragment) requireActivity().getSupportFragmentManager().findFragmentByTag("test_fr");
                if (logFrag != null) {
                    String ts = logFrag.getTimestamp();
                    logFrag.appendLog(ts + s);
                    Log.d(TAG, s);
                }
            }
        });

        exoPlayer.setVideoTextureView(textureView);
        isSurfaceReady = true;

        // RecyclerView
        CloudMediaAdapter adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        // 登录
        btnLogin.setOnClickListener(v -> mediaService.authenticate(requireActivity(), new CloudMediaService.AuthCallback() {
            @Override
            public void onSuccess() {
                mediaService.listMedia("/").observe(getViewLifecycleOwner(), items -> {
                    adapter.submitList(items);
                    textureView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                });
            }

            @Override
            public void onError(Throwable error) {
                Toast.makeText(requireContext(), "登录失败: " + error.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }));
        // 回根目录
        btnRoot.setOnClickListener(v -> mediaService.listMedia("/").observe(getViewLifecycleOwner(), adapter::submitList));
        // 列表点击
        adapter.setOnItemClickListener(item -> {
            switch (item.getType()) {
                case IMAGE:
                    exoPlayer.pause();
                    stopMediaPlayer();
                    textureView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    loadImageAsync(item.getUrl());
                    break;
                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    textureView.setVisibility(View.VISIBLE);
                    if (!isSurfaceReady) {
                        Toast.makeText(requireContext(), "视频加载中，请稍候…", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    stopMediaPlayer();
                    String url = item.getUrl();
                    if (url.contains("1drv.com")) {
                        url += (url.contains("?") ? "&" : "?") + "download=1";
                    }
                    Log.d(TAG, "Exo 播放直链: " + url);
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                    exoPlayer.prepare();
                    exoPlayer.play();
                    break;
                case FOLDER:
                    exoPlayer.pause();
                    stopMediaPlayer();
                    textureView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                    mediaService.listMedia(item.getName()).observe(getViewLifecycleOwner(), adapter::submitList);
                    break;
                default:
                    Toast.makeText(requireContext(), "无法播放该文件类型", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // Helper: 停止 MediaPlayer
    private void stopMediaPlayer() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            mediaPlayer.stop();
        }
    }

    // —— MediaPlayer & SurfaceTextureListener ——

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder().setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).setUsage(AudioAttributes.USAGE_MEDIA).build());
        mediaPlayer.setSurface(new Surface(surface));
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "onError: what=" + what + " extra=" + extra);
            Toast.makeText(requireContext(), "视频播放错误 code=" + what, Toast.LENGTH_LONG).show();
            return true;
        });
        mediaPlayer.setOnInfoListener((mp, what, extra) -> {
            Log.d(TAG, "onInfo: what=" + what + " extra=" + extra);
            return false;
        });
        mediaPlayer.setOnPreparedListener(MediaPlayer::start);
        isSurfaceReady = true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
    }

    @Override
    public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isSurfaceReady = false;
        return true;
    }

    @Override
    public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {
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
}
