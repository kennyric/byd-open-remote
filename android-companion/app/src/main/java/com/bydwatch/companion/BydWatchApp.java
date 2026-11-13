package com.bydwatch.companion;

import android.app.Application;

import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.data.BydGateway;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.CloudBydGateway;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.data.SecureConfigStore;

import java.io.IOException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Android Application singleton providing access to repository, gateway, and secure config store.
 */
public class BydWatchApp extends Application {

    private static BydWatchApp instance;

    private static ConfigStore testConfigStore;
    private static BydGateway testGateway;
    private static BydRepository testRepository;
    private static Executor testExecutor;

    private ConfigStore configStore;
    private BydGateway gateway;
    private BydRepository repository;
    private Executor asyncExecutor;

    public static BydWatchApp getInstance() {
        return instance;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
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

    public synchronized Executor getAsyncExecutor() {
        if (testExecutor != null) {
            return testExecutor;
        }
        if (asyncExecutor == null) {
            asyncExecutor = Executors.newSingleThreadExecutor();
        }
        return asyncExecutor;
    }

    @VisibleForTesting
    public static synchronized void setTestDependencies(ConfigStore configStore, BydRepository repository) {
        setTestDependencies(configStore, repository != null ? repository.getGateway() : null, repository);
    }

    @VisibleForTesting
    public static synchronized void setTestDependencies(ConfigStore configStore, BydGateway gateway, BydRepository repository) {
        testConfigStore = configStore;
        testGateway = gateway;
        testRepository = repository;
        testExecutor = Runnable::run;
    }

    @VisibleForTesting
    public static synchronized void setTestExecutor(Executor executor) {
        testExecutor = executor;
    }

    @VisibleForTesting
    public static synchronized void resetForTesting() {
        testConfigStore = null;
        testGateway = null;
        testRepository = null;
        testExecutor = null;
    }
}
