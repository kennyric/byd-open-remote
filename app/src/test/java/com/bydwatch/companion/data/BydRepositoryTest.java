package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;

import javax.net.ssl.SSLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

public class BydRepositoryTest {

    private InMemoryConfigStore configStore;
    private FakeBydGateway fakeGateway;
    private BydRepository repository;

    @Before
    public void setUp() {
        configStore = new InMemoryConfigStore();
        configStore.setUsername("driver@example.com");
        configStore.setPassword("SecretPass123");
        configStore.setPin("123456");
        configStore.setVin("LC0TESTVIN1234567");
        configStore.setRegion("id");
        configStore.setDefaultTempC(17);

        fakeGateway = new FakeBydGateway();
        repository = new BydRepository(fakeGateway, configStore);
    }

    @After
    public void tearDown() {
        repository.shutdown();
    }

    private JSONObject executeCommand(String rawJson) throws Exception {
        CompletableFuture<String> future = new CompletableFuture<>();
        repository.handleWatchJson(rawJson, future::complete);
        String resultStr = future.get(5, TimeUnit.SECONDS);
        return new JSONObject(resultStr);
    }

    @Test
    public void returnsNotConfiguredWhenCredentialsMissing() throws Exception {
        configStore.clear();

        JSONObject resp = executeCommand("{\"id\":\"1\",\"action\":\"status\"}");

        assertEquals("1", resp.getString("id"));
        assertFalse(resp.getBoolean("ok"));
        assertEquals("status", resp.getString("action"));
        assertEquals("NOT_CONFIGURED", resp.getString("err"));
        assertEquals(0, fakeGateway.fetchRealtimeCalls.get());
        assertEquals(0, fakeGateway.loginCalls.get());
    }

    @Test
    public void mapsInvalidPinToPinInvalidWithoutRetrying() throws Exception {
        fakeGateway.pinValid = false;

        JSONObject resp = executeCommand("{\"id\":\"2\",\"action\":\"lock\"}");

        assertEquals("2", resp.getString("id"));
        assertFalse(resp.getBoolean("ok"));
        assertEquals("lock", resp.getString("action"));
        assertEquals("PIN_INVALID", resp.getString("err"));
        assertEquals(1, fakeGateway.verifyPinCalls.get());
        assertEquals(0, fakeGateway.lockCalls.get());
    }

    @Test
    public void mapsCode2003AccountUnboundWithoutMisclassifyingAsPinInvalid() throws Exception {
        fakeGateway.transportError = new IOException(
                "Control PIN verification failed: code=2003 Akun ini tidak terikat dengan kendaraan.");

        JSONObject resp = executeCommand("{\"id\":\"2b\",\"action\":\"unlock\"}");

        assertEquals("2b", resp.getString("id"));
        assertFalse(resp.getBoolean("ok"));
        assertEquals("ACCOUNT_UNBOUND", resp.getString("err"));
        assertTrue(resp.getString("msg").contains("Fetch VIN"));
    }

    @Test
    public void serializesConcurrentCommandsAndAutoRefreshesAfterLock() throws Exception {
        fakeGateway.operationDelayMs = 50; // Add latency to observe concurrency

        CompletableFuture<String> f1 = new CompletableFuture<>();
        CompletableFuture<String> f2 = new CompletableFuture<>();

        // Submit concurrent lock and ac_on requests
        repository.handleWatchJson("{\"id\":\"10\",\"action\":\"lock\"}", f1::complete);
        repository.handleWatchJson("{\"id\":\"11\",\"action\":\"ac_on\",\"tempC\":17}", f2::complete);

        JSONObject resp1 = new JSONObject(f1.get(5, TimeUnit.SECONDS));
        JSONObject resp2 = new JSONObject(f2.get(5, TimeUnit.SECONDS));

        assertTrue(resp1.getBoolean("ok"));
        assertEquals("10", resp1.getString("id"));
        assertEquals("lock", resp1.getString("action"));

        assertTrue(resp2.getBoolean("ok"));
        assertEquals("11", resp2.getString("id"));
        assertEquals("ac_on", resp2.getString("action"));

        // Max concurrency must be strictly 1 (serialized queue)
        assertEquals(1, fakeGateway.maxObservedConcurrency.get());
        assertEquals(1, fakeGateway.lockCalls.get());
        assertEquals(1, fakeGateway.startClimateCalls.get());
        // Each mutating command auto-refreshes telemetry: 1 after lock, 1 after ac_on = 2 total
        assertEquals(2, fakeGateway.fetchRealtimeCalls.get());
    }

