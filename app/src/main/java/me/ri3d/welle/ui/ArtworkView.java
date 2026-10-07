package me.ri3d.welle.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

import me.ri3d.welle.audio.AudioEngine;

/**
 * The artwork panel. Layers, back to front: panel, imported scene, audio visualization,
 * slideshow picture or station logo (with the configured transparency), the monogram
 * fallback when there is no picture, the after-sunset dimming, and the border.
 *
 * The visualization is computed from the PCM that is actually being played (the engine's
 * tap), redraws 20 times a second, and only runs while the view is on screen.
 */
final class ArtworkView extends View {
    static final int VIS_NONE = 0, VIS_SPECTRUM = 1, VIS_CIRCULAR = 2, VIS_RAINBOW = 3, VIS_EQUALIZER = 4, VIS_WAVES = 5, VIS_RETRO = 6;

    private static final int FFT = 512;
    private static final int BANDS = 24;
    private static final int FRAME_MS = 50;

    private final Ui ui;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Matrix matrix = new Matrix();
    private final Path path = new Path();

    private Bitmap image;
    private boolean imageCover;
    private String mono = "";
    private String caption = "";
    private Scene scene;
    private float imageAlpha = 1f;
    private float brightness = 1f;
    private int vis;
    private AudioEngine engine;
    private boolean animate;

    // Precomputed so a frame allocates nothing and calls no trigonometry per sample.
    private static final float[] WINDOW = new float[FFT];
    private static final int[] RAINBOW = new int[BANDS];

