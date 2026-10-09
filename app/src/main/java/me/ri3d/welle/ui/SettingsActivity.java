package me.ri3d.welle.ui;

import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.net.Uri;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import me.ri3d.welle.R;
import me.ri3d.welle.UsbAttachActivity;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.UsbLink;

/** The six settings categories of the reference. Every row writes a setting the app acts on. */
public final class SettingsActivity extends BaseActivity {
    private static final int PICK_SCENE = 10;

    private static final int GENERAL = 0, LAYOUT = 1, SLIDESHOW = 2, AUDIO = 3, THEME = 4, ABOUT = 5;
    private int section = GENERAL;

    private LinearLayout rows;

    @Override
    protected View build() {
        LinearLayout root = ui.col(0);
        root.addView(header(getString(R.string.settings), null));

        LinearLayout body = ui.row(28);
        ui.pad(body, 40, 28, 40, 28);

        LinearLayout nav = ui.col(8);
        int[] names = {R.string.set_general, R.string.set_layout, R.string.set_slideshow, R.string.set_audio, R.string.set_theme, R.string.set_about};
        for (int i = 0; i < names.length; i++) {
            final int index = i;
            boolean sel = i == section;
            TextView b = ui.label(getString(names[i]), ui.bold(), 19, sel ? ui.t.onSelected : ui.t.text);
            b.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(b, 16, 0, 16, 0);
            b.setBackground(ui.choice(sel));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    section = index;
                    rebuild();
                }
            });
            nav.addView(b, ui.lp(Ui.MATCH, 56));
        }
        nav.addView(ui.spacer(), ui.flex(1, 0));
        nav.addView(link(R.string.station_logos, LogosActivity.class), ui.lp(Ui.MATCH, 52));
        nav.addView(link(R.string.web_radio_manager, WebRadioActivity.class), ui.lp(Ui.MATCH, 52));
        body.addView(nav, ui.lp(264, Ui.MATCH));

        rows = ui.col(8);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(rows);
        body.addView(scroll, ui.flex(0, Ui.MATCH));
        root.addView(body, ui.flex(Ui.MATCH, 0));
        fill();
        return root;
    }

    private View link(int text, final Class<?> target) {
        LinearLayout b = ui.row(0);
        b.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(b, 16, 0, 16, 0);
        b.setBackground(ui.outlineButton());
        b.addView(ui.label(getString(text), ui.text(), 18, ui.t.text), ui.flex(0, Ui.WRAP));
        b.addView(new IconView(this, IconView.CHEVRON, ui.px(20), ui.t.text), ui.lp(20, 20));
        b.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(SettingsActivity.this, target));
            }
        });
        return b;
    }

    @Override
    protected void refresh() {
        if (section == ABOUT) fill(); // shows the live adapter state
    }

    private void fill() {
        rows.removeAllViews();
        switch (section) {
            case GENERAL:
                select(Settings.LANG, R.string.s_language, 0, R.array.opt_language);
                toggle(Settings.START_USB, R.string.s_start_usb, R.string.s_autostart);
                toggle(Settings.START_BG, R.string.s_start_bg, R.string.s_autostart_if_supported);
                toggle(Settings.FINISH_BACK, R.string.s_finish_back, R.string.s_finish_back_sum);
                select(Settings.MINIMIZE, R.string.s_minimize, R.string.s_minimize_sum, R.array.opt_minimize);
                toggle(Settings.FINISH_FOCUS, R.string.s_finish_focus, 0);
                toggle(Settings.SERVICE_FOLLOWING, R.string.s_service_following, R.string.s_service_following_sum);
                toggle(Settings.STUTTER, R.string.s_stutter, R.string.s_stutter_sum);
                toggle(Settings.SKIP_USB, R.string.s_usb_search, R.string.s_usb_search_sum);
                break;
            case LAYOUT:
                select(Settings.BARS, R.string.s_bars, 0, R.array.opt_bars);
                toggle(Settings.MENU_TOP, R.string.s_menu_top, 0);
                toggle(Settings.CLOCK, R.string.s_clock, 0);
                toggle(Settings.NOW_PLAYING, R.string.s_now_playing, 0);
                toggle(Settings.DLS_TOP, R.string.s_dls_top, 0);
                select(Settings.DLS_OVERLAY, R.string.s_dls_overlay, 0, R.array.opt_overlay);
                select(Settings.PER_PAGE, R.string.s_per_page, 0, R.array.opt_per_page);
                select(Settings.PAGES, R.string.s_pages, 0, R.array.opt_pages);
                select(Settings.HIDE_PRESETS, R.string.s_hide_presets, R.string.s_hide_presets_sum, R.array.opt_hide_presets);
                break;
            case SLIDESHOW:
                toggle(Settings.SLIDESHOW, R.string.s_slideshow, R.string.s_slideshow_sum);
                select(Settings.TRANSPARENCY, R.string.s_transparency, 0, R.array.opt_transparency);
                toggle(Settings.DIM, R.string.s_dim, 0);
                select(Settings.DIM_BRIGHT, R.string.s_dim_bright, 0, R.array.opt_percent);
                select(Settings.VIS, R.string.s_vis, 0, R.array.opt_vis);
                action(R.string.s_scene, R.string.s_scene_sum,
                        getString(Scene.installed(this) ? R.string.s_scene_remove : R.string.select_file), new Runnable() {
                            @Override
                            public void run() {
                                if (Scene.installed(SettingsActivity.this)) {
                                    Scene.remove(SettingsActivity.this);
                                    fill();
                                } else {
                                    pickFile(PICK_SCENE);
                                }
                            }
                        });
                break;
            case AUDIO:
                select(Settings.VOLUME, R.string.s_volume, 0, R.array.opt_volume);
                toggle(Settings.AGC, R.string.s_agc, R.string.s_agc_sum);
                toggle(Settings.NOISE, R.string.s_noise, R.string.s_noise_sum);
                toggle(Settings.MUTE_FOCUS, R.string.s_mute_focus, R.string.s_mute_focus_sum);
                select(Settings.DUCK, R.string.s_duck, 0, R.array.opt_percent);
                break;
            case THEME:
                themeSection();
                break;
            default:
                about();
                break;
        }
    }

    // ---- rows --------------------------------------------------------------------------------

    private LinearLayout row(int label, int summary) {
        LinearLayout r = ui.row(20);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(ui.px(60));
        ui.pad(r, 20, 8, 20, 8);
        r.setBackground(ui.panelButton());
        LinearLayout texts = ui.col(2);
        TextView title = ui.label(getString(label), ui.bold(), 19, ui.t.text);
        title.setSingleLine(false);
        texts.addView(title);
        if (summary != 0) {
            TextView sum = ui.label(getString(summary), ui.text(), 15, ui.t.dim);
            sum.setSingleLine(false);
            texts.addView(sum);
        }
        r.addView(texts, ui.flex(0, Ui.WRAP));
        rows.addView(r, ui.lp(Ui.MATCH, Ui.WRAP));
        return r;
    }

    private void toggle(final String key, int label, int summary) {
        // "Search for the USB adapter" is shown positively but stored as "skip".
        final boolean inverted = Settings.SKIP_USB.equals(key);
        LinearLayout r = row(label, summary);
        final ToggleView t = new ToggleView(ui, settings.b(key) != inverted);
        r.addView(t);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean stored = !settings.b(key);
                settings.set(key, stored);
                t.setOn(stored != inverted);
                changed(key);
            }
        });
    }

    /** Tapping steps to the next option, as in the reference. */
    private void select(final String key, int label, int summary, int optionsArray) {
        final String[] options = getResources().getStringArray(optionsArray);
        LinearLayout r = row(label, summary);
        final TextView value = value(r, options[Math.max(0, Math.min(options.length - 1, settings.i(key)))]);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int next = (settings.i(key) + 1) % options.length;
                settings.set(key, next);
                value.setText(options[next]);
                changed(key);
            }
        });
    }

    private void action(int label, int summary, String valueText, final Runnable run) {
        LinearLayout r = row(label, summary);
        value(r, valueText);
        r.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                run.run();
            }
        });
    }

    private TextView value(LinearLayout r, String text) {
        LinearLayout v = ui.row(10);
        v.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = ui.label(text, ui.monoMed(), 17, ui.t.mark);
        v.addView(t);
        v.addView(new IconView(this, IconView.CHEVRON, ui.px(20), ui.t.mark), ui.lp(20, 20));
        r.addView(v);
        return t;
    }

    /** Applies a changed setting right away. */
    private void changed(String key) {
        if (radio != null) radio.applyAudioSettings();
        if (Settings.LANG.equals(key)) {
            recreate();
        } else if (Settings.START_USB.equals(key)) {
            UsbAttachActivity.setEnabled(this, settings.b(key));
        } else if (Settings.BARS.equals(key)) {
            applyBars();
        } else if (Settings.SKIP_USB.equals(key)) {
            if (radio != null) radio.setUsbSearchEnabled(!settings.b(key));
        } else if (Settings.DLS_OVERLAY.equals(key)) {
            if (settings.i(key) != 0 && Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) {
                toast(getString(R.string.overlay_permission));
                try {
                    startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())));
                } catch (RuntimeException ignored) {
                    // No such settings screen on this device; the overlay simply stays off.
                }
            }
        }
    }

    // ---- theme -------------------------------------------------------------------------------

    private void themeSection() {
        LinearLayout box = ui.col(28);

        LinearLayout themes = ui.col(12);
        themes.addView(caption(R.string.set_theme));
        LinearLayout themeRow = ui.row(8);
        int[] titles = {R.string.theme_night, R.string.theme_day, R.string.theme_gps, R.string.theme_system};
        int[] sums = {R.string.theme_night_sum, R.string.theme_day_sum, R.string.theme_gps_sum, R.string.theme_system_sum};
        for (int i = 0; i < 4; i++) {
            final int index = i;
            boolean sel = settings.i(Settings.THEME) == i;
            int fg = sel ? ui.t.onSelected : ui.t.text;
            LinearLayout b = ui.col(4);
            b.setGravity(Gravity.CENTER_VERTICAL);
            ui.pad(b, 16, 0, 16, 0);
            b.setBackground(ui.choice(sel));
            b.addView(ui.label(getString(titles[i]), ui.bold(), 20, fg));
            b.addView(ui.label(getString(sums[i]), ui.text(), 15, fg));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settings.set(Settings.THEME, index);
                    // The sunset calculation is better with a position; coarse is enough.
                    if (index == 2) ensurePermission(android.Manifest.permission.ACCESS_COARSE_LOCATION, 2);
                    Theme.updateLocation(SettingsActivity.this, settings);
                    rebuild();
                }
            });
            themeRow.addView(b, ui.flex(0, 92));
        }
        themes.addView(themeRow, ui.lp(Ui.MATCH, Ui.WRAP));
        box.addView(themes, ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout accents = ui.col(12);
        accents.addView(caption(R.string.accent_colour));
        LinearLayout accentRow = ui.row(8);
        String[] names = getResources().getStringArray(R.array.accent_names);
        for (int i = 0; i < Theme.ACCENTS.length; i++) {
            final int index = i;
            boolean sel = settings.i(Settings.ACCENT) == i;
            LinearLayout b = ui.col(0);
            ui.pad(b, 12, 12, 12, 12);
            b.setBackground(ui.button(ui.t.panel, sel ? ui.t.text : ui.t.line, 6));
            View swatch = new View(this);
            swatch.setBackground(ui.box(Theme.ACCENTS[i], 0, 3));
            b.addView(swatch, ui.lp(Ui.MATCH, 40));
            b.addView(ui.spacer(), ui.flex(1, 0));
            b.addView(ui.label(names[i], ui.bold(), 17, ui.t.text));
            b.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    settings.set(Settings.ACCENT, index);
                    rebuild(); // the whole screen, preview included, takes the new accent
                }
            });
            accentRow.addView(b, ui.flex(0, 112));
        }
        accents.addView(accentRow, ui.lp(Ui.MATCH, Ui.WRAP));
        box.addView(accents, ui.lp(Ui.MATCH, Ui.WRAP));

        box.addView(new Preview(ui), ui.lp(Ui.MATCH, 96));
        rows.addView(box, ui.lp(Ui.MATCH, Ui.WRAP));
    }

    private TextView caption(int text) {
        TextView t = ui.tech(getString(text), 13, ui.t.dim);
        t.setTypeface(ui.mono());
        return t;
    }

    /** Live preview strip: station name with the needle and its flag in the current accent. */
    private static final class Preview extends View {
        private final Ui ui;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        Preview(Ui ui) {
            super(ui.c);
            this.ui = ui;
        }

        @Override
        protected void onDraw(Canvas c) {
            Theme t = ui.t;
            int w = getWidth(), h = getHeight();
            paint.setColor(t.line);
            c.drawRect(0, 0, w, ui.px(1), paint);
            c.drawRect(0, h - ui.px(1), w, h, paint);
            paint.setColor(t.text);
            paint.setTypeface(ui.cond());
            paint.setTextSize(48 * ui.u);
            c.drawText("Deutschlandfunk", 0, ui.px(24) + 40 * ui.u, paint);
            float x = w * 0.62f;
            paint.setColor(t.mark);
            c.drawRect(x, 0, x + ui.px(t.night ? 2 : 3), h, paint);
            paint.setTypeface(ui.monoMed());
            paint.setTextSize(16 * ui.u);
            String flag = "5C · 178.352 MHz";
            if (t.night) {
                paint.setColor(t.accent);
                c.drawText(flag, x + ui.px(12), ui.px(10) + 16 * ui.u, paint);
            } else {
                float tw = paint.measureText(flag);
                paint.setColor(t.accent);
                c.drawRect(x + ui.px(12), ui.px(10), x + ui.px(12 + 16) + tw, ui.px(10 + 26), paint);
                paint.setColor(t.onAccent);
                c.drawText(flag, x + ui.px(20), ui.px(12) + 16 * ui.u, paint);
            }
        }
    }

    // ---- about -------------------------------------------------------------------------------

    private void about() {
        String version = "?";
        try {
            version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Exception ignored) {
        }
        action(R.string.s_version, 0, version, new Runnable() {
            @Override
            public void run() {
            }
        });
        action(R.string.s_licences, 0, getString(R.string.view), new Runnable() {
            @Override
            public void run() {
                TextActivity.open(SettingsActivity.this, getString(R.string.s_licences), "licenses.txt");
            }
        });
        action(R.string.s_privacy, 0, getString(R.string.view), new Runnable() {
            @Override
            public void run() {
                boolean german = "de".equals(me.ri3d.welle.Locales.chosen(SettingsActivity.this).getLanguage());
                TextActivity.open(SettingsActivity.this, getString(R.string.s_privacy), german ? "privacy_de.txt" : "privacy_en.txt");
            }
        });
        int[] states = {R.string.usb_off, R.string.usb_none, R.string.usb_permission, R.string.usb_denied,
                R.string.usb_unsupported, R.string.usb_starting, R.string.usb_ready, R.string.usb_disconnected};
        int state = radio == null ? UsbLink.NO_DEVICE : radio.usbState;
        action(R.string.s_usb_adapter, R.string.s_usb_adapter_sum, getString(states[state]), new Runnable() {
            @Override
            public void run() {
                if (radio != null) radio.searchUsb();
            }
        });
    }

    // ---- scene import ------------------------------------------------------------------------

    @Override
    protected void onFilePicked(int request, Uri uri) {
        if (request != PICK_SCENE) return;
        try {
            Scene.install(this, readPicked(uri, Scene.MAX_BYTES));
            toast(getString(R.string.scene_installed));
        } catch (Exception e) {
            toast(getString(R.string.scene_failed, String.valueOf(e.getMessage())));
        }
        if (rows != null) fill();
    }
}
