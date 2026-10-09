package me.ri3d.welle.ui;

import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import me.ri3d.welle.R;
import me.ri3d.welle.RadioService;
import me.ri3d.welle.core.DabChannels;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.core.StationStore;
import me.ri3d.welle.core.UsbLink;
import me.ri3d.welle.logos.LogoJob;

/**
 * The player: tuner-dial layout of the reference (direction A). Also shows the
 * "No adapter found" state and its variants in place of the station block.
 */
public final class PlayerActivity extends BaseActivity {
    private TabLabel tabDab, tabWeb;
    private View statusDot;
    private TextView statusText, clock;
    private IconView scanIcon, listIcon;

    private View playerMiddle, noticeMiddle, playerBottom, noticeBottom;
    private TextView meta, dlsView, signalLabel, followLabel;
    private NameView name;
    private SignalView signal;
    private ArtworkView art;
    private RulerView ruler;
    private IconView playButton;
    private SwipeLayout presetBox;
    private TextView presetHeader;
    private LinearLayout pageBars;
    private View presetColumn;

    private TextView noticeTag, noticeText, noticePrimaryText, noticeSecondary;
    private NameView noticeTitle;
    private View noticePrimary, noticeSkip;
    private IconView noticePrimaryIcon;
    private ToggleView skipToggle;

    private int page;
    private String presetSignature = "";
    private String needleStation = "";
    private int noticeAction;

