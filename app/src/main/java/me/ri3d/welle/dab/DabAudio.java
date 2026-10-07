package me.ri3d.welle.dab;

import org.omri.radio.impl.RadioServiceDabImpl;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import me.ri3d.welle.audio.Asc;
import me.ri3d.welle.audio.AudioEngine;
import me.ri3d.welle.audio.CodecDecoder;

/**
 * Receives the running DAB service's payload from libirtdab. The dongle delivers the
 * broadcast's compressed audio (HE-AAC for DAB+, MPEG Layer II for DAB), not PCM, so this
 * decodes it with MediaCodec and plays it through the shared AudioEngine.
 */
public final class DabAudio implements RadioServiceDabImpl.Sink, Runnable {

    /** Called on native or decoder threads. */
    public interface Meta {
        void onDabFormat(boolean dabPlus, boolean stereo);
        void onDabDls(String text);
        void onDabSlide(byte[] image);
        /** Compressed audio arrives but cannot be decoded on this device. */
        void onDabDecoderMissing(boolean dabPlus);
    }

    // About two seconds of access units; a live source that outruns the decoder drops the oldest.
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<byte[]>(96);
    private final AudioEngine engine;
    private final int session;
    private final Meta meta;
    private final Thread thread = new Thread(this, "dab-decode");

    private volatile boolean running = true;
    private volatile boolean formatChanged;
    private volatile int ascty = -1;
    private volatile int channels;
    private volatile int sampleRate;
    private volatile boolean sbr;
    private volatile boolean ps;
    private volatile long lastAudioMs;

    public DabAudio(AudioEngine engine, Meta meta) {
        this.engine = engine;
        this.session = engine.begin();
        this.meta = meta;
        thread.start();
    }

    /** When compressed audio last arrived from the tuner (uptime clock of the caller). */
    public long lastAudioMs() {
        return lastAudioMs;
    }

    public void stop() {
        running = false;
        thread.interrupt();
    }

    // ---- RadioServiceDabImpl.Sink (native threads) -----------------------------------------

    @Override
    public void onAudioFormat(int ascty, int channels, int sampleRate, boolean sbr, boolean ps) {
        this.ascty = ascty;
        this.channels = channels;
        this.sampleRate = sampleRate;
        this.sbr = sbr;
        this.ps = ps;
        formatChanged = true;
        if (running) meta.onDabFormat(ascty == RadioServiceDabImpl.ASCTY_DAB_PLUS, channels > 1 || ps);
    }

    @Override
    public void onAudioData(byte[] data) {
        if (!running) return;
        lastAudioMs = android.os.SystemClock.elapsedRealtime();
        while (!queue.offer(data)) queue.poll();
    }

    @Override
    public void onDynamicLabel(String text) {
        if (running) meta.onDabDls(text);
    }

    @Override
    public void onSlide(byte[] image) {
        if (running) meta.onDabSlide(image);
    }

    // ---- decoder thread --------------------------------------------------------------------

    @Override
    public void run() {
        CodecDecoder decoder = null;
        boolean failed = false;
        try {
            while (running) {
                byte[] au;
                try {
                    au = queue.poll(250, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    break;
                }
                if (au == null) continue;
                if (formatChanged) {
                    formatChanged = false;
                    failed = false;
                    if (decoder != null) decoder.release();
                    decoder = null;
                }
                if (decoder == null) {
                    if (failed) continue;
                    boolean plus = ascty == RadioServiceDabImpl.ASCTY_DAB_PLUS;
                    try {
                        decoder = plus
                                ? new CodecDecoder(CodecDecoder.DAB_PLUS, sampleRate, channels, Asc.dabPlus(sampleRate, channels, sbr, ps), engine, session)
                                : new CodecDecoder(CodecDecoder.MP2, sampleRate, channels, null, engine, session);
                    } catch (IOException e) {
                        failed = true;
                        meta.onDabDecoderMissing(plus);
                        continue;
                    }
                }
                decoder.feed(au, 0, au.length);
            }
        } finally {
            if (decoder != null) decoder.release();
        }
    }
}
