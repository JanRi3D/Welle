package me.ri3d.welle.core;

import java.util.Calendar;
import java.util.TimeZone;

/** Sunrise/sunset from date and position (NOAA low-accuracy equations, about a minute off). */
public final class SunClock {
    private SunClock() { }

    /** Latitude used when no position is known: central Europe. */
    public static final double FALLBACK_LAT = 50.0;

    /** Without a position, assume the meridian of the time zone (15 degrees per hour). */
    public static double fallbackLon(TimeZone tz) {
        return tz.getRawOffset() / 3600000.0 * 15.0;
    }

    /** True between sunset and sunrise at the given place. */
    public static boolean isNight(long utcMillis, double lat, double lon) {
        Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        utc.setTimeInMillis(utcMillis);
        double minutes = utc.get(Calendar.HOUR_OF_DAY) * 60 + utc.get(Calendar.MINUTE);
        double g = 2 * Math.PI / 365.0 * (utc.get(Calendar.DAY_OF_YEAR) - 1 + (minutes / 60.0 - 12) / 24.0);
        double eqTime = 229.18 * (0.000075 + 0.001868 * Math.cos(g) - 0.032077 * Math.sin(g)
                - 0.014615 * Math.cos(2 * g) - 0.040849 * Math.sin(2 * g));
        double decl = 0.006918 - 0.399912 * Math.cos(g) + 0.070257 * Math.sin(g)
                - 0.006758 * Math.cos(2 * g) + 0.000907 * Math.sin(2 * g)
                - 0.002697 * Math.cos(3 * g) + 0.00148 * Math.sin(3 * g);
        double latR = Math.toRadians(lat);
        double cosH = Math.cos(Math.toRadians(90.833)) / (Math.cos(latR) * Math.cos(decl)) - Math.tan(latR) * Math.tan(decl);
        if (cosH > 1) return true;   // polar night
        if (cosH < -1) return false; // midnight sun
        double ha = Math.toDegrees(Math.acos(cosH));
        double sunrise = 720 - 4 * (lon + ha) - eqTime;
        double dayLength = 8 * ha;
        double sinceSunrise = ((minutes - sunrise) % 1440 + 1440) % 1440;
        return sinceSunrise >= dayLength;
    }
}
