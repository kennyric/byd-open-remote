package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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

    // ── Test Fakes ─────────────────────────────────────────────────────────

    public static class InMemoryConfigStore implements ConfigStore {
        private String username;
        private String password;
        private String pin;
        private String vin;
        private String region = "id";
        private int defaultTempC = 17;

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

        @Override public int getDefaultTempC() { return defaultTempC; }
        @Override public void setDefaultTempC(int defaultTempC) { this.defaultTempC = defaultTempC; }

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
        }
    }

    public static class FakeBydGateway implements BydGateway {
        public final AtomicInteger loginCalls = new AtomicInteger();
        public final AtomicInteger verifyPinCalls = new AtomicInteger();
        public final AtomicInteger fetchRealtimeCalls = new AtomicInteger();
        public final AtomicInteger lockCalls = new AtomicInteger();
        public final AtomicInteger unlockCalls = new AtomicInteger();
        public final AtomicInteger startClimateCalls = new AtomicInteger();
        public final AtomicInteger stopClimateCalls = new AtomicInteger();
        public final AtomicInteger flashLightsCalls = new AtomicInteger();
        public final AtomicInteger findCarCalls = new AtomicInteger();

        public final AtomicInteger activeConcurrentOperations = new AtomicInteger();
        public final AtomicInteger maxObservedConcurrency = new AtomicInteger();

        public volatile boolean pinValid = true;
        public volatile boolean authValid = true;
        public volatile boolean carOnline = true;
        public volatile boolean timeout = false;
        public volatile long operationDelayMs = 0;

        public volatile VehicleStatus currentStatus =
                new VehicleStatus(80, 300, false, false, 25, 17, 1700000000L);

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
                currentStatus = new VehicleStatus(currentStatus.soc, currentStatus.rangeKm,
                        currentStatus.locked, true, currentStatus.cabinTempC, (int) Math.round(tempCelsius), System.currentTimeMillis());
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
    }
}
