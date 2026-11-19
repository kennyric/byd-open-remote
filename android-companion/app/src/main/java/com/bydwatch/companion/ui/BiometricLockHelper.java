package com.bydwatch.companion.ui;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.FragmentActivity;

import com.bydwatch.companion.data.ConfigStore;

/**
 * Coordinates biometric / device PIN protection and persisted grace-window checks
 * for both {@link MainActivity} and home-screen widget actions.
 */
public final class BiometricLockHelper {

    public interface AuthCallback {
        void onSucceeded();
        void onFailedOrCancelled(@NonNull String message);
    }

    @VisibleForTesting
    public interface TestAuthenticator {
        void authenticate(
                @NonNull FragmentActivity activity,
                @NonNull String title,
                @NonNull String subtitle,
                @NonNull AuthCallback callback
        );
    }

    @Nullable
    private static volatile Boolean testDeviceCredentialAvailable = null;
    @Nullable
    private static volatile TestAuthenticator testAuthenticator = null;

    private BiometricLockHelper() {}

    @VisibleForTesting
    public static void setTestHooks(
            @Nullable Boolean deviceCredentialAvailable,
            @Nullable TestAuthenticator authenticator
    ) {
        testDeviceCredentialAvailable = deviceCredentialAvailable;
        testAuthenticator = authenticator;
    }

    @VisibleForTesting
    public static void resetTestHooks() {
        testDeviceCredentialAvailable = null;
        testAuthenticator = null;
    }

    public static boolean isAppProtectionEnabled(@Nullable ConfigStore cfg) {
        if (cfg == null) return false;
        String scope = cfg.getBiometricScope();
        return ConfigStore.BIOMETRIC_SCOPE_APP.equals(scope)
                || ConfigStore.BIOMETRIC_SCOPE_BOTH.equals(scope);
    }

    public static boolean isWidgetProtectionEnabled(@Nullable ConfigStore cfg) {
        if (cfg == null) return false;
        String scope = cfg.getBiometricScope();
        return ConfigStore.BIOMETRIC_SCOPE_WIDGET.equals(scope)
                || ConfigStore.BIOMETRIC_SCOPE_BOTH.equals(scope);
    }

    public static boolean isDeviceCredentialAvailable(@Nullable Context context) {
        Boolean override = testDeviceCredentialAvailable;
        if (override != null) {
            return override;
        }
        if (context == null) {
            return false;
        }
        BiometricManager bm = BiometricManager.from(context);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            int authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG
                    | BiometricManager.Authenticators.BIOMETRIC_WEAK
                    | BiometricManager.Authenticators.DEVICE_CREDENTIAL;
            return bm.canAuthenticate(authenticators) == BiometricManager.BIOMETRIC_SUCCESS;
        }
        KeyguardManager km = (KeyguardManager) context.getSystemService(Context.KEYGUARD_SERVICE);
        boolean deviceSecure = km != null && km.isDeviceSecure();
        boolean bioReady = bm.canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_WEAK)
                == BiometricManager.BIOMETRIC_SUCCESS;
        return deviceSecure || bioReady;
    }

    public static boolean isWithinGracePeriod(@Nullable Context context, @Nullable ConfigStore cfg) {
        if (cfg == null) return false;
        int graceMinutes = cfg.getBiometricGraceMinutes();
        if (graceMinutes <= 0) {
            return false;
        }
        long savedElapsed = cfg.getLastBiometricAuthElapsedMs();
        if (savedElapsed < 0L) {
            return false;
        }
        int savedBoot = cfg.getLastBiometricAuthBootCount();
        int currentBoot = readBootCount(context);
        if (savedBoot >= 0 && currentBoot >= 0 && savedBoot != currentBoot) {
            return false;
        }
        long nowElapsed = SystemClock.elapsedRealtime();
        if (nowElapsed < savedElapsed) {
            return false;
        }
        long graceMs = graceMinutes * 60_000L;
        return (nowElapsed - savedElapsed) < graceMs;
    }

    public static boolean isAuthRequiredForApp(
            @Nullable Context context,
            @Nullable ConfigStore cfg,
            boolean unlockedInCurrentForeground
    ) {
        if (!isAppProtectionEnabled(cfg)) {
            return false;
        }
        if (cfg.getBiometricGraceMinutes() <= 0) {
            return !unlockedInCurrentForeground;
        }
        return !isWithinGracePeriod(context, cfg);
    }

    public static boolean isAuthRequiredForWidget(
            @Nullable Context context,
            @Nullable ConfigStore cfg,
            boolean isSyncAction
    ) {
        if (!isWidgetProtectionEnabled(cfg)) {
            return false;
        }
        if (isSyncAction && !cfg.isBiometricWidgetSyncRequired()) {
            return false;
        }
        return !isWithinGracePeriod(context, cfg);
    }

    public static void recordAuthSuccess(@Nullable Context context, @Nullable ConfigStore cfg) {
        if (cfg == null) return;
        cfg.setLastBiometricAuthElapsedMs(SystemClock.elapsedRealtime());
        cfg.setLastBiometricAuthBootCount(readBootCount(context));
    }

    @SuppressWarnings("deprecation")
    public static void authenticate(
            @NonNull FragmentActivity activity,
            @NonNull String title,
            @NonNull String subtitle,
            @NonNull AuthCallback callback
    ) {
        TestAuthenticator override = testAuthenticator;
        if (override != null) {
            override.authenticate(activity, title, subtitle, callback);
            return;
        }

        BiometricPrompt prompt = new BiometricPrompt(
                activity,
                ContextCompat.getMainExecutor(activity),
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        callback.onSucceeded();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        callback.onFailedOrCancelled(errString.toString());
                    }
                }
        );

        BiometricPrompt.PromptInfo.Builder builder = new BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            builder.setAllowedAuthenticators(
                    BiometricManager.Authenticators.BIOMETRIC_STRONG
                            | BiometricManager.Authenticators.BIOMETRIC_WEAK
                            | BiometricManager.Authenticators.DEVICE_CREDENTIAL
            );
        } else {
            builder.setDeviceCredentialAllowed(true);
        }
        prompt.authenticate(builder.build());
    }

    private static int readBootCount(@Nullable Context context) {
        if (context == null) return -1;
        try {
            return Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, -1);
        } catch (Throwable ignored) {
            return -1;
        }
    }
}
