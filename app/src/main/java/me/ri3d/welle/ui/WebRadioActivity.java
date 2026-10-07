package me.ri3d.welle.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.ri3d.welle.R;
import me.ri3d.welle.RadioService;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.logos.LogoStore;
import me.ri3d.welle.web.Net;
import me.ri3d.welle.web.Playlist;
import me.ri3d.welle.web.RadioBrowser;

/**
 * Web radio manager: find stations through the search API, a local playlist file or a
 * playlist URL, add one by hand, and maintain "My web stations".
 */
public final class WebRadioActivity extends BaseActivity {
    private static final int PICK_PLAYLIST = 20;
    private static final String[] COUNTRIES = {"", "DE", "AT", "CH", "GB", "IE", "FR", "NL", "BE", "LU", "IT", "ES", "PT", "PL", "CZ", "DK", "SE", "NO", "FI", "US", "CA"};
    private static final String[] GENRES = {"", "pop", "rock", "news", "classical", "jazz", "electronic", "oldies", "hits", "dance", "talk", "schlager", "alternative", "metal", "hiphop", "country", "kids", "culture"};

    private final Handler handler = new Handler();
    private final ArrayList<Station> fetched = new ArrayList<Station>();
    private final ArrayList<Station> results = new ArrayList<Station>();
    private final ArrayList<Station> mine = new ArrayList<Station>();
    private TextView mineCount, resultsLabel, resultsNote, mineNote, testLabel;
    private EditText apiUrl, m3uUrl, manualName, manualUrl;
    private String query = "";
    private String note = "";
    private String testId;
    private int generation;

