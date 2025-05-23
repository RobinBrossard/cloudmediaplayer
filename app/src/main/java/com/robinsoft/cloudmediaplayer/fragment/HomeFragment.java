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
import com.robinsoft.cloudmediaplayer.utils.FLog;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@UnstableApi
public class HomeFragment extends Fragment {

    private static final String TAG = "MP_PLAYBACK";
    // --- 修改开始: 为状态保存添加Key ---
    private static final String KEY_CURRENT_DIR_ID = "currentDirId";
    // --- 修改结束 ---

    private final CloudMediaService mediaService = new OneDriveMediaService();
    private DefaultTrackSelector trackSelector;
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private ImageView imageView1;
    private ImageView imageView2;
    private ImageView activeImageView;
    private RecyclerView recyclerView;
    private Button btnLogin, btnRoot, btnGo;
    private boolean isFullscreen = false;
    private ConstraintLayout.LayoutParams originalBottomContainerLayoutParams;
    private View topContainer;
    private View bottomContainer;
    private String currentDirId;
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

    private Handler mainThreadHandler;
    private long autoPlayImageDisplayDurationMs = 10000L;

    private ActivityResultLauncher<String> requestPermissionLauncher;


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        apkDownloader = ApkDownloader.getInstance(context.getApplicationContext());
        mainThreadHandler = new Handler(Looper.getMainLooper());
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        FLog.init(requireContext().getApplicationContext());

        // --- 修改开始: 恢复因系统回收而可能丢失的currentDirId ---
        if (savedInstanceState != null) {
            currentDirId = savedInstanceState.getString(KEY_CURRENT_DIR_ID);
            FLog.d(TAG, "Restored currentDirId from savedInstanceState: " + currentDirId);
        }
        // --- 修改结束 ---