    @Test
    public void returnsBadRequestOnMalformedWatchPayload() throws Exception {
        // Corrupt string
        JSONObject resp1 = executeCommand("invalid-json");
        assertFalse(resp1.getBoolean("ok"));
        assertEquals("BAD_REQUEST", resp1.getString("err"));

        // Unknown action
        JSONObject resp2 = executeCommand("{\"id\":\"99\",\"action\":\"explode\"}");
        assertFalse(resp2.getBoolean("ok"));
        assertEquals("99", resp2.getString("id"));
        assertEquals("explode", resp2.getString("action"));
        assertEquals("BAD_REQUEST", resp2.getString("err"));
    }

    @Test
    public void mapsAuthFailureToAuthFailed() throws Exception {
        fakeGateway.authValid = false;

        JSONObject resp = executeCommand("{\"id\":\"30\",\"action\":\"status\"}");

        assertFalse(resp.getBoolean("ok"));
        assertEquals("AUTH_FAILED", resp.getString("err"));
    }

    @Test
    public void mapsCarOfflineToCarOffline() throws Exception {
        fakeGateway.carOnline = false;

        JSONObject resp = executeCommand("{\"id\":\"31\",\"action\":\"status\"}");

        assertFalse(resp.getBoolean("ok"));
        assertEquals("CAR_OFFLINE", resp.getString("err"));
    }

    @Test
    public void mapsTimeoutToTimeout() throws Exception {
        fakeGateway.timeout = true;

        JSONObject resp = executeCommand("{\"id\":\"32\",\"action\":\"status\"}");

        assertFalse(resp.getBoolean("ok"));
        assertEquals("TIMEOUT", resp.getString("err"));
    }

    @Test
    public void mapsOfflinePhoneTransportExceptionsToTimeout() throws Exception {
        fakeGateway.transportError = new UnknownHostException("dilinkappoversea-id.byd.auto");

        JSONObject resp = executeCommand("{\"id\":\"34\",\"action\":\"status\"}");

        assertFalse(resp.getBoolean("ok"));
        assertEquals("TIMEOUT", resp.getString("err"));
    }

    @Test
    public void doesNotMisclassifyWordsContainingPinSubstring() throws Exception {
        fakeGateway.genericError = "stopping motor failed due to spinning tires";

        JSONObject resp = executeCommand("{\"id\":\"35\",\"action\":\"status\"}");

        assertFalse(resp.getBoolean("ok"));
        assertNotEquals("PIN_INVALID", resp.getString("err"));
        assertEquals("ERROR", resp.getString("err"));
    }

    @Test
    public void statusCommandFetchesTelemetryWithoutCallingMutatingCommands() throws Exception {
        JSONObject resp = executeCommand("{\"id\":\"33\",\"action\":\"status\"}");

        assertTrue(resp.getBoolean("ok"));
        assertEquals("status", resp.getString("action"));
        assertTrue(resp.has("state"));
        assertEquals(1, fakeGateway.fetchRealtimeCalls.get());
        assertEquals(0, fakeGateway.lockCalls.get());
        assertEquals(0, fakeGateway.unlockCalls.get());
        assertEquals(0, fakeGateway.startClimateCalls.get());
    }

    @Test
    public void autoRefreshesAfterUnlockAndAcOff() throws Exception {
        JSONObject respUnlock = executeCommand("{\"id\":\"40\",\"action\":\"unlock\"}");
        assertTrue(respUnlock.getBoolean("ok"));
        assertEquals("unlock", respUnlock.getString("action"));

        JSONObject respAcOff = executeCommand("{\"id\":\"41\",\"action\":\"ac_off\"}");
        assertTrue(respAcOff.getBoolean("ok"));
        assertEquals("ac_off", respAcOff.getString("action"));

        assertEquals(1, fakeGateway.unlockCalls.get());
        assertEquals(1, fakeGateway.stopClimateCalls.get());
        // 1 refresh after unlock + 1 refresh after ac_off = 2 total
        assertEquals(2, fakeGateway.fetchRealtimeCalls.get());
    }

    @Test
    public void lightsAndHornCommandsExecuteSuccessfully() throws Exception {
        JSONObject respLights = executeCommand("{\"id\":\"50\",\"action\":\"lights\"}");
        assertTrue(respLights.getBoolean("ok"));
        assertEquals("lights", respLights.getString("action"));
        assertEquals("Lights flashed", respLights.getString("msg"));
        assertEquals(1, fakeGateway.flashLightsCalls.get());

        JSONObject respHorn = executeCommand("{\"id\":\"51\",\"action\":\"horn\"}");
        assertTrue(respHorn.getBoolean("ok"));
        assertEquals("horn", respHorn.getString("action"));
        assertEquals("Horn sounded", respHorn.getString("msg"));
        assertEquals(1, fakeGateway.findCarCalls.get());
    }

    @Test
    public void propagatesErrorWhenGatewayLockReturnsFalse() throws Exception {
        fakeGateway.lockResult = false;

        JSONObject resp = executeCommand("{\"id\":\"52\",\"action\":\"lock\"}");

        assertFalse(resp.getBoolean("ok"));
        assertEquals("ERROR", resp.getString("err"));
        assertEquals("Failed to lock vehicle", resp.getString("msg"));
    }

