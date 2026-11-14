package com.bydwatch.companion.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.location.Address;
import android.location.Geocoder;
import android.net.Uri;
import android.os.Build;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.R;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * Shared formatting, reverse-geocoding, and navigation-app chooser prompts for car GPS coordinates.
 */
public final class VehicleLocationHelper {

    public interface StreetNameCallback {
        void onStreetResolved(@Nullable String streetName);
    }

    @VisibleForTesting
    public interface StreetLookup {
        @Nullable
        String lookup(Context context, double latitude, double longitude);
    }

    private static final int MAX_CACHE_ENTRIES = 64;
    private static final int NOMINATIM_TIMEOUT_MS = 2500;
    private static final Pattern PLUS_CODE_PATTERN =
            Pattern.compile("^[A-Z0-9]{4,8}\\+[A-Z0-9]{2,4}(\\b.*)?$", Pattern.CASE_INSENSITIVE);
    private static final Pattern COORD_PATTERN =
            Pattern.compile("^-?\\d+(?:\\.\\d+)?\\s*,\\s*-?\\d+(?:\\.\\d+)?$");

    private static final Map<String, String> STREET_CACHE = Collections.synchronizedMap(
            new LinkedHashMap<String, String>(32, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                    return size() > MAX_CACHE_ENTRIES;
                }
            });
    private static final Set<String> IN_FLIGHT_KEYS = ConcurrentHashMap.newKeySet();
    private static final ExecutorService GEOCODE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "byd-reverse-geocode");
        t.setDaemon(true);
        return t;
    });
    private static volatile StreetLookup testStreetLookup;

    private VehicleLocationHelper() {}

    public static String formatCoordinates(double latitude, double longitude) {
        return String.format(Locale.US, "%.5f, %.5f", latitude, longitude);
    }

    private static String cacheKey(double latitude, double longitude) {
        return String.format(Locale.US, "%.4f,%.4f", latitude, longitude);
    }

    @Nullable
    public static String getCachedStreetName(double latitude, double longitude) {
        return STREET_CACHE.get(cacheKey(latitude, longitude));
    }

    public static void resolveStreetName(
            @Nullable Context context,
            double latitude,
            double longitude,
            @Nullable StreetNameCallback callback
    ) {
        String key = cacheKey(latitude, longitude);
        String cached = STREET_CACHE.get(key);
        if (cached != null) {
            if (callback != null) {
                callback.onStreetResolved(cached);
            }
            return;
        }
        if (context == null || !IN_FLIGHT_KEYS.add(key)) {
            return;
        }
        final Context appCtx = context.getApplicationContext() != null
                ? context.getApplicationContext()
                : context;
        GEOCODE_EXECUTOR.execute(() -> {
            String resolved = null;
            try {
                StreetLookup override = testStreetLookup;
                if (override != null) {
                    resolved = cleanAddressPart(override.lookup(appCtx, latitude, longitude));
                } else {
                    resolved = lookupViaAndroidGeocoder(appCtx, latitude, longitude);
                    if (resolved == null && !isRobolectricEnvironment()) {
                        resolved = lookupViaNominatim(latitude, longitude);
                    }
                }
                if (resolved != null && !resolved.isEmpty()) {
                    STREET_CACHE.put(key, resolved);
                }
            } finally {
                IN_FLIGHT_KEYS.remove(key);
            }
            if (callback != null && resolved != null && !resolved.isEmpty()) {
                callback.onStreetResolved(resolved);
            }
        });
    }

    @VisibleForTesting
    public static void setTestStreetLookupForTesting(@Nullable StreetLookup lookup) {
        if (!BuildConfig.DEBUG) return;
        testStreetLookup = lookup;
    }

    @VisibleForTesting
    public static void clearCacheForTesting() {
        if (!BuildConfig.DEBUG) return;
        STREET_CACHE.clear();
        IN_FLIGHT_KEYS.clear();
        testStreetLookup = null;
    }

    @VisibleForTesting
    public static void awaitExecutorForTesting() throws Exception {
        if (!BuildConfig.DEBUG) return;
        GEOCODE_EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    private static boolean isRobolectricEnvironment() {
        return "robolectric".equalsIgnoreCase(Build.FINGERPRINT)
                || "robolectric".equalsIgnoreCase(Build.BRAND);
    }

    @Nullable
    private static String lookupViaAndroidGeocoder(Context context, double latitude, double longitude) {
        try {
            if (!Geocoder.isPresent()) {
                return null;
            }
            Geocoder geocoder = new Geocoder(context, Locale.getDefault());
            List<Address> addresses = null;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                CountDownLatch latch = new CountDownLatch(1);
                AtomicReference<List<Address>> ref = new AtomicReference<>();
                geocoder.getFromLocation(latitude, longitude, 1, new Geocoder.GeocodeListener() {
                    @Override
                    public void onGeocode(List<Address> results) {
                        ref.set(results);
                        latch.countDown();
                    }

                    @Override
                    public void onError(@Nullable String errorMessage) {
                        latch.countDown();
                    }
                });
                if (latch.await(3, TimeUnit.SECONDS)) {
                    addresses = ref.get();
                }
            } else {
                addresses = geocoder.getFromLocation(latitude, longitude, 1);
            }
            if (addresses != null && !addresses.isEmpty()) {
                return formatStreetAddress(addresses.get(0));
            }
        } catch (Exception ignored) {
            // Fall through to HTTP fallback or coordinate display
        }
        return null;
    }

    @Nullable
    private static String lookupViaNominatim(double latitude, double longitude) {
        HttpURLConnection conn = null;
        try {
            String urlStr = String.format(Locale.US,
                    "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.5f&lon=%.5f&zoom=17&addressdetails=1",
                    latitude, longitude);
            conn = (HttpURLConnection) new URL(urlStr).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(NOMINATIM_TIMEOUT_MS);
            conn.setReadTimeout(NOMINATIM_TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "BYDWatchCompanion/1.0 (Android)");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Accept-Language", Locale.getDefault().toLanguageTag());

            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                char[] buf = new char[1024];
                int read;
                while ((read = br.read(buf)) != -1 && sb.length() < 16_384) {
                    sb.append(buf, 0, read);
                }
            }
            return parseNominatimStreet(new JSONObject(sb.toString()));
        } catch (Exception ignored) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    @Nullable
    public static String parseNominatimStreet(@Nullable JSONObject json) {
        if (json == null) return null;
        JSONObject addr = json.optJSONObject("address");
        String road = null;
        String area = null;
        if (addr != null) {
            road = firstNonEmptyPart(addr, "road", "pedestrian", "footway", "residential", "path", "hamlet");
            area = firstNonEmptyPart(addr, "suburb", "neighbourhood", "village", "city_district", "town", "city");
        }
        if (road != null) {
            return (area != null && !road.toLowerCase(Locale.US).contains(area.toLowerCase(Locale.US)))
                    ? road + ", " + area
                    : road;
        }
        String placeName = cleanAddressPart(json.optString("name", ""));
        if (placeName != null) {
            return (area != null && !placeName.toLowerCase(Locale.US).contains(area.toLowerCase(Locale.US)))
                    ? placeName + ", " + area
                    : placeName;
        }
        if (area != null) {
            return area;
        }
        String displayName = cleanAddressPart(json.optString("display_name", ""));
        if (displayName != null) {
            int firstComma = displayName.indexOf(',');
            return firstComma > 0 ? displayName.substring(0, firstComma).trim() : displayName;
        }
        return null;
    }

    @Nullable
    public static String formatStreetAddress(@Nullable Address address) {
        if (address == null) return null;
        String street = cleanAddressPart(address.getThoroughfare());
        String area = cleanAddressPart(address.getSubLocality());
        if (area == null) {
            area = cleanAddressPart(address.getLocality());
        }
        if (area == null) {
            area = cleanAddressPart(address.getSubAdminArea());
        }
        if (street != null) {
            return (area != null && !street.toLowerCase(Locale.US).contains(area.toLowerCase(Locale.US)))
                    ? street + ", " + area
                    : street;
        }
        String feature = cleanAddressPart(address.getFeatureName());
        if (feature != null && !isPureDigits(feature)) {
            return (area != null && !feature.toLowerCase(Locale.US).contains(area.toLowerCase(Locale.US)))
                    ? feature + ", " + area
                    : feature;
        }
        if (area != null) {
            return area;
        }
        if (address.getMaxAddressLineIndex() >= 0) {
            String line = cleanAddressPart(address.getAddressLine(0));
            if (line != null) {
                int firstComma = line.indexOf(',');
                return firstComma > 0 ? line.substring(0, firstComma).trim() : line;
            }
        }
        return null;
    }

    @Nullable
    private static String firstNonEmptyPart(JSONObject obj, String... keys) {
        for (String key : keys) {
            String cleaned = cleanAddressPart(obj.optString(key, ""));
            if (cleaned != null) return cleaned;
        }
        return null;
    }

    @Nullable
    private static String cleanAddressPart(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return null;
        }
        if (PLUS_CODE_PATTERN.matcher(trimmed).matches() || COORD_PATTERN.matcher(trimmed).matches()) {
            return null;
        }
        return trimmed;
    }

    private static boolean isPureDigits(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (!Character.isDigit(s.charAt(i))) return false;
        }
        return !s.isEmpty();
    }

    public static Intent buildGeoNavigationIntent(double latitude, double longitude, @Nullable String label) {
        String latStr = String.format(Locale.US, "%.6f", latitude);
        String lngStr = String.format(Locale.US, "%.6f", longitude);
        String cleanLabel = (label != null && !label.trim().isEmpty()) ? label.trim() : "BYD Vehicle";
        Uri geoUri = Uri.parse("geo:" + latStr + "," + lngStr
                + "?q=" + latStr + "," + lngStr + "(" + Uri.encode(cleanLabel) + ")");
        return new Intent(Intent.ACTION_VIEW, geoUri);
    }

    public static Intent buildWebMapsFallbackIntent(double latitude, double longitude) {
        String latStr = String.format(Locale.US, "%.6f", latitude);
        String lngStr = String.format(Locale.US, "%.6f", longitude);
        Uri webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=" + latStr + "," + lngStr);
        return new Intent(Intent.ACTION_VIEW, webUri);
    }

    public static Intent buildNavigationChooserIntent(Context context, double latitude, double longitude,
                                                      @Nullable String label) {
        Intent geoIntent = buildGeoNavigationIntent(latitude, longitude, label);
        Intent target = geoIntent;
        if (context != null && context.getPackageManager() != null) {
            List<ResolveInfo> handlers = context.getPackageManager().queryIntentActivities(geoIntent, 0);
            if (handlers == null || handlers.isEmpty()) {
                target = buildWebMapsFallbackIntent(latitude, longitude);
            }
        }
        String title = context != null
                ? context.getString(R.string.nav_chooser_title)
                : "Redirect to Navigation App";
        Intent chooser = Intent.createChooser(target, title);
        if (!(context instanceof Activity)) {
            chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        return chooser;
    }

    public static void showNavigatePromptDialog(
            Activity activity,
            double latitude,
            double longitude,
            @Nullable String carLabel,
            @Nullable Runnable onDone
    ) {
        if (activity == null || activity.isFinishing()) return;
        String label = (carLabel != null && !carLabel.trim().isEmpty()) ? carLabel.trim() : "BYD Vehicle";
        String coords = formatCoordinates(latitude, longitude);

        new MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.nav_dialog_title)
                .setMessage(activity.getString(R.string.nav_dialog_message, label, coords))
                .setPositiveButton(R.string.nav_dialog_confirm, (dialog, which) -> {
                    try {
                        activity.startActivity(buildNavigationChooserIntent(activity, latitude, longitude, label));
                    } catch (Exception e) {
                        Toast.makeText(activity, R.string.nav_no_app_error, Toast.LENGTH_SHORT).show();
                    }
                    if (onDone != null) onDone.run();
                })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> {
                    dialog.dismiss();
                    if (onDone != null) onDone.run();
                })
                .setOnCancelListener(dialog -> {
                    if (onDone != null) onDone.run();
                })
                .show();
    }
}
