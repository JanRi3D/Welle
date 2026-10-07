package me.ri3d.welle.web;

/**
 * Splits a raw byte stream into MPEG audio frames (MP3, MP2) or AAC access units (ADTS).
 * Accepts arbitrary chunks, skips ID3v2 tags and resynchronises after garbage. Pure Java.
 */
public final class FrameParser {
    public static final int MP3 = 0;
    public static final int AAC = 1;
    public static final int MP2 = 2;

    public interface Sink {
        /**
         * Called before the first frame and whenever the format changes.
         *
         * @param aacObjectType MPEG-4 audio object type (2 = AAC-LC), 0 for MPEG audio
         * @param rateIndex     MPEG-4 sampling frequency index, AAC only
         */
        void onFormat(int codec, int sampleRate, int channels, int aacObjectType, int rateIndex, int kbps);

        /** One MPEG audio frame including its header, or one AAC access unit without ADTS header. */
        void onFrame(byte[] buf, int off, int len);
    }

    private static final int[] BR_V1_L3 = {0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320};
    private static final int[] BR_V1_L2 = {0, 32, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384};
    private static final int[] BR_V2 = {0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160};
    private static final int[] SR_V1 = {44100, 48000, 32000};
    private static final int[] AAC_RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    private final Sink sink;
    private byte[] buf = new byte[32768];
    private int len;
    private boolean locked;
    private long formatKey = -1;
    private long skipped;
    private long frames;

    public FrameParser(Sink sink) {
        this.sink = sink;
    }

    /** Bytes discarded while looking for a frame; a large value with no frames means "not MP3/AAC". */
    public long skippedBytes() {
        return skipped;
    }

    public long frames() {
        return frames;
    }

    public void push(byte[] data, int off, int n) {
        if (len + n > buf.length) {
            byte[] bigger = new byte[Math.max(buf.length * 2, len + n)];
            System.arraycopy(buf, 0, bigger, 0, len);
            buf = bigger;
        }
        System.arraycopy(data, off, buf, len, n);
        len += n;

        int p = 0;
        while (true) {
            int avail = len - p;
            if (avail < 10) break;
            // ID3v2 tag: "ID3", version (2), flags (1), sync-safe size (4)
            if (buf[p] == 'I' && buf[p + 1] == 'D' && buf[p + 2] == '3') {
                int size = ((buf[p + 6] & 0x7f) << 21) | ((buf[p + 7] & 0x7f) << 14) | ((buf[p + 8] & 0x7f) << 7) | (buf[p + 9] & 0x7f);
                if (avail < 10 + size) {
                    if (10 + size > 4 * 1024 * 1024) { // absurd tag: treat as garbage
                        p++;
                        skipped++;
                        continue;
                    }
                    break;
                }
                p += 10 + size;
                continue;
            }
            int frameLen = frameLength(p);
            if (frameLen <= 0) {
                p++;
                skipped++;
                locked = false;
                continue;
            }
            if (avail < frameLen + 2) break; // need the frame and the start of the next one
            if (!locked) {
                // Two sync patterns in a row before trusting the stream.
                if ((buf[p + frameLen] & 0xff) != 0xff || (buf[p + frameLen + 1] & 0xe0) != 0xe0) {
                    p++;
                    skipped++;
                    continue;
                }
                locked = true;
            }
            emit(p, frameLen);
            p += frameLen;
        }
        if (p > 0) {
            System.arraycopy(buf, p, buf, 0, len - p);
            len -= p;
        }
    }

    /** Length of the frame starting at p, or 0 if there is no valid header. */
    private int frameLength(int p) {
        int b0 = buf[p] & 0xff, b1 = buf[p + 1] & 0xff, b2 = buf[p + 2] & 0xff;
        if (b0 != 0xff || (b1 & 0xe0) != 0xe0) return 0;
        int layerBits = (b1 >> 1) & 3;
        if (layerBits == 0) {
            // ADTS: 12 sync bits, layer 00
            if ((b1 & 0xf0) != 0xf0) return 0;
            int rateIndex = (b2 >> 2) & 0xf;
            if (rateIndex >= AAC_RATES.length) return 0;
            int n = ((buf[p + 3] & 3) << 11) | ((buf[p + 4] & 0xff) << 3) | ((buf[p + 5] & 0xff) >> 5);
            return n > 9 ? n : 0;
        }
        int version = (b1 >> 3) & 3; // 3 = MPEG-1, 2 = MPEG-2, 0 = MPEG-2.5
        int brIndex = b2 >> 4, srIndex = (b2 >> 2) & 3, padding = (b2 >> 1) & 1;
        if (version == 1 || brIndex == 0 || brIndex == 15 || srIndex == 3) return 0;
        int rate = SR_V1[srIndex] >> (version == 3 ? 0 : version == 2 ? 1 : 2);
        if (layerBits == 3) return 0; // Layer I is not used for radio
        int kbps = version == 3 ? (layerBits == 1 ? BR_V1_L3 : BR_V1_L2)[brIndex] : BR_V2[brIndex];
        if (layerBits == 1 && version != 3) return 72000 * kbps / rate + padding;
        return 144000 * kbps / rate + padding;
    }

    private void emit(int p, int frameLen) {
        int b1 = buf[p + 1] & 0xff, b2 = buf[p + 2] & 0xff, b3 = buf[p + 3] & 0xff;
        int layerBits = (b1 >> 1) & 3;
        if (layerBits == 0) {
            int objectType = ((b2 >> 6) & 3) + 1;
            int rateIndex = (b2 >> 2) & 0xf;
            int channels = ((b2 & 1) << 2) | (b3 >> 6);
            int header = (b1 & 1) == 1 ? 7 : 9;
            long key = (1L << 40) | (objectType << 16) | (rateIndex << 8) | channels;
            if (key != formatKey) {
                formatKey = key;
                sink.onFormat(AAC, AAC_RATES[rateIndex], channels, objectType, rateIndex, 0);
            }
            if (frameLen > header) sink.onFrame(buf, p + header, frameLen - header);
        } else {
            int version = (b1 >> 3) & 3;
            int brIndex = b2 >> 4, srIndex = (b2 >> 2) & 3;
            int rate = SR_V1[srIndex] >> (version == 3 ? 0 : version == 2 ? 1 : 2);
            int channels = (b3 >> 6) == 3 ? 1 : 2;
            int codec = layerBits == 1 ? MP3 : MP2;
            long key = ((long) codec << 32) | (rate << 4) | channels;
            if (key != formatKey) {
                formatKey = key;
                int kbps = version == 3 ? (layerBits == 1 ? BR_V1_L3 : BR_V1_L2)[brIndex] : BR_V2[brIndex];
                sink.onFormat(codec, rate, channels, 0, 0, kbps);
            }
            sink.onFrame(buf, p, frameLen);
        }
        frames++;
    }
}