    @Test
    public void autoRefreshExceptionFallsBackToCachedStatus() throws Exception {
        VehicleStatus cached = new VehicleStatus(88, 350, true, false, 24, 17, 12345L);
        repository.setCachedStatus(cached);
        fakeGateway.failOnFetchAfterMutating = true;

        JSONObject resp = executeCommand("{\"id\":\"53\",\"action\":\"lock\"}");

        assertTrue(resp.getBoolean("ok"));
        assertEquals("lock", resp.getString("action"));
        JSONObject state = resp.getJSONObject("state");
        assertEquals(88, state.getInt("soc"));
        assertEquals(350, state.getInt("rangeKm"));
    }

    @Test
    public void manualAcUsesElectricClimateWithTempGearAndFanLevel() throws Exception {
        configStore.setDetectedManualAc(true);
        JSONObject resp = executeCommand(
                "{\"id\":\"54\",\"action\":\"ac_on\",\"tempGear\":4,\"fanLevel\":6}");

        assertTrue(resp.getBoolean("ok"));
        assertEquals("ac_on", resp.getString("action"));
        assertEquals(1, fakeGateway.startElectricClimateCalls.get());
        assertEquals(0, fakeGateway.startClimateCalls.get());
        assertEquals(4, fakeGateway.lastTempGear);
        assertEquals(6, fakeGateway.lastWindLevel);
        assertEquals(ConfigStore.WIND_MODE_BODY, fakeGateway.lastWindMode);
        assertEquals(4, configStore.getDefaultTempGear());
        assertEquals(6, configStore.getDefaultFanLevel());
        assertEquals(ConfigStore.WIND_MODE_BODY, configStore.getDefaultWindMode());
        assertTrue(resp.getJSONObject("state").optBoolean("manAc", false));

        JSONObject resp2 = executeCommand(
                "{\"id\":\"55\",\"action\":\"ac_on\",\"tempGear\":2,\"fanLevel\":5,\"windMode\":2}");
        assertTrue(resp2.getBoolean("ok"));
        assertEquals(ConfigStore.WIND_MODE_BODY_FEET, fakeGateway.lastWindMode);
        assertEquals(ConfigStore.WIND_MODE_BODY_FEET, configStore.getDefaultWindMode());
    }

    @Test
    public void mapsFlakyNetworkAndRawApiErrorsToGracefulMessages() throws Exception {
        fakeGateway.transportError = new UnknownHostException(
                "Unable to resolve host \"dilinkappoversea-id.byd.auto\": No address associated with hostname");
        JSONObject dnsResp = executeCommand("{\"id\":\"56\",\"action\":\"status\"}");
        assertFalse(dnsResp.getBoolean("ok"));
        assertEquals("TIMEOUT", dnsResp.getString("err"));
        String dnsMsg = dnsResp.getString("msg");
        assertTrue(dnsMsg.contains("Unable to reach BYD Cloud"));
        assertFalse(dnsMsg.contains("dilinkappoversea-id.byd.auto"));
        assertFalse(dnsMsg.contains("No address associated"));

        fakeGateway.transportError = new IOException("wrapped failure", new SSLException("Connection reset by peer"));
        JSONObject tlsResp = executeCommand("{\"id\":\"57\",\"action\":\"status\"}");
        assertFalse(tlsResp.getBoolean("ok"));
        assertEquals("TIMEOUT", tlsResp.getString("err"));
        assertTrue(tlsResp.getString("msg").contains("Unstable internet connection"));

        fakeGateway.transportError = new SocketTimeoutException("Read timed out");
        JSONObject toResp = executeCommand("{\"id\":\"58\",\"action\":\"status\"}");
        assertFalse(toResp.getBoolean("ok"));
        assertEquals("TIMEOUT", toResp.getString("err"));
        assertTrue(toResp.getString("msg").contains("Sync timed out"));

        fakeGateway.transportError = null;
        fakeGateway.genericError = "Realtime request failed: HTTP 502 /vehicleInfo/vehicle/vehicleRealTimeRequest";
        JSONObject apiResp = executeCommand("{\"id\":\"59\",\"action\":\"status\"}");
        assertFalse(apiResp.getBoolean("ok"));
        assertTrue(apiResp.getString("msg").contains("BYD Cloud is temporarily busy"));
        assertFalse(apiResp.getString("msg").contains("502"));
        assertFalse(apiResp.getString("msg").contains("/vehicleInfo"));

        fakeGateway.genericError = null;
        fakeGateway.transportError = new UnknownHostException(
                "Unable to resolve host \"dilinkappoversea-id.byd.auto\": No address associated with hostname");
        CompletableFuture<String> fetchErr = new CompletableFuture<>();
        repository.fetchVehicles(new BydRepository.VehiclesCallback() {
            @Override public void onSuccess(List<VehicleSummary> vehicles, VehicleSummary active) {}
            @Override public void onError(String error) { fetchErr.complete(error); }
        });
        String fetchMsg = fetchErr.get(5, TimeUnit.SECONDS);
        assertTrue(fetchMsg.contains("Unable to reach BYD Cloud"));
        assertFalse(fetchMsg.contains("dilinkappoversea-id.byd.auto"));
    }

