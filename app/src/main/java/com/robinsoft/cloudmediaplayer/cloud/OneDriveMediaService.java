package com.robinsoft.cloudmediaplayer.cloud;

import android.app.Activity;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.JsonParser;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.IPublicClientApplication;
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
                        // 使用新版 acquireToken API 登录
                        msalApp.acquireToken(
                                activity,
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
                        // 创建 MSAL 实例失败
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
                // --- 1）选择正确的 URL ---
                String url;
                if (folderPath == null || folderPath.trim().isEmpty() || "/".equals(folderPath)) {
                    // 根目录
                    url = "https://graph.microsoft.com/v1.0/me/drive/root/children";
                } else {
                    // 子目录：剔除开头的斜杠，拼成 root:/path:/children
                    String path = folderPath.startsWith("/")
                            ? folderPath.substring(1)
                            : folderPath;
                    url = "https://graph.microsoft.com/v1.0/me/drive/root:/"
                            + path
                            + ":/children";
                }

                // --- 2）发请求 ---
                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();

                Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }

                // --- 3）解析返回的 JSON 并分类 ---
                JsonArray items = JsonParser
                        .parseString(response.body().string())
                        .getAsJsonObject()
                        .getAsJsonArray("value");

                for (JsonElement e : items) {
                    JsonObject obj = e.getAsJsonObject();
                    String id   = obj.get("id").getAsString();
                    String name = obj.get("name").getAsString();
                    String webUrl = obj.get("webUrl").getAsString();

                    // 目录
                    if (obj.has("folder")) {
                        list.add(new CloudMediaItem(id, name, webUrl, CloudMediaItem.MediaType.FOLDER));
                        continue;
                    }
                    // 快捷方式
                    if (obj.has("remoteItem")) {
                        list.add(new CloudMediaItem(id, name, webUrl, CloudMediaItem.MediaType.FILE));
                        continue;
                    }
                    // 普通文件：按 mimeType 判断
                    if (obj.has("file")) {
                        String mime = obj.getAsJsonObject("file")
                                .get("mimeType")
                                .getAsString();
                        if (mime.startsWith("image/")) {
                            list.add(new CloudMediaItem(id, name, webUrl, CloudMediaItem.MediaType.IMAGE));
                        } else if (mime.startsWith("video/")) {
                            list.add(new CloudMediaItem(id, name, webUrl, CloudMediaItem.MediaType.VIDEO));
                        } else {
                            list.add(new CloudMediaItem(id, name, webUrl, CloudMediaItem.MediaType.FILE));
                        }
                        continue;
                    }
                    // 兜底
                    list.add(new CloudMediaItem(id, name, webUrl, CloudMediaItem.MediaType.FILE));
                }

                mediaLiveData.postValue(list);
            } catch (Exception ex) {
                ex.printStackTrace();
                mediaLiveData.postValue(null);
            }
        }).start();
        return mediaLiveData;
    }


}
