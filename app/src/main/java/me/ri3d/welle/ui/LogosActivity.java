package me.ri3d.welle.ui;

import android.annotation.TargetApi;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;

import me.ri3d.welle.R;
import me.ri3d.welle.core.Io;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.logos.LogoJob;
import me.ri3d.welle.logos.LogoStore;

/** Station logos: RadioDNS download, logo pack import/export, manual choice per station. */
public final class LogosActivity extends BaseActivity implements LogoJob.Listener {
    private static final int PICK_LOGO = 30, PICK_PACK = 31, PERMISSION_EXPORT = 32;
    private static final String PACK_NAME = "welle-logos.zip";

    private final ArrayList<Station> rows = new ArrayList<Station>();
    private TextView progressLine, progressBig;
    private View progressFill, progressBlock;
    private String pickFor;

    @Override
    protected View build() {
        LinearLayout root = ui.col(0);
        root.addView(header(getString(R.string.station_logos), null));

        LinearLayout body = ui.row(28);
        ui.pad(body, 40, 28, 40, 28);

        LinearLayout left = ui.col(8);
        left.addView(bigButton(R.string.download_radiodns, R.string.needs_internet, true, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                app.logoJob.start(LogosActivity.this, stations(), app.logos);
            }
        }), ui.lp(Ui.MATCH, 76));
        left.addView(bigButton(R.string.import_pack, R.string.import_pack_sum, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pickFile(PICK_PACK);
            }
        }), ui.lp(Ui.MATCH, 76));
        left.addView(bigButton(R.string.export_pack, R.string.export_pack_sum, false, new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportPack();
            }
        }), ui.lp(Ui.MATCH, 76));
        left.addView(ui.spacer(), ui.flex(1, 0));

        LinearLayout progress = ui.col(12);
        View rule = new View(this);
        rule.setBackgroundColor(ui.t.line);
        progress.addView(rule, ui.lp(Ui.MATCH, 1));
        progressLine = ui.tech("", 13, ui.t.dim);
        progressLine.setTypeface(ui.mono());
        progress.addView(progressLine);
        progressBig = ui.label("", ui.cond(), 44, ui.t.text);
        progress.addView(progressBig);
        LinearLayout track = ui.row(0);
        track.setBackgroundColor(ui.t.line);
        progressFill = new View(this);
        progressFill.setBackgroundColor(ui.t.mark);
        track.addView(progressFill, new LinearLayout.LayoutParams(0, Ui.MATCH, 0f));
        track.addView(ui.spacer(), new LinearLayout.LayoutParams(0, Ui.MATCH, 1f));
        progress.addView(track, ui.lp(Ui.MATCH, 8));
        progressBlock = progress;
        left.addView(progress, ui.lp(Ui.MATCH, Ui.WRAP));
        body.addView(left, ui.lp(340, Ui.MATCH));

        LinearLayout right = ui.col(14);
        TextView hint = ui.tech(getString(R.string.tap_set_hold_remove), 13, ui.t.dim);
        hint.setTypeface(ui.mono());
        right.addView(hint);
        GridView grid = new GridView(this);
        grid.setNumColumns(columns(3));
        grid.setHorizontalSpacing(ui.px(8));
        grid.setVerticalSpacing(ui.px(8));
        grid.setSelector(new android.graphics.drawable.ColorDrawable(0));
        grid.setAdapter(adapter);
        grid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                pickFor = rows.get(position).id;
                pickFile(PICK_LOGO);
            }
        });
        grid.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                final Station s = rows.get(position);
                if (!app.logos.has(s.id)) return true;
                Chooser.show(LogosActivity.this, ui, getString(R.string.logo_remove_title, s.name),
                        new String[]{getString(R.string.logo_remove)}, -1, new Chooser.OnPick() {
                            @Override
                            public void onPick(int index) {
                                app.logos.remove(s.id);
                                show();
                            }
                        });
                return true;
            }
        });
        right.addView(grid, ui.flex(Ui.MATCH, 0));
        body.addView(right, ui.flex(0, Ui.MATCH));

        root.addView(body, ui.flex(Ui.MATCH, 0));
        show();
        return root;
    }

    private ArrayList<Station> stations() {
        ArrayList<Station> all = new ArrayList<Station>(app.stations.dab);
        all.addAll(app.stations.web);
        return all;
    }

    private View bigButton(int title, int sub, boolean accent, View.OnClickListener click) {
        LinearLayout b = ui.col(2);
        b.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(b, 16, 0, 16, 0);
        b.setBackground(accent ? ui.accentButton() : ui.outlineButton());
        b.addView(ui.label(getString(title), ui.bold(), 19, accent ? ui.t.onAccent : ui.t.text));
        b.addView(ui.label(getString(sub), ui.text(), 15, accent ? ui.t.onAccent : ui.t.dim));
        b.setOnClickListener(click);
        return b;
    }

    @Override
    protected void onStart() {
        super.onStart();
        app.logoJob.listener = this;
    }

    @Override
    protected void onStop() {
        app.logoJob.listener = null;
        super.onStop();
    }

    @Override
    public void onLogoJob() {
        if (ui != null) show();
    }

    private void show() {
        rows.clear();
        rows.addAll(stations());
        adapter.notifyDataSetChanged();

        LogoJob job = app.logoJob;
        progressBlock.setVisibility(job.state == LogoJob.IDLE ? View.INVISIBLE : View.VISIBLE);
        ui.setTech(progressLine, getString(R.string.logo_progress, job.done, job.total, job.found));
        int percent = job.total == 0 ? 100 : Math.round(job.done * 100f / job.total);
        progressBig.setText(job.state == LogoJob.RUNNING ? getString(R.string.downloading_percent, percent)
                : job.state == LogoJob.NO_NETWORK ? getString(R.string.logo_no_network)
                : getString(R.string.logo_done));
        ((LinearLayout.LayoutParams) progressFill.getLayoutParams()).weight = percent;
        View rest = ((ViewGroup) progressFill.getParent()).getChildAt(1);
        ((LinearLayout.LayoutParams) rest.getLayoutParams()).weight = 100 - percent;
        progressFill.requestLayout();
    }

    // ---- files -------------------------------------------------------------------------------

    @Override
    protected void onFilePicked(int request, Uri uri) {
        try {
            if (request == PICK_LOGO && pickFor != null) {
                app.logos.put(pickFor, readPicked(uri, 8 * 1024 * 1024), LogoStore.MANUAL);
            } else if (request == PICK_PACK) {
                InputStream in = getContentResolver().openInputStream(uri);
                if (in == null) throw new IOException("file cannot be opened");
                try {
                    toast(getString(R.string.pack_imported, app.logos.importPack(in)));
                } finally {
                    Io.close(in);
                }
            }
        } catch (Exception e) {
            toast(getString(R.string.logo_failed, String.valueOf(e.getMessage())));
        }
        if (ui != null) show();
    }

    /**
     * Writes the pack to Documents/Welle. Android 10+ goes through MediaStore and needs no
     * permission; Android 6-9 asks for storage permission; older versions just write. If the
     * shared folder is not writable, the app's own external or internal folder is used.
     */
    private void exportPack() {
        if (Build.VERSION.SDK_INT >= 23 && Build.VERSION.SDK_INT < 29
                && !ensurePermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE, PERMISSION_EXPORT)) {
            return;
        }
        try {
            String where;
            OutputStream out;
            if (Build.VERSION.SDK_INT >= 29) {
                out = mediaStoreOutput();
                where = "Documents/Welle/" + PACK_NAME;
            } else {
                File dir = new File(Environment.getExternalStorageDirectory(), "Documents/Welle");
                if (!dir.isDirectory() && !dir.mkdirs()) dir = getExternalFilesDir(null);
                if (dir == null) dir = getFilesDir();
                File f = new File(dir, PACK_NAME);
                out = new FileOutputStream(f);
                where = f.getPath();
            }
            int n = app.logos.exportPack(out, stations()); // closes the stream
            toast(getString(R.string.pack_exported, n, where));
        } catch (Exception e) {
            toast(getString(R.string.logo_failed, String.valueOf(e.getMessage())));
        }
    }

    @TargetApi(29)
    private OutputStream mediaStoreOutput() throws IOException {
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, PACK_NAME);
        v.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/Welle");
        Uri uri = getContentResolver().insert(MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
        OutputStream out = uri == null ? null : getContentResolver().openOutputStream(uri);
        if (out == null) throw new IOException("Documents folder is not available");
        return out;
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request == PERMISSION_EXPORT && results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            exportPack();
        }
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int i) { return rows.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Station s = rows.get(i);
            LogoJob job = app.logoJob;
            boolean set = app.logos.has(s.id);
            LinearLayout row = ui.row(16);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(row, 16, 0, 16, 0);
            row.setBackground(ui.panelButton());

            View tile = StationsActivity.tile(ui, app.logos.get(s.id, ui.px(80)), s.mono(), 80, 36);
            android.graphics.drawable.GradientDrawable frame = set ? ui.box(ui.t.tile, 0, 4) : ui.dashed(ui.t.lineStrong, 4);
            if (set) frame.setStroke(ui.px(2), ui.t.mark);
            else frame.setColor(ui.t.tile);
            tile.setBackground(frame);
            int inset = ui.px(2);
            tile.setPadding(inset, inset, inset, inset);
            row.addView(tile, ui.lp(80, 80));

            LinearLayout texts = ui.col(6);
            texts.addView(ui.label(s.name, ui.bold(), 19, ui.t.text));
            int status = set ? R.string.logo_set : job.waiting.contains(s.id) ? R.string.logo_waiting
                    : job.notFound.contains(s.id) ? R.string.logo_not_found : R.string.logo_none;
            TextView st = ui.tech(getString(status), 13, set ? ui.t.mark : ui.t.dim);
            st.setTypeface(ui.mono());
            texts.addView(st);
            row.addView(texts, ui.flex(0, Ui.WRAP));
            row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, ui.px(120)));
            return row;
        }
    };
}
