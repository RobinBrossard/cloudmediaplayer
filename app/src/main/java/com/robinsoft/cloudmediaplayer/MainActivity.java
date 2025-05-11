package com.robinsoft.cloudmediaplayer;

import android.os.Bundle;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.Observer;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;

import com.robinsoft.cloudmediaplayer.adapter.CloudMediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.util.List;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private PlayerView playerView;
    private RecyclerView recyclerView;
    private CloudMediaService mediaService = new OneDriveMediaService();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnLogin = findViewById(R.id.btnLogin);
        recyclerView = findViewById(R.id.recyclerView);
        playerView = findViewById(R.id.playerView);

        // 初始化 ExoPlayer
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // RecyclerView + Adapter
        CloudMediaAdapter adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        recyclerView.setAdapter(adapter);

        // 登录并加载根目录媒体
        btnLogin.setOnClickListener(v -> {
            mediaService.authenticate(this, new CloudMediaService.AuthCallback() {
                @Override
                public void onSuccess() {
                    mediaService.listMedia("/")  // 取根目录
                            .observe(MainActivity.this, new Observer<List<CloudMediaItem>>() {
                                @Override
                                public void onChanged(List<CloudMediaItem> items) {
                                    adapter.submitList(items);
                                }
                            });
                }
                @Override
                public void onError(Throwable error) {
                    // TODO: 登录失败处理（Toast、日志等）
                }
            });
        });

        // 列表点击播放
        adapter.setOnItemClickListener(item -> {
            player.setMediaItem(MediaItem.fromUri(item.getUrl()));
            player.prepare();
            player.play();
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        player.release();
    }
}
