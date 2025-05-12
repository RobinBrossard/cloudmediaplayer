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
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.MediaItem;
import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.adapter.CloudMediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.net.HttpURLConnection;
import java.net.URL;

public class HomeFragment extends Fragment implements TextureView.SurfaceTextureListener {

    private static final String TAG = "MP_PLAYBACK";

    private TextureView textureView;
    private MediaPlayer mediaPlayer;
    private ExoPlayer exoPlayer;
    private boolean isSurfaceReady = false;

    private ImageView imageView;
    private RecyclerView recyclerView;
    private Button btnLogin,btnRoot;

    private final CloudMediaService mediaService = new OneDriveMediaService();

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // fragment_home.xml 内容与 activity_main.xml 一致，除去底部导航
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // 绑定 UI
        btnLogin     = view.findViewById(R.id.btnLogin);
        recyclerView = view.findViewById(R.id.recyclerView);
        textureView  = view.findViewById(R.id.texture_view);
        imageView    = view.findViewById(R.id.imageView);

        // SurfaceTextureListener 用于 MediaPlayer
        textureView.setSurfaceTextureListener(this);

        // 初始化 ExoPlayer
        exoPlayer = new ExoPlayer.Builder(requireContext()).build();
        exoPlayer.setVideoTextureView(textureView);
        isSurfaceReady = true;

        // RecyclerView + Adapter
        CloudMediaAdapter adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        // 登录 & 列表加载
        btnLogin.setOnClickListener(v ->
                mediaService.authenticate(requireActivity(), new CloudMediaService.AuthCallback() {
                    @Override
                    public void onSuccess() {
                        mediaService.listMedia("/").observe(getViewLifecycleOwner(),
                                items -> {
                                    adapter.submitList(items);
                                    textureView.setVisibility(View.GONE);
                                    imageView.setVisibility(View.GONE);
                                });
                    }
                    @Override
                    public void onError(Throwable error) {
                        Toast.makeText(requireContext(),
                                "登录失败: " + error.getMessage(),
                                Toast.LENGTH_SHORT).show();
                    }
                })
        );

        btnRoot = view.findViewById(R.id.btnRoot);
        btnRoot.setOnClickListener(v -> {
            // 回到根目录：重新加载 "/"
            mediaService.listMedia("/")
                    .observe(getViewLifecycleOwner(), items -> {
                        adapter.submitList(items);
                    });
        });


        // 列表点击
        adapter.setOnItemClickListener(item -> {
            switch (item.getType()) {
                case IMAGE:
                    exoPlayer.pause();
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.stop();
                    textureView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    loadImageAsync(item.getUrl());
                    break;

                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    textureView.setVisibility(View.VISIBLE);
                    if (!isSurfaceReady) {
                        Toast.makeText(requireContext(),
                                "视频加载中，请稍候…",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.stop();

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
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) mediaPlayer.stop();
                    textureView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                    mediaService.listMedia(item.getName())
                            .observe(getViewLifecycleOwner(), adapter::submitList);
                    break;

                default:
                    Toast.makeText(requireContext(),
                            "无法播放该文件类型",
                            Toast.LENGTH_SHORT).show();
            }
        });
    }

    // —— MediaPlayer & SurfaceTextureListener ——

    @Override
    public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build());
        mediaPlayer.setSurface(new Surface(surface));
        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "onError: what=" + what+" extra="+extra);
            Toast.makeText(requireContext(),
                    "视频播放错误 code=" + what,
                    Toast.LENGTH_LONG).show();
            return true;
        });
        mediaPlayer.setOnInfoListener((mp, what, extra) -> {
            Log.d(TAG, "onInfo: what=" + what+" extra="+extra);
            return false;
        });
        mediaPlayer.setOnPreparedListener(MediaPlayer::start);
        isSurfaceReady = true;
    }

    @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
    @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isSurfaceReady = false;
        return true;
    }
    @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}

    // —— 图片加载逻辑（同 MainActivity） ——
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

                int reqW = imageView.getWidth()>0 ? imageView.getWidth()
                        : Resources.getSystem().getDisplayMetrics().widthPixels;
                int reqH = imageView.getHeight()>0? imageView.getHeight()
                        : Resources.getSystem().getDisplayMetrics().heightPixels;
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
                requireActivity().runOnUiThread(() ->
                        Toast.makeText(requireContext(),
                                "加载图片失败", Toast.LENGTH_SHORT).show());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
    /**
     * 计算 Bitmap 采样率
     */
    private int calculateInSampleSize(
            BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width  = options.outWidth;
        int inSampleSize = 1;
        if (height > reqHeight || width > reqWidth) {
            final int halfH = height / 2;
            final int halfW = width  / 2;
            while ((halfH / inSampleSize) >= reqHeight
                    && (halfW / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

}
