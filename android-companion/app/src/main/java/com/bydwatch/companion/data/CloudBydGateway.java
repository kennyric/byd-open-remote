package com.bydwatch.companion.data;

import android.content.Context;

import com.bydwatch.companion.model.VehicleStatus;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.util.Objects;

/**
 * Implementation of BydGateway wrapping the ported BydCloudClient.
 */
public class CloudBydGateway implements BydGateway {

    public interface ClientFactory {
        BydCloudClient createClient(BydCloudConfig config) throws Exception;
    }

    private final ConfigStore configStore;
    private final ClientFactory clientFactory;
    private final byte[] tablesBytes;

    private BydCloudClient client;
    private BydCloudConfig config;

    private String lastUsername;
    private String lastPassword;
    private String lastPin;
    private String lastRegion;

    public CloudBydGateway(Context context, ConfigStore configStore) throws IOException {
        this.configStore = configStore;
        this.clientFactory = null;
        try (InputStream in = context.getAssets().open("byd/bangcle_tables.bin")) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            this.tablesBytes = baos.toByteArray();
        }
        syncConfigToClient();
    }

    public CloudBydGateway(ConfigStore configStore, ClientFactory clientFactory) {
        this.configStore = configStore;
        this.clientFactory = clientFactory;
        this.tablesBytes = null;
        syncConfigToClient();
    }

    public CloudBydGateway(BydCloudClient client, BydCloudConfig config, ConfigStore configStore) {
        this.client = client;
        this.config = config;
        this.configStore = configStore;
        this.clientFactory = null;
        this.tablesBytes = null;
        snapshotCredentials();
    }

    public CloudBydGateway(BydCloudClient client, ConfigStore configStore) {
        this(client, null, configStore);
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

        return parseVehicleStatus(vi);
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

    public synchronized void syncConfigToClient() {
        if (configStore == null) return;

        String currentUsername = configStore.getUsername();
        String currentPassword = configStore.getPassword();
        String currentPin = configStore.getPin();
        String currentRegion = configStore.getRegion();
        String currentVin = configStore.getVin();

        boolean credsChanged = !Objects.equals(lastUsername, currentUsername)
                || !Objects.equals(lastPassword, currentPassword)
                || !Objects.equals(lastPin, currentPin)
                || !Objects.equals(lastRegion, currentRegion);

        if (credsChanged || client == null) {
            BydCloudConfig newConfig = new BydCloudConfig();
            applyConfig(newConfig, currentUsername, currentPassword, currentPin, currentRegion, currentVin);

            try {
                if (clientFactory != null) {
                    this.client = clientFactory.createClient(newConfig);
                    this.config = newConfig;
                } else if (tablesBytes != null) {
                    BydCloudClient newClient = new BydCloudClient(newConfig);
                    newClient.init(new ByteArrayInputStream(tablesBytes));
                    this.client = newClient;
                    this.config = newConfig;
                } else if (client != null && config != null) {
                    applyConfig(config, currentUsername, currentPassword, currentPin, currentRegion, currentVin);
                }
            } catch (Exception e) {
                // If recreation fails, keep what we had or propagate as unchecked
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
    }

    private static void applyConfig(
            BydCloudConfig cfg,
            String username,
            String password,
            String pin,
            String region,
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
            cfg.setCommandPwd(pin.trim());
        }
        if (region != null && !region.trim().isEmpty()) {
            cfg.setRegion(region.trim());
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

        boolean acOn = vi.optBoolean("acOn",
                vi.optInt("airConditionStatus", 0) == 1
                        || vi.optInt("airConditioner", 0) == 1
                        || vi.optInt("airConditionerStatus", 0) == 1
                        || vi.optInt("autoAirConditioning", 0) == 1
                        || vi.optInt("eleAirACSwitch", 0) == 1
                        || vi.optInt("airState", 0) == 1);

        Integer cabinTempC = null;
        if (vi.has("cabinTempC") && !vi.isNull("cabinTempC")) {
            cabinTempC = vi.optInt("cabinTempC");
        } else if (vi.has("tempInCar") && !vi.isNull("tempInCar")) {
            double tempInCar = vi.optDouble("tempInCar", -129.0);
            if (tempInCar >= -40.0 && tempInCar <= 80.0) {
                cabinTempC = (int) Math.round(tempInCar);
            }
        }

        int targetTempC = vi.optInt("targetTempC", configStore != null ? configStore.getDefaultTempC() : 17);
        long ts = vi.optLong("ts", vi.optLong("time", System.currentTimeMillis() / 1000L));

        return new VehicleStatus(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts);
    }
}