    static {
        for (int i = 0; i < FFT; i++) WINDOW[i] = (float) (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (FFT - 1)));
        for (int i = 0; i < BANDS; i++) RAINBOW[i] = Color.HSVToColor(new float[]{300f * i / BANDS, 0.75f, 1f});
    }

    private Bitmap shaderBitmap;
    private BitmapShader shader;

    private final float[] samples = new float[FFT];
    private final float[] re = new float[FFT];
    private final float[] im = new float[FFT];
    private final float[] bands = new float[BANDS];
    private final float[] peaks = new float[BANDS];

    ArtworkView(Ui ui) {
        super(ui.c);
        this.ui = ui;
    }

    /** @param cover true for slideshow pictures (fill the panel), false for logos (fit inside) */
    void setImage(Bitmap b, boolean cover) {
        if (b != image || cover != imageCover) {
            image = b;
            imageCover = cover;
            invalidate();
        }
    }

    void setFallback(String mono, String caption) {
        if (!mono.equals(this.mono) || !caption.equals(this.caption)) {
            this.mono = mono;
            this.caption = caption;
            invalidate();
        }
    }

    void setScene(Scene s) {
        scene = s;
        invalidate();
    }

    /** @param imageAlpha 1 = opaque slideshow; @param brightness 1 = not dimmed */
    void setLook(float imageAlpha, float brightness) {
        if (imageAlpha != this.imageAlpha || brightness != this.brightness) {
            this.imageAlpha = imageAlpha;
            this.brightness = brightness;
            invalidate();
        }
    }

    /** Selects the visualization; it only animates while audio is playing. */
    void setVisualization(int kind, AudioEngine source, boolean playing) {
        vis = kind;
        engine = source;
        boolean run = kind != VIS_NONE && source != null && playing;
        if (source != null) source.setTap(run && getWindowToken() != null);
        if (run != animate) {
            animate = run;
            invalidate();
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (engine != null) engine.setTap(animate);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (engine != null) engine.setTap(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onDraw(Canvas c) {
        Theme t = ui.t;
        float one = ui.px(1), radius = ui.px(6);
        int w = getWidth(), h = getHeight();
        rect.set(one / 2, one / 2, w - one / 2, h - one / 2);

        paint.setStyle(Paint.Style.FILL);
        paint.setShader(null);
        paint.setColor(scene != null && scene.background != 0 ? scene.background : t.panel);
        c.drawRoundRect(rect, radius, radius, paint);

        if (scene != null && scene.image != null) drawBitmap(c, scene.image, true, scene.imageOpacity, radius);

        if (vis != VIS_NONE && animate) {
            analyse();
            drawVisualization(c, w, h);
        }

        if (image != null) {
            drawBitmap(c, image, imageCover, imageAlpha, radius);
        } else if (vis == VIS_NONE || !animate) {
            // No picture: the monogram stands in, unless a running visualization fills the panel.
            paint.setShader(null);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(ui.cond());
            paint.setColor(t.mark);
            float size = Math.min(100 * ui.u, h * 0.48f);
            paint.setTextSize(size);
            float baseline = h / 2f + size * 0.28f - (caption.isEmpty() ? 0 : ui.px(10));
            c.drawText(mono, w / 2f, baseline, paint);
            if (!caption.isEmpty()) {
                paint.setTypeface(ui.mono());
                paint.setTextSize(13 * ui.u);
                paint.setColor(t.dim);
                paint.setTextAlign(Paint.Align.LEFT);
                float cw = Tracked.width(paint, caption, 0.12f);
                Tracked.draw(c, paint, caption, (w - cw) / 2f, baseline + ui.px(6 + 16), 0.12f);
            }
        }

        boolean somethingToDim = image != null || scene != null || (vis != VIS_NONE && animate);
        if (brightness < 1f && somethingToDim) {
            paint.setShader(null);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.argb(Math.round((1f - brightness) * 255), 0, 0, 0));
            c.drawRoundRect(rect, radius, radius, paint);
        }

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(one);
        paint.setColor(t.line);
        c.drawRoundRect(rect, radius, radius, paint);
        paint.setStyle(Paint.Style.FILL);

        if (animate && vis != VIS_NONE) postInvalidateDelayed(FRAME_MS);
    }

    /** Draws a bitmap into the rounded panel; a shader keeps the corners round without clipPath. */
    private void drawBitmap(Canvas c, Bitmap b, boolean cover, float alpha, float radius) {
        float w = rect.width(), h = rect.height();
        float sx = w / b.getWidth(), sy = h / b.getHeight();
        float s = cover ? Math.max(sx, sy) : Math.min(sx, sy) * 0.8f;
        float dw = b.getWidth() * s, dh = b.getHeight() * s;
        float left = rect.left + (w - dw) / 2, top = rect.top + (h - dh) / 2;
        matrix.reset();
        matrix.setScale(s, s);
        matrix.postTranslate(left, top);
        BitmapShader sh = b == shaderBitmap ? shader : new BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
        if (b == image) { // the picture on top is the one redrawn every frame: keep its shader
            shaderBitmap = b;
            shader = sh;
        }
        sh.setLocalMatrix(matrix);
        bitmapPaint.setShader(sh);
        bitmapPaint.setAlpha(Math.round(alpha * 255));
        if (cover) {
            c.drawRoundRect(rect, radius, radius, bitmapPaint);
        } else {
            c.drawRect(left, top, left + dw, top + dh, bitmapPaint);
        }
    }

    // ---- visualization -----------------------------------------------------------------------

    /** Latest PCM -> windowed FFT -> BANDS log-spaced levels in 0..1 with fall-off. */
    private void analyse() {
        engine.snapshot(samples);
        for (int i = 0; i < FFT; i++) {
            re[i] = samples[i] * WINDOW[i];
            im[i] = 0;
        }
        fft(re, im);
        int half = FFT / 2;
        for (int b = 0; b < BANDS; b++) {
            int lo = (int) Math.pow(half, b / (double) BANDS);
            int hi = Math.max(lo + 1, (int) Math.pow(half, (b + 1) / (double) BANDS));
            float max = 0;
            for (int i = lo; i < hi && i < half; i++) max = Math.max(max, re[i] * re[i] + im[i] * im[i]);
            // magnitude in dB, mapped from -60..0 dB to 0..1
            float level = (float) ((10 * Math.log10(max / (FFT * FFT / 16f) + 1e-9) + 60) / 60);
            level = Math.max(0, Math.min(1, level));
            bands[b] = level > bands[b] ? level : bands[b] * 0.82f;
            peaks[b] = level > peaks[b] ? level : Math.max(0, peaks[b] - 0.02f);
        }
    }

    private void drawVisualization(Canvas c, int w, int h) {
        int color = scene != null && scene.visualizationColor != 0 ? scene.visualizationColor : ui.t.accent;
        float pad = ui.px(16);
        float left = pad, right = w - pad, bottom = h - pad, top = pad;
        float bw = (right - left) / BANDS;
        paint.setShader(null);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(color);
        switch (vis) {
            case VIS_CIRCULAR: {
                float cx = w / 2f, cy = h / 2f, r0 = Math.min(w, h) * 0.18f, len = Math.min(w, h) * 0.28f;
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.px(4));
                for (int i = 0; i < BANDS; i++) {
                    double a = 2 * Math.PI * i / BANDS - Math.PI / 2;
                    float r1 = r0 + ui.px(2) + bands[i] * len;
                    c.drawLine(cx + (float) Math.cos(a) * r0, cy + (float) Math.sin(a) * r0,
                            cx + (float) Math.cos(a) * r1, cy + (float) Math.sin(a) * r1, paint);
                }
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case VIS_RAINBOW:
                for (int i = 0; i < BANDS; i++) {
                    paint.setColor(RAINBOW[i]);
                    c.drawRect(left + i * bw + 1, bottom - bands[i] * (bottom - top), left + (i + 1) * bw - 1, bottom, paint);
                }
                break;
            case VIS_EQUALIZER: {
                int rows = 12;
                float cell = (bottom - top) / rows;
                for (int i = 0; i < BANDS; i++) {
                    int lit = Math.round(bands[i] * rows);
                    for (int r = 0; r < lit; r++) {
                        c.drawRect(left + i * bw + 1, bottom - (r + 1) * cell + 1, left + (i + 1) * bw - 1, bottom - r * cell - 1, paint);
                    }
                    float py = bottom - peaks[i] * (bottom - top);
                    c.drawRect(left + i * bw + 1, py - ui.px(2), left + (i + 1) * bw - 1, py, paint);
                }
                break;
            }
            case VIS_WAVES: {
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(ui.px(2));
                path.reset();
                int step = 4;
                for (int i = 0; i < FFT; i += step) {
                    float x = left + (right - left) * i / (FFT - step);
                    float y = h / 2f - samples[i] * (bottom - top) * 0.9f;
                    y = Math.max(top, Math.min(bottom, y));
                    if (i == 0) path.moveTo(x, y);
                    else path.lineTo(x, y);
                }
                c.drawPath(path, paint);
                paint.setStyle(Paint.Style.FILL);
                break;
            }
            case VIS_RETRO: {
                // Chunky two-colour meter: eight wide bars, the top quarter in red.
                int wide = 8, rows = 8;
                float cellW = (right - left) / wide, cellH = (bottom - top) / rows;
                for (int i = 0; i < wide; i++) {
                    float level = 0;
                    for (int k = 0; k < BANDS / wide; k++) level = Math.max(level, bands[i * BANDS / wide + k]);
                    int lit = Math.round(level * rows);
                    for (int r = 0; r < lit; r++) {
                        paint.setColor(r >= rows - 2 ? 0xFFE5484D : color);
                        c.drawRect(left + i * cellW + ui.px(2), bottom - (r + 1) * cellH + ui.px(2),
                                left + (i + 1) * cellW - ui.px(2), bottom - r * cellH - ui.px(2), paint);
                    }
                }
                break;
            }
            default: // VIS_SPECTRUM
                for (int i = 0; i < BANDS; i++) {
                    c.drawRect(left + i * bw + 1, bottom - bands[i] * (bottom - top), left + (i + 1) * bw - 1, bottom, paint);
                }
                break;
        }
    }

    /** In-place radix-2 FFT. */
    private static void fft(float[] re, float[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                float tr = re[i];
                re[i] = re[j];
                re[j] = tr;
                float ti = im[i];
                im[i] = im[j];
                im[j] = ti;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            float wr = (float) Math.cos(ang), wi = (float) Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                float cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    float xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    float ncr = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = ncr;
                }
            }
        }
    }
}
