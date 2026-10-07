package me.ri3d.welle.ui;

import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;

import me.ri3d.welle.core.Io;

/** Shows a bundled text file: the open-source licences and the privacy policy. */
public final class TextActivity extends BaseActivity {
    static void open(Context c, String title, String asset) {
        c.startActivity(new Intent(c, TextActivity.class).putExtra("title", title).putExtra("asset", asset));
    }

    @Override
    protected View build() {
        LinearLayout root = ui.col(0);
        root.addView(header(getIntent().getStringExtra("title"), null));
        String text;
        try {
            InputStream in = getAssets().open(getIntent().getStringExtra("asset"));
            try {
                text = new String(Io.readAll(in, 512 * 1024), "UTF-8");
            } finally {
                Io.close(in);
            }
        } catch (Exception e) {
            text = String.valueOf(e);
        }
        TextView body = ui.label(text, ui.text(), 19, ui.t.text2);
        body.setSingleLine(false);
        body.setLineSpacing(0, 1.25f);
        ui.pad(body, 40, 28, 40, 28);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        root.addView(scroll, ui.flex(Ui.MATCH, 0));
        return root;
    }
}
