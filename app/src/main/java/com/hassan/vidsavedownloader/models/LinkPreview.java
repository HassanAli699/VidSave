package com.hassan.vidsavedownloader.models;

public class LinkPreview {
    public String title;
    public String description;
    public String imageUrl;
    public String domain;

    public LinkPreview(String title, String description, String imageUrl, String domain) {
        this.title = title;
        this.description = description;
        this.imageUrl = imageUrl;
        this.domain = domain;
    }
}