    @Override
    protected View build() {
        page = Math.min(settings.i(Settings.PRESET_PAGE), settings.pages() - 1);
        presetSignature = "";
        needleStation = "";
        boolean menuTop = settings.b(Settings.MENU_TOP);

        LinearLayout root = ui.col(0);
        if (menuTop) {
            root.addView(topBar(), ui.lp(Ui.MATCH, 63));
            root.addView(rule(), ui.lp(Ui.MATCH, 1));
        }

        FrameLayout middle = new FrameLayout(this);
        playerMiddle = playerMiddle();
        noticeMiddle = noticeMiddle();
        middle.addView(playerMiddle, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        middle.addView(noticeMiddle, new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        root.addView(middle, ui.flex(Ui.MATCH, 0));

        ruler = new RulerView(ui);
        LinearLayout.LayoutParams rp = ui.lp(Ui.MATCH, 112);
        rp.leftMargin = rp.rightMargin = ui.px(40);
        root.addView(ruler, rp);

        playerBottom = playerBottom();
        noticeBottom = noticeBottom();
        root.addView(playerBottom, ui.lp(Ui.MATCH, Ui.WRAP));
        root.addView(noticeBottom, ui.lp(Ui.MATCH, Ui.WRAP));

        if (!menuTop) {
            root.addView(rule(), ui.lp(Ui.MATCH, 1));
            root.addView(topBar(), ui.lp(Ui.MATCH, 63));
        }
        return root;
    }

    private View rule() {
        View v = new View(this);
        v.setBackgroundColor(ui.t.line);
        return v;
    }

    private View topBar() {
        LinearLayout bar = ui.row(0);
        ui.pad(bar, 40, 0, 40 - 12, 0);

        LinearLayout tabs = ui.row(28);
        tabDab = new TabLabel(ui, getString(R.string.tab_dab));
        tabWeb = new TabLabel(ui, getString(R.string.tab_web));
        tabDab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio != null) radio.setSource(RadioService.SRC_DAB);
            }
        });
        tabWeb.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio != null) radio.setSource(RadioService.SRC_WEB);
            }
        });
        tabs.addView(tabDab, ui.lp(Ui.WRAP, Ui.MATCH));
        tabs.addView(tabWeb, ui.lp(Ui.WRAP, Ui.MATCH));
        bar.addView(tabs, ui.lp(Ui.WRAP, Ui.MATCH));
        bar.addView(ui.spacer(), ui.flex(0, 1));

        LinearLayout right = ui.row(16);
        right.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout status = ui.row(10);
        status.setGravity(Gravity.CENTER_VERTICAL);
        statusDot = new View(this);
        status.addView(statusDot, ui.lp(8, 8));
        statusText = ui.tech("", 14, ui.t.dim);
        statusText.setTypeface(ui.mono());
        status.addView(statusText);
        right.addView(status);

        clock = ui.label("", ui.monoMed(), 20, ui.t.text);
        LinearLayout.LayoutParams cp = ui.lp(Ui.WRAP, Ui.WRAP);
        cp.leftMargin = cp.rightMargin = ui.px(8);
        right.addView(clock, cp);
        clock.setVisibility(settings.b(Settings.CLOCK) ? View.VISIBLE : View.GONE);

        scanIcon = icon(IconView.SCAN, R.string.cd_scan, ScanActivity.class);
        listIcon = icon(IconView.LIST, R.string.cd_stations, StationsActivity.class);
        right.addView(scanIcon, ui.lp(48, 48));
        right.addView(listIcon, ui.lp(48, 48));
        right.addView(icon(IconView.SETTINGS, R.string.cd_settings, SettingsActivity.class), ui.lp(48, 48));
        right.addView(minimizeButton(), ui.lp(48, 48));
        bar.addView(right, ui.lp(Ui.WRAP, Ui.MATCH));
        return bar;
    }

    private IconView minimizeButton() {
        final boolean close = settings.i(Settings.MINIMIZE) == 1;
        IconView v = new IconView(this, close ? IconView.CLOSE : IconView.MINIMIZE, ui.px(26), ui.t.text);
        v.setContentDescription(getResources().getStringArray(R.array.opt_minimize)[close ? 1 : 0]);
        v.setBackground(ui.button(0, 0, 6));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                leave(close);
            }
        });
        return v;
    }

    private IconView icon(int kind, int description, final Class<?> target) {
        IconView v = new IconView(this, kind, ui.px(26), ui.t.text);
        v.setContentDescription(getString(description));
        v.setBackground(ui.button(0, 0, 6));
        v.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                startActivity(new Intent(PlayerActivity.this, target));
            }
        });
        return v;
    }

    private View playerMiddle() {
        LinearLayout row = ui.row(40);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(row, 40, 0, 40, 0);

        LinearLayout left = ui.col(10);
        meta = ui.tech("", 16, ui.t.dim);
        meta.setTypeface(ui.mono());
        name = new NameView(ui, ui.t.text, 176, 128, 104, 84, 68);
        dlsView = ui.label("", ui.text(), 28, ui.t.text2);
        boolean dlsTop = settings.b(Settings.DLS_TOP);
        if (dlsTop) left.addView(dlsView, ui.lp(Ui.MATCH, Ui.WRAP));
        left.addView(meta, ui.lp(Ui.MATCH, Ui.WRAP));
        left.addView(name, ui.lp(Ui.MATCH, Ui.WRAP));
        name.setVisibility(settings.b(Settings.NOW_PLAYING) ? View.VISIBLE : View.GONE);
        if (!dlsTop) left.addView(dlsView, ui.lp(Ui.MATCH, Ui.WRAP));

        LinearLayout signalRow = ui.row(14);
        signalRow.setGravity(Gravity.BOTTOM);
        signalLabel = ui.tech(getString(R.string.signal), 14, ui.t.dim);
        signalLabel.setTypeface(ui.mono());
        signal = new SignalView(ui);
        followLabel = ui.tech("", 14, ui.t.dim);
        followLabel.setTypeface(ui.mono());
        signalRow.addView(signalLabel);
        signalRow.addView(signal);
        signalRow.addView(followLabel);
        LinearLayout.LayoutParams sp = ui.lp(Ui.MATCH, Ui.WRAP);
        sp.topMargin = ui.px(6);
        left.addView(signalRow, sp);
        row.addView(left, ui.flex(0, Ui.WRAP));

        // 280 x 210 in the reference; on wider windows the panel grows with the extra width.
        float extra = Math.max(0, Math.min(120, (widthUnits() - 1280) * 0.2f));
        art = new ArtworkView(ui);
        art.setScene(Scene.load(this));
        row.addView(art, ui.lp(280 + extra, (280 + extra) * 0.75f));
        return row;
    }

    private float widthUnits() {
        return getResources().getDisplayMetrics().widthPixels / ui.u;
    }

    private View playerBottom() {
        LinearLayout row = ui.row(20);
        row.setGravity(Gravity.BOTTOM);
        ui.pad(row, 40, 16, 40, 24);

        LinearLayout transport = ui.row(8);
        IconView prev = new IconView(this, IconView.PREV, ui.px(32), ui.t.text);
        prev.setContentDescription(getString(R.string.cd_prev));
        prev.setBackground(ui.panelButton());
        prev.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio != null) radio.prev();
            }
        });
        playButton = new IconView(this, IconView.PAUSE, ui.px(44), ui.t.onAccent);
        playButton.setContentDescription(getString(R.string.cd_play_pause));
        playButton.setBackground(ui.accentButton());
        playButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio != null) radio.togglePlay();
            }
        });
        IconView next = new IconView(this, IconView.NEXT, ui.px(32), ui.t.text);
        next.setContentDescription(getString(R.string.cd_next));
        next.setBackground(ui.panelButton());
        next.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (radio != null) radio.next();
            }
        });
        transport.addView(prev, ui.lp(76, 136));
        transport.addView(playButton, ui.lp(112, 136));
        transport.addView(next, ui.lp(76, 136));
        row.addView(transport);

        LinearLayout presets = ui.col(8);
        LinearLayout head = ui.row(0);
        head.setGravity(Gravity.CENTER_VERTICAL);
        presetHeader = ui.tech("", 13, ui.t.dim);
        presetHeader.setTypeface(ui.mono());
        pageBars = ui.row(6);
        pageBars.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(presetHeader, ui.flex(0, Ui.WRAP));
        head.addView(pageBars);
        head.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                turnPage(1);
            }
        });
        // 16 units tall in the design; the negative margins give it a 40-unit touch target.
        LinearLayout.LayoutParams hp = ui.lp(Ui.MATCH, 40);
        hp.topMargin = hp.bottomMargin = -ui.px(12);
        presets.addView(head, hp);
        presetBox = new SwipeLayout(this);
        presetBox.setOrientation(LinearLayout.VERTICAL);
        presetBox.setOnSwipe(new SwipeLayout.OnSwipe() {
            @Override
            public void onSwipe(int direction) {
                turnPage(direction);
            }
        });
        presets.addView(presetBox, ui.lp(Ui.MATCH, 64 + 8 + 64));
        presetColumn = presets;
        row.addView(presets, ui.flex(0, Ui.WRAP));
        return row;
    }

    private View noticeMiddle() {
        LinearLayout col = ui.col(10);
        col.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(col, 40, 0, 40, 0);
        noticeTag = ui.tech("", 16, ui.t.accent);
        noticeTag.setTypeface(ui.mono());
        noticeTitle = new NameView(ui, ui.t.text, 152, 128, 104, 84, 68);
        noticeText = ui.label("", ui.text(), 28, ui.t.text2);
        noticeText.setSingleLine(false);
        noticeText.setMaxLines(3);
        col.addView(noticeTag, ui.lp(Ui.MATCH, Ui.WRAP));
        col.addView(noticeTitle, ui.lp(Ui.MATCH, Ui.WRAP));
        col.addView(noticeText, ui.lp(Ui.MATCH, Ui.WRAP));
        return col;
    }

    private View noticeBottom() {
        LinearLayout row = ui.row(12);
        row.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(row, 40, 24, 40, 28);

        LinearLayout primary = ui.row(12);
        primary.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(primary, 32, 0, 32, 0);
        primary.setBackground(ui.outlineButton());
        noticePrimaryIcon = new IconView(this, IconView.SCAN, ui.px(26), ui.t.text);
        noticePrimaryText = ui.label("", ui.bold(), 21, ui.t.text);
        primary.addView(noticePrimaryIcon, ui.lp(26, 26));
        primary.addView(noticePrimaryText);
        primary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (noticeAction == 1) startActivity(new Intent(PlayerActivity.this, ScanActivity.class));
                else if (radio != null) radio.searchUsb();
            }
        });
        noticePrimary = primary;
        row.addView(primary, ui.lp(Ui.WRAP, 84));

        noticeSecondary = ui.label(getString(R.string.add_web_radios), ui.bold(), 21, ui.t.onAccent);
        noticeSecondary.setGravity(Gravity.CENTER);
        ui.pad(noticeSecondary, 32, 0, 32, 0);
        noticeSecondary.setBackground(ui.accentButton());
        noticeSecondary.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(PlayerActivity.this, WebRadioActivity.class));
            }
        });
        row.addView(noticeSecondary, ui.lp(Ui.WRAP, 84));
        row.addView(ui.spacer(), ui.flex(0, 1));

        LinearLayout skip = ui.row(20);
        skip.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(skip, 20, 0, 20, 0);
        skip.setBackground(ui.panelButton());
        TextView skipText = ui.label(getString(R.string.skip_usb_search), ui.text(), 18, ui.t.text);
        skipToggle = new ToggleView(ui, settings.b(Settings.SKIP_USB));
        skip.addView(skipText);
        skip.addView(skipToggle);
        skip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean skipNow = !settings.b(Settings.SKIP_USB);
                // Takes effect on the next start; "Search again" keeps working either way.
                settings.set(Settings.SKIP_USB, skipNow);
                skipToggle.setOn(skipNow);
            }
        });
        noticeSkip = skip;
        row.addView(skip, ui.lp(Ui.WRAP, 84));
        return row;
    }

    // ---- state -> views ----------------------------------------------------------------------

    @Override
    protected void refresh() {
        boolean dab = radio.source == RadioService.SRC_DAB;
        boolean ready = radio.usbState == UsbLink.READY;
        Station st = radio.station;
        Theme t = ui.t;

        tabDab.setActive(dab);
        tabWeb.setActive(!dab);
        boolean good = dab ? ready : LogoJob.online(this);
        GradientDrawable dot = new GradientDrawable();
        if (good) dot.setColor(t.ok);
        else {
            dot.setColor(0);
            dot.setStroke(ui.px(2), t.dim);
        }
        statusDot.setBackground(dot);
        ui.setTech(statusText, getString(dab ? (ready ? R.string.usb_tuner : R.string.no_usb_tuner) : (good ? R.string.online : R.string.offline)));
        clock.setText(new SimpleDateFormat("HH:mm", Locale.US).format(new Date()));

        boolean adapterNotice = dab && !ready;
        boolean notice = adapterNotice || st == null;
        playerMiddle.setVisibility(notice ? View.GONE : View.VISIBLE);
        playerBottom.setVisibility(notice ? View.GONE : View.VISIBLE);
        noticeMiddle.setVisibility(notice ? View.VISIBLE : View.GONE);
        noticeBottom.setVisibility(notice ? View.VISIBLE : View.GONE);
        scanIcon.setVisibility(dab && ready ? View.VISIBLE : View.GONE);
        listIcon.setVisibility(adapterNotice ? View.GONE : View.VISIBLE);

        if (notice) {
            showNotice(dab, ready);
            ruler.setMode(RulerView.OFF);
            return;
        }

        // Station block
        String metaText;
        Station.Loc loc = null;
        if (dab) {
            loc = st.locs.get(Math.min(radio.locIndex, st.locs.size() - 1));
            boolean plus = radio.formatKnown ? radio.dabPlus : st.dabPlus;
            metaText = loc.ensemble + " · " + (plus ? "DAB+" : "DAB");
            if (radio.formatKnown) metaText += " · " + getString(radio.stereo ? R.string.stereo : R.string.mono);
            if (st.missing) metaText += " · " + getString(R.string.not_in_last_scan);
        } else {
            metaText = getString(R.string.tab_web) + (radio.webFormat.isEmpty() ? "" : " · " + radio.webFormat);
        }
        ui.setTech(meta, metaText);
        name.setText(st.name);
        String line = !radio.status.isEmpty() ? radio.status
                : radio.mutedByFocus() ? getString(R.string.status_muted)
                : !radio.wantPlay ? getString(R.string.status_paused)
                : radio.dls;
        dlsView.setText(line.isEmpty() ? " " : line);

        signal.setVisibility(dab ? View.VISIBLE : View.GONE);
        followLabel.setVisibility(dab ? View.VISIBLE : View.GONE);
        if (dab) {
            ui.setTech(signalLabel, getString(radio.playState == RadioService.PLAYING || radio.rfLock ? R.string.signal : R.string.no_signal));
            signal.setBars(radio.signalBars());
            ui.setTech(followLabel, getString(!settings.b(Settings.SERVICE_FOLLOWING) ? R.string.sf_off
                    : radio.locIndex > 0 ? R.string.sf_alternative : R.string.sf_on));
        } else {
            ui.setTech(signalLabel, getString(R.string.internet_stream));
        }

        // Artwork: slideshow picture if enabled and received, else the station logo, else the monogram.
        Bitmap slide = settings.b(Settings.SLIDESHOW) ? radio.slide : null;
        Bitmap logo = slide == null ? app.logos.get(st.id, ui.px(200)) : null;
        art.setImage(slide != null ? slide : logo, slide != null);
        art.setFallback(st.mono(), getString(R.string.no_artwork).toUpperCase(Locale.getDefault()));
        boolean dimmed = settings.b(Settings.DIM) && Theme.afterSunset(this, settings);
        // The transparency setting applies to whichever picture is on top, slide or logo,
        // so the scene and visualization behind it can show through.
        art.setLook(settings.slideshowAlpha(), dimmed ? settings.dimBrightness() : 1f);
        art.setVisualization(settings.i(Settings.VIS), radio.engine(), radio.playState == RadioService.PLAYING);

        // Dial
        if (dab) {
            ruler.setMode(RulerView.PLAYER);
            boolean[] dots = new boolean[DabChannels.RULER_TICKS];
            for (StationStore.Ensemble e : app.stations.ensembles()) {
                int tick = DabChannels.rulerTick(DabChannels.indexOf(e.khz));
                if (tick >= 0) dots[tick] = true;
            }
            ruler.setDots(dots);
            String flag = settings.b(Settings.NOW_PLAYING)
                    ? DabChannels.label(loc.khz) + " · " + DabChannels.mhz(loc.khz) + " MHz" : "";
            String key = st.id + "/" + loc.khz;
            ruler.setNeedle(DabChannels.rulerPos(DabChannels.indexOf(loc.khz)), flag, needleStation.isEmpty() || key.equals(needleStation) ? 0 : 450);
            needleStation = key;
        } else {
            ruler.setMode(RulerView.OFF);
        }

        playButton.setIcon(radio.wantPlay && radio.playState != RadioService.ERROR && !radio.mutedByFocus() ? IconView.PAUSE : IconView.PLAY);
        boolean hide = settings.i(Settings.HIDE_PRESETS) == 1
                && getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        presetColumn.setVisibility(hide ? View.INVISIBLE : View.VISIBLE);
        if (!hide) showPresets(st);
    }

    private void showNotice(boolean dab, boolean ready) {
        int tag = R.string.no_signal, title, text;
        noticeAction = 0;
        boolean searchButton = true;
        String detail = "";
        if (dab && !ready) {
            switch (radio.usbState) {
                case UsbLink.OFF:
                    title = R.string.notice_off_title;
                    text = R.string.notice_off_text;
                    break;
                case UsbLink.PERMISSION:
                    title = R.string.notice_permission_title;
                    text = R.string.notice_permission_text;
                    break;
                case UsbLink.DENIED:
                    title = R.string.notice_denied_title;
                    text = R.string.notice_denied_text;
                    break;
                case UsbLink.UNSUPPORTED:
                    title = R.string.notice_unsupported_title;
                    text = R.string.notice_unsupported_text;
                    detail = radio.usbDetail;
                    break;
                case UsbLink.STARTING:
                    title = R.string.notice_starting_title;
                    text = R.string.notice_starting_text;
                    break;
                case UsbLink.DISCONNECTED:
                    title = R.string.notice_disconnected_title;
                    text = R.string.notice_disconnected_text;
                    break;
                default:
                    title = R.string.notice_none_title;
                    text = R.string.notice_none_text;
                    break;
            }
        } else if (dab) {
            tag = R.string.usb_tuner;
            title = R.string.notice_no_stations_title;
            text = R.string.notice_no_stations_text;
            noticeAction = 1;
        } else {
            tag = R.string.tab_web;
            title = R.string.notice_no_web_title;
            text = R.string.notice_no_web_text;
            searchButton = false;
        }
        ui.setTech(noticeTag, getString(tag));
        noticeTitle.setText(getString(title));
        noticeText.setText(detail.isEmpty() ? getString(text) : getString(text) + " (" + detail + ")");
        noticePrimary.setVisibility(searchButton ? View.VISIBLE : View.GONE);
        noticePrimaryText.setText(getString(noticeAction == 1 ? R.string.scan_stations : R.string.search_again));
        noticeSkip.setVisibility(dab && !ready ? View.VISIBLE : View.GONE);
        skipToggle.setOn(settings.b(Settings.SKIP_USB));
    }

    // ---- presets -----------------------------------------------------------------------------

    private void turnPage(int direction) {
        int pages = settings.pages();
        page = ((page + direction) % pages + pages) % pages;
        settings.set(Settings.PRESET_PAGE, page);
        if (radio != null) refresh();
    }

    private void showPresets(Station current) {
        final StationStore store = app.stations;
        int perPage = settings.perPage(), pages = settings.pages();
        StringBuilder sig = new StringBuilder().append(page).append('/').append(perPage).append('/').append(pages)
                .append('/').append(current == null ? "" : current.id);
        for (int i = 0; i < perPage; i++) {
            Station s = store.find(store.presets[page * perPage + i]);
            sig.append('|').append(s == null ? "" : s.id + s.name);
        }
        if (sig.toString().equals(presetSignature)) return;
        presetSignature = sig.toString();

        ui.setTech(presetHeader, getString(R.string.presets_page, page + 1, pages));
        pageBars.removeAllViews();
        for (int i = 0; i < pages; i++) {
            View bar = new View(this);
            bar.setBackgroundColor(i == page ? ui.t.mark : ui.t.barOff);
            pageBars.addView(bar, ui.lp(20, 4));
        }

        presetBox.removeAllViews();
        // Four presets are one row of big buttons as tall as the usual two rows.
        int rows = perPage == 4 ? 1 : 2, cols = perPage / rows;
        for (int r = 0; r < rows; r++) {
            LinearLayout line = ui.row(8);
            for (int c = 0; c < cols; c++) line.addView(presetCell(page * perPage + r * cols + c, current, rows == 1), ui.flex(0, Ui.MATCH));
            LinearLayout.LayoutParams lp = ui.lp(Ui.MATCH, rows == 1 ? 64 + 8 + 64 : 64);
            if (r > 0) lp.topMargin = ui.px(8);
            presetBox.addView(line, lp);
        }
    }

    private View presetCell(final int slot, Station current, boolean big) {
        final Station s = app.stations.find(app.stations.presets[slot]);
        LinearLayout cell = ui.col(0);
        ui.pad(cell, big ? 16 : 12, big ? 12 : 8, big ? 16 : 12, big ? 12 : 8);
        if (s == null) {
            cell.setBackground(ui.dashed(ui.t.lineStrong, 6));
            cell.addView(ui.tech(getString(R.string.hold_to_save), 12, ui.t.dim));
        } else {
            boolean active = current != null && current.id.equals(s.id);
            int fg = active ? ui.t.onSelected : ui.t.text;
            cell.setBackground(ui.choice(active));
            String where = s.isDab() && s.loc() != null ? DabChannels.label(s.loc().khz) : getString(R.string.web_short);
            cell.addView(ui.tech(String.format(Locale.US, "%02d · %s", slot + 1, where), 12, fg));
            cell.addView(ui.spacer(), ui.flex(1, 0));
            if (big) {
                NameView name = new NameView(ui, fg, 34, 28, 22, 18);
                name.setText(s.name);
                cell.addView(name, ui.lp(Ui.MATCH, Ui.WRAP));
            } else {
                cell.addView(ui.label(s.name, ui.bold(), 17, fg), ui.lp(Ui.MATCH, Ui.WRAP));
            }
        }
        cell.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (s != null && radio != null) radio.tune(s);
                else if (s == null) toast(getString(R.string.hold_to_save_hint));
            }
        });
        // Returning true consumes the gesture, so a long press never also tunes.
        cell.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                holdPreset(slot, s);
                return true;
            }
        });
        return cell;
    }

    private void holdPreset(final int slot, final Station existing) {
        final Station current = radio == null ? null : radio.station;
        if (existing == null) {
            if (current != null) savePreset(slot, current);
            return;
        }
        final boolean canReplace = current != null && !current.id.equals(existing.id);
        String[] items = canReplace
                ? new String[]{getString(R.string.preset_replace, current.name), getString(R.string.preset_remove)}
                : new String[]{getString(R.string.preset_remove)};
        Chooser.show(this, ui, getString(R.string.preset_title, slot + 1, existing.name), items, -1, new Chooser.OnPick() {
            @Override
            public void onPick(int index) {
                if (canReplace && index == 0) {
                    savePreset(slot, current);
                } else {
                    app.stations.setPreset(slot, null);
                    if (radio != null) refresh();
                }
            }
        });
    }

    private void savePreset(int slot, Station s) {
        StationStore store = app.stations;
        int old = store.presetOf(s.id);
        if (old >= 0 && old != slot) store.presets[old] = null; // a station sits on one preset
        store.setPreset(slot, s.id);
        toast(getString(R.string.preset_saved, s.name, slot + 1));
        if (radio != null) refresh();
    }

    // ---- lifecycle ---------------------------------------------------------------------------

    @Override
    public void onServiceConnected(android.content.ComponentName cn, android.os.IBinder binder) {
        super.onServiceConnected(cn, binder);
        // Came back to the app: look again for a tuner that was missing, unless searching is off.
        if (radio != null && !settings.b(Settings.SKIP_USB)
                && (radio.usbState == UsbLink.NO_DEVICE || radio.usbState == UsbLink.DISCONNECTED)) {
            radio.searchUsb();
        }
        if (Build.VERSION.SDK_INT >= 33) ensurePermission(android.Manifest.permission.POST_NOTIFICATIONS, 1);
    }

    /**
     * "Close with back" on: back ends playback and the app. Off: back only leaves the
     * screen and the radio keeps playing from the service.
     */
    @Override
    protected void onBack() {
        leave(settings.b(Settings.FINISH_BACK));
    }

    /** Ends playback and the app, or only moves it to the background with the radio playing on. */
    private void leave(boolean close) {
        if (close && radio != null) {
            radio.exit();
            finish();
        } else {
            moveTaskToBack(true);
        }
    }
}
