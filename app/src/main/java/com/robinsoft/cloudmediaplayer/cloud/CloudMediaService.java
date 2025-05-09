package com.robinsoft.cloudmediaplayer.cloud;
import android.app.Activity;
import android.content.Context;
import androidx.lifecycle.LiveData;
import java.util.List;
public interface CloudMediaService {
    /** 把这里的 ctx 改成 Activity */
    void authenticate(Activity activity, AuthCallback cb);
    LiveData<List<CloudMediaItem>> listMedia(String folderPath);
    interface AuthCallback { void onSuccess(); void onError(Throwable t); }
}
