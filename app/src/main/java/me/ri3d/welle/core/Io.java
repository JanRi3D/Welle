package me.ri3d.welle.core;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Small bounded I/O helpers. */
public final class Io {
    private Io() { }

    /** Reads a stream fully, failing instead of buffering more than {@code max} bytes. */
    public static byte[] readAll(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        copy(in, out, max);
        return out.toByteArray();
    }

    public static void copy(InputStream in, OutputStream out, int max) throws IOException {
        byte[] b = new byte[8192];
        int n;
        int total = 0;
        while ((n = in.read(b)) > 0) {
            total += n;
            if (total > max) throw new IOException("larger than " + max + " bytes");
            out.write(b, 0, n);
        }
    }

    public static void close(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
        }
    }
}
