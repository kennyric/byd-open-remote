package com.bydwatch.companion.update;

import android.content.Context;

import com.bydwatch.companion.BuildConfig;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Checks GitHub Releases for newer companion APKs and downloads verified APK assets locally.
 */
public class AppUpdateChecker {

    public static final String LATEST_RELEASE_API_URL =
            "https://api.github.com/repos/kennyric/byd-open-remote/releases/latest";

    private static final String GITHUB_RELEASES_FALLBACK_URL =
            "https://github.com/kennyric/byd-open-remote/releases";

    public interface DownloadProgressListener {
        void onProgress(long bytesRead, long totalBytes);
    }

    public static final class DownloadCancelledException extends IOException {
        public DownloadCancelledException() {
            super("Download cancelled");
        }
    }

    private final OkHttpClient httpClient;
    private final String latestReleaseUrl;

    public AppUpdateChecker() {
        this(createDefaultHttpClient(), LATEST_RELEASE_API_URL);
    }

    public AppUpdateChecker(OkHttpClient httpClient, String latestReleaseUrl) {
        this.httpClient = httpClient != null ? httpClient : createDefaultHttpClient();
        this.latestReleaseUrl = latestReleaseUrl != null && !latestReleaseUrl.trim().isEmpty()
                ? latestReleaseUrl.trim()
                : LATEST_RELEASE_API_URL;
    }

