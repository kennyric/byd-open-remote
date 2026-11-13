package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class CloudBydGatewayTest {

    private BydRepositoryTest.InMemoryConfigStore configStore;

    @Before
    public void setUp() {
        configStore = new BydRepositoryTest.InMemoryConfigStore();
        configStore.setUsername("test@byd.com");
        configStore.setPassword("Password123");
        configStore.setPin("654321");
        configStore.setRegion("id");
        configStore.setVin("VIN1234567890");
        configStore.setDefaultTempC(17);
    }

    @Test
    public void parseVehicleStatusHandlesDoorLockQuadVariants() throws Exception {
        CloudBydGateway gateway = new CloudBydGateway(null, null, configStore);

        // All 4 doors locked (code 2)
        JSONObject allLocked = new JSONObject();
        allLocked.put("leftFrontDoorLock", 2);
        allLocked.put("rightFrontDoorLock", 2);
        allLocked.put("leftRearDoorLock", 2);
        allLocked.put("rightRearDoorLock", 2);
        allLocked.put("elecPercent", 75);
        VehicleStatus s1 = gateway.parseVehicleStatus(allLocked);
        assertTrue(s1.locked);

        // One door unlocked (code 1)
        JSONObject oneUnlocked = new JSONObject();
        oneUnlocked.put("leftFrontDoorLock", 2);
        oneUnlocked.put("rightFrontDoorLock", 1);
        oneUnlocked.put("leftRearDoorLock", 2);
        oneUnlocked.put("rightRearDoorLock", 2);
        oneUnlocked.put("elecPercent", 75);
        VehicleStatus s2 = gateway.parseVehicleStatus(oneUnlocked);
        assertFalse(s2.locked);

        // Explicit "locked" boolean key takes precedence if present
        JSONObject explicit = new JSONObject();
        explicit.put("locked", true);
        explicit.put("leftFrontDoorLock", 1);
        VehicleStatus s3 = gateway.parseVehicleStatus(explicit);
        assertTrue(s3.locked);
    }

    @Test
    public void parseVehicleStatusHandlesSocAndRangeVariants() throws Exception {
        CloudBydGateway gateway = new CloudBydGateway(null, null, configStore);

        // Test elecPercent vs soc
        JSONObject vi1 = new JSONObject();
        vi1.put("elecPercent", 85.4);
        vi1.put("enduranceMileage", 250);
        VehicleStatus s1 = gateway.parseVehicleStatus(vi1);
        assertEquals(85, s1.soc);
        assertEquals(250, s1.rangeKm);

        // Test explicit soc and enduranceMileageV2 precedence
        JSONObject vi2 = new JSONObject();
        vi2.put("soc", 90);
        vi2.put("elecPercent", 40);
        vi2.put("enduranceMileageV2", 380);
        vi2.put("enduranceMileage", 200);
        VehicleStatus s2 = gateway.parseVehicleStatus(vi2);
        assertEquals(90, s2.soc);
        assertEquals(380, s2.rangeKm);
    }

    @Test
    public void parseVehicleStatusHandlesAcFlags() throws Exception {
        CloudBydGateway gateway = new CloudBydGateway(null, null, configStore);

        String[] acTrueKeys = new String[]{
                "airConditionStatus",
                "airConditioner",
                "airConditionerStatus",
                "autoAirConditioning",
                "eleAirACSwitch",
                "airState"
        };

        for (String key : acTrueKeys) {
            JSONObject vi = new JSONObject();
            vi.put(key, 1);
            vi.put("elecPercent", 50);
            VehicleStatus status = gateway.parseVehicleStatus(vi);
            assertTrue("Key " + key + "=1 must set acOn=true", status.acOn);
        }

        JSONObject viOff = new JSONObject();
        viOff.put("airConditionStatus", 0);
        assertFalse(gateway.parseVehicleStatus(viOff).acOn);
    }

    @Test
    public void parseVehicleStatusFiltersSentinelTemperatures() throws Exception {
        CloudBydGateway gateway = new CloudBydGateway(null, null, configStore);

        // Valid cabin temperature (24.6C -> 25C)
        JSONObject viValid = new JSONObject();
        viValid.put("tempInCar", 24.6);
        VehicleStatus s1 = gateway.parseVehicleStatus(viValid);
        assertEquals(Integer.valueOf(25), s1.cabinTempC);

        // Sentinel -129.0C -> null
        JSONObject viSentinel = new JSONObject();
        viSentinel.put("tempInCar", -129.0);
        VehicleStatus s2 = gateway.parseVehicleStatus(viSentinel);
        assertNull(s2.cabinTempC);

        // Out-of-bounds temperature (-50C or 100C) -> null
        JSONObject viExtreme = new JSONObject();
        viExtreme.put("tempInCar", 99.0);
        VehicleStatus s3 = gateway.parseVehicleStatus(viExtreme);
        assertNull(s3.cabinTempC);
    }

    @Test
    public void fetchVehicleRealtimeRejectsCarOfflineAndEmptyTelemetry() throws Exception {
        FakeClient fakeClient = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(configStore, cfg -> fakeClient);

        // 1. Vehicle offline (onlineState == 2)
        JSONObject offlineVi = new JSONObject();
        offlineVi.put("onlineState", 2);
        offlineVi.put("elecPercent", 50);
        fakeClient.realtimeResult = offlineVi;

        try {
            gateway.fetchVehicleRealtime("VIN1234");
            fail("Expected CAR_OFFLINE exception");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("CAR_OFFLINE"));
        }

        // 2. Null response
        fakeClient.realtimeResult = null;
        try {
            gateway.fetchVehicleRealtime("VIN1234");
            fail("Expected CAR_OFFLINE exception on null");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("CAR_OFFLINE"));
        }

        // 3. Unready / empty telemetry (e.g. only requestSerial from polling timeout)
        JSONObject unreadyVi = new JSONObject();
        unreadyVi.put("requestSerial", "SERIAL_123");
        fakeClient.realtimeResult = unreadyVi;

        try {
            gateway.fetchVehicleRealtime("VIN1234");
            fail("Expected SocketTimeoutException on missing telemetry fields");
        } catch (SocketTimeoutException expected) {
            assertTrue(expected.getMessage().contains("telemetry unavailable"));
        }
    }

    @Test
    public void syncConfigToClientInstantiatesFreshClientOnCredentialOrRegionChange() throws Exception {
        AtomicInteger clientInstantiations = new AtomicInteger();

        CloudBydGateway gateway = new CloudBydGateway(configStore, cfg -> {
            clientInstantiations.incrementAndGet();
            return new FakeClient();
        });

        assertEquals(1, clientInstantiations.get());
        assertEquals("test@byd.com", gateway.getConfig().username);
        assertEquals("id", gateway.getConfig().region);

        // No change -> no client re-instantiation
        gateway.login();
        assertEquals(1, clientInstantiations.get());

        // Credential change (username changed) -> client re-instantiated
        configStore.setUsername("newdriver@byd.com");
        gateway.login();
        assertEquals(2, clientInstantiations.get());
        assertEquals("newdriver@byd.com", gateway.getConfig().username);

        // Region change (id -> eu) -> client re-instantiated
        configStore.setRegion("eu");
        gateway.login();
        assertEquals(3, clientInstantiations.get());
        assertEquals("eu", gateway.getConfig().region);
    }

    @Test
    public void fetchFirstVinSyncsConfigAndDelegatesToClient() throws Exception {
        FakeClient fakeClient = new FakeClient();
        fakeClient.firstVinResult = "VIN_DELEGATED_42";

        CloudBydGateway gateway = new CloudBydGateway(fakeClient, new BydCloudConfig(), configStore);
        configStore.setUsername("freshuser@byd.com");

        String vin = gateway.fetchFirstVin();

        assertEquals("VIN_DELEGATED_42", vin);
        assertEquals("freshuser@byd.com", gateway.getConfig().username);
    }

    // ── Fake BydCloudClient ───────────────────────────────────────────────

    private static class FakeClient extends BydCloudClient {
        JSONObject realtimeResult;
        String firstVinResult = "VIN_DEFAULT";

        FakeClient() {
            super(new BydCloudConfig());
        }

        @Override
        public String fetchFirstVin() {
            return firstVinResult;
        }

        @Override
        public JSONObject fetchVehicleRealtime(String vin) {
            return realtimeResult;
        }

        @Override
        public synchronized void login() {
            // no-op
        }

        @Override
        public void verifyControlPassword(String vin) {
            // no-op
        }

        @Override
        public boolean lock(String vin) {
            return true;
        }

        @Override
        public boolean unlock(String vin) {
            return true;
        }

        @Override
        public boolean startClimate(String vin, double tempCelsius) {
            return true;
        }

        @Override
        public boolean stopClimate(String vin) {
            return true;
        }

        @Override
        public boolean flashLights(String vin) {
            return true;
        }

        @Override
        public boolean findCar(String vin) {
            return true;
        }
    }
}
