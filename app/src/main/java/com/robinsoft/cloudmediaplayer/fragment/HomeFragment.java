package com.robinsoft.cloudmediaplayer.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.Target;
import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.adapter.CloudMediaAdapter;
import com.robinsoft.cloudmediaplayer.cloud.ApkDownloader;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.OneDriveMediaService;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@UnstableApi
public class HomeFragment extends Fragment {

    private static final String TAG = "MP_PLAYBACK";
    // 2. REQUEST_STORAGE_PERMISSION_FOR_APK 常量不再需要，可以注释或删除
    // private static final int REQUEST_STORAGE_PERMISSION_FOR_APK = 123;
    private final CloudMediaService mediaService = new OneDriveMediaService();
    private DefaultTrackSelector trackSelector;
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    // --- MODIFICATION START: ImageView changes for double buffering ---
    private ImageView imageView1; // Was 'imageView'
    private ImageView imageView2;
    private ImageView activeImageView; // Tracks the currently visible ImageView
    // --- MODIFICATION END ---
    private RecyclerView recyclerView;
    private Button btnLogin, btnRoot, btnGo;
    private boolean isFullscreen = false;
    private ConstraintLayout.LayoutParams originalBottomContainerLayoutParams;
    private View topContainer;
    private View bottomContainer;
    private String currentDirId;
    //    private String autoRootId; 这个参数无用，逻辑有问题，去掉
    private Deque<String> autoPlayStack = new ArrayDeque<>();
    private boolean isAutoPlaying = false;
    private Runnable autoPlayImageRunnable;
    private Player.Listener exoPlayerListener;
    private List<CloudMediaItem> currentAutoPlayFiles;
    private int currentAutoPlayIndex;
    private Runnable currentAutoPlayOnAllDone;
    private CloudMediaAdapter adapter;
    private ApkDownloader apkDownloader;
    private String pendingApkUrl;
    private String pendingApkFileName;
    //   private ProgressBar downloadProgressBar;
    //   private TextView downloadProgressText;

    private Handler mainThreadHandler;
    private long autoPlayImageDisplayDurationMs = 5000L;

