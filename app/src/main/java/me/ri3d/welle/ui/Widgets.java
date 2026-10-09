package me.ri3d.welle.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.text.TextPaint;
import android.text.TextUtils;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Small custom-drawn views. Vector drawables need API 21, so the icons are Canvas paths. */
final class Widgets {
    private Widgets() { }
}

/** One of the design's stroke icons, drawn from its 24 x 24 SVG path data. */
final class IconView extends View {
    static final int SCAN = 0, LIST = 1, SETTINGS = 2, BACK = 3, CHEVRON = 4, PREV = 5, NEXT = 6, PLAY = 7, PAUSE = 8, CLOSE = 9, MINIMIZE = 10;

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF arc = new RectF();
    private int icon;
    private float glyph;
    private float stroke = 2f;

    /** @param glyphPx drawn size of the 24-unit icon; the view itself may be larger (touch target) */
    IconView(Context c, int icon, float glyphPx, int color) {
        super(c);
        this.icon = icon;
        this.glyph = glyphPx;
        paint.setColor(color);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    void setIcon(int icon) {
        if (this.icon != icon) {
            this.icon = icon;
            invalidate();
        }
    }

    void setStroke(float units) {
        stroke = units;
    }

    @Override
    protected void onDraw(Canvas c) {
        float k = glyph / 24f;
        c.save();
        c.translate((getWidth() - glyph) / 2f, (getHeight() - glyph) / 2f);
        c.scale(k, k);
        paint.setStrokeWidth(stroke);
        paint.setStyle(Paint.Style.STROKE);
        path.reset();
        switch (icon) {
            case SCAN:
                arc.set(4, 4, 20, 20);
                path.addArc(arc, 0, 315.5f);
                path.moveTo(20, 4);
                path.lineTo(20, 8);
                path.lineTo(16, 8);
                break;
            case LIST:
                line(4, 6, 20, 6);
                line(4, 12, 20, 12);
                line(4, 18, 20, 18);
                break;
            case SETTINGS:
                line(4, 7, 14, 7);
                line(18, 7, 20, 7);
                path.addCircle(16, 7, 2, Path.Direction.CW);
                line(4, 17, 6, 17);
                line(10, 17, 20, 17);
                path.addCircle(8, 17, 2, Path.Direction.CW);
                break;
            case BACK:
                path.moveTo(15, 5);
                path.lineTo(8, 12);
                path.lineTo(15, 19);
                break;
            case CHEVRON:
                path.moveTo(9, 5);
                path.lineTo(16, 12);
                path.lineTo(9, 19);
                break;
            case PREV:
                path.moveTo(19, 5);
                path.lineTo(19, 19);
                path.lineTo(8, 12);
                path.close();
                line(5, 5, 5, 19);
                break;
            case NEXT:
                path.moveTo(5, 5);
                path.lineTo(5, 19);
                path.lineTo(16, 12);
                path.close();
                line(19, 5, 19, 19);
                break;
            case PLAY:
                paint.setStyle(Paint.Style.FILL_AND_STROKE);
                path.moveTo(7, 4);
                path.lineTo(7, 20);
                path.lineTo(20, 12);
                path.close();
                break;
            case PAUSE:
                paint.setStrokeWidth(2.5f);
                line(8, 5, 8, 19);
                line(16, 5, 16, 19);
                break;
            case MINIMIZE:
                line(5, 18, 19, 18);
                break;
            default: // CLOSE
                line(6, 6, 18, 18);
                line(18, 6, 6, 18);
                break;
        }
        c.drawPath(path, paint);
        c.restore();
    }

    private void line(float x1, float y1, float x2, float y2) {
        path.moveTo(x1, y1);
        path.lineTo(x2, y2);
    }
}

/** The five signal bars. */
final class SignalView extends View {
    private final Ui ui;
    private final Paint paint = new Paint();
    private int bars;

    SignalView(Ui ui) {
        super(ui.c);
        this.ui = ui;
    }

    void setBars(int n) {
        if (n != bars) {
            bars = n;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(ui.px(5 * 6 + 4 * 4), ui.px(24));
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = ui.px(6), gap = ui.px(4);
        for (int i = 0; i < 5; i++) {
            paint.setColor(i < bars ? ui.t.mark : ui.t.barOff);
            float x = i * (w + gap);
            c.drawRect(x, getHeight() - ui.px(8 + i * 4), x + w, getHeight(), paint);
        }
    }
}

/** The design's rectangular switch. */
final class ToggleView extends View {
    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean on;

    ToggleView(Ui ui, boolean on) {
        super(ui.c);
        this.ui = ui;
        this.on = on;
    }

    void setOn(boolean v) {
        on = v;
        invalidate();
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(ui.px(60), ui.px(30));
    }

