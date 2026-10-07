package me.ri3d.welle;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.view.KeyEvent;

import me.ri3d.welle.core.Diag;

/** Media keys (steering wheel, headset) on Android 4.x; Android 5+ uses the media session. */
public final class MediaButtonReceiver extends BroadcastReceiver {

    /** Key whose press was acted on and whose release is still to come; -1 if none. */
    private static int pressed = -1;

    /** The service action a media key stands for, or null for any other key. */
    public static String actionFor(int keyCode) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MEDIA_NEXT:
                return RadioService.ACTION_NEXT;
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                return RadioService.ACTION_PREV;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
                return RadioService.ACTION_PLAY;
            case KeyEvent.KEYCODE_MEDIA_PAUSE:
            case KeyEvent.KEYCODE_MEDIA_STOP:
                return RadioService.ACTION_PAUSE;
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
            case KeyEvent.KEYCODE_HEADSETHOOK:
                return RadioService.ACTION_TOGGLE;
            default:
                return null;
        }
    }

    /**
     * One action per key stroke: on the press, or on the release if the sender never reported
     * a press (some head units only broadcast the release).
     *
     * @return the service action to run now, or null
     */
    public static synchronized String decide(int keyAction, int repeat, int keyCode) {
        if (keyAction == KeyEvent.ACTION_DOWN) {
            if (repeat > 0) return null;
            pressed = keyCode;
            return actionFor(keyCode);
        }
        if (keyAction != KeyEvent.ACTION_UP) return null;
        boolean alreadyHandled = pressed == keyCode;
        pressed = -1;
        return alreadyHandled ? null : actionFor(keyCode);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        KeyEvent e = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
        if (e == null) {
            Diag.note("media button broadcast without a key event");
            return;
        }
        if (e.getRepeatCount() == 0) {
            Diag.note("media button broadcast: key " + e.getKeyCode() + (e.getAction() == KeyEvent.ACTION_DOWN ? " down" : " up"));
        }
        String action = decide(e.getAction(), e.getRepeatCount(), e.getKeyCode());
        if (action == null) return;
        try {
            context.startService(new Intent(context, RadioService.class).setAction(action));
        } catch (RuntimeException ignored) {
            // Background start refused (Android 8+): the media session handles keys there.
        }
    }
}
