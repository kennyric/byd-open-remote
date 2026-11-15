package com.bydwatch.companion.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.InputFilter;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RemoteViews;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.CustomActionCombo;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.WidgetSlotConfig;
import com.bydwatch.companion.protocol.WatchProtocol;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Manages the Live 4x3 Widget Preview card, 4 customizable button slot bar,
 * Action Combos list, Slot Picker dialog, and Combo Builder dialog in Preferences.
 */
public final class WidgetComboUiHelper {

    public interface Listener {
        @Nullable VehicleStatus getCurrentStatus();
        void onRunCombo(CustomActionCombo combo);
        void onConfigUpdated();
    }

    public interface ComboSavedListener {
        void onComboSaved(CustomActionCombo combo);
    }

    private static final int[] PREVIEW_TILE_IDS = {
            R.id.tileWidgetLock,
            R.id.tileWidgetUnlock,
            R.id.tileWidgetAc,
            R.id.tileWidgetRefresh
    };
    private static final int[] PREVIEW_BTN_IDS = {
            R.id.btnWidgetLock,
            R.id.btnWidgetUnlock,
            R.id.btnWidgetAc,
            R.id.btnWidgetRefresh
    };

    private static final List<String> COMBO_STEP_ACTIONS = Arrays.asList(
            WatchProtocol.ACTION_UNLOCK,
            WatchProtocol.ACTION_LOCK,
            WatchProtocol.ACTION_AC_ON,
            WatchProtocol.ACTION_AC_OFF,
            WatchProtocol.ACTION_LIGHTS,
            WatchProtocol.ACTION_HORN,
            WatchProtocol.ACTION_VENT_WINDOWS,
            WatchProtocol.ACTION_CLOSE_WINDOWS,
            WatchProtocol.ACTION_BATTERY_HEAT_ON,
            WatchProtocol.ACTION_BATTERY_HEAT_OFF,
            WatchProtocol.ACTION_SEAT_HEAT,
            WatchProtocol.ACTION_LOCATION,
            WatchProtocol.ACTION_STATUS
    );

    private final Activity activity;
    private final ConfigStore configStore;
    private final Listener listener;

    private FrameLayout flWidgetPreviewContainer;
    private TextView tvSampleBadge;
    private MaterialButton[] slotButtons;
    private LinearLayout layoutCombosContainer;

    public WidgetComboUiHelper(Activity activity, ConfigStore configStore, Listener listener) {
        this.activity = activity;
        this.configStore = configStore;
        this.listener = listener;
    }

    public void bindViews(
            FrameLayout flPreview,
            TextView tvSampleBadge,
            MaterialButton[] slotButtons,
            @Nullable View btnResetSlots,
            @Nullable View btnNewCombo,
            LinearLayout layoutCombos
    ) {
        this.flWidgetPreviewContainer = flPreview;
        this.tvSampleBadge = tvSampleBadge;
        this.slotButtons = slotButtons;
        this.layoutCombosContainer = layoutCombos;

        if (btnResetSlots != null) {
            btnResetSlots.setOnClickListener(v -> resetSlotsToDefault());
        }
        if (btnNewCombo != null) {
            btnNewCombo.setOnClickListener(v -> showComboEditorDialog(null, -1));
        }
        refreshUi();
    }

    public void refreshUi() {
        renderLiveWidgetPreview();
        renderSlotBar();
        renderComboList();
    }

    private void renderLiveWidgetPreview() {
        if (flWidgetPreviewContainer == null || configStore == null) return;

        VehicleStatus realStatus = listener != null ? listener.getCurrentStatus() : null;
        if (realStatus == null) {
            realStatus = VehicleWidgetProvider.resolveStatus(activity);
        }
        boolean isSample = (realStatus == null);
        VehicleStatus previewStatus = isSample ? buildSampleStatus() : realStatus;
        long lastSyncMs = isSample ? System.currentTimeMillis() : configStore.getWidgetLastSyncMs();

        if (tvSampleBadge != null) {
            tvSampleBadge.setVisibility(isSample ? View.VISIBLE : View.GONE);
        }

        int layoutRes = VehicleWidgetProvider.resolveTallLayoutRes(configStore);
        RemoteViews rv = VehicleWidgetProvider.buildWidgetRemoteViews(
                activity, configStore, layoutRes, true, false, true, previewStatus, lastSyncMs, null);

        flWidgetPreviewContainer.removeAllViews();
        try {
            Context appResCtx = AppLocaleHelper.localizedContext(
                    activity.getApplicationContext(), configStore);
            View previewView = rv.apply(appResCtx, flWidgetPreviewContainer);
            flWidgetPreviewContainer.addView(previewView);
            wirePreviewClickOverrides(previewView);
        } catch (Exception ignored) {
        }
    }

    private void wirePreviewClickOverrides(View previewView) {
        View header = previewView.findViewById(R.id.widgetHeader);
        if (header != null) header.setOnClickListener(null);
        View batteryCard = previewView.findViewById(R.id.layoutWidgetBatteryCard);
        if (batteryCard != null) batteryCard.setOnClickListener(null);
        View locBar = previewView.findViewById(R.id.layoutWidgetLocationBar);
        if (locBar != null) locBar.setOnClickListener(null);

        for (int i = 0; i < WidgetSlotConfig.SLOT_COUNT; i++) {
            final int slotIdx = i;
            View tile = previewView.findViewById(PREVIEW_TILE_IDS[i]);
            View btn = previewView.findViewById(PREVIEW_BTN_IDS[i]);
            View.OnClickListener pickerClick = v -> showSlotPickerDialog(slotIdx);
            if (tile != null) tile.setOnClickListener(pickerClick);
            if (btn != null) btn.setOnClickListener(pickerClick);
        }
    }

