package me.ri3d.welle;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.TextView;

import org.omri.radio.impl.RadioServiceDabComponentImpl;
import org.omri.radio.impl.RadioServiceDabImpl;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;

import me.ri3d.welle.audio.AudioEngine;
import me.ri3d.welle.core.DabChannels;
import me.ri3d.welle.core.Diag;
import me.ri3d.welle.core.FocusPolicy;
import me.ri3d.welle.core.ServiceFollower;
import me.ri3d.welle.core.Settings;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.core.StationStore;
import me.ri3d.welle.core.UsbLink;
import me.ri3d.welle.dab.DabAudio;
import me.ri3d.welle.dab.DabTuner;
import me.ri3d.welle.logos.LogoStore;
import me.ri3d.welle.ui.PlayerActivity;
import me.ri3d.welle.web.StreamPlayer;

/**
 * Owns playback. The tuner, the web stream player, the audio output, audio focus, the
 * notification and the scan live here; activities only show this state and call the
 * methods below. All fields and methods are main-thread only.
 */
public final class RadioService extends Service
        implements DabTuner.Listener, DabAudio.Meta, StreamPlayer.Listener, AudioManager.OnAudioFocusChangeListener {

    public static final String ACTION_TOGGLE = "me.ri3d.welle.TOGGLE";
    public static final String ACTION_NEXT = "me.ri3d.welle.NEXT";
    public static final String ACTION_PREV = "me.ri3d.welle.PREV";
    public static final String ACTION_PLAY = "me.ri3d.welle.PLAY";
    public static final String ACTION_PAUSE = "me.ri3d.welle.PAUSE";
    /**
     * Now-playing state for other apps on the head unit, e.g. OpenDashboard: a sticky broadcast,
     * because Android 4.x has no MediaSession and a dashboard may start after the radio. Extras:
     * source "dab"|"web", station, text (DLS or stream title), status, playing (bool),
     * state "idle"|"loading"|"playing"|"error", index and count (1-based position in the list
     * that next/previous step through; 0 = none), preset (1-based slot; 0 = not on a preset),
     * art (content URI of the picture WELLE shows: slideshow if enabled and received, else the
     * logo; empty = none, see {@link ArtProvider}) and artVersion (changes with the picture).
     */
    public static final String ACTION_STATE = "me.ri3d.welle.STATE";
    /** Sent when the USB tuner is plugged in and autostart is enabled. */
    public static final String ACTION_AUTOSTART = "me.ri3d.welle.AUTOSTART";

    public static final int SRC_DAB = 0;
    public static final int SRC_WEB = 1;

    public static final int IDLE = 0;
    public static final int LOADING = 1;
    public static final int PLAYING = 2;
    public static final int ERROR = 3;

    private static final int NOTIFICATION_ID = 1;
    private static final String CHANNEL = "playback";

    public interface Listener {
        void onRadioChanged();
    }

    public final class LocalBinder extends Binder {
        public RadioService service() {
            return RadioService.this;
        }
    }

    // ---- state shown by the UI ---------------------------------------------------------------

    public int source;
    public Station station;
    /** The user wants audio; false after pause. */
    public boolean wantPlay;
    public int playState = IDLE;
    /** Localised detail for LOADING/ERROR, empty while playing normally. */
    public String status = "";
    /** DLS text (DAB) or stream title (web). */
    public String dls = "";
    public Bitmap slide;
    public boolean rfLock;
    /** 0..6 from the tuner's error-rate estimate. */
    public int quality;
    public boolean formatKnown;
    public boolean stereo;
    public boolean dabPlus = true;
    /** e.g. "MP3 · 128 KBPS", empty until the stream format is known. */
    public String webFormat = "";
    public int usbState = UsbLink.NO_DEVICE;
    public String usbDetail = "";
    /** Which of the station's locations is tuned; > 0 means service following switched. */
    public int locIndex;
    /** Set when the app is closing itself; activities finish when they see it. */
    public boolean exiting;

    public boolean scanning;
    public boolean scanPaused;
    public boolean scanDone;
    /** Index into DabChannels.KHZ of the channel being scanned; COUNT when complete. */
    public int scanIndex;
    public final ArrayList<Station> scanFound = new ArrayList<Station>();
    public StationStore.ScanOutcome scanOutcome;

    // ---- internals ---------------------------------------------------------------------------

    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<Listener> listeners = new ArrayList<Listener>();
    private final FocusPolicy focus = new FocusPolicy();
    private final ServiceFollower follower = new ServiceFollower();
    private final AudioEngine engine = new AudioEngine();

    private App app;
    private Settings settings;
    private StationStore store;
    private AudioManager audio;
    private DabTuner tuner;
    private StreamPlayer player;
    private DabAudio dabAudio;
    private RadioServiceDabImpl nativeService;
    private PowerManager.WakeLock wakeLock;
    private MediaSession21 mediaSession;
    private boolean hasFocus;
    private boolean foreground;
    private boolean changePosted;
    private int notedPlayState = -1;
    private String lastState = "";
    /** Changes whenever a new slideshow picture has been written for ArtProvider. */
    private long slideStamp;
    private static final String[] PLAY_NAMES = {"idle", "loading", "playing", "error"};
    private boolean retried;
    private long tuneStartedMs;
    /** libirtdab drops a start that arrives while a stop or scan abort is still being processed. */
    private long dabHoldUntilMs;
    private volatile int webGeneration;
    private String lastNotification = "";
    private TextView overlay;

    /**
     * "Next" and "previous" in the form the stock Android 4 music player accepts. Players of
     * that era honour it, and some head units send it for their steering-wheel keys instead of
     * media key events. Only those two commands are obeyed, and only while the radio is on.
     */
    private static final String MUSIC_COMMAND = "com.android.music.musicservicecommand";

    private final BroadcastReceiver musicCommands = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = String.valueOf(intent.getAction());
            String command = intent.getStringExtra("command");
            Diag.note("music command broadcast" + action.substring(MUSIC_COMMAND.length()) + " command=" + command);
            if (!wantPlay) return;
            if (action.endsWith(".next") || "next".equals(command)) next();
            else if (action.endsWith(".previous") || "previous".equals(command)) prev();
        }
    };

    @SuppressWarnings("UnspecifiedRegisterReceiverFlag") // the flag exists from API 33 and is passed there
    private void listenForMusicCommands() {
        IntentFilter f = new IntentFilter(MUSIC_COMMAND);
        f.addAction(MUSIC_COMMAND + ".next");
        f.addAction(MUSIC_COMMAND + ".previous");
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(musicCommands, f, Context.RECEIVER_EXPORTED);
        else registerReceiver(musicCommands, f);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        app = App.of(this);
        settings = app.settings;
        store = app.stations;
        audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        player = new StreamPlayer(engine, this);
        tuner = new DabTuner(this, this);
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "welle:playback");
        wakeLock.setReferenceCounted(false);
        if (Build.VERSION.SDK_INT >= 21) mediaSession = new MediaSession21(this);

        source = settings.i(Settings.SOURCE) == SRC_WEB ? SRC_WEB : SRC_DAB;
        station = store.find(settings.s(source == SRC_DAB ? Settings.LAST_DAB : Settings.LAST_WEB));
        if (station == null && !store.list(source).isEmpty()) station = store.list(source).get(0);
        applyAudioSettings();

        Diag.note("service created: play " + settings.b(Settings.WANT_PLAY) + ", exit on focus loss "
                + settings.b(Settings.FINISH_FOCUS) + ", usb search " + !settings.b(Settings.SKIP_USB));
        listenForMusicCommands();
        tuner.start();
        if (settings.b(Settings.SKIP_USB)) tuner.disable();
        else tuner.search();
        if (settings.b(Settings.WANT_PLAY)) play();
        changed(); // publishes the initial state for dashboards even when nothing plays
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        exiting = false;
        if (action != null) Diag.note("command " + action.substring(action.lastIndexOf('.') + 1));
        if (ACTION_TOGGLE.equals(action)) togglePlay();
        else if (ACTION_NEXT.equals(action)) next();
        else if (ACTION_PREV.equals(action)) prev();
        else if (ACTION_PLAY.equals(action)) play();
        else if (ACTION_PAUSE.equals(action)) pause();
        else if (ACTION_AUTOSTART.equals(action)) {
            // Started with startForegroundService(): the notification is due whatever happens next.
            goForeground();
            searchUsb();
            setSource(SRC_DAB);
            play();
            if (station == null) {
                stopForeground(true);
                foreground = false;
            }
        }
        // Restart after a kill only makes sense while the radio is meant to be on.
        return wantPlay ? START_STICKY : START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return new LocalBinder();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        if (!wantPlay) stopSelf();
    }

    @Override
    public void onDestroy() {
        Diag.note("service destroyed");
        unregisterReceiver(musicCommands);
        main.removeCallbacksAndMessages(null);
        hideOverlay();
        player.stop();
        stopDabAudio();
        tuner.release();
        engine.release();
        abandonFocus();
        if (wakeLock.isHeld()) wakeLock.release();
        if (mediaSession != null) mediaSession.release();
        if (Build.VERSION.SDK_INT < 21) {
            audio.unregisterMediaButtonEventReceiver(new ComponentName(this, MediaButtonReceiver.class));
        }
        stopForeground(true);
        publishState(false);
        super.onDestroy();
    }

    /** Strings in the language chosen in the app, which may differ from the system language. */
    private String str(int id, Object... args) {
        return Locales.wrap(this).getString(id, args);
    }

    // ---- listeners ---------------------------------------------------------------------------

    public void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** Tells the UI to refresh; several changes in one pass cause one refresh. */
    private void changed() {
        if (changePosted) return;
        changePosted = true;
        main.post(new Runnable() {
            @Override
            public void run() {
                changePosted = false;
                if (playState != notedPlayState) {
                    notedPlayState = playState;
                    Diag.note("play state " + PLAY_NAMES[playState] + (source == SRC_DAB ? " (dab)" : " (web)"));
                }
                updateNotification();
                publishState(true);
                for (Listener l : new ArrayList<Listener>(listeners)) l.onRadioChanged();
            }
        });
    }

    /** Called by activities from onStart (+1) and onStop (-1). */
    public void uiVisible(int delta) {
        app.visibleActivities = Math.max(0, app.visibleActivities + delta);
        if (app.visibleActivities > 0) {
            hideOverlay();
        } else {
            // Nothing to do in the background without playback or a scan: free the memory.
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (app.visibleActivities == 0 && !wantPlay && !scanning) stopSelf();
                }
            }, 5000);
        }
    }

    // ---- transport ---------------------------------------------------------------------------

    public void setSource(int src) {
        if (src == source) return;
        stopPlayback();
        source = src;
        settings.set(Settings.SOURCE, src);
        station = store.find(settings.s(src == SRC_DAB ? Settings.LAST_DAB : Settings.LAST_WEB));
        if (station == null && !store.list(src).isEmpty()) station = store.list(src).get(0);
        resetNowPlaying();
        if (wantPlay) startPlayback();
        changed();
    }

    /** Switches to this station (and its source) and plays it. */
    public void tune(Station s) {
        if (s == null) return;
        boolean same = station != null && station.id.equals(s.id);
        source = s.isDab() ? SRC_DAB : SRC_WEB;
        station = s;
        locIndex = 0;
        follower.reset();
        settings.set(Settings.SOURCE, source);
        settings.set(s.isDab() ? Settings.LAST_DAB : Settings.LAST_WEB, s.id);
        if (!same) resetNowPlaying();
        play();
    }

    public void next() {
        step(1);
    }

    public void prev() {
        step(-1);
    }

    private void step(int d) {
        ArrayList<Station> list = store.list(source);
        if (list.isEmpty()) return;
        int i = station == null ? -1 : indexOf(list, station.id);
        tune(list.get(((i < 0 ? 0 : i + d) % list.size() + list.size()) % list.size()));
    }

    private static int indexOf(ArrayList<Station> list, String id) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).id.equals(id)) return i;
        return -1;
    }

    public void togglePlay() {
        if (wantPlay && playState != ERROR && !focus.mutedByLoss) pause();
        else play();
    }

    public void play() {
        wantPlay = true;
        settings.set(Settings.WANT_PLAY, true);
        startPlayback();
        changed();
    }

    /**
     * Pause. DAB is live radio: this stops decoding and silences the output, there is no
     * time-shift buffer, and play resumes with whatever is on air then.
     */
    public void pause() {
        wantPlay = false;
        settings.set(Settings.WANT_PLAY, false);
        stopPlayback();
        abandonFocus();
        changed();
    }

    /** Ends playback and closes the app's screens ("close with back", "exit on focus loss"). */
    public void exit() {
        Diag.note("exit");
        wantPlay = false;
        settings.set(Settings.WANT_PLAY, false);
        stopPlayback();
        abandonFocus();
        exiting = true;
        changed();
        stopSelf();
    }

    /** A station is about to be deleted: stop it if it is the current one and move on. */
    public void forget(String id) {
        if (station == null || !station.id.equals(id)) return;
        stopPlayback();
        station = null;
        for (Station s : store.list(source)) {
            if (!s.id.equals(id)) {
                station = s;
                break;
            }
        }
        resetNowPlaying();
        changed();
    }

    private void resetNowPlaying() {
        dls = "";
        slide = null;
        webFormat = "";
        formatKnown = false;
        rfLock = false;
        quality = 0;
        status = "";
    }

    @android.annotation.SuppressLint("WakelockTimeout")
    private void startPlayback() {
        if (station == null) {
            playState = IDLE;
            return;
        }
        if (!hasFocus) {
            int r = audio.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
            if (r != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                Diag.note("audio focus request denied");
                playState = ERROR;
                status = str(R.string.status_focus_denied);
                return;
            }
            hasFocus = true;
        }
        focus.reset();
        applyAudioSettings();
        if (station.isDab()) {
            webGeneration++;
            player.stop();
            if (scanning) {
                playState = IDLE;
            } else if (usbState == UsbLink.READY) {
                startDab();
            } else {
                playState = LOADING;
                status = str(R.string.status_waiting_adapter);
            }
        } else {
            stopDab();
            webGeneration++;
            playState = LOADING;
            status = str(R.string.status_connecting);
            player.play(station.url);
        }
        // No timeout: a radio plays until it is paused, and pause/stop always release the lock.
        if (!wakeLock.isHeld()) wakeLock.acquire();
        if (Build.VERSION.SDK_INT < 21) {
            audio.registerMediaButtonEventReceiver(new ComponentName(this, MediaButtonReceiver.class));
        }
        goForeground();
        main.removeCallbacks(ticker);
        main.postDelayed(ticker, 1000);
    }

    private void stopPlayback() {
        main.removeCallbacks(ticker);
        webGeneration++;
        player.stop();
        stopDab();
        engine.end();
        playState = IDLE;
        status = "";
        hideOverlay();
        if (wakeLock.isHeld()) wakeLock.release();
        if (foreground) {
            stopForeground(false);
            foreground = false;
        }
    }

    /** Nothing is audible and nothing will be without the user: stop holding the device awake. */
    private void standDown() {
        if (wakeLock.isHeld()) wakeLock.release();
        if (foreground) {
            stopForeground(false);
            foreground = false;
        }
    }

    private void startDab() {
        if (station == null || station.locs.isEmpty()) return;
        Station.Loc loc = station.locs.get(Math.min(locIndex, station.locs.size() - 1));
        int hz = loc.khz * 1000;
        playState = LOADING;
        status = str(R.string.status_tuning);
        tuneStartedMs = SystemClock.elapsedRealtime();
        retried = false;
        if (nativeService != null && dabAudio != null && nativeService.serviceId == station.sid && nativeService.ensembleFrequency == hz) {
            // Same service again: libirtdab ignores a second start, so keep the running link.
            tuner.play(nativeService);
            return;
        }
        stopDabAudio();
        dabAudio = new DabAudio(engine, this);
        final RadioServiceDabImpl svc = new RadioServiceDabImpl();
        svc.serviceId = station.sid;
        svc.ecc = station.ecc;
        svc.ensembleId = loc.eid;
        svc.ensembleFrequency = hz;
        svc.sink = dabAudio;
        nativeService = svc;
        long hold = dabHoldUntilMs - SystemClock.elapsedRealtime();
        if (hold > 0) {
            main.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (nativeService == svc) tuner.play(svc);
                }
            }, hold);
        } else {
            tuner.play(svc);
        }
    }

    private void stopDab() {
        if (nativeService != null) {
            tuner.stop();
            dabHoldUntilMs = SystemClock.elapsedRealtime() + 400;
        }
        nativeService = null;
        stopDabAudio();
    }

    private void stopDabAudio() {
        if (dabAudio != null) {
            dabAudio.stop();
            dabAudio = null;
        }
    }

    /** Once a second while DAB should be playing: start-up watchdog and service following. */
    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            if (!wantPlay) return;
            main.postDelayed(this, 1000);
            if (station == null || !station.isDab() || scanning || usbState != UsbLink.READY || nativeService == null) return;
            long now = SystemClock.elapsedRealtime();
            boolean receiving = dabAudio != null && dabAudio.lastAudioMs() > tuneStartedMs && now - dabAudio.lastAudioMs() < 2500;
            if (receiving && playState == LOADING) {
                playState = PLAYING;
                status = "";
                changed();
            } else if (!receiving && playState == PLAYING) {
                playState = LOADING;
                status = str(R.string.status_no_signal);
                changed();
            } else if (!receiving && playState == LOADING) {
                if (!retried && now - tuneStartedMs > 4000) {
                    retried = true;
                    tuner.play(nativeService);
                } else if (now - tuneStartedMs > 10000 && !str(R.string.status_no_signal).equals(status)) {
                    status = str(R.string.status_no_signal);
                    changed();
                }
            }
            int to = follower.tick(now, settings.b(Settings.SERVICE_FOLLOWING), receiving, station.locs.size(), locIndex);
            if (to >= 0) {
                locIndex = to;
                rfLock = false;
                quality = 0;
                startDab();
                changed();
            }
        }
    };

    // ---- audio focus and settings ------------------------------------------------------------

    /** Re-reads the audio settings; call after any of them changed. */
    public void applyAudioSettings() {
        engine.dsp.agc = settings.b(Settings.AGC);
        engine.dsp.noiseGate = settings.b(Settings.NOISE);
        engine.dsp.targetGain = settings.volumeGain() * focus.gain;
        engine.setDeepBuffer(settings.b(Settings.STUTTER));
    }

    public AudioEngine engine() {
        return engine;
    }

    public boolean mutedByFocus() {
        return wantPlay && focus.gain == 0f;
    }

    @Override
    public void onAudioFocusChange(int change) {
        int action = focus.onChange(change, settings.b(Settings.FINISH_FOCUS), settings.b(Settings.MUTE_FOCUS), settings.duckGain());
        Diag.note("audio focus change " + change + " -> " + (action == FocusPolicy.EXIT ? "exit" : action == FocusPolicy.PAUSE ? "pause" : "gain " + focus.gain));
        applyAudioSettings();
        if (change == AudioManager.AUDIOFOCUS_LOSS) hasFocus = false;
        if (action == FocusPolicy.EXIT) exit();
        else if (action == FocusPolicy.PAUSE) pause();
        else changed();
    }

    private void abandonFocus() {
        if (hasFocus) audio.abandonAudioFocus(this);
        hasFocus = false;
    }

    // ---- USB tuner ---------------------------------------------------------------------------

    /** Manual or automatic search; works even when the automatic search is switched off. */
    public void searchUsb() {
        tuner.search();
    }

    public void setUsbSearchEnabled(boolean on) {
        settings.set(Settings.SKIP_USB, !on);
        if (on) tuner.search();
        else {
            if (source == SRC_DAB) stopDab();
            tuner.disable();
        }
    }

    @Override
    public void onUsbState(int state, String detail) {
        int before = usbState;
        if (state != before) Diag.note("usb " + UsbLink.NAMES[state] + (detail.length() > 0 ? " (" + detail + ")" : ""));
        usbState = state;
        usbDetail = detail;
        if (state == UsbLink.READY && before != UsbLink.READY) {
            if (wantPlay && source == SRC_DAB && !scanning) startPlayback();
        } else if (state != UsbLink.READY && before == UsbLink.READY) {
            // Unplugged or failed: drop everything tied to the device, keep the lists.
            nativeService = null;
            stopDabAudio();
            rfLock = false;
            quality = 0;
            if (scanning) {
                scanning = false;
                scanPaused = scanIndex > 0 || !scanFound.isEmpty();
            }
            if (source == SRC_DAB && wantPlay) {
                engine.end();
                playState = LOADING;
                status = str(R.string.status_waiting_adapter);
            }
        }
        changed();
    }

    @Override
    public void onServiceStarted(RadioServiceDabImpl service) {
        if (service == nativeService && playState == LOADING) {
            status = "";
            changed();
        }
    }

    @Override
    public void onReception(boolean lock, int q) {
        if (lock == rfLock && q == quality) return;
        rfLock = lock;
        quality = q;
        changed();
    }

    /** Signal bars 0..5 for the UI. */
    public int signalBars() {
        if (!rfLock) return 0;
        return Math.max(1, Math.min(5, Math.round(quality * 5 / 6f)));
    }

    // ---- scan --------------------------------------------------------------------------------

    /** Starts a scan, or continues a paused one at the channel it stopped on. */
    public void startScan() {
        if (usbState != UsbLink.READY || scanning) return;
        if (!scanPaused) {
            scanFound.clear();
            scanIndex = 0;
        }
        scanDone = false;
        scanPaused = false;
        scanOutcome = null;
        scanning = true;
        // The tuner can either scan or play, so DAB audio pauses for the duration.
        if (source == SRC_DAB) {
            nativeService = null;
            stopDabAudio();
            engine.end();
            playState = IDLE;
        }
        tuner.startScan(scanIndex);
        changed();
    }

    /** Stops a running scan; found stations are kept for "Continue" but not saved. */
    public void stopScan() {
        if (!scanning) return;
        scanning = false;
        scanPaused = true;
        tuner.stopScan();
        dabHoldUntilMs = SystemClock.elapsedRealtime() + 1200;
        if (wantPlay && source == SRC_DAB) startPlayback();
        changed();
    }

    /** Throws away a paused or finished scan session. */
    public void discardScan() {
        if (scanning) return;
        scanPaused = false;
        scanDone = false;
        scanIndex = 0;
        scanFound.clear();
        scanOutcome = null;
        changed();
    }

    @Override
    public void onScanIndex(int tableIndex) {
        if (!scanning) return;
        scanIndex = tableIndex;
        if (tableIndex >= DabChannels.COUNT) finishScan();
        changed();
    }

    @Override
    public void onServiceFound(RadioServiceDabImpl svc) {
        if (!scanning || !svc.programmeService) return;
        RadioServiceDabComponentImpl audioComponent = svc.primaryAudio();
        if (audioComponent == null) return;
        Station s = new Station();
        s.sid = svc.serviceId;
        s.id = Station.dabId(s.sid);
        s.name = svc.serviceLabel.isEmpty() ? svc.shortLabel : svc.serviceLabel;
        if (s.name.isEmpty()) s.name = Integer.toHexString(s.sid).toUpperCase(java.util.Locale.ROOT);
        s.ecc = svc.ecc;
        s.scids = audioComponent.scIdS;
        s.dabPlus = audioComponent.type == RadioServiceDabImpl.ASCTY_DAB_PLUS;
        s.bitrate = audioComponent.bitrate;
        Station.Loc loc = new Station.Loc();
        loc.eid = svc.ensembleId;
        loc.khz = svc.ensembleFrequency / 1000;
        loc.ensemble = svc.ensembleLabel;
        s.locs.add(loc);
        StationStore.mergeFound(scanFound, s);
        changed();
    }

    /** The scan ran through all channels: only now is the saved list replaced. */
    private void finishScan() {
        scanning = false;
        scanPaused = false;
        scanDone = true;
        scanOutcome = store.applyScan(new ArrayList<Station>(scanFound), settings.i(Settings.SCAN_MODE) == 0);
        if (source == SRC_DAB) {
            Station again = station == null ? null : store.find(station.id);
            station = again != null ? again : store.dab.isEmpty() ? null : store.dab.get(0);
            locIndex = 0;
            if (again == null) resetNowPlaying();
            if (wantPlay) startPlayback();
        }
    }

    // ---- DAB payload (native and decoder threads) --------------------------------------------

    @Override
    public void onDabFormat(final boolean plus, final boolean isStereo) {
        final DabAudio from = dabAudio;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (from != dabAudio) return;
                formatKnown = true;
                dabPlus = plus;
                stereo = isStereo;
                changed();
            }
        });
    }

    @Override
    public void onDabDls(final String text) {
        final DabAudio from = dabAudio;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (from != dabAudio || text.equals(dls)) return;
                dls = text;
                showOverlay();
                changed();
            }
        });
    }

    @Override
    public void onDabSlide(byte[] image) {
        final DabAudio from = dabAudio;
        // Decode here, off the main thread, and no larger than the artwork panel needs.
        final Bitmap b = LogoStore.decodeBounded(image, 480);
        if (b == null) return;
        saveSlide(image);
        main.post(new Runnable() {
            @Override
            public void run() {
                if (from != dabAudio) return;
                slide = b;
                slideStamp = SystemClock.elapsedRealtime();
                changed();
            }
        });
    }

    @Override
    public void onDabDecoderMissing(final boolean plus) {
        main.post(new Runnable() {
            @Override
            public void run() {
                playState = ERROR;
                status = str(plus ? R.string.status_no_decoder_plus : R.string.status_no_decoder_mp2);
                standDown();
                changed();
            }
        });
    }

    // ---- web stream (stream thread) ----------------------------------------------------------

    @Override
    public void onStreamState(final int state, final String detail) {
        final int gen = webGeneration;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen != webGeneration || source != SRC_WEB) return;
                switch (state) {
                    case StreamPlayer.CONNECTING:
                        playState = LOADING;
                        status = str(R.string.status_connecting);
                        break;
                    case StreamPlayer.BUFFERING:
                        playState = LOADING;
                        status = str(R.string.status_buffering);
                        break;
                    case StreamPlayer.RECONNECTING:
                        playState = LOADING;
                        status = str(R.string.status_reconnecting);
                        break;
                    case StreamPlayer.PLAYING:
                        playState = PLAYING;
                        status = "";
                        break;
                    case StreamPlayer.FAILED:
                        playState = ERROR;
                        status = str(R.string.status_stream_failed, detail == null ? "" : detail);
                        engine.end();
                        standDown();
                        break;
                    default:
                        break;
                }
                changed();
            }
        });
    }

    @Override
    public void onStreamTitle(final String title) {
        final int gen = webGeneration;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen != webGeneration || title.equals(dls)) return;
                dls = title;
                showOverlay();
                changed();
            }
        });
    }

    @Override
    public void onStreamFormat(final String codec, final int kbps) {
        final int gen = webGeneration;
        main.post(new Runnable() {
            @Override
            public void run() {
                if (gen != webGeneration) return;
                webFormat = kbps > 0 ? codec + " · " + kbps + " KBPS" : codec;
                changed();
            }
        });
    }

    // ---- notification ------------------------------------------------------------------------

    private void goForeground() {
        try {
            startForeground(NOTIFICATION_ID, buildNotification());
            foreground = true;
        } catch (RuntimeException e) {
            // Android 12+ refuses a foreground start from the background; playback still
            // runs while an activity is visible.
        }
    }

    private void updateNotification() {
        String key = (station == null ? "" : station.name) + "|" + dls + "|" + status + "|" + wantPlay;
        if (mediaSession != null) mediaSession.update(station == null ? str(R.string.app_name) : station.name, dls, wantPlay);
        if (!foreground || key.equals(lastNotification)) return;
        lastNotification = key;
        try {
            ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(NOTIFICATION_ID, buildNotification());
        } catch (RuntimeException ignored) {
        }
    }

    /** The raw slideshow picture for {@link ArtProvider}; written beside and renamed. */
    private void saveSlide(byte[] image) {
        File f = ArtProvider.slideFile(this);
        File tmp = new File(f.getPath() + ".tmp");
        try {
            FileOutputStream out = new FileOutputStream(tmp);
            try {
                out.write(image);
            } finally {
                out.close();
            }
            if (!tmp.renameTo(f)) {
                f.delete();
                tmp.renameTo(f);
            }
        } catch (IOException ignored) {
            // Dashboards then show the logo instead.
        }
    }

    /** See {@link #ACTION_STATE}; sent only when something a dashboard shows has changed. */
    @SuppressWarnings("deprecation") // sticky broadcasts are deprecated from API 21 but are what Android 4.x offers
    private void publishState(boolean alive) {
        ArrayList<Station> list = store.list(source);
        int index = station == null ? -1 : indexOf(list, station.id);
        int preset = station == null ? -1 : store.presetOf(station.id);
        boolean playing = alive && wantPlay;
        String name = station == null ? "" : station.name;
        // Same choice as the player: slideshow picture if enabled and received, else the logo.
        String art = "";
        long artVersion = 0;
        File logo = station == null ? null : app.logos.fileOf(station.id);
        if (slide != null && settings.b(Settings.SLIDESHOW) && ArtProvider.slideFile(this).isFile()) {
            art = ArtProvider.BASE + "slide";
            artVersion = slideStamp;
        } else if (logo != null) {
            art = ArtProvider.BASE + "logo/" + Uri.encode(station.id);
            artVersion = logo.lastModified();
        }
        String key = source + "|" + name + "|" + dls + "|" + status + "|" + playing + "|" + playState
                + "|" + index + "|" + list.size() + "|" + preset + "|" + art + "|" + artVersion;
        if (key.equals(lastState)) return;
        lastState = key;
        Intent i = new Intent(ACTION_STATE)
                .putExtra("source", source == SRC_DAB ? "dab" : "web")
                .putExtra("station", name)
                .putExtra("text", dls)
                .putExtra("status", status)
                .putExtra("playing", playing)
                .putExtra("state", PLAY_NAMES[playState])
                .putExtra("index", index + 1)
                .putExtra("count", list.size())
                .putExtra("preset", preset + 1)
                .putExtra("art", art)
                .putExtra("artVersion", artVersion);
        try {
            sendStickyBroadcast(i);
        } catch (RuntimeException ignored) {
            // Permission missing on a modified build: dashboards simply show no state.
        }
    }

    @SuppressWarnings("deprecation") // Notification.Builder(Context) and addAction(int,...) are the API 16 forms
    private Notification buildNotification() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? channelBuilder() : new Notification.Builder(this);
        int immutable = Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0;
        Intent open = new Intent(this, PlayerActivity.class).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        b.setSmallIcon(R.drawable.ic_stat_radio)
                .setContentTitle(station == null ? str(R.string.app_name) : station.name)
                .setContentText(!status.isEmpty() ? status : dls)
                .setContentIntent(PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | immutable))
                .setOngoing(wantPlay)
                .setWhen(0);
        b.addAction(R.drawable.ic_prev, str(R.string.cd_prev), action(ACTION_PREV, 1, immutable));
        b.addAction(wantPlay ? R.drawable.ic_pause : R.drawable.ic_play, str(R.string.cd_play_pause), action(ACTION_TOGGLE, 2, immutable));
        b.addAction(R.drawable.ic_next, str(R.string.cd_next), action(ACTION_NEXT, 3, immutable));
        return b.build();
    }

    private PendingIntent action(String action, int request, int immutable) {
        return PendingIntent.getService(this, request, new Intent(this, RadioService.class).setAction(action),
                PendingIntent.FLAG_UPDATE_CURRENT | immutable);
    }

    @TargetApi(26)
    private Notification.Builder channelBuilder() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel ch = new NotificationChannel(CHANNEL, str(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
        return new Notification.Builder(this, CHANNEL);
    }

    // ---- DLS overlay while the UI is in the background -----------------------------------------

    private final Runnable overlayTimeout = new Runnable() {
        @Override
        public void run() {
            hideOverlay();
        }
    };

    @SuppressWarnings("deprecation") // TYPE_SYSTEM_ALERT is the overlay type before API 26
    @android.annotation.SuppressLint("SetTextI18n") // station name and broadcast text, nothing to translate
    private void showOverlay() {
        int seconds = settings.overlaySeconds();
        if (seconds == 0 || app.visibleActivities > 0 || !wantPlay || dls.isEmpty() || station == null) return;
        if (Build.VERSION.SDK_INT >= 23 && !android.provider.Settings.canDrawOverlays(this)) return;
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        if (overlay == null) {
            TextView t = new TextView(this);
            t.setTextColor(0xFFF3EFE6);
            t.setBackgroundColor(0xE6121214);
            t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
            t.setSingleLine(true);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            int pad = (int) (12 * getResources().getDisplayMetrics().density);
            t.setPadding(pad * 2, pad, pad * 2, pad);
            try {
                t.setTypeface(Typeface.createFromAsset(getAssets(), "fonts/Barlow-Medium.ttf"));
            } catch (RuntimeException ignored) {
            }
            WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                    Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                    PixelFormat.TRANSLUCENT);
            lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
            try {
                wm.addView(t, lp);
                overlay = t;
            } catch (RuntimeException e) {
                return; // overlay permission missing or revoked
            }
        }
        overlay.setText(station.name + "  ·  " + dls);
        main.removeCallbacks(overlayTimeout);
        if (seconds > 0) main.postDelayed(overlayTimeout, seconds * 1000L);
    }

    private void hideOverlay() {
        main.removeCallbacks(overlayTimeout);
        if (overlay == null) return;
        try {
            ((WindowManager) getSystemService(WINDOW_SERVICE)).removeView(overlay);
        } catch (RuntimeException ignored) {
        }
        overlay = null;
    }
}
