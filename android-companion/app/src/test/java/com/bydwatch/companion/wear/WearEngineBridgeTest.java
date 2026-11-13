package com.bydwatch.companion.wear;

import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;
import com.bydwatch.companion.protocol.WatchResponse;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class WearEngineBridgeTest {

    private BydRepositoryTest.InMemoryConfigStore configStore;
    private BydRepositoryTest.FakeBydGateway fakeGateway;
    private BydRepository repository;
    private FakePeerTransport fakeTransport;
    private WearEngineBridge bridge;

    @Before
    public void setUp() {
        configStore = new BydRepositoryTest.InMemoryConfigStore();
        configStore.setUsername("driver@example.com");
        configStore.setPassword("SecretPass123");
        configStore.setPin("123456");
        configStore.setVin("LC0TESTVIN1234567");
        configStore.setRegion("id");
        configStore.setDefaultTempC(17);

        fakeGateway = new BydRepositoryTest.FakeBydGateway();
        repository = new BydRepository(fakeGateway, configStore);
        fakeTransport = new FakePeerTransport();
        bridge = new WearEngineBridge(repository, fakeTransport);
    }

    @After
    public void tearDown() {
        if (bridge != null) {
            bridge.stop();
        }
        if (repository != null) {
            repository.shutdown();
        }
    }

    @Test
    public void dispatchesIncomingWatchJsonAndRepliesWithResponseJson() throws Exception {
        fakeTransport.permissionGranted = true;
        bridge.start();

        byte[] request = "{\"id\":\"9\",\"action\":\"lock\"}".getBytes(StandardCharsets.UTF_8);
        bridge.onIncomingBytes(request);

        byte[] reply = fakeTransport.pollSentPayload(5, TimeUnit.SECONDS);
        assertNotNull("Expected reply payload from bridge", reply);

        String replyJsonStr = new String(reply, StandardCharsets.UTF_8);
        JSONObject obj = new JSONObject(replyJsonStr);
        WatchResponse resp = WatchResponse.fromJson(obj);

        assertNotNull(resp);
        assertEquals("9", resp.id);
        assertTrue(resp.ok);
        assertEquals("lock", resp.action);
        assertEquals("Locked", resp.msg);
        assertNotNull(resp.state);
        assertTrue(resp.state.locked);
    }

    @Test
    public void repliesWithBadRequestOnNonUtf8OrCorruptPayload() throws Exception {
        fakeTransport.permissionGranted = true;
        bridge.start();

        // 1. Non-UTF-8 corrupt bytes (e.g. invalid leading byte 0xC0 or 0xFF)
        byte[] invalidUtf8 = new byte[]{(byte) 0xC0, (byte) 0xAF};
        bridge.onIncomingBytes(invalidUtf8);

        byte[] reply = fakeTransport.pollSentPayload(5, TimeUnit.SECONDS);
        assertNotNull("Expected reply for non-UTF8 payload", reply);

        String replyJsonStr = new String(reply, StandardCharsets.UTF_8);
        JSONObject obj = new JSONObject(replyJsonStr);
        WatchResponse resp = WatchResponse.fromJson(obj);

        assertNotNull(resp);
        assertFalse(resp.ok);
        assertEquals("BAD_REQUEST", resp.err);

        // 2. Corrupt non-JSON utf-8 payload
        byte[] corruptJson = "not-a-valid-json".getBytes(StandardCharsets.UTF_8);
        bridge.onIncomingBytes(corruptJson);

        byte[] reply2 = fakeTransport.pollSentPayload(5, TimeUnit.SECONDS);
        assertNotNull("Expected reply for corrupt JSON payload", reply2);

        String replyJsonStr2 = new String(reply2, StandardCharsets.UTF_8);
        JSONObject obj2 = new JSONObject(replyJsonStr2);
        WatchResponse resp2 = WatchResponse.fromJson(obj2);

        assertNotNull(resp2);
        assertFalse(resp2.ok);
        assertEquals("BAD_REQUEST", resp2.err);
    }

    @Test
    public void tracksPermissionPendingStateUntilDeviceManagerGranted() throws Exception {
        fakeTransport.permissionGranted = false;

        bridge.start();
        assertEquals(WearEngineBridge.ConnectionState.PERMISSION_NEEDED, bridge.getConnectionState());
        assertFalse("Listener should not be listening before permission", fakeTransport.listening.get());

        AtomicBoolean authCallbackSucceeded = new AtomicBoolean(false);
        bridge.requestHealthPermission(new WearEngineBridge.Callback() {
            @Override
            public void onSuccess() {
                authCallbackSucceeded.set(true);
            }

            @Override
            public void onError(String error) {
            }
        });

        // Simulate user consenting to permission in Huawei Health
        fakeTransport.simulatePermissionConsent();

        assertTrue("Auth callback should succeed upon consent", authCallbackSucceeded.get());
        assertEquals(WearEngineBridge.ConnectionState.LISTENING, bridge.getConnectionState());
        assertTrue("Transport should now be actively listening", fakeTransport.listening.get());

        bridge.stop();
        assertEquals(WearEngineBridge.ConnectionState.DISCONNECTED, bridge.getConnectionState());
        assertFalse("Transport should stop listening", fakeTransport.listening.get());
    }

    public static class FakePeerTransport implements PeerTransport {
        public boolean available = true;
        public boolean permissionGranted = false;
        public final AtomicBoolean listening = new AtomicBoolean(false);
        public MessageListener messageListener;
        public AuthCallback pendingAuthCallback;
        public final BlockingQueue<byte[]> sentPayloads = new LinkedBlockingQueue<>();

        @Override
        public boolean isAvailable() {
            return available;
        }

        @Override
        public boolean hasPermission() {
            return permissionGranted;
        }

        @Override
        public void requestPermission(AuthCallback callback) {
            this.pendingAuthCallback = callback;
            if (permissionGranted && callback != null) {
                callback.onSuccess();
            }
        }

        public void simulatePermissionConsent() {
            this.permissionGranted = true;
            if (pendingAuthCallback != null) {
                pendingAuthCallback.onSuccess();
            }
        }

        @Override
        public void startListening(MessageListener listener) {
            this.messageListener = listener;
            if (permissionGranted) {
                listening.set(true);
            }
        }

        @Override
        public void stopListening() {
            this.listening.set(false);
            this.messageListener = null;
        }

        @Override
        public void send(byte[] payload, SendCallback callback) {
            if (payload != null) {
                sentPayloads.offer(payload);
            }
            if (callback != null) {
                callback.onSuccess();
            }
        }

        public byte[] pollSentPayload(long timeout, TimeUnit unit) throws InterruptedException {
            return sentPayloads.poll(timeout, unit);
        }
    }
}
