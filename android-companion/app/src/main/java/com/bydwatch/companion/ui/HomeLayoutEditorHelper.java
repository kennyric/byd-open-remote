package com.bydwatch.companion.ui;

import android.app.Activity;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
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
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.bydwatch.companion.R;
import com.bydwatch.companion.data.ConfigStore;
import com.bydwatch.companion.model.CustomActionCombo;
import com.bydwatch.companion.model.HomeButtonConfig;
import com.bydwatch.companion.model.VehicleStatus;
import com.bydwatch.companion.model.WidgetSlotConfig;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.ArrayList;
import java.util.List;

/**
 * Manages the Homepage Layout Editor dialog (section visibility + custom homepage buttons),
 * the Add/Edit Homepage Button dialog, and renders the custom button grid on the homepage.
 */
public final class HomeLayoutEditorHelper {

    public interface Listener {
        @Nullable VehicleStatus getCurrentStatus();
        void onRunSingleAction(String actionKey);
        void onRunCombo(CustomActionCombo combo);
        void onLayoutConfigChanged();
    }

    private final Activity activity;
    private final ConfigStore configStore;
    private final WidgetComboUiHelper comboHelper;
    private final Listener listener;

    private View layoutHomeCustomSection;
    private LinearLayout layoutHomeCustomGrid;

    public HomeLayoutEditorHelper(
            Activity activity,
            ConfigStore configStore,
            WidgetComboUiHelper comboHelper,
            Listener listener
    ) {
        this.activity = activity;
        this.configStore = configStore;
        this.comboHelper = comboHelper;
        this.listener = listener;
    }

    public void bindViews(@Nullable View section, @Nullable LinearLayout grid) {
        this.layoutHomeCustomSection = section;
        this.layoutHomeCustomGrid = grid;
        renderCustomButtonsGrid();
    }

    public void renderCustomButtonsGrid() {
        if (layoutHomeCustomSection == null || layoutHomeCustomGrid == null || configStore == null) {
            return;
        }
        List<HomeButtonConfig> buttons = configStore.getHomeCustomButtons();
        layoutHomeCustomGrid.removeAllViews();
        if (buttons.isEmpty()) {
            layoutHomeCustomSection.setVisibility(View.GONE);
            return;
        }
        layoutHomeCustomSection.setVisibility(View.VISIBLE);

        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);
        VehicleStatus st = listener != null ? listener.getCurrentStatus() : null;
        List<CustomActionCombo> combos = configStore.getActionCombos();

        for (int i = 0; i < buttons.size(); i += 2) {
            LinearLayout row = new LinearLayout(activity);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i + 2 < buttons.size()) {
                rowLp.bottomMargin = comboHelper.dp(8);
            }
            row.setLayoutParams(rowLp);

            HomeButtonConfig left = buttons.get(i);
            HomeButtonConfig right = (i + 1 < buttons.size()) ? buttons.get(i + 1) : null;

