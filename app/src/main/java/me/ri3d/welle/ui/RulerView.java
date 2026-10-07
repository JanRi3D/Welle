package me.ri3d.welle.ui;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import me.ri3d.welle.core.DabChannels;

/**
 * The Band III tuner dial: 38 channel ticks (tall and numbered at each block's A channel),
 * a dot above every channel that carries a known ensemble, and the needle with its flag.
 */
final class RulerView extends View {
    /** Player: ticks at full contrast. */
    static final int PLAYER = 0;
    /** Scan: channels left of the needle are lit, the rest dimmed. */
    static final int SCAN = 1;
    /** No tuner or web radio: dimmed dial without needle. */
    static final int OFF = 2;

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final boolean[] dots = new boolean[DabChannels.RULER_TICKS];
    private int mode = PLAYER;
    private float needle = -1;
    private String flag = "";
    private ValueAnimator animator;

    RulerView(Ui ui) {
        super(ui.c);
        this.ui = ui;
    }

    void setMode(int m) {
        if (mode != m) {
            mode = m;
            invalidate();
        }
    }

    void setDots(boolean[] on) {
        System.arraycopy(on, 0, dots, 0, dots.length);
        invalidate();
    }

    /**
     * @param pos   needle position in tick units (see DabChannels.rulerPos), negative hides it
     * @param durationMs 0 jumps, otherwise the needle glides there
     */
    void setNeedle(float pos, String label, int durationMs) {
        flag = label;
        if (animator != null) animator.cancel();
        if (durationMs <= 0 || needle < 0 || pos < 0 || Math.abs(pos - needle) < 0.01f) {
            needle = pos;
            invalidate();
            return;
        }
        animator = ValueAnimator.ofFloat(needle, pos);
        animator.setDuration(durationMs);
        animator.setInterpolator(new DecelerateInterpolator(2f));
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                needle = (Float) a.getAnimatedValue();
                invalidate();
            }
        });
        animator.start();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (animator != null) animator.cancel();
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        Theme t = ui.t;
        int w = getWidth(), h = getHeight();
        float one = ui.px(1);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(t.line);
        c.drawRect(0, 0, w, one, paint);
        c.drawRect(0, h - one, w, h, paint);

        float cell = w / (float) DabChannels.RULER_TICKS;
        float dotTop = ui.px(38), dot = ui.px(8), tickTop = dotTop + dot + ui.px(5);
        float tickW = ui.px(2);
        paint.setTypeface(ui.mono());
        paint.setTextSize(13 * ui.u);
        paint.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < DabChannels.RULER_TICKS; i++) {
            String label = DabChannels.rulerLabel(i);
            boolean major = label.endsWith("A");
            float cx = (i + 0.5f) * cell;
            if (mode != OFF && dots[i]) {
                paint.setColor(t.mark);
                c.drawCircle(cx, dotTop + dot / 2, dot / 2, paint);
            }
            int color;
            if (mode == OFF) color = t.rulerOff;
            else if (mode == SCAN) color = i + 0.5f < needle ? (major ? t.text : t.passedMinor) : t.barOff;
            else color = major ? t.text : t.tickMinor;
            paint.setColor(color);
            float tickH = ui.px(major ? 28 : 14);
            c.drawRect(cx - tickW / 2, tickTop, cx + tickW / 2, tickTop + tickH, paint);
            if (major) {
                paint.setColor(mode == OFF ? t.rulerOffLabel : t.dim);
                c.drawText(label.substring(0, label.length() - 1), cx, tickTop + ui.px(28 + 5 + 12), paint);
            }
        }

        if (mode == OFF || needle < 0) return;
        float x = needle * cell;
        float needleW = ui.px(t.night ? 2 : 3);
        paint.setColor(t.mark);
        c.drawRect(x - needleW / 2, 0, x + needleW / 2, h, paint);
        if (flag.isEmpty()) return;
        paint.setTypeface(ui.monoMed());
        paint.setTextSize(16 * ui.u);
        paint.setTextAlign(Paint.Align.LEFT);
        float textW = paint.measureText(flag);
        float padX = t.night ? 0 : ui.px(8);
        float left = x + ui.px(12);
        // Near the right edge the flag flips to the left of the needle.
        if (left + textW + 2 * padX > w) left = x - ui.px(12) - textW - 2 * padX;
        float baseline = ui.px(8) + 16 * ui.u;
        if (t.night) {
            paint.setColor(t.accent);
        } else {
            paint.setColor(t.accent);
            c.drawRect(left, ui.px(8), left + textW + 2 * padX, ui.px(8) + 16 * ui.u * 1.3f + ui.px(4), paint);
            paint.setColor(t.onAccent);
            baseline += ui.px(2);
        }
        c.drawText(flag, left + padX, baseline, paint);
    }
}
