package com.github.tvbox.osc.bean;

import androidx.annotation.NonNull;

import java.util.UUID;

/**
 * DLNA 投屏视频信息
 */
public class CastVideo {

    private final String name;
    private final String url;
    private final String id;

    public CastVideo(String name, String url) {
        this.name = name != null ? name : "未知视频";
        this.url = url != null ? url : "";
        this.id = UUID.randomUUID().toString();
    }

    @NonNull
    public String getId() {
        return id;
    }

    @NonNull
    public String getUri() {
        return url;
    }

    @NonNull
    public String getName() {
        return name;
    }

    public boolean isValid() {
        return url != null && !url.isEmpty();
    }
}