    // ── Test Fakes ─────────────────────────────────────────────────────────

    public static class InMemoryConfigStore implements ConfigStore {
        private String username;
        private String password;
        private String pin;
        private String vin;
        private String region = "id";
        private String countryCode = "";
        private String vehicleName = VehicleSummary.DEFAULT_MODEL_NAME;
        private String customVehicleName = "";
        private List<VehicleSummary> vehicles = java.util.Collections.emptyList();
        private String distanceUnit = com.bydwatch.companion.model.UnitPreferences.DIST_KM;
        private String tempUnit = com.bydwatch.companion.model.UnitPreferences.TEMP_C;
        private String pressureUnit = com.bydwatch.companion.model.UnitPreferences.PRES_KPA;
        private String watchTarget = "dev";
        private boolean alertsEnabled = false;
        private boolean disclaimerAccepted = false;
        private int widgetSyncIntervalMinutes = DEFAULT_WIDGET_SYNC_MINUTES;
        private boolean hideExtraControls = false;
        private boolean verboseLogsEnabled = false;
        private String widgetStatusJson = null;
        private long widgetLastSyncMs = 0L;
        private int defaultTempC = 17;
        private int defaultTempGear = DEFAULT_TEMP_GEAR;
        private int defaultFanLevel = DEFAULT_FAN_LEVEL;
        private int defaultWindMode = DEFAULT_WIND_MODE;
        private Boolean manualAcOverride = null;
        private boolean detectedManualAc = false;

        @Override public String getUsername() { return username; }
        @Override public void setUsername(String username) { this.username = username; }

        @Override public String getPassword() { return password; }
        @Override public void setPassword(String password) { this.password = password; }

        @Override public String getPin() { return pin; }
        @Override public void setPin(String pin) { this.pin = pin; }

        @Override public String getVin() { return vin; }
        @Override public void setVin(String vin) { this.vin = vin; }

        @Override public String getRegion() { return region != null ? region : "id"; }
        @Override public void setRegion(String region) { this.region = region; }

        @Override public String getCountryCode() { return countryCode != null ? countryCode : ""; }
        @Override public void setCountryCode(String countryCode) { this.countryCode = countryCode != null ? countryCode : ""; }

        @Override public String getVehicleName() { return vehicleName != null ? vehicleName : VehicleSummary.DEFAULT_MODEL_NAME; }
        @Override public void setVehicleName(String vehicleName) { this.vehicleName = vehicleName != null ? vehicleName : VehicleSummary.DEFAULT_MODEL_NAME; }

        @Override public String getCustomVehicleName() { return customVehicleName != null ? customVehicleName : ""; }
        @Override public void setCustomVehicleName(String customVehicleName) { this.customVehicleName = customVehicleName != null ? customVehicleName.trim() : ""; }

        @Override public List<VehicleSummary> getVehicles() { return vehicles != null ? vehicles : java.util.Collections.emptyList(); }
        @Override public void setVehicles(List<VehicleSummary> vehicles) { this.vehicles = vehicles != null ? vehicles : java.util.Collections.emptyList(); }

        @Override public String getDistanceUnit() { return distanceUnit; }
        @Override public void setDistanceUnit(String unit) { this.distanceUnit = com.bydwatch.companion.model.UnitPreferences.normalizeDistanceUnit(unit); }

        @Override public String getTempUnit() { return tempUnit; }
        @Override public void setTempUnit(String unit) { this.tempUnit = com.bydwatch.companion.model.UnitPreferences.normalizeTempUnit(unit); }

        @Override public String getPressureUnit() { return pressureUnit; }
        @Override public void setPressureUnit(String unit) { this.pressureUnit = com.bydwatch.companion.model.UnitPreferences.normalizePressureUnit(unit); }

        @Override public String getWatchTarget() { return watchTarget; }
        @Override public void setWatchTarget(String target) { this.watchTarget = "store".equalsIgnoreCase(target) ? "store" : "dev"; }

        @Override public boolean isAlertsEnabled() { return alertsEnabled; }
        @Override public void setAlertsEnabled(boolean enabled) { this.alertsEnabled = enabled; }

        @Override public boolean isDisclaimerAccepted() { return disclaimerAccepted; }
        @Override public void setDisclaimerAccepted(boolean accepted) { this.disclaimerAccepted = accepted; }

