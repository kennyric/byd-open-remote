package com.bydwatch.companion.ui;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.RemoteViews;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.CustomActionCombo;
import com.bydwatch.companion.model.UnitPreferences;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;
import com.bydwatch.companion.model.WidgetSlotConfig;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.bydwatch.companion.protocol.WatchResponse;

import org.json.JSONObject;

import java.text.NumberFormat;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Responsive Material You home-screen widget with 30-minute background auto-sync
 * and direct background controls without opening the app:
 * - 2x2 grid: Battery, Range, plus large Lock / Unlock / Sync buttons.
 * - 4x1 grid: Single-row Battery %, Range, Progress bar, Status, plus Lock / Unlock / AC / Sync.
 * - 4x2 grid: Battery %, Range, Progress bar, AC status, Sync time, plus Lock / Unlock / AC / Sync.
 * - 4x3+ grid: Adds middle reverse-geocoded Car Location card with tap-to-navigate.
 */
public class VehicleWidgetProvider extends AppWidgetProvider {

    public static final String ACTION_WIDGET_REFRESH = "com.bydwatch.companion.WIDGET_REFRESH";
    public static final String ACTION_WIDGET_LOCK = "com.bydwatch.companion.WIDGET_LOCK";
    public static final String ACTION_WIDGET_UNLOCK = "com.bydwatch.companion.WIDGET_UNLOCK";
    public static final String ACTION_WIDGET_AC = "com.bydwatch.companion.WIDGET_AC";
    public static final String ACTION_WIDGET_AUTO_SYNC = "com.bydwatch.companion.WIDGET_AUTO_SYNC";
    public static final String ACTION_WIDGET_SLOT_PREFIX = "com.bydwatch.companion.WIDGET_SLOT_";

    static final int WIDE_WIDGET_MIN_WIDTH_DP = 220;
    static final int SHORT_WIDGET_MAX_HEIGHT_DP = 108;
    // Measured launcher reports: a 2-row span can be reported as high as ~189dp (4-column
    // launchers) while a 3-row span can be reported as low as ~208dp (5-column launchers),
    // so the tall cutoff sits between them.
    static final int TALL_WIDGET_MIN_HEIGHT_DP = 200;

    /**
     * Some launchers (e.g. Huawei home) report generous sizes for a 2x2 placement, so a
     * plain ">=220dp wide" check binds the wide layouts and squishes them into a square
     * cell. A reported box is treated as a compact 2x2 cell when it is square-ish: the
     * landscape width (when reported) is still narrow, or the portrait box is not
     * wide-enough-to-be-a-real-4x2/4x3 (those are at least ~1.6:1 and taller than two rows).
     */
    static final int COMPACT_MAX_LANDSCAPE_WIDTH_DP = 240;
    static final int COMPACT_MAX_SQUARE_HEIGHT_DP = 205;
    static final float COMPACT_MAX_ASPECT_RATIO = 1.6f;

    private static final String PREFS_WIDGET = "byd_widget_prefs";
    private static final String KEY_LAST_STATUS_JSON = "last_status_json";
    private static final String KEY_LAST_SYNC_MS = "last_sync_ms";
    private static final String KEY_BANNER_TEXT = "banner_text";
    private static final String KEY_STATUS_PREFIX = "widget_status_";
    private static final String KEY_STATUS_VIN_PREFIX = "widget_status_vin_";
    private static final String KEY_SYNC_PREFIX = "widget_sync_ms_";
    /** Widget action strings carry their instance id so each tile acts on its own car. */
    private static final String WIDGET_ID_SEP = "|wid=";
    private static final String KEY_BANNER_EXPIRES_MS = "banner_expires_ms";

