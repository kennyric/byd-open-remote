package com.bydwatch.companion.data;

import android.content.Context;

import com.bydwatch.companion.model.VehicleStatus;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.crypto.BydCryptoUtils;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;

/**
 * Implementation of BydGateway wrapping the ported BydCloudClient.
 */
public class CloudBydGateway implements BydGateway {

    private final BydCloudClient client;
    private final BydCloudConfig config;
    private final ConfigStore configStore;

    public CloudBydGateway(Context context, ConfigStore configStore) throws IOException {
        this.configStore = configStore;
        this.config = new BydCloudConfig();
        if (configStore != null) {
            this.config.setRegion(configStore.getRegion());
        }
        this.client = new BydCloudClient(config);
        try (InputStream in = context.getAssets().open("byd/bangcle_tables.bin")) {
            this.client.init(in);
        }
    }

    public CloudBydGateway(BydCloudClient client, BydCloudConfig config, ConfigStore configStore) {
        this.client = client;
        this.config = config;
        this.configStore = configStore;
    }

    public CloudBydGateway(BydCloudClient client, ConfigStore configStore) {
        this(client, null, configStore);
    }

    public BydCloudClient getClient() {
        return client;
    }

    public BydCloudConfig getConfig() {
        return config;
    }

    @Override
    public void login() throws Exception {
        syncConfigToClient();
        client.login();
    }

    @Override
    public void verifyControlPassword(String vin) throws Exception {
        syncConfigToClient();
        client.verifyControlPassword(vin);
    }

    @Override
    public VehicleStatus fetchVehicleRealtime(String vin) throws Exception {
        syncConfigToClient();
        JSONObject vi = client.fetchVehicleRealtime(vin);
        if (vi == null) {
            throw new IOException("CAR_OFFLINE: Failed to fetch vehicle realtime data");
        }

        if (vi.optInt("onlineState", -1) == 2) {
            throw new IOException("CAR_OFFLINE: Vehicle T-Box is offline");
        }

        return parseVehicleStatus(vi);
    }

    @Override
    public boolean lock(String vin) throws Exception {
        syncConfigToClient();
        return client.lock(vin);
    }

    @Override
    public boolean unlock(String vin) throws Exception {
        syncConfigToClient();
        return client.unlock(vin);
    }

    @Override
    public boolean startClimate(String vin, double tempCelsius) throws Exception {
        syncConfigToClient();
        return client.startClimate(vin, tempCelsius);
    }

    @Override
    public boolean stopClimate(String vin) throws Exception {
        syncConfigToClient();
        return client.stopClimate(vin);
    }

    @Override
    public boolean flashLights(String vin) throws Exception {
        syncConfigToClient();
        return client.flashLights(vin);
    }

    @Override
    public boolean findCar(String vin) throws Exception {
        syncConfigToClient();
        return client.findCar(vin);
    }

    private void syncConfigToClient() {
        if (config == null || configStore == null) return;
        String username = configStore.getUsername();
        String password = configStore.getPassword();
        String pin = configStore.getPin();
        String region = configStore.getRegion();
        String vin = configStore.getVin();

        if (username != null && !username.trim().isEmpty()) {
            config.setUsername(username.trim());
        }
        if (password != null && !password.trim().isEmpty()) {
            config.setRawPassword(password);
            config.setLoginKey(BydCryptoUtils.pwdLoginKey(password));
            config.setSignPassword(BydCryptoUtils.md5Hex(password));
        }
        if (pin != null && !pin.trim().isEmpty()) {
            config.setCommandPwd(pin.trim());
        }
        if (region != null && !region.trim().isEmpty()) {
            config.setRegion(region.trim());
        }
        if (vin != null && !vin.trim().isEmpty()) {
            config.setVin(vin.trim());
        }
    }

    private VehicleStatus parseVehicleStatus(JSONObject vi) {
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
            if (tempInCar > -40.0 && tempInCar < 80.0) {
                cabinTempC = (int) Math.round(tempInCar);
            }
        }

        int targetTempC = vi.optInt("targetTempC", configStore != null ? configStore.getDefaultTempC() : 17);
        long ts = vi.optLong("ts", vi.optLong("time", System.currentTimeMillis() / 1000L));

        return new VehicleStatus(soc, rangeKm, locked, acOn, cabinTempC, targetTempC, ts);
    }
}