    @Override
    protected View build() {
        final int source = settings.i(Settings.WEB_SOURCE);
        LinearLayout root = ui.col(0);
        mineCount = ui.tech("", 14, ui.t.dim);
        mineCount.setTypeface(ui.mono());
        root.addView(header(getString(R.string.web_radio_manager), mineCount));

        LinearLayout body = ui.row(28);
        ui.pad(body, 40, 28, 40, 28);

        // ---- left: source and manual entry (scrolls when the keyboard is up) ----
        LinearLayout left = ui.col(12);
        left.addView(caption(getString(R.string.station_source)));
        LinearLayout sources = ui.row(8);
        int[] names = {R.string.source_api, R.string.source_file, R.string.source_m3u};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            boolean sel = i == source;
            TextView b = ui.label(getString(names[i]), ui.bold(), 17, sel ? ui.t.onSelected : ui.t.text);
            b.setGravity(Gravity.CENTER);
            b.setBackground(ui.choice(sel));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    keepInputs();
                    settings.set(Settings.WEB_SOURCE, index);
                    fetched.clear();
                    note = "";
                    rebuild();
                }
            });
            sources.addView(b, ui.flex(0, 52));
        }
        left.addView(sources, ui.lp(Ui.MATCH, Ui.WRAP));

        if (source == 0) {
            left.addView(fieldLabel(R.string.api_url_label));
            apiUrl = ui.input("https://…", true);
            apiUrl.setText(settings.s(Settings.API_URL));
            left.addView(apiUrl, ui.lp(Ui.MATCH, 52));
            LinearLayout filters = ui.row(8);
            filters.addView(filterButton(R.string.country, countryName(settings.s(Settings.API_COUNTRY)), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chooseCountry();
                }
            }), ui.flex(0, 60));
            filters.addView(filterButton(R.string.genre, genreName(settings.s(Settings.API_GENRE)), new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    chooseGenre();
                }
            }), ui.flex(0, 60));
            left.addView(filters, ui.lp(Ui.MATCH, Ui.WRAP));
        } else if (source == 1) {
            left.addView(fieldLabel(R.string.file_label));
            left.addView(outline(R.string.select_file, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    pickFile(PICK_PLAYLIST);
                }
            }), ui.lp(Ui.MATCH, 56));
        } else {
            left.addView(fieldLabel(R.string.m3u_label));
            m3uUrl = ui.input("https://…/stations.m3u", true);
            m3uUrl.setText(settings.s(Settings.M3U_URL));
            left.addView(m3uUrl, ui.lp(Ui.MATCH, 52));
            left.addView(outline(R.string.load_playlist, new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    loadPlaylistUrl();
                }
            }), ui.lp(Ui.MATCH, 56));
        }

        left.addView(ui.spacer(), ui.flex(1, 0));

        View rule = new View(this);
        rule.setBackgroundColor(ui.t.line);
        left.addView(rule, ui.lp(Ui.MATCH, 1));
        left.addView(caption(getString(R.string.add_manually)));
        left.addView(fieldLabel(R.string.station_name));
        String keepName = manualName == null ? "" : manualName.getText().toString();
        String keepUrl = manualUrl == null ? "" : manualUrl.getText().toString();
        manualName = ui.input("", false);
        manualName.setText(keepName);
        left.addView(manualName, ui.lp(Ui.MATCH, 48));
        left.addView(fieldLabel(R.string.stream_url));
        manualUrl = ui.input("https://…", true);
        manualUrl.setText(keepUrl);
        left.addView(manualUrl, ui.lp(Ui.MATCH, 48));
        TextView add = ui.label(getString(R.string.add_and_play), ui.bold(), 18, ui.t.onAccent);
        add.setGravity(Gravity.CENTER);
        add.setBackground(ui.accentButton());
        add.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addManually();
            }
        });
        left.addView(add, ui.lp(Ui.MATCH, 56));

        ScrollView leftScroll = new ScrollView(this);
        leftScroll.setFillViewport(true);
        leftScroll.addView(left, new ScrollView.LayoutParams(Ui.MATCH, Ui.WRAP));
        body.addView(leftScroll, ui.lp(340, Ui.MATCH));

        // ---- middle: results ----
        LinearLayout mid = ui.col(12);
        LinearLayout searchBox = ui.col(8);
        resultsLabel = caption("");
        searchBox.addView(resultsLabel);
        EditText search = ui.input(getString(R.string.station_name), false);
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim();
                if (settings.i(Settings.WEB_SOURCE) == 0) {
                    // Wait for a pause in typing before asking the server.
                    handler.removeCallbacks(searchApi);
                    handler.postDelayed(searchApi, 700);
                } else {
                    show();
                }
            }
        });
        searchBox.addView(search, ui.lp(Ui.MATCH, 56));
        mid.addView(searchBox, ui.lp(Ui.MATCH, Ui.WRAP));
        resultsNote = ui.label("", ui.text(), 19, ui.t.dim);
        resultsNote.setSingleLine(false);
        mid.addView(resultsNote, ui.lp(Ui.MATCH, Ui.WRAP));
        mid.addView(list(resultAdapter), ui.flex(Ui.MATCH, 0));
        body.addView(mid, ui.flex(0, Ui.MATCH));

        // ---- right: my stations ----
        LinearLayout right = ui.col(12);
        LinearLayout head = ui.col(6);
        head.setGravity(Gravity.BOTTOM);
        testLabel = caption(getString(R.string.shown_on_web_tab));
        head.addView(testLabel);
        head.addView(ui.label(getString(R.string.my_web_stations), ui.cond(), 44, ui.t.text));
        right.addView(head, ui.lp(Ui.MATCH, 85));
        mineNote = ui.label(getString(R.string.no_web_stations_yet), ui.text(), 19, ui.t.dim);
        mineNote.setSingleLine(false);
        right.addView(mineNote, ui.lp(Ui.MATCH, Ui.WRAP));
        right.addView(list(mineAdapter), ui.flex(Ui.MATCH, 0));
        body.addView(right, ui.flex(0, Ui.MATCH));

        root.addView(body, ui.flex(Ui.MATCH, 0));
        show();
        if (source == 0 && fetched.isEmpty() && note.isEmpty()) handler.post(searchApi);
        return root;
    }

    private TextView caption(String text) {
        TextView t = ui.tech(text, 13, ui.t.dim);
        t.setTypeface(ui.mono());
        return t;
    }

    private TextView fieldLabel(int text) {
        return ui.label(getString(text), ui.text(), 15, ui.t.dim);
    }

    private View outline(int text, View.OnClickListener click) {
        TextView b = ui.label(getString(text), ui.bold(), 18, ui.t.text);
        b.setGravity(Gravity.CENTER);
        b.setBackground(ui.outlineButton());
        b.setOnClickListener(click);
        return b;
    }

    private View filterButton(int label, String value, View.OnClickListener click) {
        LinearLayout b = ui.col(2);
        b.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(b, 14, 0, 14, 0);
        b.setBackground(ui.panelButton());
        TextView l = ui.tech(getString(label), 12, ui.t.dim);
        l.setTypeface(ui.mono());
        b.addView(l);
        b.addView(ui.label(value, ui.bold(), 17, ui.t.text));
        b.setOnClickListener(click);
        return b;
    }

    private ListView list(BaseAdapter adapter) {
        ListView l = new ListView(this);
        l.setDivider(null);
        l.setDividerHeight(ui.px(8));
        l.setSelector(new android.graphics.drawable.ColorDrawable(0));
        l.setCacheColorHint(0);
        l.setAdapter(adapter);
        return l;
    }

    private String countryName(String code) {
        return code.isEmpty() ? getString(R.string.all) : new Locale("", code).getDisplayCountry(getResources().getConfiguration().locale);
    }

    private String genreName(String tag) {
        return tag.isEmpty() ? getString(R.string.all) : tag.substring(0, 1).toUpperCase(Locale.US) + tag.substring(1);
    }

    private void chooseCountry() {
        String[] items = new String[COUNTRIES.length];
        int selected = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = countryName(COUNTRIES[i]);
            if (COUNTRIES[i].equals(settings.s(Settings.API_COUNTRY))) selected = i;
        }
        Chooser.show(this, ui, getString(R.string.country), items, selected, new Chooser.OnPick() {
            @Override
            public void onPick(int index) {
                keepInputs();
                settings.set(Settings.API_COUNTRY, COUNTRIES[index]);
                fetched.clear();
                note = "";
                rebuild();
            }
        });
    }

    private void chooseGenre() {
        String[] items = new String[GENRES.length];
        int selected = 0;
        for (int i = 0; i < items.length; i++) {
            items[i] = genreName(GENRES[i]);
            if (GENRES[i].equals(settings.s(Settings.API_GENRE))) selected = i;
        }
        Chooser.show(this, ui, getString(R.string.genre), items, selected, new Chooser.OnPick() {
            @Override
            public void onPick(int index) {
                keepInputs();
                settings.set(Settings.API_GENRE, GENRES[index]);
                fetched.clear();
                note = "";
                rebuild();
            }
        });
    }

    /** Persists the editable URLs before the views are rebuilt or the screen is left. */
    private void keepInputs() {
        if (apiUrl != null && settings.i(Settings.WEB_SOURCE) == 0) {
            String u = apiUrl.getText().toString().trim();
            settings.set(Settings.API_URL, u.isEmpty() ? Settings.DEFAULT_API_URL : u);
        }
        if (m3uUrl != null && settings.i(Settings.WEB_SOURCE) == 2) settings.set(Settings.M3U_URL, m3uUrl.getText().toString().trim());
    }

    @Override
    protected void onPause() {
        keepInputs();
        super.onPause();
    }

    // ---- loading results ---------------------------------------------------------------------

    private final Runnable searchApi = new Runnable() {
        @Override
        public void run() {
            if (settings.i(Settings.WEB_SOURCE) != 0) return;
            keepInputs();
            final String base = settings.s(Settings.API_URL), country = settings.s(Settings.API_COUNTRY), tag = settings.s(Settings.API_GENRE), name = query;
            load(new Loader() {
                @Override
                public List<Station> load() throws Exception {
                    return RadioBrowser.search(base, name, country, tag);
                }
            });
        }
    };

    private void loadPlaylistUrl() {
        keepInputs();
        final String url = settings.s(Settings.M3U_URL);
        if (Playlist.resolve(null, url) == null) {
            toast(getString(R.string.invalid_url));
            return;
        }
        load(new Loader() {
            @Override
            public List<Station> load() throws Exception {
                return fromPlaylist(decode(Net.get(url, Playlist.MAX_BYTES)), url);
            }
        });
    }

    private interface Loader {
        List<Station> load() throws Exception;
    }

    /** Runs a network request off the main thread; a newer request supersedes an older one. */
    private void load(final Loader loader) {
        final int gen = ++generation;
        note = getString(R.string.loading);
        fetched.clear();
        show();
        new Thread("web-manager") {
            @Override
            public void run() {
                List<Station> out = null;
                String error = null;
                try {
                    out = loader.load();
                } catch (Exception e) {
                    error = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final List<Station> list = out;
                final String failed = error;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (gen != generation || ui == null) return;
                        fetched.clear();
                        if (list != null) fetched.addAll(list);
                        note = failed == null ? "" : getString(R.string.load_failed, failed);
                        show();
                    }
                });
            }
        }.start();
    }

    private static String decode(byte[] data) throws java.io.UnsupportedEncodingException {
        String s = new String(data, "UTF-8");
        // Plain .m3u files are often Latin-1; undecodable bytes show up as U+FFFD.
        return s.indexOf(0xFFFD) >= 0 ? new String(data, "ISO-8859-1") : s;
    }

    /**
     * A station playlist becomes one result per entry. An HLS manifest is a single stream,
     * so it becomes one station pointing at the manifest itself.
     */
    private static List<Station> fromPlaylist(String text, String baseUrl) {
        ArrayList<Station> out = new ArrayList<Station>();
        if (Playlist.isHls(text)) {
            if (baseUrl != null) out.add(Station.web(Playlist.nameFromUrl(baseUrl), baseUrl, null));
            return out;
        }
        for (Playlist.Entry e : Playlist.parse(text, baseUrl)) out.add(Station.web(e.name, e.url, null));
        return out;
    }

    @Override
    protected void onFilePicked(int request, Uri uri) {
        if (request != PICK_PLAYLIST) return;
        generation++;
        fetched.clear();
        try {
            String text = decode(readPicked(uri, Playlist.MAX_BYTES));
            if (Playlist.isHls(text)) {
                note = getString(R.string.file_is_hls);
            } else {
                fetched.addAll(fromPlaylist(text, null));
                note = fetched.isEmpty() ? getString(R.string.file_no_stations) : "";
            }
        } catch (Exception e) {
            note = getString(R.string.load_failed, String.valueOf(e.getMessage()));
        }
        if (ui != null) show();
    }

    // ---- lists -------------------------------------------------------------------------------

    private void show() {
        int source = settings.i(Settings.WEB_SOURCE);
        results.clear();
        String q = query.toLowerCase(Locale.getDefault());
        for (Station s : fetched) {
            if (app.stations.find(s.id) != null) continue; // already added
            if (source != 0 && !q.isEmpty() && !s.name.toLowerCase(Locale.getDefault()).contains(q)) continue;
            results.add(s);
        }
        mine.clear();
        mine.addAll(app.stations.web);

        String where = source == 0 ? countryName(settings.s(Settings.API_COUNTRY)) : getString(source == 1 ? R.string.source_file : R.string.source_m3u);
        ui.setTech(resultsLabel, getString(R.string.search_results, where, results.size()));
        String n = note;
        if (n.isEmpty() && results.isEmpty()) {
            n = getString(fetched.isEmpty() ? (source == 0 ? R.string.no_results : R.string.nothing_loaded) : R.string.nothing_left_to_add);
        }
        resultsNote.setText(n);
        resultsNote.setVisibility(n.isEmpty() ? View.GONE : View.VISIBLE);
        mineNote.setVisibility(mine.isEmpty() ? View.VISIBLE : View.GONE);
        ui.setTech(mineCount, getResources().getQuantityString(R.plurals.web_stations, mine.size(), mine.size()));
        resultAdapter.notifyDataSetChanged();
        mineAdapter.notifyDataSetChanged();
    }

    @Override
    protected void refresh() {
        // Outcome of a stream test, shown above "My web stations".
        String text = getString(R.string.shown_on_web_tab);
        if (testId != null && radio.station != null && radio.station.id.equals(testId)) {
            String state = radio.playState == RadioService.PLAYING
                    ? getString(R.string.test_ok) + (radio.webFormat.isEmpty() ? "" : " · " + radio.webFormat)
                    : radio.status;
            text = radio.station.name + ": " + state;
        }
        ui.setTech(testLabel, text);
        testLabel.setTextColor(testId == null ? ui.t.dim : ui.t.mark);
    }

    private void add(Station s) {
        app.stations.addWeb(s);
        fetchLogo(s);
        show();
    }

    /** Stores the catalogue icon of a newly added station, if it has one. */
    private void fetchLogo(final Station s) {
        if (s.logoUrl == null || app.logos.has(s.id)) return;
        new Thread("web-logo") {
            @Override
            public void run() {
                try {
                    app.logos.put(s.id, Net.get(s.logoUrl, 1024 * 1024), LogoStore.AUTO);
                } catch (Exception ignored) {
                    // No icon is fine; the monogram is shown instead.
                }
            }
        }.start();
    }

    private void addManually() {
        String url = Playlist.resolve(null, manualUrl.getText().toString().trim());
        if (url == null) {
            toast(getString(R.string.invalid_url));
            return;
        }
        Station s = Station.web(manualName.getText().toString(), url, null);
        Station existing = app.stations.find(s.id);
        if (existing == null) app.stations.addWeb(s);
        else s = existing;
        manualName.setText("");
        manualUrl.setText("");
        if (radio != null) radio.tune(s);
        startActivity(new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    private View rowBase(Station s) {
        LinearLayout row = ui.row(12);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(row, 10, 0, 8, 0);
        row.setBackground(ui.box(ui.t.panel, ui.t.line, 6));
        row.addView(StationsActivity.tile(ui, app.logos.get(s.id, ui.px(44)), s.mono(), 44, 22), ui.lp(44, 44));
        row.addView(ui.label(s.name, ui.bold(), 19, ui.t.text), ui.flex(0, Ui.WRAP));
        row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, ui.px(64)));
        return row;
    }

    private final BaseAdapter resultAdapter = new BaseAdapter() {
        @Override public int getCount() { return results.size(); }
        @Override public Object getItem(int i) { return results.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final Station s = results.get(i);
            LinearLayout row = (LinearLayout) rowBase(s);
            TextView add = ui.tech(getString(R.string.add), 14, ui.t.mark);
            add.setGravity(Gravity.CENTER);
            ui.pad(add, 18, 0, 18, 0);
            add.setBackground(ui.button(0, ui.t.mark, 4));
            add.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    add(s);
                }
            });
            row.addView(add, ui.lp(Ui.WRAP, 48));
            return row;
        }
    };

    private final BaseAdapter mineAdapter = new BaseAdapter() {
        @Override public int getCount() { return mine.size(); }
        @Override public Object getItem(int i) { return mine.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            final Station s = mine.get(i);
            LinearLayout row = (LinearLayout) rowBase(s);
            IconView test = new IconView(WebRadioActivity.this, IconView.PLAY, ui.px(20), ui.t.text);
            test.setContentDescription(getString(R.string.cd_test_stream));
            test.setBackground(ui.button(0, ui.t.lineStrong, 4));
            test.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    // Testing plays through the one and only player, replacing what was on.
                    testId = s.id;
                    if (radio != null) radio.tune(s);
                }
            });
            IconView remove = new IconView(WebRadioActivity.this, IconView.CLOSE, ui.px(20), ui.t.text);
            remove.setContentDescription(getString(R.string.cd_remove_station));
            remove.setBackground(ui.button(0, ui.t.lineStrong, 4));
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (radio != null) radio.forget(s.id);
                    app.stations.removeWeb(s.id);
                    app.logos.remove(s.id);
                    if (s.id.equals(testId)) testId = null;
                    show();
                }
            });
            LinearLayout.LayoutParams lp = ui.lp(48, 48);
            row.addView(test, lp);
            LinearLayout.LayoutParams lp2 = ui.lp(48, 48);
            lp2.leftMargin = -ui.px(4);
            row.addView(remove, lp2);
            return row;
        }
    };
}
