package me.ri3d.welle.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;

import me.ri3d.welle.R;

/**
 * Bare-bones file browser. Only used on Android 4.1-4.3 when no file manager is installed
 * to answer GET_CONTENT; newer versions always have the system document picker.
 */
public final class FilePickerActivity extends BaseActivity {
    private File dir;
    private final ArrayList<File> entries = new ArrayList<File>();
    private TextView path;

    @Override
    protected View build() {
        if (dir == null) dir = Environment.getExternalStorageDirectory();
        LinearLayout root = ui.col(0);
        path = ui.tech("", 14, ui.t.dim);
        path.setTypeface(ui.mono());
        root.addView(header(getString(R.string.pick_file), path));
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setDividerHeight(ui.px(8));
        list.setSelector(new android.graphics.drawable.ColorDrawable(0));
        list.setPadding(ui.px(40), ui.px(28), ui.px(40), ui.px(28));
        list.setClipToPadding(false);
        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                File f = entries.get(position);
                if (f.isDirectory()) {
                    dir = f;
                    list();
                } else {
                    setResult(RESULT_OK, new Intent().setData(Uri.fromFile(f)));
                    finish();
                }
            }
        });
        root.addView(list, ui.flex(Ui.MATCH, 0));
        list();
        return root;
    }

    @Override
    protected void onStart() {
        super.onStart();
        ensurePermission(android.Manifest.permission.READ_EXTERNAL_STORAGE, 1);
    }

    private void list() {
        entries.clear();
        File parent = dir.getParentFile();
        if (parent != null) entries.add(parent);
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, new Comparator<File>() {
                @Override
                public int compare(File a, File b) {
                    if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
                    return a.getName().compareToIgnoreCase(b.getName());
                }
            });
            for (File f : files) if (!f.isHidden()) entries.add(f);
        }
        path.setText(dir.getPath());
        adapter.notifyDataSetChanged();
    }

    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            File f = entries.get(i);
            boolean up = i == 0 && f.equals(dir.getParentFile());
            TextView row = ui.label(up ? ".." : f.isDirectory() ? f.getName() + "/" : f.getName(),
                    f.isDirectory() ? ui.bold() : ui.text(), 19, ui.t.text);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(row, 20, 0, 20, 0);
            row.setBackground(ui.box(ui.t.panel, ui.t.line, 6));
            row.setLayoutParams(new AbsListView.LayoutParams(Ui.MATCH, ui.px(60)));
            return row;
        }
    };
}
