package com.bydwatch.companion.lab;

import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.BuildConfig;
import com.bydwatch.companion.R;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.switchmaterial.SwitchMaterial;

import java.util.HashMap;
import java.util.Map;

/** Advanced settings for the Overdrive WebView. */
public class AdvancedActivity extends AppCompatActivity {

    private SwitchMaterial switchShow;
    private SwitchMaterial switchHideUrl;
    private EditText etBase;
    private RadioGroup rgTop;
    private RadioGroup rgMore;
    private View layoutMore;
    private MaterialButton btnLoadAll;
    private EditText etManual;
    private TextView tvPreview;

    private final Map<Integer, String> morePaths = new HashMap<>();
    private boolean binding;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        if (BuildConfig.DEMO_BUILD) {
            super.onCreate(savedInstanceState);
            finish();
            return;
        }
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_advanced);

        switchShow = findViewById(R.id.switchAdvShowOverdrive);
        switchHideUrl = findViewById(R.id.switchAdvHideUrlBar);
        etBase = findViewById(R.id.etAdvBaseUrl);
        rgTop = findViewById(R.id.rgAdvTopPages);
        rgMore = findViewById(R.id.rgAdvMorePages);
        layoutMore = findViewById(R.id.layoutAdvMorePages);
        btnLoadAll = findViewById(R.id.btnAdvLoadAll);
        etManual = findViewById(R.id.etAdvManualUrl);
        tvPreview = findViewById(R.id.tvAdvPreview);

        ImageButton back = findViewById(R.id.btnAdvBack);
        if (back != null) back.setOnClickListener(v -> finish());
        View reset = findViewById(R.id.btnAdvReset);
        if (reset != null) reset.setOnClickListener(v -> resetForm());
        View save = findViewById(R.id.btnAdvSave);
        if (save != null) save.setOnClickListener(v -> save());

        populateMorePages();
        bindListeners();
        loadFromPrefs();
        updatePreview();
    }

    private void populateMorePages() {
        for (OverdrivePages.Page page : OverdrivePages.morePages()) {
            RadioButton button = new RadioButton(this);
            button.setId(View.generateViewId());
            button.setText(page.label);
            button.setTextColor(MaterialColors.getColor(button,
                    com.google.android.material.R.attr.colorOnSurface,
                    ContextCompat.getColor(this, R.color.text_primary)));
            rgMore.addView(button);
            morePaths.put(button.getId(), page.path);
        }
    }

    private void bindListeners() {
        if (btnLoadAll != null) {
            btnLoadAll.setOnClickListener(v -> {
                boolean show = layoutMore.getVisibility() != View.VISIBLE;
                layoutMore.setVisibility(show ? View.VISIBLE : View.GONE);
                btnLoadAll.setText(show ? R.string.advanced_show_top5 : R.string.advanced_load_all);
            });
        }

        rgTop.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding || checkedId == -1) return;
            binding = true;
            rgMore.clearCheck();
            etManual.setText("");
            binding = false;
            updatePreview();
        });

        rgMore.setOnCheckedChangeListener((group, checkedId) -> {
            if (binding || checkedId == -1) return;
            binding = true;
            rgTop.clearCheck();
            etManual.setText("");
            binding = false;
            updatePreview();
        });

        etManual.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                if (binding) return;
                if (s != null && s.toString().trim().length() > 0) {
                    binding = true;
                    rgTop.clearCheck();
                    rgMore.clearCheck();
                    binding = false;
                }
                updatePreview();
            }
        });

        etBase.addTextChangedListener(new SimpleTextWatcher() {
            @Override
            public void afterTextChanged(Editable s) {
                updatePreview();
            }
        });
    }

    private void loadFromPrefs() {
        binding = true;
        switchShow.setChecked(CarWebPrefs.showOverdrive(this));
        switchHideUrl.setChecked(CarWebPrefs.hideUrlBar(this));
        etBase.setText(CarWebPrefs.baseUrl(this));
        rgTop.clearCheck();
        rgMore.clearCheck();
        etManual.setText("");

        String page = CarWebPrefs.defaultPage(this);
        int topId = topIdForPath(page);
        if (topId != 0) {
            rgTop.check(topId);
        } else if (!"/".equals(page)) {
            Integer moreId = null;
            for (Map.Entry<Integer, String> entry : morePaths.entrySet()) {
                if (entry.getValue().equals(page)) {
                    moreId = entry.getKey();
                    break;
                }
            }
            if (moreId != null) {
                layoutMore.setVisibility(View.VISIBLE);
                btnLoadAll.setText(R.string.advanced_show_top5);
                rgMore.check(moreId);
            } else if (page != null && !page.isEmpty()) {
                etManual.setText(page);
            }
        }
        binding = false;
    }

    private void resetForm() {
        binding = true;
        switchShow.setChecked(false);
        switchHideUrl.setChecked(true);
        etBase.setText("");
        rgTop.clearCheck();
        rgMore.clearCheck();
        etManual.setText("");
        binding = false;
        updatePreview();
    }

    private void save() {
        String rawBase = etBase.getText().toString().trim();
        String base = CarWebUrl.normalize(rawBase);
        if (!rawBase.isEmpty() && base.isEmpty()) {
            Toast.makeText(this, R.string.advanced_invalid_address, Toast.LENGTH_SHORT).show();
            return;
        }
        String page = selectedPage();
        CarWebPrefs.setShowOverdrive(this, switchShow.isChecked());
        CarWebPrefs.setHideUrlBar(this, switchHideUrl.isChecked());
        CarWebPrefs.setBaseUrl(this, base);
        CarWebPrefs.setDefaultPage(this, page);
        finish();
    }

    private String selectedPage() {
        String manual = etManual.getText().toString().trim();
        if (!manual.isEmpty()) return manual;
        int topId = rgTop.getCheckedRadioButtonId();
        if (topId != 0) {
            String path = pathForTopId(topId);
            if (path != null) return path;
        }
        int moreId = rgMore.getCheckedRadioButtonId();
        if (moreId != 0) {
            String path = morePaths.get(moreId);
            if (path != null) return path;
        }
        return "/";
    }

    private void updatePreview() {
        if (tvPreview == null) return;
        String base = CarWebUrl.normalize(etBase.getText().toString());
        String resolved = CarWebUrl.resolve(base, selectedPage());
        tvPreview.setText(resolved.isEmpty()
                ? getString(R.string.advanced_preview_empty) : resolved);
    }

    private int topIdForPath(String path) {
        if ("/vehicle-control.html".equals(path)) return R.id.rbAdvVehicleControl;
        if ("/events.html".equals(path)) return R.id.rbAdvEvents;
        if ("/charging.html".equals(path)) return R.id.rbAdvCharging;
        if ("/trips.html".equals(path)) return R.id.rbAdvTrips;
        if ("/live-view.html".equals(path)) return R.id.rbAdvLiveView;
        return 0;
    }

    private String pathForTopId(int id) {
        if (id == R.id.rbAdvVehicleControl) return "/vehicle-control.html";
        if (id == R.id.rbAdvEvents) return "/events.html";
        if (id == R.id.rbAdvCharging) return "/charging.html";
        if (id == R.id.rbAdvTrips) return "/trips.html";
        if (id == R.id.rbAdvLiveView) return "/live-view.html";
        return null;
    }

    private abstract static class SimpleTextWatcher implements TextWatcher {
        @Override
        public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

        @Override
        public void onTextChanged(CharSequence s, int start, int before, int count) {}
    }
}
