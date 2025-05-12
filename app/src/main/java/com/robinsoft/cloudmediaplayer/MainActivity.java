package com.robinsoft.cloudmediaplayer;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.robinsoft.cloudmediaplayer.adapter.CloudMediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;

public class MainActivity extends AppCompatActivity
        implements TextureView.SurfaceTextureListener {

    private static final String TAG = "MP_PLAYBACK";

    private TextureView textureView;
    private MediaPlayer mediaPlayer;              // 保留原来的
    private ExoPlayer exoPlayer;                  // 新增 ExoPlayer
    private boolean isSurfaceReady = false;

    private ImageView imageView;
    private RecyclerView recyclerView;
    private CloudMediaService mediaService = new OneDriveMediaService();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnLogin    = findViewById(R.id.btnLogin);
        recyclerView       = findViewById(R.id.recyclerView);
        textureView        = findViewById(R.id.texture_view);
        imageView          = findViewById(R.id.imageView);

        // 1. 监听 TextureView 准备（供 MediaPlayer 使用）
        textureView.setSurfaceTextureListener(this);

        // 2. 初始化 ExoPlayer，并把它绑定到同一个 TextureView
        exoPlayer = new ExoPlayer.Builder(this).build();
        exoPlayer.setVideoTextureView(textureView);
        isSurfaceReady = true;

        // 3. RecyclerView + Adapter
        CloudMediaAdapter adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 4. 登录并加载根目录媒体
        btnLogin.setOnClickListener(v -> {
            mediaService.authenticate(this, new CloudMediaService.AuthCallback() {
                @Override
                public void onSuccess() {
                    mediaService.listMedia("/")
                            .observe(MainActivity.this, items -> {
                                adapter.submitList(items);
                                textureView.setVisibility(View.GONE);
                                imageView.setVisibility(View.GONE);
                            });
                }
                @Override
                public void onError(Throwable error) {
                    Toast.makeText(MainActivity.this,
                            "登录失败: " + error.getMessage(),
                            Toast.LENGTH_SHORT).show();
                }
            });
        });

        // 5. 列表点击：IMAGE、VIDEO、FOLDER、其他
        adapter.setOnItemClickListener(item -> {
            switch (item.getType()) {
                case IMAGE:
                    // 停掉 ExoPlayer 和 MediaPlayer
                    exoPlayer.pause();
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    textureView.setVisibility(View.GONE);

                    imageView.setVisibility(View.VISIBLE);
                    loadImageAsync(item.getUrl());
                    break;

                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    textureView.setVisibility(View.VISIBLE);

                    if (!isSurfaceReady) {
                        Toast.makeText(this,
                                "视频加载中，请稍候…",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }

                    // 停掉 MediaPlayer
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }

                    // 构造直链（OneDrive 加 ?download=1）
                    String url = item.getUrl();
                    if (url.contains("1drv.com")) {
                        url = url + (url.contains("?") ? "&" : "?") + "download=1";
                    }
                    Log.d(TAG, "Exo 播放直链: " + url);

                    // 用 ExoPlayer 播放
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                    exoPlayer.prepare();
                    exoPlayer.play();
                    break;

                case FOLDER:
                    exoPlayer.pause();
                    if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                        mediaPlayer.stop();
                    }
                    textureView.setVisibility(View.GONE);
                    imageView.setVisibility(View.GONE);
                    mediaService.listMedia(item.getName())
                            .observe(MainActivity.this, adapter::submitList);
                    break;

                default:
                    Toast.makeText(this,
                            "无法播放该文件类型",
                            Toast.LENGTH_SHORT).show();
            }
        });
    }

    // —— 保留原有的 MediaPlayer 绑定 Surface 逻辑，不改动 ——

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
        mediaPlayer = new MediaPlayer();
        mediaPlayer.setAudioAttributes(
                new AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE)
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .build()
        );
        mediaPlayer.setSurface(new Surface(surface));
        isSurfaceReady = true;

        mediaPlayer.setOnErrorListener((mp, what, extra) -> {
            Log.e(TAG, "onError: what=" + what + " extra=" + extra);
            Toast.makeText(this,
                    "视频播放错误 (code=" + what + ")",
                    Toast.LENGTH_LONG).show();
            return true;
        });

        mediaPlayer.setOnInfoListener((mp, what, extra) -> {
            Log.d(TAG, "onInfo: what=" + what + " extra=" + extra);
            return false;
        });

        mediaPlayer.setOnPreparedListener(mp -> {
            Log.d(TAG, "onPrepared: start()");
            mp.start();
        });
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        isSurfaceReady = false;
        return true;
    }

    @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { }
    @Override public void onSurfaceTextureUpdated(SurfaceTexture s) { }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // 释放 ExoPlayer
        if (exoPlayer != null) {
            exoPlayer.release();
            exoPlayer = null;
        }
        // 释放 MediaPlayer
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) mediaPlayer.stop();
            mediaPlayer.reset();
            mediaPlayer.release();
            mediaPlayer = null;
        }
    }

    /** 图片异步加载（保持不变） **/
    private void loadImageAsync(String url) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL urlObj = new URL(url);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setDoInput(true);
                conn.connect();

                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inJustDecodeBounds = true;
                BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
                conn.disconnect();

                int reqW = imageView.getWidth() > 0
                        ? imageView.getWidth()
                        : Resources.getSystem().getDisplayMetrics().widthPixels;
                int reqH = imageView.getHeight() > 0
                        ? imageView.getHeight()
                        : Resources.getSystem().getDisplayMetrics().heightPixels;
                opts.inSampleSize = calculateInSampleSize(opts, reqW, reqH);
                opts.inJustDecodeBounds = false;

                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setDoInput(true);
                conn.connect();
                final Bitmap bmp = BitmapFactory.decodeStream(conn.getInputStream(), null, opts);
                conn.disconnect();

                runOnUiThread(() -> imageView.setImageBitmap(bmp));
            } catch (Exception e) {
                e.printStackTrace();
                runOnUiThread(() ->
                        Toast.makeText(MainActivity.this, "加载图片失败", Toast.LENGTH_SHORT).show()
                );
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /** 计算 Bitmap 采样率（保持不变） **/
    public static int calculateInSampleSize(
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
