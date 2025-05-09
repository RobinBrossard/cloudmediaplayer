package com.robinsoft.cloudmediaplayer.cloud;
public class CloudProviderFactory {
    public enum Platform { ONEDRIVE, GOOGLE_CLOUD }
    public static CloudMediaService create(Platform platform) {
        switch (platform) {
            case ONEDRIVE: return new OneDriveMediaService();
            case GOOGLE_CLOUD: /* later */ throw new IllegalArgumentException();
            default: throw new IllegalArgumentException();
        }
    }
}
