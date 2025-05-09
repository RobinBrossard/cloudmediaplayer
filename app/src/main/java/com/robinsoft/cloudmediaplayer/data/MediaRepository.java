package com.robinsoft.cloudmediaplayer.data;

import android.app.Activity;
import androidx.lifecycle.LiveData;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaItem;
import com.robinsoft.cloudmediaplayer.cloud.CloudMediaService;
import com.robinsoft.cloudmediaplayer.cloud.CloudProviderFactory;
import com.robinsoft.cloudmediaplayer.cloud.CloudProviderFactory.Platform;

import java.util.List;

public class MediaRepository {
    private final CloudMediaService service;

    public MediaRepository(Platform platform) {
        // 改为调用 create 方法
        service = CloudProviderFactory.create(platform);
    }

    public void authenticate(Activity activity, CloudMediaService.AuthCallback cb) {
        service.authenticate(activity, cb);
    }

    public LiveData<List<CloudMediaItem>> listMedia(String folder) {
        return service.listMedia(folder);
    }
}
