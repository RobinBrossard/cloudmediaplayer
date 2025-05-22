package com.robinsoft.cloudmediaplayer.cloud;

import android.app.Activity;
import android.util.Log;

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
import com.microsoft.identity.client.exception.MsalUiRequiredException;
import com.robinsoft.cloudmediaplayer.R;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class OneDriveMediaService implements CloudMediaService {
    private static final String TAG = "OneDriveMediaService";
    // Graph API 需要的权限 & authority
    private static final String[] SCOPES = {"https://graph.microsoft.com/Files.Read"};
    private static final String AUTHORITY = "https://login.microsoftonline.com/common";

    private ISingleAccountPublicClientApplication msalApp;
    private volatile String accessToken; // 修改为 volatile
    private IAccount currentAccount; // 保存当前账户信息

    @Override
    public void authenticate(Activity activity, AuthCallback callback) {
        Log.d(TAG, "authenticate called");
        PublicClientApplication.createSingleAccountPublicClientApplication(
                activity.getApplicationContext(),
                R.raw.auth_config_single_account, // 确保这个MSAL配置文件存在且正确
                new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                    @Override
                    public void onCreated(@NonNull ISingleAccountPublicClientApplication application) {
                        msalApp = application;
                        Log.d(TAG, "MSAL application created. Getting current account.");
                        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
                            @Override
                            public void onAccountLoaded(IAccount account) {
                                currentAccount = account; // 保存账户信息
                                if (account != null) {
                                    Log.d(TAG, "Account loaded: " + account.getUsername() + ". Attempting silent token acquisition.");
                                    acquireTokenSilentInternal(activity, callback, account);
                                } else {
                                    Log.d(TAG, "No cached account found, starting interactive sign-in.");
                                    activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                                }
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                Log.e(TAG, "Error getting current account.", exception);
                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                            }

                            @Override
                            public void onAccountChanged(IAccount priorAccount, IAccount currentAccount) {
                                Log.d(TAG, "Account changed. Old: " + (priorAccount != null ? priorAccount.getUsername() : "null") +
                                        ", New: " + (currentAccount != null ? currentAccount.getUsername() : "null") + ". Re-authenticating.");
                                OneDriveMediaService.this.currentAccount = currentAccount; // 更新账户
                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        Log.e(TAG, "Error creating MSAL application.", exception);
                        activity.runOnUiThread(() -> callback.onError(exception));
                    }
                }
        );
    }

    // 内部静默登录/令牌刷新逻辑，主要由 authenticate 调用
    private void acquireTokenSilentInternal(Activity activity, AuthCallback uiCallback, @NonNull IAccount accountToRefresh) {
        Log.d(TAG, "Attempting to acquire token silently for account: " + accountToRefresh.getUsername());
        AcquireTokenSilentParameters silentParams =
                new AcquireTokenSilentParameters.Builder()
                        .forAccount(accountToRefresh)
                        .fromAuthority(AUTHORITY)
                        .withScopes(Arrays.asList(SCOPES))
                        .withCallback(new AuthenticationCallback() {
                            @Override
                            public void onSuccess(@NonNull IAuthenticationResult result) {
                                accessToken = result.getAccessToken();
                                currentAccount = result.getAccount(); // 确保 currentAccount 更新
                                Log.i(TAG, "Successfully acquired token silently via acquireTokenSilentInternal.");
                                if (activity != null && uiCallback != null) {
                                    activity.runOnUiThread(uiCallback::onSuccess);
                                }
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                Log.w(TAG, "Silent token acquisition failed via acquireTokenSilentInternal. ErrorCode: " + exception.getErrorCode(), exception);
                                if (msalApp != null && "current_account_mismatch".equals(exception.getErrorCode())) {
                                    Log.d(TAG, "current_account_mismatch error. Signing out and then attempting interactive sign-in.");
                                    msalApp.signOut(new ISingleAccountPublicClientApplication.SignOutCallback() {
                                        @Override
                                        public void onSignOut() { // 必须实现 onSignOut
                                            Log.d(TAG, "Signed out due to account mismatch.");
                                            OneDriveMediaService.this.currentAccount = null;
                                            accessToken = null;
                                            if (activity != null && uiCallback != null) {
                                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
                                            }
                                        }

                                        @Override
                                        public void onError(@NonNull MsalException msalEx) {
                                            Log.e(TAG, "Error during sign out for mismatch.", msalEx);
                                            if (activity != null && uiCallback != null) { // 即使登出失败，也尝试交互式登录
                                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
                                            }
                                        }
                                    });
                                    return; // 避免重复调用 startInteractiveSignIn
                                }
                                // 其他静默获取失败，（例如 MsalUiRequiredException）需要交互式登录
                                Log.d(TAG, "Falling back to interactive sign-in after silent failure.");
                                if (activity != null && uiCallback != null) {
                                    activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
                                }
                            }

                            @Override
                            public void onCancel() {
                                Log.w(TAG, "Silent token acquisition cancelled via acquireTokenSilentInternal.");
                                if (activity != null && uiCallback != null) {
                                    // 使用通用 Exception 替代 MsalException 构造函数
                                    activity.runOnUiThread(() -> uiCallback.onError(new Exception("Silent token acquisition cancelled.")));
                                }
                            }
                        })
                        .build();
        if (msalApp != null) {
            msalApp.acquireTokenSilentAsync(silentParams);
        } else {
            Log.e(TAG, "MSAL App is null, cannot acquire token silently via acquireTokenSilentInternal.");
            if (activity != null && uiCallback != null) {
                activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
            }
        }
    }

    private void startInteractiveSignIn(Activity activity, AuthCallback callback) {
        if (msalApp == null) {
            Log.e(TAG, "MSAL App is null, cannot start interactive sign-in. Authenticate must be called first.");
            if (activity != null && callback != null) {
                activity.runOnUiThread(() -> callback.onError(new IllegalStateException("MSAL App not initialized.")));
            }
            return;
        }
        Log.d(TAG, "Starting interactive sign-in.");
        AcquireTokenParameters params = new AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .fromAuthority(AUTHORITY)
                .withScopes(Arrays.asList(SCOPES))
                .withCallback(new AuthenticationCallback() {
                    @Override
                    public void onSuccess(@NonNull IAuthenticationResult result) {
                        accessToken = result.getAccessToken();
                        currentAccount = result.getAccount(); // 保存/更新账户信息
                        Log.i(TAG, "Successfully acquired token interactively.");
                        if (activity != null && callback != null) {
                            activity.runOnUiThread(callback::onSuccess);
                        }
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        Log.e(TAG, "Interactive sign-in failed.", exception);
                        if (activity != null && callback != null) {
                            activity.runOnUiThread(() -> callback.onError(exception));
                        }
                    }

                    @Override
                    public void onCancel() {
                        Log.w(TAG, "Interactive sign-in cancelled by user.");
                        if (activity != null && callback != null) {
                            // 使用通用 Exception
                            activity.runOnUiThread(() -> callback.onError(new Exception("用户取消登录")));
                        }
                    }
                })
                .build();
        msalApp.acquireToken(params);
    }


    @Override
    public LiveData<List<CloudMediaItem>> listMedia(final String folderId) {
        final MutableLiveData<List<CloudMediaItem>> liveDataToReturn = new MutableLiveData<>();
        Log.d(TAG, "listMedia called for folderId: " + folderId);

        if (msalApp == null) {
            Log.e(TAG, "MSAL App not initialized for listMedia. UI should call authenticate first.");
            liveDataToReturn.postValue(null);
            return liveDataToReturn;
        }

        if (currentAccount == null) {
            Log.w(TAG, "No account available for listMedia. UI should trigger authenticate flow.");
            liveDataToReturn.postValue(null); // 提示UI需要认证
            return liveDataToReturn;
        }

        Log.d(TAG, "Attempting silent token acquisition before Graph API call for folderId: " + folderId);
        AcquireTokenSilentParameters silentParams =
                new AcquireTokenSilentParameters.Builder()
                        .forAccount(currentAccount)
                        .fromAuthority(AUTHORITY)
                        .withScopes(Arrays.asList(SCOPES))
                        .withCallback(new AuthenticationCallback() {
                            @Override
                            public void onSuccess(@NonNull IAuthenticationResult authenticationResult) {
                                accessToken = authenticationResult.getAccessToken(); // 更新令牌
                                currentAccount = authenticationResult.getAccount(); // 更新账户信息
                                Log.i(TAG, "Token refreshed successfully for listMedia. Proceeding with Graph API call.");
                                executeGraphApiCall(folderId, accessToken, liveDataToReturn);
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                Log.e(TAG, "Silent token acquisition failed for listMedia. ErrorCode: " + exception.getErrorCode(), exception);
                                if (exception instanceof MsalUiRequiredException) {
                                    Log.w(TAG, "UI interaction required for token. listMedia cannot proceed. UI should call authenticate().");
                                } else {
                                    Log.e(TAG, "Other MSAL error during silent token acquisition for listMedia.");
                                }
                                liveDataToReturn.postValue(null); // 通知UI加载失败，UI层应引导用户重新认证
                            }

                            @Override
                            public void onCancel() {
                                Log.w(TAG, "Silent token acquisition for listMedia cancelled.");
                                // 使用通用 Exception
                                liveDataToReturn.postValue(null); // 也可以通过 postError(new Exception("...")) 如果 LiveData 支持错误状态
                            }
                        })
                        .build();
        msalApp.acquireTokenSilentAsync(silentParams);

        return liveDataToReturn;
    }

    private void executeGraphApiCall(
            final String folderId,
            final String tokenToUse,
            final MutableLiveData<List<CloudMediaItem>> liveDataResult) {

        new Thread(() -> {
            List<CloudMediaItem> list = new ArrayList<>();
            Log.d(TAG, "Executing Graph API call for folderId: " + folderId);
            try {
                String url;
                if (folderId == null || folderId.trim().isEmpty()) {
                    url = "https://graph.microsoft.com/v1.0/me/drive/root/children";
                } else {
                    url = "https://graph.microsoft.com/v1.0/me/drive/items/" + folderId + "/children";
                }

                OkHttpClient client = new OkHttpClient.Builder()
                        .connectTimeout(15, TimeUnit.SECONDS)
                        .readTimeout(30, TimeUnit.SECONDS)
                        .build();
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + tokenToUse)
                        .build();
                Response response = client.newCall(request).execute();

                if (response.code() == 401) {
                    Log.e(TAG, "Graph API returned 401. Token (prefix): " +
                            (tokenToUse != null && tokenToUse.length() > 20 ? tokenToUse.substring(0, 20) : "null_or_short") +
                            "... URL: " + url +
                            ". This indicates the token from silent refresh was still not accepted. UI should prompt full re-login.");
                    liveDataResult.postValue(null); // 通知UI认证失败
                    return;
                }

                if (!response.isSuccessful()) {
                    String responseBodyString = response.body() != null ? response.body().string() : "null_body";
                    if (responseBodyString.length() > 500) {
                        responseBodyString = responseBodyString.substring(0, 497) + "...";
                    }
                    Log.e(TAG, "Graph API request failed. Code: " + response.code() + ", URL: " + url + ", Body: " + responseBodyString);
                    throw new IOException("Unexpected code " + response + ". Response: " + responseBodyString);
                }

                String responseBody = response.body().string(); // 确保只调用一次 string()
                JsonArray items = JsonParser.parseString(responseBody)
                        .getAsJsonObject()
                        .getAsJsonArray("value");

                for (JsonElement e : items) {
                    JsonObject obj = e.getAsJsonObject();
                    String id = obj.get("id").getAsString();
                    String name = obj.get("name").getAsString();
                    String downloadUrl = obj.has("@microsoft.graph.downloadUrl")
                            ? obj.get("@microsoft.graph.downloadUrl").getAsString()
                            : obj.get("webUrl").getAsString();

                    String lmText = obj.has("lastModifiedDateTime")
                            ? obj.get("lastModifiedDateTime").getAsString()
                            : null;
                    OffsetDateTime lastModified = lmText != null
                            ? OffsetDateTime.parse(lmText)
                            : OffsetDateTime.MIN;

                    CloudMediaItem.MediaType type;
                    if (obj.has("folder")) {
                        type = CloudMediaItem.MediaType.FOLDER;
                    } else if (obj.has("file")) {
                        JsonObject fileObj = obj.getAsJsonObject("file");
                        String mime = fileObj.get("mimeType").getAsString();
                        String lower = name.toLowerCase(Locale.ROOT);
                        if ("application/vnd.android.package-archive".equals(mime)
                                || lower.endsWith(".apk")) {
                            type = CloudMediaItem.MediaType.APK;
                        } else if (mime.startsWith("image/")) {
                            type = CloudMediaItem.MediaType.IMAGE;
                        } else if (mime.startsWith("video/")) {
                            type = CloudMediaItem.MediaType.VIDEO;
                        } else {
                            type = CloudMediaItem.MediaType.FILE;
                        }
                    } else {
                        type = CloudMediaItem.MediaType.FILE; // Default or unknown
                    }
                    list.add(new CloudMediaItem(id, name, downloadUrl, type, lastModified));
                }
                liveDataResult.postValue(list);
            } catch (Exception ex) {
                Log.e(TAG, "Error during Graph API call or JSON parsing for folderId: " + folderId, ex);
                liveDataResult.postValue(null);
            }
        }).start();
    }
}