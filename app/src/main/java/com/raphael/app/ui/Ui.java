package com.raphael.app.ui;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.TextView;

/** Renkler, boyutlar ve küçük görünüm yardımcıları (Claude mobil arayüzüne benzer sıcak tema). */
public final class Ui {
    private Ui() {}
    public static float density = 3f;
    public static float fontScale = 1f;

    public static final int BG = 0xFFFAF9F5, SURFACE = 0xFFFFFFFF, BORDER = 0xFFE8E6DC, TEXT = 0xFF1F1E1D, MUTED = 0xFF8A877F,
            BUBBLE = 0xFFF0EEE6, ACCENT = 0xFFD97757, CODEBG = 0xFFF5F4EE, INLINE_CODE = 0xFFEFEDE4,
            GREEN = 0xFF3E9B5F, RED = 0xFFD64B4B, AMBER = 0xFFD9A441, DISABLED = 0xFFD8D5CC;

    public static void init(Context c) { density = c.getResources().getDisplayMetrics().density; }
    public static int dp(float v) { return Math.round(v * density); }
    public static float sp(float v) { return v * fontScale; }

    public static GradientDrawable round(int color, float radiusDp, int stroke, float strokeDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) g.setStroke(Math.max(1, dp(strokeDp)), stroke);
        return g;
    }

    public static Drawable ripple(Drawable bg) {
        return new RippleDrawable(ColorStateList.valueOf(0x22000000), bg, null);
    }

    public static ImageView icon(Context c, int res, int sizeDp, int tint) {
        ImageView v = new ImageView(c);
        v.setImageResource(res);
        v.setColorFilter(tint);
        v.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        v.setLayoutParams(new android.widget.LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        return v;
    }

    public static TextView text(Context c, CharSequence s, float sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(sp(sp));
        t.setTextColor(color);
        t.setIncludeFontPadding(false);
        return t;
    }

    public static TextView pill(Context c, CharSequence s, float sp, int color, int bg, int strokeColor) {
        TextView t = text(c, s, sp, color);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(11), dp(7), dp(11), dp(7));
        t.setBackground(ripple(round(bg, 16, strokeColor, strokeColor == 0 ? 0 : 1)));
        return t;
    }

    public static Typeface mono() { return Typeface.MONOSPACE; }
}
