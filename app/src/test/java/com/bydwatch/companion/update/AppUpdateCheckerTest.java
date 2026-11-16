package com.bydwatch.companion.update;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Response;
import okhttp3.ResponseBody;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AppUpdateCheckerTest {

    @Rule
    public final TemporaryFolder tempFolder = new TemporaryFolder();

    @Test
    public void versionComparisonHandlesSemanticAndPrereleaseTags() {
        assertTrue(AppUpdateChecker.isNewerVersion("v1.0.3", "1.0.2"));
        assertTrue(AppUpdateChecker.isNewerVersion("1.10.0", "1.2.9"));
        assertTrue(AppUpdateChecker.isNewerVersion("v2.0", "1.9.9"));
        assertTrue(AppUpdateChecker.isNewerVersion("1.0.3-rc1", "1.0.2"));
        assertTrue(AppUpdateChecker.compareVersions("1.0.3", "1.0.3-rc1") > 0);

        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.2", "1.0.2"));
        assertFalse(AppUpdateChecker.isNewerVersion("v1.0.1", "1.0.2"));
        assertFalse(AppUpdateChecker.isNewerVersion("", "1.0.2"));
    }

    @Test
    public void parseReleaseJsonSelectsPreferredApkAndDigest() throws Exception {
        String json = "{"
                + "\"tag_name\": \"v1.0.3\","
                + "\"name\": \"v1.0.3 — Remote AC Timer\","
                + "\"body\": \"## What's New\\n- Added AC timer\\n- Bug fixes\","
                + "\"html_url\": \"https://github.com/kennyric/byd-open-remote/releases/tag/v1.0.3\","
                + "\"draft\": false,"
                + "\"assets\": ["
                + "  {\"name\": \"other-tool.apk\", \"browser_download_url\": \"https://github.com/kennyric/byd-open-remote/releases/download/v1.0.3/other-tool.apk\", \"size\": 100},"
                + "  {\"name\": \"BYD-Open-Remote-v1.0.3.apk\", \"browser_download_url\": \"https://github.com/kennyric/byd-open-remote/releases/download/v1.0.3/BYD-Open-Remote-v1.0.3.apk\", \"size\": 9403048, \"digest\": \"sha256:abcdef0123456789\"}"
                + "]"
                + "}";

        AppUpdateInfo info = AppUpdateChecker.parseReleaseJson(json);
        assertEquals("v1.0.3", info.tagName);
        assertEquals("1.0.3", info.versionName);
        assertEquals("BYD-Open-Remote-v1.0.3.apk", info.apkName);
        assertEquals(9403048L, info.apkSizeBytes);
        assertEquals("abcdef0123456789", info.sha256Hex);
        assertTrue(info.hasApkAsset());
    }

    @Test
    public void downloadUrlValidationAcceptsOnlyGitHubHttps() {
        assertTrue(AppUpdateChecker.isTrustedDownloadUrl(
                "https://github.com/kennyric/byd-open-remote/releases/download/v1.0.3/app.apk"));
        assertTrue(AppUpdateChecker.isTrustedDownloadUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/123"));

        assertFalse(AppUpdateChecker.isTrustedDownloadUrl(
                "http://github.com/kennyric/byd-open-remote/releases/download/v1.0.3/app.apk"));
        assertFalse(AppUpdateChecker.isTrustedDownloadUrl(
                "https://example.com/malicious.apk"));
        assertFalse(AppUpdateChecker.isTrustedDownloadUrl(""));
    }

    @Test
    public void fetchAndDownloadApkVerifiesSha256AndReportsProgress() throws Exception {
        byte[] apkBytes = "PK\u0003\u0004synthetic-apk-payload-for-test".getBytes(StandardCharsets.UTF_8);
        String expectedSha256 = sha256Hex(apkBytes);

        String releaseJson = "{"
                + "\"tag_name\": \"v1.0.3\","
                + "\"name\": \"v1.0.3\","
                + "\"body\": \"Added updater\","
                + "\"html_url\": \"https://github.com/kennyric/byd-open-remote/releases/tag/v1.0.3\","
                + "\"assets\": [{"
                + "  \"name\": \"BYD-Open-Remote-v1.0.3.apk\","
                + "  \"browser_download_url\": \"https://github.com/kennyric/byd-open-remote/releases/download/v1.0.3/BYD-Open-Remote-v1.0.3.apk\","
                + "  \"size\": " + apkBytes.length + ","
                + "  \"digest\": \"sha256:" + expectedSha256 + "\""
                + "}]"
                + "}";

        List<String> requestedUrls = new ArrayList<>();
        Interceptor mockInterceptor = chain -> {
            String url = chain.request().url().toString();
            requestedUrls.add(url);
            if (url.endsWith("/releases/latest")) {
                return new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(releaseJson, MediaType.get("application/json")))
                        .build();
            }
            return new Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(ResponseBody.create(apkBytes, MediaType.get("application/vnd.android.package-archive")))
                    .build();
        };

        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(mockInterceptor).build();
        AppUpdateChecker checker = new AppUpdateChecker(client, AppUpdateChecker.LATEST_RELEASE_API_URL);

        AppUpdateInfo info = checker.fetchLatestRelease();
        assertEquals("1.0.3", info.versionName);

        File updatesDir = tempFolder.newFolder("updates");
        List<Long> progressReads = new ArrayList<>();
        File downloaded = checker.downloadApkToDirectory(
                updatesDir,
                info,
                (read, total) -> progressReads.add(read),
                new AtomicBoolean(false));

        assertTrue(downloaded.exists());
        assertEquals("BYD-Open-Remote-v1.0.3.apk", downloaded.getName());
        assertEquals(apkBytes.length, downloaded.length());
        assertFalse(progressReads.isEmpty());
        assertEquals(Long.valueOf(apkBytes.length), progressReads.get(progressReads.size() - 1));
        assertEquals(2, requestedUrls.size());
    }

    @Test
    public void downloadApkRejectsSha256MismatchAndCleansTempFile() throws Exception {
        byte[] apkBytes = "corrupted-payload".getBytes(StandardCharsets.UTF_8);
        AppUpdateInfo info = new AppUpdateInfo(
                "v1.0.3",
                "1.0.3",
                "v1.0.3",
                "",
                "https://github.com/kennyric/byd-open-remote/releases/tag/v1.0.3",
                "BYD-Open-Remote-v1.0.3.apk",
                "https://github.com/kennyric/byd-open-remote/releases/download/v1.0.3/BYD-Open-Remote-v1.0.3.apk",
                apkBytes.length,
                "0000000000000000000000000000000000000000000000000000000000000000");

        OkHttpClient client = new OkHttpClient.Builder()
                .addInterceptor(chain -> new Response.Builder()
                        .request(chain.request())
                        .protocol(Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body(ResponseBody.create(apkBytes, MediaType.get("application/octet-stream")))
                        .build())
                .build();
        AppUpdateChecker checker = new AppUpdateChecker(client, AppUpdateChecker.LATEST_RELEASE_API_URL);
        File updatesDir = tempFolder.newFolder("updates_bad_hash");

        try {
            checker.downloadApkToDirectory(updatesDir, info, null, new AtomicBoolean(false));
            fail("Expected SHA-256 mismatch to throw IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("SHA-256"));
        }
        assertEquals(0, updatesDir.listFiles() != null ? updatesDir.listFiles().length : 0);
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
