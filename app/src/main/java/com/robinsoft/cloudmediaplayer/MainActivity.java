package com.robinsoft.cloudmediaplayer;

import android.os.Bundle;
import android.util.Log;
import android.widget.Button;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.robinsoft.cloudmediaplayer.adapter.MediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.ui.MediaViewModel;

public class MainActivity extends AppCompatActivity {
    private ExoPlayer player;
    private PlayerView playerView;
    private MediaViewModel viewModel;
    private Button btnLogin;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // 找控件
        btnLogin = findViewById(R.id.btnLogin);
        playerView = findViewById(R.id.playerView);
        RecyclerView rv = findViewById(R.id.recyclerView);

        // ExoPlayer 初始化
        player = new ExoPlayer.Builder(this).build();
        playerView.setPlayer(player);

        // RecyclerView
        rv.setLayoutManager(new LinearLayoutManager(this));
        MediaAdapter adapter = new MediaAdapter(item -> playUrl(item.getDownloadUrl()));
        rv.setAdapter(adapter);

        // ViewModel
        viewModel = new ViewModelProvider(this).get(MediaViewModel.class);

// 登录按钮
        btnLogin.setOnClickListener(v -> {
            // 验证点击事件是否生效
            Toast.makeText(this, "点击被捕捉到", Toast.LENGTH_SHORT).show();
            Log.d("MainActivity", "登录按钮被点击");

            viewModel.authenticate(MainActivity.this,new CloudMediaService.AuthCallback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this, "登录成功", Toast.LENGTH_SHORT).show()
                    );
                    viewModel.getMediaList("/drive/root:/Videos")
                            .observe(MainActivity.this, list -> {
                                if (list != null && !list.isEmpty()) {
                                    adapter.setData(list);
                                } else {
                                    Toast.makeText(MainActivity.this,
                                            "没有找到任何文件", Toast.LENGTH_SHORT).show();
                                }
                            });
                }
                @Override
                public void onError(Throwable t) {
                    Log.e("MainActivity", "登录失败回调", t);
                    runOnUiThread(() ->
                            Toast.makeText(MainActivity.this,
                                    "登录失败: " + t.getMessage(), Toast.LENGTH_LONG).show()
                    );
                }
            });
        });

    }

    private void playUrl(String url) {
        if (player == null) return;
        player.setMediaItem(MediaItem.fromUri(url));
        player.prepare();
        player.play();
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (player != null) player.pause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (player != null) {
            player.release();
            player = null;
        }
    }
}

