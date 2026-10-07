package me.ri3d.welle.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ScaleXSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Locale;

/**
 * Small view toolkit for the reference design. The artboards are 1280 x 720 design units;
 * one unit is {@link #u} pixels, chosen so the 720-unit height fills the window. Wider
 * windows (the 1920 x 720 head unit) keep the same unit and give the extra width to the
 * flexible columns, exactly like the flex layout of the reference.
 */
public final class Ui {
    public static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    public static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static Typeface sText, sBold, sCond, sMono, sMonoMed;

    public final Context c;
    public final Theme t;
    public final float u;

    public Ui(Context c, Theme t, float unit) {
        this.c = c;
        this.t = t;
        this.u = unit;
        if (sText == null) {
            sText = font(c, "Barlow-Medium.ttf", Typeface.DEFAULT);
            sBold = font(c, "Barlow-SemiBold.ttf", Typeface.DEFAULT_BOLD);
            sCond = font(c, "BarlowCondensed-SemiBold.ttf", Typeface.DEFAULT_BOLD);
            sMono = font(c, "IBMPlexMono-Regular.ttf", Typeface.MONOSPACE);
            sMonoMed = font(c, "IBMPlexMono-Medium.ttf", Typeface.MONOSPACE);
        }
    }

    private static Typeface font(Context c, String file, Typeface fallback) {
        try {
            return Typeface.createFromAsset(c.getAssets(), "fonts/" + file);
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /** Barlow 500. */
    public Typeface text() { return sText; }
    /** Barlow 600. */
    public Typeface bold() { return sBold; }
    /** Barlow Condensed 600. */
    public Typeface cond() { return sCond; }
    /** IBM Plex Mono 400. */
    public Typeface mono() { return sMono; }
    /** IBM Plex Mono 500. */
    public Typeface monoMed() { return sMonoMed; }

    public int px(float units) {
        int v = Math.round(units * u);
        return units > 0 && v == 0 ? 1 : v;
    }

    // ---- text --------------------------------------------------------------------------------

    public TextView label(CharSequence s, Typeface face, float size, int color) {
        TextView v = new TextView(c);
        v.setText(s);
        v.setTypeface(face);
        v.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * u);
        v.setTextColor(color);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    /** Upper-case mono label with 0.08em tracking: the design's "technical" text. */
    public TextView tech(String s, float size, int color) {
        TextView v = label("", sMonoMed, size, color);
        setTech(v, s);
        return v;
    }

    public void setTech(TextView v, String s) {
        String upper = s.toUpperCase(Locale.getDefault());
        if (Build.VERSION.SDK_INT >= 21) {
            v.setLetterSpacing(0.08f);
            v.setText(upper);
        } else {
            v.setText(spaced(upper, 0.08f));
        }
    }

    /**
     * Letter spacing for Android 4.x, which has no setLetterSpacing: a narrow no-break
     * space after every character. A mono space is 0.6 em wide, hence the scale.
     */
    static CharSequence spaced(String s, float em) {
        SpannableStringBuilder b = new SpannableStringBuilder();
        for (int i = 0; i < s.length(); i++) {
            b.append(s.charAt(i));
            if (i + 1 < s.length()) {
                b.append((char) 0x00A0);
                b.setSpan(new ScaleXSpan(em / 0.6f), b.length() - 1, b.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return b;
    }

    public EditText input(String hint, boolean url) {
        EditText e = new EditText(c);
        e.setTypeface(url ? sMono : sText);
        e.setTextSize(TypedValue.COMPLEX_UNIT_PX, (url ? 16 : 19) * u);
        e.setTextColor(t.text);
        e.setHintTextColor(t.dim);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setIncludeFontPadding(false);
        e.setGravity(Gravity.CENTER_VERTICAL);
        e.setPadding(px(16), 0, px(16), 0);
        e.setBackground(box(t.panel, t.lineStrong, 6));
        e.setInputType(url ? InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI
                : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        // In landscape the keyboard would otherwise replace the whole screen with its own text box.
        e.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_ACTION_DONE);
        return e;
    }

    // ---- layout ------------------------------------------------------------------------------

    public LinearLayout row(float gap) {
        return linear(LinearLayout.HORIZONTAL, gap);
    }

    public LinearLayout col(float gap) {
        return linear(LinearLayout.VERTICAL, gap);
    }

    private LinearLayout linear(int orientation, float gap) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(orientation);
        if (gap > 0) {
            GradientDrawable space = new GradientDrawable();
            space.setColor(0);
            space.setSize(px(gap), px(gap));
            l.setDividerDrawable(space);
            l.setShowDividers(LinearLayout.SHOW_DIVIDER_MIDDLE);
        }
        return l;
    }

    public LinearLayout.LayoutParams lp(float w, float h) {
        return new LinearLayout.LayoutParams(size(w), size(h));
    }

    /** Flexible child: takes the remaining space along the layout's axis. */
    public LinearLayout.LayoutParams flex(float w, float h) {
        return new LinearLayout.LayoutParams(size(w), size(h), 1f);
    }

    private int size(float v) {
        return v == MATCH || v == WRAP ? (int) v : px(v);
    }

    public View spacer() {
        return new View(c);
    }

    public void pad(View v, float l, float top, float r, float b) {
        v.setPadding(px(l), px(top), px(r), px(b));
    }

    // ---- surfaces ----------------------------------------------------------------------------

    public GradientDrawable box(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        if (stroke != 0) d.setStroke(px(1), stroke);
        d.setCornerRadius(px(radius));
        return d;
    }

    public GradientDrawable dashed(int stroke, float radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(0);
        d.setStroke(px(1), stroke, px(5), px(4));
        d.setCornerRadius(px(radius));
        return d;
    }

    /** A box that visibly reacts to touch, for anything clickable. */
    public Drawable button(int fill, int stroke, float radius) {
        StateListDrawable s = new StateListDrawable();
        int base = fill == 0 ? t.bg : fill;
        s.addState(new int[]{android.R.attr.state_pressed}, box(blend(base, t.text, 0.16f), stroke == 0 ? 0 : blend(stroke, t.text, 0.3f), radius));
        s.addState(new int[]{}, box(fill, stroke, radius));
        return s;
    }

    /** Standard list/control surface: panel fill, hairline border, 6-unit radius. */
    public Drawable panelButton() {
        return button(t.panel, t.line, 6);
    }

    public Drawable outlineButton() {
        return button(0, t.lineStrong, 6);
    }

    public Drawable accentButton() {
        return button(t.accent, 0, 6);
    }

    /** Selected or unselected state of a filter/navigation/mode button. */
    public Drawable choice(boolean selected) {
        return selected ? button(t.accent, t.selectedRing, 6) : panelButton();
    }

    public static int blend(int a, int b, float k) {
        int ar = (a >> 16) & 0xff, ag = (a >> 8) & 0xff, ab = a & 0xff;
        int br = (b >> 16) & 0xff, bg = (b >> 8) & 0xff, bb = b & 0xff;
        return 0xFF000000 | ((int) (ar + (br - ar) * k) << 16) | ((int) (ag + (bg - ag) * k) << 8) | (int) (ab + (bb - ab) * k);
    }
}