    private static final long BANNER_TTL_MS = 18_000L;
    private static final long GO_ASYNC_GUARD_MS = 8_500L;
    private static final long STEP_TIMEOUT_MS = 45_000L;
    static volatile long comboStepDelayMs = 800L;
    private static final AtomicLong CMD_SEQ = new AtomicLong();
    private static final ExecutorService COMBO_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "byd-combo-runner");
        t.setDaemon(true);
        return t;
    });

    static void awaitComboExecutorForTesting() throws Exception {
        COMBO_EXECUTOR.submit(() -> {}).get(5, TimeUnit.SECONDS);
    }

    /** Callback for interactive combo execution inside MainActivity. */
    public interface ComboProgressListener {
        void onStepProgress(int stepIndex, int totalSteps, String label);
        void onFinished(boolean ok, String message);
    }

    /** Visual metadata for one resolved widget button slot. */
    public static final class SlotVisual {
        public final String label;
        public final int iconRes;
        public final int bgRes;
        public final int textColorRes;

        public SlotVisual(String label, int iconRes, int bgRes, int textColorRes) {
            this.label = label;
            this.iconRes = iconRes;
            this.bgRes = bgRes;
            this.textColorRes = textColorRes;
        }
    }

    private static final int[] SLOT_TILE_IDS = {
            R.id.tileWidgetLock,
            R.id.tileWidgetUnlock,
            R.id.tileWidgetAc,
            R.id.tileWidgetRefresh
    };
    private static final int[] SLOT_ICON_IDS = {
            R.id.ivWidgetLock,
            R.id.ivWidgetUnlock,
            R.id.ivWidgetAc,
            R.id.ivWidgetRefresh
    };
    private static final int[] SLOT_BTN_IDS = {
            R.id.btnWidgetLock,
            R.id.btnWidgetUnlock,
            R.id.btnWidgetAc,
            R.id.btnWidgetRefresh
    };

    public static String slotAction(int slotIndex) {
        return ACTION_WIDGET_SLOT_PREFIX + slotIndex;
    }

    static int parseSlotIndex(@Nullable String act) {
        if (act == null || !act.startsWith(ACTION_WIDGET_SLOT_PREFIX)) {
            return -1;
        }
        try {
            int idx = Integer.parseInt(act.substring(ACTION_WIDGET_SLOT_PREFIX.length()));
            return (idx >= 0 && idx < WidgetSlotConfig.SLOT_COUNT) ? idx : -1;
        } catch (Exception ignored) {
            return -1;
        }
    }

    @Override
    public void onEnabled(Context context) {
        super.onEnabled(context);
        WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
    }

    @Override
    public void onDisabled(Context context) {
        super.onDisabled(context);
        if (!LocationWidgetProvider.hasActiveWidgets(context)) {
            WidgetSyncJobService.cancelPeriodicSync(context);
            clearTransientBanner(context);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(
            Context context,
            AppWidgetManager appWidgetManager,
            int appWidgetId,
            Bundle newOptions
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions);
        updateAllWidgets(context, appWidgetManager, new int[]{appWidgetId});
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager appWidgetManager, int[] appWidgetIds) {
        WidgetSyncJobService.ensurePeriodicSyncScheduled(context);
        updateAllWidgets(context, appWidgetManager, appWidgetIds);

        if (isAutoSyncNeeded(context)) {
            executeActionAsync(this, context, WatchProtocol.ACTION_STATUS, false);
        }
    }

    @Override
    public void onDeleted(Context context, int[] appWidgetIds) {
        super.onDeleted(context, appWidgetIds);
        if (context == null || appWidgetIds == null) return;
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        SharedPreferences.Editor prefs = context
                .getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE).edit();
        for (int id : appWidgetIds) {
            if (cfg != null) cfg.setWidgetVehicle(id, "");
            prefs.remove(KEY_STATUS_PREFIX + id)
                    .remove(KEY_STATUS_VIN_PREFIX + id)
                    .remove(KEY_SYNC_PREFIX + id);
        }
        prefs.apply();
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        super.onReceive(context, intent);
        // Custom widget actions are routed exclusively through unexported WidgetActionReceiver.
    }

    static String withWidgetId(String action, int appWidgetId) {
        if (action == null || appWidgetId < 0) return action;
        return action + WIDGET_ID_SEP + appWidgetId;
    }

    static String baseAction(@Nullable String action) {
        if (action == null) return "";
        int idx = action.indexOf(WIDGET_ID_SEP);
        return idx < 0 ? action : action.substring(0, idx);
    }

    static int parseWidgetId(@Nullable String action) {
        if (action == null) return -1;
        int idx = action.indexOf(WIDGET_ID_SEP);
        if (idx < 0) return -1;
        try {
            return Integer.parseInt(action.substring(idx + WIDGET_ID_SEP.length()));
        } catch (Exception ignored) {
            return -1;
        }
    }

    static boolean handlesAction(String act) {
        act = baseAction(act);
        return ACTION_WIDGET_AUTO_SYNC.equals(act)
                || ACTION_WIDGET_REFRESH.equals(act)
                || ACTION_WIDGET_LOCK.equals(act)
                || ACTION_WIDGET_UNLOCK.equals(act)
                || ACTION_WIDGET_AC.equals(act)
                || parseSlotIndex(act) >= 0;
    }

    static void handleInternalAction(BroadcastReceiver receiver, Context context, String rawAct) {
        String act = baseAction(rawAct);
        int appWidgetId = parseWidgetId(rawAct);
        if (ACTION_WIDGET_AUTO_SYNC.equals(act)) {
            if (WidgetSyncJobService.hasAnyActiveWidgets(context)) {
                syncEveryWidgetVehicle(receiver, context);
                WidgetSyncJobService.rescheduleShortAlarmIfNeeded(context);
            } else {
                WidgetSyncJobService.cancelPeriodicSync(context);
            }
            return;
        }
        int slotIdx = parseSlotIndex(act);
        if (slotIdx >= 0) {
            BydWatchApp app = resolveApp(context);
            ConfigStore cfg = app != null ? app.getConfigStore() : null;
            List<WidgetSlotConfig> slots = cfg != null ? cfg.getWidgetSlots() : WidgetSlotConfig.defaults();
            WidgetSlotConfig slot = slotIdx < slots.size()
                    ? slots.get(slotIdx)
                    : WidgetSlotConfig.defaults().get(slotIdx);
            executeSlotAsync(receiver, context, slot, appWidgetId);
            return;
        }
        String watchAction = null;
        if (ACTION_WIDGET_REFRESH.equals(act)) {
            watchAction = WatchProtocol.ACTION_STATUS;
        } else if (ACTION_WIDGET_LOCK.equals(act)) {
            watchAction = WatchProtocol.ACTION_LOCK;
        } else if (ACTION_WIDGET_UNLOCK.equals(act)) {
            watchAction = WatchProtocol.ACTION_UNLOCK;
        } else if (ACTION_WIDGET_AC.equals(act)) {
            VehicleStatus st = resolveStatus(context, appWidgetId);
            watchAction = (st != null && st.acOn)
                    ? WatchProtocol.ACTION_AC_OFF
                    : WatchProtocol.ACTION_AC_ON;
        }

        if (watchAction != null) {
            executeActionAsync(receiver, context, watchAction, true, appWidgetId);
        }
    }

    public static boolean hasActiveWidgets(Context context) {
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, VehicleWidgetProvider.class));
        return ids != null && ids.length > 0;
    }

    public static void refreshWidgets(Context context) {
        if (context == null) return;
        if (Looper.myLooper() != Looper.getMainLooper()) {
            Context appCtx = context.getApplicationContext() != null
                    ? context.getApplicationContext()
                    : context;
            new Handler(Looper.getMainLooper()).post(() -> refreshWidgetsOnMain(appCtx));
            return;
        }
        refreshWidgetsOnMain(context);
    }

    private static void refreshWidgetsOnMain(Context context) {
        if (context == null) return;
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        VehicleStatus live = repo != null ? repo.getCachedStatus() : null;
        if (live != null) {
            persistStatus(context, live, false);
        }
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, VehicleWidgetProvider.class));
        if (ids != null && ids.length > 0) {
            updateAllWidgets(context, mgr, ids);
        }
        LocationWidgetProvider.refreshWidgets(context);
    }

    public static void recordSuccessfulSync(Context context, VehicleStatus status) {
        persistStatus(context, status, true);
    }

    /**
     * Executes a single widget action in the background via BydRepository,
     * persists returned telemetry, and refreshes all home-screen widgets.
     */
    public static boolean executeWidgetAction(
            Context context,
            String watchAction,
            boolean showProgressBanner,
            @Nullable Runnable onComplete
    ) {
        return executeWidgetAction(context, watchAction, showProgressBanner, -1, onComplete);
    }

    public static boolean executeWidgetAction(
            Context context,
            String watchAction,
            boolean showProgressBanner,
            int appWidgetId,
            @Nullable Runnable onComplete
    ) {
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (repo == null || cfg == null || !cfg.isConfigured()) {
            if (showProgressBanner) {
                setTransientBanner(context, "Setup app");
            }
            refreshWidgets(context);
            return false;
        }

        if (showProgressBanner) {
            setTransientBanner(context, progressLabelForAction(watchAction));
            refreshWidgets(context);
        }

        try {
            String boundVin = boundVinFor(context, appWidgetId);
            JSONObject cmd = new JSONObject();
            cmd.put("id", "wdg_" + System.currentTimeMillis() + "_" + CMD_SEQ.incrementAndGet());
            cmd.put("action", watchAction);
            if (!boundVin.isEmpty()) {
                cmd.put("vin", boundVin);
            }
            if (WatchProtocol.ACTION_AC_ON.equals(watchAction)) {
                if (cfg.isManualAcMode()) {
                    cmd.put("tempGear", cfg.getDefaultTempGear());
                    cmd.put("fanLevel", cfg.getDefaultFanLevel());
                    cmd.put("windMode", cfg.getDefaultWindMode());
                } else {
                    cmd.put("tempC", cfg.getDefaultTempC());
                }
                cmd.put("acDurationMin", cfg.getDefaultAcDurationMinutes());
            }
            if (BuildConfig.DEMO_BUILD) {
                // Demo build: show the synthetic sample instead of calling BYD Cloud.
                VehicleStatus demoSample = boundVin.isEmpty()
                        ? repo.currentStatusOrDemo()
                        : repo.demoSampleForVin(boundVin);
                if (demoSample == null) demoSample = repo.currentStatusOrDemo();
                persistStatus(context, demoSample, true, appWidgetId);
                clearTransientBanner(context);
                refreshWidgets(context);
                if (onComplete != null) {
                    onComplete.run();
                }
                return true;
            }
            repo.handleWatchJson(cmd.toString(), respStr -> {
                try {
                    WatchResponse resp = WatchResponse.fromJson(new JSONObject(respStr));
                    if (resp != null && resp.ok && resp.state != null) {
                        persistStatus(context, resp.state, true, appWidgetId);
                        clearTransientBanner(context);
                    } else if (showProgressBanner) {
                        setTransientBanner(context, shortWidgetErrorBanner(resp));
                    }
                    refreshWidgets(context);
                } catch (Exception e) {
                    if (showProgressBanner) {
                        setTransientBanner(context, "Sync failed");
                        refreshWidgets(context);
                    }
                } finally {
                    if (onComplete != null) {
                        onComplete.run();
                    }
                }
            });
            return true;
        } catch (Exception e) {
            if (showProgressBanner) {
                setTransientBanner(context, "Error");
                refreshWidgets(context);
            }
            return false;
        }
    }

    static void executeActionAsync(
            @Nullable BroadcastReceiver receiver,
            Context context,
            String watchAction,
            boolean showProgressBanner
    ) {
        executeActionAsync(receiver, context, watchAction, showProgressBanner, -1);
    }

    static void executeActionAsync(
            @Nullable BroadcastReceiver receiver,
            Context context,
            String watchAction,
            boolean showProgressBanner,
            int appWidgetId
    ) {
        PendingResult pendingResult = receiver != null ? receiver.goAsync() : null;
        AtomicBoolean finished = new AtomicBoolean(false);
        Runnable finishOnce = () -> {
            if (finished.compareAndSet(false, true) && pendingResult != null) {
                try {
                    pendingResult.finish();
                } catch (Exception ignored) {}
            }
        };

        Handler mainHandler = new Handler(Looper.getMainLooper());
        mainHandler.postDelayed(finishOnce, GO_ASYNC_GUARD_MS);

        boolean started = executeWidgetAction(context, watchAction, showProgressBanner, appWidgetId, () -> {
            mainHandler.removeCallbacks(finishOnce);
            finishOnce.run();
        });
        if (!started) {
            mainHandler.removeCallbacks(finishOnce);
            finishOnce.run();
        }
    }

    static void executeSlotAsync(
            @Nullable BroadcastReceiver receiver,
            Context context,
            @Nullable WidgetSlotConfig slot
    ) {
        executeSlotAsync(receiver, context, slot, -1);
    }

    static void executeSlotAsync(
            @Nullable BroadcastReceiver receiver,
            Context context,
            @Nullable WidgetSlotConfig slot,
            int appWidgetId
    ) {
        if (slot != null && slot.isCombo()) {
            BydWatchApp app = resolveApp(context);
            ConfigStore cfg = app != null ? app.getConfigStore() : null;
            CustomActionCombo combo = findComboById(cfg, slot.targetId);
            if (combo == null || combo.steps.isEmpty()) {
                Context resCtx = AppLocaleHelper.localizedContext(context, cfg);
                setTransientBanner(context, resCtx.getString(R.string.combo_missing_banner));
                refreshWidgets(context);
                return;
            }
            PendingResult pendingResult = receiver != null ? receiver.goAsync() : null;
            AtomicBoolean finished = new AtomicBoolean(false);
            Runnable finishOnce = () -> {
                if (finished.compareAndSet(false, true) && pendingResult != null) {
                    try {
                        pendingResult.finish();
                    } catch (Exception ignored) {}
                }
            };
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.postDelayed(finishOnce, GO_ASYNC_GUARD_MS);

            executeCombo(context, combo, true, appWidgetId, null, () -> {
                mainHandler.removeCallbacks(finishOnce);
                finishOnce.run();
            });
            return;
        }
        String target = slot != null ? slot.targetId : WidgetSlotConfig.SINGLE_STATUS;
        String watchAction = resolveWatchActionForSingleSlot(context, target, appWidgetId);
        executeActionAsync(receiver, context, watchAction, true, appWidgetId);
    }

    public static void executeCombo(
            Context context,
            CustomActionCombo combo,
            boolean updateWidgetBanner,
            @Nullable ComboProgressListener listener,
            @Nullable Runnable onComplete
    ) {
        executeCombo(context, combo, updateWidgetBanner, -1, listener, onComplete);
    }

    public static void executeCombo(
            Context context,
            CustomActionCombo combo,
            boolean updateWidgetBanner,
            int appWidgetId,
            @Nullable ComboProgressListener listener,
            @Nullable Runnable onComplete
    ) {
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        Context resCtx = AppLocaleHelper.localizedContext(context, cfg);

        if (repo == null || cfg == null || !cfg.isConfigured()) {
            String errMsg = resCtx.getString(R.string.combo_setup_required);
            if (updateWidgetBanner) {
                setTransientBanner(context, errMsg);
                refreshWidgets(context);
            }
            if (listener != null) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onFinished(false, errMsg));
            }
            if (onComplete != null) onComplete.run();
            return;
        }
        if (combo == null || combo.steps.isEmpty()) {
            String errMsg = resCtx.getString(R.string.combo_empty_error);
            if (updateWidgetBanner) {
                setTransientBanner(context, errMsg);
                refreshWidgets(context);
            }
            if (listener != null) {
                new Handler(Looper.getMainLooper()).post(() -> listener.onFinished(false, errMsg));
            }
            if (onComplete != null) onComplete.run();
            return;
        }

        Handler mainHandler = new Handler(Looper.getMainLooper());
        COMBO_EXECUTOR.execute(() -> {
            int total = combo.steps.size();
            boolean allOk = true;
            String finalMsg = resCtx.getString(R.string.combo_done_fmt, combo.name);

            for (int i = 0; i < total; i++) {
                CustomActionCombo.ComboStep step = combo.steps.get(i);
                String stepLabel = (i + 1) + "/" + total + " · " + stepDisplayName(resCtx, step);
                if (updateWidgetBanner) {
                    setTransientBanner(context, stepLabel);
                    refreshWidgets(context);
                }
                if (listener != null) {
                    final int idx = i + 1;
                    mainHandler.post(() -> listener.onStepProgress(idx, total, stepLabel));
                }

                String boundVin = boundVinFor(context, appWidgetId);
                WatchResponse resp = executeComboStepSync(repo, cfg, step, boundVin);
                if (resp != null && resp.ok) {
                    if (resp.state != null) {
                        persistStatus(context, resp.state, true, appWidgetId);
                    }
                } else {
                    allOk = false;
                    finalMsg = (i + 1) + "/" + total + ": " + shortWidgetErrorBanner(resp);
                    break;
                }

                if (i < total - 1 && comboStepDelayMs > 0L) {
                    try {
                        Thread.sleep(comboStepDelayMs);
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                        allOk = false;
                        finalMsg = "Interrupted";
                        break;
                    }
                }
            }

            if (updateWidgetBanner) {
                if (allOk) {
                    clearTransientBanner(context);
                } else {
                    setTransientBanner(context, finalMsg);
                }
                refreshWidgets(context);
            } else {
                refreshWidgets(context);
            }

            final boolean okResult = allOk;
            final String msgResult = finalMsg;
            if (listener != null) {
                mainHandler.post(() -> listener.onFinished(okResult, msgResult));
            }
            if (onComplete != null) {
                onComplete.run();
            }
        });
    }

    @Nullable
    private static WatchResponse executeComboStepSync(
            BydRepository repo,
            ConfigStore cfg,
            CustomActionCombo.ComboStep step
    ) {
        return executeComboStepSync(repo, cfg, step, "");
    }

    private static WatchResponse executeComboStepSync(
            BydRepository repo,
            ConfigStore cfg,
            CustomActionCombo.ComboStep step,
            String vin
    ) {
        try {
            JSONObject cmd = buildCommandJsonForStep(cfg, step, vin);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<WatchResponse> ref = new AtomicReference<>();
            repo.handleWatchJson(cmd.toString(), respStr -> {
                try {
                    ref.set(WatchResponse.fromJson(new JSONObject(respStr)));
                } catch (Exception ignored) {
                } finally {
                    latch.countDown();
                }
            });
            boolean completed = latch.await(STEP_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return completed ? ref.get() : null;
        } catch (Exception ignored) {
            return null;
        }
    }

    static JSONObject buildCommandJsonForStep(ConfigStore cfg, CustomActionCombo.ComboStep step) throws Exception {
        return buildCommandJsonForStep(cfg, step, "");
    }

    static JSONObject buildCommandJsonForStep(ConfigStore cfg, CustomActionCombo.ComboStep step, String vin) throws Exception {
        JSONObject cmd = new JSONObject();
        cmd.put("id", "cmb_" + System.currentTimeMillis() + "_" + CMD_SEQ.incrementAndGet());
        cmd.put("action", step.action);
        if (vin != null && !vin.isEmpty()) {
            cmd.put("vin", vin);
        }
        if (WatchProtocol.ACTION_AC_ON.equals(step.action)) {
            if (step.useCustomAc) {
                if (step.manualAc) {
                    cmd.put("acMode", "manual");
                    cmd.put("tempGear", step.tempGear);
                    cmd.put("fanLevel", step.fanLevel);
                    cmd.put("windMode", step.windMode);
                } else {
                    cmd.put("acMode", "auto");
                    cmd.put("tempC", step.tempC);
                }
                cmd.put("acDurationMin", step.acDurationMinutes);
            } else if (cfg != null && cfg.isManualAcMode()) {
                cmd.put("tempGear", cfg.getDefaultTempGear());
                cmd.put("fanLevel", cfg.getDefaultFanLevel());
                cmd.put("windMode", cfg.getDefaultWindMode());
                cmd.put("acDurationMin", cfg.getDefaultAcDurationMinutes());
            } else if (cfg != null) {
                cmd.put("tempC", cfg.getDefaultTempC());
                cmd.put("acDurationMin", cfg.getDefaultAcDurationMinutes());
            }
        } else if (WatchProtocol.ACTION_SEAT_HEAT.equals(step.action) && step.seatLevel >= 0) {
            cmd.put("level", step.seatLevel);
        }
        return cmd;
    }

    static String resolveWatchActionForSingleSlot(Context context, String singleKey) {
        return resolveWatchActionForSingleSlot(context, singleKey, -1);
    }

    static String resolveWatchActionForSingleSlot(Context context, String singleKey, int appWidgetId) {
        if (WidgetSlotConfig.SINGLE_LOCK.equals(singleKey)) {
            return WatchProtocol.ACTION_LOCK;
        }
        if (WidgetSlotConfig.SINGLE_UNLOCK.equals(singleKey)) {
            return WatchProtocol.ACTION_UNLOCK;
        }
        if (WidgetSlotConfig.SINGLE_AC_TOGGLE.equals(singleKey)) {
            VehicleStatus st = resolveStatus(context, appWidgetId);
            return (st != null && st.acOn) ? WatchProtocol.ACTION_AC_OFF : WatchProtocol.ACTION_AC_ON;
        }
        if (WidgetSlotConfig.SINGLE_LIGHTS.equals(singleKey)) {
            return WatchProtocol.ACTION_LIGHTS;
        }
        if (WidgetSlotConfig.SINGLE_HORN.equals(singleKey)) {
            return WatchProtocol.ACTION_HORN;
        }
        if (WidgetSlotConfig.SINGLE_VENT_WINDOWS.equals(singleKey)) {
            return WatchProtocol.ACTION_VENT_WINDOWS;
        }
        if (WidgetSlotConfig.SINGLE_CLOSE_WINDOWS.equals(singleKey)) {
            return WatchProtocol.ACTION_CLOSE_WINDOWS;
        }
        if (WidgetSlotConfig.SINGLE_BAT_HEAT.equals(singleKey)) {
            VehicleStatus st = resolveStatus(context, appWidgetId);
            return (st != null && Boolean.TRUE.equals(st.batteryHeatOn))
                    ? WatchProtocol.ACTION_BATTERY_HEAT_OFF
                    : WatchProtocol.ACTION_BATTERY_HEAT_ON;
        }
        if (WidgetSlotConfig.SINGLE_SEAT_HEAT.equals(singleKey)) {
            return WatchProtocol.ACTION_SEAT_HEAT;
        }
        if (WidgetSlotConfig.SINGLE_LOCATION.equals(singleKey)) {
            return WatchProtocol.ACTION_LOCATION;
        }
        return WatchProtocol.ACTION_STATUS;
    }

    @Nullable
    static CustomActionCombo findComboById(@Nullable ConfigStore cfg, @Nullable String id) {
        if (cfg == null || id == null || id.isEmpty()) return null;
        for (CustomActionCombo c : cfg.getActionCombos()) {
            if (id.equals(c.id)) {
                return c;
            }
        }
        return null;
    }

    private static boolean isAutoSyncNeeded(Context context) {
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (cfg == null || !cfg.isConfigured()) {
            return false;
        }
        long lastSyncMs = cfg.getWidgetLastSyncMs();
        int intervalMin = WidgetSyncJobService.getConfiguredIntervalMinutes(context);
        long intervalMs = intervalMin * 60L * 1000L;
        long staleMs = intervalMin >= 5 ? (intervalMs - 60_000L) : Math.max(30_000L, intervalMs / 2L);
        return lastSyncMs <= 0L || (System.currentTimeMillis() - lastSyncMs) >= staleMs;
    }

    private static String progressLabelForAction(String action) {
        if (WatchProtocol.ACTION_LOCK.equals(action)) return "Locking...";
        if (WatchProtocol.ACTION_UNLOCK.equals(action)) return "Unlocking...";
        if (WatchProtocol.ACTION_AC_ON.equals(action)) return "AC On...";
        if (WatchProtocol.ACTION_AC_OFF.equals(action)) return "AC Off...";
        if (WatchProtocol.ACTION_LIGHTS.equals(action)) return "Lights...";
        if (WatchProtocol.ACTION_HORN.equals(action)) return "Horn...";
        if (WatchProtocol.ACTION_VENT_WINDOWS.equals(action)) return "Venting...";
        if (WatchProtocol.ACTION_CLOSE_WINDOWS.equals(action)) return "Closing...";
        if (WatchProtocol.ACTION_BATTERY_HEAT_ON.equals(action)) return "Bat Heat...";
        if (WatchProtocol.ACTION_BATTERY_HEAT_OFF.equals(action)) return "Bat Heat Off...";
        if (WatchProtocol.ACTION_SEAT_HEAT.equals(action)) return "Seat Heat...";
        if (WatchProtocol.ACTION_LOCATION.equals(action)) return "Locating...";
        return "Syncing...";
    }

    public static String stepDisplayName(Context resCtx, CustomActionCombo.ComboStep step) {
        if (step == null) return "";
        switch (step.action) {
            case WatchProtocol.ACTION_LOCK:
                return resCtx.getString(R.string.btn_lock);
            case WatchProtocol.ACTION_UNLOCK:
                return resCtx.getString(R.string.btn_unlock);
            case WatchProtocol.ACTION_AC_ON:
                if (!step.useCustomAc) return resCtx.getString(R.string.btn_start_ac);
                if (step.manualAc) {
                    return resCtx.getString(R.string.combo_step_ac_manual_fmt,
                            step.tempGear, step.fanLevel, step.acDurationMinutes);
                }
                return resCtx.getString(R.string.combo_step_ac_auto_fmt,
                        step.tempC, step.acDurationMinutes);
            case WatchProtocol.ACTION_AC_OFF:
                return resCtx.getString(R.string.btn_ac_off);
            case WatchProtocol.ACTION_LIGHTS:
                return resCtx.getString(R.string.btn_lights);
            case WatchProtocol.ACTION_HORN:
                return resCtx.getString(R.string.btn_horn);
            case WatchProtocol.ACTION_VENT_WINDOWS:
                return resCtx.getString(R.string.btn_vent_windows);
            case WatchProtocol.ACTION_CLOSE_WINDOWS:
                return resCtx.getString(R.string.btn_close_windows);
            case WatchProtocol.ACTION_BATTERY_HEAT_ON:
                return resCtx.getString(R.string.combo_step_bat_heat_on);
            case WatchProtocol.ACTION_BATTERY_HEAT_OFF:
                return resCtx.getString(R.string.combo_step_bat_heat_off);
            case WatchProtocol.ACTION_SEAT_HEAT:
                if (step.seatLevel == 0) return resCtx.getString(R.string.combo_step_seat_off);
                if (step.seatLevel == 1) return resCtx.getString(R.string.combo_step_seat_low);
                if (step.seatLevel == 2) return resCtx.getString(R.string.combo_step_seat_high);
                return resCtx.getString(R.string.btn_seat_heat);
            case WatchProtocol.ACTION_LOCATION:
                return resCtx.getString(R.string.slot_label_location);
            case WatchProtocol.ACTION_STATUS:
            default:
                return resCtx.getString(R.string.btn_sync);
        }
    }

    static String shortWidgetErrorBanner(@Nullable WatchResponse resp) {
        if (resp == null) return "Sync failed";
        String err = resp.err != null ? resp.err : "";
        String msg = resp.msg != null ? resp.msg.trim() : "";
        String lower = msg.toLowerCase(Locale.US);
        if ("NOT_CONFIGURED".equals(err)) return "Setup app";
        if ("PIN_INVALID".equals(err)) return "Check PIN";
        if ("AUTH_FAILED".equals(err)) return "Sign-in err";
        if ("CAR_OFFLINE".equals(err)) return "Car offline";
        if (lower.contains("reach byd cloud") || lower.contains("internet connection")) {
            return "No internet";
        }
        if (lower.contains("unstable") || lower.contains("interrupted")) {
            return "Weak signal";
        }
        if ("TIMEOUT".equals(err) || lower.contains("timed out")) {
            return "Timed out";
        }
        if (lower.contains("busy")) return "Cloud busy";
        if (!msg.isEmpty() && msg.length() <= 18) return msg;
        return "Sync failed";
    }

    @Nullable
    static BydWatchApp resolveApp(Context context) {
        BydWatchApp app = BydWatchApp.getInstance();
        if (app == null && context != null && context.getApplicationContext() instanceof BydWatchApp) {
            app = (BydWatchApp) context.getApplicationContext();
        }
        return app;
    }

    /** Vehicle pinned to this widget, or "" for "follow the active car". Only multi-car accounts bind. */
    static String boundVinFor(Context context, int appWidgetId) {
        if (appWidgetId < 0) return "";
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (cfg == null) return "";
        List<VehicleSummary> cars = cfg.getVehicles();
        if (cars == null || cars.size() < 2) return "";
        String vin = cfg.getWidgetVehicle(appWidgetId);
        if (vin == null || vin.trim().isEmpty()) return "";
        for (VehicleSummary car : cars) {
            if (car != null && vin.equals(car.vin)) return vin;
        }
        return "";
    }

    static boolean canBindWidgetToVehicle(@Nullable ConfigStore cfg) {
        return cfg != null && cfg.getVehicles() != null && cfg.getVehicles().size() > 1;
    }

    /** Status for one widget: its bound car when pinned, else the active car. */
    @Nullable
    static VehicleStatus resolveStatus(Context context, int appWidgetId) {
        String bound = boundVinFor(context, appWidgetId);
        if (bound.isEmpty()) {
            return resolveStatus(context);
        }
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        VehicleStatus cached = repo != null ? repo.getStatusForVin(bound) : null;
        if (cached != null) return cached;
        if (repo != null) {
            VehicleStatus demo = repo.demoSampleForVin(bound);
            if (demo != null) return demo;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        if (!bound.equals(prefs.getString(KEY_STATUS_VIN_PREFIX + appWidgetId, null))) {
            return null;
        }
        String raw = prefs.getString(KEY_STATUS_PREFIX + appWidgetId, null);
        if (raw == null || raw.isEmpty()) return null;
        try {
            return VehicleStatus.fromJson(new JSONObject(raw));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static long lastSyncFor(Context context, @Nullable ConfigStore cfg, int appWidgetId) {
        if (boundVinFor(context, appWidgetId).isEmpty()) {
            return cfg != null ? cfg.getWidgetLastSyncMs() : 0L;
        }
        return context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
                .getLong(KEY_SYNC_PREFIX + appWidgetId, 0L);
    }

    @Nullable
    static VehicleStatus resolveStatus(Context context) {
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        VehicleStatus st = repo != null ? repo.getCachedStatus() : null;
        if (st != null) {
            return st;
        }
        // Demo builds render their synthetic sample car instead of an empty widget.
        if (repo != null) {
            VehicleStatus demo = repo.currentStatusOrDemo();
            if (demo != null) {
                return demo;
            }
        }
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        String raw = cfg != null ? cfg.getWidgetStatusJson() : null;
        if ((raw == null || raw.isEmpty()) && context != null) {
            SharedPreferences legacy = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
            String legacyJson = legacy.getString(KEY_LAST_STATUS_JSON, null);
            long legacyTs = legacy.getLong(KEY_LAST_SYNC_MS, 0L);
            if (legacyJson != null && !legacyJson.isEmpty()) {
                raw = legacyJson;
                if (cfg != null) {
                    cfg.setWidgetStatusJson(legacyJson);
                    if (legacyTs > 0L) {
                        cfg.setWidgetLastSyncMs(legacyTs);
                    }
                }
                legacy.edit().remove(KEY_LAST_STATUS_JSON).remove(KEY_LAST_SYNC_MS).apply();
            }
        }
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            VehicleStatus restored = VehicleStatus.fromJson(new JSONObject(raw));
            if (restored != null && repo != null) {
                repo.setCachedStatus(restored);
            }
            return restored;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void persistStatus(Context context, VehicleStatus status, boolean updateLastSyncMs,
                                      int appWidgetId) {
        if (context == null || status == null) return;
        String bound = boundVinFor(context, appWidgetId);
        if (!bound.isEmpty()) {
            // Only telemetry for this widget's own car may be stored; action responses for another
            // car must not overwrite it.
            if (status.vin == null || !bound.equals(status.vin)) return;
            VehicleStatus sanitized = status.toBuilder()
                    .vin("")
                    .vehicles(Collections.emptyList())
                    .build();
            SharedPreferences.Editor editor = context
                    .getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_STATUS_PREFIX + appWidgetId, sanitized.toJson().toString())
                    .putString(KEY_STATUS_VIN_PREFIX + appWidgetId, bound);
            if (updateLastSyncMs) {
                editor.putLong(KEY_SYNC_PREFIX + appWidgetId, System.currentTimeMillis());
            }
            editor.apply();
            return;
        }
        persistStatus(context, status, updateLastSyncMs);
    }

    private static void persistStatus(Context context, VehicleStatus status, boolean updateLastSyncMs) {
        if (context == null || status == null) return;
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        if (cfg != null) {
            VehicleStatus sanitized = status.toBuilder()
                    .vin("")
                    .vehicles(Collections.emptyList())
                    .build();
            cfg.setWidgetStatusJson(sanitized.toJson().toString());
            if (updateLastSyncMs) {
                cfg.setWidgetLastSyncMs(System.currentTimeMillis());
            }
        }
        SharedPreferences legacy = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        if (legacy.contains(KEY_LAST_STATUS_JSON) || legacy.contains(KEY_LAST_SYNC_MS)) {
            legacy.edit().remove(KEY_LAST_STATUS_JSON).remove(KEY_LAST_SYNC_MS).apply();
        }
    }

    private static void setTransientBanner(Context context, String text) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_BANNER_TEXT, text)
                .putLong(KEY_BANNER_EXPIRES_MS, System.currentTimeMillis() + BANNER_TTL_MS)
                .apply();
    }

    private static void clearTransientBanner(Context context) {
        if (context == null) return;
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        prefs.edit().remove(KEY_BANNER_TEXT).remove(KEY_BANNER_EXPIRES_MS).apply();
    }

    @Nullable
    static String getActiveBanner(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_WIDGET, Context.MODE_PRIVATE);
        String text = prefs.getString(KEY_BANNER_TEXT, null);
        long expires = prefs.getLong(KEY_BANNER_EXPIRES_MS, 0L);
        if (text == null || text.isEmpty() || System.currentTimeMillis() > expires) {
            return null;
        }
        return text;
    }

    static boolean isWideWidget(AppWidgetManager mgr, int appWidgetId) {
        Bundle options = mgr != null ? mgr.getAppWidgetOptions(appWidgetId) : null;
        int minWidthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
        return minWidthDp >= WIDE_WIDGET_MIN_WIDTH_DP;
    }

    /** True when the reported box is a small square-ish cell that must show the 2x2 button grid. */
    static boolean isCompactSquareBox(int minWidthDp, int minHeightDp, int maxWidthDp) {
        if (minWidthDp < WIDE_WIDGET_MIN_WIDTH_DP) {
            return true;
        }
        if (maxWidthDp > 0 && maxWidthDp < COMPACT_MAX_LANDSCAPE_WIDTH_DP) {
            return true;
        }
        return minHeightDp > 0 && minHeightDp <= COMPACT_MAX_SQUARE_HEIGHT_DP
                && minWidthDp < Math.round(minHeightDp * COMPACT_MAX_ASPECT_RATIO);
    }

    static boolean isShortWidget(AppWidgetManager mgr, int appWidgetId) {
        Bundle options = mgr != null ? mgr.getAppWidgetOptions(appWidgetId) : null;
        int minHeightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
        return minHeightDp > 0 && minHeightDp <= SHORT_WIDGET_MAX_HEIGHT_DP;
    }

    static boolean isTallWidget(AppWidgetManager mgr, int appWidgetId) {
        Bundle options = mgr != null ? mgr.getAppWidgetOptions(appWidgetId) : null;
        int minHeightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
        return minHeightDp >= TALL_WIDGET_MIN_HEIGHT_DP;
    }

    private static void updateAllWidgets(Context context, AppWidgetManager mgr, int[] ids) {
        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        String banner = getActiveBanner(context);

        boolean anyTallWide = false;
        VehicleStatus streetSource = null;
        for (int appWidgetId : ids) {
            Bundle options = mgr != null ? mgr.getAppWidgetOptions(appWidgetId) : null;
            int minWidthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0) : 0;
            int minHeightDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0) : 0;
            int maxWidthDp = options != null ? options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 0) : 0;
            boolean wide = !isCompactSquareBox(minWidthDp, minHeightDp, maxWidthDp)
                    && isWideWidget(mgr, appWidgetId);
            boolean shortWide = wide && isShortWidget(mgr, appWidgetId);
            boolean tallWide = wide && !shortWide && isTallWidget(mgr, appWidgetId);
            if (tallWide) anyTallWide = true;
            int layoutRes = shortWide
                    ? R.layout.widget_vehicle_4x1
                    : (tallWide
                            ? resolveTallLayoutRes(cfg)
                            : (wide ? resolveWide4x2LayoutRes(cfg) : R.layout.widget_vehicle_2x2));
            VehicleStatus st = resolveStatus(context, appWidgetId);
            long lastSyncMs = lastSyncFor(context, cfg, appWidgetId);
            RemoteViews views = buildWidgetRemoteViews(
                    context, cfg, layoutRes, wide, shortWide, tallWide, st, lastSyncMs, banner, appWidgetId);
            mgr.updateAppWidget(appWidgetId, views);
            if (tallWide && st != null && st.hasLocation()) {
                streetSource = st;
            }
        }
        if (anyTallWide && streetSource != null
                && VehicleLocationHelper.getCachedStreetName(streetSource.latitude, streetSource.longitude) == null) {
            VehicleLocationHelper.resolveStreetName(context, streetSource.latitude, streetSource.longitude,
                    street -> refreshWidgets(context));
        }
    }

    /** Periodic sync: refresh the active car plus every car a widget is pinned to. */
    private static void syncEveryWidgetVehicle(@Nullable BroadcastReceiver receiver, Context context) {
        executeActionAsync(receiver, context, WatchProtocol.ACTION_STATUS, false, -1);

        BydWatchApp app = resolveApp(context);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        BydRepository repo = app != null ? app.getRepository() : null;
        if (cfg == null || repo == null) return;

        String activeVin = cfg.getVin();
        AppWidgetManager mgr = AppWidgetManager.getInstance(context);
        int[] ids = mgr.getAppWidgetIds(new ComponentName(context, VehicleWidgetProvider.class));
        java.util.Set<String> extraVins = new java.util.LinkedHashSet<>();
        if (ids != null) {
            for (int id : ids) {
                String bound = boundVinFor(context, id);
                if (!bound.isEmpty() && !bound.equals(activeVin)) {
                    extraVins.add(bound);
                }
            }
        }
        for (String vin : extraVins) {
            refreshVehicleAsync(context, vin);
        }
    }

    /** Fetch one specific car (widget binding) without disturbing the app's active car. */
    public static void refreshVehicleAsync(Context context, String vin) {
        if (context == null || vin == null || vin.trim().isEmpty()) return;
        BydWatchApp app = resolveApp(context);
        BydRepository repo = app != null ? app.getRepository() : null;
        if (repo == null) return;
        try {
            JSONObject cmd = new JSONObject();
            cmd.put("id", "wdv_" + System.currentTimeMillis() + "_" + CMD_SEQ.incrementAndGet());
            cmd.put("action", WatchProtocol.ACTION_STATUS);
            cmd.put("vin", vin.trim());
            repo.handleWatchJson(cmd.toString(), resp -> refreshWidgets(context));
        } catch (Exception ignored) {
        }
    }

    public static RemoteViews buildWidgetRemoteViews(
            Context context,
            @Nullable ConfigStore cfg,
            int layoutRes,
            boolean wide,
            boolean shortWide,
            boolean tallWide,
            @Nullable VehicleStatus st,
            long lastSyncMs,
            @Nullable String banner
    ) {
        return buildWidgetRemoteViews(context, cfg, layoutRes, wide, shortWide, tallWide,
                st, lastSyncMs, banner, -1);
    }

    public static RemoteViews buildWidgetRemoteViews(
            Context context,
            @Nullable ConfigStore cfg,
            int layoutRes,
            boolean wide,
            boolean shortWide,
            boolean tallWide,
            @Nullable VehicleStatus st,
            long lastSyncMs,
            @Nullable String banner,
            int appWidgetId
    ) {
        Context resCtx = AppLocaleHelper.localizedContext(context, cfg);
        String distUnit = cfg != null ? cfg.getDistanceUnit() : UnitPreferences.DIST_KM;
        int socProgress = st != null ? Math.max(0, Math.min(100, st.soc)) : 0;
        String socText = st != null ? (st.soc + "%") : "--%";
        String rangeText = st != null
                ? UnitPreferences.formatDistance(st.rangeKm, distUnit)
                : "-- " + UnitPreferences.normalizeDistanceUnit(distUnit);

        String lastSyncText = lastSyncMs > 0L
                ? resCtx.getString(R.string.widget_last_sync_fmt,
                        new SimpleDateFormat("HH:mm", Locale.US).format(new Date(lastSyncMs)))
                : resCtx.getString(R.string.widget_last_sync_empty);

        String acStatusText = st == null
                ? resCtx.getString(R.string.widget_ac_unknown)
                : resCtx.getString(st.acOn ? R.string.btn_ac_on : R.string.btn_ac_off);

        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;

        PendingIntent openPi = PendingIntent.getActivity(context, 10, new Intent(context, MainActivity.class), flags);
        PendingIntent navPi = PendingIntent.getBroadcast(context, 15,
                new Intent(context, WidgetActionReceiver.class).setAction(LocationWidgetProvider.ACTION_LOCATION_NAVIGATE), flags);

        RemoteViews views = new RemoteViews(context.getPackageName(), layoutRes);
        views.setTextViewText(R.id.tvWidgetSoc, socText);
        views.setOnClickPendingIntent(R.id.widgetHeader, openPi);

        bindWidgetSlots(context, resCtx, views, cfg, st, flags, appWidgetId);


        if (wide) {
            views.setProgressBar(R.id.pbWidgetSoc, 100, socProgress, false);
            views.setTextViewText(R.id.tvWidgetRange, rangeText);

            String idleStatus = formatWideIdleStatus(resCtx, st);
            if (shortWide) {
                String compact4x1Banner = banner != null
                        ? banner
                        : (st != null ? idleStatus + " · " + acStatusText : idleStatus);
                views.setTextViewText(R.id.tvWidgetBanner, compact4x1Banner);
            } else {
                views.setTextViewText(R.id.tvWidgetAcStatus, acStatusText);
                views.setTextViewText(R.id.tvWidgetLastSync, lastSyncText);
                views.setOnClickPendingIntent(R.id.layoutWidgetBatteryCard, openPi);
                bindTallBatteryHeader(resCtx, views, st, cfg, distUnit);
                if (layoutRes == R.layout.widget_vehicle_4x3_ring
                        || layoutRes == R.layout.widget_vehicle_4x2_ring) {
                    views.setTextViewText(R.id.tvWidgetEstRangeLabel,
                            resCtx.getString(R.string.widget_label_estimated_range));
                } else if (layoutRes != R.layout.widget_vehicle) {
                    views.setTextViewText(R.id.tvWidgetRangeLabel, resCtx.getString(R.string.label_range));
                    if (layoutRes == R.layout.widget_vehicle_4x3_bento
                            || layoutRes == R.layout.widget_vehicle_4x2_bento
                            || layoutRes == R.layout.widget_vehicle_4x3_tank
                            || layoutRes == R.layout.widget_vehicle_4x2_tank) {
                        views.setTextViewText(R.id.tvWidgetBatteryLabel,
                                resCtx.getString(R.string.widget_label_battery));
                    }
                }

                String statePill = banner != null
                        ? banner
                        : (st == null ? resCtx.getString(R.string.widget_tap_sync)
                                : formatVehicleStateLabel(resCtx, st));
                if (statePill != null) {
                    views.setViewVisibility(R.id.tvWidgetBanner, View.VISIBLE);
                    views.setTextViewText(R.id.tvWidgetBanner, statePill);
                } else {
                    views.setViewVisibility(R.id.tvWidgetBanner, View.GONE);
                }
                if (st != null) {
                    views.setViewVisibility(R.id.tvWidgetLockStatus, View.VISIBLE);
                    views.setTextViewText(R.id.tvWidgetLockStatus,
                            resCtx.getString(st.locked ? R.string.state_locked : R.string.state_unlocked));
                    views.setViewVisibility(R.id.tvWidgetAcStatus, View.VISIBLE);
                } else {
                    views.setViewVisibility(R.id.tvWidgetLockStatus, View.GONE);
                    views.setViewVisibility(R.id.tvWidgetAcStatus, View.GONE);
                }

                if (tallWide) {
                    views.setViewVisibility(R.id.layoutWidgetLocationBar, View.VISIBLE);
                    views.setOnClickPendingIntent(R.id.layoutWidgetLocationBar, navPi);
                    String locText;
                    if (st != null && st.hasLocation()) {
                        String cachedStreet = VehicleLocationHelper.getCachedStreetName(st.latitude, st.longitude);
                        locText = cachedStreet != null
                                ? cachedStreet
                                : VehicleLocationHelper.formatCoordinates(st.latitude, st.longitude);
                    } else {
                        locText = resCtx.getString(R.string.location_widget_empty);
                    }
                    views.setTextViewText(R.id.tvWidgetLocationText, locText);
                } else {
                    views.setViewVisibility(R.id.layoutWidgetLocationBar, View.GONE);
                }
            }
        } else {
            String compactSubtitle = rangeText;
            if (st != null && st.isCharging()) {
                String power = st.hasChargePowerKw()
                        ? String.format(Locale.US, "%.1f kW", st.chargePowerKw)
                        : "";
                String rem = formatWidgetChargeEta(resCtx, st.chargeRemainingMinutes);
                if (!power.isEmpty() && !rem.isEmpty()) {
                    compactSubtitle = rangeText + "\n⚡ " + power + " · " + rem;
                } else if (!power.isEmpty()) {
                    compactSubtitle = rangeText + "\n⚡ " + power;
                } else if (!rem.isEmpty()) {
                    compactSubtitle = rangeText + "\n⚡ " + rem;
                } else {
                    compactSubtitle = rangeText + " · ⚡";
                }
            } else if (st != null && st.chargingState == VehicleStatus.CHARGING_CONNECTED) {
                compactSubtitle = rangeText + " · " + resCtx.getString(R.string.widget_plugged_short);
            } else if (st != null && st.vehicleOn) {
                compactSubtitle = rangeText + "\n" + resCtx.getString(R.string.state_driving_on);
            }
            views.setTextViewText(R.id.tvWidgetRange, banner != null ? banner : compactSubtitle);
        }

        // Multi-car: the car name becomes an outlined pill that opens the picker.
        if (canBindWidgetToVehicle(cfg)) {
            String carLabel = widgetCarLabel(context, cfg, appWidgetId, st);
            if (carLabel != null && !carLabel.isEmpty()) {
                int dp4 = Math.round(6 * resCtx.getResources().getDisplayMetrics().density);
                int dp2 = Math.round(2 * resCtx.getResources().getDisplayMetrics().density);
                views.setTextViewText(R.id.tvWidgetCarModel, widgetIconPrefix(cfg, context, appWidgetId, st) + carLabel + "  \u25BE");
                views.setInt(R.id.tvWidgetCarModel, "setBackgroundResource", R.drawable.bg_widget_car_pill);
                views.setTextColor(R.id.tvWidgetCarModel,
                        ContextCompat.getColor(resCtx, R.color.accent_emerald));
                views.setViewPadding(R.id.tvWidgetCarModel, dp4, dp2, dp4, dp2);
                views.setViewVisibility(R.id.tvWidgetCarModel, View.VISIBLE);
                Intent pickerIntent = WidgetVehiclePickerActivity.createIntent(context, appWidgetId);
                PendingIntent pickerPi = PendingIntent.getActivity(
                        context, 7000 + Math.max(0, appWidgetId), pickerIntent, flags);
                views.setOnClickPendingIntent(R.id.tvWidgetCarModel, pickerPi);
            }
        }

        return views;
    }

    /** Short model label for the widget header; null for single-car accounts. */
    @Nullable
    private static String widgetCarLabel(Context context, @Nullable ConfigStore cfg, int appWidgetId,
                                         @Nullable VehicleStatus st) {
        if (!canBindWidgetToVehicle(cfg)) return null;
        String bound = boundVinFor(context, appWidgetId);
        String vin = !bound.isEmpty()
                ? bound
                : (st != null && st.vin != null && !st.vin.isEmpty() ? st.vin : cfg.getVin());
        String custom = cfg.resolveVehicleCustomName(vin);
        if (custom != null && !custom.trim().isEmpty()) return custom.trim();
        for (VehicleSummary car : cfg.getVehicles()) {
            if (car != null && vin != null && vin.equals(car.vin)) {
                return car.watchShortLabel();
            }
        }
        return null;
    }


    /** Emoji for the car a widget shows; photos stay app-only. */
    private static String widgetIconPrefix(@Nullable ConfigStore cfg, Context context,
                                           int appWidgetId, @Nullable VehicleStatus st) {
        if (cfg == null) return "";
        String vin = !boundVinFor(context, appWidgetId).isEmpty()
                ? boundVinFor(context, appWidgetId)
                : (st != null && st.vin != null && !st.vin.isEmpty() ? st.vin : cfg.getVin());
        return VehicleIconStore.emojiOrDefault(cfg.getVehicleIconEmoji(vin)) + " ";
    }

    private static void bindWidgetSlots(
            Context context,
            Context resCtx,
            RemoteViews views,
            @Nullable ConfigStore cfg,
            @Nullable VehicleStatus st,
            int piFlags
    ) {
        bindWidgetSlots(context, resCtx, views, cfg, st, piFlags, -1);
    }

    private static void bindWidgetSlots(
            Context context,
            Context resCtx,
            RemoteViews views,
            @Nullable ConfigStore cfg,
            @Nullable VehicleStatus st,
            int piFlags,
            int appWidgetId
    ) {
        List<WidgetSlotConfig> slots = cfg != null ? cfg.getWidgetSlots() : WidgetSlotConfig.defaults();
        List<CustomActionCombo> combos = cfg != null ? cfg.getActionCombos() : Collections.emptyList();
        for (int i = 0; i < WidgetSlotConfig.SLOT_COUNT; i++) {
            WidgetSlotConfig slot = i < slots.size() ? slots.get(i) : WidgetSlotConfig.defaults().get(i);
            SlotVisual visual = resolveSlotVisual(resCtx, slot, st, combos);
            int fgColor = ContextCompat.getColor(resCtx, visual.textColorRes);

            views.setTextViewText(SLOT_BTN_IDS[i], visual.label);
            views.setTextColor(SLOT_BTN_IDS[i], fgColor);
            views.setImageViewResource(SLOT_ICON_IDS[i], visual.iconRes);
            views.setInt(SLOT_ICON_IDS[i], "setColorFilter", fgColor);
            views.setInt(SLOT_TILE_IDS[i], "setBackgroundResource", visual.bgRes);

            boolean isSyncSlot = !slot.isCombo()
                    && WidgetSlotConfig.SINGLE_STATUS.equals(slot.targetId);
            boolean requireWidgetAuth = BiometricLockHelper.isWidgetProtectionEnabled(cfg)
                    && (!isSyncSlot || (cfg != null && cfg.isBiometricWidgetSyncRequired()));
            String actionWithId = withWidgetId(slotAction(i), appWidgetId);
            int idOffset = appWidgetId < 0 ? 0 : appWidgetId * 10;
            PendingIntent slotPi = requireWidgetAuth
                    ? PendingIntent.getActivity(
                            context,
                            120 + i + idOffset,
                            WidgetAuthActivity.createIntent(context, actionWithId),
                            piFlags
                    )
                    : PendingIntent.getBroadcast(
                            context,
                            20 + i + idOffset,
                            new Intent(context, WidgetActionReceiver.class).setAction(actionWithId),
                            piFlags
                    );
            views.setOnClickPendingIntent(SLOT_BTN_IDS[i], slotPi);
            views.setOnClickPendingIntent(SLOT_TILE_IDS[i], slotPi);
        }
    }

    public static SlotVisual resolveSlotVisual(
            Context resCtx,
            @Nullable WidgetSlotConfig slot,
            @Nullable VehicleStatus st,
            @Nullable List<CustomActionCombo> combos
    ) {
        if (slot == null) {
            slot = WidgetSlotConfig.single(WidgetSlotConfig.SINGLE_STATUS);
        }
        String label;
        int iconRes;
        int bgRes;
        int textColorRes;

        if (slot.isCombo()) {
            CustomActionCombo matched = null;
            if (combos != null) {
                for (CustomActionCombo c : combos) {
                    if (slot.targetId.equals(c.id)) {
                        matched = c;
                        break;
                    }
                }
            }
            if (matched != null) {
                label = !slot.customLabel.isEmpty() ? slot.customLabel : matched.name;
                iconRes = iconResForKey(matched.iconKey);
                bgRes = bgResForTone(matched.tone);
                textColorRes = textColorResForTone(matched.tone);
            } else {
                label = !slot.customLabel.isEmpty() ? slot.customLabel : resCtx.getString(R.string.combo_default_name);
                iconRes = R.drawable.ic_slot_sparkle;
                bgRes = R.drawable.bg_widget_btn_tonal;
                textColorRes = R.color.widget_on_surface;
            }
            return new SlotVisual(label, iconRes, bgRes, textColorRes);
        }

        switch (slot.targetId) {
            case WidgetSlotConfig.SINGLE_LOCK:
                label = resCtx.getString(R.string.btn_lock);
                iconRes = R.drawable.ic_slot_lock;
                bgRes = R.drawable.bg_widget_btn_primary;
                textColorRes = R.color.widget_on_primary_container;
                break;
            case WidgetSlotConfig.SINGLE_UNLOCK:
                label = resCtx.getString(R.string.btn_unlock);
                iconRes = R.drawable.ic_slot_unlock;
                bgRes = R.drawable.bg_widget_btn_tertiary;
                textColorRes = R.color.widget_on_tertiary_container;
                break;
            case WidgetSlotConfig.SINGLE_AC_TOGGLE:
                label = resCtx.getString((st != null && st.acOn) ? R.string.btn_ac_off : R.string.btn_ac_on);
                iconRes = R.drawable.ic_slot_ac;
                bgRes = R.drawable.bg_widget_btn_secondary;
                textColorRes = R.color.widget_on_secondary_container;
                break;
            case WidgetSlotConfig.SINGLE_LIGHTS:
                label = resCtx.getString(R.string.slot_label_lights);
                iconRes = R.drawable.ic_slot_lights;
                bgRes = R.drawable.bg_widget_btn_tertiary;
                textColorRes = R.color.widget_on_tertiary_container;
                break;
            case WidgetSlotConfig.SINGLE_HORN:
                label = resCtx.getString(R.string.btn_horn);
                iconRes = R.drawable.ic_slot_horn;
                bgRes = R.drawable.bg_widget_btn_rose;
                textColorRes = R.color.widget_on_rose_container;
                break;
            case WidgetSlotConfig.SINGLE_VENT_WINDOWS:
                label = resCtx.getString(R.string.btn_vent_windows);
                iconRes = R.drawable.ic_slot_vent_win;
                bgRes = R.drawable.bg_widget_btn_secondary;
                textColorRes = R.color.widget_on_secondary_container;
                break;
            case WidgetSlotConfig.SINGLE_CLOSE_WINDOWS:
                label = resCtx.getString(R.string.btn_close_windows);
                iconRes = R.drawable.ic_slot_close_win;
                bgRes = R.drawable.bg_widget_btn_secondary;
                textColorRes = R.color.widget_on_secondary_container;
                break;
            case WidgetSlotConfig.SINGLE_BAT_HEAT:
                boolean batOn = st != null && Boolean.TRUE.equals(st.batteryHeatOn);
                label = resCtx.getString(batOn ? R.string.slot_label_bat_heat_off : R.string.btn_bat_heat);
                iconRes = R.drawable.ic_slot_bat_heat;
                bgRes = R.drawable.bg_widget_btn_rose;
                textColorRes = R.color.widget_on_rose_container;
                break;
            case WidgetSlotConfig.SINGLE_SEAT_HEAT:
                label = (st != null && st.driverSeatLevel > 0)
                        ? resCtx.getString(R.string.btn_seat_heat_level, st.driverSeatLevel)
                        : resCtx.getString(R.string.btn_seat_heat);
                iconRes = R.drawable.ic_slot_seat_heat;
                bgRes = R.drawable.bg_widget_btn_tertiary;
                textColorRes = R.color.widget_on_tertiary_container;
                break;
            case WidgetSlotConfig.SINGLE_LOCATION:
                label = resCtx.getString(R.string.slot_label_location);
                iconRes = R.drawable.ic_slot_location;
                bgRes = R.drawable.bg_widget_btn_primary;
                textColorRes = R.color.widget_on_primary_container;
                break;
            case WidgetSlotConfig.SINGLE_STATUS:
            default:
                label = resCtx.getString(R.string.btn_sync);
                iconRes = R.drawable.ic_slot_sync;
                bgRes = R.drawable.bg_widget_btn_tonal;
                textColorRes = R.color.widget_on_surface;
                break;
        }
        if (!slot.customLabel.isEmpty()) {
            label = slot.customLabel;
        }
        return new SlotVisual(label, iconRes, bgRes, textColorRes);
    }

    public static int iconResForKey(@Nullable String iconKey) {
        if (iconKey == null) return R.drawable.ic_slot_bolt;
        switch (CustomActionCombo.normalizeIconKey(iconKey)) {
            case CustomActionCombo.ICON_FROST:
                return R.drawable.ic_slot_ac;
            case CustomActionCombo.ICON_SUN:
                return R.drawable.ic_slot_sun;
            case CustomActionCombo.ICON_SHIELD:
                return R.drawable.ic_slot_shield;
            case CustomActionCombo.ICON_UNLOCK:
                return R.drawable.ic_slot_unlock;
            case CustomActionCombo.ICON_BREEZE:
                return R.drawable.ic_slot_breeze;
            case CustomActionCombo.ICON_SPARKLE:
                return R.drawable.ic_slot_sparkle;
            case CustomActionCombo.ICON_CAR:
                return R.drawable.ic_slot_car;
            case CustomActionCombo.ICON_BOLT:
            default:
                return R.drawable.ic_slot_bolt;
        }
    }

    public static int bgResForTone(@Nullable String tone) {
        if (tone == null) return R.drawable.bg_widget_btn_tertiary;
        switch (CustomActionCombo.normalizeTone(tone)) {
            case CustomActionCombo.TONE_EMERALD:
                return R.drawable.bg_widget_btn_primary;
            case CustomActionCombo.TONE_CYAN:
                return R.drawable.bg_widget_btn_secondary;
            case CustomActionCombo.TONE_ROSE:
                return R.drawable.bg_widget_btn_rose;
            case CustomActionCombo.TONE_AMBER:
            default:
                return R.drawable.bg_widget_btn_tertiary;
        }
    }

    public static int textColorResForTone(@Nullable String tone) {
        if (tone == null) return R.color.widget_on_tertiary_container;
        switch (CustomActionCombo.normalizeTone(tone)) {
            case CustomActionCombo.TONE_EMERALD:
                return R.color.widget_on_primary_container;
            case CustomActionCombo.TONE_CYAN:
                return R.color.widget_on_secondary_container;
            case CustomActionCombo.TONE_ROSE:
                return R.color.widget_on_rose_container;
            case CustomActionCombo.TONE_AMBER:
            default:
                return R.color.widget_on_tertiary_container;
        }
    }

    @Nullable
    private static String formatVehicleStateLabel(Context resCtx, @Nullable VehicleStatus st) {
        if (st == null) return null;
        if (st.isCharging()) {
            String power = st.hasChargePowerKw()
                    ? String.format(Locale.US, "%.1f kW", st.chargePowerKw)
                    : "";
            String rem = formatWidgetChargeEta(resCtx, st.chargeRemainingMinutes);
            if (!power.isEmpty() && !rem.isEmpty()) {
                return "⚡ " + power + " · " + rem;
            } else if (!power.isEmpty()) {
                return resCtx.getString(R.string.state_charging) + " · " + power;
            } else if (!rem.isEmpty()) {
                return resCtx.getString(R.string.state_charging) + " · " + rem;
            }
            return resCtx.getString(R.string.state_charging);
        }
        if (st.chargingState == VehicleStatus.CHARGING_CONNECTED) {
            return resCtx.getString(R.string.state_plugged_in);
        }
        if (st.vehicleOn) {
            return resCtx.getString(R.string.state_driving_on);
        }
        return null;
    }

    private static String formatWideIdleStatus(Context resCtx, @Nullable VehicleStatus st) {
        if (st == null) {
            return resCtx.getString(R.string.widget_tap_sync);
        }
        String lockText = resCtx.getString(st.locked ? R.string.state_locked : R.string.state_unlocked);
        String state = formatVehicleStateLabel(resCtx, st);
        return state != null ? (state + " · " + lockText) : lockText;
    }

    static int resolveTallLayoutRes(@Nullable ConfigStore cfg) {
        int style = cfg != null ? cfg.getWidgetStyle() : ConfigStore.DEFAULT_WIDGET_STYLE;
        switch (ConfigStore.normalizeWidgetStyle(style)) {
            case ConfigStore.WIDGET_STYLE_RING:
                return R.layout.widget_vehicle_4x3_ring;
            case ConfigStore.WIDGET_STYLE_BENTO:
                return R.layout.widget_vehicle_4x3_bento;
            case ConfigStore.WIDGET_STYLE_TANK:
                return R.layout.widget_vehicle_4x3_tank;
            case ConfigStore.WIDGET_STYLE_CAPSULE:
            default:
                return R.layout.widget_vehicle_4x3;
        }
    }

    static int resolveWide4x2LayoutRes(@Nullable ConfigStore cfg) {
        int style = cfg != null ? cfg.getWidgetStyle() : ConfigStore.DEFAULT_WIDGET_STYLE;
        switch (ConfigStore.normalizeWidgetStyle(style)) {
            case ConfigStore.WIDGET_STYLE_RING:
                return R.layout.widget_vehicle_4x2_ring;
            case ConfigStore.WIDGET_STYLE_BENTO:
                return R.layout.widget_vehicle_4x2_bento;
            case ConfigStore.WIDGET_STYLE_TANK:
                return R.layout.widget_vehicle_4x2_tank;
            case ConfigStore.WIDGET_STYLE_CAPSULE:
            default:
                return R.layout.widget_vehicle;
        }
    }

    @Nullable
    static String resolvePreferredModelName(@Nullable ConfigStore cfg) {
        if (cfg == null) return null;
        String custom = cfg.resolveVehicleCustomName(cfg.getVin());
        return (custom != null && !custom.trim().isEmpty()) ? custom.trim() : null;
    }

    private static void bindTallBatteryHeader(
            Context resCtx,
            RemoteViews views,
            @Nullable VehicleStatus st,
            @Nullable ConfigStore cfg,
            String distUnit
    ) {
        String model = resolvePreferredModelName(cfg);
        if (model != null) {
            views.setViewVisibility(R.id.tvWidgetCarModel, View.VISIBLE);
            views.setTextViewText(R.id.tvWidgetCarModel, model);
        } else {
            views.setViewVisibility(R.id.tvWidgetCarModel, View.GONE);
        }

        if (st == null) {
            views.setViewVisibility(R.id.tvWidgetBatteryMeta, View.GONE);
            return;
        }

        String odoPart = null;
        if (st.totalMileageKm != null && st.totalMileageKm > 0) {
            int converted = UnitPreferences.convertDistance(st.totalMileageKm, distUnit);
            odoPart = NumberFormat.getIntegerInstance(Locale.US).format(converted)
                    + " " + UnitPreferences.normalizeDistanceUnit(distUnit);
        }
        int openings = st.doorsOpenCount + st.windowsOpenCount + (st.trunkOpen ? 1 : 0);
        String closurePart = openings > 0
                ? (st.doorsOpenCount + "D " + st.windowsOpenCount + "W" + (st.trunkOpen ? " + Trunk" : "") + " Open")
                : resCtx.getString(R.string.state_closed);

        views.setViewVisibility(R.id.tvWidgetBatteryMeta, View.VISIBLE);
        views.setTextViewText(R.id.tvWidgetBatteryMeta,
                odoPart != null ? (odoPart + " · " + closurePart) : closurePart);
    }

    private static String formatWidgetChargeEta(Context resCtx, Integer totalMinutes) {
        if (totalMinutes == null || totalMinutes <= 0) return "";
        int hrs = totalMinutes / 60;
        int mins = totalMinutes % 60;
        return resCtx.getString(R.string.charge_eta_fmt, hrs, mins);
    }
}
