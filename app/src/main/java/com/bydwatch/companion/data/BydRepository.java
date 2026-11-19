package com.bydwatch.companion.data;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.bydwatch.companion.protocol.WatchCommand;
import com.bydwatch.companion.protocol.WatchProtocol;

import org.json.JSONObject;

import java.io.EOFException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Locale;

import javax.net.ssl.SSLException;
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
    /** Telemetry per VIN, so a widget bound to another car renders without changing the active one. */
    private final java.util.concurrent.ConcurrentHashMap<String, VehicleStatus> statusByVin =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile String lastStatusDiag = "";
    private volatile String lastErrorDetail = "";
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

    /** Last known telemetry for a specific car (widget binding), or null when never fetched. */
    public VehicleStatus getStatusForVin(String vin) {
        return vin == null || vin.isEmpty() ? null : statusByVin.get(vin);
    }

    /** The status to reason about for a VIN: the live one when active, the cached one otherwise. */
    private VehicleStatus prevFor(String vin) {
        if (vin != null && configStore != null && vin.equals(configStore.getVin())) {
            return cachedStatus.get();
        }
        return getStatusForVin(vin);
    }

    /** Publish a status to the active-car UI, or quietly cache it when it belongs to another car. */
    private void storeStatusFor(String vin, VehicleStatus updated) {
        if (updated == null) return;
        if (vin != null && configStore != null && vin.equals(configStore.getVin())) {
            setCachedStatus(updated);
        } else if (updated.vin != null && !updated.vin.isEmpty()) {
            statusByVin.put(updated.vin, updated);
        }
    }

    public String getLastStatusDiag() {
        return lastStatusDiag;
    }

    public void setCachedStatus(VehicleStatus status) {
        VehicleStatus previous = cachedStatus.getAndSet(status);
        if (status != null) {
            if (status.vin != null && !status.vin.isEmpty()) statusByVin.put(status.vin, status);
            for (StatusListener listener : statusListeners) {
                try {
                    listener.onStatusUpdated(status);
                } catch (Exception ignored) {}
            }
            if (changed(previous, status)) {
                VehicleStatusContract.log("status soc=" + status.soc + "% range=" + status.rangeKm
                        + status.distanceUnit + " ts=" + status.ts);
            }
            // Nudge an external consumer (the patched Google Maps) so it refetches immediately
            // instead of polling. Carries no data: the provider stays the single source of truth.
            BydWatchApp app = BydWatchApp.getInstance();
            if (app != null) VehicleStatusBroadcaster.notifyChanged(app);
        }
    }

    /**
     * Demo build only: supplies the synthetic sample car whenever a refresh finds no cached
     * status. A demo install has no cloud account, so without this the first failed sync would
     * replace the sample data with the "not configured" placeholder. Never set in release.
     */
    private volatile java.util.function.Supplier<VehicleStatus> demoProvider;
    /** The unmodified synthetic sample, used to rebuild every demo car from the same telemetry. */
    private volatile VehicleStatus demoBaseSample;
    static final String DEMO_BASE_VIN = "LGX0DEMO000000001";

    public void setDemoStatusProvider(java.util.function.Supplier<VehicleStatus> provider) {
        this.demoProvider = provider;
        // Remember the untouched sample so a demo car switch can rebuild it from the same telemetry.
        VehicleStatus sample = provider != null ? provider.get() : null;
        this.demoBaseSample = sample;
    }

    /**
     * Demo build only: per-VIN samples, so a two-car garage does not show identical numbers for
     * both cars in a screenshot. Cars missing from the map fall back to the base sample.
     */
    private volatile java.util.Map<String, VehicleStatus> demoFleet;

    public void setDemoFleet(java.util.Map<String, VehicleStatus> fleet) {
        this.demoFleet = fleet;
    }

    /** Demo builds only: true when the given VIN belongs to the synthetic sample garage. */
    boolean isDemoSampleVin(String vin) {
        if (demoBaseSample == null) return false;
        if (DEMO_BASE_VIN.equals(vin)) return true;
        java.util.Map<String, VehicleStatus> fleet = demoFleet;
        if (fleet != null && fleet.containsKey(vin)) return true;
        for (VehicleSummary v : demoBaseSample.vehicles) {
            if (v != null && vin.equals(v.vin)) return true;
        }
        return false;
    }

    /** The sample to render for a demo VIN: its own fleet entry, else the base sample. */
    private VehicleStatus demoSampleFor(String vin) {
        java.util.Map<String, VehicleStatus> fleet = demoFleet;
        if (fleet != null) {
            VehicleStatus own = fleet.get(vin);
            if (own != null) return own;
        }
        return demoBaseSample;
    }

    /**
     * Demo build only: telemetry for a specific garage VIN, so the Account garage card can show
     * per-car charge/range for cars that are not the active one. Returns null outside the demo.
     */
    public VehicleStatus demoSampleForVin(String vin) {
        java.util.Map<String, VehicleStatus> fleet = demoFleet;
        return fleet != null && vin != null ? fleet.get(vin) : null;
    }

    /** The status the UI should render: the live cache, or the demo sample when there is none. */
    public VehicleStatus currentStatusOrDemo() {
        VehicleStatus current = cachedStatus.get();
        if (current != null) {
            return current;
        }
        java.util.function.Supplier<VehicleStatus> provider = demoProvider;
        return provider != null ? provider.get() : null;
    }
    /** True when a field a consumer can see actually moved; keeps the drive log readable. */
    private static boolean changed(VehicleStatus previous, VehicleStatus current) {
        return previous == null
                || previous.soc != current.soc
                || previous.rangeKm != current.rangeKm
                || previous.ts != current.ts
                || !java.util.Objects.equals(previous.distanceUnit, current.distanceUnit);
    }

    private volatile long lastRequestedRefreshAt;

    /**
     * Refetches from the cloud on a consumer's request (the patched Maps poking on an interval).
     * Debounced here rather than trusted from the caller: the account is the thing at risk.
     */
    public void refreshNow() {
        long now = System.currentTimeMillis();
        if (now - lastRequestedRefreshAt < VehicleStatusContract.MIN_REFRESH_GAP_MS) {
            VehicleStatusContract.log("refresh request ignored (too soon)");
            return;
        }
        String vin = configStore.getVin();
        if (vin == null || vin.trim().isEmpty()) {
            VehicleStatusContract.log("refresh request ignored (no vehicle)");
            return;
        }
        lastRequestedRefreshAt = now;
        VehicleStatusContract.log("refresh requested by consumer");
        try {
            executor.execute(() -> autoRefresh(vin));
        } catch (RuntimeException rejected) {
            VehicleStatusContract.log("refresh request rejected");
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
                recordErrorDetail(e);
                String msg = toFriendlyErrorMessage(e, classifyErrorCode(e));
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
                recordErrorDetail(e);
                String msg = toFriendlyErrorMessage(e, classifyErrorCode(e));
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

        String activeVin = configStore.getVin();
        String vin = (cmd.vin != null && !cmd.vin.trim().isEmpty()) ? cmd.vin.trim() : activeVin;
        boolean backgroundTarget = vin != null && !vin.equals(activeVin);
        if (backgroundTarget && !isAllowedVehicleVin(vin, configStore.getVehicles(), activeVin)) {
            return WatchProtocol.error(cmd.id, cmd.action, "BAD_REQUEST", "Unknown VIN not linked to account");
        }

        try {
            switch (cmd.action) {
                case WatchProtocol.ACTION_STATUS: {
                    if (demoProvider != null) {
                        // Demo build: refreshing must not overwrite the sample car with placeholder data.
                        VehicleStatus sample = currentStatusOrDemo();
                        return WatchProtocol.ok(cmd.id, cmd.action, "Demo sample data", sample);
                    }
                    VehicleStatus fresh = gateway.fetchVehicleRealtimeFast(vin);
                    this.lastStatusDiag = (fresh != null && fresh.diagSummary != null)
                            ? fresh.diagSummary : "";
                    if (fresh != null) {
                        fresh = enrichedStatus(fresh);
                        if (backgroundTarget) {
                            // A widget bound to another car: cache it only, never hijack the app's view.
                            if (fresh.vin != null && !fresh.vin.isEmpty()) statusByVin.put(fresh.vin, fresh);
                        } else {
                            syncConfigStoreFromLiveAc(fresh);
                            setCachedStatus(fresh);
                            triggerBackgroundEnrichment(vin, fresh);
                        }
                    }
                    VehicleStatus statusFallback = backgroundTarget ? prevFor(vin) : cachedStatus.get();
                    return WatchProtocol.ok(cmd.id, cmd.action, "Updated",
                            fresh != null ? fresh : enrichedStatus(statusFallback));
                }
                case WatchProtocol.ACTION_LOCK: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.lock(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to lock vehicle"));
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Locked", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_UNLOCK: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.unlock(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to unlock vehicle"));
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Unlocked", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_AC_ON: {
                    gateway.verifyControlPassword(vin);
                    VehicleStatus prev = prevFor(vin);
                    boolean explicitAuto = "auto".equalsIgnoreCase(cmd.acMode);
                    boolean explicitManual = "manual".equalsIgnoreCase(cmd.acMode);
                    Boolean manualOverride = configStore.getManualAcOverride();
                    boolean useManualAc = explicitManual
                            || (!explicitAuto && (manualOverride != null
                                    ? manualOverride.booleanValue()
                                    : ((prev != null && prev.manualAc)
                                            || configStore.isDetectedManualAc()
                                            || cmd.tempGear > 0
                                            || cmd.fanLevel > 0
                                            || cmd.windMode > 0)));
                    int durationMin = cmd.acDurationMinutes > 0
                            ? ConfigStore.normalizeAcDurationMinutes(cmd.acDurationMinutes)
                            : configStore.getDefaultAcDurationMinutes();
                    boolean ok;
                    if (useManualAc) {
                        int gear = (cmd.tempGear >= ConfigStore.MIN_TEMP_GEAR && cmd.tempGear <= ConfigStore.MAX_TEMP_GEAR)
                                ? cmd.tempGear
                                : configStore.getDefaultTempGear();
                        int fan = (cmd.fanLevel >= ConfigStore.MIN_FAN_LEVEL && cmd.fanLevel <= ConfigStore.MAX_FAN_LEVEL)
                                ? cmd.fanLevel
                                : configStore.getDefaultFanLevel();
                        int windMode = (cmd.windMode == ConfigStore.WIND_MODE_BODY
                                || cmd.windMode == ConfigStore.WIND_MODE_BODY_FEET
                                || cmd.windMode == ConfigStore.WIND_MODE_FEET)
                                ? cmd.windMode
                                : configStore.getDefaultWindMode();
                        if (!explicitManual) {
                            configStore.setDefaultTempGear(gear);
                            configStore.setDefaultFanLevel(fan);
                            configStore.setDefaultWindMode(windMode);
                        }
                        ok = gateway.startElectricClimate(vin, gear, fan, windMode, durationMin);
                    } else {
                        int temp = cmd.tempC;
                        ok = gateway.startClimate(vin, temp, durationMin);
                    }
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to start AC"));
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "AC started", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_AC_OFF: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.stopClimate(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to stop AC"));
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "AC stopped", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_LIGHTS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.flashLights(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to flash lights"));
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Lights flashed", enrichedStatus(prevFor(vin)));
                }
                case WatchProtocol.ACTION_HORN: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.findCar(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to sound horn"));
                    }
                    return WatchProtocol.ok(cmd.id, cmd.action, "Horn sounded", enrichedStatus(prevFor(vin)));
                }
                case WatchProtocol.ACTION_VENT_WINDOWS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.ventAllWindows(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to vent windows"));
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Windows vented", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_CLOSE_WINDOWS: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.closeAllWindows(vin);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to close windows"));
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Windows closed", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_BATTERY_HEAT_ON: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.setBatteryHeat(vin, true);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to start battery heat"));
                    }
                    VehicleStatus prev = prevFor(vin);
                    if (prev != null) {
                        storeStatusFor(vin, prev.toBuilder().batteryHeatOn(true).build());
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Battery heat on", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_BATTERY_HEAT_OFF: {
                    gateway.verifyControlPassword(vin);
                    boolean ok = gateway.setBatteryHeat(vin, false);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to stop battery heat"));
                    }
                    VehicleStatus prev = prevFor(vin);
                    if (prev != null) {
                        storeStatusFor(vin, prev.toBuilder().batteryHeatOn(false).build());
                    }
                    VehicleStatus fresh = autoRefresh(vin);
                    return WatchProtocol.ok(cmd.id, cmd.action, "Battery heat off", enrichedStatus(fresh));
                }
                case WatchProtocol.ACTION_SEAT_HEAT: {
                    gateway.verifyControlPassword(vin);
                    int nextLevel = cmd.level >= 0 && cmd.level <= 2
                            ? cmd.level
                            : nextSeatHeatLevel(prevFor(vin));
                    boolean ok = gateway.setDriverSeatHeat(vin, nextLevel);
                    if (!ok) {
                        return WatchProtocol.error(cmd.id, cmd.action, "ERROR", detailedActionError("Failed to update seat climate"));
                    }
                    VehicleStatus prev = prevFor(vin);
                    if (prev != null) {
                        storeStatusFor(vin, prev.toBuilder().driverSeatLevel(nextLevel).build());
                    }
                    String msg = nextLevel == 0 ? "Seat off" : (nextLevel == 2 ? "Seat high" : "Seat low");
                    return WatchProtocol.ok(cmd.id, cmd.action, msg, enrichedStatus(prevFor(vin)));
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
                    if (!targetVin.equals(vin)) {
                        configStore.setDetectedManualAc(false);
                    }
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
                    if (demoProvider != null && isDemoSampleVin(targetVin)) {
                        // Demo build: there is no cloud account, so reshape the synthetic sample
                        // instead of fetching. Header, switcher and widgets all follow the choice.
                        VehicleStatus sample = demoSampleFor(targetVin);
                        if (sample == null) {
                            return WatchProtocol.error(cmd.id, cmd.action, "ERROR", "Demo sample unavailable");
                        }
                        configStore.setVin(targetVin);
                        VehicleStatus switched = sample.toBuilder()
                                .vin(targetVin)
                                .modelName(resolvedName)
                                .ts(System.currentTimeMillis() / 1000L)
                                .vehicles(list)
                                .build();
                        setCachedStatus(switched);
                        return WatchProtocol.ok(cmd.id, cmd.action, "Switched to " + resolvedName, switched);
                    }
                    configStore.setVin(targetVin);
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
                    VehicleStatus prev = prevFor(vin);
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
        VehicleStatus prev = prevFor(activeVin);
        if (!base.hasLocation() && prev != null && prev.hasLocation()
                && (activeVin == null || activeVin.isEmpty() || prev.vin == null || prev.vin.isEmpty()
                        || activeVin.equals(prev.vin))) {
            lat = prev.latitude;
            lng = prev.longitude;
            gpsTs = prev.gpsTs;
        }
        Boolean manualOverride = configStore.getManualAcOverride();
        boolean manualAc = manualOverride != null
                ? manualOverride.booleanValue()
                : (base.manualAc || configStore.isDetectedManualAc());
        return base.toBuilder()
                .vin(activeVin)
                .modelName(modelName)
                .manualAc(manualAc)
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

    private void syncConfigStoreFromLiveAc(VehicleStatus status) {
        if (status == null || !status.acOn || configStore == null) return;
        if (status.manualAc) {
            if (status.targetTempGear != null) {
                configStore.setDefaultTempGear(status.targetTempGear);
            }
            if (status.fanLevel != null) {
                configStore.setDefaultFanLevel(status.fanLevel);
            }
            if (status.windMode != null) {
                configStore.setDefaultWindMode(status.windMode);
            }
        } else if (status.targetTempC >= 17 && status.targetTempC <= 31) {
            configStore.setDefaultTempC(status.targetTempC);
        }
    }

    private VehicleStatus autoRefresh(String vin) {
        boolean active = vin != null && vin.equals(configStore.getVin());
        try {
            VehicleStatus fresh = gateway.fetchVehicleRealtime(vin);
            if (fresh != null) {
                if (active) {
                    setCachedStatus(fresh);
                } else if (fresh.vin != null && !fresh.vin.isEmpty()) {
                    // Another car: keep the app on its active car.
                    statusByVin.put(fresh.vin, fresh);
                }
                return fresh;
            }
        } catch (Exception e) {
            // Keep previous cached status on refresh error
        }
        return active ? cachedStatus.get() : getStatusForVin(vin);
    }

    private void triggerBackgroundEnrichment(String vin, VehicleStatus baseStatus) {
        if (baseStatus == null) return;
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            try {
                VehicleStatus enriched = gateway.enrichStatusAsync(vin, baseStatus);
                if (enriched != null) {
                    syncConfigStoreFromLiveAc(enriched);
                    setCachedStatus(enrichedStatus(enriched));
                }
            } catch (Exception ignored) {
                // Silent best-effort background enrichment
            }
        });
    }

    private String detailedActionError(String baseMessage) {
        String detail = gateway.getLastCommandError();
        if (detail == null || detail.trim().isEmpty()) {
            return baseMessage;
        }
        return baseMessage + " (" + detail.trim() + ")";
    }

    public String getLastErrorDetail() {
        return lastErrorDetail;
    }

    private void recordErrorDetail(Throwable e) {
        if (e == null) {
            lastErrorDetail = "";
            return;
        }
        String msg = e.getMessage() != null ? e.getMessage().replaceAll("\\s+", " ").trim() : "";
        Throwable cause = e.getCause();
        String causeMsg = (cause != null && cause.getMessage() != null)
                ? cause.getMessage().replaceAll("\\s+", " ").trim()
                : "";
        StringBuilder sb = new StringBuilder(e.getClass().getSimpleName());
        if (!msg.isEmpty()) {
            sb.append(": ").append(msg);
        }
        if (!causeMsg.isEmpty() && !causeMsg.equals(msg)) {
            sb.append(" | cause=").append(cause.getClass().getSimpleName()).append(": ").append(causeMsg);
        }
        lastErrorDetail = sb.toString();
    }

    private String mapExceptionToError(String id, String action, Exception e) {
        recordErrorDetail(e);
        String errCode = classifyErrorCode(e);
        String msg = toFriendlyErrorMessage(e, errCode);
        return WatchProtocol.error(id, action, errCode, msg);
    }

    private String classifyErrorCode(Exception e) {
        if (e == null) {
            return "ERROR";
        }
        String combinedLower = collectCauseMessagesLower(e);

        // Immediate offline-phone transport failures, flaky sockets, or read timeouts
        if (hasCauseInstance(e,
                SocketTimeoutException.class,
                InterruptedIOException.class,
                TimeoutException.class,
                UnknownHostException.class,
                ConnectException.class,
                NoRouteToHostException.class,
                SSLException.class,
                EOFException.class)
                || containsAny(combinedLower,
                        "timeout",
                        "timed out",
                        "unable to resolve host",
                        "no address associated with hostname",
                        "host is unresolved",
                        "failed to connect",
                        "unknownhostexception",
                        "connectexception",
                        "noroutetohostexception",
                        "no route to host",
                        "network unreachable",
                        "network is unreachable",
                        "connection refused",
                        "connection reset",
                        "broken pipe",
                        "software caused connection abort",
                        "unexpected end of stream",
                        "sslhandshake",
                        "chain validation",
                        "realtime telemetry unavailable")) {
            return "TIMEOUT";
        }

        String msg = e.getMessage() != null ? e.getMessage() : "";

        // Account-not-bound errors from verifyControlPassword (e.g. BYD code 2003)
        // must not fall through to PIN_INVALID just because the wrapper mentions "Control PIN".
        if (containsAny(combinedLower,
                "code=2003",
                "tidak terikat",
                "not bound")) {
            return "ACCOUNT_UNBOUND";
        }

        // Specific PIN invalidity matching (avoid false matches on ping, mapping, etc.)
        if (PIN_PATTERN.matcher(msg).find() || PIN_PATTERN.matcher(combinedLower).find()) {
            return "PIN_INVALID";
        }

        if (containsAny(combinedLower,
                "login failed",
                "auth_failed",
                "unauthorized",
                "401",
                "token")) {
            return "AUTH_FAILED";
        }

        if (containsAny(combinedLower,
                "car_offline",
                "offline",
                "vehicle t-box is offline")) {
            return "CAR_OFFLINE";
        }

        return "ERROR";
    }

    public static String toFriendlyErrorMessage(Throwable e) {
        return toFriendlyErrorMessage(e, null);
    }

    public static String toFriendlyErrorMessage(Throwable e, String errCode) {
        String combinedLower = collectCauseMessagesLower(e);

        if ("ACCOUNT_UNBOUND".equals(errCode)) {
            return "Vehicle VIN is not bound to this BYD account. Go to Account and tap Fetch VIN.";
        }
        if ("PIN_INVALID".equals(errCode)) {
            return "Incorrect 6-digit Remote Control PIN. Check your PIN in BYD Account.";
        }
        if ("AUTH_FAILED".equals(errCode)) {
            return "BYD Cloud sign-in failed. Check your email/phone, password, and selected region.";
        }
        if ("CAR_OFFLINE".equals(errCode)) {
            return "Vehicle is currently offline or out of cellular coverage.";
        }

        boolean dnsOrConnectFailure = hasCauseInstance(e,
                UnknownHostException.class,
                ConnectException.class,
                NoRouteToHostException.class)
                || containsAny(combinedLower,
                        "unable to resolve host",
                        "no address associated with hostname",
                        "host is unresolved",
                        "failed to connect",
                        "network is unreachable",
                        "network unreachable",
                        "connection refused",
                        "no route to host",
                        "enetunreach",
                        "ehostunreach");
        if (dnsOrConnectFailure) {
            return "Unable to reach BYD Cloud. Check your internet connection and try again.";
        }

        boolean interruptedOrSsl = hasCauseInstance(e, SSLException.class, EOFException.class)
                || containsAny(combinedLower,
                        "connection reset",
                        "broken pipe",
                        "software caused connection abort",
                        "unexpected end of stream",
                        "sslhandshake",
                        "chain validation",
                        "trust anchor");
        if (interruptedOrSsl) {
            return "Unstable internet connection interrupted the sync. Please try again.";
        }

        boolean timeoutFailure = "TIMEOUT".equals(errCode)
                || hasCauseInstance(e,
                        SocketTimeoutException.class,
                        InterruptedIOException.class,
                        TimeoutException.class)
                || containsAny(combinedLower,
                        "timed out",
                        "timeout",
                        "realtime telemetry unavailable");
        if (timeoutFailure) {
            return "Sync timed out — signal may be weak or BYD Cloud is slow. Please try again.";
        }

        if (containsAny(combinedLower,
                "1009",
                "temporarily unavailable",
                "too many requests",
                "http 429",
                "http 500",
                "http 502",
                "http 503",
                "http 504")) {
            return "BYD Cloud is temporarily busy. Please wait a moment and try again.";
        }

        if (containsAny(combinedLower,
                "no vehicles found",
                "no vehicle with vin")) {
            return "No vehicles found on this BYD account. Check your selected region and vehicle authorization.";
        }

        String raw = e != null && e.getMessage() != null ? e.getMessage().replaceAll("\\s+", " ").trim() : "";
        if (raw.isEmpty() || looksLikeRawTechnicalError(raw)) {
            return "Could not complete BYD Cloud sync. Please try again.";
        }
        return raw;
    }

    private static boolean looksLikeRawTechnicalError(String raw) {
        String lower = raw.toLowerCase(Locale.US);
        return containsAny(lower,
                ".byd.auto",
                "http ",
                "code=",
                "responddata",
                "envelope",
                "json",
                "exception",
                "java.",
                "okhttp",
                "/app/",
                "/control/",
                "/vehicle");
    }

    private static String collectCauseMessagesLower(Throwable e) {
        StringBuilder sb = new StringBuilder();
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            String msg = cur.getMessage();
            if (msg != null && !msg.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(msg.toLowerCase(Locale.US));
            }
            cur = cur.getCause();
            depth++;
        }
        return sb.toString();
    }

    @SafeVarargs
    private static boolean hasCauseInstance(Throwable e, Class<? extends Throwable>... types) {
        Throwable cur = e;
        int depth = 0;
        while (cur != null && depth < 8) {
            for (Class<? extends Throwable> type : types) {
                if (type.isInstance(cur)) {
                    return true;
                }
            }
            cur = cur.getCause();
            depth++;
        }
        return false;
    }

    private static boolean containsAny(String haystackLower, String... needles) {
        if (haystackLower == null || haystackLower.isEmpty()) return false;
        for (String needle : needles) {
            if (haystackLower.contains(needle)) {
                return true;
            }
        }
        return false;
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