    private void renderSlotBar() {
        if (slotButtons == null || configStore == null) return;
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);
        VehicleStatus st = resolveStatusOrSample();
        List<WidgetSlotConfig> slots = configStore.getWidgetSlots();
        List<CustomActionCombo> combos = configStore.getActionCombos();

        for (int i = 0; i < slotButtons.length && i < WidgetSlotConfig.SLOT_COUNT; i++) {
            MaterialButton btn = slotButtons[i];
            if (btn == null) continue;
            WidgetSlotConfig slot = i < slots.size() ? slots.get(i) : WidgetSlotConfig.defaults().get(i);
            VehicleWidgetProvider.SlotVisual visual =
                    VehicleWidgetProvider.resolveSlotVisual(resCtx, slot, st, combos);
            btn.setText(resCtx.getString(R.string.slot_pill_fmt, i + 1, visual.label));
            final int slotIndex = i;
            btn.setOnClickListener(v -> showSlotPickerDialog(slotIndex));
        }
    }

    private void renderComboList() {
        if (layoutCombosContainer == null || configStore == null) return;
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);
        layoutCombosContainer.removeAllViews();
        List<CustomActionCombo> combos = configStore.getActionCombos();

        if (combos.isEmpty()) {
            TextView emptyTv = new TextView(activity);
            emptyTv.setText(resCtx.getString(R.string.combo_empty_hint));
            emptyTv.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            emptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            emptyTv.setPadding(0, 0, 0, dp(8));
            layoutCombosContainer.addView(emptyTv);

            Button createBtn = createCompactActionBtn(resCtx.getString(R.string.btn_new_combo_long), true);
            createBtn.setOnClickListener(v -> showComboEditorDialog(null, -1));
            layoutCombosContainer.addView(createBtn, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }

        for (CustomActionCombo combo : combos) {
            layoutCombosContainer.addView(buildComboCardView(resCtx, combo));
        }
    }

    private View buildComboCardView(Context resCtx, CustomActionCombo combo) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackgroundResource(R.drawable.bg_panel_inner);
        card.setPadding(dp(10), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.bottomMargin = dp(8);
        card.setLayoutParams(cardLp);

        FrameLayout iconBadge = new FrameLayout(activity);
        iconBadge.setBackgroundResource(VehicleWidgetProvider.bgResForTone(combo.tone));
        LinearLayout.LayoutParams badgeLp = new LinearLayout.LayoutParams(dp(36), dp(36));
        badgeLp.setMarginEnd(dp(10));
        iconBadge.setLayoutParams(badgeLp);

        ImageView iv = new ImageView(activity);
        iv.setImageResource(VehicleWidgetProvider.iconResForKey(combo.iconKey));
        iv.setColorFilter(ContextCompat.getColor(activity, VehicleWidgetProvider.textColorResForTone(combo.tone)));
        FrameLayout.LayoutParams ivLp = new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER);
        iconBadge.addView(iv, ivLp);
        card.addView(iconBadge);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginEnd(dp(6));
        textCol.setLayoutParams(textLp);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(combo.name);
        tvTitle.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setMaxLines(1);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(activity);
        tvSub.setText(formatComboSummary(resCtx, combo));
        tvSub.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvSub.setMaxLines(1);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvSub);
        card.addView(textCol);

        Button btnRun = createCompactActionBtn(resCtx.getString(R.string.btn_run_combo), true);
        btnRun.setOnClickListener(v -> {
            if (listener != null) listener.onRunCombo(combo);
        });
        card.addView(btnRun);

        Button btnEdit = createCompactActionBtn(resCtx.getString(R.string.btn_edit_combo), false);
        LinearLayout.LayoutParams editLp = (LinearLayout.LayoutParams) btnEdit.getLayoutParams();
        editLp.setMarginStart(dp(4));
        btnEdit.setOnClickListener(v -> showComboEditorDialog(combo, -1));
        card.addView(btnEdit);

        return card;
    }

    Button createCompactActionBtn(String text, boolean filled) {
        MaterialButton btn = new MaterialButton(
                activity,
                null,
                com.google.android.material.R.attr.materialButtonOutlinedStyle
        );
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumHeight(0);
        btn.setInsetTop(0);
        btn.setInsetBottom(0);
        btn.setPadding(dp(10), dp(5), dp(10), dp(5));
        btn.setCornerRadius(dp(10));
        if (filled) {
            btn.setStrokeWidth(0);
            btn.setBackgroundTintList(ColorStateList.valueOf(
                    ContextCompat.getColor(activity, R.color.widget_primary_container)));
            btn.setTextColor(ContextCompat.getColor(activity, R.color.widget_on_primary_container));
        }
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(30)));
        return btn;
    }

    private void resetSlotsToDefault() {
        if (configStore == null) return;
        configStore.setWidgetSlots(WidgetSlotConfig.defaults());
        refreshUi();
        if (listener != null) listener.onConfigUpdated();
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);
        Toast.makeText(activity, resCtx.getString(R.string.slots_reset_toast), Toast.LENGTH_SHORT).show();
    }

    public void showSlotPickerDialog(int initialSlotIndex) {
        if (configStore == null || activity.isFinishing()) return;
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);
        int startIdx = Math.max(0, Math.min(WidgetSlotConfig.SLOT_COUNT - 1, initialSlotIndex));
        final int[] activeSlot = { startIdx };
        final List<WidgetSlotConfig> currentSlots = new ArrayList<>(configStore.getWidgetSlots());
        final List<CustomActionCombo> combos = configStore.getActionCombos();

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(10));
        scroll.addView(root);

        // Top 1..4 Slot Selector Strip
        LinearLayout slotStrip = new LinearLayout(activity);
        slotStrip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams stripLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stripLp.bottomMargin = dp(12);
        slotStrip.setLayoutParams(stripLp);
        root.addView(slotStrip);

        // Optional Custom Button Label Input
        TextView tvLabelTitle = makeSectionHeader(resCtx.getString(R.string.label_custom_button_name));
        root.addView(tvLabelTitle);

        EditText etCustomLabel = new EditText(activity);
        etCustomLabel.setHint(resCtx.getString(R.string.hint_custom_button_name));
        etCustomLabel.setSingleLine(true);
        etCustomLabel.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(WidgetSlotConfig.MAX_LABEL_LENGTH) });
        etCustomLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        etLp.bottomMargin = dp(12);
        root.addView(etCustomLabel, etLp);

        // Dynamic body container so switching slots updates active selection highlights cleanly
        LinearLayout bodyContainer = new LinearLayout(activity);
        bodyContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(bodyContainer);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(resCtx.getString(R.string.dialog_choose_action_title, startIdx + 1))
                .setView(scroll)
                .setNegativeButton(resCtx.getString(R.string.btn_cancel), null)
                .create();

        final Runnable[] renderPickerBody = new Runnable[1];
        renderPickerBody[0] = () -> {
            int slotIdx = activeSlot[0];
            dialog.setTitle(resCtx.getString(R.string.dialog_choose_action_title, slotIdx + 1));
            WidgetSlotConfig selectedSlot = slotIdx < currentSlots.size()
                    ? currentSlots.get(slotIdx)
                    : WidgetSlotConfig.defaults().get(slotIdx);

            etCustomLabel.setText(selectedSlot.customLabel);

            slotStrip.removeAllViews();
            for (int i = 0; i < WidgetSlotConfig.SLOT_COUNT; i++) {
                Button pill = createCompactActionBtn(
                        resCtx.getString(R.string.label_button_number_prefix, i + 1),
                        i == slotIdx
                );
                LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, dp(32), 1f);
                if (i < WidgetSlotConfig.SLOT_COUNT - 1) pLp.setMarginEnd(dp(4));
                pill.setLayoutParams(pLp);
                final int idx = i;
                pill.setOnClickListener(v -> {
                    activeSlot[0] = idx;
                    renderPickerBody[0].run();
                });
                slotStrip.addView(pill);
            }

            bodyContainer.removeAllViews();

            // Section: Your Action Combos
            LinearLayout comboHeaderRow = new LinearLayout(activity);
            comboHeaderRow.setOrientation(LinearLayout.HORIZONTAL);
            comboHeaderRow.setGravity(Gravity.CENTER_VERTICAL);
            TextView tvCombosHeader = makeSectionHeader(resCtx.getString(R.string.section_your_combos));
            tvCombosHeader.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            comboHeaderRow.addView(tvCombosHeader);

            Button btnQuickNew = createCompactActionBtn(resCtx.getString(R.string.btn_new_combo), false);
            btnQuickNew.setOnClickListener(v -> {
                dialog.dismiss();
                showComboEditorDialog(null, activeSlot[0]);
            });
            comboHeaderRow.addView(btnQuickNew);
            bodyContainer.addView(comboHeaderRow);

            if (combos.isEmpty()) {
                TextView noneTv = new TextView(activity);
                noneTv.setText(resCtx.getString(R.string.combo_empty_hint));
                noneTv.setTextColor(ContextCompat.getColor(activity, R.color.text_muted));
                noneTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                noneTv.setPadding(0, dp(4), 0, dp(10));
                bodyContainer.addView(noneTv);
            } else {
                for (CustomActionCombo combo : combos) {
                    boolean selected = selectedSlot.isCombo() && combo.id.equals(selectedSlot.targetId);
                    View row = buildComboPickerRow(resCtx, combo, selected);
                    row.setOnClickListener(v -> {
                        String label = etCustomLabel.getText() != null
                                ? etCustomLabel.getText().toString().trim() : "";
                        saveSlotChoice(currentSlots, activeSlot[0], WidgetSlotConfig.combo(combo.id, label));
                        dialog.dismiss();
                    });
                    bodyContainer.addView(row);
                }
            }

            // Section: Single Actions (3-column grid)
            TextView tvSinglesHeader = makeSectionHeader(resCtx.getString(R.string.section_single_actions));
            tvSinglesHeader.setPadding(0, dp(8), 0, dp(6));
            bodyContainer.addView(tvSinglesHeader);

            List<String> actions = WidgetSlotConfig.ALL_SINGLE_ACTIONS;
            for (int r = 0; r < actions.size(); r += 3) {
                LinearLayout rowLayout = new LinearLayout(activity);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                rowLp.bottomMargin = dp(6);
                rowLayout.setLayoutParams(rowLp);

                for (int c = 0; c < 3; c++) {
                    int idx = r + c;
                    if (idx >= actions.size()) {
                        View spacer = new View(activity);
                        rowLayout.addView(spacer, new LinearLayout.LayoutParams(0, dp(64), 1f));
                        continue;
                    }
                    String actionKey = actions.get(idx);
                    boolean selected = !selectedSlot.isCombo() && actionKey.equals(selectedSlot.targetId);
                    View tile = buildSingleActionTile(resCtx, actionKey, selected);
                    LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, dp(66), 1f);
                    if (c < 2) tLp.setMarginEnd(dp(6));
                    tile.setLayoutParams(tLp);
                    tile.setOnClickListener(v -> {
                        String label = etCustomLabel.getText() != null
                                ? etCustomLabel.getText().toString().trim() : "";
                        saveSlotChoice(currentSlots, activeSlot[0],
                                new WidgetSlotConfig(WidgetSlotConfig.TYPE_SINGLE, actionKey, label));
                        dialog.dismiss();
                    });
                    rowLayout.addView(tile);
                }
                bodyContainer.addView(rowLayout);
            }
        };

        renderPickerBody[0].run();
        dialog.show();
    }

    private void saveSlotChoice(List<WidgetSlotConfig> slots, int slotIdx, WidgetSlotConfig updated) {
        while (slots.size() < WidgetSlotConfig.SLOT_COUNT) {
            slots.add(WidgetSlotConfig.defaults().get(slots.size()));
        }
        slots.set(slotIdx, updated);
        configStore.setWidgetSlots(slots);
        refreshUi();
        if (listener != null) listener.onConfigUpdated();
    }

    View buildComboPickerRow(Context resCtx, CustomActionCombo combo, boolean selected) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(makeHighlightBoxDrawable(selected));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(6);
        row.setLayoutParams(lp);

        FrameLayout badge = new FrameLayout(activity);
        badge.setBackgroundResource(VehicleWidgetProvider.bgResForTone(combo.tone));
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(dp(30), dp(30));
        bLp.setMarginEnd(dp(10));
        badge.setLayoutParams(bLp);
        ImageView iv = new ImageView(activity);
        iv.setImageResource(VehicleWidgetProvider.iconResForKey(combo.iconKey));
        iv.setColorFilter(ContextCompat.getColor(activity, VehicleWidgetProvider.textColorResForTone(combo.tone)));
        badge.addView(iv, new FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER));
        row.addView(badge);

        TextView tvName = new TextView(activity);
        tvName.setText(combo.name + "  ·  " + formatStepCount(resCtx, combo.steps.size()));
        tvName.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvName.setTypeface(null, selected ? Typeface.BOLD : Typeface.NORMAL);
        row.addView(tvName);
        return row;
    }

    View buildSingleActionTile(Context resCtx, String actionKey, boolean selected) {
        VehicleWidgetProvider.SlotVisual visual = VehicleWidgetProvider.resolveSlotVisual(
                resCtx, WidgetSlotConfig.single(actionKey), resolveStatusOrSample(), null);

        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackgroundResource(visual.bgRes);
        tile.setPadding(dp(6), dp(8), dp(6), dp(8));
        if (selected) {
            tile.setForeground(makeSelectionBorderDrawable());
        }

        ImageView iv = new ImageView(activity);
        iv.setImageResource(visual.iconRes);
        iv.setColorFilter(ContextCompat.getColor(activity, visual.textColorRes));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(dp(20), dp(20));
        ivLp.bottomMargin = dp(4);
        tile.addView(iv, ivLp);

        TextView tv = new TextView(activity);
        tv.setText(visual.label);
        tv.setTextColor(ContextCompat.getColor(activity, visual.textColorRes));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setGravity(Gravity.CENTER);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(tv);
        return tile;
    }

    public void showComboEditorDialog(@Nullable CustomActionCombo existing, int assignToSlotIndex) {
        showComboEditorDialog(existing, assignToSlotIndex, null);
    }

    public void showComboEditorDialog(
            @Nullable CustomActionCombo existing,
            int assignToSlotIndex,
            @Nullable ComboSavedListener onSaved
    ) {
        if (configStore == null || activity.isFinishing()) return;
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);

        final String[] selectedTone = {
                existing != null ? existing.tone : CustomActionCombo.TONE_AMBER
        };
        final String[] selectedIcon = {
                existing != null ? existing.iconKey : CustomActionCombo.ICON_BOLT
        };
        final List<EditableStep> steps = new ArrayList<>();
        if (existing != null) {
            for (CustomActionCombo.ComboStep s : existing.steps) {
                steps.add(EditableStep.from(s));
            }
        } else {
            steps.add(new EditableStep(WatchProtocol.ACTION_UNLOCK));
            EditableStep acStep = new EditableStep(WatchProtocol.ACTION_AC_ON);
            acStep.tempC = configStore.getDefaultTempC();
            acStep.manualAc = configStore.isManualAcMode();
            acStep.tempGear = configStore.getDefaultTempGear();
            acStep.fanLevel = configStore.getDefaultFanLevel();
            acStep.windMode = configStore.getDefaultWindMode();
            steps.add(acStep);
        }

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(10));
        scroll.addView(root);

        // Name
        root.addView(makeSectionHeader(resCtx.getString(R.string.label_combo_name)));
        EditText etName = new EditText(activity);
        etName.setHint(resCtx.getString(R.string.hint_combo_name));
        etName.setSingleLine(true);
        etName.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(CustomActionCombo.MAX_NAME_LENGTH) });
        if (existing != null) {
            etName.setText(existing.name);
        }
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameLp.bottomMargin = dp(10);
        root.addView(etName, nameLp);

        // Color & Icon containers
        root.addView(makeSectionHeader(resCtx.getString(R.string.label_combo_color)));
        LinearLayout toneRow = new LinearLayout(activity);
        toneRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams toneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toneLp.bottomMargin = dp(10);
        root.addView(toneRow, toneLp);

        root.addView(makeSectionHeader(resCtx.getString(R.string.label_combo_icon)));
        LinearLayout iconGrid = new LinearLayout(activity);
        iconGrid.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gridLp.bottomMargin = dp(12);
        root.addView(iconGrid, gridLp);

        // Steps header + Add button
        LinearLayout stepsHeader = new LinearLayout(activity);
        stepsHeader.setOrientation(LinearLayout.HORIZONTAL);
        stepsHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvStepsTitle = makeSectionHeader(resCtx.getString(R.string.label_combo_steps));
        tvStepsTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        stepsHeader.addView(tvStepsTitle);

        Button btnAddStep = createCompactActionBtn(resCtx.getString(R.string.btn_add_action), true);
        stepsHeader.addView(btnAddStep);
        root.addView(stepsHeader);

        LinearLayout stepsContainer = new LinearLayout(activity);
        stepsContainer.setOrientation(LinearLayout.VERTICAL);
        root.addView(stepsContainer);

        final Runnable[] renderAppearances = new Runnable[1];
        renderAppearances[0] = () -> renderToneAndIconPickers(
                toneRow, iconGrid, selectedTone, selectedIcon, renderAppearances[0]);

        final Runnable[] renderSteps = new Runnable[1];
        renderSteps[0] = () -> {
            stepsContainer.removeAllViews();
            btnAddStep.setEnabled(steps.size() < CustomActionCombo.MAX_STEPS);
            btnAddStep.setAlpha(steps.size() < CustomActionCombo.MAX_STEPS ? 1f : 0.45f);

            for (int i = 0; i < steps.size(); i++) {
                stepsContainer.addView(buildEditableStepCard(resCtx, steps, i, renderSteps[0]));
            }
        };

        btnAddStep.setOnClickListener(v -> {
            if (steps.size() >= CustomActionCombo.MAX_STEPS) {
                Toast.makeText(activity, resCtx.getString(R.string.combo_max_steps_hint), Toast.LENGTH_SHORT).show();
                return;
            }
            showAddStepActionDialog(resCtx, action -> {
                EditableStep next = new EditableStep(action);
                next.tempC = configStore.getDefaultTempC();
                next.manualAc = configStore.isManualAcMode();
                next.tempGear = configStore.getDefaultTempGear();
                next.fanLevel = configStore.getDefaultFanLevel();
                next.windMode = configStore.getDefaultWindMode();
                steps.add(next);
                renderSteps[0].run();
            });
        });

        renderAppearances[0].run();
        renderSteps[0].run();

        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle(resCtx.getString(existing != null
                        ? R.string.dialog_combo_edit_title
                        : R.string.dialog_combo_create_title))
                .setView(scroll)
                .setNegativeButton(resCtx.getString(R.string.btn_cancel), null)
                .setPositiveButton(resCtx.getString(R.string.btn_save), null);

        if (existing != null) {
            builder.setNeutralButton(resCtx.getString(R.string.btn_delete_combo), (d, w) -> {
                deleteComboById(existing.id);
            });
        }

        AlertDialog dialog = builder.create();
        dialog.show();

        Button posBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (posBtn != null) {
            posBtn.setOnClickListener(v -> {
                if (steps.isEmpty()) {
                    Toast.makeText(activity, resCtx.getString(R.string.combo_empty_error), Toast.LENGTH_SHORT).show();
                    return;
                }
                String name = etName.getText() != null ? etName.getText().toString().trim() : "";
                if (name.isEmpty()) {
                    name = resCtx.getString(R.string.combo_default_name);
                }
                List<CustomActionCombo.ComboStep> builtSteps = new ArrayList<>(steps.size());
                for (EditableStep es : steps) {
                    builtSteps.add(es.toComboStep());
                }
                String id = existing != null ? existing.id : CustomActionCombo.newId();
                CustomActionCombo saved = new CustomActionCombo(
                        id, name, selectedIcon[0], selectedTone[0], builtSteps);
                upsertCombo(saved, assignToSlotIndex);
                if (onSaved != null) {
                    onSaved.onComboSaved(saved);
                }
                dialog.dismiss();
            });
        }
    }

    private View buildEditableStepCard(
            Context resCtx,
            List<EditableStep> steps,
            int index,
            Runnable rerender
    ) {
        EditableStep step = steps.get(index);
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_panel_inner);
        card.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(6);
        card.setLayoutParams(cardLp);

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText((index + 1) + ". " + VehicleWidgetProvider.stepDisplayName(resCtx, step.toComboStep()));
        tvTitle.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        topRow.addView(tvTitle);

        if (index > 0) {
            Button btnUp = createCompactActionBtn("↑", false);
            btnUp.setOnClickListener(v -> {
                EditableStep cur = steps.remove(index);
                steps.add(index - 1, cur);
                rerender.run();
            });
            topRow.addView(btnUp);
        }
        if (index < steps.size() - 1) {
            Button btnDown = createCompactActionBtn("↓", false);
            LinearLayout.LayoutParams dLp = (LinearLayout.LayoutParams) btnDown.getLayoutParams();
            dLp.setMarginStart(dp(4));
            btnDown.setOnClickListener(v -> {
                EditableStep cur = steps.remove(index);
                steps.add(index + 1, cur);
                rerender.run();
            });
            topRow.addView(btnDown);
        }
        Button btnDel = createCompactActionBtn("×", false);
        LinearLayout.LayoutParams delLp = (LinearLayout.LayoutParams) btnDel.getLayoutParams();
        delLp.setMarginStart(dp(4));
        btnDel.setOnClickListener(v -> {
            steps.remove(index);
            rerender.run();
        });
        topRow.addView(btnDel);
        card.addView(topRow);

        if (WatchProtocol.ACTION_AC_ON.equals(step.action)) {
            buildAcStepControls(resCtx, card, step, rerender);
        } else if (WatchProtocol.ACTION_SEAT_HEAT.equals(step.action)) {
            buildSeatStepControls(resCtx, card, step, rerender);
        }

        return card;
    }

    private void buildAcStepControls(
            Context resCtx,
            LinearLayout card,
            EditableStep step,
            Runnable rerender
    ) {
        LinearLayout modeRow = new LinearLayout(activity);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams mLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mLp.topMargin = dp(6);
        modeRow.setLayoutParams(mLp);

        Button btnAuto = createCompactActionBtn(
                resCtx.getString(R.string.combo_ac_mode_auto), step.useCustomAc && !step.manualAc);
        Button btnManual = createCompactActionBtn(
                resCtx.getString(R.string.combo_ac_mode_manual), step.useCustomAc && step.manualAc);
        Button btnDef = createCompactActionBtn(
                resCtx.getString(R.string.combo_ac_mode_default), !step.useCustomAc);
        ((LinearLayout.LayoutParams) btnManual.getLayoutParams()).setMarginStart(dp(4));
        ((LinearLayout.LayoutParams) btnDef.getLayoutParams()).setMarginStart(dp(4));

        btnAuto.setOnClickListener(v -> {
            step.useCustomAc = true;
            step.manualAc = false;
            rerender.run();
        });
        btnManual.setOnClickListener(v -> {
            step.useCustomAc = true;
            step.manualAc = true;
            rerender.run();
        });
        btnDef.setOnClickListener(v -> {
            step.useCustomAc = false;
            rerender.run();
        });
        modeRow.addView(btnAuto);
        modeRow.addView(btnManual);
        modeRow.addView(btnDef);
        card.addView(modeRow);

        if (!step.useCustomAc) return;

        if (!step.manualAc) {
            card.addView(buildStepperRow(
                    resCtx.getString(R.string.label_temp),
                    step.tempC + "°C",
                    () -> {
                        step.tempC = Math.max(WatchProtocol.MIN_TEMP_C, step.tempC - 1);
                        rerender.run();
                    },
                    () -> {
                        step.tempC = Math.min(WatchProtocol.MAX_TEMP_C, step.tempC + 1);
                        rerender.run();
                    }
            ));
        } else {
            card.addView(buildStepperRow(
                    resCtx.getString(R.string.label_temp),
                    resCtx.getString(R.string.climate_level_fmt, step.tempGear),
                    () -> {
                        step.tempGear = Math.max(1, step.tempGear - 1);
                        rerender.run();
                    },
                    () -> {
                        step.tempGear = Math.min(9, step.tempGear + 1);
                        rerender.run();
                    }
            ));
            card.addView(buildStepperRow(
                    resCtx.getString(R.string.label_fan_speed),
                    String.valueOf(step.fanLevel),
                    () -> {
                        step.fanLevel = Math.max(1, step.fanLevel - 1);
                        rerender.run();
                    },
                    () -> {
                        step.fanLevel = Math.min(7, step.fanLevel + 1);
                        rerender.run();
                    }
            ));

            LinearLayout windRow = new LinearLayout(activity);
            windRow.setOrientation(LinearLayout.HORIZONTAL);
            windRow.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams wLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            wLp.topMargin = dp(4);
            windRow.setLayoutParams(wLp);

            TextView wLabel = new TextView(activity);
            wLabel.setText(resCtx.getString(R.string.label_vent));
            wLabel.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
            wLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            wLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            windRow.addView(wLabel);

            int[] modes = { 1, 2, 3 };
            int[] names = { R.string.combo_wind_face, R.string.combo_wind_face_feet, R.string.combo_wind_feet };
            for (int k = 0; k < modes.length; k++) {
                final int modeVal = modes[k];
                Button wb = createCompactActionBtn(resCtx.getString(names[k]), step.windMode == modeVal);
                if (k > 0) ((LinearLayout.LayoutParams) wb.getLayoutParams()).setMarginStart(dp(4));
                wb.setOnClickListener(v -> {
                    step.windMode = modeVal;
                    rerender.run();
                });
                windRow.addView(wb);
            }
            card.addView(windRow);
        }
    }

    private void buildSeatStepControls(
            Context resCtx,
            LinearLayout card,
            EditableStep step,
            Runnable rerender
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(6);
        row.setLayoutParams(rLp);

        int[] levels = { -1, 0, 1, 2 };
        int[] labels = {
                R.string.combo_step_seat_cycle,
                R.string.state_closed,
                R.string.combo_step_seat_low,
                R.string.combo_step_seat_high
        };
        for (int k = 0; k < levels.length; k++) {
            final int lvl = levels[k];
            String text = lvl == 0 ? "Off" : resCtx.getString(labels[k]).replace("Seat: ", "").replace("Kursi: ", "");
            Button btn = createCompactActionBtn(text, step.seatLevel == lvl);
            if (k > 0) ((LinearLayout.LayoutParams) btn.getLayoutParams()).setMarginStart(dp(4));
            btn.setOnClickListener(v -> {
                step.seatLevel = lvl;
                rerender.run();
            });
            row.addView(btn);
        }
        card.addView(row);
    }

    private LinearLayout buildStepperRow(
            String label,
            String valueText,
            Runnable onMinus,
            Runnable onPlus
    ) {
        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rLp.topMargin = dp(6);
        row.setLayoutParams(rLp);

        TextView tvLabel = new TextView(activity);
        tvLabel.setText(label);
        tvLabel.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        tvLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tvLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLabel);

        Button btnMinus = createCompactActionBtn("−", false);
        btnMinus.setOnClickListener(v -> onMinus.run());
        row.addView(btnMinus);

        TextView tvVal = new TextView(activity);
        tvVal.setText(valueText);
        tvVal.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tvVal.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        tvVal.setTypeface(null, Typeface.BOLD);
        tvVal.setGravity(Gravity.CENTER);
        tvVal.setPadding(dp(10), 0, dp(10), 0);
        row.addView(tvVal);

        Button btnPlus = createCompactActionBtn("+", false);
        btnPlus.setOnClickListener(v -> onPlus.run());
        row.addView(btnPlus);

        return row;
    }

    private void showAddStepActionDialog(Context resCtx, java.util.function.Consumer<String> onSelected) {
        CharSequence[] items = new CharSequence[COMBO_STEP_ACTIONS.size()];
        for (int i = 0; i < COMBO_STEP_ACTIONS.size(); i++) {
            CustomActionCombo.ComboStep preview = new CustomActionCombo.ComboStep(
                    COMBO_STEP_ACTIONS.get(i), false, false, 20, 3, 4, 1, -1);
            items[i] = VehicleWidgetProvider.stepDisplayName(resCtx, preview);
        }
        new AlertDialog.Builder(activity)
                .setTitle(resCtx.getString(R.string.btn_add_action))
                .setItems(items, (d, which) -> onSelected.accept(COMBO_STEP_ACTIONS.get(which)))
                .setNegativeButton(resCtx.getString(R.string.btn_cancel), null)
                .show();
    }

    private void upsertCombo(CustomActionCombo combo, int assignToSlotIndex) {
        List<CustomActionCombo> current = new ArrayList<>(configStore.getActionCombos());
        boolean replaced = false;
        for (int i = 0; i < current.size(); i++) {
            if (combo.id.equals(current.get(i).id)) {
                current.set(i, combo);
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            current.add(combo);
        }
        configStore.setActionCombos(current);

        if (assignToSlotIndex >= 0 && assignToSlotIndex < WidgetSlotConfig.SLOT_COUNT) {
            List<WidgetSlotConfig> slots = new ArrayList<>(configStore.getWidgetSlots());
            while (slots.size() < WidgetSlotConfig.SLOT_COUNT) {
                slots.add(WidgetSlotConfig.defaults().get(slots.size()));
            }
            slots.set(assignToSlotIndex, WidgetSlotConfig.combo(combo.id, ""));
            configStore.setWidgetSlots(slots);
        }

        refreshUi();
        if (listener != null) listener.onConfigUpdated();
    }

    private void deleteComboById(String comboId) {
        List<CustomActionCombo> current = new ArrayList<>(configStore.getActionCombos());
        current.removeIf(c -> comboId.equals(c.id));
        configStore.setActionCombos(current);

        List<WidgetSlotConfig> slots = new ArrayList<>(configStore.getWidgetSlots());
        List<WidgetSlotConfig> defs = WidgetSlotConfig.defaults();
        boolean slotsChanged = false;
        for (int i = 0; i < slots.size() && i < WidgetSlotConfig.SLOT_COUNT; i++) {
            WidgetSlotConfig s = slots.get(i);
            if (s.isCombo() && comboId.equals(s.targetId)) {
                slots.set(i, defs.get(i));
                slotsChanged = true;
            }
        }
        if (slotsChanged) {
            configStore.setWidgetSlots(slots);
        }

        refreshUi();
        if (listener != null) listener.onConfigUpdated();
    }

    void renderToneAndIconPickers(
            LinearLayout toneRow,
            LinearLayout iconGrid,
            String[] selectedTone,
            String[] selectedIcon,
            Runnable rerender
    ) {
        toneRow.removeAllViews();
        for (String tone : CustomActionCombo.PRESET_TONES) {
            boolean active = tone.equals(selectedTone[0]);
            FrameLayout swatch = new FrameLayout(activity);
            swatch.setBackgroundResource(VehicleWidgetProvider.bgResForTone(tone));
            if (active) swatch.setForeground(makeSelectionBorderDrawable());
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, dp(34), 1f);
            sLp.setMarginEnd(dp(6));
            swatch.setLayoutParams(sLp);
            if (active) {
                TextView check = new TextView(activity);
                check.setText("✓");
                check.setTextColor(ContextCompat.getColor(
                        activity, VehicleWidgetProvider.textColorResForTone(tone)));
                check.setTypeface(null, Typeface.BOLD);
                check.setGravity(Gravity.CENTER);
                swatch.addView(check, new FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            }
            swatch.setOnClickListener(v -> {
                selectedTone[0] = tone;
                if (rerender != null) rerender.run();
            });
            toneRow.addView(swatch);
        }

        iconGrid.removeAllViews();
        List<String> icons = CustomActionCombo.PRESET_ICONS;
        for (int r = 0; r < icons.size(); r += 4) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rLp.bottomMargin = dp(6);
            row.setLayoutParams(rLp);
            for (int c = 0; c < 4; c++) {
                int idx = r + c;
                if (idx >= icons.size()) continue;
                String key = icons.get(idx);
                boolean active = key.equals(selectedIcon[0]);
                FrameLayout cell = new FrameLayout(activity);
                if (active) {
                    cell.setBackgroundResource(VehicleWidgetProvider.bgResForTone(selectedTone[0]));
                    cell.setForeground(makeSelectionBorderDrawable());
                } else {
                    cell.setBackgroundResource(R.drawable.bg_panel_inner);
                }
                LinearLayout.LayoutParams cLp = new LinearLayout.LayoutParams(0, dp(42), 1f);
                if (c < 3) cLp.setMarginEnd(dp(6));
                cell.setLayoutParams(cLp);

                ImageView iv = new ImageView(activity);
                iv.setImageResource(VehicleWidgetProvider.iconResForKey(key));
                iv.setColorFilter(ContextCompat.getColor(activity,
                        active ? VehicleWidgetProvider.textColorResForTone(selectedTone[0]) : R.color.text_primary));
                cell.addView(iv, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                cell.setOnClickListener(v -> {
                    selectedIcon[0] = key;
                    if (rerender != null) rerender.run();
                });
                row.addView(cell);
            }
            iconGrid.addView(row);
        }
    }

    String formatComboSummary(Context resCtx, CustomActionCombo combo) {
        String countText = formatStepCount(resCtx, combo.steps.size());
        if (combo.steps.isEmpty()) return countText;
        StringBuilder sb = new StringBuilder(countText).append(" · ");
        int maxShow = Math.min(3, combo.steps.size());
        for (int i = 0; i < maxShow; i++) {
            if (i > 0) sb.append(" → ");
            sb.append(VehicleWidgetProvider.stepDisplayName(resCtx, combo.steps.get(i)));
        }
        if (combo.steps.size() > maxShow) sb.append("…");
        return sb.toString();
    }

    private String formatStepCount(Context resCtx, int count) {
        if (count == 1) return resCtx.getString(R.string.combo_step_single);
        return resCtx.getString(R.string.combo_step_count_fmt, count);
    }

    private VehicleStatus resolveStatusOrSample() {
        VehicleStatus real = listener != null ? listener.getCurrentStatus() : null;
        if (real != null) return real;
        real = VehicleWidgetProvider.resolveStatus(activity);
        return real != null ? real : buildSampleStatus();
    }

    private static VehicleStatus buildSampleStatus() {
        long now = System.currentTimeMillis();
        return new VehicleStatus.Builder(82, 332, true, false, 26, 20, now)
                .modelName("BYD Atto 3")
                .totalMileageKm(14820)
                .location(-6.2088, 106.8456, now)
                .build();
    }

    TextView makeSectionHeader(String text) {
        TextView tv = new TextView(activity);
        tv.setText(text);
        tv.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setPadding(0, dp(2), 0, dp(4));
        return tv;
    }

    private GradientDrawable makeHighlightBoxDrawable(boolean selected) {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(ContextCompat.getColor(activity, R.color.surface_inner));
        if (selected) {
            d.setStroke(dp(2), ContextCompat.getColor(activity, R.color.accent_emerald));
        }
        return d;
    }

    private GradientDrawable makeSelectionBorderDrawable() {
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(14));
        d.setColor(Color.TRANSPARENT);
        d.setStroke(dp(2), ContextCompat.getColor(activity, R.color.accent_emerald));
        return d;
    }

    int dp(int value) {
        return Math.round(TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, value, activity.getResources().getDisplayMetrics()));
    }

    private static final class EditableStep {
        String action;
        boolean useCustomAc = true;
        boolean manualAc = false;
        int tempC = 20;
        int tempGear = 3;
        int fanLevel = 4;
        int windMode = 1;
        int seatLevel = -1;

        EditableStep(String action) {
            this.action = action;
        }

        static EditableStep from(CustomActionCombo.ComboStep s) {
            EditableStep e = new EditableStep(s.action);
            e.useCustomAc = s.useCustomAc;
            e.manualAc = s.manualAc;
            e.tempC = s.tempC;
            e.tempGear = s.tempGear;
            e.fanLevel = s.fanLevel;
            e.windMode = s.windMode;
            e.seatLevel = s.seatLevel;
            return e;
        }

        CustomActionCombo.ComboStep toComboStep() {
            return new CustomActionCombo.ComboStep(
                    action, useCustomAc, manualAc, tempC, tempGear, fanLevel, windMode, seatLevel);
        }
    }
}
