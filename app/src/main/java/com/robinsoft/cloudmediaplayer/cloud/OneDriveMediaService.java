package com.robinsoft.cloudmediaplayer.cloud;

import android.app.Activity;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.microsoft.identity.client.AcquireTokenParameters;
import com.microsoft.identity.client.AcquireTokenSilentParameters;
import com.microsoft.identity.client.AuthenticationCallback;
import com.microsoft.identity.client.IAccount;
import com.microsoft.identity.client.IAuthenticationResult;
import com.microsoft.identity.client.IPublicClientApplication;
import com.microsoft.identity.client.ISingleAccountPublicClientApplication;
import com.microsoft.identity.client.PublicClientApplication;
import com.microsoft.identity.client.exception.MsalException;
import com.robinsoft.cloudmediaplayer.R;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OneDriveMediaService implements CloudMediaService {
    // Graph API 需要的权限 & authority
    private static final String[] SCOPES = {"https://graph.microsoft.com/Files.Read"};
    private static final String AUTHORITY = "https://login.microsoftonline.com/common";
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
                    public void onCreated(@NonNull ISingleAccountPublicClientApplication application) {
                        msalApp = application;
                        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
                            @Override
                            public void onAccountLoaded(IAccount account) {
                                if (account != null) {
                                    // 异步静默续签
                                    AcquireTokenSilentParameters silentParams =
                                            new AcquireTokenSilentParameters.Builder()
                                                    .forAccount(account)
                                                    .fromAuthority(AUTHORITY)
                                                    .withScopes(Arrays.asList(SCOPES))
                                                    .withCallback(new AuthenticationCallback() {
                                                        @Override
                                                        public void onSuccess(@NonNull IAuthenticationResult result) {
                                                            accessToken = result.getAccessToken();
                                                            activity.runOnUiThread(callback::onSuccess);
                                                        }

                                                        @Override
                                                        public void onError(@NonNull MsalException exception) {
                                                            // 如果账户不匹配，先登出
                                                            if ("current_account_mismatch".equals(exception.getErrorCode())) {
                                                                msalApp.signOut(new ISingleAccountPublicClientApplication.SignOutCallback() {
                                                                    @Override
                                                                    public void onSignOut() {
                                                                    }

                                                                    @Override
                                                                    public void onError(@NonNull MsalException msalEx) {
                                                                    }
                                                                });
                                                            }
                                                            // 没拿到 Token，走交互式
                                                            activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                                                        }

                                                        @Override
                                                        public void onCancel() {
                                                            activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                                                        }
                                                    })
                                                    .build();
                                    msalApp.acquireTokenSilentAsync(silentParams);
                                } else {
                                    // 无缓存，交互式登录
                                    activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                                }
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                // 获取账户失败，交互式登录
                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                            }

                            @Override
                            public void onAccountChanged(IAccount priorAccount, IAccount currentAccount) {
                                // 账户变动，重新登录
                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        activity.runOnUiThread(() -> callback.onError(exception));
                    }
                }
        );
    }

    private void startInteractiveSignIn(Activity activity, AuthCallback callback) {
        AcquireTokenParameters params = new AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .fromAuthority(AUTHORITY)
                .withScopes(Arrays.asList(SCOPES))
                .withCallback(new AuthenticationCallback() {
                    @Override
                    public void onSuccess(@NonNull IAuthenticationResult result) {
                        accessToken = result.getAccessToken();
                        activity.runOnUiThread(callback::onSuccess);
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        activity.runOnUiThread(() -> callback.onError(exception));
                    }

                    @Override
                    public void onCancel() {
                        activity.runOnUiThread(() -> callback.onError(new Exception("用户取消登录")));
                    }
                })
                .build();
        msalApp.acquireToken(params);
    }

    /**
     * 改为按文件夹 ID 拉取子项，避免路径拼装问题
     *
     * @param folderId 根目录传 null 或空字符串
     */
    @Override

    public LiveData<List<CloudMediaItem>> listMedia(String folderId) {
        // 假设 mediaLiveData 是在类中定义好的 MutableLiveData<List<CloudMediaItem>>
        MutableLiveData<List<CloudMediaItem>> mediaLiveData = new MutableLiveData<>();

        new Thread(() -> {
            List<CloudMediaItem> list = new ArrayList<>();
            try {
                String url;
                if (folderId == null || folderId.trim().isEmpty()) {
                    url = "https://graph.microsoft.com/v1.0/me/drive/root/children";
                } else {
                    url = "https://graph.microsoft.com/v1.0/me/drive/items/" + folderId + "/children";
                }

                OkHttpClient client = new OkHttpClient();
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + accessToken)
                        .build();
                Response response = client.newCall(request).execute();
                if (!response.isSuccessful()) {
                    throw new IOException("Unexpected code " + response);
                }

                JsonArray items = JsonParser.parseString(response.body().string())
                        .getAsJsonObject()
                        .getAsJsonArray("value");

                for (JsonElement e : items) {
                    JsonObject obj = e.getAsJsonObject();
                    String id = obj.get("id").getAsString();
                    String name = obj.get("name").getAsString();
                    String downloadUrl = obj.has("@microsoft.graph.downloadUrl")
                            ? obj.get("@microsoft.graph.downloadUrl").getAsString()
                            : obj.get("webUrl").getAsString();

                    CloudMediaItem.MediaType type;
                    if (obj.has("folder")) {
                        type = CloudMediaItem.MediaType.FOLDER;
                    } else if (obj.has("file")) {
                        JsonObject fileObj = obj.getAsJsonObject("file");
                        String mime = fileObj.get("mimeType").getAsString();
                        String lowerName = name.toLowerCase(Locale.ROOT);

                        if ("application/vnd.android.package-archive".equals(mime)
                                || lowerName.endsWith(".apk")) {
                            type = CloudMediaItem.MediaType.APK;
                        } else if (mime.startsWith("image/")) {
                            type = CloudMediaItem.MediaType.IMAGE;
                        } else if (mime.startsWith("video/")) {
                            type = CloudMediaItem.MediaType.VIDEO;
                        } else {
                            type = CloudMediaItem.MediaType.FILE;
                        }
                    } else {
                        // 既不是文件也不是文件夹，归为普通文件
                        type = CloudMediaItem.MediaType.FILE;
                    }

                    list.add(new CloudMediaItem(id, name, downloadUrl, type));
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
