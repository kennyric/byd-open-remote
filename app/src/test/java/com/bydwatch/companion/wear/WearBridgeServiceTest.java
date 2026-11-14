package com.bydwatch.companion.wear;

import android.content.Intent;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.BydRepositoryTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ServiceController;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
public class WearBridgeServiceTest {

    private WearEngineBridgeTest.FakePeerTransport fakeTransport;
    private WearEngineBridge bridge;
    private ServiceController<WearBridgeService> controller;

    @Before
    public void setUp() {
        BydRepositoryTest.InMemoryConfigStore configStore = new BydRepositoryTest.InMemoryConfigStore();
        BydRepositoryTest.FakeBydGateway gateway = new BydRepositoryTest.FakeBydGateway();
        BydRepository repo = new BydRepository(gateway, configStore);

        fakeTransport = new WearEngineBridgeTest.FakePeerTransport();
        fakeTransport.permissionGranted = true;
        bridge = new WearEngineBridge(repo, fakeTransport);

        BydWatchApp.setTestWearDependencies(new WearEngineConfig(), fakeTransport, bridge);
    }

    @After
    public void tearDown() {
        if (controller != null) {
            controller.destroy();
        }
        BydWatchApp.resetForTesting();
    }

    @Test
    public void startsAndStopsForegroundServiceCleanly() {
        controller = Robolectric.buildService(WearBridgeService.class);
        controller.create();

        assertTrue(WearBridgeService.isRunning());

        controller.startCommand(0, 0);
        assertTrue(fakeTransport.listening.get());

        Intent stopIntent = new Intent(WearBridgeService.ACTION_STOP);
        controller.get().onStartCommand(stopIntent, 0, 0);
        controller.destroy();

        assertFalse(WearBridgeService.isRunning());
        assertFalse(fakeTransport.listening.get());
    }
}
