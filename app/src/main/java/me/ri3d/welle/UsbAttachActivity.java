package me.ri3d.welle;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import me.ri3d.welle.core.Diag;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.ui.PlayerActivity;

/**
 * Invisible landing point for "the USB tuner was plugged in". Android delivers that event
 * only to an activity with a matching device filter. The component is enabled only while
 * "Start when the USB tuner is detected" is on.
 */
public final class UsbAttachActivity extends Activity {

    /** Keeps the manifest component in step with the setting. */
    public static void setEnabled(Context c, boolean on) {
        c.getPackageManager().setComponentEnabledSetting(new ComponentName(c, UsbAttachActivity.class),
                on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Settings settings = App.of(this).settings;
        Diag.note("usb attach intent, start-on-usb " + (settings.b(Settings.START_USB) ? "on" : "off")
                + ", in background " + (settings.b(Settings.START_BG) ? "on" : "off"));
        if (settings.b(Settings.START_USB)) {
            Intent service = new Intent(this, RadioService.class).setAction(RadioService.ACTION_AUTOSTART);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
            // "Start in the background": radio plays, the current screen stays where it is.
            if (!settings.b(Settings.START_BG)) {
                startActivity(new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
        finish();
    }
}
