package me.ri3d.welle.ui;

import android.app.UiModeManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;

import java.util.TimeZone;

import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.SunClock;

/** Colour tokens of the reference design for the night and day themes, plus theme selection. */
public final class Theme {
    public static final int[] ACCENTS = {0xFFFFB224, 0xFF4CD6E8, 0xFFFF6B81, 0xFF7CE38B, 0xFFFF8AC8, 0xFFFF8A3D};

    public final boolean night;
    public final int bg, panel, tile, line, lineStrong, text, text2, dim;
    public final int tickMinor, barOff, rulerOff, rulerOffLabel, passedMinor, ok;
    public final int accent, onAccent;
    /** Thin marks (needle, bars, dots, active underline): the accent at night, ink by day. */
    public final int mark;
    /** Border of a selected control. */
    public final int selectedRing;
    /** Text on a selected (accent-filled) control. */
    public final int onSelected;

    public Theme(boolean night, int accentIndex) {
        this.night = night;
        accent = ACCENTS[Math.max(0, Math.min(ACCENTS.length - 1, accentIndex))];
        onAccent = 0xFF1A1200;
        if (night) {
            bg = 0xFF0A0A0B;
            panel = 0xFF121214;
            tile = 0xFF1F1F23;
            line = 0xFF2A2A2E;
            lineStrong = 0xFF55555C;
            text = 0xFFF3EFE6;
            text2 = 0xFFD6D3CC;
            dim = 0xFFA3A3AB;
            tickMinor = 0xFF55555C;
            barOff = 0xFF3A3A40;
            rulerOff = 0xFF3A3A40;
            rulerOffLabel = 0xFF8A8A92;
            passedMinor = 0xFF8A8A92;
            ok = 0xFF5BD08A;
            mark = accent;
            selectedRing = accent;
            onSelected = onAccent;
        } else {
            bg = 0xFFF1EEE7;
            panel = 0xFFFFFFFF;
            tile = 0xFFE9E5DC;
            line = 0xFFCFCAC0;
            lineStrong = 0xFF8C877B;
            text = 0xFF17150F;
            text2 = 0xFF36332B;
            dim = 0xFF5C584F;
            tickMinor = 0xFFA8A398;
            barOff = 0xFFCFCAC0;
            rulerOff = 0xFFCFCAC0;
            rulerOffLabel = 0xFF8C877B;
            passedMinor = 0xFF8C877B;
            ok = 0xFF1F8A4C;
            mark = text;
            selectedRing = text;
            onSelected = text;
        }
    }

    /** Whether the night palette applies under the "Theme" setting. */
    public static boolean resolveNight(Context c, Settings s) {
        switch (s.i(Settings.THEME)) {
            case 1:
                return false;
            case 2:
                return afterSunset(c, s);
            case 3:
                return systemNight(c, s);
            default:
                return true;
        }
    }

    /**
     * Auto (system): Android 10+ has a real dark-theme switch. Older versions only report
     * night when a car/desk night mode is active or set to automatic; if the platform just
     * says "no" there, that means "no opinion", and the sunset calculation decides.
     */
    private static boolean systemNight(Context c, Settings s) {
        int flag = c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        if (flag == Configuration.UI_MODE_NIGHT_YES) return true;
        if (Build.VERSION.SDK_INT >= 29 && flag == Configuration.UI_MODE_NIGHT_NO) return false;
        UiModeManager ui = (UiModeManager) c.getSystemService(Context.UI_MODE_SERVICE);
        if (ui != null && ui.getNightMode() == UiModeManager.MODE_NIGHT_AUTO && flag == Configuration.UI_MODE_NIGHT_NO) return false;
        return afterSunset(c, s);
    }

    /**
     * Sun position at the last known location. Without location permission or any fix, the
     * position is assumed to be 50 degrees north on the meridian of the device time zone.
     */
    public static boolean afterSunset(Context c, Settings s) {
        double lat = SunClock.FALLBACK_LAT;
        double lon = SunClock.fallbackLon(TimeZone.getDefault());
        try {
            String la = s.s(Settings.LAST_LAT), lo = s.s(Settings.LAST_LON);
            if (!la.isEmpty() && !lo.isEmpty()) {
                lat = Double.parseDouble(la);
                lon = Double.parseDouble(lo);
            }
        } catch (NumberFormatException ignored) {
        }
        return SunClock.isNight(System.currentTimeMillis(), lat, lon);
    }

    /**
     * Stores the most recent position any provider already has. No location updates are
     * requested, so this never powers the GPS; a head unit running navigation keeps it fresh.
     */
    public static void updateLocation(Context c, Settings s) {
        if (Build.VERSION.SDK_INT >= 23
                && c.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        try {
            LocationManager lm = (LocationManager) c.getSystemService(Context.LOCATION_SERVICE);
            Location best = null;
            for (String provider : lm.getProviders(true)) {
                Location l = lm.getLastKnownLocation(provider);
                if (l != null && (best == null || l.getTime() > best.getTime())) best = l;
            }
            if (best != null) {
                s.set(Settings.LAST_LAT, String.valueOf(best.getLatitude()));
                s.set(Settings.LAST_LON, String.valueOf(best.getLongitude()));
            }
        } catch (RuntimeException ignored) {
            // No permission or no location service: the fallback position is used.
        }
    }
}
