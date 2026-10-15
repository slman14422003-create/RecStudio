package com.rec.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public final class RecordingAdapter extends RecyclerView.Adapter<RecordingAdapter.VH> {

    public interface Listener {
        void onOpen(Recording r);
        void onMenu(Recording r);
    }

    private final Context ctx;
    private final Listener listener;
    private List<Recording> items = new ArrayList<>();

    public RecordingAdapter(Context ctx, Listener listener) {
        this.ctx = ctx;
        this.listener = listener;
    }

    public void submit(List<Recording> list) {
        items = list;
        notifyDataSetChanged();
    }

    public Recording get(int position) {
        return items.get(position);
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
        mlp.topMargin = Ui.dp(ctx, 2);
        card.addView(meta, mlp);

        WaveformView wave = new WaveformView(ctx);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(ctx, 30));
        wlp.topMargin = Ui.dp(ctx, 10);
        card.addView(wave, wlp);

        return new VH(card, title, star, duration, meta, wave);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        Recording r = items.get(position);
        h.title.setText(r.title);
        h.star.setVisibility(r.favorite ? View.VISIBLE : View.GONE);
        h.duration.setText(Fmt.duration(r.durationMs));
        CharSequence when = DateUtils.getRelativeDateTimeString(ctx, r.createdAt,
                DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0);
        StringBuilder sb = new StringBuilder(when);
        sb.append("  \u00B7  ").append(Fmt.size(r.size(ctx)));
        if (!r.markers.isEmpty()) sb.append("  \u00B7  ").append(ctx.getResources().getString(R.string.markers_short, r.markers.size()));
        h.meta.setText(sb);
        h.wave.setPeaks(r.peaksAsFloat());
        h.itemView.setOnClickListener(v -> listener.onOpen(r));
        h.itemView.setOnLongClickListener(v -> {
            listener.onMenu(r);
            return true;
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView title;
        final ImageView star;
        final TextView duration;
        final TextView meta;
        final WaveformView wave;

        VH(View itemView, TextView title, ImageView star, TextView duration, TextView meta, WaveformView wave) {
            super(itemView);
            this.title = title;
            this.star = star;
            this.duration = duration;
            this.meta = meta;
            this.wave = wave;
        }
    }
}
