package com.bydwatch.companion.ui;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.bydwatch.companion.BydWatchApp;
import com.bydwatch.companion.R;
import com.bydwatch.companion.data.BydRepository;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.VehicleSummary;

import java.util.List;

/**
 * Opened from a widget's settings gear (multi-car accounts only). Picks the car that widget is
 * pinned to; actions and telemetry from then on use that VIN without touching the active car.
 */
public class WidgetVehiclePickerActivity extends AppCompatActivity {

    static final String EXTRA_APPWIDGET_ID = "extra_appwidget_id";

    public static Intent createIntent(Context context, int appWidgetId) {
        return new Intent(context, WidgetVehiclePickerActivity.class)
                .putExtra(EXTRA_APPWIDGET_ID, appWidgetId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_CLEAR_TOP
                        | Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    private int appWidgetId = -1;
    private ConfigStore configStore;
    private BydRepository repository;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_widget_vehicle_picker);

        appWidgetId = getIntent() != null
                ? getIntent().getIntExtra(EXTRA_APPWIDGET_ID, -1)
                : -1;

        BydWatchApp app = BydWatchApp.getInstance();
        configStore = app != null ? app.getConfigStore() : null;
        repository = app != null ? app.getRepository() : null;

        Button cancel = findViewById(R.id.btnWidgetVehicleCancel);
        if (cancel != null) {
            cancel.setOnClickListener(v -> finish());
        }

        renderChoices();
    }

    private void renderChoices() {
        LinearLayout container = findViewById(R.id.layoutWidgetVehicleChoices);
        if (container == null || configStore == null) return;
        container.removeAllViews();

        String activeVin = configStore.getVin();
        String bound = appWidgetId >= 0 ? configStore.getWidgetVehicle(appWidgetId) : "";
        if (bound == null) bound = "";

        container.addView(buildRow(container, "", getString(R.string.widget_vehicle_follow_active),
                getString(R.string.widget_vehicle_follow_active_sub), bound));

        List<VehicleSummary> cars = configStore.getVehicles();
        if (cars != null) {
            for (VehicleSummary car : cars) {
                if (car == null || car.vin == null || car.vin.trim().isEmpty()) continue;
                String meta = maskVin(car.vin) + " · " + car.watchShortLabel();
                VehicleStatus st = repository != null ? repository.getStatusForVin(car.vin) : null;
                if (st != null) {
                    meta = maskVin(car.vin) + " · " + st.soc + "% · " + st.rangeKm + " km";
                } else if (car.vin.equals(activeVin)) {
                    VehicleStatus live = repository != null ? repository.getCachedStatus() : null;
                    if (live != null) {
                        meta = maskVin(car.vin) + " · " + live.soc + "% · " + live.rangeKm + " km";
                    }
                }
                container.addView(buildRow(container, car.vin, car.watchShortLabel(), meta, bound));
            }
        }
    }

    private View buildRow(LinearLayout parent, String vin, String title, String meta, String boundVin) {
        View row = LayoutInflater.from(this).inflate(R.layout.item_widget_vehicle_choice, parent, false);
        TextView name = row.findViewById(R.id.tvWidgetVehicleName);
        TextView metaView = row.findViewById(R.id.tvWidgetVehicleMeta);
        RadioButton radio = row.findViewById(R.id.rbWidgetVehicle);
        name.setText(title);
        metaView.setText(meta);
        boolean checked = vin.equals(boundVin);
        if (radio != null) radio.setChecked(checked);
        row.setOnClickListener(v -> {
            if (configStore == null || appWidgetId < 0) {
                finish();
                return;
            }
            configStore.setWidgetVehicle(appWidgetId, vin);
            finish();
            VehicleWidgetProvider.refreshWidgets(this);
            if (!vin.isEmpty()) {
                VehicleWidgetProvider.refreshVehicleAsync(this, vin);
            }
        });
        return row;
    }

    private static String maskVin(String vin) {
        if (vin == null || vin.length() <= 4) return "••••";
        return "••••" + vin.substring(vin.length() - 4);
    }
}