    // 3. 声明 ActivityResultLauncher
    private ActivityResultLauncher<String> requestPermissionLauncher;


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        apkDownloader = ApkDownloader.getInstance(context.getApplicationContext());
        mainThreadHandler = new Handler(Looper.getMainLooper());
    }

    // 4. 在 onCreate 中初始化 requestPermissionLauncher
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    // 这里的逻辑完全来自于您原来 onRequestPermissionsResult 中对应的部分
                    if (isGranted) {
                        if (isAdded() && getContext() != null) {
                            Toast.makeText(getContext(), "存储权限已授予，开始下载...", Toast.LENGTH_SHORT).show();
                        }
                        // pendingApkUrl 和 pendingApkFileName 是成员变量，可以直接访问
                        if (pendingApkUrl != null && pendingApkFileName != null) {
                            proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
                        } else {
                            Log.w(TAG, "权限授予后，待下载的APK信息丢失");
                            if (isAdded() && getContext() != null) {
                                Toast.makeText(getContext(), "无法继续下载，信息丢失", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else {
                        if (isAdded() && getContext() != null) {
                            Toast.makeText(getContext(), "存储权限被拒绝，无法下载APK", Toast.LENGTH_LONG).show();
                        }
                        appendLog("权限被拒绝"); // 保留您原有的 appendLog 调用
                    }
                    // 清理临时变量，与您原来 onRequestPermissionsResult 中的逻辑保持一致
                    pendingApkUrl = null;
                    pendingApkFileName = null;
                });
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
        TestFragment logFrag = null;
        try {
            logFrag = (TestFragment) getActivity()
                    .getSupportFragmentManager()
                    .findFragmentByTag("test_fr");
        } catch (ClassCastException e) {
            Log.e(TAG, "TestFragment tag lookup failed or fragment is of wrong type.", e);
        }

        if (logFrag != null) {
            String ts = logFrag.getTimestamp();
            logFrag.appendLog(ts + s);
            Log.d(TAG, s);
        } else {
            Log.w(TAG, "TestFragment not found, logging to console only: " + s);
        }
    }

    private void refreshDirectory() {
        if (getViewLifecycleOwner() == null || adapter == null || !isAdded()) {
            Log.w(TAG, "refreshDirectory called when view, adapter, or fragment not ready.");
            return;
        }
        mediaService.listMedia(currentDirId)
                .observe(getViewLifecycleOwner(), items -> {
                    if (isAdded() && adapter != null) {
                        adapter.submitList(items);
                    }
                });
    }

    private String prepareMediaUrl(String originalUrl) {
        if (originalUrl != null && originalUrl.contains("1drv.com")) {
            return originalUrl + (originalUrl.contains("?") ? "&" : "?") + "download=1";
        }
        return originalUrl;
    }

    public void setAutoPlayImageDisplayDuration(long durationMs) {
        if (durationMs > 0) {
            this.autoPlayImageDisplayDurationMs = durationMs;
            Log.d(TAG, "Auto-play image display duration set to: " + durationMs + "ms");
        } else {
            Log.w(TAG, "Attempted to set invalid auto-play image display duration: " + durationMs + "ms");
        }
    }


    @UnstableApi
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnLogin = view.findViewById(R.id.btnLogin);
        btnRoot = view.findViewById(R.id.btnRoot);
        btnGo = view.findViewById(R.id.btnGo);
        recyclerView = view.findViewById(R.id.recyclerView);
        // --- MODIFICATION START: Initialize both ImageViews ---
        imageView1 = view.findViewById(R.id.imageView1); // Ensure this ID exists in your XML
        imageView2 = view.findViewById(R.id.imageView2); // Ensure this ID exists in your XML

        if (imageView1 == null || imageView2 == null) {
            Log.e(TAG, "One or both ImageViews not found in layout! Check R.id.imageView1 and R.id.imageView2.");
            // Handle error appropriately, maybe show a toast or disable image features
            Toast.makeText(getContext(), "Image views not found, image features disabled.", Toast.LENGTH_LONG).show();
            // You might want to return or disable parts of the UI if ImageViews are critical
        } else {
            activeImageView = imageView1; // imageView1 is initially active
            imageView1.setVisibility(View.VISIBLE);
            imageView2.setVisibility(View.GONE);
        }
        // --- MODIFICATION END ---
        playerView = view.findViewById(R.id.player_view);
        topContainer = view.findViewById(R.id.top_container);
        bottomContainer = view.findViewById(R.id.bottom_container);

        if (bottomContainer != null) {
            originalBottomContainerLayoutParams = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            Log.d(TAG, "onViewCreated: Captured originalBottomContainerLayoutParams.");
        } else {
            Log.e(TAG, "onViewCreated: Fragment's R.id.bottom_container not found!");
        }

        trackSelector = new DefaultTrackSelector(requireContext());
        DefaultRenderersFactory renderersFactory =
                new DefaultRenderersFactory(requireContext())
                        .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
                        .setEnableDecoderFallback(true);
        exoPlayer = new ExoPlayer.Builder(requireContext(), renderersFactory)
                .setTrackSelector(trackSelector)
                .build();

        exoPlayerListener = new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int playbackState) {
                if (playbackState == Player.STATE_ENDED && isAutoPlaying) {
                    Log.d(TAG, "AutoPlay: Video ended, trying next file.");
                    handleAutoPlayNextFile();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                Log.e(TAG, "ExoPlayer Error: " + error.getMessage(), error);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "播放错误: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
                appendLog("播放错误: " + error.getMessage());
                if (isAutoPlaying) {
                    Log.w(TAG, "AutoPlay: Error during video playback. Attempting to skip to next file.");
                    handleAutoPlayNextFile();
                }
            }
        };
        exoPlayer.addListener(exoPlayerListener);
        playerView.setPlayer(exoPlayer);
        playerView.setControllerShowTimeoutMs(3000);

        adapter = new CloudMediaAdapter();
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        adapter.setOnItemClickListener(item -> {
            if (!isAdded() || getContext() == null) return;
            if (imageView1 == null || imageView2 == null) { // Safety check
                Log.e(TAG, "ImageViews not initialized, cannot process click.");
                return;
            }


            String url = prepareMediaUrl(item.getUrl());

            switch (item.getType()) {
                case FOLDER:
                    if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                    currentDirId = item.getId();
                    refreshDirectory();
                    break;
                case IMAGE:
                    if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    // --- MODIFICATION START: Manual image click uses double buffering too ---
                    // Determine target for manual click (the non-active one, then swap)
                    ImageView targetManualImageView = (activeImageView == imageView1) ? imageView2 : imageView1;
                    if (autoPlayImageRunnable != null && mainThreadHandler != null) { // Cancel any auto-play timer
                        mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                    }
                    loadImage(url, true, false, targetManualImageView); // false for autoPlayTimer
                    // --- MODIFICATION END ---
                    break;
                case VIDEO:
                    // --- MODIFICATION START: Ensure ImageViews are hidden when video plays ---
                    if (imageView1 != null) imageView1.setVisibility(View.GONE);
                    if (imageView2 != null) imageView2.setVisibility(View.GONE);
                    // --- MODIFICATION END ---
                    playerView.setVisibility(View.VISIBLE);
                    if (exoPlayer != null) {
                        exoPlayer.stop();
                        exoPlayer.clearMediaItems();
                        exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                        exoPlayer.prepare();
                        exoPlayer.play();
                    }
                    break;
                case APK:
                    Toast.makeText(getContext(), "准备下载并安装：" + item.getName(), Toast.LENGTH_SHORT).show();
                    checkStoragePermissionAndDownloadApk(url, item.getName());
                    break;
                default:
                    Toast.makeText(getContext(), "无法播放该文件类型", Toast.LENGTH_SHORT).show();
            }
        });

        btnLogin.setOnClickListener(v -> mediaService.authenticate(requireActivity(), new CloudMediaService.AuthCallback() {
            @Override
            public void onSuccess() {
                currentDirId = null;
                refreshDirectory();
            }

            @Override
            public void onError(Throwable error) {
                Log.e(TAG, "Login failed", error);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "登录失败: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }));

        btnRoot.setOnClickListener(v -> {
            currentDirId = null;
            refreshDirectory();
        });
/*
currentDirId用于记录用户当前所在的网盘目录，其中，如果是根目录，则currentDirId=null。currentDirId在三处会发生变动，
登录网盘时，currentDirId=null，代表在根目录；点击回到根目录按钮时，currentDirId=null；以及用户点击recyclerView里面的item，
类型是目录时，将其设置为被点击的目录，且根据该子目录刷新recyclerView的内容。然后，当自动播放时，应该从currentDirId开始播放，
先播放currentDirId的所有文件，然后将其子目录全部入栈，逐一遍历和播放子目录。当栈为空时，代表完成了所有currentDirId下子目录的播放，
那么我们应该重新开始播放。
 */
        btnGo.setOnClickListener(v -> {

            RecyclerView.Adapter<?> adapterLocal = recyclerView.getAdapter(); // 使用您原来的变量名 adapter
            if (adapterLocal != null && adapterLocal.getItemCount() > 0) {
                // RecyclerView 有内容，正常放行
                Log.d("RecyclerViewCheck", "RecyclerView has content. Item count: " + adapterLocal.getItemCount());
            } else {
                // RecyclerView 为空，或者没有设置 Adapter
                if (adapterLocal == null) {
                    Log.d("RecyclerViewCheck", "RecyclerView has no adapter set.");
                } else {
                    Log.d("RecyclerViewCheck", "RecyclerView is empty. Item count: 0");
                }
                Toast.makeText(getContext(), "未登录网盘或网盘无内容", Toast.LENGTH_SHORT).show();
                return; //说明还没有打开网盘链接，直接返回
            }

//            if (currentDirId == null) return;  //可以是null，null代表是根目录
            if (!isAutoPlaying) { //点了按钮，从非自动播放开始自动播放
                //autoRootId = currentDirId;
                autoPlayStack.clear();
//                if (autoRootId != null) {
//                    autoPlayStack.push(autoRootId);
//                }
                if (currentDirId != null)
                    autoPlayStack.push(currentDirId);
                else {
                    //代表当前在根目录
                }
                isAutoPlaying = true;
                btnGo.setText("停止");
                enterFullscreen();
                startAutoPlay();
            } else {
                //点了按钮，从自动播放关闭
                //实际上不大可能来这个分支。如果自动播放了，看不到按钮的
                exitFullscreen();
                stopAutoPlay();
                btnGo.setText("播放");
                refreshDirectory();
            }
        });

        View.OnClickListener mediaViewClickListener = v -> {
            if (isAutoPlaying) { //正在自动播放
                exitFullscreen();
                stopAutoPlay();
                btnGo.setText("播放");
                refreshDirectory();
            } else {  //非自动播放，手动查看时候，全屏非全屏切换
                toggleFullscreen();
            }
        };
        playerView.setOnClickListener(mediaViewClickListener);
        // --- MODIFICATION START: Attach click listener to both ImageViews ---
        if (imageView1 != null) imageView1.setOnClickListener(mediaViewClickListener);
        if (imageView2 != null) imageView2.setOnClickListener(mediaViewClickListener);
        // --- MODIFICATION END ---
    }

    private void startAutoPlay() {
        if (!isAutoPlaying || !isAdded()) {
            Log.d(TAG, "startAutoPlay: Bailing out, isAutoPlaying=" + isAutoPlaying + ", isAdded=" + isAdded());
            return;
        }

        String dirIdToPlay;
        if (autoPlayStack.isEmpty()) {
            // 栈为空，意味着当前播放范围的遍历已完成一轮，或者首次从根目录启动。
            // 使用 this.currentDirId (Fragment 成员变量，代表用户启动播放时的目录) 作为播放/重新播放的起点。
            dirIdToPlay = this.currentDirId;
            Log.d(TAG, "AutoPlay: Stack empty. Starting/Restarting playback from scope: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay));
        } else {
            dirIdToPlay = autoPlayStack.pop();
            Log.d(TAG, "AutoPlay: Popped '" + dirIdToPlay + "' from stack. Stack size now: " + autoPlayStack.size());
        }

        if (getViewLifecycleOwner() == null) {
            Log.w(TAG, "AutoPlay: View lifecycle owner not available. Stopping auto-play.");
            stopAutoPlay(); // 确保停止，并可能重置UI
            if (isAdded() && btnGo != null) btnGo.setText("播放");
            if (isFullscreen) exitFullscreen();
            return;
        }

        Log.d(TAG, "AutoPlay: Listing media for dirId: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay));
        mediaService.listMedia(dirIdToPlay)
                .observe(getViewLifecycleOwner(), items -> {
                    if (!isAutoPlaying || !isAdded()) { // 再次检查状态，因为这是异步回调
                        Log.d(TAG, "AutoPlay: listMedia observed, but state changed. isAutoPlaying=" + isAutoPlaying + ", isAdded=" + isAdded());
                        return;
                    }
                    if (items == null) {
                        Log.w(TAG, "AutoPlay: listMedia returned null items for dirId: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay) + ". Attempting next from stack or restarting scope.");
                        // 如果获取列表失败，直接尝试处理栈中下一个或重新开始当前范围
                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(this::startAutoPlay);
                        }
                        return;
                    }

                    // 当 items 不为 null，但在一个循环的开始，如果 dirIdToPlay (即 this.currentDirId) 本身就是空的，
                    // 并且 items 也为空（例如整个网盘是空的），我们需要一个终止条件。
                    if (dirIdToPlay == null && items.isEmpty() && autoPlayStack.isEmpty()) {
                        Log.i(TAG, "AutoPlay: Root directory is empty and stack is empty. No content to play.");
                        if (isAdded() && getContext() != null) {
                            Toast.makeText(getContext(), "自动播放完成：未找到任何可播放内容。", Toast.LENGTH_LONG).show();
                        }
                        exitFullscreen();
                        stopAutoPlay();
                        btnGo.setText("播放");
                        return;
                    }

                    new Thread(() -> {
                        final List<String> localChildDirs = new ArrayList<>();
                        final List<CloudMediaItem> localFilesToPlay = new ArrayList<>();

                        for (CloudMediaItem it : items) {
                            if (it.getType() == CloudMediaItem.MediaType.FOLDER) {
                                localChildDirs.add(it.getId());
                            } else if (it.getType() == CloudMediaItem.MediaType.IMAGE || it.getType() == CloudMediaItem.MediaType.VIDEO) {
                                localFilesToPlay.add(it);
                            }
                        }

                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(() -> {
                                if (!isAutoPlaying || !isAdded()) { // 再次检查
                                    return;
                                }
                                // 将子目录反向压入栈，以实现期望的遍历顺序
                                for (int i = localChildDirs.size() - 1; i >= 0; i--) {
                                    autoPlayStack.push(localChildDirs.get(i));
                                }
                                Log.d(TAG, "AutoPlay: Pushed " + localChildDirs.size() + " child dirs. Stack size now: " + autoPlayStack.size());

                                // 检查在处理完当前目录后是否应该结束整个自动播放
                                // （例如，当前目录没文件，子目录也处理完了，栈也空了）
                                if (localFilesToPlay.isEmpty() && autoPlayStack.isEmpty()) {
                                    // 这种情况意味着 dirIdToPlay (可能是 this.currentDirId) 没有可播放文件，
                                    // 也没有子目录被加入栈（或者子目录处理完了栈又空了）。
                                    // 这实际上是一个循环点，或者如果 this.currentDirId 本身就没有内容，
                                    // 那么就是整个范围都没有内容。
                                    // startAutoPlay 下次被调用时，会因为栈空而重新使用 this.currentDirId。
                                    // 如果 this.currentDirId 本身就是个空目录且无子目录，会无限循环尝试播放它。
                                    // 所以，如果 localFilesToPlay 为空，并且处理完子目录后 autoPlayStack 也为空，
                                    // 并且 dirIdToPlay 就是 this.currentDirId（意味着我们刚开始一个循环或初始扫描根范围）
                                    // 且这个 this.currentDirId 确实没有内容，那么就应该停止。

                                    // 上面已经有对 items.isEmpty() && autoPlayStack.isEmpty() 的判断，
                                    // 这里的逻辑是确保 playFilesSequentially 被正确调用或跳过。
                                    if (this.currentDirId == dirIdToPlay && items.isEmpty() && localChildDirs.isEmpty()) {
                                        Log.i(TAG, "AutoPlay: Scope root " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay) + " is empty (no files, no subdirs). Stopping.");
                                        if (isAdded() && getContext() != null) {
                                            Toast.makeText(getContext(), "自动播放范围为空，已停止。", Toast.LENGTH_LONG).show();
                                        }
                                        exitFullscreen();
                                        stopAutoPlay();
                                        btnGo.setText("播放");
                                        return;
                                    }
                                }
                                playFilesSequentially(localFilesToPlay, this::startAutoPlay);
                            });
                        }
                    }).start();
                });
    }

    private void handleAutoPlayNextFile() {
        if (!isAutoPlaying || !isAdded()) return;

        if (autoPlayImageRunnable != null && mainThreadHandler != null) {
            Log.d(TAG, "handleAutoPlayNextFile: Removing existing autoPlayImageRunnable before advancing.");
            mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
        }

        if (currentAutoPlayFiles == null || currentAutoPlayOnAllDone == null) {
            Log.d(TAG, "AutoPlay: handleAutoPlayNextFile called but no current auto-play sequence info. Attempting to process next directory.");
            startAutoPlay();
            return;
        }

        currentAutoPlayIndex++;
        if (currentAutoPlayIndex < currentAutoPlayFiles.size()) {
            Log.d(TAG, "AutoPlay: Playing next file in sequence, index: " + currentAutoPlayIndex);
            playFileAtIndex(currentAutoPlayFiles, currentAutoPlayIndex, currentAutoPlayOnAllDone);
        } else {
            Log.d(TAG, "AutoPlay: All files in current directory played.");
            Runnable tempOnAllDone = currentAutoPlayOnAllDone;
            currentAutoPlayFiles = null;
            currentAutoPlayIndex = -1;
            currentAutoPlayOnAllDone = null;
            if (tempOnAllDone != null) {
                tempOnAllDone.run();
            } else {
                startAutoPlay();
            }
        }
    }

    private void playFilesSequentially(List<CloudMediaItem> files, Runnable onAllDone) {
        if (!isAutoPlaying || !isAdded()) {
            Log.d(TAG, "AutoPlay: playFilesSequentially called but auto-play is off or fragment not added.");
            return;
        }
        this.currentAutoPlayFiles = new ArrayList<>(files);
        this.currentAutoPlayIndex = -1;
        this.currentAutoPlayOnAllDone = onAllDone;

        Log.d(TAG, "AutoPlay: Starting sequential play for " + files.size() + " files.");
        if (files.isEmpty()) {
            Log.d(TAG, "AutoPlay: No files to play in this directory. Proceeding to next task.");
            Runnable tempOnAllDone = this.currentAutoPlayOnAllDone;
            this.currentAutoPlayFiles = null;
            this.currentAutoPlayIndex = -1;
            this.currentAutoPlayOnAllDone = null;
            if (tempOnAllDone != null) {
                tempOnAllDone.run();  //这里又回自动播放那里，和下面没区别啊？
            } else {
                startAutoPlay();
            }
        } else {
            handleAutoPlayNextFile();
        }
    }

    private void playFileAtIndex(List<CloudMediaItem> files, int idx, Runnable onAllDone) {
        if (!isAutoPlaying || !isAdded()) {
            Log.d(TAG, "AutoPlay: playFileAtIndex called but auto-play is off or fragment not added.");
            return;
        }
        if (files == null || idx >= files.size() || idx < 0) {
            Log.w(TAG, "AutoPlay: Invalid files list or index in playFileAtIndex. Index: " + idx + ", Files size: " + (files == null ? "null" : files.size()));
            if (onAllDone != null) {
                onAllDone.run();
            } else {
                startAutoPlay();
            }
            return;
        }
        if (imageView1 == null || imageView2 == null) { // Safety check for ImageViews
            Log.e(TAG, "AutoPlay: ImageViews not initialized in playFileAtIndex.");
            stopAutoPlay(); // Stop if UI is not ready
            return;
        }

        this.currentAutoPlayFiles = files;
        this.currentAutoPlayIndex = idx;
        this.currentAutoPlayOnAllDone = onAllDone;

        CloudMediaItem item = files.get(idx);
        String url = prepareMediaUrl(item.getUrl());

        Log.d(TAG, "AutoPlay: Playing file " + item.getName() + " at index " + idx);

        if (autoPlayImageRunnable != null && mainThreadHandler != null) {
            Log.d(TAG, "playFileAtIndex: Removing previous autoPlayImageRunnable.");
            mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
        }

        preloadNextAutoPlayImageIfApplicable();

        if (item.getType() == CloudMediaItem.MediaType.IMAGE) {
            playerView.setVisibility(View.GONE);
            // --- MODIFICATION START: Determine target ImageView for buffering ---
            ImageView targetImageView = (activeImageView == imageView1) ? imageView2 : imageView1;
            Log.d(TAG, "AutoPlay: Loading image into " + (targetImageView == imageView1 ? "imageView1" : "imageView2"));
            // Ensure the target (back) image view is ready to receive image, but might be GONE initially
            // Glide will handle making it visible in onResourceReady if it loads successfully.
            // The activeImageView remains visible with the old image.
            loadImage(url, false, true, targetImageView);
            // --- MODIFICATION END ---

        } else if (item.getType() == CloudMediaItem.MediaType.VIDEO) {
            // --- MODIFICATION START: Hide both ImageViews when video plays ---
            if (imageView1 != null) imageView1.setVisibility(View.GONE);
            if (imageView2 != null) imageView2.setVisibility(View.GONE);
            // --- MODIFICATION END ---
            if (playerView != null) playerView.setVisibility(View.VISIBLE);
            if (exoPlayer != null) {
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                exoPlayer.prepare();
                exoPlayer.play();
            }
        } else {
            Log.w(TAG, "AutoPlay: Encountered unexpected file type: " + item.getType() + ". Skipping.");
            handleAutoPlayNextFile();
        }
    }

    private void preloadNextAutoPlayImageIfApplicable() {
        if (!isAutoPlaying || !isAdded() || currentAutoPlayFiles == null || currentAutoPlayFiles.isEmpty()) {
            return;
        }

        int preloadNextIndex = currentAutoPlayIndex + 1;
        if (preloadNextIndex < currentAutoPlayFiles.size()) {
            CloudMediaItem nextItem = currentAutoPlayFiles.get(preloadNextIndex);
            if (nextItem.getType() == CloudMediaItem.MediaType.IMAGE) {
                Context safeContext = getContext();
                if (safeContext == null) return;
                Log.d(TAG, "AutoPlay: Preloading next image: " + nextItem.getName());
                Glide.with(safeContext)
                        .load(prepareMediaUrl(nextItem.getUrl()))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .preload();
            }
        }
    }


    private void stopAutoPlay() {
        Log.i(TAG, "AutoPlay: Stopping auto-play sequence.");
        isAutoPlaying = false;
        autoPlayStack.clear();
        if (exoPlayer != null && exoPlayer.isPlaying()) {
            exoPlayer.stop();
            exoPlayer.clearMediaItems();
        }
        if (autoPlayImageRunnable != null) {
            if (mainThreadHandler != null) {
                mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
            }
        }
        autoPlayImageRunnable = null;
        currentAutoPlayFiles = null;
        currentAutoPlayIndex = -1;
        currentAutoPlayOnAllDone = null;
    }

    // loadImage 方法保持您提供的版本，不进行任何改动
    private void loadImage(String url, boolean loadForPotentialFullscreen, final boolean isForAutoPlayTimer, final ImageView targetImageView) {
        if (!isAdded() || getContext() == null || targetImageView == null) {
            Log.w(TAG, "loadImage: Fragment not ready, ImageView is null, or targetImageView is null.");
            if (isForAutoPlayTimer && isAutoPlaying && isAdded() && mainThreadHandler != null) {
                Log.w(TAG, "loadImage: Pre-condition failed for auto-play image, trying next.");
                mainThreadHandler.post(this::handleAutoPlayNextFile);
            }
            return;
        }
        final String finalUrl = prepareMediaUrl(url);

        if (isForAutoPlayTimer && autoPlayImageRunnable != null && mainThreadHandler != null) {
            // This might be redundant if playFileAtIndex always cancels, but good as a safeguard.
            // mainThreadHandler.removeCallbacks(autoPlayImageRunnable); // 保持您原有代码的注释状态
        }

        RequestListener<Drawable> glideListener = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                Log.e(TAG, "Glide: Failed to load image into " + (targetImageView == imageView1 ? "imageView1" : "imageView2") + " URL: " + finalUrl, e);
                if (isAdded() && getContext() != null) {
                    if (isForAutoPlayTimer && isAutoPlaying && isAdded()) {
                        Log.w(TAG, "AutoPlay: Image load failed for " + finalUrl + ", attempting to play next file.");
                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(() -> {
                                if (isAutoPlaying && isAdded()) {
                                    handleAutoPlayNextFile();
                                }
                            });
                        }
                    }
                }
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                Log.d(TAG, "Glide: Image resource ready for " + (targetImageView == imageView1 ? "imageView1" : "imageView2") + " URL: " + finalUrl);
                if (targetImageView != null && isAdded()) {

                    if (playerView != null)
                        playerView.setVisibility(View.GONE);

                    ImageView previouslyActiveImageView = activeImageView;
                    activeImageView = targetImageView;

                    activeImageView.setVisibility(View.VISIBLE);
                    activeImageView.setAlpha(0f);
                    activeImageView.animate().alpha(1f).setDuration(300).start();

                    if (previouslyActiveImageView != null && previouslyActiveImageView != activeImageView) {
                        previouslyActiveImageView.animate().alpha(0f).setDuration(300).withEndAction(() -> {
                            if (previouslyActiveImageView != activeImageView) {
                                previouslyActiveImageView.setVisibility(View.GONE);
                            }
                        }).start();
                    } else if (previouslyActiveImageView == activeImageView && imageView1 != null && imageView2 != null) {
                        ImageView otherImageView = (activeImageView == imageView1) ? imageView2 : imageView1;
                        otherImageView.setVisibility(View.GONE);
                        otherImageView.setAlpha(0f);
                    }

                    if (isForAutoPlayTimer && isAutoPlaying) {
                        Log.d(TAG, "AutoPlay: Image resource ready, starting " + autoPlayImageDisplayDurationMs + "ms timer for " + finalUrl);
                        autoPlayImageRunnable = () -> {
                            if (isAutoPlaying && isAdded()) {
                                Log.d(TAG, "AutoPlay: " + autoPlayImageDisplayDurationMs + "ms timer expired for " + finalUrl + ", handling next file.");
                                handleAutoPlayNextFile();
                            }
                        };

                        if (mainThreadHandler != null) {
                            mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                            mainThreadHandler.postDelayed(autoPlayImageRunnable, autoPlayImageDisplayDurationMs);
                        }
                    }
                }
                return false;
            }
        };

        // Determine which Glide call to use based on whether it's a manual click (loadForPotentialFullscreen)
        // or an auto-play background load.
        if (loadForPotentialFullscreen && !isForAutoPlayTimer) { // Manual click, potentially for fullscreen
            DisplayMetrics displayMetrics = new DisplayMetrics();
            Activity activity = getActivity();
            if (activity != null) {
                activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenWidth = displayMetrics.widthPixels;
                int screenHeight = displayMetrics.heightPixels;
                Log.d(TAG, "Glide: Loading image for potential fullscreen into " + (targetImageView == imageView1 ? "imageView1" : "imageView2") + " URL: " + finalUrl);
                Glide.with(this)
                        .load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        // .transition(DrawableTransitionOptions.withCrossFade(200)) // Transition handled by alpha animation now
                        .override(screenWidth, screenHeight)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .listener(glideListener)
                        .into(targetImageView); // Load into the specified target
            } else {
                Log.w(TAG, "Activity not available for getting screen dimensions. Loading with default Glide sizing into " + (targetImageView == imageView1 ? "imageView1" : "imageView2"));
                Glide.with(this).load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        // .transition(DrawableTransitionOptions.withCrossFade(200))
                        .fitCenter().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).listener(glideListener).into(targetImageView);
            }
        } else { // Auto-play image load, or non-fullscreen initial load.
            Log.d(TAG, "Glide: Loading image for AUTO-PLAY path.");
            if (getActivity() != null && isFullscreen) { // 假设自动播放主要在全屏时，或者根据需要调整条件
                DisplayMetrics displayMetrics = new DisplayMetrics();
                getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenWidth = displayMetrics.widthPixels;
                int screenHeight = displayMetrics.heightPixels;
                Log.d(TAG, "Glide (Auto-Play with Override): Loading " + finalUrl);
                Glide.with(this)
                        .load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .override(screenWidth, screenHeight)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE) // 与手动一致
                        .listener(glideListener)
                        .into(targetImageView);
            } else { // Fallback or non-fullscreen auto-play
                Log.d(TAG, "Glide (Auto-Play Original): Loading " + finalUrl);
                Glide.with(this)
                        .load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .listener(glideListener)
                        .into(targetImageView);
            }
        }
    }

    // enterFullscreen 和 exitFullscreen 方法保持您提供的版本
    private void enterFullscreen() {
        if (isFullscreen) return;
        Activity activity = getActivity();
        if (activity == null || !isAdded()) return;
        isFullscreen = true;

        View activityBottomNav = activity.findViewById(R.id.bottom_nav);
        if (activityBottomNav != null) activityBottomNav.setVisibility(View.GONE);

        if (topContainer != null) topContainer.setVisibility(View.GONE);
        if (bottomContainer != null) {
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.width = ConstraintLayout.LayoutParams.MATCH_PARENT;
            lp.height = ConstraintLayout.LayoutParams.MATCH_PARENT;
            bottomContainer.setLayoutParams(lp);
        }
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;
        Activity activity = getActivity();
        if (activity == null || !isAdded()) return;
        isFullscreen = false;

        if (topContainer != null) {
            topContainer.setVisibility(View.VISIBLE);
        }

        if (bottomContainer != null) {
            Log.d(TAG, "exitFullscreen: Applying user's specified non-fullscreen constraints.");
            // 确保使用您原来代码中的这部分逻辑，而不是我之前修改的 originalBottomContainerLayoutParams
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            lp.topToTop = R.id.guideline_half; // 确保此 ID 在您的布局中有效
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.width = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT;
            lp.height = ConstraintLayout.LayoutParams.MATCH_CONSTRAINT;
            bottomContainer.setLayoutParams(lp);
            bottomContainer.requestLayout();
        }

        View activityBottomNav = activity.findViewById(R.id.bottom_nav);
        if (activityBottomNav != null) {
            activityBottomNav.setVisibility(View.VISIBLE);
        }
    }

    private void toggleFullscreen() {
        if (isFullscreen) exitFullscreen();
        else enterFullscreen();
    }


    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView called");

        if (isAutoPlaying) {
            stopAutoPlay();
        } else {
            if (autoPlayImageRunnable != null) {
                if (mainThreadHandler != null) {
                    mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                }
                autoPlayImageRunnable = null;
            }
        }

        if (exoPlayer != null) {
            if (exoPlayerListener != null) {
                exoPlayer.removeListener(exoPlayerListener);
            }
            exoPlayer.release();
            exoPlayer = null;
        }

        if (mainThreadHandler != null) {
            mainThreadHandler.removeCallbacksAndMessages(null);
        }

        playerView = null;
        imageView1 = null;
        imageView2 = null;
        activeImageView = null;
        recyclerView = null;
        btnLogin = null;
        btnRoot = null;
        btnGo = null;
        trackSelector = null;
        adapter = null;
        exoPlayerListener = null;
        currentAutoPlayFiles = null;
        currentAutoPlayIndex = -1;
        currentAutoPlayOnAllDone = null;
        topContainer = null;
        bottomContainer = null;
        originalBottomContainerLayoutParams = null;
    }

    @Override
    public void onDetach() {
        super.onDetach();
        if (mainThreadHandler != null) {
            mainThreadHandler.removeCallbacksAndMessages(null);
            mainThreadHandler = null;
        }
    }

    // 5. 修改 checkStoragePermissionAndDownloadApk 以使用新的 launcher
    private void checkStoragePermissionAndDownloadApk(String url, String fileName) {
        this.pendingApkUrl = url; // 保存 url 和 fileName，因为权限请求是异步的
        this.pendingApkFileName = fileName;

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                // 使用新的启动器请求权限
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                // 权限已授予，直接下载
                proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
                // 清理 pending 变量
                this.pendingApkUrl = null;
                this.pendingApkFileName = null;
            }
        } else {
            // 对于 Android Q (API 29) 及更高版本
            proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
            // 清理 pending 变量
            this.pendingApkUrl = null;
            this.pendingApkFileName = null;
        }
    }

    private void proceedWithApkDownload(String url, String fileName) {
        if (!isAdded() || getActivity() == null) {
            Log.w(TAG, "proceedWithApkDownload: Fragment not added or activity is null.");
            appendLog("proceedWithApkDownload: Fragment not added or activity is null.");
            return;
        }

        if (apkDownloader != null) {
            appendLog("准备下载..."); // 保留您原有的 appendLog
            apkDownloader.downloadAndInstallApk(url, fileName, getActivity(), new ApkDownloader.Callback() {
                @Override
                public void onSuccess(File apkFile) {
                    Log.d(TAG, "APK Downloaded Successfully. File: " + apkFile.getAbsolutePath());
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "APK 下载成功，准备安装", Toast.LENGTH_SHORT).show();
                        appendLog("APK 下载成功，准备安装");
                    }
                }

                @Override
                public void onFailure(String errorMessage) {
                    Log.e(TAG, "APK Download Failed: " + errorMessage);
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "下载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                        appendLog("下载失败");
                    }
                }

                @Override
                public void onProgress(int progressPercentage) {
                    Log.d(TAG, "APK Download Progress: " + progressPercentage + "%");
                    if (isAdded()) {
                        appendLog("下载中... " + progressPercentage + "%");
                    }
                }
            });
        } else {
            Log.e(TAG, "ApkDownloader not initialized!");
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "下载器服务未准备好", Toast.LENGTH_SHORT).show();
            }
            appendLog("下载器服务未准备好");
        }
    }

    // 6. 移除已弃用的 onRequestPermissionsResult 方法
    // @Override
    // public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    //     super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    //     if (requestCode == REQUEST_STORAGE_PERMISSION_FOR_APK) {
    //         // ... (原有逻辑已移至 requestPermissionLauncher 的回调中)
    //     }
    // }
}