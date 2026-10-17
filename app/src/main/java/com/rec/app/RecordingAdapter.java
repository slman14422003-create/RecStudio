package com.rec.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.VH> {

    public interface Listener {
        void onOpen(Recording r);
        void onSelectionChanged(int count, int total);
    }

    private final Context ctx;
    private final Listener listener;
    private final boolean trashMode;
    private final Set<String> selected = new HashSet<>();
    private boolean selecting;
    private List<Recording> items = new ArrayList<>();

    public RecordingAdapter(Context ctx, boolean trashMode, Listener listener) {
        this.ctx = ctx;
        this.trashMode = trashMode;
        this.listener = listener;
    }

    public void submit(List<Recording> list) {
        items = list;
        if (selecting) {
            Set<String> ids = new HashSet<>();
            for (Recording r : list) ids.add(r.id);
            selected.retainAll(ids);
        }
        notifyDataSetChanged();
        if (selecting) listener.onSelectionChanged(selected.size(), items.size());
    }

    public Recording get(int position) {
        return items.get(position);
    }

    public boolean isSelecting() {
        return selecting;
    }

    public void startSelection(String id) {
        selecting = true;
        selected.clear();
        if (id != null) selected.add(id);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size(), items.size());
    }

    public void stopSelection() {
        selecting = false;
        selected.clear();
        notifyDataSetChanged();
    }

    public void selectAll(boolean all) {
        selected.clear();
        if (all) for (Recording r : items) selected.add(r.id);
        notifyDataSetChanged();
        listener.onSelectionChanged(selected.size(), items.size());
    }

    public boolean allSelected() {
        return !items.isEmpty() && selected.size() == items.size();
    }

    public int selectedCount() {
        return selected.size();
    }

    public List<Recording> selectedItems() {
        List<Recording> out = new ArrayList<>();
        for (Recording r : items) if (selected.contains(r.id)) out.add(r);
        return out;
    }

    private void toggle(Recording r, int position) {
        if (!selected.remove(r.id)) selected.add(r.id);
        notifyItemChanged(position);
        listener.onSelectionChanged(selected.size(), items.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.bg_card);
        card.setForeground(ctx.getDrawable(R.drawable.bg_row_ripple));
        card.setClickable(true);
        card.setFocusable(true);
        card.setPadding(Ui.dp(ctx, 18), Ui.dp(ctx, 14), Ui.dp(ctx, 18), Ui.dp(ctx, 14));
        RecyclerView.LayoutParams rlp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, Ui.dp(ctx, 5), 0, Ui.dp(ctx, 5));
        card.setLayoutParams(rlp);

        LinearLayout top = new LinearLayout(ctx);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout check = new FrameLayout(ctx);
        ImageView tick = new ImageView(ctx);
        tick.setImageResource(R.drawable.ic_check);
        tick.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        check.addView(tick, new FrameLayout.LayoutParams(Ui.dp(ctx, 16), Ui.dp(ctx, 16), Gravity.CENTER));
        LinearLayout.LayoutParams chlp = new LinearLayout.LayoutParams(Ui.dp(ctx, 24), Ui.dp(ctx, 24));
        chlp.setMarginEnd(Ui.dp(ctx, 12));
        top.addView(check, chlp);

        View dot = new View(ctx);
        LinearLayout.LayoutParams dlp0 = new LinearLayout.LayoutParams(Ui.dp(ctx, 9), Ui.dp(ctx, 9));
        dlp0.setMarginEnd(Ui.dp(ctx, 8));
        top.addView(dot, dlp0);

        TextView title = new TextView(ctx);
        title.setTextSize(17);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(Ui.attrColor(ctx, R.attr.cTextPrimary));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView star = new ImageView(ctx);
        star.setImageResource(R.drawable.ic_star_fill);
        star.setImageTintList(ColorStateList.valueOf(ctx.getColor(R.color.warn)));
        star.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(Ui.dp(ctx, 18), Ui.dp(ctx, 18));
        slp.setMarginStart(Ui.dp(ctx, 8));
        top.addView(star, slp);

        TextView duration = new TextView(ctx);
        duration.setTextSize(15);
        duration.setSingleLine(true);
        duration.setTextColor(Ui.attrColor(ctx, R.attr.cAccentText));
        duration.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMarginStart(Ui.dp(ctx, 10));
        top.addView(duration, dlp);

        card.addView(top, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView meta = new TextView(ctx);
        meta.setTextSize(13);
        meta.setSingleLine(true);
        meta.setEllipsize(TextUtils.TruncateAt.END);
        meta.setTextColor(Ui.attrColor(ctx, R.attr.cTextHint));
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = Ui.dp(ctx, 3);
        card.addView(meta, mlp);

        WaveformView wave = new WaveformView(ctx);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 34));
        wlp.topMargin = Ui.dp(ctx, 10);
        card.addView(wave, wlp);

        return new VH(card, check, tick, dot, title, star, duration, meta, wave);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Recording r = items.get(position);
        boolean isSel = selecting && selected.contains(r.id);

        if (isSel) {
            h.itemView.setBackground(Ui.round(Ui.attrColor(ctx, R.attr.cAccentSoft), Ui.dp(ctx, 18), Ui.attrColor(ctx, R.attr.cAccent), Ui.dp(ctx, 2)));
        } else {
            h.itemView.setBackgroundResource(R.drawable.bg_card);
        }
        h.check.setVisibility(selecting ? View.VISIBLE : View.GONE);
        h.check.setBackgroundResource(isSel ? R.drawable.bg_check_on : R.drawable.bg_check_off);
        h.tick.setVisibility(isSel ? View.VISIBLE : View.INVISIBLE);

        h.title.setText(r.title);
        h.star.setVisibility(r.favorite && !trashMode ? View.VISIBLE : View.GONE);
        if (r.label > 0 && !trashMode) {
            h.dot.setVisibility(View.VISIBLE);
            h.dot.setBackground(Ui.round(Labels.color(ctx, r.label), Ui.dp(ctx, 5), 0, 0));
        } else {
            h.dot.setVisibility(View.GONE);
        }
        h.duration.setText(Fmt.duration(r.durationMs));

        StringBuilder sb = new StringBuilder();
        if (trashMode) {
            long left = Repo.TRASH_RETENTION_MS - (System.currentTimeMillis() - r.deletedAt);
            int days = (int) Math.max(0, (left + Repo.DAY_MS - 1) / Repo.DAY_MS);
            sb.append(ctx.getResources().getString(R.string.days_left_fmt, days));
        } else {
            sb.append(DateUtils.getRelativeDateTimeString(ctx, r.createdAt,
                    DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0));
        }
        sb.append("  \u00B7  ").append(Fmt.size(r.size(ctx)));
        if (r.mode != Store.M_STANDARD) {
            String[] modes = ctx.getResources().getStringArray(R.array.mode_options);
            if (r.mode < modes.length) sb.append("  \u00B7  ").append(modes[r.mode]);
        }
        if (!trashMode && Repo.hasTranscript(ctx, r.id)) sb.append("  \u00B7  ").append(ctx.getString(R.string.has_transcript));
        if (!r.markers.isEmpty()) sb.append("  \u00B7  ").append(ctx.getResources().getString(R.string.markers_short, r.markers.size()));
        h.meta.setText(sb);
        h.wave.setPeaks(r.peaksAsFloat());

        h.itemView.setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            if (selecting) toggle(items.get(pos), pos);
            else listener.onOpen(items.get(pos));
        });
        h.itemView.setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return true;
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            if (!selecting) startSelection(items.get(pos).id);
            else toggle(items.get(pos), pos);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final FrameLayout check;
        final ImageView tick;
        final View dot;
        final TextView title;
        final ImageView star;
        final TextView duration;
        final TextView meta;
        final WaveformView wave;

        VH(View itemView, FrameLayout check, ImageView tick, View dot, TextView title, ImageView star,
           TextView duration, TextView meta, WaveformView wave) {
            super(itemView);
            this.check = check;
            this.tick = tick;
            this.dot = dot;
            this.title = title;
            this.star = star;
            this.duration = duration;
            this.meta = meta;
            this.wave = wave;
        }
    }
}
