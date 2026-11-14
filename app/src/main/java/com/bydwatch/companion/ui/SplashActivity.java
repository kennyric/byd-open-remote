package com.bydwatch.companion.ui;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.R;

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
        setContentView(R.layout.activity_splash);

        TextView tvVersion = findViewById(R.id.tvSplashVersion);
        if (tvVersion != null) {
            tvVersion.setText("v" + BuildConfig.VERSION_NAME);
        }

        Button btnContinue = findViewById(R.id.btnSplashContinue);
        if (btnContinue != null) {
            btnContinue.setOnClickListener(v -> navigateToMain());
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
        startActivity(new Intent(this, MainActivity.class));
        finish();
    }
}
