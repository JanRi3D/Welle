package me.ri3d.welle.audio;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class AudioTest {

    // ---- decoder configuration -----------------------------------------------------------------

    @Test
    public void dabPlusConfigMatchesTheReferenceImplementation() {
        // The byte values omri-usb hard-codes in DabAudioDecoder.java for 48 kHz stereo.
        assertArrayEquals(new byte[]{0x2B, 0x11, (byte) 0x8A, 0x00}, Asc.dabPlus(48000, 2, true, false));
        assertArrayEquals(new byte[]{(byte) 0xEB, 0x11, (byte) 0x8A, 0x00}, Asc.dabPlus(48000, 2, true, true));
        assertArrayEquals(new byte[]{0x11, (byte) 0x94, 0x00, 0x00}, Asc.dabPlus(48000, 2, false, false));
        // and its adjustments for 32 kHz and mono
        assertArrayEquals(new byte[]{0x2C, 0x12, (byte) 0x8A, 0x00}, Asc.dabPlus(32000, 2, true, false));
        assertArrayEquals(new byte[]{0x12, (byte) 0x94, 0x00, 0x00}, Asc.dabPlus(32000, 2, false, false));
        assertArrayEquals(new byte[]{(byte) 0xEB, 0x09, (byte) 0x8A, 0x00}, Asc.dabPlus(48000, 1, true, true));
        assertArrayEquals(new byte[]{0x11, (byte) 0x8C, 0x00, 0x00}, Asc.dabPlus(48000, 1, false, false));
    }

    @Test
    public void adtsConfig() {
        // AAC-LC, 44.1 kHz, stereo: the well-known 0x12 0x10
        assertArrayEquals(new byte[]{0x12, 0x10}, Asc.adts(2, 4, 2));
        assertEquals(44100, Asc.rate(4));
        assertEquals(3, Asc.rateIndex(48000));
    }

    // ---- processing ----------------------------------------------------------------------------

    private static short[] sine(int frames, int channels, double amplitude) {
        short[] pcm = new short[frames * channels];
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / 48000.0) * amplitude * 32767);
            for (int c = 0; c < channels; c++) pcm[i * channels + c] = v;
        }
        return pcm;
    }

    private static double rms(short[] pcm, int from) {
        double sum = 0;
        for (int i = from; i < pcm.length; i++) sum += (double) pcm[i] * pcm[i];
        return Math.sqrt(sum / (pcm.length - from)) / 32768.0;
    }

    @Test
    public void volumeIsAppliedAfterAShortFadeIn() {
        Dsp d = new Dsp();
        d.targetGain = 0.5f;
        short[] pcm = sine(9600, 2, 0.5);
        d.process(pcm, pcm.length, 48000, 2);
        assertEquals("starts from silence", 0, pcm[0]);
        assertEquals(0.5f, d.currentGain(), 0.0001f);
        // After the 20 ms ramp the level is half of the input (0.5 * 0.5 / sqrt 2).
        assertEquals(0.25 / Math.sqrt(2), rms(pcm, 4800), 0.01);
    }

    @Test
    public void duckingRampsDownWithoutAStep() {
        Dsp d = new Dsp();
        short[] warm = sine(4800, 1, 0.5);
        d.process(warm, warm.length, 48000, 1);
        assertEquals(1f, d.currentGain(), 0.0001f);
        d.targetGain = 0f;
        short[] pcm = new short[4800];
        java.util.Arrays.fill(pcm, (short) 10000);
        d.process(pcm, pcm.length, 48000, 1);
        assertTrue("first sample still nearly full level", pcm[0] > 9900);
        assertEquals("silent after the ramp", 0, pcm[4799]);
        for (int i = 1; i < pcm.length; i++) assertTrue(pcm[i] <= pcm[i - 1]);
    }

    @Test
    public void agcBringsQuietAndLoudStationsTowardsTheSameLevel() {
        Dsp quiet = new Dsp();
        quiet.agc = true;
        Dsp loud = new Dsp();
        loud.agc = true;
        double q = 0, l = 0;
        for (int block = 0; block < 400; block++) { // 8 seconds
            short[] a = sine(960, 2, 0.03);
            short[] b = sine(960, 2, 0.6);
            quiet.process(a, a.length, 48000, 2);
            loud.process(b, b.length, 48000, 2);
            q = rms(a, 0);
            l = rms(b, 0);
        }
        assertTrue("quiet station raised", quiet.currentAgcGain() > 1.5f);
        assertTrue("loud station lowered", loud.currentAgcGain() < 0.5f);
        assertTrue("levels within 6 dB of each other, were 26 dB apart", l / q < 2.0);
    }

    @Test
    public void agcLeavesSilenceAlone() {
        Dsp d = new Dsp();
        d.agc = true;
        short[] pause = new short[9600];
        for (int i = 0; i < 20; i++) d.process(pause, pause.length, 48000, 2);
        assertEquals(1f, d.currentAgcGain(), 0.0001f);
    }

    @Test
    public void neverWrapsAroundAtFullScale() {
        Dsp d = new Dsp();
        d.agc = true;
        short[] warm = sine(48000, 1, 0.02);
        d.process(warm, warm.length, 48000, 1); // AGC gain is now well above 1
        short[] peak = new short[960];
        java.util.Arrays.fill(peak, (short) 30000);
        d.process(peak, peak.length, 48000, 1);
        for (short v : peak) assertTrue("clamped, not wrapped negative", v > 0);
    }

    @Test
    public void noiseGateAttenuatesHissButNotProgramme() {
        Dsp d = new Dsp();
        d.noiseGate = true;
        short[] warm = sine(4800, 1, 0.3);
        d.process(warm, warm.length, 48000, 1);
        short[] programme = sine(48000, 1, 0.3);
        d.process(programme, programme.length, 48000, 1);
        assertEquals(0.3 / Math.sqrt(2), rms(programme, 24000), 0.01);

        short[] hiss = sine(96000, 1, 0.0006);
        double before = rms(hiss, 48000);
        d.process(hiss, hiss.length, 48000, 1);
        assertTrue("hiss is turned down by more than half", rms(hiss, 48000) < before * 0.5);
    }
}
