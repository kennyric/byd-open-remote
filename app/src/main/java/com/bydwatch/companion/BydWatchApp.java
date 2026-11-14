package com.bydwatch.companion;

import android.app.Application;

import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.CloudBydGateway;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.data.SecureConfigStore;
import com.bydwatch.companion.ui.VehicleLocationHelper;
import com.bydwatch.companion.wear.HuaweiWearP2pTransport;
import com.bydwatch.companion.wear.PeerTransport;
import com.bydwatch.companion.wear.WearEngineBridge;
import com.bydwatch.companion.wear.WearEngineConfig;

import com.google.android.material.color.DynamicColors;
import java.io.IOException;

/**
 * Android Application singleton providing access to repository, gateway, and secure config store.
 */
public class BydWatchApp extends Application {

    private static BydWatchApp instance;

    private static ConfigStore testConfigStore;
    private static BydGateway testGateway;
    private static BydRepository testRepository;
    private static WearEngineConfig testWearEngineConfig;
    private static PeerTransport testPeerTransport;
    private static WearEngineBridge testWearEngineBridge;

    private ConfigStore configStore;
    private BydGateway gateway;
    private BydRepository repository;
    private WearEngineConfig wearEngineConfig;
    private PeerTransport peerTransport;
    private WearEngineBridge wearEngineBridge;

    public static BydWatchApp getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        DynamicColors.applyToActivitiesIfAvailable(this);
        // NOTE: Hardware keystore-backed SecureConfigStore and network gateway are
        // initialized lazily upon first getter access so JVM tests without hardware
        // KeyStore do not crash Application.onCreate().
    }

    public synchronized ConfigStore getConfigStore() {
        if (testConfigStore != null) {
            return testConfigStore;
        }
        if (configStore == null) {
            configStore = new SecureConfigStore(this);
        }
        return configStore;
    }

    public synchronized BydGateway getGateway() {
        if (testGateway != null) {
            return testGateway;
        }
        if (gateway == null) {
            try {
                gateway = new CloudBydGateway(this, getConfigStore());
            } catch (IOException e) {
                throw new IllegalStateException("Failed to initialize CloudBydGateway", e);
            }
        }
        return gateway;
    }

    public synchronized BydRepository getRepository() {
        if (testRepository != null) {
            return testRepository;
        }
        if (repository == null) {
            repository = new BydRepository(getGateway(), getConfigStore());
        }
        return repository;
    }

    public synchronized WearEngineConfig getWearEngineConfig() {
        if (testWearEngineConfig != null) {
            return testWearEngineConfig;
        }
        if (wearEngineConfig == null) {
            wearEngineConfig = WearEngineConfig.forTarget(getConfigStore().getWatchTarget());
        }
        return wearEngineConfig;
    }

    public synchronized void refreshWatchTarget() {
        if (testWearEngineConfig != null) {
            return;
        }
        wearEngineConfig = WearEngineConfig.forTarget(getConfigStore().getWatchTarget());
        if (peerTransport != null) {
            try {
                peerTransport.stopListening();
            } catch (Exception ignored) {}
            peerTransport = null;
        }
        if (wearEngineBridge != null) {
            try {
                wearEngineBridge.stop();
            } catch (Exception ignored) {}
            wearEngineBridge = null;
        }
    }

    public synchronized PeerTransport getPeerTransport() {
        if (testPeerTransport != null) {
            return testPeerTransport;
        }
        if (peerTransport == null) {
            peerTransport = new HuaweiWearP2pTransport(this, getWearEngineConfig());
        }
        return peerTransport;
    }

    public synchronized WearEngineBridge getWearEngineBridge() {
        if (testWearEngineBridge != null) {
            return testWearEngineBridge;
        }
        if (wearEngineBridge == null) {
            wearEngineBridge = new WearEngineBridge(getRepository(), getPeerTransport());
        }
        return wearEngineBridge;
    }

    @VisibleForTesting
    public static synchronized void setTestDependencies(ConfigStore configStore, BydRepository repository) {
        setTestDependencies(configStore, repository != null ? repository.getGateway() : null, repository);
    }

    @VisibleForTesting
    public static synchronized void setTestDependencies(ConfigStore configStore, BydGateway gateway, BydRepository repository) {
        if (!BuildConfig.DEBUG) return;
        testConfigStore = configStore;
        testGateway = gateway;
        testRepository = repository;
    }

    @VisibleForTesting
    public static synchronized void setTestWearDependencies(WearEngineConfig config, PeerTransport transport, WearEngineBridge bridge) {
        if (!BuildConfig.DEBUG) return;
        testWearEngineConfig = config;
        testPeerTransport = transport;
        testWearEngineBridge = bridge;
    }

    @VisibleForTesting
    public static synchronized void resetForTesting() {
        if (!BuildConfig.DEBUG) return;
        testConfigStore = null;
        testGateway = null;
        testRepository = null;
        testWearEngineConfig = null;
        testPeerTransport = null;
        testWearEngineBridge = null;
        VehicleLocationHelper.clearCacheForTesting();
    }
}