        //这是一个存储权限申请的注册，不用管
        requestPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(),
                isGranted -> {
                    if (isGranted) {
                        if (isAdded() && getContext() != null) {
                            Toast.makeText(getContext(), "存储权限已授予", Toast.LENGTH_SHORT).show();
                        }
                        if (pendingApkUrl != null && pendingApkFileName != null) {
                            proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
                        } else {
                            FLog.w(TAG, "权限授予后，待下载的APK信息丢失");
                            if (isAdded() && getContext() != null) {
                                Toast.makeText(getContext(), "无法继续下载，信息丢失", Toast.LENGTH_SHORT).show();
                            }
                        }
                    } else {
                        if (isAdded() && getContext() != null) {
                            Toast.makeText(getContext(), "存储权限被拒绝，无法下载APK", Toast.LENGTH_LONG).show();
                        }
                        FLog.w(TAG, "权限被拒绝");
                    }
                    pendingApkUrl = null;
                    pendingApkFileName = null;
                });
    }

    // --- 修改开始: 实现状态保存 ---
    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString(KEY_CURRENT_DIR_ID, currentDirId);
    }
    // --- 修改结束 ---

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    private void refreshDirectory() {
        if (getViewLifecycleOwner() == null || adapter == null || !isAdded()) {
            FLog.w(TAG, "refreshDirectory called when view, adapter, or fragment not ready.");
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
            FLog.d(TAG, "Auto-play image display duration set to: " + durationMs + "ms");
        } else {
            FLog.w(TAG, "Attempted to set invalid auto-play image display duration: " + durationMs + "ms");
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
        imageView1 = view.findViewById(R.id.imageView1);
        imageView2 = view.findViewById(R.id.imageView2);

        if (imageView1 == null || imageView2 == null) {
            FLog.e(TAG, "One or both ImageViews not found in layout! Check R.id.imageView1 and R.id.imageView2.");
            Toast.makeText(getContext(), "Image views not found, image features disabled.", Toast.LENGTH_LONG).show();
        } else {
            activeImageView = imageView1;
            imageView1.setVisibility(View.VISIBLE);
            imageView2.setVisibility(View.GONE);
        }
        playerView = view.findViewById(R.id.player_view);
        topContainer = view.findViewById(R.id.top_container);
        bottomContainer = view.findViewById(R.id.bottom_container);

        if (bottomContainer != null) {
            originalBottomContainerLayoutParams = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            FLog.d(TAG, "onViewCreated: Captured originalBottomContainerLayoutParams.");
        } else {
            FLog.e(TAG, "onViewCreated: Fragment's R.id.bottom_container not found!");
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
                    FLog.d(TAG, "AutoPlay: Video ended, trying next file.");
                    if (playerView != null) {
                        playerView.setVisibility(View.GONE);
                    }
                    handleAutoPlayNextFile();
                }
            }

            @Override
            public void onPlayerError(@NonNull PlaybackException error) {
                FLog.e(TAG, "ExoPlayer Error: " + error.getMessage(), error);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "播放错误: " + error.getMessage(), Toast.LENGTH_LONG).show();
                }
                FLog.e(TAG, "播放错误: " + error.getMessage());
                if (isAutoPlaying) {
                    FLog.w(TAG, "AutoPlay: Error during video playback. Attempting to skip to next file.");
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
            if (imageView1 == null || imageView2 == null) {
                FLog.e(TAG, "ImageViews not initialized, cannot process click.");
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
                    ImageView targetManualImageView = (activeImageView == imageView1) ? imageView2 : imageView1;
                    if (autoPlayImageRunnable != null && mainThreadHandler != null) {
                        mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                    }
                    loadImage(url, true, false, targetManualImageView);
                    break;
                case VIDEO:
                    if (imageView1 != null) imageView1.setVisibility(View.GONE);
                    if (imageView2 != null) imageView2.setVisibility(View.GONE);
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
                FLog.e(TAG, "Login failed", error);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "登录失败: " + error.getMessage(), Toast.LENGTH_SHORT).show();
                }
            }
        }));

        btnRoot.setOnClickListener(v -> {
            currentDirId = null;
            refreshDirectory();
        });

        btnGo.setOnClickListener(v -> {

            RecyclerView.Adapter<?> adapterLocal = recyclerView.getAdapter();
            if (adapterLocal != null && adapterLocal.getItemCount() > 0) {
                FLog.d("RecyclerViewCheck", "RecyclerView has content. Item count: " + adapterLocal.getItemCount());
            } else {
                if (adapterLocal == null) {
                    FLog.d("RecyclerViewCheck", "RecyclerView has no adapter set.");
                } else {
                    FLog.d("RecyclerViewCheck", "RecyclerView is empty. Item count: 0");
                }
                Toast.makeText(getContext(), "未登录网盘或网盘无内容", Toast.LENGTH_SHORT).show();
                return;
            }

            if (!isAutoPlaying) {
                autoPlayStack.clear();
                if (currentDirId != null)
                    autoPlayStack.push(currentDirId);
                else {
                }
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

        View.OnClickListener mediaViewClickListener = v -> {
            if (isAutoPlaying) {
                exitFullscreen();
                stopAutoPlay();
                btnGo.setText("播放");
                refreshDirectory();
            } else {
                toggleFullscreen();
            }
        };
        playerView.setOnClickListener(mediaViewClickListener);
        if (imageView1 != null) imageView1.setOnClickListener(mediaViewClickListener);
        if (imageView2 != null) imageView2.setOnClickListener(mediaViewClickListener);
    }

    private void startAutoPlay() {
        if (!isAutoPlaying || !isAdded()) {
            FLog.d(TAG, "startAutoPlay: Bailing out, isAutoPlaying=" + isAutoPlaying + ", isAdded=" + isAdded());
            return;
        }

        String dirIdToPlay;
        if (autoPlayStack.isEmpty()) {
            dirIdToPlay = this.currentDirId;
            FLog.d(TAG, "AutoPlay: Stack empty. Starting/Restarting playback from scope: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay));
        } else {
            dirIdToPlay = autoPlayStack.pop();
            FLog.d(TAG, "AutoPlay: Popped '" + dirIdToPlay + "' from stack. Stack size now: " + autoPlayStack.size());
        }

        if (getViewLifecycleOwner() == null) {
            FLog.w(TAG, "AutoPlay: View lifecycle owner not available. Stopping auto-play.");
            stopAutoPlay();
            if (isAdded() && btnGo != null) btnGo.setText("播放");
            if (isFullscreen) exitFullscreen();
            return;
        }

        FLog.d(TAG, "AutoPlay: Listing media for dirId: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay));
        mediaService.listMedia(dirIdToPlay)
                .observe(getViewLifecycleOwner(), items -> {
                    if (!isAutoPlaying || !isAdded()) {
                        FLog.d(TAG, "AutoPlay: listMedia observed, but state changed. isAutoPlaying=" + isAutoPlaying + ", isAdded=" + isAdded());
                        return;
                    }
                    if (items == null) {
                        FLog.w(TAG, "AutoPlay: listMedia returned null items for dirId: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay) + ". Attempting next from stack or restarting scope.");
                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(this::startAutoPlay);
                        }
                        return;
                    }

                    if (dirIdToPlay == null && items.isEmpty() && autoPlayStack.isEmpty()) {
                        FLog.i(TAG, "AutoPlay: Root directory is empty and stack is empty. No content to play.");
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
                                // --- 修改开始: 增加null检查，防止将null的ID加入列表 ---
                                if (it.getId() != null) {
                                    localChildDirs.add(it.getId());
                                } else {
                                    FLog.w(TAG, "A folder item was returned with a null ID. Skipping.");
                                }
                                // --- 修改结束 ---
                            } else if (it.getType() == CloudMediaItem.MediaType.IMAGE || it.getType() == CloudMediaItem.MediaType.VIDEO) {
                                localFilesToPlay.add(it);
                            }
                        }

                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(() -> {
                                if (!isAutoPlaying || !isAdded()) {
                                    return;
                                }
                                for (int i = localChildDirs.size() - 1; i >= 0; i--) {
                                    autoPlayStack.push(localChildDirs.get(i));
                                }
                                FLog.d(TAG, "AutoPlay: Pushed " + localChildDirs.size() + " child dirs. Stack size now: " + autoPlayStack.size());

                                if (localFilesToPlay.isEmpty() && autoPlayStack.isEmpty()) {
                                    if (this.currentDirId == dirIdToPlay && items.isEmpty() && localChildDirs.isEmpty()) {
                                        FLog.i(TAG, "AutoPlay: Scope root " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay) + " is empty (no files, no subdirs). Stopping.");
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
            FLog.d(TAG, "handleAutoPlayNextFile: Removing existing autoPlayImageRunnable before advancing.");
            mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
        }

        if (currentAutoPlayFiles == null || currentAutoPlayOnAllDone == null) {
            FLog.d(TAG, "AutoPlay: handleAutoPlayNextFile called but no current auto-play sequence info. Attempting to process next directory.");
            startAutoPlay();
            return;
        }

        currentAutoPlayIndex++;
        if (currentAutoPlayIndex < currentAutoPlayFiles.size()) {
            FLog.d(TAG, "AutoPlay: Playing next file in sequence, index: " + currentAutoPlayIndex);
            playFileAtIndex(currentAutoPlayFiles, currentAutoPlayIndex, currentAutoPlayOnAllDone);
        } else {
            FLog.d(TAG, "AutoPlay: All files in current directory played.");
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
            FLog.d(TAG, "AutoPlay: playFilesSequentially called but auto-play is off or fragment not added.");
            return;
        }
        this.currentAutoPlayFiles = new ArrayList<>(files);
        this.currentAutoPlayIndex = -1;
        this.currentAutoPlayOnAllDone = onAllDone;

        FLog.d(TAG, "AutoPlay: Starting sequential play for " + files.size() + " files.");
        if (files.isEmpty()) {
            FLog.d(TAG, "AutoPlay: No files to play in this directory. Proceeding to next task.");
            Runnable tempOnAllDone = this.currentAutoPlayOnAllDone;
            this.currentAutoPlayFiles = null;
            this.currentAutoPlayIndex = -1;
            this.currentAutoPlayOnAllDone = null;
            if (tempOnAllDone != null) {
                tempOnAllDone.run();
            } else {
                startAutoPlay();
            }
        } else {
            handleAutoPlayNextFile();
        }
    }

    private void playFileAtIndex(List<CloudMediaItem> files, int idx, Runnable onAllDone) {
        if (!isAutoPlaying || !isAdded()) {
            FLog.d(TAG, "AutoPlay: playFileAtIndex called but auto-play is off or fragment not added.");
            return;
        }
        if (files == null || idx >= files.size() || idx < 0) {
            FLog.w(TAG, "AutoPlay: Invalid files list or index in playFileAtIndex. Index: " + idx + ", Files size: " + (files == null ? "null" : files.size()));
            if (onAllDone != null) {
                onAllDone.run();
            } else {
                startAutoPlay();
            }
            return;
        }
        if (imageView1 == null || imageView2 == null) {
            FLog.e(TAG, "AutoPlay: ImageViews not initialized in playFileAtIndex.");
            stopAutoPlay();
            return;
        }

        this.currentAutoPlayFiles = files;
        this.currentAutoPlayIndex = idx;
        this.currentAutoPlayOnAllDone = onAllDone;

        CloudMediaItem item = files.get(idx);
        String url = prepareMediaUrl(item.getUrl());

        FLog.d(TAG, "AutoPlay: Playing file " + item.getName() + " at index " + idx);

        if (autoPlayImageRunnable != null && mainThreadHandler != null) {
            FLog.d(TAG, "playFileAtIndex: Removing previous autoPlayImageRunnable.");
            mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
        }

        preloadNextImage();

        if (item.getType() == CloudMediaItem.MediaType.IMAGE) {
            playerView.setVisibility(View.GONE);
            ImageView targetImageView = (activeImageView == imageView1) ? imageView2 : imageView1;
            FLog.d(TAG, "AutoPlay: Loading image into " + (targetImageView == imageView1 ? "imageView1" : "imageView2"));
            loadImage(url, false, true, targetImageView);

        } else if (item.getType() == CloudMediaItem.MediaType.VIDEO) {
            if (imageView1 != null) imageView1.setVisibility(View.GONE);
            if (imageView2 != null) imageView2.setVisibility(View.GONE);
            if (playerView != null) playerView.setVisibility(View.VISIBLE);
            if (exoPlayer != null) {
                exoPlayer.stop();
                exoPlayer.clearMediaItems();
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                exoPlayer.prepare();
                exoPlayer.play();
            }
        } else {
            FLog.w(TAG, "AutoPlay: Encountered unexpected file type: " + item.getType() + ". Skipping.");
            handleAutoPlayNextFile();
        }
    }

    private void preloadNextImage() {
        if (!isAutoPlaying || !isAdded() || currentAutoPlayFiles == null || currentAutoPlayFiles.isEmpty()) {
            return;
        }

        for (int i = currentAutoPlayIndex + 1; i < currentAutoPlayFiles.size(); i++) {
            CloudMediaItem nextItem = currentAutoPlayFiles.get(i);
            if (nextItem.getType() == CloudMediaItem.MediaType.IMAGE) {
                Context safeContext = getContext();
                if (safeContext == null) return;

                FLog.d(TAG, "AutoPlay: Preloading next image found at index " + i + ": " + nextItem.getName());
                Glide.with(safeContext)
                        .load(prepareMediaUrl(nextItem.getUrl()))
                        .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                        .preload();
                return;
            }
        }
        FLog.d(TAG, "AutoPlay: No subsequent image found to preload in the current directory list.");
    }

    private void stopAutoPlay() {
        FLog.i(TAG, "AutoPlay: Stopping auto-play sequence.");
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

    private void loadImage(String url, boolean loadForPotentialFullscreen, final boolean isForAutoPlayTimer, final ImageView targetImageView) {
        if (!isAdded() || getContext() == null || targetImageView == null) {
            FLog.w(TAG, "loadImage: Fragment not ready, ImageView is null, or targetImageView is null.");
            if (isForAutoPlayTimer && isAutoPlaying && isAdded() && mainThreadHandler != null) {
                FLog.w(TAG, "loadImage: Pre-condition failed for auto-play image, trying next.");
                mainThreadHandler.post(this::handleAutoPlayNextFile);
            }
            return;
        }
        final String finalUrl = prepareMediaUrl(url);

        RequestListener<Drawable> glideListener = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                FLog.e(TAG, "Glide: Failed to load image into " + (targetImageView == imageView1 ? "imageView1" : "imageView2") + " URL: " + finalUrl, e);
                if (isAdded() && getContext() != null) {
                    if (isForAutoPlayTimer && isAutoPlaying && isAdded()) {
                        FLog.w(TAG, "AutoPlay: Image load failed for " + finalUrl + ", attempting to play next file.");
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
                FLog.d(TAG, "Glide: Image resource ready for " + (targetImageView == imageView1 ? "imageView1" : "imageView2") + " URL: " + finalUrl);
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
                        FLog.d(TAG, "AutoPlay: Image resource ready, starting " + autoPlayImageDisplayDurationMs + "ms timer for " + finalUrl);
                        autoPlayImageRunnable = () -> {
                            if (isAutoPlaying && isAdded()) {
                                FLog.d(TAG, "AutoPlay: " + autoPlayImageDisplayDurationMs + "ms timer expired for " + finalUrl + ", handling next file.");
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

        if (loadForPotentialFullscreen && !isForAutoPlayTimer) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            Activity activity = getActivity();
            if (activity != null) {
                activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenWidth = displayMetrics.widthPixels;
                int screenHeight = displayMetrics.heightPixels;
                FLog.d(TAG, "Glide: Loading image for potential fullscreen into " + (targetImageView == imageView1 ? "imageView1" : "imageView2") + " URL: " + finalUrl);
                Glide.with(this)
                        .load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .override(screenWidth, screenHeight)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .listener(glideListener)
                        .into(targetImageView);
            } else {
                FLog.w(TAG, "Activity not available for getting screen dimensions. Loading with default Glide sizing into " + (targetImageView == imageView1 ? "imageView1" : "imageView2"));
                Glide.with(this).load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .fitCenter().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).listener(glideListener).into(targetImageView);
            }
        } else {
            FLog.d(TAG, "Glide: Loading image for AUTO-PLAY path.");
            if (getActivity() != null && isFullscreen) {
                DisplayMetrics displayMetrics = new DisplayMetrics();
                getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenWidth = displayMetrics.widthPixels;
                int screenHeight = displayMetrics.heightPixels;
                FLog.d(TAG, "Glide (Auto-Play with Override): Loading " + finalUrl);
                Glide.with(this)
                        .load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .override(screenWidth, screenHeight)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .listener(glideListener)
                        .into(targetImageView);
            } else {
                FLog.d(TAG, "Glide (Auto-Play Original): Loading " + finalUrl);
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
            FLog.d(TAG, "exitFullscreen: Applying user's specified non-fullscreen constraints.");
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            lp.topToTop = R.id.guideline_half;
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
        FLog.d(TAG, "onDestroyView called");

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

    private void checkStoragePermissionAndDownloadApk(String url, String fileName) {
        this.pendingApkUrl = url;
        this.pendingApkFileName = fileName;

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            } else {
                proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
                this.pendingApkUrl = null;
                this.pendingApkFileName = null;
            }
        } else {
            proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
            this.pendingApkUrl = null;
            this.pendingApkFileName = null;
        }
    }

    private void proceedWithApkDownload(String url, String fileName) {
        if (!isAdded() || getActivity() == null) {
            FLog.w(TAG, "proceedWithApkDownload: Fragment not added or activity is null.");
            FLog.w(TAG, "proceedWithApkDownload: Fragment not added or activity is null.");
            return;
        }

        if (apkDownloader != null) {
            FLog.d(TAG, "准备下载...");
            apkDownloader.downloadAndInstallApk(url, fileName, getActivity(), new ApkDownloader.Callback() {
                @Override
                public void onSuccess(File apkFile) {
                    FLog.d(TAG, "APK Downloaded Successfully. File: " + apkFile.getAbsolutePath());
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "APK 下载成功，准备安装", Toast.LENGTH_SHORT).show();
                        FLog.d(TAG, "APK 下载成功，准备安装");
                    }
                }

                @Override
                public void onFailure(String errorMessage) {
                    FLog.e(TAG, "APK Download Failed: " + errorMessage);
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "下载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                        FLog.w(TAG, "下载失败");
                    }
                }

                @Override
                public void onProgress(int progressPercentage) {
                    FLog.d(TAG, "APK Download Progress: " + progressPercentage + "%");
                    if (isAdded()) {
                        FLog.d(TAG, "下载中... " + progressPercentage + "%");
                    }
                }
            });
        } else {
            FLog.e(TAG, "ApkDownloader not initialized!");
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "下载器服务未准备好", Toast.LENGTH_SHORT).show();
            }
            FLog.w(TAG, "下载器服务未准备好");
        }
    }
}