package com.bydwatch.companion.update;

/**
 * Metadata describing a GitHub release and its installable Android APK asset.
 */
public final class AppUpdateInfo {

    public final String tagName;
    public final String versionName;
    public final String releaseName;
    public final String releaseNotes;
    public final String htmlUrl;
    public final String apkName;
    public final String apkDownloadUrl;
    public final long apkSizeBytes;
    public final String sha256Hex;

    public AppUpdateInfo(
            String tagName,
            String versionName,
            String releaseName,
            String releaseNotes,
            String htmlUrl,
            String apkName,
            String apkDownloadUrl,
            long apkSizeBytes,
            String sha256Hex) {
        this.tagName = tagName != null ? tagName : "";
        this.versionName = versionName != null ? versionName : "";
        this.releaseName = releaseName != null ? releaseName : "";
        this.releaseNotes = releaseNotes != null ? releaseNotes : "";
        this.htmlUrl = htmlUrl != null ? htmlUrl : "";
        this.apkName = apkName != null ? apkName : "";
        this.apkDownloadUrl = apkDownloadUrl != null ? apkDownloadUrl : "";
        this.apkSizeBytes = Math.max(0L, apkSizeBytes);
        this.sha256Hex = sha256Hex != null ? sha256Hex : "";
    }

    public boolean hasApkAsset() {
        return !apkDownloadUrl.isEmpty() && !apkName.isEmpty();
    }
}
