package com.bydwatch.companion.lab;

import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.BuildConfig;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Off-main-thread execution for Overdrive calls.
 *
 * <p>One shared single-thread executor keeps card probes and screen refreshes
 * from piling requests onto the car's server. Tests swap in a direct executor.
 */
public final class OverdriveWork {

    private static final Executor DEFAULT = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "overdrive-io");
        thread.setDaemon(true);
        return thread;
    });

    private static volatile Executor executor;

    private OverdriveWork() {}

    @VisibleForTesting
    public static void setExecutorForTesting(Executor value) {
        if (!BuildConfig.DEBUG) return;
        executor = value;
    }

    public static Executor executor() {
        Executor injected = executor;
        return injected != null ? injected : DEFAULT;
    }

    public static void run(Runnable runnable) {
        executor().execute(runnable);
    }
}
