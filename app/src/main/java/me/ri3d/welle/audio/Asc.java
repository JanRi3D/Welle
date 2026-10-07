package me.ri3d.welle.audio;

/** Builds MPEG-4 AudioSpecificConfig blobs ("csd-0") for the AAC decoder. */
public final class Asc {
    private Asc() { }

    private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350};

    public static int rateIndex(int sampleRate) {
        for (int i = 0; i < RATES.length; i++) if (RATES[i] == sampleRate) return i;
        return 3;
    }

    public static int rate(int index) {
        return index >= 0 && index < RATES.length ? RATES[index] : 0;
    }

    /**
     * DAB+ audio (ETSI TS 102 563): AAC-LC with the 960-sample transform, optionally with
     * SBR and PS signalled explicitly. Byte-identical to the constants omri-usb uses.
     *
     * @param sampleRate output rate, 48000 or 32000
     * @param channels   AAC core channels (1 when PS is used)
     */
    public static byte[] dabPlus(int sampleRate, int channels, boolean sbr, boolean ps) {
        Bits b = new Bits(4);
        if (sbr) {
            b.put(ps ? 29 : 5, 5);                  // audioObjectType: PS or SBR
            b.put(rateIndex(sampleRate / 2), 4);    // core rate
            b.put(channels, 4);
            b.put(rateIndex(sampleRate), 4);        // extension (output) rate
            b.put(2, 5);                            // underlying AAC-LC
        } else {
            b.put(2, 5);
            b.put(rateIndex(sampleRate), 4);
            b.put(channels, 4);
        }
        b.put(1, 1);                                // frameLengthFlag: 960 samples
        return b.bytes;
    }

    /** Plain AAC as carried in ADTS: object type, rate index and channel configuration. */
    public static byte[] adts(int objectType, int rateIndex, int channelConfig) {
        Bits b = new Bits(2);
        b.put(objectType, 5);
        b.put(rateIndex, 4);
        b.put(channelConfig, 4);
        return b.bytes;
    }

    private static final class Bits {
        final byte[] bytes;
        int pos;

        Bits(int size) {
            bytes = new byte[size];
        }

        void put(int value, int count) {
            for (int i = count - 1; i >= 0; i--, pos++) {
                if (((value >> i) & 1) != 0) bytes[pos >> 3] |= (byte) (0x80 >> (pos & 7));
            }
        }
    }
}
