package me.ri3d.welle.ui;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.io.InputStream;

import me.ri3d.welle.App;
import me.ri3d.welle.Locales;
import me.ri3d.welle.MediaButtonReceiver;
import me.ri3d.welle.R;
import me.ri3d.welle.RadioService;
import me.ri3d.welle.core.Diag;
import me.ri3d.welle.core.Io;
import me.ri3d.welle.core.Settings;

/**
 * Common ground of all screens: language, theme, the scaled view toolkit, the connection to
 * the playback service, fullscreen handling and file picking. A screen builds its views in
 * {@link #build()} and updates them from service state in {@link #refresh()}.
 */
public abstract class BaseActivity extends Activity implements RadioService.Listener, ServiceConnection {
    protected App app;
    protected Settings settings;
    /** Null until the service is bound. */
    protected RadioService radio;
    protected Ui ui;

    private FrameLayout root;
    private int rootW, rootH;
    private boolean bound;
    private String look = "";

    private final BroadcastReceiver minuteTick = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            // The auto themes can flip at sunset without any user action.
            if (!look().equals(look)) rebuild();
            else if (radio != null) refresh();
        }
    };

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Locales.wrap(base));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        app = App.of(this);
        settings = app.settings;
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 33) registerBackCallback();
        root = new FrameLayout(this) {
            @Override
            protected void onSizeChanged(int w, int h, int oldW, int oldH) {
                super.onSizeChanged(w, h, oldW, oldH);
                post(new Runnable() {
                    @Override
                    public void run() {
                        sized();
                    }
                });
            }
        };
        setContentView(root);
        // A fullscreen window is not resized for the on-screen keyboard on old Android, so
        // make room for it by hand; scrolling columns then keep every field reachable.
        root.getViewTreeObserver().addOnGlobalLayoutListener(new ViewTreeObserver.OnGlobalLayoutListener() {
            private final Rect visible = new Rect();

            @Override
            public void onGlobalLayout() {
                root.getWindowVisibleDisplayFrame(visible);
                int[] at = new int[2];
                root.getLocationOnScreen(at);
                int hidden = Math.max(0, at[1] + root.getHeight() - visible.bottom);
                if (hidden < root.getHeight() / 6) hidden = 0;
                if (root.getPaddingBottom() != hidden) root.setPadding(0, 0, 0, hidden);
            }
        });
    }

    /**
     * Media keys that arrive as ordinary key events (some head units send their steering-wheel
     * keys to the window in front) act on the radio here, whichever app currently owns the
     * system's media-button slot.
     */
    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        boolean pressed = e.getAction() == KeyEvent.ACTION_DOWN && e.getRepeatCount() == 0;
        // Never the keys that type text: the event log must not record what was entered.
        if (pressed && !e.isPrintingKey()) Diag.note("key " + e.getKeyCode() + " in " + getClass().getSimpleName());
        String action = MediaButtonReceiver.actionFor(e.getKeyCode());
        if (action == null) return super.dispatchKeyEvent(e);
        if (pressed) startService(new Intent(this, RadioService.class).setAction(action));
        return true;
    }

    /** What "back" does on this screen: sub-pages close. */
    protected void onBack() {
        finish();
    }

    /** Hardware/software back key up to Android 12. */
    @SuppressLint("GestureBackNavigation") // Android 13+ is served by registerBackCallback()
    @Override
    public void onBackPressed() {
        onBack();
    }

    /** Android 13+ delivers back (including the gesture) through this callback instead. */
    @TargetApi(33)
    private void registerBackCallback() {
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                new OnBackInvokedCallback() {
                    @Override
                    public void onBackInvoked() {
                        onBack();
                    }
                });
    }

    private void sized() {
        int w = root.getWidth(), h = root.getHeight();
        if (w == 0 || h == 0) return;
        // Rebuild for a new width or a taller window, not when the keyboard shrinks it.
        if (w != rootW || h > rootH || ui == null) {
            rootW = w;
            rootH = h;
            rebuild();
        }
    }

    private String look() {
        return Theme.resolveNight(this, settings) + "/" + settings.i(Settings.ACCENT);
    }

    /** Recreates the view tree, e.g. after a setting that changes the layout or colours. */
    protected final void rebuild() {
        if (rootW == 0) return;
        look = look();
        Theme theme = new Theme(Theme.resolveNight(this, settings), settings.i(Settings.ACCENT));
        ui = new Ui(this, theme, Math.min(rootH / 720f, rootW / 1280f));
        root.removeAllViews();
        root.setBackgroundColor(theme.bg);
        root.addView(build(), new FrameLayout.LayoutParams(Ui.MATCH, Ui.MATCH));
        if (radio != null) refresh();
    }

    protected abstract View build();

    /** Called with a bound service whenever its state changed. */
    protected void refresh() {
    }

    @Override
    protected void onStart() {
        super.onStart();
        Diag.note("screen shown: " + getClass().getSimpleName());
        Theme.updateLocation(this, settings);
        rebuild();
        try {
            startService(new Intent(this, RadioService.class));
        } catch (IllegalStateException ignored) {
            // Not allowed in this state on Android 8+; binding below still creates the service.
        }
        bound = bindService(new Intent(this, RadioService.class), this, BIND_AUTO_CREATE);
        registerReceiver(minuteTick, new IntentFilter(Intent.ACTION_TIME_TICK));
    }

    @Override
    protected void onStop() {
        Diag.note("screen hidden: " + getClass().getSimpleName());
        unregisterReceiver(minuteTick);
        if (radio != null) {
            radio.removeListener(this);
            radio.uiVisible(-1);
            radio = null;
        }
        if (bound) unbindService(this);
        bound = false;
        super.onStop();
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        radio = ((RadioService.LocalBinder) binder).service();
        radio.addListener(this);
        radio.uiVisible(1);
        onRadioChanged();
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        radio = null;
    }

    @Override
    public void onRadioChanged() {
        if (radio == null || ui == null) return;
        if (radio.exiting) {
            finish();
            return;
        }
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus && Build.VERSION.SDK_INT >= 19) {
            // Android 4.1-4.3 use the fullscreen theme alone; from 4.4 also hide the navigation bar.
            getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
        }
    }

    // ---- shared building blocks --------------------------------------------------------------

    /** Sub-page header: back chevron, condensed title, optional technical label on the right. */
    protected LinearLayout header(String title, TextView right) {
        LinearLayout bar = ui.row(8);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        ui.pad(bar, 40 - 14, 0, 40, 0);
        IconView back = new IconView(this, IconView.BACK, ui.px(26), ui.t.text);
        back.setContentDescription(getString(R.string.cd_back));
        back.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onBack();
            }
        });
        bar.addView(back, ui.lp(48, 48));
        bar.addView(ui.label(title, ui.cond(), 34, ui.t.text), ui.flex(0, Ui.WRAP));
        if (right != null) bar.addView(right);
        LinearLayout wrap = ui.col(0);
        wrap.addView(bar, ui.lp(Ui.MATCH, 63));
        View rule = new View(this);
        rule.setBackgroundColor(ui.t.line);
        wrap.addView(rule, ui.lp(Ui.MATCH, 1));
        return wrap;
    }

    /**
     * Column count for a grid that has {@code base} columns on the 1280-unit artboard:
     * wider windows (1920 x 720) get proportionally more columns instead of wider cells.
     */
    protected int columns(int base) {
        return Math.max(base, Math.round(base * (rootW / ui.u) / 1280f - 0.2f));
    }

    protected void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    /** Runtime permission check for Android 6+; older versions grant at install time. */
    protected boolean ensurePermission(String permission, int request) {
        if (Build.VERSION.SDK_INT < 23 || checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED) return true;
        requestPermissions(new String[]{permission}, request);
        return false;
    }

    // ---- file picking ------------------------------------------------------------------------

    /**
     * Lets the user choose a file. Android 4.4+ has the system document picker; before that
     * any installed file manager answering GET_CONTENT is used; if there is none, the
     * built-in file browser opens.
     */
    protected void pickFile(int request) {
        Intent i = new Intent(Build.VERSION.SDK_INT >= 19 ? Intent.ACTION_OPEN_DOCUMENT : Intent.ACTION_GET_CONTENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            // Only asked on Android 4.1-4.3, long before package visibility rules existed.
            if (Build.VERSION.SDK_INT < 19 && noHandler(i)) throw new ActivityNotFoundException();
            startActivityForResult(i, request);
        } catch (ActivityNotFoundException e) {
            startActivityForResult(new Intent(this, FilePickerActivity.class), request);
        }
    }

    @SuppressLint("QueryPermissionsNeeded")
    private boolean noHandler(Intent i) {
        return i.resolveActivity(getPackageManager()) == null;
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result == RESULT_OK && data != null && data.getData() != null) onFilePicked(request, data.getData());
    }

    protected void onFilePicked(int request, Uri uri) {
    }

    /** Reads a picked file completely, refusing anything larger than {@code max} bytes. */
    protected byte[] readPicked(Uri uri, int max) throws IOException {
        InputStream in = getContentResolver().openInputStream(uri);
        if (in == null) throw new IOException("file cannot be opened");
        try {
            return Io.readAll(in, max);
        } finally {
            Io.close(in);
        }
    }
}
