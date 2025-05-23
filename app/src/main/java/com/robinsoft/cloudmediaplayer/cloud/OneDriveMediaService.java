package com.robinsoft.cloudmediaplayer.cloud; // 请替换为你的实际包名

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
import com.microsoft.identity.client.exception.MsalUiRequiredException;
import com.robinsoft.cloudmediaplayer.R;
import com.robinsoft.cloudmediaplayer.utils.FLog;

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
        FLog.d(TAG, "authenticate called"); // --- 修改: Log -> FLog ---
        PublicClientApplication.createSingleAccountPublicClientApplication(
                activity.getApplicationContext(),
                R.raw.auth_config_single_account, // 确保这个MSAL配置文件存在且正确
                new IPublicClientApplication.ISingleAccountApplicationCreatedListener() {
                    @Override
                    public void onCreated(@NonNull ISingleAccountPublicClientApplication application) {
                        msalApp = application;
                        FLog.d(TAG, "MSAL application created. Getting current account."); // --- 修改: Log -> FLog ---
                        msalApp.getCurrentAccountAsync(new ISingleAccountPublicClientApplication.CurrentAccountCallback() {
                            @Override
                            public void onAccountLoaded(IAccount account) {
                                currentAccount = account; // 保存账户信息
                                if (account != null) {
                                    FLog.d(TAG, "Account loaded: " + account.getUsername() + ". Attempting silent token acquisition."); // --- 修改: Log -> FLog ---
                                    acquireTokenSilentInternal(activity, callback, account);
                                } else {
                                    FLog.d(TAG, "No cached account found, starting interactive sign-in."); // --- 修改: Log -> FLog ---
                                    activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                                }
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                FLog.e(TAG, "Error getting current account.", exception); // --- 修改: Log -> FLog ---
                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                            }

                            @Override
                            public void onAccountChanged(IAccount priorAccount, IAccount currentAccount) {
                                FLog.d(TAG, "Account changed. Old: " + (priorAccount != null ? priorAccount.getUsername() : "null") + // --- 修改: Log -> FLog ---
                                        ", New: " + (currentAccount != null ? currentAccount.getUsername() : "null") + ". Re-authenticating.");
                                OneDriveMediaService.this.currentAccount = currentAccount; // 更新账户
                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, callback));
                            }
                        });
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        FLog.e(TAG, "Error creating MSAL application.", exception); // --- 修改: Log -> FLog ---
                        activity.runOnUiThread(() -> callback.onError(exception));
                    }
                }
        );
    }

    // 内部静默登录/令牌刷新逻辑，主要由 authenticate 调用
    private void acquireTokenSilentInternal(Activity activity, AuthCallback uiCallback, @NonNull IAccount accountToRefresh) {
        FLog.d(TAG, "Attempting to acquire token silently for account: " + accountToRefresh.getUsername()); // --- 修改: Log -> FLog ---
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
                                FLog.i(TAG, "Successfully acquired token silently via acquireTokenSilentInternal."); // --- 修改: Log -> FLog ---
                                if (activity != null && uiCallback != null) {
                                    activity.runOnUiThread(uiCallback::onSuccess);
                                }
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                FLog.w(TAG, "Silent token acquisition failed via acquireTokenSilentInternal. ErrorCode: " + exception.getErrorCode(), exception); // --- 修改: Log -> FLog ---
                                if (msalApp != null && "current_account_mismatch".equals(exception.getErrorCode())) {
                                    FLog.d(TAG, "current_account_mismatch error. Signing out and then attempting interactive sign-in."); // --- 修改: Log -> FLog ---
                                    msalApp.signOut(new ISingleAccountPublicClientApplication.SignOutCallback() {
                                        @Override
                                        public void onSignOut() { // 必须实现 onSignOut
                                            FLog.d(TAG, "Signed out due to account mismatch."); // --- 修改: Log -> FLog ---
                                            OneDriveMediaService.this.currentAccount = null;
                                            accessToken = null;
                                            if (activity != null && uiCallback != null) {
                                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
                                            }
                                        }

                                        @Override
                                        public void onError(@NonNull MsalException msalEx) {
                                            FLog.e(TAG, "Error during sign out for mismatch.", msalEx); // --- 修改: Log -> FLog ---
                                            if (activity != null && uiCallback != null) { // 即使登出失败，也尝试交互式登录
                                                activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
                                            }
                                        }
                                    });
                                    return; // 避免重复调用 startInteractiveSignIn
                                }
                                // 其他静默获取失败，（例如 MsalUiRequiredException）需要交互式登录
                                FLog.d(TAG, "Falling back to interactive sign-in after silent failure."); // --- 修改: Log -> FLog ---
                                if (activity != null && uiCallback != null) {
                                    activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
                                }
                            }

                            @Override
                            public void onCancel() {
                                FLog.w(TAG, "Silent token acquisition cancelled via acquireTokenSilentInternal."); // --- 修改: Log -> FLog ---
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
            FLog.e(TAG, "MSAL App is null, cannot acquire token silently via acquireTokenSilentInternal."); // --- 修改: Log -> FLog ---
            if (activity != null && uiCallback != null) {
                activity.runOnUiThread(() -> startInteractiveSignIn(activity, uiCallback));
            }
        }
    }

    private void startInteractiveSignIn(Activity activity, AuthCallback callback) {
        if (msalApp == null) {
            FLog.e(TAG, "MSAL App is null, cannot start interactive sign-in. Authenticate must be called first."); // --- 修改: Log -> FLog ---
            if (activity != null && callback != null) {
                activity.runOnUiThread(() -> callback.onError(new IllegalStateException("MSAL App not initialized.")));
            }
            return;
        }
        FLog.d(TAG, "Starting interactive sign-in."); // --- 修改: Log -> FLog ---
        AcquireTokenParameters params = new AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .fromAuthority(AUTHORITY)
                .withScopes(Arrays.asList(SCOPES))
                .withCallback(new AuthenticationCallback() {
                    @Override
                    public void onSuccess(@NonNull IAuthenticationResult result) {
                        accessToken = result.getAccessToken();
                        currentAccount = result.getAccount(); // 保存/更新账户信息
                        FLog.i(TAG, "Successfully acquired token interactively."); // --- 修改: Log -> FLog ---
                        if (activity != null && callback != null) {
                            activity.runOnUiThread(callback::onSuccess);
                        }
                    }

                    @Override
                    public void onError(@NonNull MsalException exception) {
                        FLog.e(TAG, "Interactive sign-in failed.", exception); // --- 修改: Log -> FLog ---
                        if (activity != null && callback != null) {
                            activity.runOnUiThread(() -> callback.onError(exception));
                        }
                    }

                    @Override
                    public void onCancel() {
                        FLog.w(TAG, "Interactive sign-in cancelled by user."); // --- 修改: Log -> FLog ---
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
        FLog.d(TAG, "listMedia called for folderId: " + folderId); // --- 修改: Log -> FLog ---

        if (msalApp == null) {
            FLog.e(TAG, "MSAL App not initialized for listMedia. UI should call authenticate first."); // --- 修改: Log -> FLog ---
            liveDataToReturn.postValue(null);
            return liveDataToReturn;
        }

        if (currentAccount == null) {
            FLog.w(TAG, "No account available for listMedia. UI should trigger authenticate flow."); // --- 修改: Log -> FLog ---
            liveDataToReturn.postValue(null); // 提示UI需要认证
            return liveDataToReturn;
        }

        FLog.d(TAG, "Attempting silent token acquisition before Graph API call for folderId: " + folderId); // --- 修改: Log -> FLog ---
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
                                FLog.i(TAG, "Token refreshed successfully for listMedia. Proceeding with Graph API call."); // --- 修改: Log -> FLog ---
                                executeGraphApiCall(folderId, accessToken, liveDataToReturn);
                            }

                            @Override
                            public void onError(@NonNull MsalException exception) {
                                FLog.e(TAG, "Silent token acquisition failed for listMedia. ErrorCode: " + exception.getErrorCode(), exception); // --- 修改: Log -> FLog ---
                                if (exception instanceof MsalUiRequiredException) {
                                    FLog.w(TAG, "UI interaction required for token. listMedia cannot proceed. UI should call authenticate()."); // --- 修改: Log -> FLog ---
                                } else {
                                    FLog.e(TAG, "Other MSAL error during silent token acquisition for listMedia."); // --- 修改: Log -> FLog ---
                                }
                                liveDataToReturn.postValue(null); // 通知UI加载失败，UI层应引导用户重新认证
                            }

                            @Override
                            public void onCancel() {
                                FLog.w(TAG, "Silent token acquisition for listMedia cancelled."); // --- 修改: Log -> FLog ---
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
            FLog.d(TAG, "Executing Graph API call for folderId: " + folderId); // --- 修改: Log -> FLog ---
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
                    FLog.e(TAG, "Graph API returned 401 (Unauthorized). Token (prefix): " + // --- 修改: Log -> FLog ---
                            (tokenToUse != null && tokenToUse.length() > 20 ? tokenToUse.substring(0, 20) : "null_or_short") +
                            "... URL: " + url +
                            ". This indicates the token was not accepted. UI should prompt full re-login.");
                    liveDataResult.postValue(null); // 通知UI认证失败
                    return;
                }

                if (!response.isSuccessful()) {
                    String responseBodyString = response.body() != null ? response.body().string() : "null_body";
                    // --- 修改开始: 增强对错误响应体的日志记录 ---
                    String loggableBody = responseBodyString;
                    if (responseBodyString.length() > 1024) { // 限制一下日志长度，避免过长
                        loggableBody = responseBodyString.substring(0, 1021) + "...";
                        FLog.w(TAG, "Graph API response body was very long, logging truncated version."); // --- 修改: Log -> FLog ---
                    }
                    FLog.e(TAG, "Graph API request failed. Code: " + response.code() + ", URL: " + url + ", Body: " + loggableBody); // --- 修改: Log -> FLog ---
                    // --- 修改结束 ---
                    liveDataResult.postValue(null); // Post null on failure so observer can react
                    return; // Return after posting null due to error
                }

                String responseBody = response.body().string(); // 确保只调用一次 string()
                JsonArray items = JsonParser.parseString(responseBody)
                        .getAsJsonObject()
                        .getAsJsonArray("value");

                for (JsonElement e : items) {
                    JsonObject obj = e.getAsJsonObject();
                    String id = obj.get("id").getAsString(); // 假设ID总是存在且为String
                    String name = obj.get("name").getAsString(); // 假设name总是存在且为String

                    String downloadUrl;
                    // --- 修改开始: 增强对downloadUrl缺失的日志记录 ---
                    if (obj.has("@microsoft.graph.downloadUrl")) {
                        downloadUrl = obj.get("@microsoft.graph.downloadUrl").getAsString();
                    } else {
                        // 尝试获取webUrl作为备用，但记录这是一个非理想状态
                        if (obj.has("webUrl")) {
                            downloadUrl = obj.get("webUrl").getAsString();
                            FLog.w(TAG, "Item '" + name + "' (ID: " + id + ") missing @microsoft.graph.downloadUrl. Using webUrl as fallback: " + downloadUrl); // --- 修改: Log -> FLog ---
                        } else {
                            downloadUrl = null; // 没有可用的URL
                            FLog.e(TAG, "Item '" + name + "' (ID: " + id + ") missing BOTH @microsoft.graph.downloadUrl AND webUrl. Cannot determine URL."); // --- 修改: Log -> FLog ---
                        }
                    }
                    // --- 修改结束 ---


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
                        // --- 修改开始: 增强对mimeType缺失的日志记录 ---
                        if (fileObj.has("mimeType")) {
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
                                FLog.w(TAG, "Item '" + name + "' (ID: " + id + ") has unknown mimeType: " + mime + ". Defaulting to FILE type."); // --- 修改: Log -> FLog ---
                                type = CloudMediaItem.MediaType.FILE;
                            }
                        } else {
                            FLog.w(TAG, "Item '" + name + "' (ID: " + id + ") is a file but missing mimeType. Defaulting to FILE type."); // --- 修改: Log -> FLog ---
                            type = CloudMediaItem.MediaType.FILE;
                        }
                        // --- 修改结束 ---
                    } else {
                        // --- 修改开始: 记录既不是文件夹也不是文件的情况 ---
                        FLog.w(TAG, "Item '" + name + "' (ID: " + id + ") is neither a folder nor a file. Defaulting to FILE type."); // --- 修改: Log -> FLog ---
                        // --- 修改结束 ---
                        type = CloudMediaItem.MediaType.FILE; // Default or unknown
                    }
                    list.add(new CloudMediaItem(id, name, downloadUrl, type, lastModified));
                }
                liveDataResult.postValue(list);
            } catch (Exception ex) {
                // --- 修改开始: 确保异常被记录，并传递给UI ---
                FLog.e(TAG, "Error during Graph API call or JSON parsing for folderId: " + folderId, ex); // --- 修改: Log -> FLog ---
                liveDataResult.postValue(null); //通知UI层列表加载失败
                // --- 修改结束 ---
            }
        }).start();
    }
}