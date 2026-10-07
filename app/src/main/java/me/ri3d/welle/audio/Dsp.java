package me.ri3d.welle.audio;

/**
 * Processing applied to the decoded radio audio itself (DAB and web alike): app volume,
 * focus ducking, automatic gain control and noise suppression. Works on 16-bit interleaved
 * PCM in place. Pure Java so it can be unit-tested.
 */
public final class Dsp {
    /** AGC aims for this RMS level (about -20 dBFS). */
    static final float AGC_TARGET = 0.10f;
    static final float AGC_MIN = 0.25f;
    static final float AGC_MAX = 4f;
    /** Blocks quieter than this (about -50 dBFS) are pauses, not programme; AGC ignores them. */
    static final float AGC_SILENCE = 0.003f;
    /** Noise gate: fully open above OPEN, attenuated to ATTEN below FLOOR, linear in between. */
    static final float GATE_OPEN = 0.0040f;
    static final float GATE_FLOOR = 0.0012f;
    static final float GATE_ATTEN = 0.12f;

    /** App volume multiplied by the audio-focus factor. Set from any thread. */
    public volatile float targetGain = 1f;
    public volatile boolean agc;
    public volatile boolean noiseGate;

    private float gain;          // follows targetGain without clicks; starts at 0 for a fade-in
    private float agcGain = 1f;
    private float gateGain = 1f;
    private float envelope;

    /** Next block fades in from silence (after a tune or flush). */
    public void fadeIn() {
        gain = 0f;
        envelope = 0f;
        gateGain = 1f;
    }

    float currentGain() {
        return gain;
    }

    float currentAgcGain() {
        return agcGain;
    }

    public void process(short[] pcm, int len, int sampleRate, int channels) {
        if (len <= 0 || channels <= 0 || sampleRate <= 0) return;

        if (agc) {
            double sum = 0;
            for (int i = 0; i < len; i++) sum += (double) pcm[i] * pcm[i];
            float rms = (float) Math.sqrt(sum / len) / 32768f;
            if (rms > AGC_SILENCE) {
                float wanted = Math.max(AGC_MIN, Math.min(AGC_MAX, AGC_TARGET / rms));
                float seconds = len / (float) (sampleRate * channels);
                // Turn down quickly, turn up slowly: no pumping between words.
                float rate = wanted < agcGain ? 4f : 0.4f;
                agcGain += (wanted - agcGain) * Math.min(1f, rate * seconds);
            }
        } else {
            agcGain += (1f - agcGain) * 0.25f;
        }

        final float step = 1f / (0.02f * sampleRate);       // full swing in 20 ms
        final float release = (float) Math.exp(-1.0 / (0.05 * sampleRate)); // 50 ms envelope decay
        final float target = targetGain;
        final boolean gate = noiseGate;
        float g = gain;
        for (int i = 0; i + channels <= len; i += channels) {
            if (g < target) g = Math.min(target, g + step);
            else if (g > target) g = Math.max(target, g - step);
            float total = g * agcGain;
            if (gate) {
                float peak = 0;
                for (int c = 0; c < channels; c++) peak = Math.max(peak, Math.abs(pcm[i + c]) / 32768f);
                envelope = peak > envelope ? peak : envelope * release;
                float open = envelope >= GATE_OPEN ? 1f
                        : envelope <= GATE_FLOOR ? GATE_ATTEN
                        : GATE_ATTEN + (1f - GATE_ATTEN) * (envelope - GATE_FLOOR) / (GATE_OPEN - GATE_FLOOR);
                gateGain += (open - gateGain) * 0.003f;
                total *= gateGain;
            }
            for (int c = 0; c < channels; c++) {
                float v = pcm[i + c] * total;
                // AGC can push peaks past full scale; clamp instead of wrapping around.
                pcm[i + c] = v > 32767f ? 32767 : v < -32768f ? -32768 : (short) v;
            }
        }
        gain = g;
    }
}
