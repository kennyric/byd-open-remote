package com.bydwatch.companion.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.widget.Toast;

import androidx.annotation.Nullable;

import com.bydwatch.companion.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;
import java.util.Locale;

/**
 * Shared formatting and navigation-app chooser prompts for car GPS coordinates.
 */
public final class VehicleLocationHelper {

    private VehicleLocationHelper() {}

    public static String formatCoordinates(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    public static Intent buildGeoNavigationIntent(double latitude, double longitude, @Nullable String label) {
        String latStr = String.format(Locale.US, "%.6f", latitude);
        String lngStr = String.format(Locale.US, "%.6f", longitude);
        String cleanLabel = (label != null && !label.trim().isEmpty()) ? label.trim() : "BYD Vehicle";
        Uri geoUri = Uri.parse("geo:" + latStr + "," + lngStr
                + "?q=" + latStr + "," + lngStr + "(" + Uri.encode(cleanLabel) + ")");
        return new Intent(Intent.ACTION_VIEW, geoUri);
    }

    public static Intent buildWebMapsFallbackIntent(double latitude, double longitude) {
        String latStr = String.format(Locale.US, "%.6f", latitude);
        String lngStr = String.format(Locale.US, "%.6f", longitude);
        Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + latStr + "," + lngStr);
        return new Intent(Intent.ACTION_VIEW, webUri);
    }

    public static Intent buildNavigationChooserIntent(Context context, double latitude, double longitude,
                                                      @Nullable String label) {
        Intent geoIntent = buildGeoNavigationIntent(latitude, longitude, label);
        Intent target = geoIntent;
        if (context != null && context.getPackageManager() != null) {
            List<ResolveInfo> handlers = context.getPackageManager().queryIntentActivities(geoIntent, 0);
            if (handlers == null || handlers.isEmpty()) {
                target = buildWebMapsFallbackIntent(latitude, longitude);
            }
        }
        String title = context != null
                ? context.getString(R.string.nav_chooser_title)
                : "Redirect to Navigation App";
        Intent chooser = Intent.createChooser(target, title);
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return chooser;
    }

    public static void showNavigatePromptDialog(
            Activity activity,
            double latitude,
            double longitude,
            @Nullable String carLabel,
            @Nullable Runnable onDone
    ) {
        if (activity == null || activity.isFinishing()) return;
        String label = (carLabel != null && !carLabel.trim().isEmpty()) ? carLabel.trim() : "BYD Vehicle";
        String coords = formatCoordinates(latitude, longitude);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.nav_dialog_title)
                .setMessage(activity.getString(R.string.nav_dialog_message, label, coords))
                .setPositiveButton(R.string.nav_dialog_confirm, (dialog, which) -> {
                    try {
                        activity.startActivity(buildNavigationChooserIntent(activity, latitude, longitude, label));
                    } catch (Exception e) {
                        Toast.makeText(activity, R.string.nav_no_app_error, Toast.LENGTH_SHORT).show();
                    }
                    if (onDone != null) onDone.run();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    dialog.dismiss();
                    if (onDone != null) onDone.run();
                })
                .setOnCancelListener(dialog -> {
                    if (onDone != null) onDone.run();
                })
                .show();
    }
}
