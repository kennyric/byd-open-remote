package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.WidgetSlotConfig;

import java.util.List;

/**
 * Translucent trampoline activity that prompts Fingerprint / Face / Device PIN
 * over the home screen when a protected widget action is tapped, without opening
 * {@link MainActivity}.
 */
public class WidgetAuthActivity extends AppCompatActivity {

    public static final String EXTRA_WIDGET_ACTION = "extra_widget_action";

    public static Intent createIntent(@NonNull Context context, @NonNull String widgetAction) {
        return new Intent(context, WidgetAuthActivity.class)
                .putExtra(EXTRA_WIDGET_ACTION, widgetAction)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        String action = getIntent() != null ? getIntent().getStringExtra(EXTRA_WIDGET_ACTION) : null;
        if (action == null || action.trim().isEmpty()) {
            finishWithoutAnimation();
            return;
        }

        BydWatchApp app = VehicleWidgetProvider.resolveApp(this);
        ConfigStore cfg = app != null ? app.getConfigStore() : null;
        Context resCtx = AppLocaleHelper.localizedContext(this, cfg);
        boolean isSync = isSyncWidgetAction(cfg, action);

        if (!BiometricLockHelper.isAuthRequiredForWidget(this, cfg, isSync)) {
            dispatchVerifiedAction(getApplicationContext(), action);
            finishWithoutAnimation();
            return;
        }

        BiometricLockHelper.authenticate(
                this,
                resCtx.getString(R.string.biometric_widget_prompt_title),
                resCtx.getString(R.string.biometric_widget_prompt_subtitle),
                new BiometricLockHelper.AuthCallback() {
                    @Override
                    public void onSucceeded() {
                        BiometricLockHelper.recordAuthSuccess(getApplicationContext(), cfg);
                        dispatchVerifiedAction(getApplicationContext(), action);
                        finishWithoutAnimation();
                    }

                    @Override
                    public void onFailedOrCancelled(@NonNull String message) {
                        Toast.makeText(
                                getApplicationContext(),
                                resCtx.getString(R.string.biometric_auth_cancelled),
                                Toast.LENGTH_SHORT
                        ).show();
                        finishWithoutAnimation();
                    }
                }
        );
    }

    private void finishWithoutAnimation() {
        finish();
        overridePendingTransition(0, 0);
    }

    static boolean isSyncWidgetAction(@Nullable ConfigStore cfg, @Nullable String action) {
        if (action == null) return false;
        if (LocationWidgetProvider.ACTION_LOCATION_REFRESH.equals(action)
                || VehicleWidgetProvider.ACTION_WIDGET_REFRESH.equals(action)) {
            return true;
        }
        int slotIdx = VehicleWidgetProvider.parseSlotIndex(action);
        if (slotIdx >= 0) {
            List<WidgetSlotConfig> slots = cfg != null ? cfg.getWidgetSlots() : WidgetSlotConfig.defaults();
            WidgetSlotConfig slot = slotIdx < slots.size()
                    ? slots.get(slotIdx)
                    : WidgetSlotConfig.defaults().get(slotIdx);
            return slot != null && !slot.isCombo()
                    && WidgetSlotConfig.SINGLE_STATUS.equals(slot.targetId);
        }
        return false;
    }

    static void dispatchVerifiedAction(@NonNull Context appContext, @NonNull String action) {
        if (LocationWidgetProvider.handlesAction(action)) {
            LocationWidgetProvider.handleInternalAction(null, appContext, action);
        } else if (VehicleWidgetProvider.handlesAction(action)) {
            VehicleWidgetProvider.handleInternalAction(null, appContext, action);
        }
    }
}
