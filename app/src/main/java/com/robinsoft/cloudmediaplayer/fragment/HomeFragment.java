package com.robinsoft.cloudmediaplayer.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

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
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

@UnstableApi
public class HomeFragment extends Fragment {

    private static final String TAG = "MP_PLAYBACK";
    // --- 新增：APK 下载和权限相关 ---
    private static final int REQUEST_STORAGE_PERMISSION_FOR_APK = 123; // 权限请求码
    private final CloudMediaService mediaService = new OneDriveMediaService();
    private DefaultTrackSelector trackSelector;
    private ExoPlayer exoPlayer;
    private PlayerView playerView;
    private ImageView imageView;
    private RecyclerView recyclerView;
    private Button btnLogin, btnRoot, btnGo;
    private boolean isFullscreen = false;
    private ConstraintLayout.LayoutParams originalBottomContainerLayoutParams;
    private View topContainer;
    private View bottomContainer;
    // 自动播放相关
    private String currentDirId;
    private String autoRootId;
    private Deque<String> autoPlayStack = new ArrayDeque<>();
    private boolean isAutoPlaying = false;
    private Runnable autoPlayImageRunnable;
    private Player.Listener exoPlayerListener;
    private List<CloudMediaItem> currentAutoPlayFiles;
    private int currentAutoPlayIndex;
    private Runnable currentAutoPlayOnAllDone;
    private CloudMediaAdapter adapter;
    private ApkDownloader apkDownloader;
    private String pendingApkUrl;      // 用于在请求权限时暂存 URL
    private String pendingApkFileName; // 用于在请求权限时暂存文件名

