package com.bydwatch.companion.lab;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.data.CloudBydGateway;
import com.overdrive.app.byd.cloud.BydCloudClient;
import com.overdrive.app.byd.cloud.BydCloudConfig;
import com.overdrive.app.byd.cloud.CloudCallTrace;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class LabRawRunnerTest {

    @After
    public void tearDown() {
        BydWatchApp.resetForTesting();
    }

    static class FakeClient extends BydCloudClient {
        String endpoint;
        JSONObject inner;

        FakeClient() { super(new BydCloudConfig()); }

        @Override
        public CloudCallTrace callControlEndpoint(String endpoint, JSONObject extraInner) {
            this.endpoint = endpoint;
            this.inner = extraInner;
            return new CloudCallTrace(endpoint, extraInner, new JSONObject(),
                    new JSONObject(), "0", "", 1L, null);
        }
    }

    @Test
    public void rawRunnerSendsEditableJsonToSmartChargeEndpoint() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("VIN_TEST_0001");
        FakeClient client = new FakeClient();
        CloudBydGateway gateway = new CloudBydGateway(client, new BydCloudConfig(), store);
        BydRepository repository = new BydRepository(gateway, store);
        BydWatchApp.setTestDependencies(store, gateway, repository);

        LabActivity activity = Robolectric.buildActivity(LabActivity.class).setup().get();
        android.widget.EditText custom = activity.findViewById(
                com.bydwatch.companion.R.id.etLabCustomEndpoint);
        custom.setText("/control/smartCharge/homePage");
        android.widget.EditText inner = activity.findViewById(
                com.bydwatch.companion.R.id.etLabRawInner);
        inner.setText("{\"vin\":\"VIN_TEST_0001\",\"probe\":\"1\"}");

        activity.findViewById(com.bydwatch.companion.R.id.btnLabSendRaw).performClick();
        drain(repository);

        assertEquals("/control/smartCharge/homePage", client.endpoint);
        assertEquals("1", client.inner.optString("probe"));
        assertTrue(((android.widget.TextView) activity.findViewById(
                com.bydwatch.companion.R.id.tvLabConsole)).getText().toString()
                .contains("smartCharge/homePage"));
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
