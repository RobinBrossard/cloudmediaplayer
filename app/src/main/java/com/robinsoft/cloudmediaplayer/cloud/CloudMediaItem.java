package com.robinsoft.cloudmediaplayer.cloud;

public class CloudMediaItem {
    private final String id;
    private final String name;
    private final String url;
    private final MediaType type;
    public CloudMediaItem(String id, String name, String url, MediaType type) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.type = type;
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

    public enum MediaType {
        IMAGE,      // 图片文件
        VIDEO,      // 视频文件
        APK,
        FILE,       // 普通文件（含快捷方式）
        FOLDER      // 子目录
    }
}
