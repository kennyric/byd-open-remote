package com.bydwatch.companion.lab;

import android.content.Context;

import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.BuildConfig;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Lightweight reachability probe for the car's Overdrive server. */
public final class OverdriveConnection {

    public interface Callback {
        void onResult(boolean reachable);
    }

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(3, TimeUnit.SECONDS)
            .build();

    private static volatile Boolean testReachable;

    private OverdriveConnection() {}

    /** Public Overdrive endpoint; no login required. */
    public static String statusUrl(String base) {
        String normalized = CarWebUrl.normalize(base);
        if (normalized.isEmpty()) return "";
        return normalized.endsWith("/")
                ? normalized + "auth/status"
                : normalized + "/auth/status";
    }

    /**
     * Asynchronously check whether Overdrive answers. The callback may run on an
     * OkHttp worker thread; callers update UI on the main thread themselves.
     */
    public static void check(Context context, Callback callback) {
        Boolean test = testReachable;
        if (test != null) {
            callback.onResult(test);
            return;
        }
        String url = statusUrl(CarWebPrefs.baseUrl(context));
        if (url.isEmpty()) {
            callback.onResult(false);
            return;
        }
        Request request = new Request.Builder().url(url).get().build();
        CLIENT.newCall(request).enqueue(new okhttp3.Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                callback.onResult(false);
            }

            @Override
            public void onResponse(Call call, Response response) {
                try {
                    callback.onResult(response.isSuccessful());
                } finally {
                    response.close();
                }
            }
        });
    }

    @VisibleForTesting
    public static void setReachableForTesting(Boolean reachable) {
        if (!BuildConfig.DEBUG) return;
        testReachable = reachable;
    }
}