            View leftBtn = buildHomepageCustomButtonView(resCtx, left, st, combos);
            LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, comboHelper.dp(44), 1f);
            if (right != null) {
                leftLp.setMarginEnd(comboHelper.dp(6));
            }
            row.addView(leftBtn, leftLp);

            if (right != null) {
                View rightBtn = buildHomepageCustomButtonView(resCtx, right, st, combos);
                LinearLayout.LayoutParams rightLp = new LinearLayout.LayoutParams(0, comboHelper.dp(44), 1f);
                row.addView(rightBtn, rightLp);
            }
            layoutHomeCustomGrid.addView(row);
        }
    }

    private View buildHomepageCustomButtonView(
            Context resCtx,
            HomeButtonConfig btn,
            @Nullable VehicleStatus st,
            List<CustomActionCombo> combos
    ) {
        VehicleWidgetProvider.SlotVisual visual = resolveButtonVisual(resCtx, btn, st, combos);

        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.HORIZONTAL);
        tile.setGravity(Gravity.CENTER);
        tile.setBackgroundResource(visual.bgRes);
        tile.setPadding(comboHelper.dp(12), comboHelper.dp(6), comboHelper.dp(12), comboHelper.dp(6));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setContentDescription(visual.label);
        Drawable ripple = resolveSelectableForeground();
        if (ripple != null) {
            tile.setForeground(ripple);
        }

        ImageView iv = new ImageView(activity);
        iv.setImageResource(visual.iconRes);
        iv.setColorFilter(ContextCompat.getColor(activity, visual.textColorRes));
        LinearLayout.LayoutParams ivLp = new LinearLayout.LayoutParams(
                comboHelper.dp(18), comboHelper.dp(18));
        ivLp.setMarginEnd(comboHelper.dp(8));
        tile.addView(iv, ivLp);

        TextView tv = new TextView(activity);
        tv.setText(visual.label);
        tv.setTextColor(ContextCompat.getColor(activity, visual.textColorRes));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setMaxLines(1);
        tv.setEllipsize(TextUtils.TruncateAt.END);
        tile.addView(tv);

        tile.setOnClickListener(v -> triggerCustomButton(resCtx, btn));
        return tile;
    }

    private void triggerCustomButton(Context resCtx, HomeButtonConfig btn) {
        if (btn == null || listener == null) return;
        if (btn.isCombo()) {
            CustomActionCombo matched = findComboById(btn.targetId);
            if (matched == null) {
                Toast.makeText(activity, resCtx.getString(R.string.home_button_combo_missing), Toast.LENGTH_SHORT).show();
                return;
            }
            listener.onRunCombo(matched);
            return;
        }
        listener.onRunSingleAction(btn.targetId);
    }

    public void showEditLayoutDialog() {
        if (configStore == null || activity.isFinishing()) return;
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(comboHelper.dp(18), comboHelper.dp(14), comboHelper.dp(18), comboHelper.dp(10));
        scroll.addView(root);

        // Header: Custom Buttons + "+ Add Button"
        LinearLayout customHeader = new LinearLayout(activity);
        customHeader.setOrientation(LinearLayout.HORIZONTAL);
        customHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView tvButtonsTitle = comboHelper.makeSectionHeader(
                resCtx.getString(R.string.section_home_custom_buttons));
        tvButtonsTitle.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        customHeader.addView(tvButtonsTitle);

        Button btnAdd = comboHelper.createCompactActionBtn(
                resCtx.getString(R.string.btn_add_home_button), true);
        customHeader.addView(btnAdd);
        root.addView(customHeader);

        LinearLayout buttonsList = new LinearLayout(activity);
        buttonsList.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams listLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        listLp.topMargin = comboHelper.dp(6);
        listLp.bottomMargin = comboHelper.dp(12);
        root.addView(buttonsList, listLp);

        // Visible Homepage Sections
        root.addView(comboHelper.makeSectionHeader(resCtx.getString(R.string.section_home_visible_panels)));

        MaterialSwitch swClimate = buildSectionSwitch(
                resCtx.getString(R.string.home_section_climate),
                configStore.isShowHomeClimate(),
                checked -> {
                    configStore.setShowHomeClimate(checked);
                    notifyLayoutChanged();
                });
        MaterialSwitch swAlerts = buildSectionSwitch(
                resCtx.getString(R.string.home_section_alerts),
                configStore.isShowHomeAlerts(),
                checked -> {
                    configStore.setShowHomeAlerts(checked);
                    notifyLayoutChanged();
                });
        MaterialSwitch swExtra = buildSectionSwitch(
                resCtx.getString(R.string.home_section_extra),
                !configStore.isHideExtraControls(),
                checked -> {
                    configStore.setHideExtraControls(!checked);
                    notifyLayoutChanged();
                });
        MaterialSwitch swLocation = buildSectionSwitch(
                resCtx.getString(R.string.home_section_location),
                configStore.isShowHomeLocation(),
                checked -> {
                    configStore.setShowHomeLocation(checked);
                    notifyLayoutChanged();
                });
        MaterialSwitch swHealth = buildSectionSwitch(
                resCtx.getString(R.string.home_section_health),
                configStore.isShowHomeHealth(),
                checked -> {
                    configStore.setShowHomeHealth(checked);
                    notifyLayoutChanged();
                });

        root.addView(swClimate);
        root.addView(swAlerts);
        root.addView(swExtra);
        root.addView(swLocation);
        root.addView(swHealth);

        final Runnable[] renderList = new Runnable[1];
        renderList[0] = () -> {
            buttonsList.removeAllViews();
            List<HomeButtonConfig> current = new ArrayList<>(configStore.getHomeCustomButtons());
            boolean canAdd = current.size() < HomeButtonConfig.MAX_BUTTONS;
            btnAdd.setEnabled(canAdd);
            btnAdd.setAlpha(canAdd ? 1f : 0.45f);

            if (current.isEmpty()) {
                TextView empty = new TextView(activity);
                empty.setText(resCtx.getString(R.string.home_custom_empty_hint));
                empty.setTextColor(ContextCompat.getColor(activity, R.color.text_muted));
                empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                empty.setPadding(comboHelper.dp(4), comboHelper.dp(6), comboHelper.dp(4), comboHelper.dp(6));
                buttonsList.addView(empty);
                return;
            }

            List<CustomActionCombo> combos = configStore.getActionCombos();
            VehicleStatus st = listener != null ? listener.getCurrentStatus() : null;
            for (int i = 0; i < current.size(); i++) {
                buttonsList.addView(buildManageButtonRow(resCtx, current, i, combos, st, renderList[0]));
            }
        };

        btnAdd.setOnClickListener(v -> {
            List<HomeButtonConfig> current = configStore.getHomeCustomButtons();
            if (current.size() >= HomeButtonConfig.MAX_BUTTONS) {
                Toast.makeText(activity,
                        resCtx.getString(R.string.home_button_max_toast, HomeButtonConfig.MAX_BUTTONS),
                        Toast.LENGTH_SHORT).show();
                return;
            }
            showButtonEditorDialog(null, renderList[0]);
        });

        renderList[0].run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(resCtx.getString(R.string.dialog_home_layout_title))
                .setView(scroll)
                .setPositiveButton(resCtx.getString(R.string.btn_done), null)
                .setNeutralButton(resCtx.getString(R.string.btn_reset_home_layout), (d, w) -> {
                    configStore.setShowHomeClimate(true);
                    configStore.setShowHomeAlerts(true);
                    configStore.setHideExtraControls(false);
                    configStore.setShowHomeLocation(true);
                    configStore.setShowHomeHealth(true);
                    notifyLayoutChanged();
                    Toast.makeText(activity, resCtx.getString(R.string.home_layout_reset_toast), Toast.LENGTH_SHORT).show();
                })
                .create();
        dialog.show();
    }

    private View buildManageButtonRow(
            Context resCtx,
            List<HomeButtonConfig> buttons,
            int index,
            List<CustomActionCombo> combos,
            @Nullable VehicleStatus st,
            Runnable rerender
    ) {
        HomeButtonConfig btn = buttons.get(index);
        VehicleWidgetProvider.SlotVisual visual = resolveButtonVisual(resCtx, btn, st, combos);

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundResource(R.drawable.bg_panel_inner);
        row.setPadding(comboHelper.dp(10), comboHelper.dp(8), comboHelper.dp(8), comboHelper.dp(8));
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.bottomMargin = comboHelper.dp(6);
        row.setLayoutParams(rowLp);

        FrameLayout badge = new FrameLayout(activity);
        badge.setBackgroundResource(visual.bgRes);
        LinearLayout.LayoutParams bLp = new LinearLayout.LayoutParams(comboHelper.dp(34), comboHelper.dp(34));
        bLp.setMarginEnd(comboHelper.dp(10));
        badge.setLayoutParams(bLp);
        ImageView iv = new ImageView(activity);
        iv.setImageResource(visual.iconRes);
        iv.setColorFilter(ContextCompat.getColor(activity, visual.textColorRes));
        badge.addView(iv, new FrameLayout.LayoutParams(
                comboHelper.dp(18), comboHelper.dp(18), Gravity.CENTER));
        row.addView(badge);

        LinearLayout textCol = new LinearLayout(activity);
        textCol.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        textLp.setMarginEnd(comboHelper.dp(6));
        textCol.setLayoutParams(textLp);

        TextView tvTitle = new TextView(activity);
        tvTitle.setText(visual.label);
        tvTitle.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setMaxLines(1);
        tvTitle.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvTitle);

        TextView tvSub = new TextView(activity);
        tvSub.setText(formatButtonTargetSummary(resCtx, btn, combos));
        tvSub.setTextColor(ContextCompat.getColor(activity, R.color.text_secondary));
        tvSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tvSub.setMaxLines(1);
        tvSub.setEllipsize(TextUtils.TruncateAt.END);
        textCol.addView(tvSub);
        row.addView(textCol);

        if (index > 0) {
            Button btnUp = comboHelper.createCompactActionBtn("↑", false);
            LinearLayout.LayoutParams uLp = (LinearLayout.LayoutParams) btnUp.getLayoutParams();
            uLp.setMarginEnd(comboHelper.dp(3));
            btnUp.setOnClickListener(v -> {
                List<HomeButtonConfig> updated = new ArrayList<>(buttons);
                HomeButtonConfig moved = updated.remove(index);
                updated.add(index - 1, moved);
                configStore.setHomeCustomButtons(updated);
                notifyLayoutChanged();
                rerender.run();
            });
            row.addView(btnUp);
        }

        if (index < buttons.size() - 1) {
            Button btnDown = comboHelper.createCompactActionBtn("↓", false);
            LinearLayout.LayoutParams dLp = (LinearLayout.LayoutParams) btnDown.getLayoutParams();
            dLp.setMarginEnd(comboHelper.dp(3));
            btnDown.setOnClickListener(v -> {
                List<HomeButtonConfig> updated = new ArrayList<>(buttons);
                HomeButtonConfig moved = updated.remove(index);
                updated.add(index + 1, moved);
                configStore.setHomeCustomButtons(updated);
                notifyLayoutChanged();
                rerender.run();
            });
            row.addView(btnDown);
        }

        Button btnEdit = comboHelper.createCompactActionBtn(resCtx.getString(R.string.btn_edit_combo), false);
        LinearLayout.LayoutParams eLp = (LinearLayout.LayoutParams) btnEdit.getLayoutParams();
        eLp.setMarginEnd(comboHelper.dp(3));
        btnEdit.setOnClickListener(v -> showButtonEditorDialog(btn, rerender));
        row.addView(btnEdit);

        Button btnDel = comboHelper.createCompactActionBtn("✕", false);
        btnDel.setOnClickListener(v -> {
            List<HomeButtonConfig> updated = new ArrayList<>(buttons);
            updated.remove(index);
            configStore.setHomeCustomButtons(updated);
            notifyLayoutChanged();
            rerender.run();
        });
        row.addView(btnDel);

        return row;
    }

    public void showButtonEditorDialog(@Nullable HomeButtonConfig existing, @Nullable Runnable onSaved) {
        if (configStore == null || activity.isFinishing()) return;
        Context resCtx = AppLocaleHelper.localizedContext(activity, configStore);

        final boolean[] comboMode = { existing != null && existing.isCombo() };
        final String[] selectedAction = {
                (existing != null && !existing.isCombo()) ? existing.targetId : WidgetSlotConfig.SINGLE_LIGHTS
        };
        final String[] selectedComboId = {
                (existing != null && existing.isCombo()) ? existing.targetId : ""
        };
        final String[] selectedTone = {
                (existing != null && !existing.tone.isEmpty())
                        ? existing.tone
                        : CustomActionCombo.TONE_EMERALD
        };
        final String[] selectedIcon = {
                (existing != null && !existing.iconKey.isEmpty())
                        ? existing.iconKey
                        : CustomActionCombo.ICON_BOLT
        };

        ScrollView scroll = new ScrollView(activity);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(comboHelper.dp(18), comboHelper.dp(14), comboHelper.dp(18), comboHelper.dp(10));
        scroll.addView(root);

        // Label field
        root.addView(comboHelper.makeSectionHeader(resCtx.getString(R.string.label_home_button_label)));
        EditText etLabel = new EditText(activity);
        etLabel.setHint(resCtx.getString(R.string.hint_home_button_label));
        etLabel.setSingleLine(true);
        etLabel.setFilters(new InputFilter[]{ new InputFilter.LengthFilter(HomeButtonConfig.MAX_LABEL_LENGTH) });
        if (existing != null && !existing.label.isEmpty()) {
            etLabel.setText(existing.label);
        }
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lblLp.bottomMargin = comboHelper.dp(10);
        root.addView(etLabel, lblLp);

        // Type toggle: Single Action | Action Combo
        LinearLayout modeRow = new LinearLayout(activity);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.bottomMargin = comboHelper.dp(10);
        root.addView(modeRow, modeLp);

        LinearLayout pickerBody = new LinearLayout(activity);
        pickerBody.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bodyLp.bottomMargin = comboHelper.dp(10);
        root.addView(pickerBody, bodyLp);

        // Appearance pickers (color & icon)
        root.addView(comboHelper.makeSectionHeader(resCtx.getString(R.string.label_combo_color)));
        LinearLayout toneRow = new LinearLayout(activity);
        toneRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams toneLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toneLp.bottomMargin = comboHelper.dp(10);
        root.addView(toneRow, toneLp);

        root.addView(comboHelper.makeSectionHeader(resCtx.getString(R.string.label_combo_icon)));
        LinearLayout iconGrid = new LinearLayout(activity);
        iconGrid.setOrientation(LinearLayout.VERTICAL);
        root.addView(iconGrid);

        final Runnable[] renderAll = new Runnable[1];
        renderAll[0] = () -> {
            modeRow.removeAllViews();
            Button btnSingleTab = comboHelper.createCompactActionBtn(
                    resCtx.getString(R.string.tab_single_action), !comboMode[0]);
            LinearLayout.LayoutParams sLp = new LinearLayout.LayoutParams(0, comboHelper.dp(34), 1f);
            sLp.setMarginEnd(comboHelper.dp(6));
            btnSingleTab.setLayoutParams(sLp);
            btnSingleTab.setOnClickListener(v -> {
                comboMode[0] = false;
                renderAll[0].run();
            });
            modeRow.addView(btnSingleTab);

            Button btnComboTab = comboHelper.createCompactActionBtn(
                    resCtx.getString(R.string.tab_action_combo), comboMode[0]);
            btnComboTab.setLayoutParams(new LinearLayout.LayoutParams(0, comboHelper.dp(34), 1f));
            btnComboTab.setOnClickListener(v -> {
                comboMode[0] = true;
                List<CustomActionCombo> combos = configStore.getActionCombos();
                if (selectedComboId[0].isEmpty() && !combos.isEmpty()) {
                    selectedComboId[0] = combos.get(0).id;
                    selectedTone[0] = combos.get(0).tone;
                    selectedIcon[0] = combos.get(0).iconKey;
                }
                renderAll[0].run();
            });
            modeRow.addView(btnComboTab);

            pickerBody.removeAllViews();
            if (comboMode[0]) {
                LinearLayout comboHeader = new LinearLayout(activity);
                comboHeader.setOrientation(LinearLayout.HORIZONTAL);
                comboHeader.setGravity(Gravity.CENTER_VERTICAL);
                TextView tvSec = comboHelper.makeSectionHeader(resCtx.getString(R.string.section_your_combos));
                tvSec.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                comboHeader.addView(tvSec);
                Button btnNewCombo = comboHelper.createCompactActionBtn(
                        resCtx.getString(R.string.btn_new_combo), true);
                btnNewCombo.setOnClickListener(v -> comboHelper.showComboEditorDialog(null, -1, saved -> {
                    selectedComboId[0] = saved.id;
                    selectedTone[0] = saved.tone;
                    selectedIcon[0] = saved.iconKey;
                    if (etLabel.getText() == null || etLabel.getText().toString().trim().isEmpty()) {
                        etLabel.setText(saved.name);
                    }
                    renderAll[0].run();
                }));
                comboHeader.addView(btnNewCombo);
                pickerBody.addView(comboHeader);

                List<CustomActionCombo> combos = configStore.getActionCombos();
                if (combos.isEmpty()) {
                    TextView empty = new TextView(activity);
                    empty.setText(resCtx.getString(R.string.combo_empty_hint));
                    empty.setTextColor(ContextCompat.getColor(activity, R.color.text_muted));
                    empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                    empty.setPadding(0, comboHelper.dp(4), 0, comboHelper.dp(6));
                    pickerBody.addView(empty);
                } else {
                    for (CustomActionCombo c : combos) {
                        boolean active = c.id.equals(selectedComboId[0]);
                        View row = comboHelper.buildComboPickerRow(resCtx, c, active);
                        row.setOnClickListener(v -> {
                            selectedComboId[0] = c.id;
                            selectedTone[0] = c.tone;
                            selectedIcon[0] = c.iconKey;
                            if (etLabel.getText() == null || etLabel.getText().toString().trim().isEmpty()) {
                                etLabel.setText(c.name);
                            }
                            renderAll[0].run();
                        });
                        pickerBody.addView(row);
                    }
                }
            } else {
                pickerBody.addView(comboHelper.makeSectionHeader(
                        resCtx.getString(R.string.section_single_actions)));
                List<String> actions = WidgetSlotConfig.ALL_SINGLE_ACTIONS;
                for (int r = 0; r < actions.size(); r += 3) {
                    LinearLayout rowLayout = new LinearLayout(activity);
                    rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rLp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    rLp.bottomMargin = comboHelper.dp(6);
                    rowLayout.setLayoutParams(rLp);

                    for (int c = 0; c < 3; c++) {
                        int idx = r + c;
                        if (idx >= actions.size()) {
                            rowLayout.addView(new View(activity),
                                    new LinearLayout.LayoutParams(0, comboHelper.dp(64), 1f));
                            continue;
                        }
                        String actionKey = actions.get(idx);
                        boolean active = actionKey.equals(selectedAction[0]);
                        View tile = comboHelper.buildSingleActionTile(resCtx, actionKey, active);
                        LinearLayout.LayoutParams tLp = new LinearLayout.LayoutParams(0, comboHelper.dp(66), 1f);
                        if (c < 2) tLp.setMarginEnd(comboHelper.dp(6));
                        tile.setLayoutParams(tLp);
                        tile.setOnClickListener(v -> {
                            selectedAction[0] = actionKey;
                            renderAll[0].run();
                        });
                        rowLayout.addView(tile);
                    }
                    pickerBody.addView(rowLayout);
                }
            }

            comboHelper.renderToneAndIconPickers(
                    toneRow, iconGrid, selectedTone, selectedIcon, renderAll[0]);
        };

        renderAll[0].run();

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(resCtx.getString(existing == null
                        ? R.string.dialog_add_home_button_title
                        : R.string.dialog_edit_home_button_title))
                .setView(scroll)
                .setPositiveButton(resCtx.getString(R.string.btn_save), null)
                .setNegativeButton(resCtx.getString(R.string.btn_cancel), null)
                .create();
        dialog.show();

        Button pos = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (pos != null) {
            pos.setOnClickListener(v -> {
                String type = comboMode[0] ? HomeButtonConfig.TYPE_COMBO : HomeButtonConfig.TYPE_SINGLE;
                String targetId = comboMode[0] ? selectedComboId[0] : selectedAction[0];
                if (comboMode[0] && (targetId == null || targetId.trim().isEmpty())) {
                    Toast.makeText(activity,
                            resCtx.getString(R.string.home_button_select_action_toast),
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                String label = etLabel.getText() != null ? etLabel.getText().toString().trim() : "";
                HomeButtonConfig saved = new HomeButtonConfig(
                        existing != null ? existing.id : HomeButtonConfig.newId(),
                        type,
                        targetId,
                        label,
                        selectedIcon[0],
                        selectedTone[0]
                );
                upsertHomeButton(saved);
                dialog.dismiss();
                if (onSaved != null) onSaved.run();
            });
        }
    }

    private void upsertHomeButton(HomeButtonConfig button) {
        List<HomeButtonConfig> current = new ArrayList<>(configStore.getHomeCustomButtons());
        boolean replaced = false;
        for (int i = 0; i < current.size(); i++) {
            if (current.get(i).id.equals(button.id)) {
                current.set(i, button);
                replaced = true;
                break;
            }
        }
        if (!replaced && current.size() < HomeButtonConfig.MAX_BUTTONS) {
            current.add(button);
        }
        configStore.setHomeCustomButtons(current);
        notifyLayoutChanged();
    }

    private MaterialSwitch buildSectionSwitch(String label, boolean initial, SwitchListener onChange) {
        MaterialSwitch sw = new MaterialSwitch(activity);
        sw.setText(label);
        sw.setChecked(initial);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        sw.setTextColor(ContextCompat.getColor(activity, R.color.text_primary));
        sw.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (onChange != null) onChange.onChanged(isChecked);
        });
        return sw;
    }

    private interface SwitchListener {
        void onChanged(boolean checked);
    }

    private VehicleWidgetProvider.SlotVisual resolveButtonVisual(
            Context resCtx,
            HomeButtonConfig btn,
            @Nullable VehicleStatus st,
            List<CustomActionCombo> combos
    ) {
        VehicleWidgetProvider.SlotVisual base = VehicleWidgetProvider.resolveSlotVisual(
                resCtx,
                new WidgetSlotConfig(btn.type, btn.targetId, btn.label),
                st,
                combos
        );
        int iconRes = !btn.iconKey.isEmpty()
                ? VehicleWidgetProvider.iconResForKey(btn.iconKey)
                : base.iconRes;
        int bgRes = !btn.tone.isEmpty()
                ? VehicleWidgetProvider.bgResForTone(btn.tone)
                : base.bgRes;
        int textColorRes = !btn.tone.isEmpty()
                ? VehicleWidgetProvider.textColorResForTone(btn.tone)
                : base.textColorRes;
        return new VehicleWidgetProvider.SlotVisual(base.label, iconRes, bgRes, textColorRes);
    }

    private String formatButtonTargetSummary(
            Context resCtx,
            HomeButtonConfig btn,
            List<CustomActionCombo> combos
    ) {
        if (btn.isCombo()) {
            CustomActionCombo matched = findComboInList(combos, btn.targetId);
            return matched != null
                    ? comboHelper.formatComboSummary(resCtx, matched)
                    : resCtx.getString(R.string.combo_missing_banner);
        }
        VehicleWidgetProvider.SlotVisual v = VehicleWidgetProvider.resolveSlotVisual(
                resCtx,
                WidgetSlotConfig.single(btn.targetId),
                listener != null ? listener.getCurrentStatus() : null,
                combos
        );
        return resCtx.getString(R.string.tab_single_action) + " · " + v.label;
    }

    @Nullable
    private CustomActionCombo findComboById(String comboId) {
        return findComboInList(configStore != null ? configStore.getActionCombos() : null, comboId);
    }

    @Nullable
    private static CustomActionCombo findComboInList(
            @Nullable List<CustomActionCombo> combos,
            @Nullable String comboId
    ) {
        if (combos == null || comboId == null) return null;
        for (CustomActionCombo c : combos) {
            if (comboId.equals(c.id)) {
                return c;
            }
        }
        return null;
    }

    private void notifyLayoutChanged() {
        renderCustomButtonsGrid();
        if (listener != null) {
            listener.onLayoutConfigChanged();
        }
    }

    @Nullable
    private Drawable resolveSelectableForeground() {
        TypedArray ta = activity.obtainStyledAttributes(new int[]{ android.R.attr.selectableItemBackground });
        try {
            return ta.getDrawable(0);
        } finally {
            ta.recycle();
        }
    }
}