        @Override public int getWidgetSyncIntervalMinutes() { return widgetSyncIntervalMinutes; }
        @Override public void setWidgetSyncIntervalMinutes(int minutes) {
            this.widgetSyncIntervalMinutes = Math.max(
                    MIN_WIDGET_SYNC_MINUTES, Math.min(MAX_WIDGET_SYNC_MINUTES, minutes));
        }

        private int widgetStyle = DEFAULT_WIDGET_STYLE;
        @Override public int getWidgetStyle() { return widgetStyle; }
        @Override public void setWidgetStyle(int style) {
            this.widgetStyle = ConfigStore.normalizeWidgetStyle(style);
        }

        private String appLanguage = DEFAULT_APP_LANGUAGE;
        @Override public String getAppLanguage() { return appLanguage; }
        @Override public void setAppLanguage(String language) {
            this.appLanguage = ConfigStore.normalizeAppLanguage(language);
        }

        @Override public boolean isHideExtraControls() { return hideExtraControls; }
        @Override public void setHideExtraControls(boolean hide) { this.hideExtraControls = hide; }

        @Override public boolean isVerboseLogsEnabled() { return verboseLogsEnabled; }
        @Override public void setVerboseLogsEnabled(boolean enabled) { this.verboseLogsEnabled = enabled; }

        @Override public String getWidgetStatusJson() { return widgetStatusJson; }
        @Override public void setWidgetStatusJson(String json) { this.widgetStatusJson = json; }

        @Override public long getWidgetLastSyncMs() { return widgetLastSyncMs; }
        @Override public void setWidgetLastSyncMs(long syncMs) { this.widgetLastSyncMs = Math.max(0L, syncMs); }

        @Override public int getDefaultTempC() { return defaultTempC; }
        @Override public void setDefaultTempC(int defaultTempC) { this.defaultTempC = defaultTempC; }

        @Override public int getDefaultTempGear() { return defaultTempGear; }
        @Override public void setDefaultTempGear(int gear) {
            this.defaultTempGear = Math.max(MIN_TEMP_GEAR, Math.min(MAX_TEMP_GEAR, gear));
        }

        @Override public int getDefaultFanLevel() { return defaultFanLevel; }
        @Override public void setDefaultFanLevel(int level) {
            this.defaultFanLevel = Math.max(MIN_FAN_LEVEL, Math.min(MAX_FAN_LEVEL, level));
        }

        @Override public int getDefaultWindMode() { return defaultWindMode; }
        @Override public void setDefaultWindMode(int windMode) {
            this.defaultWindMode = (windMode == WIND_MODE_BODY || windMode == WIND_MODE_BODY_FEET
                    || windMode == WIND_MODE_FEET) ? windMode : DEFAULT_WIND_MODE;
        }

        @Override public boolean isManualAcMode() {
            return manualAcOverride != null ? manualAcOverride.booleanValue() : detectedManualAc;
        }
        @Override public void setManualAcMode(boolean manual) { this.manualAcOverride = Boolean.valueOf(manual); }
        @Override public Boolean getManualAcOverride() { return manualAcOverride; }
        @Override public boolean isDetectedManualAc() { return detectedManualAc; }
        @Override public void setDetectedManualAc(boolean detected) { this.detectedManualAc = detected; }

        private String actionCombosJson = null;
        private String widgetSlotsJson = null;
        private String homeCustomButtonsJson = null;
        private boolean showHomeClimate = true;
        private boolean showHomeAlerts = true;
        private boolean showHomeLocation = true;
        private boolean showHomeHealth = true;

        @Override public String getActionCombosJson() { return actionCombosJson; }
        @Override public void setActionCombosJson(String json) { this.actionCombosJson = json; }
        @Override public String getWidgetSlotsJson() { return widgetSlotsJson; }
        @Override public void setWidgetSlotsJson(String json) { this.widgetSlotsJson = json; }
        @Override public String getHomeCustomButtonsJson() { return homeCustomButtonsJson; }
        @Override public void setHomeCustomButtonsJson(String json) { this.homeCustomButtonsJson = json; }
        @Override public boolean isShowHomeClimate() { return showHomeClimate; }
        @Override public void setShowHomeClimate(boolean show) { this.showHomeClimate = show; }
        @Override public boolean isShowHomeAlerts() { return showHomeAlerts; }
        @Override public void setShowHomeAlerts(boolean show) { this.showHomeAlerts = show; }
        @Override public boolean isShowHomeLocation() { return showHomeLocation; }
        @Override public void setShowHomeLocation(boolean show) { this.showHomeLocation = show; }
        @Override public boolean isShowHomeHealth() { return showHomeHealth; }
        @Override public void setShowHomeHealth(boolean show) { this.showHomeHealth = show; }

        @Override
        public boolean isConfigured() {
            return username != null && !username.trim().isEmpty()
                    && password != null && !password.trim().isEmpty()
                    && pin != null && !pin.trim().isEmpty()
                    && vin != null && !vin.trim().isEmpty();
        }

