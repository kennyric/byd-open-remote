package com.bydwatch.companion.lab;

import android.content.Context;
import android.webkit.CookieManager;

import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Reachability and session probe for the car's Overdrive server.
 *
 * <p>{@code /auth/status} is public and proves the server is up.
 * {@code /status} requires the Overdrive login session; a 401/403 means the
 * user still needs to log in through the WebView.
 */
public final class OverdriveConnection {

    public enum Status {
        CONNECTED,
        LOGIN_REQUIRED,
        UNREACHABLE
    }

    public interface Callback {
        void onResult(Status status);
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    private static volatile Status testStatus;

    private OverdriveConnection() {}

    public static String statusUrl(String base) {
        String normalized = CarWebUrl.normalize(base);
        if (normalized.isEmpty()) return "";
        return normalized.endsWith("/") ? normalized + "auth/status" : normalized + "/auth/status";
    }

    public static String authStatusUrl(String base) {
        String normalized = CarWebUrl.normalize(base);
        if (normalized.isEmpty()) return "";
        return normalized.endsWith("/") ? normalized + "status" : normalized + "/status";
    }

    public static void check(Context context, Callback callback) {
        Status test = testStatus;
        if (test != null) {
            callback.onResult(test);
            return;
        }
        String base = CarWebPrefs.baseUrl(context);
        String reachUrl = statusUrl(base);
        if (reachUrl.isEmpty()) {
            callback.onResult(Status.UNREACHABLE);
            return;
        }

        Request reach = new Request.Builder().url(reachUrl).get().build();
        CLIENT.newCall(reach).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onResult(Status.UNREACHABLE);
            }

            @Override
            public void onResponse(Call call, Response response) {
                boolean reachable = response.isSuccessful();
                response.close();
                if (!reachable) {
                    callback.onResult(Status.UNREACHABLE);
                    return;
                }
                checkSession(authStatusUrl(base), callback);
            }
        });
    }

    private static void checkSession(String authUrl, Callback callback) {
        String cookie = CookieManager.getInstance().getCookie(authUrl);
        Request.Builder builder = new Request.Builder().url(authUrl).get();
        if (cookie != null && !cookie.isEmpty()) {
            builder.header("Cookie", cookie);
        }
        CLIENT.newCall(builder.build()).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onResult(Status.UNREACHABLE);
            }

            @Override
            public void onResponse(Call call, Response response) {
                int code = response.code();
                response.close();
                if (code == 401 || code == 403) {
                    callback.onResult(Status.LOGIN_REQUIRED);
                } else if (code >= 200 && code < 300) {
                    callback.onResult(Status.CONNECTED);
                } else {
                    callback.onResult(Status.UNREACHABLE);
                }
            }
        });
    }

    @VisibleForTesting
    public static void setStatusForTesting(Status status) {
        if (!BuildConfig.DEBUG) return;
        testStatus = status;
    }
}
