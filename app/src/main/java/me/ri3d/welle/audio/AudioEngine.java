package me.ri3d.welle.audio;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;

/**
 * The single audio output of the app. Whichever source is active (DAB or web) decodes to
 * PCM and writes it here; the DSP runs on that PCM and an AudioTrack plays it.
 *
 * Only one writer is audible at a time: a source calls {@link #begin()} for a session
 * token, and writes carrying an older token are dropped. That is what prevents two
 * pipelines from being heard during a source switch or a stream test.
 */
public final class AudioEngine {
    /** Mono samples kept for the visualizer. */
    public static final int TAP_SIZE = 1024;

    private static final int BUFFER_MS = 600;
    private static final int PREBUFFER_MS = 200;
    // "Prevent stuttering": ride out short reception gaps with a deeper buffer.
    // 2.5 s of 48 kHz stereo is 480 KB; Android 4.x gives a process 1 MB of track memory.
    private static final int DEEP_BUFFER_MS = 2500;
    private static final int DEEP_PREBUFFER_MS = 1500;

    public final Dsp dsp = new Dsp();

    private final Object lock = new Object();
    private AudioTrack track;
    private int rate;
    private int channels;
    private boolean deep;
    private boolean started;
    private long framesWritten;
    private int prebufferFrames;
    private int token;

    private volatile boolean wantDeep;
    private volatile boolean tapOn;
    private final short[] tap = new short[TAP_SIZE];
    private int tapPos;
    private volatile int underruns;

    /** Starts a new audio session and silences every earlier one. */
    public int begin() {
        synchronized (lock) {
            token++;
            flushLocked();
            return token;
        }
    }

    /** Silences the current session without starting a new one. */
    public void end() {
        synchronized (lock) {
            token++;
            flushLocked();
        }
    }

    public void setDeepBuffer(boolean on) {
        wantDeep = on;
    }

    public void setTap(boolean on) {
        tapOn = on;
    }

    public int underruns() {
        return underruns;
    }

    /**
     * Plays interleaved 16-bit PCM. Blocks while the output buffer is full, which paces the
     * decoder to real time.
     */
    public void write(int session, short[] pcm, int len, int sampleRate, int ch) {
        AudioTrack t;
        boolean wasStarted;
        synchronized (lock) {
            if (session != token || len <= 0) return;
            if (track == null || sampleRate != rate || ch != channels || deep != wantDeep) {
                if (!openLocked(sampleRate, ch)) return;
            }
            t = track;
            wasStarted = started;
        }

        dsp.process(pcm, len, sampleRate, ch);

        if (tapOn) {
            for (int i = 0; i + ch <= len; i += ch) {
                int v = pcm[i];
                if (ch > 1) v = (v + pcm[i + 1]) / 2;
                tap[tapPos] = (short) v;
                tapPos = (tapPos + 1) % TAP_SIZE;
            }
        }

        try {
            long head = t.getPlaybackHeadPosition() & 0xFFFFFFFFL;
            long fill = framesWritten - head;
            if (wasStarted && fill <= 0) {
                // Ran dry (reception gap or network stall): stop and refill before resuming,
                // instead of stuttering through a nearly empty buffer.
                t.pause();
                underruns++;
                synchronized (lock) {
                    if (session == token) started = false;
                }
                wasStarted = false;
            }
            int off = 0;
            while (off < len) {
                int n = t.write(pcm, off, len - off);
                if (n <= 0) break;
                off += n;
                framesWritten += n / ch;
                if (!wasStarted && framesWritten - head >= prebufferFrames) {
                    synchronized (lock) {
                        if (session != token) return;
                        t.play();
                        started = true;
                    }
                    wasStarted = true;
                }
                if (!wasStarted) break; // a stopped track takes what fits and returns
            }
        } catch (IllegalStateException e) {
            // The track was released by a concurrent flush; this session is over.
        }
    }

    /** Copies the most recent samples, oldest first, scaled to -1..1. */
    public void snapshot(float[] out) {
        int n = Math.min(out.length, TAP_SIZE);
        int start = (tapPos - n + TAP_SIZE) % TAP_SIZE;
        for (int i = 0; i < n; i++) out[i] = tap[(start + i) % TAP_SIZE] / 32768f;
    }

    public void release() {
        synchronized (lock) {
            token++;
            if (track != null) {
                try {
                    track.release();
                } catch (Exception ignored) {
                }
                track = null;
            }
        }
    }

    private void flushLocked() {
        started = false;
        framesWritten = 0;
        dsp.fadeIn();
        if (track != null) {
            // Releasing rather than pause()+flush(): a writer blocked in write() returns at once.
            try {
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
        java.util.Arrays.fill(tap, (short) 0);
    }

    private boolean openLocked(int sampleRate, int ch) {
        if (track != null) {
            try {
                track.release();
            } catch (Exception ignored) {
            }
            track = null;
        }
        if (ch < 1 || ch > 2) return false;
        int config = ch == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(sampleRate, config, AudioFormat.ENCODING_PCM_16BIT);
        if (min <= 0) return false;
        deep = wantDeep;
        int bytesPerSecond = sampleRate * ch * 2;
        int size = Math.max(min, (int) ((long) bytesPerSecond * (deep ? DEEP_BUFFER_MS : BUFFER_MS) / 1000));
        try {
            track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, config,
                    AudioFormat.ENCODING_PCM_16BIT, size, AudioTrack.MODE_STREAM);
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                return false;
            }
        } catch (IllegalArgumentException e) {
            track = null;
            return false;
        }
        rate = sampleRate;
        channels = ch;
        started = false;
        framesWritten = 0;
        int capacityFrames = size / (ch * 2);
        prebufferFrames = Math.min(capacityFrames * 3 / 4, sampleRate * (deep ? DEEP_PREBUFFER_MS : PREBUFFER_MS) / 1000);
        dsp.fadeIn();
        return true;
    }
}