        @Override
        public void clear() {
            username = null;
            password = null;
            pin = null;
            vin = null;
            region = "id";
            defaultTempC = 17;
            defaultTempGear = DEFAULT_TEMP_GEAR;
            defaultFanLevel = DEFAULT_FAN_LEVEL;
            defaultWindMode = DEFAULT_WIND_MODE;
            manualAcOverride = null;
            detectedManualAc = false;
            actionCombosJson = null;
            widgetSlotsJson = null;
            homeCustomButtonsJson = null;
            showHomeClimate = true;
            showHomeAlerts = true;
            showHomeLocation = true;
            showHomeHealth = true;
        }
    }

    public static class FakeBydGateway implements BydGateway {
        public final AtomicInteger loginCalls = new AtomicInteger();
        public final AtomicInteger verifyPinCalls = new AtomicInteger();
        public final AtomicInteger fetchRealtimeCalls = new AtomicInteger();
        public final AtomicInteger lockCalls = new AtomicInteger();
        public final AtomicInteger unlockCalls = new AtomicInteger();
        public final AtomicInteger startClimateCalls = new AtomicInteger();
        public final AtomicInteger startElectricClimateCalls = new AtomicInteger();
        public final AtomicInteger stopClimateCalls = new AtomicInteger();
        public volatile double lastTempCelsius = -1;
        public volatile int lastTempGear = -1;
        public volatile int lastWindLevel = -1;
        public volatile int lastWindMode = -1;
        public final AtomicInteger flashLightsCalls = new AtomicInteger();
        public final AtomicInteger findCarCalls = new AtomicInteger();
        public final AtomicInteger fetchGpsCalls = new AtomicInteger();

        public final AtomicInteger activeConcurrentOperations = new AtomicInteger();
        public final AtomicInteger maxObservedConcurrency = new AtomicInteger();

        public volatile boolean pinValid = true;
        public volatile boolean authValid = true;
        public volatile boolean carOnline = true;
        public volatile boolean timeout = false;
        public volatile boolean lockResult = true;
        public volatile boolean failOnFetchAfterMutating = false;
        public volatile long operationDelayMs = 0;
        public volatile IOException transportError = null;
        public volatile String genericError = null;

        public volatile VehicleStatus currentStatus =
                new VehicleStatus(80, 300, false, false, 25, 17, 1700000000L);
        public volatile VehicleLocation currentLocation = null;

        @Override
        public VehicleLocation fetchGpsInfo(String vin) throws Exception {
            enterOp();
            try {
                fetchGpsCalls.incrementAndGet();
                if (transportError != null) throw transportError;
                if (!authValid) {
                    throw new IOException("Login failed: code=1001 unauthorized");
                }
                return currentLocation;
            } finally {
                exitOp();
            }
        }

