package me.ri3d.welle.core;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;

/**
 * A DAB service or a web stream.
 *
 * Identity: a DAB station is its Service Identifier (SId), which is the same wherever the
 * service is broadcast; ensembles and frequencies it was found on are {@link Loc}ations of
 * that one station. A web station is identified by a hash of its stream URL. Names, channel
 * positions and list indices are never used as identity.
 */
public final class Station {

    /** One place a DAB service can be received. */
    public static final class Loc {
        public int eid;
        public int khz;
        public String ensemble = "";
    }

    public String id = "";
    public String name = "";

    // DAB
    public int sid;
    /** Extended country code, 0 if the scan did not see it; needed for RadioDNS. */
    public int ecc;
    public int scids;
    public boolean dabPlus = true;
    public int bitrate;
    public final ArrayList<Loc> locs = new ArrayList<Loc>();
    /** Kept as a favourite although the last scan did not find it. */
    public boolean missing;

    // Web
    public String url;
    public String logoUrl;

    public boolean isDab() {
        return url == null;
    }

    public Loc loc() {
        return locs.isEmpty() ? null : locs.get(0);
    }

    public boolean hasEnsemble(int eid) {
        for (Loc l : locs) if (l.eid == eid) return true;
        return false;
    }

    public static String dabId(int sid) {
        return "dab:" + Integer.toHexString(sid);
    }

    public static String webId(String url) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(url.trim().getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder("web:");
            for (int i = 0; i < 6; i++) sb.append(String.format("%02x", d[i] & 0xff));
            return sb.toString();
        } catch (Exception e) {
            return "web:" + Integer.toHexString(url.trim().hashCode());
        }
    }

    public static Station web(String name, String url, String logoUrl) {
        Station s = new Station();
        s.url = url.trim();
        s.id = webId(s.url);
        s.name = name == null || name.trim().isEmpty() ? s.url : name.trim();
        s.logoUrl = logoUrl;
        return s;
    }

    /**
     * Two or three characters standing in for a missing logo: the first number in the name
     * ("rbb 88.8" -> "88"), else the initials of the first two words ("Dlf Kultur" -> "DK"),
     * else the first two letters ("Fritz" -> "Fr").
     */
    public String mono() {
        String n = name.trim();
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < n.length() && digits.length() < 3; i++) {
            char c = n.charAt(i);
            if (Character.isDigit(c)) digits.append(c);
            else if (digits.length() > 0) break;
        }
        if (digits.length() >= 2) return digits.toString();
        String[] words = n.split("[\\s\\-_/.]+");
        StringBuilder sb = new StringBuilder();
        for (String w : words) {
            if (!w.isEmpty() && Character.isLetterOrDigit(w.charAt(0)) && sb.length() < 2) sb.append(w.charAt(0));
        }
        if (sb.length() >= 2) return sb.toString();
        StringBuilder two = new StringBuilder();
        for (int i = 0; i < n.length() && two.length() < 2; i++) {
            if (Character.isLetterOrDigit(n.charAt(i))) two.append(n.charAt(i));
        }
        return two.length() == 0 ? "?" : two.toString();
    }

    JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("name", name);
        if (isDab()) {
            o.put("sid", sid);
            o.put("ecc", ecc);
            o.put("scids", scids);
            o.put("plus", dabPlus);
            o.put("br", bitrate);
            o.put("missing", missing);
            JSONArray a = new JSONArray();
            for (Loc l : locs) {
                JSONObject j = new JSONObject();
                j.put("eid", l.eid);
                j.put("khz", l.khz);
                j.put("ens", l.ensemble);
                a.put(j);
            }
            o.put("locs", a);
        } else {
            o.put("url", url);
            if (logoUrl != null) o.put("logo", logoUrl);
        }
        return o;
    }

    static Station fromJson(JSONObject o) throws JSONException {
        Station s = new Station();
        s.id = o.getString("id");
        s.name = o.optString("name", "");
        if (o.has("url")) {
            s.url = o.getString("url");
            s.logoUrl = o.has("logo") ? o.getString("logo") : null;
        } else {
            s.sid = o.getInt("sid");
            s.ecc = o.optInt("ecc");
            s.scids = o.optInt("scids");
            s.dabPlus = o.optBoolean("plus", true);
            s.bitrate = o.optInt("br");
            s.missing = o.optBoolean("missing");
            JSONArray a = o.optJSONArray("locs");
            for (int i = 0; a != null && i < a.length(); i++) {
                JSONObject j = a.getJSONObject(i);
                Loc l = new Loc();
                l.eid = j.getInt("eid");
                l.khz = j.getInt("khz");
                l.ensemble = j.optString("ens", "");
                s.locs.add(l);
            }
        }
        return s;
    }
}
