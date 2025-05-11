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

import com.robinsoft.cloudmediaplayer.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

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
            List<CloudMediaItem> list = new ArrayList<>();
            try {
                // 构造 REST 请求 URL
                String url = "https://graph.microsoft.com/v1.0/me/drive/root:" +
                        folderPath +
                        ":/children";

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }

                // 解析 JSON
                String json = response.body().string();
                JsonObject root = new Gson().fromJson(json, JsonObject.class);
                JsonArray items = root.getAsJsonArray("value");

                for (JsonElement elem : items) {
                    JsonObject obj = elem.getAsJsonObject();
                    String id = obj.get("id").getAsString();
                    String name = obj.get("name").getAsString();
                    String webUrl = obj.get("webUrl").getAsString();

                    boolean isVideo = false;
                    if (obj.has("file") && obj.get("file").isJsonObject()) {
                        JsonObject fileObj = obj.getAsJsonObject("file");
                        if (fileObj.has("mimeType")) {
                            String mime = fileObj.get("mimeType").getAsString();
                            isVideo = mime.startsWith("video");
                        }
                    }

                    list.add(new CloudMediaItem(
                            id,
                            name,
                            webUrl,
                            isVideo ? CloudMediaItem.MediaType.VIDEO
                                    : CloudMediaItem.MediaType.IMAGE
                    ));
                }

                mediaLiveData.postValue(list);
            } catch (Exception e) {
                e.printStackTrace();
                mediaLiveData.postValue(null);
            }
        }).start();
        return mediaLiveData;
    }
}
