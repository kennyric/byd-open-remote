package com.bydwatch.companion.wear;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.R;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.ui.MainActivity;

/**
 * Evaluates telemetry updates and emits smart vehicle alerts (mirrored to Watch GT 4 by Huawei Health).
 */
public final class VehicleAlertNotifier {

    public static final String CHANNEL_ID = "byd_vehicle_alerts";
    private static final int NOTIF_UNLOCKED = 3001;
    private static final int NOTIF_OPENING = 3002;
    private static final int NOTIF_CHARGE_DONE = 3003;

    private static final long UNLOCKED_GRACE_MS = 10 * 60 * 1000L;

    private static long unlockedSinceMs = 0L;
    private static boolean notifiedUnlocked = false;
    private static boolean notifiedOpening = false;
    private static boolean wasCharging = false;

    private VehicleAlertNotifier() {}

    public static synchronized void evaluate(Context context, VehicleStatus status, boolean enabled) {
        if (context == null || status == null) {
            return;
        }
        if (!enabled) {
            resetState();
            return;
        }

        long now = System.currentTimeMillis();
        String carLabel = (status.modelName != null && !status.modelName.isEmpty())
                ? status.modelName
                : "Vehicle";

        // 1. Unlocked grace period check
        if (!status.locked) {
            if (unlockedSinceMs == 0L) {
                unlockedSinceMs = now;
                notifiedUnlocked = false;
            } else if (!notifiedUnlocked && (now - unlockedSinceMs) >= UNLOCKED_GRACE_MS) {
                notifyUser(context, NOTIF_UNLOCKED,
                        carLabel + " is still unlocked",
                        "Unlocked for more than 10 minutes. Tap to lock from app or watch.");
                notifiedUnlocked = true;
            }
        } else {
            unlockedSinceMs = 0L;
            notifiedUnlocked = false;
        }

        // 2. Doors / windows / trunk open alert
        if (status.hasAnyOpening()) {
            if (!notifiedOpening && status.locked) {
                notifyUser(context, NOTIF_OPENING,
                        carLabel + ": Door or window open",
                        "Doors open: " + status.doorsOpenCount + ", Windows open: " + status.windowsOpenCount);
                notifiedOpening = true;
            }
        } else {
            notifiedOpening = false;
        }

        // 3. Charging completion alert
        if (status.isCharging()) {
            wasCharging = true;
        } else if (wasCharging && status.soc >= 80) {
            wasCharging = false;
            notifyUser(context, NOTIF_CHARGE_DONE,
                    carLabel + " charging finished",
                    "Battery is now at " + status.soc + "%.");
        } else if (!status.isCharging()) {
            wasCharging = false;
        }
    }

    public static synchronized void resetState() {
        unlockedSinceMs = 0L;
        notifiedUnlocked = false;
        notifiedOpening = false;
        wasCharging = false;
    }

    private static void notifyUser(Context context, int notifId, String title, String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                return;
            }
        }

        ensureChannel(context);
        Intent launch = new Intent(context, MainActivity.class);
        launch.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent pi = PendingIntent.getActivity(context, notifId, launch, flags);

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_ev_watch_logo)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setAutoCancel(true)
                .setContentIntent(pi);

        NotificationManagerCompat.from(context).notify(notifId, builder.build());
    }

    private static void ensureChannel(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = context.getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel channel = new NotificationChannel(
                        CHANNEL_ID,
                        "Vehicle Smart Alerts",
                        NotificationManager.IMPORTANCE_DEFAULT
                );
                channel.setDescription("Alerts when your vehicle stays unlocked, open, or finishes charging");
                nm.createNotificationChannel(channel);
            }
        }
    }
}
