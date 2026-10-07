package me.ri3d.welle.core;

import java.util.Locale;

/**
 * DAB Band III channel table. {@link #KHZ} has the same 41 entries, in the same order, as
 * DAB_FREQ_TABLE_MHZ in libirtdab (global_definitions.h); scan indices refer to it.
 * The on-screen ruler shows the 38 regular channels and leaves out 10N/11N/12N.
 */
public final class DabChannels {
    private DabChannels() { }

    public static final String[] LABEL = {
            "5A", "5B", "5C", "5D", "6A", "6B", "6C", "6D", "7A", "7B", "7C", "7D",
            "8A", "8B", "8C", "8D", "9A", "9B", "9C", "9D", "10A", "10N", "10B", "10C", "10D",
            "11A", "11N", "11B", "11C", "11D", "12A", "12N", "12B", "12C", "12D",
            "13A", "13B", "13C", "13D", "13E", "13F"};

    public static final int[] KHZ = {
            174928, 176640, 178352, 180064, 181936, 183648, 185360, 187072, 188928, 190640, 192352, 194064,
            195936, 197648, 199360, 201072, 202928, 204640, 206352, 208064, 209936, 210096, 211648, 213360, 215072,
            216928, 217088, 218640, 220352, 222064, 223936, 224096, 225648, 227360, 229072,
            230784, 232496, 234208, 235776, 237488, 239200};

    public static final int COUNT = KHZ.length;
    /** Ticks on the ruler: the table without the three N channels. */
    public static final int RULER_TICKS = 38;

    /** Table index for a frequency, or -1. */
    public static int indexOf(int khz) {
        for (int i = 0; i < COUNT; i++) if (KHZ[i] == khz) return i;
        return -1;
    }

    public static String label(int khz) {
        int i = indexOf(khz);
        return i < 0 ? "?" : LABEL[i];
    }

    /** "194.064" */
    public static String mhz(int khz) {
        return String.format(Locale.US, "%d.%03d", khz / 1000, khz % 1000);
    }

    public static boolean isN(int tableIndex) {
        return tableIndex == 21 || tableIndex == 26 || tableIndex == 31;
    }

    /**
     * Position on the 38-tick ruler for a table index, in tick units (tick centres are at
     * n + 0.5). N channels sit just right of their A channel. An index of COUNT maps to the
     * right edge.
     */
    public static float rulerPos(int tableIndex) {
        if (tableIndex < 0) return 0.5f;
        if (tableIndex >= COUNT) return RULER_TICKS - 0.5f;
        int n = tableIndex > 31 ? 3 : tableIndex > 26 ? 2 : tableIndex > 21 ? 1 : 0;
        if (isN(tableIndex)) return tableIndex - n - 1 + 0.5f + 0.5f;
        return tableIndex - n + 0.5f;
    }

    /** Ruler tick (0..37) for a table index, or -1 for N channels. */
    public static int rulerTick(int tableIndex) {
        if (tableIndex < 0 || tableIndex >= COUNT || isN(tableIndex)) return -1;
        return (int) rulerPos(tableIndex);
    }

    /** Label of ruler tick 0..37. */
    public static String rulerLabel(int tick) {
        int idx = tick;
        if (tick > 20) idx++;
        if (tick > 24) idx++;
        if (tick > 28) idx++;
        return LABEL[idx];
    }
}
