package com.rec.app;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.widget.TextViewCompat;

import java.util.function.Consumer;

public final class Ui {

    private Ui() {}

    public static int dp(Context c, int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.getResources().getDisplayMetrics());
    }

    public static int attrColor(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    public static void autoFit(TextView t, int minSp, int maxSp) {
        t.setSingleLine(true);
        t.setMaxLines(1);
        TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(t, minSp, maxSp, 1, TypedValue.COMPLEX_UNIT_SP);
    }

    public static GradientDrawable round(int color, int radius, int stroke, int strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        if (strokeW > 0) g.setStroke(strokeW, stroke);
        return g;
    }

    public static void haptic(View v, Store store) {
        if (store.getHaptic()) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    public static TextView largeTitle(Context c, int res) {
        return largeTitle(c, c.getString(res));
    }

    public static TextView largeTitle(Context c, CharSequence text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(32);
        t.setMaxLines(2);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        t.setLetterSpacing(-0.01f);
        t.setTextColor(attrColor(c, R.attr.cTextPrimary));
        t.setPadding(dp(c, 22), dp(c, 2), dp(c, 22), dp(c, 4));
        return t;
    }

    public static LinearLayout card(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setBackgroundResource(R.drawable.bg_card);
        l.setClipToOutline(true);
        return l;
    }

    public static TextView small(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(13);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(attrColor(c, R.attr.cTextSecondary));
        return t;
    }

    public static TextView chip(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(14);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setClickable(true);
        t.setFocusable(true);
        setChipState(t, false);
        t.setPadding(dp(c, 16), 0, dp(c, 16), 0);
        return t;
    }

    public static void setChipState(TextView t, boolean selected) {
        Context c = t.getContext();
        int left = t.getPaddingLeft();
        int right = t.getPaddingRight();
        t.setBackgroundResource(selected ? R.drawable.bg_chip_selected : R.drawable.bg_chip_idle);
        t.setPadding(left, 0, right, 0);
        t.setTextColor(selected ? c.getColor(R.color.on_accent) : attrColor(c, R.attr.cTextPrimary));
        t.setTypeface(Typeface.create(selected ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
    }

    public static TextView tag(Context c, String text) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(c, 12), dp(c, 6), dp(c, 12), dp(c, 6));
        t.setClickable(true);
        t.setFocusable(true);
        setTagState(t, false);
        return t;
    }

    public static void setTagState(TextView t, boolean active) {
        Context c = t.getContext();
        int left = t.getPaddingLeft();
        int top = t.getPaddingTop();
        int right = t.getPaddingRight();
        int bottom = t.getPaddingBottom();
        t.setBackgroundResource(active ? R.drawable.bg_tag_active : R.drawable.bg_tag_idle);
        t.setPadding(left, top, right, bottom);
        t.setTextColor(active ? c.getColor(R.color.on_accent) : attrColor(c, R.attr.cTextSecondary));
    }

    public static void setTagRecording(TextView t) {
        Context c = t.getContext();
        int left = t.getPaddingLeft();
        int top = t.getPaddingTop();
        int right = t.getPaddingRight();
        int bottom = t.getPaddingBottom();
        t.setBackground(round(c.getColor(R.color.bad), dp(c, 9), 0, 0));
        t.setPadding(left, top, right, bottom);
        t.setTextColor(c.getColor(R.color.on_accent));
    }

    public static ImageView tile(Context c, int iconRes, int colorRes) {
        ImageView v = new ImageView(c);
        v.setImageResource(iconRes);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int p = dp(c, 5);
        v.setPadding(p, p, p, p);
        v.setBackground(round(c.getColor(colorRes), dp(c, 8), 0, 0));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(c, 30), dp(c, 30));
        lp.setMarginEnd(dp(c, 14));
        v.setLayoutParams(lp);
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return v;
    }

    public static View divider(Context c, int startMarginDp) {
        View d = new View(c);
        d.setBackgroundColor(attrColor(c, R.attr.cStrokeSoft));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lp.setMarginStart(dp(c, startMarginDp));
        d.setLayoutParams(lp);
        return d;
    }

    public static ImageButton iconButton(Context c, int drawableRes, int descRes) {
        ImageButton b = new ImageButton(c, null, 0, R.style.IconButton);
        b.setImageResource(drawableRes);
        b.setContentDescription(c.getString(descRes));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        return b;
    }

    public static ImageButton roundButton(Context c, int drawableRes, int descRes, int sizeDp) {
        ImageButton b = new ImageButton(c);
        b.setBackgroundResource(R.drawable.bg_round_field);
        b.setImageResource(drawableRes);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setImageTintList(ColorStateList.valueOf(attrColor(c, R.attr.cTextPrimary)));
        b.setContentDescription(c.getString(descRes));
        b.setStateListAnimator(android.animation.AnimatorInflater.loadStateListAnimator(c, R.animator.press_scale));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return b;
    }

    public static void groupTitle(LinearLayout parent, int titleRes) {
        Context c = parent.getContext();
        TextView t = small(c, c.getString(titleRes));
        t.setPadding(dp(c, 6), dp(c, 22), dp(c, 6), dp(c, 8));
        parent.addView(t);
    }

    public static void rename(Context c, String current, Consumer<String> onDone) {
        EditText input = new EditText(c);
        input.setSingleLine(true);
        input.setText(current);
        input.setSelectAllOnFocus(true);
        input.setTextSize(17);
        input.setTextColor(attrColor(c, R.attr.cTextPrimary));
        input.setHintTextColor(attrColor(c, R.attr.cTextHint));
        input.setBackground(round(attrColor(c, R.attr.cField), dp(c, 12), 0, 0));
        input.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
        FrameLayout box = new FrameLayout(c);
        box.setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0);
        box.addView(input, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog d = new AlertDialog.Builder(c)
                .setTitle(R.string.rename)
                .setView(box)
                .setPositiveButton(R.string.save, (x, w) -> {
                    String t = input.getText().toString().trim();
                    if (!t.isEmpty()) onDone.accept(t);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();
        if (d.getWindow() != null) {
            d.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        d.show();
    }

    public static void confirm(Context c, int titleRes, int messageRes, int actionRes, Runnable onConfirm) {
        new AlertDialog.Builder(c)
                .setTitle(titleRes)
                .setMessage(messageRes)
                .setPositiveButton(actionRes, (x, w) -> onConfirm.run())
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    public static void share(Context c, Recording r) {
        android.net.Uri u = Repo.uri(c, r);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mp4");
        i.putExtra(Intent.EXTRA_STREAM, u);
        i.putExtra(Intent.EXTRA_SUBJECT, r.title);
        i.setClipData(ClipData.newRawUri(r.title, u));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, c.getString(R.string.action_share)));
    }

    public static void saveToDevice(Context c, Recording r) {
        if (android.os.Build.VERSION.SDK_INT < 29) {
            share(c, r);
            return;
        }
        final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            final boolean ok = Repo.exportToDevice(app, r);
            main.post(() -> android.widget.Toast.makeText(app, ok ? R.string.saved_device : R.string.save_failed, android.widget.Toast.LENGTH_LONG).show());
        }).start();
    }

    public static void chooseLabel(Context c, Recording r, Runnable onDone) {
        String[] names = Labels.names(c);
        String[] items = new String[names.length + 1];
        items[0] = c.getString(R.string.label_none);
        System.arraycopy(names, 0, items, 1, names.length);
        new AlertDialog.Builder(c)
                .setTitle(R.string.label_title)
                .setSingleChoiceItems(items, r.label, (d, which) -> {
                    r.label = which;
                    Repo.save(c, r);
                    d.dismiss();
                    if (onDone != null) onDone.run();
                })
                .show();
    }

    public static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        v.setBackground(round(color, dp(c, sizeDp) / 2, 0, 0));
        v.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return v;
    }

    public static void shareMany(Context c, java.util.List<Recording> list) {
        if (list.isEmpty()) return;
        if (list.size() == 1) {
            share(c, list.get(0));
            return;
        }
        java.util.ArrayList<android.net.Uri> uris = new java.util.ArrayList<>();
        for (Recording r : list) uris.add(Repo.uri(c, r));
        Intent i = new Intent(Intent.ACTION_SEND_MULTIPLE);
        i.setType("audio/mp4");
        i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        ClipData clip = ClipData.newRawUri(list.get(0).title, uris.get(0));
        for (int k = 1; k < uris.size(); k++) clip.addItem(new ClipData.Item(uris.get(k)));
        i.setClipData(clip);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        c.startActivity(Intent.createChooser(i, c.getString(R.string.action_share)));
    }

    public static LinearLayout action(Context c, int iconRes, int labelRes, int tint) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        l.setGravity(Gravity.CENTER);
        l.setBackgroundResource(R.drawable.bg_row_flat);
        l.setClickable(true);
        l.setFocusable(true);
        l.setContentDescription(c.getString(labelRes));
        ImageView icon = new ImageView(c);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(tint));
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        l.addView(icon, new LinearLayout.LayoutParams(dp(c, 24), dp(c, 24)));
        TextView t = new TextView(c);
        t.setText(labelRes);
        t.setTextSize(11);
        t.setGravity(Gravity.CENTER);
        t.setSingleLine(true);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(tint);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.topMargin = dp(c, 4);
        l.addView(t, tlp);
        return l;
    }
}
