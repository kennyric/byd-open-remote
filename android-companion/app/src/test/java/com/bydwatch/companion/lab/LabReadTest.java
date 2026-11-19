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
import org.robolectric.shadows.ShadowLooper;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabReadTest {

    @After
    public void tearDown() {
        BydWatchApp.resetForTesting();
    }

    static class FakeClient extends BydCloudClient {
        FakeClient() { super(new BydCloudConfig()); }

        @Override
        public AcCurrentLimitResult readAcCurrentLimit(String vin, String energyType) {
            try {
                JSONObject payload = new JSONObject()
                        .put("currentLimitList", new JSONArray().put("6A").put("10A").put("MAX"))
                        .put("currentEvCurrentLimit", "MAX");
                CloudCallTrace trace = new CloudCallTrace(
                        "/control/smartCharge/getAcCurrentLimit",
                        new JSONObject().put("vin", vin).put("energyType", energyType),
                        new JSONObject(), payload, "0", "", 3L, null);
                return new AcCurrentLimitResult(trace, AcCurrentLimit.parse(vin, payload));
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    @Test
    public void readShowsActiveValueOptionsAndConsoleEntry() throws Exception {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("VIN_TEST_0001");
        FakeClient client = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(client, new BydCloudConfig(), store);
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);
        store.setVehicles(Arrays.asList(VehicleSummary.fromBydVehicleJson(
                new JSONObject().put("vin", "VIN_TEST_0001").put("energyType", "0"))));

        LabActivity activity = Robolectric.buildActivity(LabActivity.class).setup().get();
        activity.findViewById(com.bydwatch.companion.R.id.btnLabReadLimit).performClick();
        drain(repository);

        assertTrue(text(activity, com.bydwatch.companion.R.id.tvLabActiveLimit).contains("MAX"));
        assertTrue(text(activity, com.bydwatch.companion.R.id.tvLabOptions).contains("10A"));
        assertTrue(text(activity, com.bydwatch.companion.R.id.tvLabConsole)
                .contains("getAcCurrentLimit"));
    }

    private static void drain(BydRepository repository) {
        try {
            repository.getExecutor().submit(() -> {}).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        ShadowLooper.idleMainLooper();
    }

    private static String text(LabActivity activity, int id) {
        return ((android.widget.TextView) activity.findViewById(id)).getText().toString();
    }
}
