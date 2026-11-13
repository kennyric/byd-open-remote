package com.overdrive.app.byd.cloud;

import org.json.JSONObject;

import java.util.Iterator;

/**
 * Local mirror of the BYD cloud smart-charging schedule.
 *
 * <p>When <code>/control/smartCharge/homePage</code> returns schedule DTOs,
 * they are the authoritative cloud state. This cache retains the last cloud
 * snapshot (or a confirmed local write) only for offline UI hydration.
 *
 * <p>Schema matches pyBYD's saveOrUpdate inner payload: startChargeTime,
 * endChargeTime, chargeWay, status.
 */
public final class SmartChargeCache {

    private static final CloudLog logger = CloudLog.getInstance("SmartChargeCache");
    private static final String CONFIRMED_SCHEDULE_AT = "confirmedScheduleAt";
    private static final String LAST_CLOUD_REQUEST_ORDER = "lastCloudRequestOrder";
    private static final String UNSUPPORTED = "unsupported";
    private static final long CONFIRMED_SCHEDULE_GRACE_MS = 2L * 60L * 1000L;
    private static final Object cacheLock = new Object();

    private static JSONObject memorySection = new JSONObject();

    private SmartChargeCache() {}

    private static void updateSection(JSONObject delta) throws Exception {
        if (delta == null) return;
        Iterator<String> keys = delta.keys();
        while (keys.hasNext()) {
            String k = keys.next();
            memorySection.put(k, delta.get(k));
        }
    }

    private static void replaceSection(JSONObject replacement) {
        memorySection = replacement != null ? replacement : new JSONObject();
    }

    public static void setEnabled(String vin, boolean enabled) {
        synchronized (cacheLock) {
            try {
                JSONObject section = currentSection(vin);
                section.put("enabled", enabled);
                updateSection(section);
            } catch (Exception e) {
                logger.info("setEnabled persist failed: " + e.getMessage());
            }
        }
    }

    public static void setSchedule(String vin, String startChargeTime, String endChargeTime,
                                    String chargeWay, boolean enabled) {
        synchronized (cacheLock) {
            try {
                JSONObject section = currentSection(vin);
                section.put("startChargeTime", startChargeTime);
                section.put("endChargeTime", endChargeTime);
                section.put("chargeWay", chargeWay);
                section.put("enabled", enabled);
                section.put(CONFIRMED_SCHEDULE_AT, System.currentTimeMillis());
                updateSection(section);
            } catch (Exception e) {
                logger.info("setSchedule persist failed: " + e.getMessage());
            }
        }
    }

    /**
     * A confirmed switch change is authoritative even if a preceding schedule
     * save is still inside its cloud-propagation grace period.
     */
    public static void confirmEnabled(String vin, boolean enabled) {
        synchronized (cacheLock) {
            try {
                updateSection(confirmedToggleSnapshot(currentSection(vin), enabled));
            } catch (Exception e) {
                logger.info("confirmEnabled persist failed: " + e.getMessage());
            }
        }
    }

    /**
     * Mark the cache unsupported only when the same VIN's smart-charge endpoint
     * is explicitly unavailable. A temporary cloud failure must retain offline
     * UI hydration, but endpoint support is a definitive invalidation.
     */
    public static void invalidate(String vin) {
        invalidate(vin, 0L);
    }

    /**
     * Store a capability tombstone so an older in-flight homePage response
     * cannot restore data that a newer response proved unsupported. A later
     * successful request has a higher order and replaces this marker.
     */
    public static void invalidate(String vin, long requestOrder) {
        if (vin == null || vin.isEmpty()) return;
        synchronized (cacheLock) {
            try {
                JSONObject current = memorySection;
                if (!matchesVin(current, vin) || !isOlderCloudResponse(current, requestOrder)) {
                    replaceSection(unsupportedSnapshot(vin, requestOrder));
                }
            } catch (Exception e) {
                logger.info("invalidate persist failed: " + e.getMessage());
            }
        }
    }

