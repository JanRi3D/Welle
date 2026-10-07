package me.ri3d.welle.ui;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import me.ri3d.welle.R;

/** A list dialog in the app's own look, with rows large enough to hit while driving. */
final class Chooser {
    private Chooser() { }

    interface OnPick {
        void onPick(int index);
    }

    /** @param selected index drawn as selected, or -1 */
    static void show(Activity a, Ui ui, String title, String[] items, int selected, final OnPick onPick) {
        final Dialog d = new Dialog(a, android.R.style.Theme_Translucent_NoTitleBar);
        if (d.getWindow() != null) d.getWindow().setBackgroundDrawable(new ColorDrawable(0xC0000000));

        LinearLayout panel = ui.col(8);
        panel.setBackground(ui.box(ui.t.bg, ui.t.lineStrong, 6));
        ui.pad(panel, 24, 24, 24, 24);
        TextView head = ui.label(title, ui.cond(), 30, ui.t.text);
        head.setSingleLine(false);
        panel.addView(head, ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout list = ui.col(8);
        for (int i = 0; i < items.length; i++) {
            final int index = i;
            boolean sel = i == selected;
            TextView row = ui.label(items[i], ui.bold(), 19, sel ? ui.t.onSelected : ui.t.text);
            row.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(row, 16, 0, 16, 0);
            row.setBackground(ui.choice(sel));
            row.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    d.dismiss();
                    onPick.onPick(index);
                }
            });
            list.addView(row, ui.lp(Ui.MATCH, 60));
        }
        ScrollView scroll = new ScrollView(a);
        scroll.addView(list);
        panel.addView(scroll, new LinearLayout.LayoutParams(Ui.MATCH, 0, 1f));

        TextView cancel = ui.label(a.getString(R.string.cancel), ui.bold(), 19, ui.t.text);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(ui.outlineButton());
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        panel.addView(cancel, ui.lp(Ui.MATCH, 60));

        FrameLayout frame = new FrameLayout(a);
        frame.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                d.dismiss();
            }
        });
        // Height: as tall as the content needs, at most most of the screen.
        int rows = Math.min(items.length, 6);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(ui.px(560),
                ui.px(24 + 44 + 8 + rows * 68 + 68 + 24), Gravity.CENTER);
        panel.setClickable(true);
        frame.addView(panel, lp);
        d.setContentView(frame, new ViewGroup.LayoutParams(Ui.MATCH, Ui.MATCH));
        d.show();
    }
}
