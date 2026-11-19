package com.bydwatch.companion.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Per-car icon: an emoji string or a locally saved square photo. Photos are center-cropped,
 * downscaled and written into app storage so no gallery permission or URI is retained.
 */
final class VehicleIconStore {

    static final String DEFAULT_EMOJI = "🚗";

    /** Curated glyphs offered in the editor. Keep it simple; any emoji typed is still stored. */
    static final String[] EMOJIS = {
            "🚗", "🚙", "🚐", "🛻", "🏎️", "🚕",
            "🚌", "🚓", "🚑", "🚒", "🏍️", "🛵",
            "🚲", "🛴", "🚂", "⛵", "🚤", "🔋",
            "⚡", "🌟", "🛞", "🚀", "🛸", "❤️"
    };

    private static final int TARGET_PX = 256;

    private VehicleIconStore() {
    }

    static String emojiOrDefault(String emoji) {
        return emoji != null && !emoji.trim().isEmpty() ? emoji.trim() : DEFAULT_EMOJI;
    }

    static File photoFile(Context context, String vin) {
        if (context == null || vin == null || vin.trim().isEmpty()) return null;
        File dir = new File(context.getFilesDir(), "vehicle_icons");
        if (!dir.exists() && !dir.mkdirs()) return null;
        return new File(dir, sha1(vin.trim()) + ".jpg");
    }

    /** Copies the picked image into app storage. Returns the absolute path, or "" on failure. */
    static String savePhoto(Context context, String vin, Uri uri) {
        if (context == null || uri == null) return "";
        File out = photoFile(context, vin);
        if (out == null) return "";
        try {
            Bitmap bitmap = decodeSquare(context, uri);
            if (bitmap == null) return "";
            try (FileOutputStream fos = new FileOutputStream(out)) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, fos);
            } finally {
                bitmap.recycle();
            }
            return out.getAbsolutePath();
        } catch (Exception e) {
            return "";
        }
    }

    static void deletePhoto(Context context, String vin) {
        File file = photoFile(context, vin);
        if (file != null && file.exists()) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
        }
    }

    static Bitmap loadPhoto(String path) {
        if (path == null || path.trim().isEmpty()) return null;
        try {
            return BitmapFactory.decodeFile(path);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Bitmap decodeSquare(Context context, Uri uri) throws Exception {
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, bounds);
        }
        int w = bounds.outWidth;
        int h = bounds.outHeight;
        if (w <= 0 || h <= 0) return null;

        int sample = 1;
        while (Math.min(w, h) / (sample * 2) >= TARGET_PX) {
            sample *= 2;
        }

        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = sample;
        Bitmap decoded;
        try (InputStream in = context.getContentResolver().openInputStream(uri)) {
            decoded = BitmapFactory.decodeStream(in, null, opts);
        }
        if (decoded == null) return null;

        int side = Math.min(decoded.getWidth(), decoded.getHeight());
        int left = (decoded.getWidth() - side) / 2;
        int top = (decoded.getHeight() - side) / 2;
        Bitmap square = Bitmap.createBitmap(decoded, left, top, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(square, TARGET_PX, TARGET_PX, true);
        if (scaled != square) square.recycle();
        if (scaled != decoded) decoded.recycle();
        return scaled;
    }

    private static String sha1(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            byte[] digest = md.digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format(Locale.US, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }
}
