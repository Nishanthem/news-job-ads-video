package com.jobads.model;

/**
 * A record of the original ad as proof of authenticity. This is kept separate from the video and
 * exists purely so the original source can be shown if an ad's trustworthiness is ever questioned.
 */
public class EvidenceItem {

    private String title;
    private String source;
    private String pageUrl;
    private String adLink;
    private String capturedAt;
    private String screenshotFile;
    private String originalImageFile;

    public EvidenceItem() {
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getPageUrl() {
        return pageUrl;
    }

    public void setPageUrl(String pageUrl) {
        this.pageUrl = pageUrl;
    }

    public String getAdLink() {
        return adLink;
    }

    public void setAdLink(String adLink) {
        this.adLink = adLink;
    }

    public String getCapturedAt() {
        return capturedAt;
    }

    public void setCapturedAt(String capturedAt) {
        this.capturedAt = capturedAt;
    }

    public String getScreenshotFile() {
        return screenshotFile;
    }

    public void setScreenshotFile(String screenshotFile) {
        this.screenshotFile = screenshotFile;
    }

    public String getOriginalImageFile() {
        return originalImageFile;
    }

    public void setOriginalImageFile(String originalImageFile) {
        this.originalImageFile = originalImageFile;
    }
}
