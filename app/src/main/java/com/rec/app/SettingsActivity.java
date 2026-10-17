package com.rec.app;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.os.LocaleListCompat;

public final class SettingsActivity extends AppCompatActivity {

    private static final int ROW_HEIGHT_DP = 56;
    private static final int DIVIDER_INSET_DP = 60;

    private Store store;
    private TextView engineValue;

    private interface IntGetter { int get(); }
    private interface IntSetter { void set(int i); }
    private interface OnToggle { void onChange(boolean checked); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Ui.attrColor(this, R.attr.cBg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 4));
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        root.addView(bar);
        root.addView(Ui.largeTitle(this, R.string.settings_title));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 16), Ui.dp(this, 28));
        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout g0 = group(list, R.string.group_language);
        final String[] langs = {getString(R.string.lang_system), getString(R.string.lang_arabic), getString(R.string.lang_english)};
        addRow(g0, choiceRow(R.drawable.ic_set_globe, R.color.info, R.string.settings_language, langs, this::currentLang, this::setLang));

        LinearLayout g1 = group(list, R.string.group_recording);
        final String[] quals = getResources().getStringArray(R.array.quality_options);
        addRow(g1, choiceRow(R.drawable.ic_set_quality, R.color.pink, R.string.settings_quality, quals, () -> store.getQuality(), i -> store.setQuality(i)));
        addRow(g1, toggleRow(R.drawable.ic_set_screen, R.color.warn, R.string.settings_keep_awake, store.getKeepAwake(), checked -> store.setKeepAwake(checked)));
        addRow(g1, toggleRow(R.drawable.ic_set_haptic, R.color.violet, R.string.settings_haptic, store.getHaptic(), checked -> store.setHaptic(checked)));

        footer(list, R.string.footer_recording);

        LinearLayout ga = group(list, R.string.group_audio);
        addRow(ga, toggleRow(R.drawable.ic_set_denoise, R.color.teal, R.string.settings_noise, store.getNoise(), checked -> store.setNoise(checked)));
        addRow(ga, toggleRow(R.drawable.ic_set_agc, R.color.indigo, R.string.settings_agc, store.getAgc(), checked -> store.setAgc(checked)));
        addRow(ga, toggleRow(R.drawable.ic_set_lowcut, R.color.info, R.string.settings_lowcut, store.getLowCut(), checked -> store.setLowCut(checked)));
        final String[] gains = getResources().getStringArray(R.array.gain_options);
        addRow(ga, choiceRow(R.drawable.ic_set_gain, R.color.warn, R.string.settings_gain, gains, () -> store.getGainIndex(), i -> store.setGainIndex(i)));
        footer(list, R.string.footer_audio);

        LinearLayout gs = group(list, R.string.group_speech);
        addRow(gs, linkRow(R.drawable.ic_set_speech, R.color.ok, R.string.settings_engine, () -> startActivity(new Intent(this, SpeechActivity.class))));
        addRow(gs, toggleRow(R.drawable.ic_set_clean, R.color.teal, R.string.settings_clean, store.getClean(), checked -> store.setClean(checked)));
        final String[] levels = getResources().getStringArray(R.array.clean_levels);
        addRow(gs, choiceRow(R.drawable.ic_set_denoise, R.color.indigo, R.string.settings_clean_level, levels, () -> store.getCleanLevel(), i -> store.setCleanLevel(i)));
        addRow(gs, toggleRow(R.drawable.ic_set_text, R.color.info, R.string.settings_auto_transcribe, store.getAutoTranscribe(), checked -> store.setAutoTranscribe(checked)));
        footer(list, R.string.footer_speech);

        LinearLayout g2 = group(list, R.string.group_playback);
        final int[] skipVals = {5, 10, 15, 30};
        final String[] skipOpts = new String[skipVals.length];
        for (int i = 0; i < skipVals.length; i++) skipOpts[i] = getString(R.string.seconds_fmt, skipVals[i]);
        addRow(g2, choiceRow(R.drawable.ic_set_skip, R.color.indigo, R.string.settings_skip, skipOpts, () -> indexOf(skipVals, store.getSkip()), i -> store.setSkip(skipVals[i])));

        LinearLayout g3 = group(list, R.string.group_library);
        final String[] sorts = getResources().getStringArray(R.array.sort_options);
        addRow(g3, choiceRow(R.drawable.ic_set_sort, R.color.teal, R.string.settings_sort, sorts, () -> store.getSort(), i -> store.setSort(i)));
        addRow(g3, infoRow(R.drawable.ic_set_storage, R.color.ok, R.string.settings_storage, Fmt.size(Repo.totalSize(this))));

        setContentView(root);
    }

    private LinearLayout group(LinearLayout parent, int titleRes) {
        TextView t = new TextView(this);
        t.setText(titleRes);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(Ui.attrColor(this, R.attr.cTextSecondary));
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 22), Ui.dp(this, 16), Ui.dp(this, 8));
        parent.addView(t);
        LinearLayout g = Ui.card(this);
        parent.addView(g, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return g;
    }

    private void footer(LinearLayout parent, int textRes) {
        TextView t = new TextView(this);
        t.setText(textRes);
        t.setTextSize(13);
        t.setTextColor(Ui.attrColor(this, R.attr.cTextHint));
        t.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), 0);
        parent.addView(t);
    }

    private void addRow(LinearLayout group, View row) {
        if (group.getChildCount() > 0) group.addView(Ui.divider(this, DIVIDER_INSET_DP));
        group.addView(row);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (engineValue != null) engineValue.setText(engineName());
    }

    private String engineName() {
        int idx = Models.currentIndex(this, store);
        if (idx < 0) return getString(R.string.engine_none);
        return getResources().getStringArray(R.array.speech_model_names)[idx];
    }

    private LinearLayout linkRow(int iconRes, int colorRes, int labelRes, Runnable action) {
        LinearLayout row = baseRow(iconRes, colorRes, labelRes);
        engineValue = new TextView(this);
        engineValue.setTextSize(15);
        engineValue.setSingleLine(true);
        engineValue.setEllipsize(android.text.TextUtils.TruncateAt.END);
        engineValue.setMaxWidth(Ui.dp(this, 170));
        engineValue.setTextColor(Ui.attrColor(this, R.attr.cTextSecondary));
        engineValue.setText(engineName());
        row.addView(engineValue);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_end);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        clp.setMarginStart(Ui.dp(this, 4));
        chev.setLayoutParams(clp);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chev);

        row.setBackgroundResource(R.drawable.bg_row_flat);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> action.run());
        return row;
    }

    private int currentLang() {
        LocaleListCompat l = AppCompatDelegate.getApplicationLocales();
        if (l.isEmpty()) return 0;
        String tag = l.toLanguageTags();
        return tag.startsWith("ar") ? 1 : 2;
    }

    private void setLang(int i) {
        String tag = i == 1 ? "ar" : (i == 2 ? "en" : "");
        AppCompatDelegate.setApplicationLocales(tag.isEmpty() ? LocaleListCompat.getEmptyLocaleList() : LocaleListCompat.forLanguageTags(tag));
    }

    private static int indexOf(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) if (arr[i] == v) return i;
        return 0;
    }

    private LinearLayout baseRow(int iconRes, int colorRes, int labelRes) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Ui.dp(this, ROW_HEIGHT_DP));
        row.setPadding(Ui.dp(this, 16), Ui.dp(this, 8), Ui.dp(this, 16), Ui.dp(this, 8));
        row.addView(Ui.tile(this, iconRes, colorRes));

        TextView label = new TextView(this);
        label.setText(labelRes);
        label.setTextSize(16);
        label.setTextColor(Ui.attrColor(this, R.attr.cTextPrimary));
        label.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(label);
        return row;
    }

    private LinearLayout choiceRow(int iconRes, int colorRes, int labelRes, String[] options, IntGetter getter, IntSetter setter) {
        LinearLayout row = baseRow(iconRes, colorRes, labelRes);

        TextView value = new TextView(this);
        value.setTextSize(16);
        value.setTextColor(Ui.attrColor(this, R.attr.cTextSecondary));
        value.setText(options[Math.max(0, Math.min(options.length - 1, getter.get()))]);
        row.addView(value);

        ImageView chev = new ImageView(this);
        chev.setImageResource(R.drawable.ic_chevron_end);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(Ui.dp(this, 20), Ui.dp(this, 20));
        clp.setMarginStart(Ui.dp(this, 4));
        chev.setLayoutParams(clp);
        chev.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(chev);

        row.setBackgroundResource(R.drawable.bg_row_flat);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle(labelRes)
                .setSingleChoiceItems(options, getter.get(), (d, which) -> {
                    setter.set(which);
                    value.setText(options[which]);
                    d.dismiss();
                })
                .show());
        return row;
    }

    private LinearLayout toggleRow(int iconRes, int colorRes, int labelRes, boolean initial, OnToggle onToggle) {
        LinearLayout row = baseRow(iconRes, colorRes, labelRes);

        SwitchCompat sw = new SwitchCompat(this);
        sw.setTrackDrawable(getDrawable(R.drawable.switch_track));
        sw.setThumbDrawable(getDrawable(R.drawable.switch_thumb));
        sw.setShowText(false);
        sw.setSwitchMinWidth(Ui.dp(this, 51));
        sw.setChecked(initial);
        sw.setOnCheckedChangeListener((btn, checked) -> onToggle.onChange(checked));
        row.addView(sw);

        row.setBackgroundResource(R.drawable.bg_row_flat);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> sw.toggle());
        return row;
    }

    private LinearLayout infoRow(int iconRes, int colorRes, int labelRes, String text) {
        LinearLayout row = baseRow(iconRes, colorRes, labelRes);
        TextView value = new TextView(this);
        value.setTextSize(16);
        value.setTextColor(Ui.attrColor(this, R.attr.cTextSecondary));
        value.setText(text);
        row.addView(value);
        return row;
    }
}
