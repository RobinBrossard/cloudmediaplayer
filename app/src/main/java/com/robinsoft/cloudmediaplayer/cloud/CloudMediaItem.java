package com.robinsoft.cloudmediaplayer.cloud;
public class CloudMediaItem {
    private final String id, name, downloadUrl;
    private final MediaType type;
    public enum MediaType { IMAGE, VIDEO }
    public CloudMediaItem(String id, String name, String downloadUrl, MediaType type) {
        this.id = id; this.name = name; this.downloadUrl = downloadUrl; this.type = type;
    }
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDownloadUrl() { return downloadUrl; }
    public MediaType getType() { return type; }
}
