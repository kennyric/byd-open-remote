package com.overdrive.app.byd.cloud;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class LabEndpointPolicyTest {

    @Test
    public void allowsSmartChargeAndReadOnlyProbes() {
        assertTrue(LabEndpointPolicy.isAllowed("/control/smartCharge/getAcCurrentLimit"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/smartCharge/limitCurrentControl"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/smartCharge/homePage"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/getStatusNow"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/getGpsInfo"));
        assertTrue(LabEndpointPolicy.isAllowed("/control/getBookingList"));
    }

    @Test
    public void rejectsPhysicalCommandsAndOtherPaths() {
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControl"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControlResult"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControlAsyncResult"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/remoteControlBatteryHeat"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/otaUpgrade/upgradeOta"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/bluetooth/activateKey"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/standby-vehicles/save"));
        assertFalse(LabEndpointPolicy.isAllowed("/vehicleInfo/vehicle/vehicleRealTimeRequest"));
        assertFalse(LabEndpointPolicy.isAllowed("/control/smartCharge/../remoteControl"));
        assertFalse(LabEndpointPolicy.isAllowed("http://example.com/control/smartCharge/homePage"));
        assertFalse(LabEndpointPolicy.isAllowed(null));
        assertFalse(LabEndpointPolicy.isAllowed(""));
    }
}
