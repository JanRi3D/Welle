package me.ri3d.welle.web;

import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Station playlists: extended M3U (.m3u/.m3u8) and PLS. Pure Java.
 *
 * An HLS manifest also starts with #EXTM3U but describes the segments of ONE stream;
 * {@link #isHls} tells the two apart so a manifest is played, not imported as stations.
 */
public final class Playlist {
    private Playlist() { }

    public static final int MAX_BYTES = 512 * 1024;
    public static final int MAX_ENTRIES = 2000;

    public static final class Entry {
        public String name;
        public String url;
    }

    public static boolean isHls(String text) {
        return text.contains("#EXT-X-TARGETDURATION") || text.contains("#EXT-X-STREAM-INF")
                || text.contains("#EXT-X-MEDIA-SEQUENCE");
    }

    /**
     * @param baseUrl where the playlist came from, used to resolve relative entries; with
     *                null (a local file) relative entries are skipped
     */
    public static List<Entry> parse(String text, String baseUrl) {
        ArrayList<Entry> out = new ArrayList<Entry>();
        if (text.length() > 0 && text.charAt(0) == 0xFEFF) text = text.substring(1);
        String[] lines = text.split("\r\n|\r|\n");
        boolean pls = false;
        for (String l : lines) {
            if (l.trim().equalsIgnoreCase("[playlist]")) pls = true;
        }
        if (pls) {
            // FileN=url, TitleN=name
            ArrayList<String[]> items = new ArrayList<String[]>();
            for (String raw : lines) {
                String l = raw.trim();
                int eq = l.indexOf('=');
                if (eq < 5) continue;
                String key = l.substring(0, eq).toLowerCase(java.util.Locale.ROOT);
                String value = l.substring(eq + 1).trim();
                boolean file = key.startsWith("file");
                if (!file && !key.startsWith("title")) continue;
                String n = key.substring(file ? 4 : 5);
                String[] item = null;
                for (String[] it : items) if (it[0].equals(n)) item = it;
                if (item == null) {
                    item = new String[]{n, null, null};
                    items.add(item);
                }
                item[file ? 1 : 2] = value;
            }
            for (String[] it : items) add(out, it[2], it[1], baseUrl);
            return out;
        }
        String pendingName = null;
        for (String raw : lines) {
            String l = raw.trim();
            if (l.isEmpty()) continue;
            if (l.startsWith("#EXTINF")) {
                // #EXTINF:-1 tvg-name="x" group-title="y",Display name
                int comma = lastCommaOutsideQuotes(l);
                pendingName = comma >= 0 ? l.substring(comma + 1).trim() : null;
                continue;
            }
            if (l.startsWith("#")) continue;
            add(out, pendingName, l, baseUrl);
            pendingName = null;
            if (out.size() >= MAX_ENTRIES) break;
        }
        return out;
    }

    private static int lastCommaOutsideQuotes(String l) {
        boolean quoted = false;
        for (int i = 0; i < l.length(); i++) {
            char c = l.charAt(i);
            if (c == '"') quoted = !quoted;
            else if (c == ',' && !quoted) return i;
        }
        return -1;
    }

    private static void add(List<Entry> out, String name, String ref, String baseUrl) {
        if (ref == null || out.size() >= MAX_ENTRIES) return;
        String url = resolve(baseUrl, ref.trim());
        if (url == null) return;
        Entry e = new Entry();
        e.url = url;
        e.name = name == null || name.isEmpty() ? nameFromUrl(url) : name;
        out.add(e);
    }

    /** Absolute http(s) URL for an entry, or null if it cannot be one. */
    public static String resolve(String baseUrl, String ref) {
        try {
            URL u = baseUrl == null ? new URL(ref) : new URL(new URL(baseUrl), ref);
            String p = u.getProtocol();
            return "http".equals(p) || "https".equals(p) ? u.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    public static String nameFromUrl(String url) {
        try {
            URL u = new URL(url);
            String path = u.getPath();
            int slash = path.lastIndexOf('/');
            String last = slash >= 0 ? path.substring(slash + 1) : path;
            int dot = last.lastIndexOf('.');
            if (dot > 0) last = last.substring(0, dot);
            return last.length() >= 3 ? u.getHost() + " " + last : u.getHost();
        } catch (Exception e) {
            return url;
        }
    }
}
