package me.ri3d.welle.logos;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import me.ri3d.welle.core.Io;
import me.ri3d.welle.core.Station;

/**
 * Station logos on disk, keyed by station id, so they work offline. Every logo is stored
 * as a PNG of at most 256 px; lists decode it further down to the size they draw.
 *
 * Logo pack format (version 1): a ZIP with the PNG files and a manifest.json
 *   {"format":"welle-logo-pack","version":1,
 *    "logos":[{"id":"dab:d220","name":"radioeins","file":"dab_d220.png","manual":false}]}
 * The id is the station identity (DAB SId or web URL hash), so a pack matches the same
 * stations after any rescan; "name" is only informational.
 */
public final class LogoStore {
    public static final int NONE = 0;
    /** Downloaded (RadioDNS, web favicon) or imported from a pack. */
    public static final int AUTO = 1;
    /** Chosen by hand; never overwritten by downloads. */
    public static final int MANUAL = 2;

    static final int STORE_PX = 256;
    private static final int MAX_SOURCE_PX = 4096;
    private static final int MAX_PACK_ENTRIES = 1000;
    private static final int MAX_PACK_ENTRY_BYTES = 1024 * 1024;

    private final File dir;
    private final HashMap<String, Integer> sources = new HashMap<String, Integer>();
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(3 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    public LogoStore(File dir) {
        this.dir = dir;
        dir.mkdirs();
        try {
            File f = new File(dir, "logos.json");
            if (f.exists()) {
                InputStream in = new FileInputStream(f);
                try {
                    JSONObject o = new JSONObject(new String(Io.readAll(in, 1024 * 1024), "UTF-8"));
                    for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                        String id = it.next();
                        if (file(id).exists()) sources.put(id, o.getInt(id));
                    }
                } finally {
                    Io.close(in);
                }
            }
        } catch (Exception ignored) {
            // A damaged index only loses the manual/auto distinction.
        }
    }

    static String fileName(String id) {
        return id.replaceAll("[^a-zA-Z0-9]", "_") + ".png";
    }

    private File file(String id) {
        return new File(dir, fileName(id));
    }

    public synchronized int source(String id) {
        Integer s = sources.get(id);
        return s == null ? NONE : s;
    }

    public boolean has(String id) {
        return source(id) != NONE;
    }

    /** The stored logo file, or null; the name is sanitised, so it always lies in the logo folder. */
    public File fileOf(String id) {
        return has(id) ? file(id) : null;
    }

    /** Logo decoded to roughly maxPx, from a bounded memory cache; null if there is none. */
    public Bitmap get(String id, int maxPx) {
        if (!has(id)) return null;
        int sample = 1;
        while (STORE_PX / (sample * 2) >= maxPx) sample *= 2;
        String key = id + "/" + sample;
        Bitmap b = cache.get(key);
        if (b != null) return b;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        b = BitmapFactory.decodeFile(file(id).getPath(), o);
        if (b != null) cache.put(key, b);
        return b;
    }

    /** Drops decoded bitmaps (low memory); files stay. */
    public void trim() {
        cache.evictAll();
    }

