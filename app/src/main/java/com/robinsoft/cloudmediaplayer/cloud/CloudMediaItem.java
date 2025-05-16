package com.robinsoft.cloudmediaplayer.cloud;

import java.time.OffsetDateTime;

public class CloudMediaItem {
    private final String id;
    private final String name;
    private final String url;
    private final MediaType type;
    private final OffsetDateTime lastModifiedDateTime;  // 新增

    public CloudMediaItem(String id,
                          String name,
                          String url,
                          MediaType type,
                          OffsetDateTime lastModifiedDateTime) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.type = type;
        this.lastModifiedDateTime = lastModifiedDateTime;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getUrl() {
        return url;
    }

    public MediaType getType() {
        return type;
    }

    public OffsetDateTime getLastModifiedDateTime() {
        return lastModifiedDateTime;
    }

    public enum MediaType {
        IMAGE, VIDEO, APK, FILE, FOLDER
    }
}
