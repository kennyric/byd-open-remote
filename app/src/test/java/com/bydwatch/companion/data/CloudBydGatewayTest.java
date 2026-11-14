package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
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
    public void applyConfigHashesControlPinAsUppercaseMd5Hex() throws Exception {
        CloudBydGateway gateway = new CloudBydGateway(configStore, cfg -> new FakeClient());

        // "654321" -> uppercase MD5 hex expected by BYD verifyControlPassword & remoteControl
        assertEquals(BydCryptoUtils.md5Hex("654321"), gateway.getConfig().commandPwd);
        assertEquals("C33367701511B4F6020EC61DED352059", gateway.getConfig().commandPwd);

        // Changing PIN must refresh commandPwd with the new MD5 hash
        configStore.setPin("123456");
        gateway.verifyControlPassword("VIN1234567890");
        assertEquals("E10ADC3949BA59ABBE56E057F20F883E", gateway.getConfig().commandPwd);
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

    @Test
    public void parseVehicleStatusExtractsTpmsChargingDoorsAndOdometer() throws Exception {
        CloudBydGateway gateway = new CloudBydGateway(null, null, configStore);

        JSONObject vi = new JSONObject();
        vi.put("elecPercent", 82);
        vi.put("enduranceMileageV2", 335);
        vi.put("chargeState", 1);
        vi.put("remainingHours", 1);
        vi.put("remainingMinutes", 15);
        vi.put("leftFrontDoor", 1);
        vi.put("rightFrontDoor", 0);
        vi.put("trunkLid", 1);
        vi.put("leftFrontWindow", 2);
        vi.put("totalMileageV2", 18420);
        vi.put("tempOutCar", 31);
        vi.put("leftFrontTirePressure", 2.45);
        vi.put("rightFrontTirePressure", 248);
        vi.put("leftRearTirePressure", 39);
        vi.put("rightRearTirePressure", 242);
        vi.put("batteryHeatState", 1);
        vi.put("mainSeatHeatState", 3);

        VehicleStatus status = gateway.parseVehicleStatus(vi);
        assertEquals(VehicleStatus.CHARGING_ACTIVE, status.chargingState);
        assertEquals(Integer.valueOf(75), status.chargeRemainingMinutes);
        assertEquals(1, status.doorsOpenCount);
        assertEquals(1, status.windowsOpenCount);
        assertTrue(status.trunkOpen);
        assertEquals(Integer.valueOf(18420), status.totalMileageKm);
        assertEquals(Integer.valueOf(31), status.outsideTempC);
        assertEquals(Integer.valueOf(245), status.tireLfKpa);
        assertEquals(Integer.valueOf(248), status.tireRfKpa);
        assertEquals(Integer.valueOf(269), status.tireLrKpa);
        assertEquals(Integer.valueOf(242), status.tireRrKpa);
        assertEquals(Boolean.TRUE, status.batteryHeatOn);
        assertEquals(2, status.driverSeatLevel);
    }

    @Test
    public void regionSwitchToChinaAndMultiCarFetchWork() throws Exception {
        FakeClient fakeClient = new FakeClient();
        fakeClient.vehicleListResult = new JSONArray()
                .put(new JSONObject().put("vin", "LGX_CAR_1").put("modelName", "Seal").put("plateNo", "B 100 EV"))
                .put(new JSONObject().put("vin", "LGX_CAR_2").put("modelName", "Atto 1").put("plateNo", "B 200 EV"));

        CloudBydGateway gateway = new CloudBydGateway(configStore, cfg -> fakeClient);
        configStore.setRegion("cn");
        configStore.setCountryCode("CN");

        List<VehicleSummary> cars = gateway.fetchVehicles();
        assertEquals("cn", gateway.getConfig().region);
        assertEquals("CN", gateway.getConfig().countryCode);
        assertEquals(2, cars.size());
        assertEquals("LGX_CAR_1", cars.get(0).vin);
        assertEquals("Seal", cars.get(0).modelName);
    }

    @Test
    public void autoDiscoversVehicleModelAndExtractsChargingPowerAndFullHourFallback() throws Exception {
        VehicleSummary fromSeries = VehicleSummary.fromBydVehicleJson(new JSONObject()
                .put("vin", "LGX_ATTO_3")
                .put("autoType", "1")
                .put("seriesName", "Atto 3"));
        assertNotNull(fromSeries);
        assertEquals("Atto 3", fromSeries.modelName);

        FakeClient fakeClient = new FakeClient();
        fakeClient.vehicleListResult = new JSONArray()
                .put(new JSONObject()
                        .put("vin", "LGX_ATTO_3")
                        .put("autoType", "1")
                        .put("seriesName", "Atto 3"));
        fakeClient.realtimeResult = new JSONObject()
                .put("elecPercent", 54.0)
                .put("enduranceMileageV2", 240)
                .put("chargeState", 1)
                .put("fullHour", 0)
                .put("fullMinute", 45)
                .put("gl", -7.24);

        configStore.setVin("LGX_ATTO_3");
        configStore.setVehicleName(VehicleSummary.DEFAULT_MODEL_NAME);
        CloudBydGateway gateway = new CloudBydGateway(configStore, cfg -> fakeClient);

        VehicleStatus status = gateway.fetchVehicleRealtime("LGX_ATTO_3");
        assertEquals("Atto 3", configStore.getVehicleName());
        assertEquals("Atto 3", status.modelName);
        assertEquals(Integer.valueOf(45), status.chargeRemainingMinutes);
        assertEquals(Double.valueOf(7.2), status.chargePowerKw);
    }

    @Test
    public void parkedNonZeroEnumsStayOffWhileSpeedAndStrictAcOneReflectTrueState() throws Exception {
        FakeClient fakeClient = new FakeClient();
        fakeClient.realtimeResult = new JSONObject()
                .put("elecPercent", 82)
                .put("enduranceMileageV2", 234)
                .put("chargeState", 0)
                .put("connectState", 1)
                .put("powerGear", 3)
                .put("vehicleState", 2)
                .put("speed", 0.0)
                .put("airConditionStatus", 2)
                .put("vin", "LC0SECRET123")
                .put("latitude", -5.4)
                .put("userToken", "tok_secret");
        fakeClient.hvacResult = null;

        CloudBydGateway gateway = new CloudBydGateway(configStore, cfg -> fakeClient);
        VehicleStatus parked = gateway.fetchVehicleRealtime("LGX_ATTO_1");
        assertFalse(parked.vehicleOn);
        assertEquals(VehicleStatus.CHARGING_NONE, parked.chargingState);
        assertFalse(parked.acOn);

        configStore.setVerboseLogsEnabled(true);
        fakeClient.realtimeResult.put("speed", 45.0);
        fakeClient.hvacResult = new JSONObject()
                .put("statusNow", new JSONObject()
                        .put("acSwitch", 1)
                        .put("airConditioningMode", 2));

        VehicleStatus driving = gateway.fetchVehicleRealtime("LGX_ATTO_1");
        assertTrue(driving.vehicleOn);
        assertEquals(VehicleStatus.CHARGING_NONE, driving.chargingState);
        assertTrue(driving.acOn);
        assertTrue(driving.diagSummary.contains("hv.sn.acSwitch=1"));
        assertTrue(driving.diagSummary.contains("powerGear=3"));
        assertFalse(driving.diagSummary.contains("LC0SECRET123"));
        assertFalse(driving.diagSummary.contains("tok_secret"));
        assertFalse(driving.diagSummary.contains("latitude="));
    }

    // ── Fake BydCloudClient ───────────────────────────────────────────────

    private static class FakeClient extends BydCloudClient {
        JSONObject realtimeResult;
        JSONObject hvacResult;
        JSONArray vehicleListResult;
        String firstVinResult = "VIN_DEFAULT";

        FakeClient() {
            super(new BydCloudConfig());
        }

        @Override
        public JSONObject fetchHvacStatusFast(String vin) {
            return hvacResult;
        }

        @Override
        public JSONArray fetchVehicleList() {
            return vehicleListResult;
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
