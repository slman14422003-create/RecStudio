package com.rec.app;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.util.Locale;

public final class SpeechActivity extends AppCompatActivity {

    private Store store;
    private LinearLayout list;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String signature = "";
    private long seenDone;
    private long seenError;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            poll();
            handler.postDelayed(this, 400);
        }
    };

    private int c(int attr) { return Ui.attrColor(this, attr); }
    private int dp(int v) { return Ui.dp(this, v); }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new Store(this);
        seenDone = SpeechService.doneSeq();
        seenError = SpeechService.errorSeq();

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(c(R.attr.cBg));
        root.setFitsSystemWindows(true);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(8), dp(16), dp(4));
        ImageButton back = Ui.iconButton(this, R.drawable.ic_back, R.string.action_back);
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        root.addView(bar);
        root.addView(Ui.largeTitle(this, R.string.speech_title));

        ScrollView scroll = new ScrollView(this);
        scroll.setVerticalScrollBarEnabled(false);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(4), dp(16), dp(28));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        signature = "";
        handler.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(tick);
    }

    private void poll() {
        if (SpeechService.doneSeq() != seenDone) {
            seenDone = SpeechService.doneSeq();
            if (SpeechService.doneType() == SpeechService.JOB_DOWNLOAD && !SpeechService.doneCancelled()) {
                Toast.makeText(this, R.string.speech_ready, Toast.LENGTH_LONG).show();
            }
        }
        if (SpeechService.errorSeq() != seenError) {
            seenError = SpeechService.errorSeq();
            Toast.makeText(this, getString(SpeechService.errorRes()) + "\n" + SpeechService.errorDetail(), Toast.LENGTH_LONG).show();
        }

        StringBuilder sig = new StringBuilder();
        sig.append(Models.currentIndex(this, store)).append('|');
        boolean busy = SpeechService.busy() && SpeechService.type() == SpeechService.JOB_DOWNLOAD;
        sig.append(busy ? SpeechService.key() : "-").append('|');
        sig.append(busy ? Math.round(SpeechService.progress() * 100f) : -1).append('|');
        for (Models.Spec s : Models.ALL) sig.append(Models.installed(this, s) ? '1' : '0');
        String now = sig.toString();
        if (!now.equals(signature)) {
            signature = now;
            render();
        }
    }

    private void render() {
        list.removeAllViews();

        LinearLayout intro = Ui.card(this);
        intro.setPadding(dp(18), dp(16), dp(18), dp(16));
        TextView head = new TextView(this);
        head.setText(R.string.speech_intro_title);
        head.setTextSize(17);
        head.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        head.setTextColor(c(R.attr.cTextPrimary));
        intro.addView(head);
        TextView body = new TextView(this);
        body.setText(R.string.speech_intro);
        body.setTextSize(14);
        body.setTextColor(c(R.attr.cTextSecondary));
        body.setLineSpacing(0, 1.15f);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.topMargin = dp(8);
        intro.addView(body, blp);
        list.addView(intro, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Ui.groupTitle(list, R.string.speech_models);

        String[] names = getResources().getStringArray(R.array.speech_model_names);
        int current = Models.currentIndex(this, store);
        boolean busy = SpeechService.busy();
        for (int i = 0; i < Models.ALL.length; i++) {
            list.addView(modelCard(i, names[i], current, busy));
        }

        TextView foot = new TextView(this);
        foot.setText(R.string.speech_footer);
        foot.setTextSize(13);
        foot.setTextColor(c(R.attr.cTextHint));
        foot.setPadding(dp(8), dp(14), dp(8), 0);
        list.addView(foot);
    }

    private View modelCard(final int index, String name, int current, boolean busy) {
        final Models.Spec spec = Models.ALL[index];
        boolean installed = Models.installed(this, spec);
        boolean downloadingThis = busy && SpeechService.type() == SpeechService.JOB_DOWNLOAD
                && String.valueOf(index).equals(SpeechService.key());

        LinearLayout card = Ui.card(this);
        card.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(10);
        card.setLayoutParams(clp);

        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(name);
        title.setTextSize(16);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        title.setTextColor(c(R.attr.cTextPrimary));
        top.addView(title, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (installed && index == current) {
            TextView active = Ui.tag(this, getString(R.string.speech_active));
            Ui.setTagState(active, true);
            active.setClickable(false);
            top.addView(active);
        }
        card.addView(top);

        TextView sub = new TextView(this);
        String size = getString(R.string.speech_size_fmt, spec.mb);
        sub.setText(spec.heavy ? size + "  \u00B7  " + getString(R.string.speech_heavy) : size);
        sub.setTextSize(13);
        sub.setTextColor(c(R.attr.cTextHint));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.topMargin = dp(3);
        card.addView(sub, slp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);

        if (downloadingThis) {
            float p = SpeechService.progress();
            ProgressBar pb = Ui.progressBar(this);
            pb.setIndeterminate(p < 0);
            if (p >= 0) pb.setProgress(Math.round(p * 100f));
            LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0, dp(6), 1f);
            plp.setMarginEnd(dp(12));
            actions.addView(pb, plp);
            TextView pct = new TextView(this);
            pct.setTextSize(13);
            pct.setTextColor(c(R.attr.cTextSecondary));
            pct.setText(p < 0 ? "" : (p > 0.92f
                    ? getString(R.string.speech_installing)
                    : String.format(Locale.US, "%d%%", Math.round(p * 100f / 0.92f))));
            actions.addView(pct);
            TextView cancel = Ui.tag(this, getString(R.string.cancel));
            cancel.setOnClickListener(v -> SpeechService.requestCancel(this));
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cl.setMarginStart(dp(10));
            actions.addView(cancel, cl);
        } else if (installed) {
            if (index != current) {
                TextView use = Ui.tag(this, getString(R.string.speech_use));
                Ui.setTagState(use, true);
                use.setOnClickListener(v -> {
                    store.setSpeechModel(index);
                    SpeechService.releaseModel();
                    signature = "";
                    poll();
                });
                actions.addView(use, tagLp());
            }
            TextView del = Ui.tag(this, getString(R.string.delete));
            del.setTextColor(getColor(R.color.bad));
            del.setOnClickListener(v -> Ui.confirm(this, R.string.speech_delete_title, R.string.speech_delete_message, R.string.delete, () -> {
                if (SpeechService.busy()) {
                    Toast.makeText(this, R.string.speech_busy, Toast.LENGTH_SHORT).show();
                    return;
                }
                SpeechService.releaseModel();
                Models.delete(this, spec);
                signature = "";
                poll();
            }));
            actions.addView(del, tagLp());
        } else {
            long partial = Models.partialBytes(this, spec);
            String label = partial > 0
                    ? getString(R.string.speech_resume_fmt, Fmt.size(partial))
                    : getString(R.string.speech_download);
            TextView dl = Ui.tag(this, label);
            Ui.setTagState(dl, true);
            dl.setOnClickListener(v -> askDownload(index, spec));
            actions.addView(dl, tagLp());
        }
        card.addView(actions, alp);
        return card;
    }

    private LinearLayout.LayoutParams tagLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dp(8));
        return lp;
    }

    private void askDownload(final int index, Models.Spec spec) {
        if (SpeechService.busy()) {
            Toast.makeText(this, R.string.speech_busy, Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.speech_download_title)
                .setMessage(getString(R.string.speech_download_message, spec.mb))
                .setPositiveButton(R.string.speech_download, (d, w) -> {
                    SpeechService.startDownload(this, index);
                    signature = "";
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }
}
