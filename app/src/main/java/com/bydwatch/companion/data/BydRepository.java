package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.bydwatch.companion.protocol.WatchCommand;
import com.bydwatch.companion.protocol.WatchProtocol;

import org.json.JSONObject;

import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Repository coordinating serialized BYD vehicle commands and state caching.
 */
public class BydRepository {

    public interface Callback {
        void onResponse(String responseJson);
    }

    public interface VinCallback {
        void onSuccess(String vin);
        void onError(String error);
    }

    public interface VehiclesCallback {
        void onSuccess(List<VehicleSummary> vehicles, VehicleSummary active);
        void onError(String error);
    }

    public interface StatusListener {
        void onStatusUpdated(VehicleStatus status);
    }

    private static final Pattern PIN_PATTERN =
            Pattern.compile("(?i)(?:\\bpin\\b|control pin|commandpwd|pin_invalid)");

    private static final int MAX_RECENT_COMMAND_IDS = 128;

    private final BydGateway gateway;
    private final ConfigStore configStore;
    private final ExecutorService executor;
    private final AtomicReference<VehicleStatus> cachedStatus = new AtomicReference<>();
    private final java.util.List<StatusListener> statusListeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Object replayLock = new Object();
    private final Deque<String> recentCommandOrder = new ArrayDeque<>();
    private final Set<String> recentCommandIds = new HashSet<>();

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
        if (status != null) {
            for (StatusListener listener : statusListeners) {
                try {
                    listener.onStatusUpdated(status);
                } catch (Exception ignored) {}
            }
        }
    }

    public void addStatusListener(StatusListener listener) {
        if (listener != null) {
            statusListeners.add(listener);
        }
    }

    public void removeStatusListener(StatusListener listener) {
        if (listener != null) {
            statusListeners.remove(listener);
        }
    }

    public BydGateway getGateway() {
        return gateway;
    }

    public ConfigStore getConfigStore() {
        return configStore;
    }

    public ExecutorService getExecutor() {
        return executor;
    }

    /**
     * Authenticate and discover all vehicles on the account, saving the vehicle list
     * and active VIN/model name into ConfigStore.
     */
    public void fetchVehicles(VehiclesCallback cb) {
        executor.execute(() -> {
            String username = configStore.getUsername();
            String password = configStore.getPassword();
            if (username == null || username.trim().isEmpty()
                    || password == null || password.trim().isEmpty()) {
                if (cb != null) {
                    cb.onError("Username and password must be configured before fetching vehicles");
                }
                return;
            }

            try {
                gateway.login();
                List<VehicleSummary> vehicles = gateway.fetchVehicles();
                if (vehicles != null && !vehicles.isEmpty()) {
                    configStore.setVehicles(vehicles);
                    String currentVin = configStore.getVin();
                    VehicleSummary selected = null;
                    if (currentVin != null && !currentVin.trim().isEmpty()) {
                        for (VehicleSummary v : vehicles) {
                            if (currentVin.trim().equals(v.vin)) {
                                selected = v;
                                break;
                            }
                        }
                    }
                    if (selected == null) {
                        selected = vehicles.get(0);
                    }
                    configStore.setVin(selected.vin);
                    configStore.setVehicleName(selected.modelName);
                    if (cb != null) {
                        cb.onSuccess(vehicles, selected);
                    }
                } else {
                    if (cb != null) {
                        cb.onError("No vehicles found on BYD account");
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                if (cb != null) {
                    cb.onError(msg);
                }
            }
        });
    }

    /**
     * Authenticate and discover the first vehicle VIN, saving it to ConfigStore on success.
     * Serialized through the repository's single-threaded executor.
     */
    public void fetchFirstVin(VinCallback cb) {
        executor.execute(() -> {
            String username = configStore.getUsername();
            String password = configStore.getPassword();
            if (username == null || username.trim().isEmpty()
                    || password == null || password.trim().isEmpty()) {
                if (cb != null) {
                    cb.onError("Username and password must be configured before fetching VIN");
                }
                return;
            }

            try {
                gateway.login();
                List<VehicleSummary> vehicles = gateway.fetchVehicles();
                if (vehicles != null && !vehicles.isEmpty()) {
                    configStore.setVehicles(vehicles);
                    VehicleSummary first = vehicles.get(0);
                    String cleanVin = first.vin.trim();
                    configStore.setVin(cleanVin);
                    configStore.setVehicleName(first.modelName);
                    if (cb != null) {
                        cb.onSuccess(cleanVin);
                    }
                } else {
                    if (cb != null) {
                        cb.onError("No vehicles found on BYD account");
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.toString();
                if (cb != null) {
                    cb.onError(msg);
                }
            }
        });
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

        if (isDuplicateCommandId(cmd.id)) {
            return WatchProtocol.error(cmd.id, cmd.action, "BAD_REQUEST", "Duplicate or replayed command ID");
        }

        String vin = configStore.getVin();

        try {
            switch (cmd.action) {
                case WatchProtocol.ACTION_STATUS: {
                    VehicleStatus fresh = gateway.fetchVehicleRealtime(vin);
                    if (fresh != null) {
                        fresh = enrichedStatus(fresh);
                        setCachedStatus(fresh);
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Updated", fresh != null ? fresh : enrichedStatus(cachedStatus.get()));
                }
                case WatchProtocol.ACTION_LOCK: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.lock(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to lock vehicle");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Locked", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_UNLOCK: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.unlock(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to unlock vehicle");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Unlocked", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_AC_ON: {
                    gateway.verifyControlPassword(vin);
                    int temp = cmd.tempC;
                    boolean ok = gateway.startClimate(vin, temp);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to start AC");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "AC started", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_AC_OFF: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.stopClimate(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to stop AC");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "AC stopped", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_LIGHTS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.flashLights(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to flash lights");
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Lights flashed", enrichedStatus(cachedStatus.get()));
                }
                case WatchProtocol.ACTION_HORN: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.findCar(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to sound horn");
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Horn sounded", enrichedStatus(cachedStatus.get()));
                }
                case WatchProtocol.ACTION_VENT_WINDOWS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.ventAllWindows(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to vent windows");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Windows vented", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_CLOSE_WINDOWS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.closeAllWindows(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to close windows");
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Windows closed", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_BATTERY_HEAT_ON: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.setBatteryHeat(vin, true);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to start battery heat");
                    }
                    VehicleStatus prev = cachedStatus.get();
                    if (prev != null) {
                        setCachedStatus(prev.toBuilder().batteryHeatOn(true).build());
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Battery heat on", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_BATTERY_HEAT_OFF: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.setBatteryHeat(vin, false);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to stop battery heat");
                    }
                    VehicleStatus prev = cachedStatus.get();
                    if (prev != null) {
                        setCachedStatus(prev.toBuilder().batteryHeatOn(false).build());
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Battery heat off", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_SEAT_HEAT: {
                    gateway.verifyControlPassword(vin);
                    int nextLevel = cmd.level >= 0 && cmd.level <= 2
                            ? cmd.level
                            : nextSeatHeatLevel(cachedStatus.get());
                    boolean ok = gateway.setDriverSeatHeat(vin, nextLevel);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Failed to update seat climate");
                    }
                    VehicleStatus prev = cachedStatus.get();
                    if (prev != null) {
                        setCachedStatus(prev.toBuilder().driverSeatLevel(nextLevel).build());
                    }
                    String msg = nextLevel == 0 ? "Seat off" : (nextLevel == 2 ? "Seat high" : "Seat low");
                    return WatchProtocol.ok(cmd.id, cmd.action, msg, enrichedStatus(cachedStatus.get()));
                }
                case WatchProtocol.ACTION_SWITCH_CAR: {
                    String targetVin = cmd.vin;
                    if (targetVin == null || targetVin.isEmpty()) {
                        return WatchProtocol.error(cmd.id, cmd.action, "BAD_REQUEST", "Target VIN is required");
                    }
                    List<VehicleSummary> list = configStore.getVehicles();
                    if (!isAllowedVehicleVin(targetVin, list, vin)) {
                        return WatchProtocol.error(cmd.id, cmd.action, "BAD_REQUEST", "Unknown VIN not linked to account");
                    }
                    configStore.setVin(targetVin);
                    String resolvedName = configStore.getVehicleName();
                    if (list != null) {
                        for (VehicleSummary v : list) {
                            if (targetVin.equals(v.vin)) {
                                configStore.setVehicleName(v.modelName);
                                resolvedName = v.watchShortLabel();
                                break;
                            }
                        }
                    }
                    VehicleStatus fresh = gateway.fetchVehicleRealtime(targetVin);
                    if (fresh != null) {
                        fresh = enrichedStatus(fresh.toBuilder().vin(targetVin).modelName(resolvedName).build());
                        setCachedStatus(fresh);
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Switched to " + resolvedName, fresh);
                }
                case WatchProtocol.ACTION_LOCATION: {
                    BydGateway.VehicleLocation loc = gateway.fetchGpsInfo(vin);
                    if (loc == null || !VehicleStatus.isValidCoordinates(loc.latitude, loc.longitude)) {
                        return WatchProtocol.error(cmd.id, cmd.action, "UNAVAILABLE", "Car GPS location unavailable");
                    }
                    VehicleStatus prev = cachedStatus.get();
                    VehicleStatus base = prev != null
                            ? prev
                            : new VehicleStatus.Builder(0, 0, false, false, null,
                                    configStore.getDefaultTempC(), System.currentTimeMillis() / 1000L).build();
                    VehicleStatus updated = enrichedStatus(
                            base.toBuilder().location(loc.latitude, loc.longitude, loc.timestampSec).build());
                    setCachedStatus(updated);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Location updated", updated);
                }
                default:
                    return WatchProtocol.error(cmd.id, cmd.action, "BAD_REQUEST", "Unknown action: " + cmd.action);
            }
        } catch (Exception e) {
            return mapExceptionToError(cmd.id, cmd.action, e);
        }
    }

    private VehicleStatus enrichedStatus(VehicleStatus base) {
        if (base == null || configStore == null) {
            return base;
        }
        String activeVin = (base.vin != null && !base.vin.isEmpty()) ? base.vin : configStore.getVin();
        String modelName = (base.modelName != null && !base.modelName.isEmpty())
                ? base.modelName
                : VehicleSummary.normalizeModelName(configStore.getVehicleName());
        List<VehicleSummary> cars = !base.vehicles.isEmpty() ? base.vehicles : configStore.getVehicles();
        Double lat = base.latitude;
        Double lng = base.longitude;
        Long gpsTs = base.gpsTs;
        VehicleStatus prev = cachedStatus.get();
        if (!base.hasLocation() && prev != null && prev.hasLocation()
                && (activeVin == null || activeVin.isEmpty() || prev.vin == null || prev.vin.isEmpty()
                        || activeVin.equals(prev.vin))) {
            lat = prev.latitude;
            lng = prev.longitude;
            gpsTs = prev.gpsTs;
        }
        return base.toBuilder()
                .vin(activeVin)
                .modelName(modelName)
                .units(configStore.getDistanceUnit(), configStore.getTempUnit(), configStore.getPressureUnit())
                .location(lat, lng, gpsTs)
                .vehicles(cars)
                .build();
    }

    private boolean isDuplicateCommandId(String commandId) {
        if (commandId == null || commandId.isEmpty()) {
            return false;
        }
        synchronized (replayLock) {
            if (recentCommandIds.contains(commandId)) {
                return true;
            }
            if (recentCommandOrder.size() >= MAX_RECENT_COMMAND_IDS) {
                String oldest = recentCommandOrder.pollFirst();
                if (oldest != null) {
                    recentCommandIds.remove(oldest);
                }
            }
            recentCommandOrder.addLast(commandId);
            recentCommandIds.add(commandId);
            return false;
        }
    }

    private static boolean isAllowedVehicleVin(String targetVin, List<VehicleSummary> list, String activeVin) {
        if (targetVin == null || targetVin.isEmpty()) {
            return false;
        }
        if (list != null && !list.isEmpty()) {
            for (VehicleSummary v : list) {
                if (v != null && targetVin.equals(v.vin)) {
                    return true;
                }
            }
            return false;
        }
        return activeVin != null && targetVin.equals(activeVin);
    }

    private int nextSeatHeatLevel(VehicleStatus current) {
        int cur = current != null ? current.driverSeatLevel : 0;
        if (cur <= 0) return 1;
        if (cur == 1) return 2;
        return 0;
    }

    private VehicleStatus autoRefresh(String vin) {
        try {
            VehicleStatus fresh = gateway.fetchVehicleRealtime(vin);
            if (fresh != null) {
                setCachedStatus(fresh);
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

        // Immediate offline-phone transport failures or read timeouts
        if (e instanceof SocketTimeoutException
                || e instanceof InterruptedIOException
                || e instanceof TimeoutException
                || e instanceof UnknownHostException
                || e instanceof ConnectException
                || e instanceof NoRouteToHostException
                || msg.contains("TIMEOUT")
                || msg.contains("timed out")
                || msg.contains("timeout")
                || msg.contains("UnknownHostException")
                || msg.contains("ConnectException")
                || msg.contains("NoRouteToHostException")
                || msg.contains("Network unreachable")) {
            return "TIMEOUT";
        }

        // Specific PIN invalidity matching (avoid false matches on ping, mapping, etc.)
        if (PIN_PATTERN.matcher(msg).find()) {
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
