package me.ri3d.welle;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;

import java.io.File;

import me.ri3d.welle.core.Diag;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.StationStore;
import me.ri3d.welle.logos.LogoJob;
import me.ri3d.welle.logos.LogoStore;
import me.ri3d.welle.web.Net;

/** Process-wide state shared by the service and the activities. */
public final class App extends Application {
    public Settings settings;
    public StationStore stations;
    public LogoStore logos;
    public final LogoJob logoJob = new LogoJob();
    /** Activities between onStart and onStop; the DLS overlay only shows while this is 0. */
    public int visibleActivities;

    public static App of(Context c) {
        return (App) c.getApplicationContext();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
        Diag.init(debuggable ? getFilesDir() : null);
        Diag.note("process start");
        settings = new Settings(this);
        stations = new StationStore(new File(getFilesDir(), "stations.json"));
        logos = new LogoStore(new File(getFilesDir(), "logos"));
        Net.init(this);
    }

    @Override
    public void onLowMemory() {
        super.onLowMemory();
        logos.trim();
    }

    @Override
    public void onTrimMemory(int level) {
        super.onTrimMemory(level);
        if (level >= TRIM_MEMORY_BACKGROUND) logos.trim();
    }
}