    /** Stores an image as this station's logo. @throws IOException if it is not a usable image */
    public void put(String id, byte[] image, int source) throws IOException {
        Bitmap b = decodeBounded(image, STORE_PX);
        if (b == null) throw new IOException("not an image");
        int w = b.getWidth(), h = b.getHeight();
        if (w > STORE_PX || h > STORE_PX) {
            float k = STORE_PX / (float) Math.max(w, h);
            Bitmap scaled = Bitmap.createScaledBitmap(b, Math.max(1, Math.round(w * k)), Math.max(1, Math.round(h * k)), true);
            if (scaled != b) b.recycle();
            b = scaled;
        }
        File tmp = new File(dir, fileName(id) + ".tmp");
        FileOutputStream out = new FileOutputStream(tmp);
        try {
            b.compress(Bitmap.CompressFormat.PNG, 100, out);
        } finally {
            out.close();
            b.recycle();
        }
        File f = file(id);
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("could not store logo");
        }
        synchronized (this) {
            sources.put(id, source);
            saveIndex();
        }
        cache.evictAll();
    }

    public void remove(String id) {
        file(id).delete();
        synchronized (this) {
            sources.remove(id);
            saveIndex();
        }
        cache.evictAll();
    }

    /** Decodes with subsampling so a huge picture never allocates more than about target*2 squared. */
    public static Bitmap decodeBounded(byte[] image, int target) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(image, 0, image.length, o);
        if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > MAX_SOURCE_PX * 4 || o.outHeight > MAX_SOURCE_PX * 4) return null;
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / (sample * 2) >= target) sample *= 2;
        o = new BitmapFactory.Options();
        o.inSampleSize = sample;
        return BitmapFactory.decodeByteArray(image, 0, image.length, o);
    }

    /** @return number of logos written */
    public int exportPack(OutputStream out, List<Station> stations) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out);
        int n = 0;
        try {
            JSONArray logos = new JSONArray();
            for (Station s : stations) {
                int src = source(s.id);
                if (src == NONE) continue;
                String name = fileName(s.id);
                zip.putNextEntry(new ZipEntry(name));
                InputStream in = new FileInputStream(file(s.id));
                try {
                    Io.copy(in, zip, MAX_PACK_ENTRY_BYTES);
                } finally {
                    Io.close(in);
                }
                zip.closeEntry();
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("file", name);
                o.put("manual", src == MANUAL);
                logos.put(o);
                n++;
            }
            JSONObject manifest = new JSONObject();
            manifest.put("format", "welle-logo-pack");
            manifest.put("version", 1);
            manifest.put("logos", logos);
            zip.putNextEntry(new ZipEntry("manifest.json"));
            zip.write(manifest.toString(2).getBytes("UTF-8"));
            zip.closeEntry();
        } catch (org.json.JSONException e) {
            throw new IOException(e.getMessage());
        } finally {
            zip.close();
        }
        return n;
    }

    /**
     * Imports a pack. Logos set by hand are kept unless the pack entry is itself marked manual.
     *
     * @return number of logos imported
     */
    public int importPack(InputStream in) throws IOException {
        HashMap<String, byte[]> files = new HashMap<String, byte[]>();
        byte[] manifest = null;
        ZipInputStream zip = new ZipInputStream(in);
        try {
            ZipEntry e;
            int entries = 0;
            while ((e = zip.getNextEntry()) != null) {
                if (++entries > MAX_PACK_ENTRIES) throw new IOException("logo pack has too many files");
                String name = e.getName();
                if (e.isDirectory() || name.contains("/") || name.contains("\\") || name.contains("..")) continue;
                byte[] data = Io.readAll(zip, MAX_PACK_ENTRY_BYTES);
                if ("manifest.json".equals(name)) manifest = data;
                else files.put(name, data);
            }
        } finally {
            Io.close(zip);
        }
        if (manifest == null) throw new IOException("not a Welle logo pack");
        int n = 0;
        try {
            JSONObject m = new JSONObject(new String(manifest, "UTF-8"));
            if (!"welle-logo-pack".equals(m.optString("format")) || m.optInt("version") != 1) {
                throw new IOException("unsupported logo pack version");
            }
            JSONArray logos = m.getJSONArray("logos");
            for (int i = 0; i < logos.length(); i++) {
                JSONObject o = logos.getJSONObject(i);
                String id = o.getString("id");
                byte[] data = files.get(o.getString("file"));
                boolean manual = o.optBoolean("manual");
                if (data == null || (source(id) == MANUAL && !manual)) continue;
                try {
                    put(id, data, manual ? MANUAL : AUTO);
                    n++;
                } catch (IOException skip) {
                    // one broken picture does not spoil the pack
                }
            }
        } catch (org.json.JSONException e) {
            throw new IOException("logo pack manifest is damaged");
        }
        return n;
    }

    private void saveIndex() {
        try {
            JSONObject o = new JSONObject();
            for (String id : sources.keySet()) o.put(id, sources.get(id));
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            b.write(o.toString().getBytes("UTF-8"));
            FileOutputStream out = new FileOutputStream(new File(dir, "logos.json"));
            try {
                b.writeTo(out);
            } finally {
                out.close();
            }
        } catch (Exception ignored) {
        }
    }
}
