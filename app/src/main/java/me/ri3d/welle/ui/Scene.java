package me.ri3d.welle.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.util.Base64;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import me.ri3d.welle.core.Io;
import me.ri3d.welle.logos.LogoStore;

/**
 * A custom "scene": the backdrop of the artwork panel, behind the visualization and the
 * slideshow. The reference design does not define a file format, so this is Welle's own,
 * deliberately data-only (no scripts, no web content):
 *
 *   {
 *     "welleScene": 1,                  required, format version
 *     "background": "#101418",          optional fill colour
 *     "image": "<base64 PNG or JPEG>",  optional picture, scaled to cover the panel
 *     "imageOpacity": 0.6,              optional, 0..1, default 1
 *     "visualizationColor": "#4CD6E8"   optional, default is the accent colour
 *   }
 *
 * A plain PNG or JPEG file is accepted too and becomes a scene with just that picture.
 */
final class Scene {
    static final int MAX_BYTES = 4 * 1024 * 1024;

    int background;
    Bitmap image;
    float imageOpacity = 1f;
    int visualizationColor;

    private static File file(Context c) {
        return new File(c.getFilesDir(), "scene.json");
    }

    static boolean installed(Context c) {
        return file(c).exists();
    }

    static void remove(Context c) {
        file(c).delete();
    }

    /** Validates a picked file and stores it as the current scene. */
    static void install(Context c, byte[] data) throws IOException {
        String json;
        try {
            if (data.length > 0 && data[0] == '{') {
                json = new String(data, "UTF-8");
            } else {
                if (LogoStore.decodeBounded(data, 512) == null) throw new IOException("neither a scene nor a picture");
                JSONObject o = new JSONObject();
                o.put("welleScene", 1);
                o.put("image", Base64.encodeToString(data, Base64.NO_WRAP));
                json = o.toString();
            }
            parse(json); // reject anything that would not load later
        } catch (org.json.JSONException e) {
            throw new IOException("not a valid scene file");
        }
        FileOutputStream out = new FileOutputStream(file(c));
        try {
            out.write(json.getBytes("UTF-8"));
        } finally {
            out.close();
        }
    }

    /** The installed scene, or null. */
    static Scene load(Context c) {
        File f = file(c);
        if (!f.exists()) return null;
        try {
            InputStream in = new FileInputStream(f);
            try {
                return parse(new String(Io.readAll(in, MAX_BYTES * 2), "UTF-8"));
            } finally {
                Io.close(in);
            }
        } catch (Exception e) {
            return null;
        }
    }

    private static Scene parse(String json) throws org.json.JSONException, IOException {
        JSONObject o = new JSONObject(json);
        if (o.optInt("welleScene") != 1) throw new IOException("unsupported scene version");
        Scene s = new Scene();
        s.background = color(o.optString("background", ""));
        s.visualizationColor = color(o.optString("visualizationColor", ""));
        s.imageOpacity = (float) Math.max(0, Math.min(1, o.optDouble("imageOpacity", 1)));
        String img = o.optString("image", "");
        if (!img.isEmpty()) {
            s.image = LogoStore.decodeBounded(Base64.decode(img, Base64.DEFAULT), 512);
            if (s.image == null) throw new IOException("scene picture cannot be decoded");
        }
        return s;
    }

    private static int color(String s) {
        try {
            return s.isEmpty() ? 0 : Color.parseColor(s);
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }
}