        private void enterOp() {
            int active = activeConcurrentOperations.incrementAndGet();
            maxObservedConcurrency.accumulateAndGet(active, Math::max);
            if (operationDelayMs > 0) {
                try {
                    Thread.sleep(operationDelayMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void exitOp() {
            activeConcurrentOperations.decrementAndGet();
        }

        @Override
        public void login() throws Exception {
            enterOp();
            try {
                loginCalls.incrementAndGet();
                if (transportError != null) throw transportError;
                if (!authValid) {
                    throw new IOException("Login failed: code=1001 invalid username or password");
                }
            } finally {
                exitOp();
            }
        }

        @Override
        public void verifyControlPassword(String vin) throws Exception {
            enterOp();
            try {
                verifyPinCalls.incrementAndGet();
                if (transportError != null) throw transportError;
                if (!authValid) {
                    throw new IOException("Login failed: code=1001 unauthorized");
                }
                if (!pinValid) {
                    throw new IOException("Control PIN verification failed: code=6011 invalid pin");
                }
            } finally {
                exitOp();
            }
        }

        @Override
        public VehicleStatus fetchVehicleRealtime(String vin) throws Exception {
            enterOp();
            try {
                fetchRealtimeCalls.incrementAndGet();
                if (failOnFetchAfterMutating) {
                    throw new IOException("Auto-refresh failed");
                }
                if (transportError != null) throw transportError;
                if (genericError != null) {
                    throw new IOException(genericError);
                }
                if (timeout) {
                    throw new SocketTimeoutException("Read timed out");
                }
                if (!authValid) {
                    throw new IOException("Login failed: code=1001 unauthorized");
                }
                if (!carOnline) {
                    throw new IOException("CAR_OFFLINE: vehicle t-box offline");
                }
                return currentStatus;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean lock(String vin) throws Exception {
            enterOp();
            try {
                lockCalls.incrementAndGet();
                if (!lockResult) return false;
                currentStatus = new VehicleStatus(currentStatus.soc, currentStatus.rangeKm,
                        true, currentStatus.acOn, currentStatus.cabinTempC, currentStatus.targetTempC, System.currentTimeMillis());
                return true;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean unlock(String vin) throws Exception {
            enterOp();
            try {
                unlockCalls.incrementAndGet();
                currentStatus = new VehicleStatus(currentStatus.soc, currentStatus.rangeKm,
                        false, currentStatus.acOn, currentStatus.cabinTempC, currentStatus.targetTempC, System.currentTimeMillis());
                return true;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean startClimate(String vin, double tempCelsius) throws Exception {
            enterOp();
            try {
                startClimateCalls.incrementAndGet();
                lastTempCelsius = tempCelsius;
                currentStatus = new VehicleStatus.Builder(
                        currentStatus.soc, currentStatus.rangeKm, currentStatus.locked,
                        true, currentStatus.cabinTempC, (int) Math.round(tempCelsius),
                        System.currentTimeMillis())
                        .manualAc(currentStatus.manualAc)
                        .build();
                return true;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean startElectricClimate(String vin, int tempGear, int windLevel) throws Exception {
            return startElectricClimate(vin, tempGear, windLevel, ConfigStore.DEFAULT_WIND_MODE);
        }

        @Override
        public boolean startElectricClimate(String vin, int tempGear, int windLevel, int windMode) throws Exception {
            enterOp();
            try {
                startElectricClimateCalls.incrementAndGet();
                lastTempGear = tempGear;
                lastWindLevel = windLevel;
                lastWindMode = windMode;
                currentStatus = new VehicleStatus.Builder(
                        currentStatus.soc, currentStatus.rangeKm, currentStatus.locked,
                        true, currentStatus.cabinTempC, currentStatus.targetTempC,
                        System.currentTimeMillis())
                        .manualAc(true)
                        .build();
                return true;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean stopClimate(String vin) throws Exception {
            enterOp();
            try {
                stopClimateCalls.incrementAndGet();
                currentStatus = new VehicleStatus(currentStatus.soc, currentStatus.rangeKm,
                        currentStatus.locked, false, currentStatus.cabinTempC, currentStatus.targetTempC, System.currentTimeMillis());
                return true;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean flashLights(String vin) throws Exception {
            enterOp();
            try {
                flashLightsCalls.incrementAndGet();
                return true;
            } finally {
                exitOp();
            }
        }

        @Override
        public boolean findCar(String vin) throws Exception {
            enterOp();
            try {
                findCarCalls.incrementAndGet();
                return true;
            } finally {
                exitOp();
            }
        }

        public final AtomicInteger ventCalls = new AtomicInteger();
        public final AtomicInteger batHeatCalls = new AtomicInteger();
        public final AtomicInteger seatHeatCalls = new AtomicInteger();
        public int lastSeatLevel = -1;

        @Override
        public boolean ventAllWindows(String vin) {
            ventCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean closeAllWindows(String vin) {
            return true;
        }

        @Override
        public boolean setBatteryHeat(String vin, boolean enable) {
            batHeatCalls.incrementAndGet();
            return true;
        }

        @Override
        public boolean setDriverSeatHeat(String vin, int level) {
            seatHeatCalls.incrementAndGet();
            lastSeatLevel = level;
            return true;
        }

        public volatile List<VehicleSummary> customVehicles = null;

        @Override
        public List<VehicleSummary> fetchVehicles() throws Exception {
            if (customVehicles != null) {
                return customVehicles;
            }
            return BydGateway.super.fetchVehicles();
        }
    }

    @Test
    public void comfortCommandsAndSwitchCarUpdateConfigAndState() throws Exception {
        fakeGateway.customVehicles = Arrays.asList(
                new VehicleSummary("LC0TESTVIN1234567", "Atto 1", "B1", "EV"),
                new VehicleSummary("LC0SECONDVIN99999", "Seal U", "B2", "EV")
        );
        configStore.setVehicles(fakeGateway.customVehicles);
        JSONObject vent = executeCommand("{\"id\":\"c1\",\"action\":\"vent_windows\"}");
        assertTrue(vent.getBoolean("ok"));
        assertEquals(1, fakeGateway.ventCalls.get());

        JSONObject bat = executeCommand("{\"id\":\"c2\",\"action\":\"bat_heat_on\"}");
        assertTrue(bat.getBoolean("ok"));
        assertEquals(1, fakeGateway.batHeatCalls.get());

        JSONObject seat = executeCommand("{\"id\":\"c3\",\"action\":\"seat_heat\",\"level\":2}");
        assertTrue(seat.getBoolean("ok"));
        assertEquals(2, fakeGateway.lastSeatLevel);

        JSONObject sw = executeCommand("{\"id\":\"c4\",\"action\":\"switch_car\",\"vin\":\"LC0SECONDVIN99999\"}");
        assertTrue(sw.getBoolean("ok"));
        assertEquals("LC0SECONDVIN99999", configStore.getVin());
        assertEquals("Seal U", configStore.getVehicleName());
        assertEquals("SEAL U", sw.getJSONObject("state").getString("modelName"));

        JSONObject replay = executeCommand("{\"id\":\"c4\",\"action\":\"switch_car\",\"vin\":\"LC0SECONDVIN99999\"}");
        assertFalse(replay.getBoolean("ok"));
        assertEquals("BAD_REQUEST", replay.getString("err"));

        JSONObject unknownVin = executeCommand("{\"id\":\"c5\",\"action\":\"switch_car\",\"vin\":\"UNKNOWN_VIN_999\"}");
        assertFalse(unknownVin.getBoolean("ok"));
        assertEquals("BAD_REQUEST", unknownVin.getString("err"));
        assertEquals("LC0SECONDVIN99999", configStore.getVin());
    }

    @Test
    public void acOnHonorsPerStepAcModeOverrideAndModelJsonRoundTrip() throws Exception {
        // Global Manual AC enabled, but command asks for explicit Auto mode
        configStore.setManualAcMode(true);
        JSONObject autoOverride = executeCommand(
                "{\"id\":\"ac_auto_1\",\"action\":\"ac_on\",\"acMode\":\"auto\",\"tempC\":21}");
        assertTrue(autoOverride.getBoolean("ok"));
        assertEquals(1, fakeGateway.startClimateCalls.get());
        assertEquals(0, fakeGateway.startElectricClimateCalls.get());
        assertEquals(21.0, fakeGateway.lastTempCelsius, 0.01);

        // Global Auto AC enabled, but command asks for explicit Manual preset
        configStore.setManualAcMode(false);
        JSONObject manualOverride = executeCommand(
                "{\"id\":\"ac_man_1\",\"action\":\"ac_on\",\"acMode\":\"manual\",\"tempGear\":5,\"fanLevel\":6,\"windMode\":2}");
        assertTrue(manualOverride.getBoolean("ok"));
        assertEquals(1, fakeGateway.startElectricClimateCalls.get());
        assertEquals(5, fakeGateway.lastTempGear);
        assertEquals(6, fakeGateway.lastWindLevel);
        assertEquals(2, fakeGateway.lastWindMode);

        // Model JSON serialization round-trips
        com.bydwatch.companion.model.CustomActionCombo combo =
                new com.bydwatch.companion.model.CustomActionCombo(
                        "cmb_test",
                        "Cool Ride",
                        com.bydwatch.companion.model.CustomActionCombo.ICON_FROST,
                        com.bydwatch.companion.model.CustomActionCombo.TONE_CYAN,
                        Arrays.asList(
                                new com.bydwatch.companion.model.CustomActionCombo.ComboStep(
                                        com.bydwatch.companion.protocol.WatchProtocol.ACTION_UNLOCK),
                                new com.bydwatch.companion.model.CustomActionCombo.ComboStep(
                                        com.bydwatch.companion.protocol.WatchProtocol.ACTION_AC_ON, true, true, 19, 6, 5, 2, -1)
                        )
                );
        configStore.setActionCombos(java.util.Collections.singletonList(combo));
        java.util.List<com.bydwatch.companion.model.CustomActionCombo> restoredCombos =
                configStore.getActionCombos();
        assertEquals(1, restoredCombos.size());
        assertEquals("Cool Ride", restoredCombos.get(0).name);
        assertEquals("frost", restoredCombos.get(0).iconKey);
        assertEquals("cyan", restoredCombos.get(0).tone);
        assertEquals(2, restoredCombos.get(0).steps.size());
        assertTrue(restoredCombos.get(0).steps.get(1).manualAc);
        assertEquals(6, restoredCombos.get(0).steps.get(1).tempGear);

        configStore.setWidgetSlots(Arrays.asList(
                com.bydwatch.companion.model.WidgetSlotConfig.combo("cmb_test", "Chill"),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_LIGHTS),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_VENT_WINDOWS),
                com.bydwatch.companion.model.WidgetSlotConfig.single(
                        com.bydwatch.companion.model.WidgetSlotConfig.SINGLE_LOCATION)
        ));
        java.util.List<com.bydwatch.companion.model.WidgetSlotConfig> restoredSlots =
                configStore.getWidgetSlots();
        assertEquals(4, restoredSlots.size());
        assertTrue(restoredSlots.get(0).isCombo());
        assertEquals("cmb_test", restoredSlots.get(0).targetId);
        assertEquals("Chill", restoredSlots.get(0).customLabel);
        assertEquals("lights", restoredSlots.get(1).targetId);
        assertEquals("vent_windows", restoredSlots.get(2).targetId);
        assertEquals("location", restoredSlots.get(3).targetId);
    }
}
