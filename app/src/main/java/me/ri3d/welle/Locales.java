package me.ri3d.welle;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;

import java.util.Locale;

import me.ri3d.welle.core.Settings;

/** Applies the "Language" setting (Auto, English, Deutsch) to a context's resources. */
public final class Locales {
    private Locales() { }

    public static Locale chosen(Context c) {
        switch (new Settings(c).i(Settings.LANG)) {
            case 1:
                return Locale.ENGLISH;
            case 2:
                return Locale.GERMAN;
            default:
                return Resources.getSystem().getConfiguration().locale;
        }
    }

    /** A context whose strings come from the chosen language. */
    @SuppressWarnings("deprecation") // Configuration.locale / updateConfiguration are the API 16 way
    public static Context wrap(Context base) {
        Locale locale = chosen(base);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        if (Build.VERSION.SDK_INT >= 17) {
            config.setLocale(locale);
            return base.createConfigurationContext(config);
        }
        config.locale = locale;
        base.getResources().updateConfiguration(config, base.getResources().getDisplayMetrics());
        return base;
    }
}
