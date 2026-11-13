package com.overdrive.app.byd.cloud.crypto;

import android.content.Context;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;

public final class WbskTablesFile {

    public static final String CACHE_PATH = new File(System.getProperty("java.io.tmpdir", "/tmp"), "wbsk_tables.json").getAbsolutePath();
    public static final String ASSET_PATH = "byd/wbsk_tables.json";

    private static final long MIN_VALID_SIZE = 12000L;

    private WbskTablesFile() {}

    public static InputStream openStream(Context ctx) {
        if (ctx != null) {
            AssetManager am = ctx.getAssets();
            if (am != null) {
                try {
                    return am.open(ASSET_PATH);
                } catch (IOException ignored) {}
            }
        }

        InputStream res = WbskTablesFile.class.getClassLoader().getResourceAsStream(ASSET_PATH);
        if (res != null) {
            return res;
        }

        File cache = new File(CACHE_PATH);
        if (isValid(cache)) {
            try {
                return new FileInputStream(cache);
            } catch (IOException ignored) {}
        }

        return null;
    }

    public static boolean isValid(File f) {
        if (f == null || !f.exists()) return false;
        if (f.length() < MIN_VALID_SIZE) return false;

        FileInputStream in = null;
        try {
            in = new FileInputStream(f);
            int b;
            while ((b = in.read()) != -1) {
                if (b == ' ' || b == '\t' || b == '\r' || b == '\n') continue;
                return b == '{';
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }
    }

    public static boolean extractFromAssets(AssetManager am, File dest) {
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists()) {
            parent.mkdirs();
        }
        File tmp = new File(dest.getAbsolutePath() + ".tmp");
        InputStream in = null;
        FileOutputStream out = null;
        try {
            in = am.open(ASSET_PATH);
            out = new FileOutputStream(tmp);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            out.flush();
            try { out.getFD().sync(); } catch (IOException ignored) {}
        } catch (IOException e) {
            tmp.delete();
            return false;
        } finally {
            if (out != null) try { out.close(); } catch (IOException ignored) {}
            if (in != null) try { in.close(); } catch (IOException ignored) {}
        }

        if (!isValid(tmp)) {
            tmp.delete();
            return false;
        }

        if (!tmp.renameTo(dest)) {
            if (dest.exists()) dest.delete();
            if (!tmp.renameTo(dest)) {
                tmp.delete();
                return false;
            }
        }
        dest.setReadable(true, false);
        return true;
    }

    public static String describeCache() {
        File f = new File(CACHE_PATH);
        if (!f.exists()) return CACHE_PATH + " (missing)";
        return String.format(Locale.US, "%s (size=%d, valid=%b)",
                CACHE_PATH, f.length(), isValid(f));
    }
}