    private static OkHttpClient createDefaultHttpClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true)
                .build();
    }

    /**
     * Fetches and parses the latest non-draft GitHub release metadata.
     */
    public AppUpdateInfo fetchLatestRelease() throws IOException {
        Request request = new Request.Builder()
                .url(latestReleaseUrl)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BYD-Open-Remote/" + BuildConfig.VERSION_NAME)
                .build();

        try (Response response = httpClient.newCall(request).execute()) {
            ResponseBody body = response.body();
            String bodyStr = body != null ? body.string() : "";
            if (!response.isSuccessful()) {
                if (response.code() == 403 || response.code() == 429) {
                    throw new IOException("GitHub API rate limit reached (HTTP " + response.code()
                            + "). Please try again later.");
                }
                if (response.code() == 404) {
                    throw new IOException("No published releases found on GitHub.");
                }
                throw new IOException("Update check failed (HTTP " + response.code() + ")");
            }
            return parseReleaseJson(bodyStr);
        } catch (JSONException e) {
            throw new IOException("Invalid release metadata from GitHub", e);
        }
    }

    /**
     * Downloads the APK asset into {@code cacheDir/updates/} while reporting byte progress.
     */
    public File downloadApk(
            Context context,
            AppUpdateInfo info,
            DownloadProgressListener progressListener,
            AtomicBoolean cancelled) throws IOException {
        if (context == null) {
            throw new IllegalArgumentException("Context required");
        }
        File updatesDir = new File(context.getCacheDir(), "updates");
        return downloadApkToDirectory(updatesDir, info, progressListener, cancelled);
    }

    /**
     * Package-testable APK downloader writing into a caller-supplied directory.
     */
    public File downloadApkToDirectory(
            File updatesDir,
            AppUpdateInfo info,
            DownloadProgressListener progressListener,
            AtomicBoolean cancelled) throws IOException {
        if (info == null || !info.hasApkAsset()) {
            throw new IOException("This release does not contain an APK download.");
        }
        if (!isTrustedDownloadUrl(info.apkDownloadUrl)) {
            throw new IOException("Untrusted APK download URL: " + info.apkDownloadUrl);
        }
        if (!updatesDir.exists() && !updatesDir.mkdirs()) {
            throw new IOException("Unable to create update cache directory");
        }

        String safeName = sanitizeApkFileName(info.apkName, info.versionName);
        File targetFile = new File(updatesDir, safeName);
        if (isReusableCachedApk(targetFile, info)) {
            if (progressListener != null) {
                long len = targetFile.length();
                progressListener.onProgress(len, len);
            }
            return targetFile;
        }

        cleanupStaleFiles(updatesDir, null);
        File tempFile = new File(updatesDir, safeName + ".tmp");

        Request request = new Request.Builder()
                .url(info.apkDownloadUrl)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "BYD-Open-Remote/" + BuildConfig.VERSION_NAME)
                .build();

        boolean keepTemp = false;
        try (Response response = httpClient.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("APK download failed (HTTP " + response.code() + ")");
            }
            HttpUrl finalUrl = response.request() != null ? response.request().url() : null;
            if (finalUrl != null && !isTrustedDownloadUrl(finalUrl.toString())) {
                throw new IOException("Redirected to untrusted download host: " + finalUrl.host());
            }

            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty APK response");
            }
            long expectedTotal = body.contentLength() > 0 ? body.contentLength() : info.apkSizeBytes;

            try (InputStream in = body.byteStream();
                 FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[16 * 1024];
                long totalRead = 0L;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    if (cancelled != null && cancelled.get()) {
                        throw new DownloadCancelledException();
                    }
                    out.write(buffer, 0, read);
                    totalRead += read;
                    if (progressListener != null) {
                        progressListener.onProgress(totalRead, expectedTotal);
                    }
                }
                out.getFD().sync();
            }

            if (tempFile.length() <= 0L) {
                throw new IOException("Downloaded APK file is empty");
            }
            if (info.apkSizeBytes > 0L && tempFile.length() != info.apkSizeBytes) {
                throw new IOException("Downloaded APK size mismatch (expected "
                        + info.apkSizeBytes + " bytes, got " + tempFile.length() + ")");
            }
            if (!info.sha256Hex.isEmpty()) {
                String actualHash = computeSha256Hex(tempFile);
                if (!info.sha256Hex.equalsIgnoreCase(actualHash)) {
                    throw new IOException("APK integrity verification failed (SHA-256 mismatch)");
                }
            }

            if (targetFile.exists() && !targetFile.delete()) {
                throw new IOException("Unable to overwrite previous APK");
            }
            if (!tempFile.renameTo(targetFile)) {
                throw new IOException("Unable to finalize downloaded APK");
            }
            keepTemp = true;
            return targetFile;
        } finally {
            if (!keepTemp && tempFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                tempFile.delete();
            }
        }
    }

    public static AppUpdateInfo parseReleaseJson(String jsonBody) throws JSONException, IOException {
        if (jsonBody == null || jsonBody.trim().isEmpty()) {
            throw new IOException("Empty release metadata");
        }
        JSONObject root = new JSONObject(jsonBody);
        if (root.optBoolean("draft", false)) {
            throw new IOException("Latest release is marked draft");
        }

        String tagName = root.optString("tag_name", "").trim();
        String versionName = normalizeVersionString(tagName);
        if (versionName.isEmpty()) {
            throw new IOException("Release is missing a version tag");
        }
        String releaseName = root.optString("name", "").trim();
        if (releaseName.isEmpty()) {
            releaseName = "v" + versionName;
        }
        String releaseNotes = root.optString("body", "").trim();
        String htmlUrl = root.optString("html_url", GITHUB_RELEASES_FALLBACK_URL).trim();

        String bestName = "";
        String bestUrl = "";
        long bestSize = 0L;
        String bestSha256 = "";

        JSONArray assets = root.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject asset = assets.optJSONObject(i);
                if (asset == null) continue;
                String name = asset.optString("name", "").trim();
                String url = asset.optString("browser_download_url", "").trim();
                if (!name.toLowerCase(Locale.US).endsWith(".apk") || !isTrustedDownloadUrl(url)) {
                    continue;
                }
                long size = Math.max(0L, asset.optLong("size", 0L));
                String sha256 = extractSha256Digest(asset.optString("digest", ""));
                boolean isPreferredName = name.toLowerCase(Locale.US).startsWith("byd-open-remote");
                if (bestUrl.isEmpty() || isPreferredName) {
                    bestName = name;
                    bestUrl = url;
                    bestSize = size;
                    bestSha256 = sha256;
                    if (isPreferredName) {
                        break;
                    }
                }
            }
        }

        return new AppUpdateInfo(
                tagName,
                versionName,
                releaseName,
                releaseNotes,
                htmlUrl,
                bestName,
                bestUrl,
                bestSize,
                bestSha256
        );
    }

    public static boolean isNewerVersion(String latestVersionOrTag, String currentVersion) {
        return compareVersions(latestVersionOrTag, currentVersion) > 0;
    }

    /**
     * Compares dotted semantic version strings (ignoring leading 'v'). Pre-release suffixes
     * sort slightly below the corresponding final version (e.g. 1.0.3-rc1 &lt; 1.0.3).
     */
    public static int compareVersions(String leftRaw, String rightRaw) {
        ParsedVersion left = parseVersion(leftRaw);
        ParsedVersion right = parseVersion(rightRaw);
        int maxLen = Math.max(left.parts.length, right.parts.length);
        for (int i = 0; i < maxLen; i++) {
            int l = i < left.parts.length ? left.parts[i] : 0;
            int r = i < right.parts.length ? right.parts[i] : 0;
            if (l != r) {
                return Integer.compare(l, r);
            }
        }
        if (left.preRelease == right.preRelease) {
            return 0;
        }
        return left.preRelease ? -1 : 1;
    }

    public static String normalizeVersionString(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if ((trimmed.startsWith("v") || trimmed.startsWith("V")) && trimmed.length() > 1) {
            trimmed = trimmed.substring(1).trim();
        }
        return trimmed;
    }

    public static boolean isTrustedDownloadUrl(String rawUrl) {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            return false;
        }
        HttpUrl url = HttpUrl.parse(rawUrl.trim());
        if (url == null || !"https".equalsIgnoreCase(url.scheme())) {
            return false;
        }
        String host = url.host().toLowerCase(Locale.US);
        return "github.com".equals(host)
                || host.endsWith(".github.com")
                || "githubusercontent.com".equals(host)
                || host.endsWith(".githubusercontent.com");
    }

    static String computeSha256Hex(File file) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (InputStream in = new FileInputStream(file)) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) {
                    md.update(buf, 0, n);
                }
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 unavailable", e);
        }
    }

    private static boolean isReusableCachedApk(File file, AppUpdateInfo info) {
        if (!file.exists() || !file.isFile() || file.length() <= 0L) {
            return false;
        }
        if (info.apkSizeBytes > 0L && file.length() != info.apkSizeBytes) {
            return false;
        }
        if (!info.sha256Hex.isEmpty()) {
            try {
                return info.sha256Hex.equalsIgnoreCase(computeSha256Hex(file));
            } catch (IOException e) {
                return false;
            }
        }
        return info.apkSizeBytes > 0L;
    }

    private static void cleanupStaleFiles(File dir, String keepName) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isFile() && (keepName == null || !keepName.equals(f.getName()))) {
                //noinspection ResultOfMethodCallIgnored
                f.delete();
            }
        }
    }

    private static String sanitizeApkFileName(String rawName, String versionName) {
        String base = rawName != null ? new File(rawName).getName().replaceAll("[^A-Za-z0-9._-]", "_") : "";
        if (base.isEmpty() || !base.toLowerCase(Locale.US).endsWith(".apk")) {
            String ver = normalizeVersionString(versionName).replaceAll("[^A-Za-z0-9._-]", "_");
            return "BYD-Open-Remote-v" + (ver.isEmpty() ? "update" : ver) + ".apk";
        }
        return base;
    }

    private static String extractSha256Digest(String digestField) {
        if (digestField == null) return "";
        String trimmed = digestField.trim();
        if (trimmed.toLowerCase(Locale.US).startsWith("sha256:")) {
            return trimmed.substring("sha256:".length()).trim();
        }
        return "";
    }

    private static ParsedVersion parseVersion(String raw) {
        String norm = normalizeVersionString(raw);
        if (norm.isEmpty()) {
            return new ParsedVersion(new int[0], false);
        }
        int dashIdx = norm.indexOf('-');
        boolean preRelease = dashIdx >= 0;
        String core = dashIdx >= 0 ? norm.substring(0, dashIdx) : norm;
        String[] segments = core.split("\\.");
        int[] nums = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            String digits = segments[i].replaceAll("[^0-9]", "");
            if (!digits.isEmpty()) {
                try {
                    nums[i] = Integer.parseInt(digits);
                } catch (NumberFormatException ignored) {
                    nums[i] = 0;
                }
            }
        }
        return new ParsedVersion(nums, preRelease);
    }

    private static final class ParsedVersion {
        final int[] parts;
        final boolean preRelease;

        ParsedVersion(int[] parts, boolean preRelease) {
            this.parts = parts;
            this.preRelease = preRelease;
        }
    }
}
