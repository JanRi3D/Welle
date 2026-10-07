package me.ri3d.welle.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import me.ri3d.welle.R;
import me.ri3d.welle.RadioService;
import me.ri3d.welle.core.DabChannels;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.core.StationStore;

/** Station list of the active source with search, ensemble filter and preset assignment. */
public final class StationsActivity extends BaseActivity {
    private TextView count, empty;
    private LinearLayout filters, action;
    private GridView grid;
    private final ArrayList<Station> rows = new ArrayList<Station>();
    private String query = "";
    /** Ensemble filter as "eid/khz", or "" for all stations. */
    private String filter = "";
    private int source = -1;

    @Override
    protected View build() {
        LinearLayout root = ui.col(0);
        count = ui.tech("", 14, ui.t.dim);
        count.setTypeface(ui.mono());
        root.addView(header(getString(R.string.stations), count));

        LinearLayout body = ui.row(28);
        ui.pad(body, 40, 28, 40, 28);

        LinearLayout left = ui.col(20);
        LinearLayout searchBox = ui.col(8);
        TextView searchLabel = ui.tech(getString(R.string.search), 13, ui.t.dim);
        searchLabel.setTypeface(ui.mono());
        searchBox.addView(searchLabel);
        EditText search = ui.input(getString(R.string.station_name), false);
        search.setText(query);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                query = s.toString().trim().toLowerCase(Locale.getDefault());
                if (radio != null) fill();
            }
        });
        searchBox.addView(search, ui.lp(Ui.MATCH, 56));
        left.addView(searchBox, ui.lp(Ui.MATCH, Ui.WRAP));

        filters = ui.col(8);
        ScrollView filterScroll = new ScrollView(this);
        filterScroll.addView(filters);
        left.addView(filterScroll, ui.flex(Ui.MATCH, 0));

        action = ui.row(12);
        action.setGravity(Gravity.CENTER);
        action.setBackground(ui.outlineButton());
        left.addView(action, ui.lp(Ui.MATCH, 64));
        body.addView(left, ui.lp(300, Ui.MATCH));

        LinearLayout right = ui.col(14);
        TextView hint = ui.tech(getString(R.string.tap_tune_hold_save), 13, ui.t.dim);
        hint.setTypeface(ui.mono());
        right.addView(hint);
        empty = ui.label(getString(R.string.no_station_matches), ui.text(), 22, ui.t.dim);
        empty.setSingleLine(false);
        empty.setVisibility(View.GONE);
        right.addView(empty, ui.lp(Ui.MATCH, Ui.WRAP));
        grid = new GridView(this);
        grid.setNumColumns(columns(2));
        grid.setHorizontalSpacing(ui.px(8));
        grid.setVerticalSpacing(ui.px(8));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setCacheColorHint(0);
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                if (radio == null) return;
                radio.tune(rows.get(position));
                finish();
            }
        });
        // Consumed long press: assigning a preset never also tunes the station.
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                assignPreset(rows.get(position));
                return true;
            }
        });
        right.addView(grid, ui.flex(Ui.MATCH, 0));
        body.addView(right, ui.flex(0, Ui.MATCH));

        root.addView(body, ui.flex(Ui.MATCH, 0));
        source = -1;
        return root;
    }

    @Override
    protected void refresh() {
        if (radio.source != source) {
            source = radio.source;
            filter = "";
            buildSideColumn();
        }
        fill();
    }

    private void buildSideColumn() {
        final boolean dab = source == RadioService.SRC_DAB;
        action.removeAllViews();
        if (dab) action.addView(new IconView(this, IconView.SCAN, ui.px(24), ui.t.text), ui.lp(24, 24));
        action.addView(ui.label(getString(dab ? R.string.scan_stations : R.string.web_radio_manager), ui.bold(), 19, ui.t.text));
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(StationsActivity.this, dab ? ScanActivity.class : WebRadioActivity.class));
            }
        });
        buildFilters();
    }

    private void buildFilters() {
        filters.removeAllViews();
        TextView label = ui.tech(getString(source == RadioService.SRC_DAB ? R.string.ensemble : R.string.tab_web), 13, ui.t.dim);
        label.setTypeface(ui.mono());
        filters.addView(label);
        StationStore store = app.stations;
        List<StationStore.Ensemble> ensembles = source == RadioService.SRC_DAB ? store.ensembles() : new ArrayList<StationStore.Ensemble>();
        String allSub = source == RadioService.SRC_DAB
                ? getResources().getQuantityString(R.plurals.ensembles, ensembles.size(), ensembles.size())
                : getString(R.string.my_web_stations);
        filters.addView(filterButton("", getString(R.string.all_stations), allSub, store.list(source).size()), ui.lp(Ui.MATCH, 68));
        for (StationStore.Ensemble e : ensembles) {
            String sub = DabChannels.label(e.khz) + " · " + DabChannels.mhz(e.khz) + " MHz";
            filters.addView(filterButton(e.eid + "/" + e.khz, e.label.isEmpty() ? DabChannels.label(e.khz) : e.label, sub, e.count), ui.lp(Ui.MATCH, 68));
        }
    }

    private View filterButton(final String id, String title, String sub, int n) {
        boolean sel = id.equals(filter);
        int fg = sel ? ui.t.onSelected : ui.t.text;
        LinearLayout b = ui.row(12);
        b.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(b, 16, 0, 16, 0);
        b.setBackground(ui.choice(sel));
        LinearLayout texts = ui.col(4);
        texts.addView(ui.label(title, ui.bold(), 19, fg));
        TextView s = ui.label(sub.toUpperCase(Locale.getDefault()), ui.mono(), 13, fg);
        texts.addView(s);
        b.addView(texts, ui.flex(0, Ui.WRAP));
        b.addView(ui.label(String.valueOf(n), ui.cond(), 30, fg));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filter = id;
                buildFilters();
                fill();
            }
        });
        return b;
    }

    private void fill() {
        rows.clear();
        for (Station s : app.stations.list(source)) {
            if (!query.isEmpty() && !s.name.toLowerCase(Locale.getDefault()).contains(query)) continue;
            if (!filter.isEmpty()) {
                boolean in = false;
                for (Station.Loc l : s.locs) if ((l.eid + "/" + l.khz).equals(filter)) in = true;
                if (!in) continue;
            }
            rows.add(s);
        }
        ui.setTech(count, getResources().getQuantityString(R.plurals.stations, rows.size(), rows.size()));
        empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
        empty.setText(getString(app.stations.list(source).isEmpty()
                ? (source == RadioService.SRC_DAB ? R.string.notice_no_stations_text : R.string.notice_no_web_text)
                : R.string.no_station_matches));
        adapter.notifyDataSetChanged();
    }

    private void assignPreset(final Station s) {
        final StationStore store = app.stations;
        int slots = settings.perPage() * settings.pages();
        String[] items = new String[slots];
        for (int i = 0; i < slots; i++) {
            Station p = store.find(store.presets[i]);
            items[i] = String.format(Locale.US, "%02d  ·  %s", i + 1, p == null ? getString(R.string.preset_empty) : p.name);
        }
        Chooser.show(this, ui, getString(R.string.preset_choose, s.name), items, store.presetOf(s.id), new Chooser.OnPick() {
            @Override
            public void onPick(int slot) {
                int old = store.presetOf(s.id);
                if (old >= 0 && old != slot) store.presets[old] = null;
                store.setPreset(slot, s.id);
                toast(getString(R.string.preset_saved, s.name, slot + 1));
                adapter.notifyDataSetChanged();
            }
        });
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            // Rows are rebuilt rather than recycled field by field: the list is short-lived
            // and a row is five small views.
            Station s = rows.get(i);
            boolean current = radio != null && radio.station != null && radio.station.id.equals(s.id);
            LinearLayout row = ui.row(14);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(row, 12, 0, 16, 0);
            row.setBackground(ui.box(ui.t.panel, current ? ui.t.mark : ui.t.line, 6));
            row.addView(tile(ui, app.logos.get(s.id, ui.px(52)), s.mono(), 52, 26), ui.lp(52, 52));

            LinearLayout texts = ui.col(4);
            texts.addView(ui.label(s.name, ui.bold(), 20, ui.t.text));
            String sub;
            if (s.isDab() && s.loc() != null) {
                sub = s.loc().ensemble + " · " + DabChannels.label(s.loc().khz);
                if (s.missing) sub += " · " + getString(R.string.not_in_last_scan);
            } else {
                sub = getString(R.string.tab_web);
            }
            texts.addView(ui.label(sub.toUpperCase(Locale.getDefault()), ui.mono(), 13, ui.t.dim));
            row.addView(texts, ui.flex(0, Ui.WRAP));

            int preset = app.stations.presetOf(s.id);
            if (preset >= 0) {
                TextView badge = ui.label(String.format(Locale.US, "%02d", preset + 1), ui.monoMed(), 13, ui.t.mark);
                ui.pad(badge, 8, 4, 8, 4);
                badge.setBackground(ui.box(0, ui.t.mark, 4));
                row.addView(badge);
            }
            row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, ui.px(76)));
            return row;
        }
    };

    /** Square logo tile, or the station's monogram when it has no logo. */
    static View tile(Ui ui, Bitmap logo, String mono, float size, float textSize) {
        if (logo != null) {
            ImageView v = new ImageView(ui.c);
            v.setImageBitmap(logo);
            v.setScaleType(ImageView.ScaleType.FIT_CENTER);
            v.setBackground(ui.box(ui.t.tile, 0, 4));
            return v;
        }
        TextView v = ui.label(mono, ui.cond(), textSize, ui.t.text);
        v.setGravity(Gravity.CENTER);
        v.setBackground(ui.box(ui.t.tile, 0, 4));
        return v;
    }
}
