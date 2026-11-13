package com.bydwatch.companion.wear;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.ui.MainActivity;

/**
 * Foreground service hosting WearEngineBridge so watch commands continue to be received
 * when the phone is locked or in pocket.
 */
public class WearBridgeService extends Service {

    private static final String TAG = "WearBridgeService";

    public static final String ACTION_START = "com.bydwatch.companion.action.START_BRIDGE";
    public static final String ACTION_STOP = "com.bydwatch.companion.action.STOP_BRIDGE";

    public static final String CHANNEL_ID = "byd_watch_bridge_channel";
    public static final int NOTIFICATION_ID = 2001;

    private static volatile boolean running = false;

    public static boolean isRunning() {
        return running;
    }

    public static void start(Context context) {
        Intent intent = new Intent(context, WearBridgeService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        Intent intent = new Intent(context, WearBridgeService.class);
        intent.setAction(ACTION_STOP);
        context.startService(intent);
    }

    private WearEngineBridge bridge;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
        Notification notification = buildNotification();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }

        BydWatchApp app = BydWatchApp.getInstance();
        if (app != null) {
            bridge = app.getWearEngineBridge();
        }
        running = true;
        Log.i(TAG, "WearBridgeService created and started in foreground");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            stopBridge();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE);
            } else {
                stopForeground(true);
            }
            stopSelf();
            return START_NOT_STICKY;
        }

        startBridge();
        return START_STICKY;
    }

    private void startBridge() {
        if (bridge != null) {
            bridge.start();
        }
    }

    private void stopBridge() {
        if (bridge != null) {
            bridge.stop();
        }
    }

    @Override
    public void onDestroy() {
        stopBridge();
        running = false;
        Log.i(TAG, "WearBridgeService destroyed");
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.bridge_notification_channel_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.bridge_notification_channel_desc));
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.createNotificationChannel(channel);
            }
        }
    }

    private Notification buildNotification() {
        Intent contentIntent = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, contentIntent, flags);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.bridge_notification_title))
                .setContentText(getString(R.string.bridge_notification_text))
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentIntent(pendingIntent)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .build();
    }
}