    // --- 新增：用于显示下载进度的UI组件 (请确保在 fragment_home.xml 中添加这些视图) ---
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;
    // --- 结束新增 ---


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        apkDownloader = ApkDownloader.getInstance(context.getApplicationContext());
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    // ... (appendLog, refreshDirectory, prepareMediaUrl 方法不变) ...
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
                .observe(getViewLifecycleOwner(), items -> adapter.submitList(items));
    }

    private String prepareMediaUrl(String originalUrl) {
        if (originalUrl != null && originalUrl.contains("1drv.com")) {
            return originalUrl + (originalUrl.contains("?") ? "&" : "?") + "download=1";
        }
        return originalUrl;
    }


    @UnstableApi
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnLogin = view.findViewById(R.id.btnLogin);
        btnRoot = view.findViewById(R.id.btnRoot);
        btnGo = view.findViewById(R.id.btnGo);
        recyclerView = view.findViewById(R.id.recyclerView);
        imageView = view.findViewById(R.id.imageView);
        playerView = view.findViewById(R.id.player_view);
        topContainer = view.findViewById(R.id.top_container);
        bottomContainer = view.findViewById(R.id.bottom_container);

        // --- 新增：初始化下载进度UI (请确保ID与布局文件一致) ---
        // 示例ID，你需要替换为你的布局文件中的真实ID
        // downloadProgressBar = view.findViewById(R.id.apk_download_progress_bar);
        // downloadProgressText = view.findViewById(R.id.apk_download_progress_text);
        // 初始时可以隐藏它们
        // if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
        // if (downloadProgressText != null) downloadProgressText.setVisibility(View.GONE);
        // --- 结束新增 ---


        if (bottomContainer != null) {
            originalBottomContainerLayoutParams = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
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
            // ... (ExoPlayer监听器内容不变) ...
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

            if (item.getType() == CloudMediaItem.MediaType.FOLDER) {
                if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                currentDirId = item.getId();
                refreshDirectory();
                return;
            }

            String url = prepareMediaUrl(item.getUrl());

            switch (item.getType()) {
                case IMAGE:
                    if (exoPlayer != null && exoPlayer.isPlaying()) exoPlayer.pause();
                    playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    loadImage(url, true);
                    break;
                case VIDEO:
                    imageView.setVisibility(View.GONE);
                    playerView.setVisibility(View.VISIBLE);
                    exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                    exoPlayer.prepare();
                    exoPlayer.play();
                    break;
                case APK:
                    Toast.makeText(getContext(), "准备下载并安装：" + item.getName(), Toast.LENGTH_SHORT).show();
                    // --- 修改：调用新的带权限检查的方法 ---
                    checkStoragePermissionAndDownloadApk(url, item.getName());
                    // --- 结束修改 ---
                    break;
                default:
                    Toast.makeText(getContext(), "无法播放该文件类型", Toast.LENGTH_SHORT).show();
            }
        });

        // ... (btnLogin, btnRoot, btnGo, mediaViewClickListener 的 setOnClickListener 内容不变) ...
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

        btnGo.setOnClickListener(v -> {
            if (!isAutoPlaying) {
                autoRootId = currentDirId;
                autoPlayStack.clear();
                if (autoRootId != null) {
                    autoPlayStack.push(autoRootId);
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
            if (isAutoPlaying && isFullscreen) {
                exitFullscreen();
                stopAutoPlay();
                btnGo.setText("播放");
                refreshDirectory();
            } else {
                toggleFullscreen();
            }
        };
        playerView.setOnClickListener(mediaViewClickListener);
        imageView.setOnClickListener(mediaViewClickListener);
    }

    // ... (startAutoPlay, handleAutoPlayNextFile, playFilesSequentially, playFileAtIndex, stopAutoPlay, loadImage, enterFullscreen, exitFullscreen, toggleFullscreen 方法不变) ...
    private void startAutoPlay() {
        if (!isAutoPlaying || !isAdded()) return;

        String dirIdToPlay;
        if (autoPlayStack.isEmpty()) {
            dirIdToPlay = autoRootId;
            Log.d(TAG, "AutoPlay: Stack empty, playing from autoRootId: " + (dirIdToPlay == null ? "ROOT" : dirIdToPlay));
        } else {
            dirIdToPlay = autoPlayStack.pop();
            Log.d(TAG, "AutoPlay: Popped from stack, playing dirId: " + dirIdToPlay);
        }

        if (getViewLifecycleOwner() == null) {
            Log.w(TAG, "AutoPlay: View lifecycle owner not available. Stopping auto-play.");
            stopAutoPlay();
            return;
        }

        mediaService.listMedia(dirIdToPlay)
                .observe(getViewLifecycleOwner(), items -> {
                    if (!isAutoPlaying || !isAdded()) return;

                    List<String> childDirs = new ArrayList<>();
                    List<CloudMediaItem> filesToPlay = new ArrayList<>();
                    for (CloudMediaItem it : items) {
                        if (it.getType() == CloudMediaItem.MediaType.FOLDER) {
                            childDirs.add(it.getId());
                        } else if (it.getType() == CloudMediaItem.MediaType.IMAGE || it.getType() == CloudMediaItem.MediaType.VIDEO) {
                            filesToPlay.add(it);
                        }
                    }
                    filesToPlay.sort(Comparator.comparing(CloudMediaItem::getLastModifiedDateTime));
                    for (int i = childDirs.size() - 1; i >= 0; i--) {
                        autoPlayStack.push(childDirs.get(i));
                    }
                    playFilesSequentially(filesToPlay, this::startAutoPlay);
                });
    }

    private void handleAutoPlayNextFile() {
        if (!isAutoPlaying || !isAdded()) return;

        if (currentAutoPlayFiles == null || currentAutoPlayOnAllDone == null) {
            Log.d(TAG, "AutoPlay: handleAutoPlayNextFile called but no current auto-play sequence info.");
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
            Log.d(TAG, "AutoPlay: No files to play in this directory.");
            Runnable tempOnAllDone = this.currentAutoPlayOnAllDone;
            this.currentAutoPlayFiles = null;
            this.currentAutoPlayIndex = -1;
            this.currentAutoPlayOnAllDone = null;
            if (tempOnAllDone != null) {
                tempOnAllDone.run();
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
        if (idx >= files.size()) {
            Log.d(TAG, "AutoPlay: Index out of bounds in playFileAtIndex, calling onAllDone.");
            if (onAllDone != null) {
                onAllDone.run();
            }
            return;
        }

        this.currentAutoPlayFiles = files;
        this.currentAutoPlayIndex = idx;
        this.currentAutoPlayOnAllDone = onAllDone;

        CloudMediaItem item = files.get(idx);
        String url = prepareMediaUrl(item.getUrl());

        Log.d(TAG, "AutoPlay: Playing file " + item.getName() + " at index " + idx);

        if (item.getType() == CloudMediaItem.MediaType.IMAGE) {
            if (playerView != null) playerView.setVisibility(View.GONE);
            if (imageView != null) imageView.setVisibility(View.VISIBLE);
            loadImage(url, false);

            if (imageView != null && autoPlayImageRunnable != null) {
                imageView.removeCallbacks(autoPlayImageRunnable);
            }
            autoPlayImageRunnable = () -> {
                if (isAutoPlaying && isAdded()) {
                    handleAutoPlayNextFile();
                }
            };
            if (imageView != null) {
                imageView.postDelayed(autoPlayImageRunnable, 3_000);
            }
        } else if (item.getType() == CloudMediaItem.MediaType.VIDEO) {
            if (imageView != null) imageView.setVisibility(View.GONE);
            if (playerView != null) playerView.setVisibility(View.VISIBLE);
            if (exoPlayer != null) {
                exoPlayer.setMediaItem(MediaItem.fromUri(Uri.parse(url)));
                exoPlayer.prepare();
                exoPlayer.play();
            }
        } else {
            Log.w(TAG, "AutoPlay: Encountered unexpected file type: " + item.getType() + ". Skipping.");
            handleAutoPlayNextFile();
        }
    }

    private void stopAutoPlay() {
        Log.d(TAG, "AutoPlay: Stopping auto-play.");
        isAutoPlaying = false;
        autoPlayStack.clear();
        if (exoPlayer != null && exoPlayer.isPlaying()) {
            exoPlayer.pause();
        }
        if (imageView != null && autoPlayImageRunnable != null) {
            imageView.removeCallbacks(autoPlayImageRunnable);
            autoPlayImageRunnable = null;
        }
        currentAutoPlayFiles = null;
        currentAutoPlayIndex = -1;
        currentAutoPlayOnAllDone = null;
    }

    private void loadImage(String url, boolean loadForPotentialFullscreen) {
        if (!isAdded() || getContext() == null || imageView == null) {
            Log.w(TAG, "loadImage: Fragment not ready or ImageView is null.");
            return;
        }
        final String finalUrl = prepareMediaUrl(url);

        RequestListener<Drawable> glideListener = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                Log.e(TAG, "Glide: Failed to load image: " + finalUrl, e);
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "加载图片失败", Toast.LENGTH_SHORT).show();
                }
                if (playerView != null) playerView.setVisibility(View.GONE);
                if (imageView != null)
                    imageView.setVisibility(View.VISIBLE); // Keep imageView visible to show placeholder or error
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                if (imageView != null && isAdded()) {
                    if (imageView.getVisibility() != View.VISIBLE) {
                        imageView.setVisibility(View.VISIBLE);
                    }
                    if (playerView != null && playerView.getVisibility() == View.VISIBLE) {
                        playerView.setVisibility(View.GONE);
                    }
                }
                return false;
            }
        };

        if (loadForPotentialFullscreen && !isAutoPlaying) {
            DisplayMetrics displayMetrics = new DisplayMetrics();
            if (getActivity() != null) {
                getActivity().getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenWidth = displayMetrics.widthPixels;
                int screenHeight = displayMetrics.heightPixels;
                Log.d(TAG, "Glide: Loading image for potential fullscreen: " + finalUrl + " with override " + screenWidth + "x" + screenHeight);
                Glide.with(this)
                        .load(finalUrl)
                        .override(screenWidth, screenHeight)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .listener(glideListener)
                        .into(imageView);
            } else {
                Log.w(TAG, "Activity not available for getting screen dimensions. Loading with default Glide sizing.");
                Glide.with(this).load(finalUrl).fitCenter().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).listener(glideListener).into(imageView);
            }
        } else {
            Log.d(TAG, "Glide: Loading image with automatic sizing based on ImageView: " + finalUrl);
            Glide.with(this)
                    .load(finalUrl)
                    .fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC) // Use AUTOMATIC for non-fullscreen or auto-play
                    .listener(glideListener)
                    .into(imageView);
        }
    }

    private void enterFullscreen() {
        if (isFullscreen) return;
        Activity activity = getActivity();
        if (activity == null || !isAdded()) return;
        isFullscreen = true;

        Window window = activity.getWindow();

        View activityBottomNav = activity.findViewById(R.id.bottom_nav);
        if (activityBottomNav != null) activityBottomNav.setVisibility(View.GONE);

        if (topContainer != null) topContainer.setVisibility(View.GONE);
        if (bottomContainer != null) {
            // originalBottomContainerLayoutParams = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams(); // Already stored
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            lp.topToTop = ConstraintLayout.LayoutParams.PARENT_ID;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            bottomContainer.setLayoutParams(lp);
        }
    }

    private void exitFullscreen() {
        if (!isFullscreen) return;
        Activity activity = getActivity();
        if (activity == null || !isAdded()) return;
        isFullscreen = false;

        Window window = activity.getWindow();

        if (topContainer != null) topContainer.setVisibility(View.VISIBLE);

        if (bottomContainer != null && originalBottomContainerLayoutParams != null) { // Check if original params are stored
            // Restore original constraints or specific non-fullscreen constraints
            // This simplified version might need adjustment based on your exact original layout.
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            lp.topToTop = R.id.guideline_half; // Assuming guideline_half is the intended top constraint out of fullscreen
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            bottomContainer.setLayoutParams(originalBottomContainerLayoutParams); // Prefer restoring original
            // bottomContainer.requestLayout(); // Usually not needed if restoring original LayoutParams object
            // bottomContainer.invalidate(); // Usually not needed
        } else if (bottomContainer != null) {
            // Fallback if originalBottomContainerLayoutParams wasn't properly captured or is null
            ConstraintLayout.LayoutParams lp = (ConstraintLayout.LayoutParams) bottomContainer.getLayoutParams();
            lp.topToTop = R.id.guideline_half;
            lp.bottomToBottom = ConstraintLayout.LayoutParams.PARENT_ID;
            bottomContainer.setLayoutParams(lp);
        }


        View activityBottomNav = activity.findViewById(R.id.bottom_nav);
        if (activityBottomNav != null) activityBottomNav.setVisibility(View.VISIBLE);
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
            if (imageView != null && autoPlayImageRunnable != null) {
                imageView.removeCallbacks(autoPlayImageRunnable);
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
        if (apkDownloader != null) {
            Log.d(TAG, "Calling apkDownloader.cleanup()");
            apkDownloader.cleanup(); // cleanup is a no-op in current ApkDownloader, but good to have
        }

        playerView = null;
        imageView = null;
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
        // --- 新增：清空下载进度UI引用 ---
        downloadProgressBar = null;
        downloadProgressText = null;
        // --- 结束新增 ---
    }

    // --- 新增：检查权限并开始下载的方法 ---
    private void checkStoragePermissionAndDownloadApk(String url, String fileName) {
        this.pendingApkUrl = url;
        this.pendingApkFileName = fileName;

        // WRITE_EXTERNAL_STORAGE 权限仅在 Android 9 (API 28) 及以下版本需要
        // (基于 Manifest 中 android:maxSdkVersion="28" 的设置)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                // 权限尚未授予，请求权限
                // Fragment可以直接调用requestPermissions
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION_FOR_APK);
            } else {
                // 权限已授予 (或不需要请求，因为是 Android 10+)
                proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
            }
        } else {
            // Android 10 (API 29) 及以上版本，应用可以直接写入自己的文件到公共 Downloads 目录
            // (基于 Manifest 中对 WRITE_EXTERNAL_STORAGE 使用 maxSdkVersion="28")
            proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
        }
    }
    // --- 结束新增 ---

    // --- 新增：实际执行下载的方法 ---
    private void proceedWithApkDownload(String url, String fileName) {
        if (!isAdded()) {
            Log.w(TAG, "proceedWithApkDownload: Fragment not added.");
            return;
        }
        if (apkDownloader != null) {
            Activity activity = getActivity();
            if (activity != null) { // isAdded() 已经保证了 getContext() != null，activity 也可以检查

                // --- 新增：显示下载进度UI ---
                if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.VISIBLE);
                if (downloadProgressText != null) {
                    downloadProgressText.setText("准备下载...");
                    downloadProgressText.setVisibility(View.VISIBLE);
                }
                // --- 结束新增 ---

                apkDownloader.downloadAndInstallApk(url, fileName, activity, new ApkDownloader.Callback() {
                    @Override
                    public void onSuccess(File apkFile) {
                        Log.d(TAG, "APK Downloaded Successfully. File: " + apkFile.getAbsolutePath());
                        if (isAdded()) { // 再次检查 Fragment 是否仍然附加
                            Toast.makeText(requireContext(), "APK 下载成功，准备安装", Toast.LENGTH_SHORT).show();
                            // --- 新增：隐藏下载进度UI ---
                            if (downloadProgressBar != null)
                                downloadProgressBar.setVisibility(View.GONE);
                            if (downloadProgressText != null) {
                                // downloadProgressText.setText("下载完成"); // 可以选择显示完成状态
                                downloadProgressText.setVisibility(View.GONE); // 或者直接隐藏
                            }
                            // --- 结束新增 ---
                        }
                    }

                    @Override
                    public void onFailure(String errorMessage) {
                        Log.e(TAG, "APK Download Failed: " + errorMessage);
                        if (isAdded()) {
                            Toast.makeText(requireContext(), "下载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                            // --- 新增：处理下载失败时的UI ---
                            if (downloadProgressBar != null)
                                downloadProgressBar.setVisibility(View.GONE);
                            if (downloadProgressText != null) {
                                downloadProgressText.setText("下载失败");
                                // downloadProgressText.setVisibility(View.GONE); // 或者一段时间后隐藏
                            }
                            // --- 结束新增 ---
                        }
                    }

                    @Override
                    public void onProgress(int progressPercentage) {
                        Log.d(TAG, "APK Download Progress: " + progressPercentage + "%");
                        if (isAdded()) {
                            // --- 新增：更新下载进度UI ---
                            if (downloadProgressBar != null) {
                                if (downloadProgressBar.getVisibility() != View.VISIBLE)
                                    downloadProgressBar.setVisibility(View.VISIBLE);
                                downloadProgressBar.setProgress(progressPercentage);
                            }
                            if (downloadProgressText != null) {
                                if (downloadProgressText.getVisibility() != View.VISIBLE)
                                    downloadProgressText.setVisibility(View.VISIBLE);
                                downloadProgressText.setText("下载中... " + progressPercentage + "%");
                            }
                            // --- 结束新增 ---
                        }
                    }
                });
            } else {
                Log.e(TAG, "Fragment not attached to an activity, or activity is null. Cannot start APK download.");
                if (isAdded() && getContext() != null) { // 理论上 isAdded() 包含 getContext() != null
                    Toast.makeText(getContext(), "无法启动下载，活动不存在", Toast.LENGTH_SHORT).show();
                }
                // --- 新增：确保UI在无法启动时被重置 ---
                if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
                if (downloadProgressText != null) downloadProgressText.setVisibility(View.GONE);
                // --- 结束新增 ---
            }
        } else {
            Log.e(TAG, "ApkDownloader not initialized!");
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "下载器服务未准备好", Toast.LENGTH_SHORT).show();
            }
            // --- 新增：确保UI在下载器未初始化时被重置 ---
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (downloadProgressText != null) downloadProgressText.setVisibility(View.GONE);
            // --- 结束新增 ---
        }
    }
    // --- 结束新增 ---


    // --- 新增：处理权限请求结果 ---
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION_FOR_APK) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                // 权限被授予
                Toast.makeText(getContext(), "存储权限已授予，开始下载...", Toast.LENGTH_SHORT).show();
                if (pendingApkUrl != null && pendingApkFileName != null) {
                    proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
                } else {
                    Log.w(TAG, "权限授予后，待下载的APK信息丢失");
                    Toast.makeText(getContext(), "无法继续下载，信息丢失", Toast.LENGTH_SHORT).show();
                }
            } else {
                // 权限被拒绝
                Toast.makeText(getContext(), "存储权限被拒绝，无法下载APK", Toast.LENGTH_LONG).show();
                // --- 新增：UI重置 ---
                if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
                if (downloadProgressText != null) {
                    downloadProgressText.setText("权限被拒绝");
                    // downloadProgressText.setVisibility(View.GONE); // 或者保持显示然后隐藏
                }
                // --- 结束新增 ---
            }
            // 清除暂存的参数
            pendingApkUrl = null;
            pendingApkFileName = null;
        }
    }
    // --- 结束新增 ---
}