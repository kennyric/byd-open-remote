package com.bydwatch.companion.wear;

import android.app.Activity;

/**
 * Transport abstraction for Huawei Wear Engine P2P messaging.
 */
public interface PeerTransport {

    interface MessageListener {
        void onMessageReceived(byte[] payload);
    }

    interface AuthCallback {
        void onSuccess();
        void onError(String error);
    }

    interface SendCallback {
        void onSuccess();
        void onFailure(int errorCode, String message);
    }

    interface TransportStateListener {
        void onAvailabilityChanged(boolean available, String reason);
        void onPermissionChanged(boolean granted);
        void onReceiverStateChanged(boolean listening, String statusDetail);
        void onError(int errorCode, String message);
    }

    boolean isAvailable();

    boolean hasPermission();

    boolean isListening();

    void setTransportStateListener(TransportStateListener listener);

    void requestPermission(Activity activity, AuthCallback callback);

    default void requestPermission(AuthCallback callback) {
        requestPermission(null, callback);
    }

    void startListening(MessageListener listener);

    void stopListening();

    void send(byte[] payload, SendCallback callback);
}
