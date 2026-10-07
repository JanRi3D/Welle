package me.ri3d.welle.web;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import me.ri3d.welle.core.Station;

/**
 * Station search against a Radio Browser server (https://api.radio-browser.info/).
 * Endpoint: GET {base}/json/stations/search with name, countrycode and tag filters; the
 * answer is a JSON array of objects with name, url, url_resolved and favicon.
 */
public final class RadioBrowser {
    private RadioBrowser() { }

    private static final int MAX_BYTES = 1024 * 1024;
    private static final int LIMIT = 60;

    public static String searchUrl(String base, String name, String countryCode, String tag) throws IOException {
        String b = base.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        StringBuilder sb = new StringBuilder(b)
                .append("/json/stations/search?limit=").append(LIMIT)
                .append("&hidebroken=true&order=clickcount&reverse=true");
        if (name != null && !name.trim().isEmpty()) sb.append("&name=").append(URLEncoder.encode(name.trim(), "UTF-8"));
        if (countryCode != null && !countryCode.isEmpty()) sb.append("&countrycode=").append(URLEncoder.encode(countryCode, "UTF-8"));
        if (tag != null && !tag.isEmpty()) sb.append("&tag=").append(URLEncoder.encode(tag, "UTF-8"));
        return sb.toString();
    }

    public static List<Station> search(String base, String name, String countryCode, String tag) throws IOException {
        try {
            return parse(new String(Net.get(searchUrl(base, name, countryCode, tag), MAX_BYTES), "UTF-8"));
        } catch (JSONException e) {
            throw new IOException("unexpected answer from the search API");
        }
    }

    public static List<Station> parse(String json) throws JSONException {
        JSONArray a = new JSONArray(json);
        ArrayList<Station> out = new ArrayList<Station>();
        for (int i = 0; i < a.length() && out.size() < LIMIT; i++) {
            JSONObject o = a.getJSONObject(i);
            String url = o.optString("url_resolved", "");
            if (url.isEmpty()) url = o.optString("url", "");
            if (!url.startsWith("http://") && !url.startsWith("https://")) continue;
            String logo = o.optString("favicon", "");
            Station s = Station.web(o.optString("name", "").trim(), url, logo.startsWith("http") ? logo : null);
            boolean dup = false;
            for (Station x : out) if (x.id.equals(s.id)) dup = true;
            if (!dup) out.add(s);
        }
        return out;
    }
}
