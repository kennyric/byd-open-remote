package com.bydwatch.companion.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;

/**
 * Front splash screen highlighting maker attribution and non-commercial free use notice.
 */
public class SplashActivity extends AppCompatActivity {

    static final long SPLASH_DELAY_MS = 2400L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean navigated = false;
    private final Runnable proceedRunnable = this::navigateToMain;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        BydWatchApp app = getApplication() instanceof BydWatchApp ? (BydWatchApp) getApplication() : null;
        if (app != null) {
            try {
                ConfigStore cfg = app.getConfigStore();
                if (BuildConfig.DEBUG && getIntent() != null && getIntent().hasExtra("demo_lang")) {
                    cfg.setAppLanguage(getIntent().getStringExtra("demo_lang"));
                }
                AppLocaleHelper.applyLanguage(cfg.getAppLanguage());
            } catch (Exception ignored) {
                // Skip eager KeyStore reads on test environments that do not mock ConfigStore.
            }
        }
        setContentView(R.layout.activity_splash);

        TextView tvVersion = findViewById(R.id.tvSplashVersion);
        if (tvVersion != null) {
            tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        }

        Button btnContinue = findViewById(R.id.btnSplashContinue);
        if (btnContinue != null) {
            btnContinue.setOnClickListener(v -> navigateToMain());
        }

        if (BuildConfig.DEBUG && getIntent() != null) {
            if (getIntent().getBooleanExtra("demo_splash", false)) {
                handler.removeCallbacks(proceedRunnable);
                return;
            }
            if (getIntent().getBooleanExtra("demo_mode", false)) {
                navigateToMain();
                return;
            }
        }

        handler.postDelayed(proceedRunnable, SPLASH_DELAY_MS);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacks(proceedRunnable);
        super.onDestroy();
    }

    private void navigateToMain() {
        if (navigated || isFinishing()) {
            return;
        }
        navigated = true;
        handler.removeCallbacks(proceedRunnable);
        Intent mainIntent = new Intent(this, MainActivity.class);
        if (BuildConfig.DEBUG && getIntent() != null && getIntent().getBooleanExtra("demo_mode", false)) {
            mainIntent.putExtras(getIntent());
        }
        startActivity(mainIntent);
        finish();
    }
}
