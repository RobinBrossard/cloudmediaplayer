package com.robinsoft.cloudmediaplayer.cloud;

import android.app.Activity;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.exception.MsalException;

import com.microsoft.graph.requests.GraphServiceClient;
import com.microsoft.graph.models.DriveItem;
import com.microsoft.graph.requests.DriveItemCollectionPage;
import com.microsoft.graph.authentication.IAuthenticationProvider;

import com.robinsoft.cloudmediaplayer.R;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class OneDriveMediaService implements CloudMediaService {
    private final MutableLiveData<List<CloudMediaItem>> mediaLiveData = new MutableLiveData<>();
    private ISingleAccountPublicClientApplication msalApp;
    private String accessToken;

    @Override
    public void authenticate(Activity activity, AuthCallback callback) {
        PublicClientApplication.createSingleAccountPublicClientApplication(
                activity.getApplicationContext(),
                R.raw.auth_config_single_account,
                new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                    @Override
                    public void onCreated(ISingleAccountPublicClientApplication application) {
                        msalApp = application;
                        msalApp.signIn(
                                activity,
                                null,
                                new String[]{"Files.Read"},
                                new AuthenticationCallback() {
                                    @Override
                                    public void onSuccess(IAuthenticationResult result) {
                                        accessToken = result.getAccessToken();
                                        callback.onSuccess();
                                    }
                                    @Override
                                    public void onError(MsalException exception) {
                                        callback.onError(exception);
                                    }
                                    @Override
                                    public void onCancel() {
                                        callback.onError(new Exception("用户取消登录"));
                                    }
                                }
                        );
                    }
                    @Override
                    public void onError(MsalException exception) {
                        callback.onError(exception);
                    }
                }
        );
    }

    @Override
    public LiveData<List<CloudMediaItem>> listMedia(String folderPath) {
        new Thread(() -> {
            try {
                // 用新的 IAuthenticationProvider 实现
                IAuthenticationProvider authProvider = new IAuthenticationProvider() {
                    @Override
                    public CompletableFuture<String> getAuthorizationTokenAsync(URL requestUrl) {
                        // 直接返回已经获取到的 accessToken
                        return CompletableFuture.completedFuture(accessToken);
                    }
                };

                GraphServiceClient<?> graphClient = GraphServiceClient
                        .builder()
                        .authenticationProvider(authProvider)
                        .buildClient();

                DriveItemCollectionPage page = graphClient
                        .me()
                        .drive()
                        .root()
                        .itemWithPath(folderPath)
                        .children()
                        .buildRequest()
                        .get();

                List<CloudMediaItem> list = new ArrayList<>();
                for (DriveItem item : page.getCurrentPage()) {
                    boolean isVideo = item.file != null && item.file.mimeType.startsWith("video");
                    list.add(new CloudMediaItem(
                            item.id,
                            item.name,
                            item.webUrl,
                            isVideo ? CloudMediaItem.MediaType.VIDEO : CloudMediaItem.MediaType.IMAGE
                    ));
                }
                mediaLiveData.postValue(list);
            } catch (Exception e) {
                mediaLiveData.postValue(null);
            }
        }).start();
        return mediaLiveData;
    }
}
