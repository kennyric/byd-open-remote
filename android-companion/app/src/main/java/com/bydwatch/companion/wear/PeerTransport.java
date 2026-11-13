package com.bydwatch.companion.wear;

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

    boolean isAvailable();

    boolean hasPermission();

    void requestPermission(AuthCallback callback);

    void startListening(MessageListener listener);

    void stopListening();

    void send(byte[] payload, SendCallback callback);
}