    /**
     * Replace the cloud schedule snapshot returned by smartCharge/homePage.
     */
    public static void updateFromCloud(String vin, JSONObject homePage) {
        if (vin == null || vin.isEmpty() || homePage == null) return;
        synchronized (cacheLock) {
            try {
                JSONObject current = currentSection(vin);
                long requestOrder = homePage.optLong(
                        BydCloudClient.SMART_CHARGE_REQUEST_ORDER_KEY, 0L);
                if (isOlderCloudResponse(current, requestOrder)) {
                    logger.info("Ignoring stale smartCharge homePage response");
                    return;
                }
                if (shouldPreserveConfirmedSchedule(current, homePage, System.currentTimeMillis())) {
                    logger.info("homePage schedule does not yet match confirmed save; retaining local cache");
                    if (requestOrder > 0L) {
                        updateSection(withCloudRequestOrder(current, requestOrder));
                    }
                    return;
                }
                JSONObject replacement = cloudSnapshot(vin, homePage);
                if (requestOrder > 0L) {
                    replacement.put(LAST_CLOUD_REQUEST_ORDER, requestOrder);
                }
                replaceSection(replacement);
            } catch (Exception e) {
                logger.info("updateFromCloud persist failed: " + e.getMessage());
            }
        }
    }

    /** Return the cached state for {@code vin}; never return another vehicle's schedule. */
    public static JSONObject getSnapshot(String vin) {
        synchronized (cacheLock) {
            try {
                JSONObject section = memorySection;
                if (!matchesVin(section, vin) || section.optBoolean(UNSUPPORTED, false)) {
                    return new JSONObject();
                }
                JSONObject snapshot = new JSONObject(section.toString());
                snapshot.remove(CONFIRMED_SCHEDULE_AT);
                snapshot.remove(LAST_CLOUD_REQUEST_ORDER);
                return snapshot;
            } catch (Exception e) {
                logger.info("getSnapshot failed: " + e.getMessage());
                return new JSONObject();
            }
        }
    }

    private static void copyIfPresent(JSONObject source, JSONObject destination, String key)
            throws Exception {
        if (source.has(key) && !source.isNull(key)) {
            destination.put(key, source.get(key));
        }
    }

    /** Build a replacement cache entry from one authoritative homePage response. */
    static JSONObject cloudSnapshot(String vin, JSONObject homePage) throws Exception {
        JSONObject section = new JSONObject();
        section.put("vin", vin);
        JSONObject charge = homePage.optJSONObject("smartChargeDto");
        if (charge != null) {
            copyIfPresent(charge, section, "startChargeTime");
            copyIfPresent(charge, section, "endChargeTime");
            copyIfPresent(charge, section, "chargeWay");
        }
        JSONObject journey = homePage.optJSONObject("smartJourneyDto");
        if (journey != null) {
            section.put("smartJourneyDto", new JSONObject(journey.toString()));
        }
        Boolean enabled = cloudEnabled(homePage);
        if (enabled != null) {
            section.put("enabled", enabled.booleanValue());
        }
        section.put("lastCloudScheduleAt", System.currentTimeMillis());
        return section;
    }

    /** The simple schedule takes precedence; journey-only schedules are still represented. */
    static Boolean cloudEnabled(JSONObject homePage) {
        if (homePage == null) return null;
        Boolean simple = statusEnabled(homePage.optJSONObject("smartChargeDto"));
        return simple != null ? simple : statusEnabled(homePage.optJSONObject("smartJourneyDto"));
    }

    private static Boolean statusEnabled(JSONObject dto) {
        if (dto == null) return null;
        Object value = null;
        if (dto.has("status") && !dto.isNull("status")) {
            value = dto.opt("status");
        } else if (dto.has("enabled") && !dto.isNull("enabled")) {
            value = dto.opt("enabled");
        } else if (dto.has("smartChargeSwitch") && !dto.isNull("smartChargeSwitch")) {
            value = dto.opt("smartChargeSwitch");
        }
        return normalizedBoolean(value);
    }

