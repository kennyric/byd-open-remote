package com.bydwatch.companion.ui;

import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.R;
import com.bydwatch.companion.model.VehicleStatus;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

/**
 * Lightweight dialog Activity launched when tapping the home-screen Location Widget
 * to ask the user before redirecting into navigation apps.
 */
public class NavigationPromptActivity extends AppCompatActivity {

    public static final String EXTRA_LAT = "extra_lat";
    public static final String EXTRA_LNG = "extra_lng";
    public static final String EXTRA_LABEL = "extra_label";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        double lat = intent != null ? intent.getDoubleExtra(EXTRA_LAT, Double.NaN) : Double.NaN;
        double lng = intent != null ? intent.getDoubleExtra(EXTRA_LNG, Double.NaN) : Double.NaN;
        String label = intent != null ? intent.getStringExtra(EXTRA_LABEL) : null;

        if (!VehicleStatus.isValidCoordinates(lat, lng)) {
            VehicleStatus cached = VehicleWidgetProvider.resolveStatus(this);
            if (cached != null && cached.hasLocation()) {
                lat = cached.latitude;
                lng = cached.longitude;
                if (label == null || label.trim().isEmpty()) {
                    label = cached.modelName;
                }
            }
        }

        if (VehicleStatus.isValidCoordinates(lat, lng)) {
            VehicleLocationHelper.showNavigatePromptDialog(this, lat, lng, label, this::finish);
        } else {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.nav_missing_title)
                    .setMessage(R.string.nav_missing_message)
                    .setPositiveButton(R.string.btn_open_app, (d, w) -> {
                        startActivity(new Intent(this, MainActivity.class));
                        finish();
                    })
                    .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                    .setOnCancelListener(d -> finish())
                    .show();
        }
    }
}
