package com.overdrive.app.byd.cloud;

/**
 * Lightweight logger adapter for BYD cloud components.
 * 
 * Safely falls back to System.out/System.err in pure JVM unit tests where
 * android.util.Log may not be mocked or available.
 */
public final class CloudLog {
    private final String tag;

    private CloudLog(String tag) {
        this.tag = tag != null ? tag : "BYD";
    }

    public static CloudLog getInstance(String tag) {
        return new CloudLog(tag);
    }

    public void info(String msg) {
        log("I", tag, msg, null);
    }

    public void warn(String msg) {
        log("W", tag, msg, null);
    }

    public void debug(String msg) {
        log("D", tag, msg, null);
    }

    public void error(String msg) {
        log("E", tag, msg, null);
    }

    public void error(String msg, Throwable tr) {
        log("E", tag, msg, tr);
    }

    private static void log(String level, String tag, String msg, Throwable tr) {
        try {
            switch (level) {
                case "I":
                    android.util.Log.i(tag, msg);
                    return;
                case "W":
                    android.util.Log.w(tag, msg);
                    return;
                case "D":
                    android.util.Log.d(tag, msg);
                    return;
                case "E":
                    if (tr != null) {
                        android.util.Log.e(tag, msg, tr);
                    } else {
                        android.util.Log.e(tag, msg);
                    }
                    return;
            }
        } catch (Throwable ignored) {
            // Android runtime log unmocked in JVM test runner
            if ("E".equals(level) || "W".equals(level)) {
                System.err.println("[" + level + "/" + tag + "] " + msg);
                if (tr != null) tr.printStackTrace(System.err);
            } else {
                System.out.println("[" + level + "/" + tag + "] " + msg);
            }
        }
    }
}
