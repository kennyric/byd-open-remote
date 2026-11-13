package com.bydwatch.companion.wear;

import android.app.Activity;

import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.protocol.WatchProtocol;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Core bridge coordinating incoming commands from the Huawei Watch GT 4 over Wear Engine P2P
 * and responses dispatched from BydRepository.
 */
public class WearEngineBridge {

    public enum ConnectionState {
        UNAVAILABLE,
        PERMISSION_NEEDED,
        CONNECTING,
        DISCONNECTED,
        LISTENING,
        ERROR
    }

    public interface Callback {
        void onSuccess();
        void onError(String error);
    }

    public interface StateListener {
        void onStateChanged(ConnectionState state);
    }

    private final BydRepository repository;
    private final PeerTransport transport;
    private final List<StateListener> stateListeners = new CopyOnWriteArrayList<>();

    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private volatile boolean isRunning = false;

    public WearEngineBridge(BydRepository repository, PeerTransport transport) {
        if (repository == null) {
            throw new IllegalArgumentException("BydRepository cannot be null");
        }
        if (transport == null) {
            throw new IllegalArgumentException("PeerTransport cannot be null");
        }
        this.repository = repository;
        this.transport = transport;

        this.transport.setTransportStateListener(new PeerTransport.TransportStateListener() {
            @Override
            public void onAvailabilityChanged(boolean available, String reason) {
                if (!available) {
                    setConnectionState(ConnectionState.UNAVAILABLE);
                } else {
                    evaluateState();
                }
            }

            @Override
            public void onPermissionChanged(boolean granted) {
                if (granted && isRunning && !transport.isListening()) {
                    transport.startListening(WearEngineBridge.this::onIncomingBytes);
                }
                evaluateState();
            }

            @Override
            public void onReceiverStateChanged(boolean listening, String statusDetail) {
                if (listening) {
                    setConnectionState(ConnectionState.LISTENING);
                } else {
                    evaluateState();
                }
            }

            @Override
            public void onError(int errorCode, String message) {
                setConnectionState(ConnectionState.ERROR);
            }
        });
    }

    public synchronized void start() {
        isRunning = true;
        if (!transport.isAvailable()) {
            setConnectionState(ConnectionState.UNAVAILABLE);
            return;
        }

        if (!transport.hasPermission()) {
            setConnectionState(ConnectionState.PERMISSION_NEEDED);
            return;
        }

        if (transport.isListening()) {
            setConnectionState(ConnectionState.LISTENING);
        } else {
            setConnectionState(ConnectionState.CONNECTING);
        }

        transport.startListening(this::onIncomingBytes);
    }

    public synchronized void stop() {
        isRunning = false;
        transport.stopListening();
        setConnectionState(ConnectionState.DISCONNECTED);
    }

    public synchronized void requestHealthPermission(Activity activity, Callback callback) {
        transport.requestPermission(activity, new PeerTransport.AuthCallback() {
            @Override
            public void onSuccess() {
                synchronized (WearEngineBridge.this) {
                    if (isRunning) {
                        setConnectionState(ConnectionState.CONNECTING);
                        transport.startListening(WearEngineBridge.this::onIncomingBytes);
                    } else {
                        setConnectionState(ConnectionState.DISCONNECTED);
                    }
                }
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onError(String error) {
                synchronized (WearEngineBridge.this) {
                    if (!transport.isAvailable()) {
                        setConnectionState(ConnectionState.UNAVAILABLE);
                    } else if (!transport.hasPermission()) {
                        setConnectionState(ConnectionState.PERMISSION_NEEDED);
                    } else {
                        setConnectionState(ConnectionState.ERROR);
                    }
                }
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
    }

    public void requestHealthPermission(Callback callback) {
        requestHealthPermission(null, callback);
    }

    public void onIncomingBytes(byte[] payload) {
        String rawJson = decodeUtf8(payload);
        if (rawJson == null) {
            String errResponse = WatchProtocol.error("unknown", "unknown", "BAD_REQUEST", "Non-UTF-8 or corrupt payload");
            transport.send(errResponse.getBytes(StandardCharsets.UTF_8), null);
            return;
        }

        repository.handleWatchJson(rawJson, responseJson -> {
            if (responseJson != null) {
                transport.send(responseJson.getBytes(StandardCharsets.UTF_8), null);
            }
        });
    }

    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public void addStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.add(listener);
        }
    }

    public void removeStateListener(StateListener listener) {
        if (listener != null) {
            stateListeners.remove(listener);
        }
    }

    private synchronized void evaluateState() {
        if (!isRunning) {
            setConnectionState(ConnectionState.DISCONNECTED);
            return;
        }
        if (!transport.isAvailable()) {
            setConnectionState(ConnectionState.UNAVAILABLE);
            return;
        }
        if (!transport.hasPermission()) {
            setConnectionState(ConnectionState.PERMISSION_NEEDED);
            return;
        }
        if (transport.isListening()) {
            setConnectionState(ConnectionState.LISTENING);
        } else {
            setConnectionState(ConnectionState.CONNECTING);
        }
    }

    private void setConnectionState(ConnectionState newState) {
        this.connectionState = newState;
        for (StateListener listener : stateListeners) {
            try {
                listener.onStateChanged(newState);
            } catch (Exception ignored) {
            }
        }
    }

    private static String decodeUtf8(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder();
        decoder.onMalformedInput(CodingErrorAction.REPORT);
        decoder.onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer chars = decoder.decode(ByteBuffer.wrap(bytes));
            return chars.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
