package me.ri3d.welle.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Station lists and presets, persisted as one JSON file that is replaced atomically.
 * Not thread-safe: use from the main thread.
 */
public final class StationStore {

    public static final class Ensemble {
        public int eid;
        public int khz;
        public String label = "";
        public int count;
    }

    public static final class ScanOutcome {
        public int found;
        /** Favourites the scan did not find but that were kept (mode 1). */
        public int keptMissing;
        /** Presets cleared because their station is gone (mode 2). */
        public int clearedPresets;
    }

    private static final Comparator<Station> BY_NAME = new Comparator<Station>() {
        @Override
        public int compare(Station a, Station b) {
            int c = a.name.compareToIgnoreCase(b.name);
            return c != 0 ? c : a.id.compareTo(b.id);
        }
    };

    private final File file;
    public final ArrayList<Station> dab = new ArrayList<Station>();
    public final ArrayList<Station> web = new ArrayList<Station>();
    /** Station ids by slot; null is an empty slot. */
    public final String[] presets = new String[Settings.MAX_PRESETS];

    public StationStore(File file) {
        this.file = file;
        load();
    }

    public Station find(String id) {
        if (id == null) return null;
        for (Station s : dab) if (s.id.equals(id)) return s;
        for (Station s : web) if (s.id.equals(id)) return s;
        return null;
    }

    public ArrayList<Station> list(int source) {
        return source == 0 ? dab : web;
    }

    /** Slot holding this station, or -1. */
    public int presetOf(String id) {
        for (int i = 0; i < presets.length; i++) if (id != null && id.equals(presets[i])) return i;
        return -1;
    }

    public void setPreset(int slot, String id) {
        presets[slot] = id;
        save();
    }

    /** @return false if a station with this URL already exists. */
    public boolean addWeb(Station s) {
        if (find(s.id) != null) return false;
        web.add(s);
        save();
        return true;
    }

    public void removeWeb(String id) {
        for (int i = web.size() - 1; i >= 0; i--) if (web.get(i).id.equals(id)) web.remove(i);
        for (int i = 0; i < presets.length; i++) if (id.equals(presets[i])) presets[i] = null;
        save();
    }

    /** Ensembles of the DAB list in channel order, for the filter column. */
    public List<Ensemble> ensembles() {
        ArrayList<Ensemble> out = new ArrayList<Ensemble>();
        for (Station s : dab) {
            for (Station.Loc l : s.locs) {
                Ensemble e = null;
                for (Ensemble x : out) if (x.eid == l.eid && x.khz == l.khz) e = x;
                if (e == null) {
                    e = new Ensemble();
                    e.eid = l.eid;
                    e.khz = l.khz;
                    e.label = l.ensemble;
                    out.add(e);
                }
                e.count++;
            }
        }
        Collections.sort(out, new Comparator<Ensemble>() {
            @Override
            public int compare(Ensemble a, Ensemble b) {
                return a.khz - b.khz;
            }
        });
        return out;
    }

    /**
     * Adds one scanned service to a result list. The same SId seen on a second ensemble
     * becomes another location of the same station, which is what service following uses.
     */
    public static void mergeFound(List<Station> list, Station found) {
        for (Station s : list) {
            if (!s.id.equals(found.id)) continue;
            if (s.ecc == 0) s.ecc = found.ecc;
            for (Station.Loc n : found.locs) {
                boolean known = false;
                for (Station.Loc l : s.locs) if (l.eid == n.eid && l.khz == n.khz) known = true;
                if (!known) s.locs.add(n);
            }
            return;
        }
        list.add(found);
    }

    /**
     * Replaces the DAB list with the result of a completed scan.
     *
     * Mode 1 (keepFavourites): stations that sit on a preset but were not found stay in the
     * list, flagged {@link Station#missing}, with their last known frequency.
     * Mode 2: the list becomes exactly the scan result; presets of vanished DAB stations
     * are cleared. Web stations and their presets are never touched.
     */
    public ScanOutcome applyScan(List<Station> found, boolean keepFavourites) {
        ScanOutcome out = new ScanOutcome();
        ArrayList<Station> next = new ArrayList<Station>(found);
        for (Station s : next) s.missing = false;
        out.found = next.size();
        if (keepFavourites) {
            for (Station old : dab) {
                if (presetOf(old.id) >= 0 && indexOf(next, old.id) < 0) {
                    old.missing = true;
                    next.add(old);
                    out.keptMissing++;
                }
            }
        } else {
            for (int i = 0; i < presets.length; i++) {
                String id = presets[i];
                if (id != null && id.startsWith("dab:") && indexOf(next, id) < 0) {
                    presets[i] = null;
                    out.clearedPresets++;
                }
            }
        }
        Collections.sort(next, BY_NAME);
        dab.clear();
        dab.addAll(next);
        save();
        return out;
    }

    private static int indexOf(List<Station> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    // ---- persistence ----------------------------------------------------------------------

    private void load() {
        if (!file.exists()) return;
        try {
            FileInputStream in = new FileInputStream(file);
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try {
                byte[] b = new byte[8192];
                int n;
                while ((n = in.read(b)) > 0) buf.write(b, 0, n);
            } finally {
                in.close();
            }
            JSONObject o = new JSONObject(buf.toString("UTF-8"));
            JSONArray a = o.optJSONArray("dab");
            for (int i = 0; a != null && i < a.length(); i++) dab.add(Station.fromJson(a.getJSONObject(i)));
            a = o.optJSONArray("web");
            for (int i = 0; a != null && i < a.length(); i++) web.add(Station.fromJson(a.getJSONObject(i)));
            a = o.optJSONArray("presets");
            for (int i = 0; a != null && i < a.length() && i < presets.length; i++) {
                presets[i] = a.isNull(i) ? null : a.getString(i);
            }
        } catch (Exception e) {
            // An unreadable file must not take the app down; keep it for inspection and start empty.
            dab.clear();
            web.clear();
            file.renameTo(new File(file.getPath() + ".bad"));
        }
    }

    public void save() {
        try {
            JSONObject o = new JSONObject();
            o.put("v", 1);
            JSONArray a = new JSONArray();
            for (Station s : dab) a.put(s.toJson());
            o.put("dab", a);
            a = new JSONArray();
            for (Station s : web) a.put(s.toJson());
            o.put("web", a);
            a = new JSONArray();
            for (String id : presets) a.put(id == null ? JSONObject.NULL : id);
            o.put("presets", a);

            // Write beside the target and rename, so a crash or power cut mid-write
            // (ignition off) leaves the previous list intact.
            File tmp = new File(file.getPath() + ".tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(o.toString().getBytes("UTF-8"));
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(file)) {
                file.delete();
                tmp.renameTo(file);
            }
        } catch (Exception e) {
            // Nothing sensible to do here; the in-memory state stays valid.
        }
    }
}
