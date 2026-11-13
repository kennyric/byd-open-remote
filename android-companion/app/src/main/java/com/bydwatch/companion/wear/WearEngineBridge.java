package com.bydwatch.companion.wear;

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

        transport.startListening(this::onIncomingBytes);
        setConnectionState(ConnectionState.LISTENING);
    }

    public synchronized void stop() {
        isRunning = false;
        transport.stopListening();
        setConnectionState(ConnectionState.DISCONNECTED);
    }

    public synchronized void requestHealthPermission(Callback callback) {
        transport.requestPermission(new PeerTransport.AuthCallback() {
            @Override
            public void onSuccess() {
                synchronized (WearEngineBridge.this) {
                    if (isRunning) {
                        transport.startListening(WearEngineBridge.this::onIncomingBytes);
                        setConnectionState(ConnectionState.LISTENING);
                    }
                }
                if (callback != null) {
                    callback.onSuccess();
                }
            }

            @Override
            public void onError(String error) {
                if (callback != null) {
                    callback.onError(error);
                }
            }
        });
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
