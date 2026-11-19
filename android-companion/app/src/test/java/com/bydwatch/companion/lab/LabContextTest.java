package com.bydwatch.companion.lab;

import com.bydwatch.companion.model.VehicleSummary;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;

public class LabContextTest {

    private static VehicleSummary car(String vin, String energyType) throws Exception {
        return VehicleSummary.fromBydVehicleJson(new JSONObject()
                .put("vin", vin)
                .put("energyType", energyType));
    }

    @Test
    public void prefersActiveVinOverFirstVehicle() throws Exception {
        assertEquals("2", LabContext.resolveEnergyType(
                "VIN_B", Arrays.asList(car("VIN_A", "0"), car("VIN_B", "2")), "0"));
    }

    @Test
    public void fallsBackWhenActiveVinIsMissingOrUnknown() throws Exception {
        assertEquals("0", LabContext.resolveEnergyType(
                "VIN_X", Arrays.asList(car("VIN_A", "0")), "2"));
        assertEquals("2", LabContext.resolveEnergyType(
                "VIN_X", Collections.emptyList(), "2"));
        assertEquals("0", LabContext.resolveEnergyType(
                "VIN_X", Arrays.asList(car("VIN_X", "9")), "9"));
    }
}
