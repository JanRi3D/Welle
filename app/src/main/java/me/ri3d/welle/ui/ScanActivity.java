package me.ri3d.welle.ui;

import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;

import me.ri3d.welle.R;
import me.ri3d.welle.core.DabChannels;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.core.StationStore;
import me.ri3d.welle.core.UsbLink;

/**
 * Band III scan. The scan itself runs in the service; the saved station list is only
 * replaced when the scan reaches the last channel, so stopping, leaving the screen or
 * unplugging the tuner never damages the list.
 */
public final class ScanActivity extends BaseActivity {
    private TextView found, action, discard, empty;
    private NameView status;
    private TextView[] modeTag = new TextView[2];
    private View[] modeButton = new View[2];
    private RulerView ruler;
    private GridView grid;
    private final ArrayList<Station> rows = new ArrayList<Station>();

    @Override
    protected View build() {
        LinearLayout root = ui.col(0);
        root.addView(header(getString(R.string.station_scan), null));

        LinearLayout top = ui.row(40);
        top.setGravity(Gravity.BOTTOM);
        ui.pad(top, 40, 24, 40, 0);
        LinearLayout titles = ui.col(8);
        found = ui.tech("", 16, ui.t.dim);
        found.setTypeface(ui.mono());
        // 84 units in the design; steps down so longer translations still fit beside the buttons.
        status = new NameView(ui, ui.t.text, 84, 68, 56, 44);
        titles.addView(found);
        titles.addView(status, ui.lp(Ui.MATCH, Ui.WRAP));
        top.addView(titles, ui.flex(0, Ui.WRAP));

        LinearLayout controls = ui.row(8);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.addView(modeButton(0, R.string.scan_mode1, R.string.scan_mode1_label), ui.lp(200, 84));
        controls.addView(modeButton(1, R.string.scan_mode2, R.string.scan_mode2_label), ui.lp(200, 84));
        discard = ui.label(getString(R.string.scan_discard), ui.bold(), 18, ui.t.text);
        discard.setGravity(Gravity.CENTER);
        discard.setBackground(ui.outlineButton());
        discard.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio != null) radio.discardScan();
            }
        });
        controls.addView(discard, ui.lp(140, 84));
        action = ui.label("", ui.bold(), 21, ui.t.onAccent);
        action.setGravity(Gravity.CENTER);
        action.setBackground(ui.accentButton());
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio == null) return;
                if (radio.scanning) radio.stopScan();
                else {
                    if (radio.scanDone) radio.discardScan(); // "Scan again" starts from 5A
                    radio.startScan();
                }
            }
        });
        controls.addView(action, ui.lp(180, 84));
        top.addView(controls);
        root.addView(top, ui.lp(Ui.MATCH, Ui.WRAP));

        ruler = new RulerView(ui);
        ruler.setMode(RulerView.SCAN);
        LinearLayout.LayoutParams rp = ui.lp(Ui.MATCH, 112);
        rp.leftMargin = rp.rightMargin = ui.px(40);
        rp.topMargin = ui.px(24);
        root.addView(ruler, rp);

        LinearLayout list = ui.col(12);
        ui.pad(list, 40, 20, 40, 28);
        TextView label = ui.tech(getString(R.string.found_so_far), 13, ui.t.dim);
        label.setTypeface(ui.mono());
        list.addView(label);
        empty = ui.label("", ui.text(), 19, ui.t.dim);
        empty.setSingleLine(false);
        list.addView(empty, ui.lp(Ui.MATCH, Ui.WRAP));
        grid = new GridView(this);
        grid.setNumColumns(columns(4));
        grid.setHorizontalSpacing(ui.px(8));
        grid.setVerticalSpacing(ui.px(8));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(adapter);
        list.addView(grid, ui.flex(Ui.MATCH, 0));
        root.addView(list, ui.flex(Ui.MATCH, 0));
        return root;
    }

    private View modeButton(final int index, int tag, int label) {
        LinearLayout b = ui.col(4);
        b.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(b, 16, 0, 16, 0);
        modeTag[index] = ui.tech(getString(tag), 12, ui.t.dim);
        modeTag[index].setTypeface(ui.mono());
        TextView text = ui.label(getString(label), ui.bold(), 18, ui.t.text);
        text.setSingleLine(false);
        text.setMaxLines(2);
        b.addView(modeTag[index]);
        b.addView(text);
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // The mode decides what happens when the scan completes, so it can still be changed mid-scan.
                settings.set(Settings.SCAN_MODE, index);
                if (radio != null) refresh();
            }
        });
        modeButton[index] = b;
        return b;
    }

    @Override
    protected void refresh() {
        int total = DabChannels.COUNT;
        int index = Math.min(radio.scanIndex, total);
        int percent = Math.round(index * 100f / total);
        boolean ready = radio.usbState == UsbLink.READY;
        boolean idle = !radio.scanning && !radio.scanPaused && !radio.scanDone;

        int n = radio.scanFound.size();
        ui.setTech(found, getString(R.string.band_iii) + " · " + getResources().getQuantityString(R.plurals.stations_found, n, n));
        String s;
        if (radio.scanDone) s = getString(R.string.scan_complete);
        else if (radio.scanning) s = getString(R.string.scanning_percent, percent);
        else if (radio.scanPaused) s = getString(R.string.paused_at_percent, percent);
        else s = getString(ready ? R.string.ready_to_scan : R.string.notice_none_title);
        status.setText(s);
        action.setText(getString(radio.scanning ? R.string.stop : radio.scanDone ? R.string.scan_again
                : radio.scanPaused ? R.string.scan_continue : R.string.start_scan));
        action.setEnabled(ready);
        action.setAlpha(ready ? 1f : 0.4f);
        discard.setVisibility(radio.scanPaused && !radio.scanning ? View.VISIBLE : View.GONE);

        int mode = settings.i(Settings.SCAN_MODE);
        for (int i = 0; i < 2; i++) {
            boolean sel = i == mode;
            modeButton[i].setBackground(ui.button(sel ? ui.t.tile : ui.t.panel, sel ? ui.t.selectedRing : ui.t.line, 6));
            modeTag[i].setTextColor(sel ? ui.t.mark : ui.t.dim);
        }

        boolean[] dots = new boolean[DabChannels.RULER_TICKS];
        for (Station st : radio.scanFound) {
            for (Station.Loc l : st.locs) {
                int tick = DabChannels.rulerTick(DabChannels.indexOf(l.khz));
                if (tick >= 0) dots[tick] = true;
            }
        }
        ruler.setDots(dots);
        String channel = index < total ? getString(R.string.channel_n, DabChannels.LABEL[index]).toUpperCase(java.util.Locale.getDefault()) : "";
        ruler.setNeedle(idle ? -1 : DabChannels.rulerPos(index), channel, radio.scanning ? 160 : 0);

        rows.clear();
        rows.addAll(radio.scanFound);
        adapter.notifyDataSetChanged();

        String note = "";
        StationStore.ScanOutcome out = radio.scanOutcome;
        if (radio.scanDone && out != null) {
            if (out.found == 0) note = getString(R.string.scan_none_guidance);
            else if (out.keptMissing > 0) note = getResources().getQuantityString(R.plurals.scan_kept, out.keptMissing, out.keptMissing);
            else if (out.clearedPresets > 0) note = getResources().getQuantityString(R.plurals.scan_cleared, out.clearedPresets, out.clearedPresets);
        } else if (!ready) {
            note = getString(R.string.scan_needs_adapter);
        } else if (radio.scanPaused) {
            note = getString(R.string.scan_paused_note);
        } else if (n == 0) {
            note = getString(R.string.none_found_yet);
        }
        empty.setText(note);
        empty.setVisibility(note.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return false; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Station s = rows.get(i);
            LinearLayout row = ui.row(12);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(row, 8, 0, 12, 0);
            row.setBackground(ui.box(ui.t.panel, ui.t.line, 6));
            row.addView(StationsActivity.tile(ui, null, s.mono(), 40, 20), ui.lp(40, 40));
            row.addView(ui.label(s.name, ui.bold(), 17, ui.t.text), ui.flex(0, Ui.WRAP));
            row.addView(ui.label(s.loc() == null ? "" : DabChannels.label(s.loc().khz), ui.mono(), 13, ui.t.dim));
            row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, ui.px(56)));
            return row;
        }
    };
}
