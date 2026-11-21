package com.bydwatch.companion.data;

import android.content.ContentValues;
import android.database.Cursor;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.model.VehicleStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.Executors;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the provider itself rather than the contract: a consumer with no vehicle signed in must
 * still get exactly one, well-formed row so it can render nothing instead of crashing.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33)
public class VehicleStatusProviderTest {

    @Before
    public void setUp() {
        BydWatchApp.resetForTesting();
    }

    @After
    public void tearDown() {
        BydWatchApp.resetForTesting();
    }

    private VehicleStatusProvider provider() {
        VehicleStatusProvider provider = new VehicleStatusProvider();
        assertTrue(provider.onCreate());
        return provider;
    }

    @Test
    public void queryReturnsExactlyOneWellFormedRow() {
        Cursor cursor = provider().query(VehicleStatusContract.contentUri(), null, null, null, null);

        assertNotNull(cursor);
        assertEquals(1, cursor.getCount());
        assertTrue(cursor.moveToFirst());
        assertEquals(4, cursor.getColumnCount());
        // Nothing signed in yet: unknown values, never a null row or a throw.
        assertEquals(-1, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_SOC)));
        assertEquals(-1, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_RANGE_KM)));
        assertEquals(0L, cursor.getLong(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_TS)));
        assertEquals("km", cursor.getString(
                cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_DISTANCE_UNIT)));
        cursor.close();
    }

    @Test
    public void typeIsThePublishedItemMime() {
        assertEquals(VehicleStatusContract.MIME_TYPE,
                provider().getType(VehicleStatusContract.contentUri()));
    }

    @Test
    public void providerIsReadOnly() {
        VehicleStatusProvider provider = provider();
        assertNull(provider.insert(VehicleStatusContract.contentUri(), new ContentValues()));
        assertEquals(0, provider.update(VehicleStatusContract.contentUri(),
                new ContentValues(), null, null));
        assertEquals(0, provider.delete(VehicleStatusContract.contentUri(), null, null));
    }

    @Test
    public void aReportedDistanceShowsAnEstimateUntilTheNextReading() {
        ConsumerProgress.resetForTesting();
        RecordingRepository repository = installRepository();
        VehicleStatus reading = new VehicleStatus.Builder(50, 200, true, false, null, 22, 1_700_000_000L)
                .recentKwhPer100Km(15d)
                .chargingState(0)
                .build();
        repository.setCachedStatus(reading);

        android.os.Bundle extras = new android.os.Bundle();
        extras.putDouble(VehicleStatusContract.EXTRA_DISTANCE_KM, 20d);
        provider().call("refresh", null, extras);

        Cursor cursor = provider().query(VehicleStatusContract.contentUri(), null, null, null, null);
        assertTrue(cursor.moveToFirst());
        assertEquals(45, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_SOC)));
        assertEquals(180, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_RANGE_KM)));
        cursor.close();
    }

    @Test
    public void aNewReadingDiscardsTheOldEstimate() {
        ConsumerProgress.resetForTesting();
        RecordingRepository repository = installRepository();
        repository.setCachedStatus(new VehicleStatus.Builder(50, 200, true, false, null, 22, 1_700_000_000L)
                .recentKwhPer100Km(15d)
                .chargingState(0)
                .build());

        android.os.Bundle extras = new android.os.Bundle();
        extras.putDouble(VehicleStatusContract.EXTRA_DISTANCE_KM, 20d);
        provider().call("refresh", null, extras);

        repository.setCachedStatus(new VehicleStatus.Builder(49, 195, true, false, null, 22, 1_700_000_600L)
                .recentKwhPer100Km(15d)
                .chargingState(0)
                .build());

        Cursor cursor = provider().query(VehicleStatusContract.contentUri(), null, null, null, null);
        assertTrue(cursor.moveToFirst());
        assertEquals(49, cursor.getInt(cursor.getColumnIndexOrThrow(VehicleStatusContract.COLUMN_SOC)));
        cursor.close();
    }

    @After
    public void clearConsumerProgress() {
        ConsumerProgress.resetForTesting();
        BydWatchApp.resetForTesting();
    }

    @Test
    public void aConsumerReadAsksTheRepositoryForFresherData() {
        RecordingRepository repository = installRepository();
        repository.setCachedStatus(statusSecondsAgo(90));

        provider().query(VehicleStatusContract.contentUri(), null, null, null, null);

        assertEquals(1, repository.refreshRequests);
    }

    @Test
    public void aReadOfRecentDataStillAsksSoTheConsumerOwnsTheCadence() {
        // The Maps patch polls every N minutes; it must not depend on how old the cache happens
        // to be, or a short interval would keep serving data the app never refetched.
        RecordingRepository repository = installRepository();
        repository.setCachedStatus(statusSecondsAgo(5));

        provider().query(VehicleStatusContract.contentUri(), null, null, null, null);

        assertEquals(1, repository.refreshRequests);
    }

    private static RecordingRepository installRepository() {
        BydRepositoryTest.InMemoryConfigStore store = new BydRepositoryTest.InMemoryConfigStore();
        store.setVin("LC0TESTVIN1234567");
        RecordingRepository repository = new RecordingRepository(store);
        BydWatchApp.setTestDependencies(store, repository);
        return repository;
    }

    private static VehicleStatus statusSecondsAgo(int seconds) {
        return new VehicleStatus(62, 310, true, false, null, 22,
                System.currentTimeMillis() - seconds * 1000L);
    }

    /** Records refresh requests without reaching the cloud. */
    static final class RecordingRepository extends BydRepository {
        int refreshRequests;

        RecordingRepository(ConfigStore store) {
            super(null, store, Executors.newSingleThreadExecutor());
        }

        @Override
        public void refreshNow(long minGapMs) {
            refreshRequests++;
        }
    }
}

