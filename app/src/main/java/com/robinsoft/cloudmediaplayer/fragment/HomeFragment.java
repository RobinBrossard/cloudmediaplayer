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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;

@UnstableApi
public class HomeFragment extends Fragment {

    private static final String TAG = "MP_PLAYBACK";
    private static final int REQUEST_STORAGE_PERMISSION_FOR_APK = 123;
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
    private String pendingApkUrl;
    private String pendingApkFileName;
    private ProgressBar downloadProgressBar;
    private TextView downloadProgressText;

    private Handler mainThreadHandler;


    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        apkDownloader = ApkDownloader.getInstance(context.getApplicationContext());
        mainThreadHandler = new Handler(Looper.getMainLooper());
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
                    // --- MODIFICATION START: Update loadImage call ---
                    loadImage(url, true, false); // true for potential fullscreen, false for autoPlayTimer
                    // --- MODIFICATION END ---
                    break;
                case VIDEO:
                    imageView.setVisibility(View.GONE);
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
                    if (!isAutoPlaying || !isAdded()) {
                        return;
                    }
                    if (items == null) {
                        Log.d(TAG, "AutoPlay: listMedia returned null items for dirId: " + dirIdToPlay + ". Proceeding to next in stack.");
                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(this::startAutoPlay);
                        }
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
                        Collections.sort(localFilesToPlay, Comparator.comparing(CloudMediaItem::getLastModifiedDateTime));

                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(() -> {
                                if (!isAutoPlaying || !isAdded()) {
                                    return;
                                }
                                for (int i = localChildDirs.size() - 1; i >= 0; i--) {
                                    autoPlayStack.push(localChildDirs.get(i));
                                }
                                playFilesSequentially(localFilesToPlay, this::startAutoPlay);
                            });
                        }
                    }).start();
                });
    }

    private void handleAutoPlayNextFile() {
        if (!isAutoPlaying || !isAdded()) return;

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

        this.currentAutoPlayFiles = files;
        this.currentAutoPlayIndex = idx;
        this.currentAutoPlayOnAllDone = onAllDone;

        CloudMediaItem item = files.get(idx);
        String url = prepareMediaUrl(item.getUrl());

        Log.d(TAG, "AutoPlay: Playing file " + item.getName() + " at index " + idx);

        preloadNextAutoPlayImageIfApplicable();

        if (item.getType() == CloudMediaItem.MediaType.IMAGE) {
            if (playerView != null) playerView.setVisibility(View.GONE);
            if (imageView != null) imageView.setVisibility(View.VISIBLE);

            // --- MODIFICATION START: Define autoPlayImageRunnable here for clarity if not already a field ---
            // (It's already a field, so this is fine)
            // autoPlayImageRunnable = () -> { ... }; // This is already a field
            // --- MODIFICATION END ---

            // --- MODIFICATION START: Update loadImage call and remove postDelayed from here ---
            loadImage(url, false, true); // false for sizing, true for autoPlayTimer
            // The postDelayed logic is now INSIDE loadImage's onResourceReady
            // --- MODIFICATION END ---

        } else if (item.getType() == CloudMediaItem.MediaType.VIDEO) {
            // --- MODIFICATION START: Ensure any pending image auto-play runnable is cancelled when switching to video ---
            if (autoPlayImageRunnable != null) {
                if (mainThreadHandler != null) {
                    mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                } else if (imageView != null) {
                    imageView.removeCallbacks(autoPlayImageRunnable);
                }
                // autoPlayImageRunnable = null; // No need to nullify here, just remove callbacks
            }
            // --- MODIFICATION END ---
            if (imageView != null) imageView.setVisibility(View.GONE);
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
            } else if (imageView != null) {
                imageView.removeCallbacks(autoPlayImageRunnable);
            }
            // Keep autoPlayImageRunnable instance, just remove callbacks.
            // It will be redefined in playFileAtIndex if needed.
        }
        currentAutoPlayFiles = null;
        currentAutoPlayIndex = -1;
        currentAutoPlayOnAllDone = null;
    }

    // --- MODIFICATION START: Modify loadImage signature and add timer logic ---
    private void loadImage(String url, boolean loadForPotentialFullscreen, final boolean isForAutoPlayTimer) {
        // --- MODIFICATION END ---
        if (!isAdded() || getContext() == null || imageView == null) {
            Log.w(TAG, "loadImage: Fragment not ready or ImageView is null.");
            return;
        }
        final String finalUrl = prepareMediaUrl(url);

        RequestListener<Drawable> glideListener = new RequestListener<Drawable>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                Log.e(TAG, "Glide: Failed to load image: " + finalUrl, e);
                if (isAdded() && getContext() != null && imageView != null) {
                    if (playerView != null) playerView.setVisibility(View.GONE);
                    imageView.setVisibility(View.VISIBLE);
                    // --- MODIFICATION START: If auto-play image fails, still try to advance ---
                    // This prevents getting stuck on a failed image load during auto-play.
                    if (isForAutoPlayTimer && isAutoPlaying && isAdded()) {
                        Log.w(TAG, "AutoPlay: Image load failed, attempting to play next file.");
                        // We can directly call handleAutoPlayNextFile or post it to avoid deep recursion
                        // if multiple failures happen quickly. Posting is safer.
                        if (mainThreadHandler != null) {
                            mainThreadHandler.post(() -> {
                                if (isAutoPlaying && isAdded()) { // Re-check state
                                    handleAutoPlayNextFile();
                                }
                            });
                        }
                    }
                    // --- MODIFICATION END ---
                }
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                if (imageView != null && isAdded()) {
                    imageView.setVisibility(View.VISIBLE);
                    if (playerView != null) {
                        playerView.setVisibility(View.GONE);
                    }

                    // --- MODIFICATION START: Add timer logic here ---
                    if (isForAutoPlayTimer && isAutoPlaying) { // Check isAutoPlaying again, it might have been stopped
                        Log.d(TAG, "AutoPlay: Image resource ready, starting 3s timer for " + finalUrl);
                        if (autoPlayImageRunnable == null) { // Define if null, though it should be defined in playFileAtIndex
                            autoPlayImageRunnable = () -> {
                                if (isAutoPlaying && isAdded()) {
                                    handleAutoPlayNextFile();
                                }
                            };
                        }

                        // Remove any previously posted runnable for safety, then post new one
                        if (mainThreadHandler != null) {
                            mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                            mainThreadHandler.postDelayed(autoPlayImageRunnable, 3_000);
                        } else if (imageView != null) { // Fallback, less ideal
                            imageView.removeCallbacks(autoPlayImageRunnable);
                            imageView.postDelayed(autoPlayImageRunnable, 3_000);
                        }
                    }
                    // --- MODIFICATION END ---
                }
                return false;
            }
        };

        if (loadForPotentialFullscreen && !isAutoPlaying) { // This condition implies manual click
            DisplayMetrics displayMetrics = new DisplayMetrics();
            Activity activity = getActivity();
            if (activity != null) {
                activity.getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
                int screenWidth = displayMetrics.widthPixels;
                int screenHeight = displayMetrics.heightPixels;
                Log.d(TAG, "Glide: Loading image for potential fullscreen: " + finalUrl + " with override " + screenWidth + "x" + screenHeight);
                Glide.with(this)
                        .load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .override(screenWidth, screenHeight)
                        .fitCenter()
                        .diskCacheStrategy(DiskCacheStrategy.RESOURCE)
                        .listener(glideListener)
                        .into(imageView);
            } else {
                Log.w(TAG, "Activity not available for getting screen dimensions. Loading with default Glide sizing.");
                Glide.with(this).load(finalUrl)
                        .placeholder(R.drawable.ic_image_placeholder)
                        .error(R.drawable.ic_image_error)
                        .transition(DrawableTransitionOptions.withCrossFade(200))
                        .fitCenter().diskCacheStrategy(DiskCacheStrategy.AUTOMATIC).listener(glideListener).into(imageView);
            }
        } else { // Auto-play or non-fullscreen initial load (where loadForPotentialFullscreen is false)
            Log.d(TAG, "Glide: Loading image with automatic sizing (auto-play or non-fullscreen initial): " + finalUrl);
            Glide.with(this)
                    .load(finalUrl)
                    .placeholder(R.drawable.ic_image_placeholder)
                    .error(R.drawable.ic_image_error)
                    .transition(DrawableTransitionOptions.withCrossFade(200))
                    .fitCenter()
                    .diskCacheStrategy(DiskCacheStrategy.AUTOMATIC)
                    .listener(glideListener)
                    .into(imageView);
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
            Log.d(TAG, "exitFullscreen: Applying user's specified non-fullscreen constraints.");
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
        Log.d(TAG, "onDestroyView called");

        if (isAutoPlaying) {
            stopAutoPlay();
        } else {
            if (autoPlayImageRunnable != null) {
                if (mainThreadHandler != null) {
                    mainThreadHandler.removeCallbacks(autoPlayImageRunnable);
                } else if (imageView != null) {
                    imageView.removeCallbacks(autoPlayImageRunnable);
                }
                // autoPlayImageRunnable = null; // Nullify in stopAutoPlay or here
            }
        }
        // Ensure autoPlayImageRunnable is nulled if not handled by stopAutoPlay
        autoPlayImageRunnable = null;


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
        originalBottomContainerLayoutParams = null;
        downloadProgressBar = null;
        downloadProgressText = null;
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
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION_FOR_APK);
            } else {
                proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
            }
        } else {
            proceedWithApkDownload(pendingApkUrl, pendingApkFileName);
        }
    }

    private void proceedWithApkDownload(String url, String fileName) {
        if (!isAdded() || getActivity() == null) {
            Log.w(TAG, "proceedWithApkDownload: Fragment not added or activity is null.");
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (downloadProgressText != null) downloadProgressText.setVisibility(View.GONE);
            return;
        }

        if (apkDownloader != null) {
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.VISIBLE);
            if (downloadProgressText != null) {
                downloadProgressText.setText("准备下载...");
                downloadProgressText.setVisibility(View.VISIBLE);
            }

            apkDownloader.downloadAndInstallApk(url, fileName, getActivity(), new ApkDownloader.Callback() {
                @Override
                public void onSuccess(File apkFile) {
                    Log.d(TAG, "APK Downloaded Successfully. File: " + apkFile.getAbsolutePath());
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "APK 下载成功，准备安装", Toast.LENGTH_SHORT).show();
                        if (downloadProgressBar != null)
                            downloadProgressBar.setVisibility(View.GONE);
                        if (downloadProgressText != null) {
                            downloadProgressText.setVisibility(View.GONE);
                        }
                    }
                }

                @Override
                public void onFailure(String errorMessage) {
                    Log.e(TAG, "APK Download Failed: " + errorMessage);
                    if (isAdded()) {
                        Toast.makeText(requireContext(), "下载失败: " + errorMessage, Toast.LENGTH_LONG).show();
                        if (downloadProgressBar != null)
                            downloadProgressBar.setVisibility(View.GONE);
                        if (downloadProgressText != null) {
                            downloadProgressText.setText("下载失败");
                        }
                    }
                }

                @Override
                public void onProgress(int progressPercentage) {
                    Log.d(TAG, "APK Download Progress: " + progressPercentage + "%");
                    if (isAdded()) {
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
                    }
                }
            });
        } else {
            Log.e(TAG, "ApkDownloader not initialized!");
            if (isAdded() && getContext() != null) {
                Toast.makeText(getContext(), "下载器服务未准备好", Toast.LENGTH_SHORT).show();
            }
            if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
            if (downloadProgressText != null) downloadProgressText.setVisibility(View.GONE);
        }
    }


    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERMISSION_FOR_APK) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (isAdded() && getContext() != null) {
                    Toast.makeText(getContext(), "存储权限已授予，开始下载...", Toast.LENGTH_SHORT).show();
                }
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
                if (downloadProgressBar != null) downloadProgressBar.setVisibility(View.GONE);
                if (downloadProgressText != null) {
                    downloadProgressText.setText("权限被拒绝");
                }
            }
            pendingApkUrl = null;
            pendingApkFileName = null;
        }
    }
}
