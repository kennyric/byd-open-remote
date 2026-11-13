package com.bydwatch.companion.data;

import com.bydwatch.companion.model.VehicleStatus;

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

    boolean stopClimate(String vin) throws Exception;

    boolean flashLights(String vin) throws Exception;

    boolean findCar(String vin) throws Exception;
}
