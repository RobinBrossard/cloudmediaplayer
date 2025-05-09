package com.robinsoft.cloudmediaplayer.ui;

import android.app.Activity;
import android.app.Application;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;

import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import com.robinsoft.cloudmediaplayer.cloud.CloudProviderFactory;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.data.MediaRepository;

import java.util.List;

public class MediaViewModel extends AndroidViewModel {
    private final MediaRepository repo;

    public MediaViewModel(@NonNull Application app) {
        super(app);
        repo = new MediaRepository(CloudProviderFactory.Platform.ONEDRIVE);
    }

    /**
     * 把 authenticate 改成接受 Activity，
     * 这样才能真正发起 MSAL 的交互式登录。
     */
    public void authenticate(Activity activity, CloudMediaService.AuthCallback cb) {
        repo.authenticate(activity, cb);
    }

    public LiveData<List<CloudMediaItem>> getMediaList(String folder) {
        return repo.listMedia(folder);
    }
}
