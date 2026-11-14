package com.bydwatch.companion.data;

import android.content.Context;

import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.CloudCapabilities;
import com.overdrive.app.byd.cloud.crypto.BangcleTablesFile;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;
import com.overdrive.app.byd.cloud.crypto.EnvelopeCodecFactory;
import com.overdrive.app.byd.cloud.crypto.WbskTablesFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of BydGateway wrapping the ported BydCloudClient.
 * Supports all 17 BYD regions including China mainland (WBSK) and overseas (Bangcle).
 */
public class CloudBydGateway implements BydGateway {

    public interface ClientFactory {
        BydCloudClient createClient(BydCloudConfig config) throws Exception;
    }

    private final Context appContext;
    private final ConfigStore configStore;
    private final ClientFactory clientFactory;
    private final byte[] bangcleTablesBytes;
    private final byte[] wbskTablesBytes;

    private BydCloudClient client;
    private BydCloudConfig config;

    private String lastUsername;
    private String lastPassword;
    private String lastPin;
    private String lastRegion;
    private String lastCountryCode;

    public CloudBydGateway(Context context, ConfigStore configStore) throws IOException {
        this.appContext = context != null ? context.getApplicationContext() : null;
        this.configStore = configStore;
        this.clientFactory = null;
        this.bangcleTablesBytes = readAssetOptional(context, BangcleTablesFile.ASSET_PATH);
        this.wbskTablesBytes = readAssetOptional(context, WbskTablesFile.ASSET_PATH);
        if (this.bangcleTablesBytes == null && this.wbskTablesBytes == null) {
            throw new IOException("Missing BYD crypto tables in assets/byd/");
        }
        syncConfigToClient();
    }

    public CloudBydGateway(ConfigStore configStore, ClientFactory clientFactory) {
        this.appContext = null;
        this.configStore = configStore;
        this.clientFactory = clientFactory;
        this.bangcleTablesBytes = null;
        this.wbskTablesBytes = null;
        syncConfigToClient();
    }

    public CloudBydGateway(BydCloudClient client, BydCloudConfig config, ConfigStore configStore) {
        this.appContext = null;
        this.client = client;
        this.config = config;
        this.configStore = configStore;
        this.clientFactory = null;
        this.bangcleTablesBytes = null;
        this.wbskTablesBytes = null;
        snapshotCredentials();
    }

    public CloudBydGateway(BydCloudClient client, ConfigStore configStore) {
        this(client, null, configStore);
    }

    private static byte[] readAssetOptional(Context ctx, String path) {
        if (ctx != null && ctx.getAssets() != null) {
            try (InputStream in = ctx.getAssets().open(path)) {
                return readAllBytes(in);
            } catch (IOException ignored) {}
        }
        try (InputStream res = CloudBydGateway.class.getClassLoader().getResourceAsStream(path)) {
            if (res != null) {
                return readAllBytes(res);
            }
        } catch (IOException ignored) {}
        return null;
    }

