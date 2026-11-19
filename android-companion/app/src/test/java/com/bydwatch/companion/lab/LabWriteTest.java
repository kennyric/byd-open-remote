package com.bydwatch.companion.lab;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.data.CloudBydGateway;
import com.bydwatch.companion.model.VehicleSummary;
import com.overdrive.app.byd.cloud.AcCurrentLimit;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabWriteTest {

    @After
    public void tearDown() {
        BydWatchApp.resetForTesting();
    }

    static class FakeClient extends BydCloudClient {
        String value = "MAX";
        String requested;

        FakeClient() { super(new BydCloudConfig()); }

        private AcCurrentLimitResult result(String endpoint, JSONObject payload) {
            CloudCallTrace trace = new CloudCallTrace(endpoint,
                    new JSONObject(), new JSONObject(), payload, "0", "", 1L, null);
            return new AcCurrentLimitResult(trace, AcCurrentLimit.parse("VIN_TEST_0001", payload));
        }

        @Override
        public AcCurrentLimitResult readAcCurrentLimit(String vin, String energyType) {
            try {
                return result("/control/smartCharge/getAcCurrentLimit",
                        new JSONObject()
                                .put("currentLimitList", new JSONArray().put("6A").put("10A").put("MAX"))
                                .put("currentEvCurrentLimit", value));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public AcCurrentLimitResult writeAcCurrentLimit(String vin, String value) {
            requested = value;
            this.value = value;
            return result("/control/smartCharge/limitCurrentControl", new JSONObject());
        }
    }

    @Test
    public void setWritesThenReReadsAndShowsApplied() throws Exception {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("VIN_TEST_0001");
        FakeClient client = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(client, new BydCloudConfig(), store);
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);
        store.setVehicles(Arrays.asList(VehicleSummary.fromBydVehicleJson(
                new JSONObject().put("vin", "VIN_TEST_0001").put("energyType", "0"))));

        LabActivity activity = Robolectric.buildActivity(LabActivity.class).setup().get();
        activity.setRetryPolicyForTesting(new LabRetryPolicy(1, 0L));

        activity.findViewById(com.bydwatch.companion.R.id.btnLabReadLimit).performClick();
        drain(repository);

        android.widget.EditText input = activity.findViewById(com.bydwatch.companion.R.id.acLabLimitValue);
        input.setText("10A");
        activity.findViewById(com.bydwatch.companion.R.id.btnLabSetLimit).performClick();

        androidx.appcompat.app.AlertDialog dialog =
                (androidx.appcompat.app.AlertDialog) ShadowAlertDialog.getLatestDialog();
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick();
        drain(repository);

        assertEquals("10A", client.requested);
        String verdict = ((android.widget.TextView) activity.findViewById(
                com.bydwatch.companion.R.id.tvLabVerdict)).getText().toString();
        assertTrue(verdict.contains("APPLIED"));
    }

    private static void drain(BydRepository repository) {
        try {
            repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();
    }
}