    @Override
    protected void onDraw(Canvas c) {
        float one = ui.px(1);
        r.set(one / 2, one / 2, getWidth() - one / 2, getHeight() - one / 2);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? ui.t.accent : ui.t.bg);
        c.drawRoundRect(r, ui.px(4), ui.px(4), paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(one);
        paint.setColor(on ? ui.t.accent : ui.t.lineStrong);
        c.drawRoundRect(r, ui.px(4), ui.px(4), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(on ? ui.t.onAccent : ui.t.dim);
        float x = ui.px(on ? 30 : 4);
        r.set(x, ui.px(5), x + ui.px(24), ui.px(25));
        c.drawRoundRect(r, ui.px(2), ui.px(2), paint);
    }
}

/**
 * The large station name. Uses the biggest of the design's sizes that fits the available
 * width, so long names stay readable instead of being cut off; only a name that does not
 * fit at the smallest size is ellipsised.
 */
final class NameView extends View {
    private final Ui ui;
    private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float[] sizes;
    private String text = "";
    private float size;

    /** @param sizes candidate sizes in design units, largest first */
    NameView(Ui ui, int color, float... sizes) {
        super(ui.c);
        this.ui = ui;
        this.sizes = sizes;
        paint.setTypeface(ui.cond());
        paint.setColor(color);
        size = sizes[0];
    }

    void setText(String s) {
        if (s == null) s = "";
        if (!s.equals(text)) {
            text = s;
            requestLayout();
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int wSpec, int hSpec) {
        int w = MeasureSpec.getSize(wSpec);
        size = sizes[sizes.length - 1];
        for (float s : sizes) {
            paint.setTextSize(s * ui.u);
            if (paint.measureText(text) <= w) {
                size = s;
                break;
            }
        }
        // The design's line-height of 0.95 puts the baseline at 0.875 em; the extra height
        // below it keeps descenders (g, p, y) from being cut off.
        setMeasuredDimension(w, Math.round(size * ui.u * 1.08f));
    }

    @Override
    protected void onDraw(Canvas c) {
        paint.setTextSize(size * ui.u);
        CharSequence shown = TextUtils.ellipsize(text, paint, getWidth(), TextUtils.TruncateAt.END);
        c.drawText(shown, 0, shown.length(), 0, size * ui.u * 0.875f, paint);
    }
}

/** Source tab ("DAB+", "WEB RADIO") with the 3-unit underline when active. */
final class TabLabel extends TextView {
    private final Ui ui;
    private final Paint paint = new Paint();
    private boolean active;

    TabLabel(Ui ui, String text) {
        super(ui.c);
        this.ui = ui;
        setTypeface(ui.monoMed());
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 16 * ui.u);
        setIncludeFontPadding(false);
        setGravity(android.view.Gravity.CENTER_VERTICAL);
        setPadding(ui.px(4), 0, ui.px(4), 0);
        ui.setTech(this, text);
        setActive(false);
    }

    void setActive(boolean a) {
        active = a;
        setTextColor(a ? ui.t.text : ui.t.dim);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (active) {
            paint.setColor(ui.t.mark);
            c.drawRect(0, getHeight() - ui.px(3), getWidth(), getHeight(), paint);
        }
    }
}

/** A container whose horizontal swipes page the presets while taps still reach the cells. */
final class SwipeLayout extends LinearLayout {
    interface OnSwipe {
        void onSwipe(int direction);
    }

    private final int slop;
    private OnSwipe listener;
    private float downX, downY;
    private boolean swiping;

    SwipeLayout(Context c) {
        super(c);
        slop = ViewConfiguration.get(c).getScaledTouchSlop() * 2;
    }

    void setOnSwipe(OnSwipe l) {
        listener = l;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent e) {
        if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
            downX = e.getX();
            downY = e.getY();
            swiping = false;
        } else if (e.getActionMasked() == MotionEvent.ACTION_MOVE) {
            float dx = e.getX() - downX, dy = e.getY() - downY;
            if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy) * 1.5f) swiping = true;
        }
        return swiping;
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility") // a swipe is not a click; the cells handle clicks
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (swiping && e.getActionMasked() == MotionEvent.ACTION_UP && listener != null) {
            listener.onSwipe(e.getX() < downX ? 1 : -1);
        }
        if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) swiping = false;
        return true;
    }
}

/** Letter-spaced text on a Canvas (Paint.setLetterSpacing needs API 21). */
final class Tracked {
    private Tracked() { }

    static float width(Paint p, String s, float em) {
        return p.measureText(s) + Math.max(0, s.length() - 1) * em * p.getTextSize();
    }

    static void draw(Canvas c, Paint p, String s, float x, float y, float em) {
        float gap = em * p.getTextSize();
        for (int i = 0; i < s.length(); i++) {
            String ch = s.substring(i, i + 1);
            c.drawText(ch, x, y, p);
            x += p.measureText(ch) + gap;
        }
    }
}
