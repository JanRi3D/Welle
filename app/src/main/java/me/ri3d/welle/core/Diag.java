package me.ri3d.welle.core;

import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * A short local list of events (start, USB, audio focus, keys) for problems that happen where
 * no adb is attached, such as at ignition. Written by debug builds only; it stays on the
 * device and holds no station names, addresses or typed text. Also mirrored to logcat.
 *
 *   adb shell cat /data/data/me.ri3d.welle/files/events.log
 */
public final class Diag {
    static final int MAX_BYTES = 48 * 1024;

    private static File file;

    private Diag() {
    }

    /** @param dir where events.log lives; null switches the log off (release builds) */
    public static synchronized void init(File dir) {
        file = dir == null ? null : new File(dir, "events.log");
    }

    public static synchronized void note(String what) {
        if (file == null) return;
        try {
            android.util.Log.i("WelleDiag", what);
            if (file.length() > MAX_BYTES) dropOlderHalf();
            // "up" is the time since boot including sleep: it tells a cold boot from a wake-up.
            String line = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date())
                    + " up " + SystemClock.elapsedRealtime() / 1000 + "s  " + what + "\n";
            FileOutputStream out = new FileOutputStream(file, true);
            try {
                out.write(line.getBytes("UTF-8"));
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
            // Diagnostics must never break the radio.
        }
    }

    private static void dropOlderHalf() throws Exception {
        byte[] all;
        FileInputStream in = new FileInputStream(file);
        try {
            all = Io.readAll(in, MAX_BYTES * 2);
        } finally {
            Io.close(in);
        }
        int from = all.length / 2;
        while (from < all.length && all[from - 1] != '\n') from++;
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(all, from, all.length - from);
        } finally {
            out.close();
        }
    }
}