    private static Boolean normalizedBoolean(Object value) {
        if (value == null || value == JSONObject.NULL) return null;
        if (value instanceof Boolean) return (Boolean) value;
        if (value instanceof Number) {
            int status = ((Number) value).intValue();
            return status == 1 ? Boolean.TRUE : status == 0 ? Boolean.FALSE : null;
        }
        String status = String.valueOf(value).trim();
        if ("1".equals(status) || "true".equalsIgnoreCase(status)) return Boolean.TRUE;
        if ("0".equals(status) || "false".equalsIgnoreCase(status)) return Boolean.FALSE;
        return null;
    }

    static boolean shouldPreserveConfirmedSchedule(JSONObject current, JSONObject homePage,
                                                   long nowMs) {
        if (current == null || homePage == null) return false;
        long confirmedAt = current.optLong(CONFIRMED_SCHEDULE_AT, 0L);
        if (confirmedAt <= 0L || nowMs < confirmedAt
                || nowMs - confirmedAt > CONFIRMED_SCHEDULE_GRACE_MS) {
            return false;
        }
        return !homePageMatchesSchedule(homePage, current);
    }

    static JSONObject confirmedToggleSnapshot(JSONObject current, boolean enabled) throws Exception {
        JSONObject updated = current == null ? new JSONObject() : new JSONObject(current.toString());
        updated.put("enabled", enabled);
        updated.remove(CONFIRMED_SCHEDULE_AT);
        return updated;
    }

    static JSONObject unsupportedSnapshot(String vin, long requestOrder) throws Exception {
        JSONObject snapshot = new JSONObject();
        snapshot.put("vin", vin);
        snapshot.put(UNSUPPORTED, true);
        if (requestOrder > 0L) {
            snapshot.put(LAST_CLOUD_REQUEST_ORDER, requestOrder);
        }
        return snapshot;
    }

    static JSONObject withCloudRequestOrder(JSONObject current, long requestOrder) throws Exception {
        JSONObject updated = current == null ? new JSONObject() : new JSONObject(current.toString());
        if (requestOrder > updated.optLong(LAST_CLOUD_REQUEST_ORDER, 0L)) {
            updated.put(LAST_CLOUD_REQUEST_ORDER, requestOrder);
        }
        return updated;
    }

    static boolean isOlderCloudResponse(JSONObject current, long requestOrder) {
        if (current == null || requestOrder <= 0L) return false;
        long lastApplied = current.optLong(LAST_CLOUD_REQUEST_ORDER, 0L);
        return lastApplied > requestOrder;
    }

    public static boolean homePageMatchesSchedule(JSONObject homePage, JSONObject schedule) {
        if (homePage == null || schedule == null) return false;
        JSONObject dto = homePage.optJSONObject("smartChargeDto");
        if (dto == null) return false;
        Boolean enabled = cloudEnabled(homePage);
        return sameString(dto, schedule, "startChargeTime")
                && sameString(dto, schedule, "endChargeTime")
                && sameString(dto, schedule, "chargeWay")
                && enabled != null && schedule.has("enabled")
                && enabled.booleanValue() == schedule.optBoolean("enabled");
    }

    private static boolean sameString(JSONObject left, JSONObject right, String key) {
        return left.has(key) && !left.isNull(key)
                && right.has(key) && !right.isNull(key)
                && left.optString(key, "").equals(right.optString(key, ""));
    }

    static boolean matchesVin(JSONObject section, String vin) {
        return section != null && vin != null && vin.equals(section.optString("vin", ""));
    }

    private static JSONObject currentSection(String vin) throws Exception {
        if (!matchesVin(memorySection, vin)) {
            JSONObject section = new JSONObject();
            section.put("vin", vin != null ? vin : "");
            return section;
        } else {
            return new JSONObject(memorySection.toString());
        }
    }
}
