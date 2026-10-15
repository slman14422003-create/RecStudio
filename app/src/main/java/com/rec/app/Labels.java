package com.rec.app;

import android.content.Context;

public final class Labels {

    public static final int COUNT = 5;

    private static final int[] COLORS = {
            R.color.info, R.color.violet, R.color.teal, R.color.warn, R.color.pink
    };

    private Labels() {}

    public static int color(Context c, int label) {
        if (label < 1 || label > COUNT) return c.getColor(R.color.text_hint);
        return c.getColor(COLORS[label - 1]);
    }

    public static String[] names(Context c) {
        return c.getResources().getStringArray(R.array.label_names);
    }

    public static String name(Context c, int label) {
        String[] n = names(c);
        if (label < 1 || label > n.length) return c.getString(R.string.label_none);
        return n[label - 1];
    }
}
