package me.ri3d.welle.core;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * All persistent settings. Selector settings store the index of the chosen option; the
 * option lists and defaults mirror the reference design.
 */
public final class Settings {
    // General
    public static final String LANG = "lang";                 // 0 Auto, 1 English, 2 Deutsch
    public static final String START_USB = "startUsb";
    public static final String START_BG = "startBck";
    public static final String FINISH_BACK = "finishBack";
    public static final String FINISH_FOCUS = "finishFocus";
    public static final String MINIMIZE = "minimize";         // menu-bar button: 0 Minimize, 1 Close the app
    public static final String SERVICE_FOLLOWING = "sf";
    public static final String STUTTER = "stutter";
    public static final String SKIP_USB = "skipUsb";          // "Do not search for a USB adapter on future starts"
    // Layout & presets
    public static final String BARS = "bars";                 // 0 Keep notch clear, 1 Hide status bar only, 2 Fullscreen, 3 Normal
    public static final String MENU_TOP = "menuTop";
    public static final String CLOCK = "clock";
    public static final String NOW_PLAYING = "nowPlaying";
    public static final String DLS_TOP = "dlsTop";
    public static final String DLS_OVERLAY = "dlsOverlay";    // 0 Off, 1 5 s, 2 10 s, 3 Infinite
    public static final String PER_PAGE = "perPage";          // 0 6, 1 8, 2 12, 3 4
    public static final String PAGES = "pages";               // 0..4 -> 1..5
    public static final String HIDE_PRESETS = "hidePresets";  // 0 Off, 1 In landscape
    // Slideshow & scene
    public static final String SLIDESHOW = "show";
    public static final String TRANSPARENCY = "transp";       // 0 Opaque, 1 25 %, 2 50 %, 3 75 %
    public static final String DIM = "dim";
    public static final String DIM_BRIGHT = "dimBright";      // 0 25 %, 1 50 %, 2 75 %
    public static final String VIS = "vis";                   // 0 None .. 6 Retro
    public static final String SCENE = "scene";               // 1 when a scene file is installed
    // Audio
    public static final String VOLUME = "volume";             // 0 100 % .. 5 50 %
    public static final String AGC = "agc";
    public static final String NOISE = "noise";
    public static final String MUTE_FOCUS = "muteFocus";
    public static final String DUCK = "duck";                 // 0 25 %, 1 50 %, 2 75 %
    // Theme
    public static final String THEME = "theme";               // 0 Night, 1 Day, 2 Auto (GPS), 3 Auto (system)
    public static final String ACCENT = "accent";             // 0 Amber .. 5 Pumpkin
    // Session state
    public static final String SOURCE = "source";             // 0 DAB, 1 web
    public static final String LAST_DAB = "lastDab";
    public static final String LAST_WEB = "lastWeb";
    public static final String WANT_PLAY = "wantPlay";
    public static final String PRESET_PAGE = "presetPage";
    public static final String SCAN_MODE = "scanMode";        // 0 keep favourites, 1 replace all
    public static final String API_URL = "apiUrl";
    public static final String API_COUNTRY = "apiCountry";
    public static final String API_GENRE = "apiGenre";
    public static final String M3U_URL = "m3uUrl";
    public static final String WEB_SOURCE = "webSource";      // 0 API, 1 File, 2 M3U URL
    public static final String LAST_LAT = "lastLat";
    public static final String LAST_LON = "lastLon";

    /** Documented at https://api.radio-browser.info/ ; any server of that pool works. */
    public static final String DEFAULT_API_URL = "https://de1.api.radio-browser.info";

    /** 4 came later and is last, so stored choices keep their meaning; the selector wraps around anyway. */
    public static final int[] PER_PAGE_OPTS = {6, 8, 12, 4};
    public static final int[] PERCENT_25_50_75 = {25, 50, 75};
    public static final int[] VOLUME_OPTS = {100, 90, 80, 70, 60, 50};
    public static final int[] TRANSPARENCY_OPTS = {0, 25, 50, 75};
    public static final int[] OVERLAY_SECONDS = {0, 5, 10, -1};
    /** Slots are kept for the largest layout so changing it never drops a preset. */
    public static final int MAX_PRESETS = 5 * 12;

    private final SharedPreferences p;

    public Settings(Context c) {
        p = c.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    public boolean b(String key) {
        return p.getBoolean(key, defBool(key));
    }

    public int i(String key) {
        return p.getInt(key, defInt(key));
    }

    public String s(String key) {
        return p.getString(key, API_URL.equals(key) ? DEFAULT_API_URL : API_COUNTRY.equals(key) ? "DE" : "");
    }

    public void set(String key, boolean v) { p.edit().putBoolean(key, v).apply(); }
    public void set(String key, int v) { p.edit().putInt(key, v).apply(); }
    public void set(String key, String v) { p.edit().putString(key, v).apply(); }

    public static boolean defBool(String key) {
        return FINISH_FOCUS.equals(key) || SERVICE_FOLLOWING.equals(key) || MENU_TOP.equals(key)
                || CLOCK.equals(key) || NOW_PLAYING.equals(key) || SLIDESHOW.equals(key) || DIM.equals(key)
                || WANT_PLAY.equals(key);
    }

    public static int defInt(String key) {
        if (PER_PAGE.equals(key) || PAGES.equals(key)) return 2;
        if (DIM_BRIGHT.equals(key) || DUCK.equals(key)) return 1;
        return 0;
    }

    // ---- derived values -------------------------------------------------------------------

    public int perPage() { return PER_PAGE_OPTS[clamp(i(PER_PAGE), 0, PER_PAGE_OPTS.length - 1)]; }
    public int pages() { return clamp(i(PAGES), 0, 4) + 1; }
    public float volumeGain() { return VOLUME_OPTS[clamp(i(VOLUME), 0, 5)] / 100f; }
    public float duckGain() { return PERCENT_25_50_75[clamp(i(DUCK), 0, 2)] / 100f; }
    public float dimBrightness() { return PERCENT_25_50_75[clamp(i(DIM_BRIGHT), 0, 2)] / 100f; }
    public float slideshowAlpha() { return 1f - TRANSPARENCY_OPTS[clamp(i(TRANSPARENCY), 0, 3)] / 100f; }
    public int overlaySeconds() { return OVERLAY_SECONDS[clamp(i(DLS_OVERLAY), 0, 3)]; }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
