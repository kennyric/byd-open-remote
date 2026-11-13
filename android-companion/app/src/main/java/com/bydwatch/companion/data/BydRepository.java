package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.protocol.WatchCommand;
import com.bydwatch.companion.protocol.WatchProtocol;

import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Repository coordinating serialized BYD vehicle commands and state caching.
 */
public class BydRepository {

    public interface Callback {
        void onResponse(String responseJson);
    }

    private final BydGateway gateway;
    private final ConfigStore configStore;
    private final ExecutorService executor;
    private final AtomicReference<VehicleStatus> cachedStatus = new AtomicReference<>();

    public BydRepository(BydGateway gateway, ConfigStore configStore) {
        this(gateway, configStore, Executors.newSingleThreadExecutor());
    }

    public BydRepository(BydGateway gateway, ConfigStore configStore, ExecutorService executor) {
        this.gateway = gateway;
        this.configStore = configStore;
        this.executor = executor;
    }

    public VehicleStatus getCachedStatus() {
        return cachedStatus.get();
    }

    public void setCachedStatus(VehicleStatus status) {
        cachedStatus.set(status);
    }

    /**
     * Submit an incoming Watch JSON request for serialized execution.
     * Responses are returned via the callback.
     */
    public void handleWatchJson(String rawJson, Callback cb) {
        executor.execute(() -> {
            String responseJson = processWatchJson(rawJson);
            if (cb != null) {
                cb.onResponse(responseJson);
            }
        });
    }

    /**
     * Process a watch JSON command synchronously on the current thread.
     */
    public String processWatchJson(String rawJson) {
        WatchCommand cmd;
        try {
            cmd = WatchProtocol.parseCommand(rawJson);
        } catch (Exception e) {
            String id = extractFieldOrFallback(rawJson, "id", "unknown");
            String action = extractFieldOrFallback(rawJson, "action", "unknown");
            return WatchProtocol.error(id, action, "BAD_REQUEST", "Malformed request or unknown action: " + e.getMessage());
        }

        if (!configStore.isConfigured()) {
            return WatchProtocol.error(cmd.id, cmd.action, "NOT_CONFIGURED", "BYD credentials are not configured");
        }

        String vin = configStore.getVin();

        try {
            switch (cmd.action) {
                case WatchProtocol.ACTION_STATUS: {
                    VehicleStatus fresh = gateway.fetchVehicleRealtime(vin);
                    if (fresh != null) {
                        cachedStatus.set(fresh);
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Updated", fresh != null ? fresh : cachedStatus.get());
                }
                case WatchProtocol.ACTION_LOCK: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.lock(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to lock vehicle");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Locked", fresh);
                }
                case WatchProtocol.ACTION_UNLOCK: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.unlock(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to unlock vehicle");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Unlocked", fresh);
                }
                case WatchProtocol.ACTION_AC_ON: {
                    gateway.verifyControlPassword(vin);
                    int temp = cmd.tempC > 0 ? cmd.tempC : configStore.getDefaultTempC();
                    boolean ok = gateway.startClimate(vin, temp);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to start AC");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "AC started", fresh);
                }
                case WatchProtocol.ACTION_AC_OFF: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.stopClimate(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to stop AC");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "AC stopped", fresh);
                }
                case WatchProtocol.ACTION_LIGHTS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.flashLights(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to flash lights");
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Lights flashed", cachedStatus.get());
                }
                case WatchProtocol.ACTION_HORN: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.findCar(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to sound horn");
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Horn sounded", cachedStatus.get());
                }
                default:
                    return WatchProtocol.error(cmd.id, cmd.action, "BAD_REQUEST", "Unknown action: " + cmd.action);
            }
        } catch (Exception e) {
            return mapExceptionToError(cmd.id, cmd.action, e);
        }
    }

    private VehicleStatus autoRefresh(String vin) {
        try {
            VehicleStatus fresh = gateway.fetchVehicleRealtime(vin);
            if (fresh != null) {
                cachedStatus.set(fresh);
                return fresh;
            }
        } catch (Exception e) {
            // Keep previous cached status on refresh error
        }
        return cachedStatus.get();
    }

    private String mapExceptionToError(String id, String action, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : e.toString();
        String errCode = classifyErrorCode(e);
        return WatchProtocol.error(id, action, errCode, msg);
    }

    private String classifyErrorCode(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        if (e instanceof SocketTimeoutException
                || e instanceof InterruptedIOException
                || e instanceof TimeoutException
                || msg.contains("TIMEOUT")
                || msg.contains("timed out")
                || msg.contains("timeout")) {
            return "TIMEOUT";
        }

        if (msg.contains("PIN")
                || msg.contains("pin")
                || msg.contains("commandPwd")
                || msg.contains("PIN_INVALID")
                || msg.contains("Control PIN")) {
            return "PIN_INVALID";
        }

        if (msg.contains("Login failed")
                || msg.contains("AUTH_FAILED")
                || msg.contains("unauthorized")
                || msg.contains("401")
                || msg.contains("token")) {
            return "AUTH_FAILED";
        }

        if (msg.contains("CAR_OFFLINE")
                || msg.contains("offline")
                || msg.contains("Vehicle T-Box is offline")) {
            return "CAR_OFFLINE";
        }

        return "ERROR";
    }

    private String extractFieldOrFallback(String rawJson, String fieldName, String fallback) {
        if (rawJson == null) return fallback;
        try {
            JSONObject obj = new JSONObject(rawJson);
            if (obj.has(fieldName) && !obj.isNull(fieldName)) {
                String val = obj.optString(fieldName, fallback);
                return (val != null && !val.trim().isEmpty()) ? val : fallback;
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(2, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