    private static byte[] readAllBytes(InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) {
            baos.write(buf, 0, n);
        }
        return baos.toByteArray();
    }

    private InputStream openTablesForConfig(BydCloudConfig cfg) throws IOException {
        boolean isCn = cfg != null && cfg.isChinaRegion();
        byte[] cached = isCn ? wbskTablesBytes : bangcleTablesBytes;
        if (cached != null) {
            return new ByteArrayInputStream(cached);
        }
        InputStream stream = EnvelopeCodecFactory.openTablesStream(isCn, appContext);
        if (stream != null) {
            return stream;
        }
        throw new IOException("Crypto tables unavailable for region: " + (isCn ? "cn" : "overseas"));
    }

    public synchronized BydCloudClient getClient() {
        return client;
    }

    public synchronized BydCloudConfig getConfig() {
        return config;
    }

    @Override
    public synchronized void login() throws Exception {
        syncConfigToClient();
        if (client != null) {
            client.login();
        }
    }

    @Override
    public synchronized void verifyControlPassword(String vin) throws Exception {
        syncConfigToClient();
        if (client != null) {
            client.verifyControlPassword(vin);
        }
    }

    @Override
    public synchronized VehicleStatus fetchVehicleRealtime(String vin) throws Exception {
        syncConfigToClient();
        if (client == null) {
            throw new IOException("BYD client not initialized");
        }

        JSONObject vi = client.fetchVehicleRealtime(vin);
        if (vi == null) {
            throw new IOException("CAR_OFFLINE: Failed to fetch vehicle realtime data");
        }

        if (vi.optInt("onlineState", -1) == 2) {
            throw new IOException("CAR_OFFLINE: Vehicle T-Box is offline");
        }

        if (!hasTelemetry(vi)) {
            throw new SocketTimeoutException("Vehicle realtime telemetry unavailable");
        }

        CloudCapabilities caps = null;
        try {
            caps = client.getCachedCloudCapabilities(vin);
            if (caps == null) {
                caps = client.fetchCloudCapabilities(vin);
            }
        } catch (Exception ignored) {
            // Capability query is best-effort so telemetry never fails if getLatestConfig flakes
        }

        VehicleLocation location = null;
        try {
            location = parseGpsPayload(client.fetchGpsInfoFast(vin));
        } catch (Exception ignored) {
            // Best-effort GPS lookup: transient 500s / timeouts must never fail status updates
        }

        JSONObject hvac = null;
        if (!extractAcOn(vi, null)) {
            try {
                hvac = client.fetchHvacStatusFast(vin);
            } catch (Exception ignored) {
                // Best-effort HVAC lookup: never fail status refresh if getStatusNow is unavailable
            }
        }

        ensureVehicleMetadataIfNeeded(vin);

        return parseVehicleStatus(vi, vin, caps, location, hvac);
    }

    private void ensureVehicleMetadataIfNeeded(String activeVin) {
        if (configStore == null || client == null) return;
        boolean needsName = VehicleSummary.isGenericModelName(configStore.getVehicleName());
        List<VehicleSummary> existing = configStore.getVehicles();
        boolean needsList = existing == null || existing.isEmpty();
        if (!needsName && !needsList) return;

        try {
            JSONArray list = client.fetchVehicleList();
            if (list == null || list.length() == 0) return;
            List<VehicleSummary> discovered = new ArrayList<>();
            for (int i = 0; i < list.length(); i++) {
                VehicleSummary summary = VehicleSummary.fromBydVehicleJson(list.optJSONObject(i));
                if (summary != null && !summary.vin.isEmpty()) {
                    discovered.add(summary);
                }
            }
            if (discovered.isEmpty()) return;

            if (needsList) {
                configStore.setVehicles(Collections.unmodifiableList(discovered));
            }
            if (needsName) {
                VehicleSummary match = null;
                if (activeVin != null && !activeVin.trim().isEmpty()) {
                    for (VehicleSummary v : discovered) {
                        if (activeVin.trim().equals(v.vin)) {
                            match = v;
                            break;
                        }
                    }
                }
                if (match == null && discovered.size() == 1) {
                    match = discovered.get(0);
                }
                if (match != null && !VehicleSummary.isGenericModelName(match.modelName)) {
                    configStore.setVehicleName(match.modelName);
                }
            }
        } catch (Exception ignored) {
            // Best-effort metadata lookup must never break status refresh
        }
    }

    @Override
    public synchronized VehicleLocation fetchGpsInfo(String vin) throws Exception {
        syncConfigToClient();
        if (client == null) {
            throw new IOException("BYD client not initialized");
        }
        return parseGpsPayload(client.fetchGpsInfo(vin));
    }

    static VehicleLocation parseGpsPayload(JSONObject gpsJson) {
        double[] coords = BydCloudClient.extractGpsCoordinates(gpsJson);
        if (coords == null) {
            return null;
        }
        return new VehicleLocation(coords[0], coords[1], (long) coords[2]);
    }

    @Override
    public synchronized boolean lock(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.lock(vin);
    }

    @Override
    public synchronized boolean unlock(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.unlock(vin);
    }

    @Override
    public synchronized boolean startClimate(String vin, double tempCelsius) throws Exception {
        syncConfigToClient();
        return client != null && client.startClimate(vin, tempCelsius);
    }

    @Override
    public synchronized boolean stopClimate(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.stopClimate(vin);
    }

    @Override
    public synchronized boolean flashLights(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.flashLights(vin);
    }

    @Override
    public synchronized boolean findCar(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.findCar(vin);
    }

    @Override
    public synchronized boolean ventAllWindows(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.ventAllWindows(vin);
    }

    @Override
    public synchronized boolean closeAllWindows(String vin) throws Exception {
        syncConfigToClient();
        return client != null && client.closeAllWindows(vin);
    }

    @Override
    public synchronized boolean setBatteryHeat(String vin, boolean on) throws Exception {
        syncConfigToClient();
        return client != null && client.setBatteryHeat(vin, on);
    }

    @Override
    public synchronized boolean setDriverSeatHeat(String vin, int levelUi) throws Exception {
        syncConfigToClient();
        if (client == null) return false;
        return client.setSeatClimate(vin, "1", levelUi, 0, 0, 0);
    }

    @Override
    public synchronized CloudCapabilities fetchCapabilities(String vin) throws Exception {
        syncConfigToClient();
        if (client == null || vin == null || vin.trim().isEmpty()) {
            return null;
        }
        return client.fetchCloudCapabilities(vin);
    }

    @Override
    public synchronized List<VehicleSummary> fetchVehicles() throws Exception {
        syncConfigToClient();
        if (client == null) {
            throw new IOException("BYD client not initialized");
        }
        JSONArray list = client.fetchVehicleList();
        List<VehicleSummary> out = new ArrayList<>();
        if (list != null) {
            for (int i = 0; i < list.length(); i++) {
                VehicleSummary summary = VehicleSummary.fromBydVehicleJson(list.optJSONObject(i));
                if (summary != null && !summary.vin.isEmpty()) {
                    out.add(summary);
                }
            }
        }
        if (out.isEmpty()) {
            String fallbackVin = client.fetchFirstVin();
            if (fallbackVin != null && !fallbackVin.trim().isEmpty()) {
                out.add(new VehicleSummary(fallbackVin.trim(), VehicleSummary.DEFAULT_MODEL_NAME, "", ""));
            }
        }
        return Collections.unmodifiableList(out);
    }

    public synchronized void syncConfigToClient() {
        if (configStore == null) return;

        String currentUsername = configStore.getUsername();
        String currentPassword = configStore.getPassword();
        String currentPin = configStore.getPin();
        String currentRegion = configStore.getRegion();
        String currentCountryCode = configStore.getCountryCode();
        String currentVin = configStore.getVin();

        boolean credsChanged = !Objects.equals(lastUsername, currentUsername)
                || !Objects.equals(lastPassword, currentPassword)
                || !Objects.equals(lastPin, currentPin)
                || !Objects.equals(lastRegion, currentRegion)
                || !Objects.equals(lastCountryCode, currentCountryCode);

        if (credsChanged || client == null) {
            BydCloudConfig newConfig = new BydCloudConfig();
            applyConfig(newConfig, currentUsername, currentPassword, currentPin,
                    currentRegion, currentCountryCode, currentVin);

            try {
                if (clientFactory != null) {
                    this.client = clientFactory.createClient(newConfig);
                    this.config = newConfig;
                } else if (bangcleTablesBytes != null || wbskTablesBytes != null || appContext != null) {
                    BydCloudClient newClient = new BydCloudClient(newConfig);
                    try (InputStream tableStream = openTablesForConfig(newConfig)) {
                        newClient.init(tableStream);
                    }
                    this.client = newClient;
                    this.config = newConfig;
                } else if (client != null && config != null) {
                    applyConfig(config, currentUsername, currentPassword, currentPin,
                            currentRegion, currentCountryCode, currentVin);
                }
            } catch (Exception e) {
                throw new RuntimeException("Failed to instantiate fresh BydCloudClient on config update", e);
            }

            snapshotCredentials();
        } else if (config != null) {
            if (currentVin != null && !currentVin.equals(config.vin)) {
                config.setVin(currentVin);
            }
        }
    }

    private void snapshotCredentials() {
        if (configStore == null) return;
        this.lastUsername = configStore.getUsername();
        this.lastPassword = configStore.getPassword();
        this.lastPin = configStore.getPin();
        this.lastRegion = configStore.getRegion();
        this.lastCountryCode = configStore.getCountryCode();
    }

    private static void applyConfig(
            BydCloudConfig cfg,
            String username,
            String password,
            String pin,
            String region,
            String countryCode,
            String vin) {
        if (username != null && !username.trim().isEmpty()) {
            cfg.setUsername(username.trim());
        }
        if (password != null && !password.trim().isEmpty()) {
            cfg.setRawPassword(password);
            cfg.setLoginKey(BydCryptoUtils.pwdLoginKey(password));
            cfg.setSignPassword(BydCryptoUtils.md5Hex(password));
        }
        if (pin != null && !pin.trim().isEmpty()) {
            cfg.setCommandPwd(BydCryptoUtils.md5Hex(pin.trim()));
        }
        if (region != null && !region.trim().isEmpty()) {
            cfg.setRegion(region.trim(), true);
        }
        if (countryCode != null && !countryCode.trim().isEmpty()) {
            cfg.setCountryCode(countryCode.trim());
        }
        if (vin != null && !vin.trim().isEmpty()) {
            cfg.setVin(vin.trim());
        }
    }

    public static boolean hasTelemetry(JSONObject vi) {
        if (vi == null) return false;
        return vi.has("elecPercent")
                || vi.has("soc")
                || vi.has("enduranceMileage")
                || vi.has("enduranceMileageV2")
                || vi.has("leftFrontDoorLock")
                || vi.has("tempInCar")
                || vi.has("time");
    }

    public VehicleStatus parseVehicleStatus(JSONObject vi) {
        String activeVin = configStore != null ? configStore.getVin() : "";
        return parseVehicleStatus(vi, activeVin, null);
    }

    public VehicleStatus parseVehicleStatus(JSONObject vi, String activeVin, CloudCapabilities caps) {
        return parseVehicleStatus(vi, activeVin, caps, null);
    }

    public VehicleStatus parseVehicleStatus(JSONObject vi, String activeVin, CloudCapabilities caps,
                                            VehicleLocation location) {
        return parseVehicleStatus(vi, activeVin, caps, location, null);
    }

    public VehicleStatus parseVehicleStatus(JSONObject vi, String activeVin, CloudCapabilities caps,
                                            VehicleLocation location, JSONObject hvac) {
        int soc = vi.optInt("soc", (int) Math.round(vi.optDouble("elecPercent", 0.0)));
        int rangeV2 = vi.optInt("enduranceMileageV2", -1);
        int rangeKm = vi.optInt("rangeKm", rangeV2 != -1 ? rangeV2 : vi.optInt("enduranceMileage", 0));

        boolean locked;
        if (vi.has("locked")) {
            locked = vi.optBoolean("locked");
        } else {
            int lf = vi.optInt("leftFrontDoorLock", -1);
            int rf = vi.optInt("rightFrontDoorLock", -1);
            int lr = vi.optInt("leftRearDoorLock", -1);
            int rr = vi.optInt("rightRearDoorLock", -1);
            locked = (lf == 2 && rf == 2 && lr == 2 && rr == 2);
        }

        boolean acOn = extractAcOn(vi, hvac);

        Integer cabinTempC = null;
        if (vi.has("cabinTempC") && !vi.isNull("cabinTempC")) {
            cabinTempC = vi.optInt("cabinTempC");
        } else if (vi.has("tempInCar") && !vi.isNull("tempInCar")) {
            double tempInCar = vi.optDouble("tempInCar", -129.0);
            if (tempInCar >= -40.0 && tempInCar <= 80.0) {
                cabinTempC = (int) Math.round(tempInCar);
            }
        }

        Integer outsideTempC = null;
        if (vi.has("tempOutCar") && !vi.isNull("tempOutCar")) {
            double tempOut = vi.optDouble("tempOutCar", -129.0);
            if (tempOut >= -50.0 && tempOut <= 75.0) {
                outsideTempC = (int) Math.round(tempOut);
            }
        }

        int targetTempC = vi.optInt("targetTempC", configStore != null ? configStore.getDefaultTempC() : 17);
        long ts = vi.optLong("ts", vi.optLong("time", System.currentTimeMillis() / 1000L));

        int powerGear = vi.optInt("powerGear", -1);
        int vehicleState = vi.optInt("vehicleState", -1);
        double speed = vi.optDouble("speed", Double.NaN);
        boolean vehicleOn = vi.optBoolean("carOn", false)
                || powerGear == 3
                || vehicleState == 2
                || (!Double.isNaN(speed) && !Double.isInfinite(speed) && speed > 0.5);

        int chargingState = vi.has("chargeState")
                ? vi.optInt("chargeState", VehicleStatus.CHARGING_UNKNOWN)
                : vi.optInt("chargingState", VehicleStatus.CHARGING_UNKNOWN);
        if (vehicleOn && chargingState != VehicleStatus.CHARGING_ACTIVE) {
            chargingState = VehicleStatus.CHARGING_NONE;
        }
        Integer chgMin = null;
        if (chargingState == VehicleStatus.CHARGING_ACTIVE) {
            int remH = vi.has("remainingHours") && !vi.isNull("remainingHours")
                    ? vi.optInt("remainingHours", -1)
                    : vi.optInt("fullHour", -1);
            int remM = vi.has("remainingMinutes") && !vi.isNull("remainingMinutes")
                    ? vi.optInt("remainingMinutes", -1)
                    : vi.optInt("fullMinute", -1);
            if ((remH >= 0 && remH < 200) || (remM >= 0 && remM < 60)) {
                int safeH = (remH >= 0 && remH < 200) ? remH : 0;
                int safeM = (remM >= 0 && remM < 60) ? remM : 0;
                chgMin = Math.max(0, safeH * 60 + safeM);
            }
        }
        Double chgPowerKw = extractChargePowerKw(vi, chargingState);

        int doorsOpen = countEquals(1,
                vi.optInt("leftFrontDoor", -1),
                vi.optInt("rightFrontDoor", -1),
                vi.optInt("leftRearDoor", -1),
                vi.optInt("rightRearDoor", -1));
        boolean trunkOpen = vi.optInt("trunkLid", -1) == 1;
        int winsOpen = countEquals(2,
                vi.optInt("leftFrontWindow", -1),
                vi.optInt("rightFrontWindow", -1),
                vi.optInt("leftRearWindow", -1),
                vi.optInt("rightRearWindow", -1),
                vi.optInt("skylight", -1));

        int mileV2 = vi.optInt("totalMileageV2", 0);
        int odo = mileV2 > 0 ? mileV2 : vi.optInt("totalMileage", 0);
        Integer totalMileageKm = odo > 0 ? odo : null;

        double oilPct = vi.optDouble("oilPercent", -1);
        Integer fuelPct = oilPct >= 0 && oilPct <= 100 ? (int) Math.round(oilPct) : null;
        int oilEnd = vi.optInt("oilEndurance", -1);
        Integer fuelKm = oilEnd >= 0 ? oilEnd : null;

        Integer lfKpa = normalizeTpms(vi, "leftFrontTirepressure", "leftFrontTirePressure");
        Integer rfKpa = normalizeTpms(vi, "rightFrontTirepressure", "rightFrontTirePressure");
        Integer lrKpa = normalizeTpms(vi, "leftRearTirepressure", "leftRearTirePressure");
        Integer rrKpa = normalizeTpms(vi, "rightRearTirepressure", "rightRearTirePressure");

        int batHeatRaw = vi.optInt("batteryHeatState", -1);
        Boolean batHeatOn = batHeatRaw >= 0 ? (batHeatRaw > 0) : null;

        int seatHeatRaw = vi.optInt("mainSeatHeatState", -1);
        int driverSeatLevel = (seatHeatRaw >= 1 && seatHeatRaw <= 3) ? (seatHeatRaw - 1) : 0;

        boolean canWin = caps == null
                || caps.supports(CloudCapabilities.Feature.WINDOWS_CLOSE)
                || caps.supports(CloudCapabilities.Feature.WINDOWS_OPEN_VENT);
        boolean canBat = caps != null && caps.supports(CloudCapabilities.Feature.BATTERY_HEAT);
        boolean canSeat = caps != null && caps.supports(CloudCapabilities.Feature.SEAT_DRIVER);

        String resolvedModel = configStore != null ? configStore.getVehicleName() : VehicleSummary.DEFAULT_MODEL_NAME;
        List<VehicleSummary> cars = configStore != null ? configStore.getVehicles() : Collections.emptyList();
        if (activeVin != null && !activeVin.isEmpty() && cars != null) {
            for (VehicleSummary v : cars) {
                if (activeVin.equals(v.vin) && !VehicleSummary.isGenericModelName(v.modelName)) {
                    resolvedModel = v.modelName;
                    break;
                }
            }
        }
        if (VehicleSummary.isGenericModelName(resolvedModel)) {
            String inlineModel = VehicleSummary.extractModelFromJson(vi);
            if (!VehicleSummary.isGenericModelName(inlineModel)) {
                resolvedModel = VehicleSummary.normalizeModelName(inlineModel);
            } else {
                resolvedModel = VehicleSummary.DEFAULT_MODEL_NAME;
            }
        }

        String uDist = configStore != null ? configStore.getDistanceUnit() : UnitPreferences.DIST_KM;
        String uTemp = configStore != null ? configStore.getTempUnit() : UnitPreferences.TEMP_C;
        String uPres = configStore != null ? configStore.getPressureUnit() : UnitPreferences.PRES_KPA;

        Double lat = null;
        Double lng = null;
        Long gpsTs = null;
        if (location != null && VehicleStatus.isValidCoordinates(location.latitude, location.longitude)) {
            lat = location.latitude;
            lng = location.longitude;
            gpsTs = location.timestampSec > 0 ? location.timestampSec : null;
        } else {
            double[] inlineCoords = BydCloudClient.extractGpsCoordinates(vi);
            if (inlineCoords != null) {
                lat = inlineCoords[0];
                lng = inlineCoords[1];
                gpsTs = (long) inlineCoords[2];
            }
        }

        return new VehicleStatus.Builder(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts)
                .vin(activeVin)
                .modelName(resolvedModel)
                .chargingState(chargingState)
                .vehicleOn(vehicleOn)
                .chargeRemainingMinutes(chgMin)
                .chargePowerKw(chgPowerKw)
                .diagSummary(buildTelemetryDiag(vi, hvac))
                .doorsOpenCount(doorsOpen)
                .windowsOpenCount(winsOpen)
                .trunkOpen(trunkOpen)
                .totalMileageKm(totalMileageKm)
                .outsideTempC(outsideTempC)
                .fuelPercent(fuelPct)
                .fuelRangeKm(fuelKm)
                .tirePressuresKpa(lfKpa, rfKpa, lrKpa, rrKpa)
                .batteryHeatOn(batHeatOn)
                .driverSeatLevel(driverSeatLevel)
                .capabilities(canWin, canBat, canSeat)
                .units(uDist, uTemp, uPres)
                .location(lat, lng, gpsTs)
                .vehicles(cars)
                .build();
    }

    private static boolean extractAcOn(JSONObject vi, JSONObject hvac) {
        if (vi != null && vi.has("acOn") && !vi.isNull("acOn")) {
            return vi.optBoolean("acOn", false);
        }
        String[] intPositiveKeys = {
                "airConditionStatus",
                "airConditioner",
                "airConditionerStatus",
                "autoAirConditioning",
                "eleAirACSwitch",
                "airState",
                "acSwitch",
                "airConditioningMode",
                "airRunState",
                "airCondition",
                "acState",
                "airConditioning"
        };
        JSONObject[] sources = new JSONObject[]{
                vi,
                vi != null ? vi.optJSONObject("statusNow") : null,
                hvac,
                hvac != null ? hvac.optJSONObject("statusNow") : null
        };
        for (JSONObject src : sources) {
            if (src == null) continue;
            for (String key : intPositiveKeys) {
                if (src.has(key) && !src.isNull(key) && src.optInt(key, 0) > 0) {
                    return true;
                }
            }
            if (src.has("isAirConditioning") && !src.isNull("isAirConditioning")) {
                if (src.optBoolean("isAirConditioning", false) || src.optInt("isAirConditioning", 0) > 0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static String buildTelemetryDiag(JSONObject vi, JSONObject hvac) {
        if (vi == null) return "";
        StringBuilder sb = new StringBuilder();
        appendIntDiag(sb, vi, "chargeState", "cs");
        appendIntDiag(sb, vi, "connectState", "conn");
        appendIntDiag(sb, vi, "powerGear", "gear");
        appendIntDiag(sb, vi, "vehicleState", "vs");
        if (vi.has("speed") && !vi.isNull("speed")) {
            sb.append(sb.length() > 0 ? " " : "").append("spd=").append(vi.optString("speed", ""));
        }
        String[] acKeys = {
                "airConditionStatus", "acSwitch", "airConditioningMode", "airRunState",
                "airConditioner", "airConditionerStatus", "autoAirConditioning",
                "eleAirACSwitch", "airState", "airCondition", "acState", "isAirConditioning"
        };
        JSONObject[] sources = new JSONObject[]{
                vi,
                vi.optJSONObject("statusNow"),
                hvac,
                hvac != null ? hvac.optJSONObject("statusNow") : null
        };
        String[] prefixes = {"", "sn.", "hv.", "hv.sn."};
        for (int i = 0; i < sources.length; i++) {
            JSONObject src = sources[i];
            if (src == null) continue;
            for (String k : acKeys) {
                if (src.has(k) && !src.isNull(k)) {
                    sb.append(sb.length() > 0 ? " " : "")
                            .append(prefixes[i]).append(k).append("=").append(src.optString(k, ""));
                }
            }
        }
        return sb.toString();
    }

    private static void appendIntDiag(StringBuilder sb, JSONObject src, String key, String label) {
        if (src.has(key) && !src.isNull(key)) {
            sb.append(sb.length() > 0 ? " " : "").append(label).append("=").append(src.optInt(key, -1));
        }
    }

    private static int countEquals(int target, int... values) {
        int count = 0;
        for (int v : values) {
            if (v == target) count++;
        }
        return count;
    }

    private static Integer normalizeTpms(JSONObject vi, String k1, String k2) {
        if (vi.has(k1) && !vi.isNull(k1)) {
            return UnitPreferences.normalizeRawTpmsToKpa(vi.optDouble(k1, -1.0));
        }
        if (vi.has(k2) && !vi.isNull(k2)) {
            return UnitPreferences.normalizeRawTpmsToKpa(vi.optDouble(k2, -1.0));
        }
        return null;
    }

    /**
     * Extract instantaneous charging power in kW when the car is actively charging.
     * Supports BYD's signed "gl" battery power telemetry (e.g. -7.2 kW while charging),
     * explicit chargingPower/chargePower keys, and non-zero totalPower fallback.
     */
    private static Double extractChargePowerKw(JSONObject vi, int chargingState) {
        if (vi == null) return null;
        String[] serializedKeys = {"chargePowerKw", "chgKw"};
        for (String key : serializedKeys) {
            if (vi.has(key) && !vi.isNull(key)) {
                double val = vi.optDouble(key, Double.NaN);
                if (!Double.isNaN(val) && !Double.isInfinite(val) && val > 0.05 && val < 500.0) {
                    return Math.round(val * 10.0) / 10.0;
                }
            }
        }
        if (chargingState != VehicleStatus.CHARGING_ACTIVE) {
            return null;
        }
        String[] powerKeys = {"chargingPower", "chargePower", "realTimePower", "batteryPower", "gl", "totalPower"};
        for (String key : powerKeys) {
            if (vi.has(key) && !vi.isNull(key)) {
                double raw = vi.optDouble(key, Double.NaN);
                if (Double.isNaN(raw) || Double.isInfinite(raw)) continue;
                double mag = "totalPower".equals(key) ? raw : Math.abs(raw);
                if (mag > 0.05 && mag < 500.0) {
                    return Math.round(mag * 10.0) / 10.0;
                }
            }
        }
        return null;
    }

    @Override
    public synchronized String fetchFirstVin() throws Exception {
        syncConfigToClient();
        if (client == null) {
            throw new IOException("BYD client not initialized");
        }
        return client.fetchFirstVin();
    }
}
