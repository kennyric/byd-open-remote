package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.CloudCapabilities;

import java.util.Collections;
import java.util.List;

/**
 * Gateway abstraction for BYD Cloud API operations.
 */
public interface BydGateway {

    void login() throws Exception;

    void verifyControlPassword(String vin) throws Exception;

    VehicleStatus fetchVehicleRealtime(String vin) throws Exception;

    boolean lock(String vin) throws Exception;

    boolean unlock(String vin) throws Exception;

    boolean startClimate(String vin, double tempCelsius) throws Exception;

    default boolean startElectricClimate(String vin, int tempGear, int windLevel) throws Exception {
        int gear = Math.max(1, Math.min(9, tempGear));
        return startClimate(vin, gear + 14);
    }

    default boolean startElectricClimate(String vin, int tempGear, int windLevel, int windMode) throws Exception {
        return startElectricClimate(vin, tempGear, windLevel);
    }

    boolean stopClimate(String vin) throws Exception;

    boolean flashLights(String vin) throws Exception;

    boolean findCar(String vin) throws Exception;

    default String fetchFirstVin() throws Exception {
        return null;
    }

    default List<VehicleSummary> fetchVehicles() throws Exception {
        String vin = fetchFirstVin();
        if (vin != null && !vin.trim().isEmpty()) {
            return Collections.singletonList(new VehicleSummary(vin.trim(), VehicleSummary.DEFAULT_MODEL_NAME, "", ""));
        }
        return Collections.emptyList();
    }

    default boolean ventAllWindows(String vin) throws Exception {
        return false;
    }

    default boolean closeAllWindows(String vin) throws Exception {
        return false;
    }

    default boolean setBatteryHeat(String vin, boolean on) throws Exception {
        return false;
    }

    default boolean setDriverSeatHeat(String vin, int levelUi) throws Exception {
        return false;
    }

    final class VehicleLocation {
        public final double latitude;
        public final double longitude;
        public final long timestampSec;

        public VehicleLocation(double latitude, double longitude, long timestampSec) {
            this.latitude = latitude;
            this.longitude = longitude;
            this.timestampSec = timestampSec;
        }
    }

    default VehicleLocation fetchGpsInfo(String vin) throws Exception {
        return null;
    }

    default CloudCapabilities fetchCapabilities(String vin) throws Exception {
        return null;
    }
}
