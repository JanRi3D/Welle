package me.ri3d.welle.audio;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.Locale;

/**
 * Decodes compressed audio frames with the platform MediaCodec (available since API 16) and
 * writes the PCM to the {@link AudioEngine}. Uses the synchronous buffer-array API, which
 * exists on every supported Android version. One thread feeds one decoder.
 */
public final class CodecDecoder {
    public static final int MP3 = 0;
    public static final int AAC = 1;
    public static final int MP2 = 2;
    public static final int DAB_PLUS = 3;

    private static final String TAG = "WelleAudio";

    private final MediaCodec codec;
    private final ByteBuffer[] inputs;
    private ByteBuffer[] outputs;
    private final MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
    private final AudioEngine engine;
    private final int session;
    private int outRate;
    private int outChannels;
    private short[] pcm = new short[8192];
    private long pts;
    private long pcmFrames;

    /**
     * @param kind       MP3, AAC (csd = ADTS-derived config), MP2 or DAB_PLUS (csd = 960-sample config)
     * @param sampleRate nominal rate; the decoder reports the real output format
     */
    public CodecDecoder(int kind, int sampleRate, int channels, byte[] csd, AudioEngine engine, int session) throws IOException {
        this.engine = engine;
        this.session = session;
        String mime;
        // Tried by name before the system's own pick. On a vendor build that pick can be a
        // decoder that fails or plays through its own DSP; the software ones return PCM.
        String[] preferred;
        switch (kind) {
            case MP3:
                mime = "audio/mpeg";
                preferred = new String[]{"OMX.google.mp3.decoder", "c2.android.mp3.decoder"};
                break;
            case MP2:
                // Google's "audio/mpeg" decoder is Layer III only. Try a dedicated Layer II
                // type first, then MediaTek's general MPEG audio decoder found on the head unit.
                mime = "audio/mpeg-L2";
                preferred = new String[]{"OMX.mtk.audio.decoder.mpeg"};
                break;
            default:
                // AAC and DAB_PLUS. The software decoders also handle the 960-sample transform of DAB+.
                mime = "audio/mp4a-latm";
                preferred = new String[]{"OMX.google.aac.decoder", "c2.android.aac.decoder"};
                break;
        }
        MediaFormat format = MediaFormat.createAudioFormat(mime, sampleRate, channels);
        if (csd != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(csd));

        StringBuilder why = new StringBuilder();
        MediaCodec c = null;
        for (String name : preferred) {
            if (c == null && exists(name)) c = tryCreate(name, kind == MP2 ? "audio/mpeg" : mime, format, why);
        }
        if (c == null) c = tryCreate(null, mime, format, why);
        if (c == null && kind == MP2) c = tryCreate(null, "audio/mpeg", format, why);
        if (c == null && kind == MP3) c = tryListed(format, why, preferred, "audio/mpeg", "audio/mp3");
        if (c == null) throw new IOException(why.toString());
        codec = c;
        inputs = codec.getInputBuffers();
        outputs = codec.getOutputBuffers();
    }

    /**
     * @param name decoder to create, or null for the system pick for the type
     * @param why  collects "decoder: step failed" for each failure; it becomes the error the user sees
     */
    private static MediaCodec tryCreate(String name, String mime, MediaFormat format, StringBuilder why) {
        MediaCodec c = null;
        String who = name != null ? name : "default for " + mime;
        String step = "create";
        try {
            format.setString(MediaFormat.KEY_MIME, mime);
            c = name != null ? MediaCodec.createByCodecName(name) : MediaCodec.createDecoderByType(mime);
            step = "configure";
            c.configure(format, null, null, 0);
            step = "start";
            c.start();
            Log.i(TAG, "decoder: " + who);
            return c;
        } catch (Exception e) {
            Log.w(TAG, who + " failed at " + step + ": " + e);
            why.append(why.length() > 0 ? "; " : "").append(who).append(": ").append(step).append(" failed");
            if (c != null) {
                try {
                    c.release();
                } catch (Exception ignored) {
                }
            }
            return null;
        }
    }

    /** Every decoder the device lists for one of the types and that was not tried by name yet. */
    @SuppressWarnings("deprecation") // the non-deprecated MediaCodecList API is 21+
    private static MediaCodec tryListed(MediaFormat format, StringBuilder why, String[] tried, String... types) {
        try {
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo ci = MediaCodecList.getCodecInfoAt(i);
                if (ci.isEncoder() || Arrays.asList(tried).contains(ci.getName())) continue;
                for (String type : ci.getSupportedTypes()) {
                    if (!Arrays.asList(types).contains(type.toLowerCase(Locale.US))) continue;
                    MediaCodec c = tryCreate(ci.getName(), type, format, why);
                    if (c != null) return c;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    @SuppressWarnings("deprecation") // the non-deprecated MediaCodecList API is 21+
    private static boolean exists(String name) {
        try {
            for (int i = 0; i < MediaCodecList.getCodecCount(); i++) {
                MediaCodecInfo ci = MediaCodecList.getCodecInfoAt(i);
                if (!ci.isEncoder() && name.equals(ci.getName())) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /** PCM frames produced so far; lets callers tell "decoding" from "accepting input". */
    public long pcmFrames() {
        return pcmFrames;
    }

    /** Feeds one access unit (AAC) or frame (MPEG audio) and plays whatever is decoded. */
    public void feed(byte[] data, int off, int len) {
        try {
            for (int tries = 0; tries < 40; tries++) {
                int i = codec.dequeueInputBuffer(10000);
                if (i >= 0) {
                    ByteBuffer b = inputs[i];
                    b.clear();
                    int n = Math.min(len, b.capacity());
                    b.put(data, off, n);
                    codec.queueInputBuffer(i, 0, n, pts, 0);
                    pts += 20000;
                    drain();
                    return;
                }
                drain();
            }
        } catch (IllegalStateException e) {
            // Decoder gave up (bad stream or released); the caller notices via pcmFrames().
        }
    }

    @SuppressWarnings("deprecation") // INFO_OUTPUT_BUFFERS_CHANGED is how API 16-20 report new buffers
    private void drain() {
        while (true) {
            int o = codec.dequeueOutputBuffer(info, 0);
            if (o == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                outputs = codec.getOutputBuffers();
            } else if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                MediaFormat f = codec.getOutputFormat();
                outRate = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                outChannels = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
            } else if (o >= 0) {
                int samples = info.size / 2;
                if (samples > 0 && outRate > 0) {
                    if (pcm.length < samples) pcm = new short[samples];
                    ByteBuffer b = outputs[o];
                    b.position(info.offset);
                    b.limit(info.offset + info.size);
                    b.order(ByteOrder.nativeOrder()).asShortBuffer().get(pcm, 0, samples);
                    b.clear();
                }
                codec.releaseOutputBuffer(o, false);
                if (samples > 0 && outRate > 0) {
                    pcmFrames += samples / outChannels;
                    engine.write(session, pcm, samples, outRate, outChannels);
                }
            } else {
                return;
            }
        }
    }

    public void release() {
        try {
            codec.stop();
        } catch (Exception ignored) {
        }
        try {
            codec.release();
        } catch (Exception ignored) {
        }
    }
}
