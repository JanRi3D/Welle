package me.ri3d.welle.debug;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Parcelable;
import android.util.Log;

import org.omri.radio.impl.RadioServiceDabImpl;
import org.omri.radio.impl.TunerUsb;
import org.omri.radio.impl.UsbHelper;

import java.lang.reflect.Constructor;
import java.util.ArrayList;

import me.ri3d.welle.App;
import me.ri3d.welle.RadioService;
import me.ri3d.welle.core.Station;
import me.ri3d.welle.core.StationStore;
import me.ri3d.welle.core.UsbLink;

/**
 * DEVELOPMENT DEMO, debug builds only. Puts made-up stations (the ones drawn in the design
 * reference) and a made-up "tuner ready" state on screen so layouts can be reviewed
 * without hardware. Nothing here receives radio: there is no audio, and the text line says
 * "Demo". It is no evidence that DAB reception works.
 *
 * Modes (string extra "mode"):
 *   player  demo stations, presets, and the player showing the first one
 *   scan    the scan screen paused part-way with some stations found
 *   clear   remove the demo stations again
 *   loadlib load libirtdab without a tuner, to check that it links on this device
 *   fakeusb run libirtdab's attach/permission/handshake/detach against a made-up dongle
 *   set     store one integer setting: -e key theme --ei value 1
 *   text    replace the text line of the current station: -e text "..."
 */
public final class DemoReceiver extends BroadcastReceiver {
    private static final String TAG = "WelleDemo";

    /**
     * Hands libirtdab a UsbDevice built by reflection (Android 4.x constructors) and a
     * connection that was never opened, so every transfer fails and the tuner handshake
     * must end in FAILED. Exercises the JNI layer on Dalvik without hardware; it says
     * nothing about reception.
     */
    private static void fakeTuner() {
        final HandlerThread thread = new HandlerThread("dab-usb-fake");
        thread.start();
        final Handler worker = new Handler(thread.getLooper());
        worker.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Constructor<UsbEndpoint> ep = UsbEndpoint.class.getConstructor(int.class, int.class, int.class, int.class);
                    Parcelable[] eps = {ep.newInstance(0x81, 2, 512, 0), ep.newInstance(0x02, 2, 512, 0), ep.newInstance(0x82, 2, 512, 0)};
                    UsbInterface itf = UsbInterface.class
                            .getConstructor(int.class, int.class, int.class, int.class, Parcelable[].class)
                            .newInstance(0, 0xff, 0xff, 0xff, eps);
                    final UsbDevice dev = UsbDevice.class
                            .getConstructor(String.class, int.class, int.class, int.class, int.class, int.class, Parcelable[].class)
                            .newInstance("/dev/bus/usb/001/002", 0x16c0, 0x05dc, 0xff, 0, 0, new Parcelable[]{itf});
                    final UsbDeviceConnection closed = UsbDeviceConnection.class.getConstructor(UsbDevice.class).newInstance(dev);
                    final String name = dev.getDeviceName();

                    UsbHelper helper = UsbHelper.create(new UsbHelper.Host() {
                        @Override
                        public void requestPermission(UsbDevice device) {
                            Log.i(TAG, "fake tuner: native asked for permission");
                            worker.post(new Runnable() {
                                @Override
                                public void run() {
                                    UsbHelper.getInstance().permission(name, true);
                                }
                            });
                        }

                        @Override
                        public UsbDeviceConnection openDevice(UsbDevice device) {
                            return closed;
                        }
                    });
                    helper.attach(new TunerUsb(dev, new TunerUsb.Listener() {
                        @Override
                        public void onTunerEvent(int type) {
                            Log.i(TAG, "fake tuner: event " + type + (type == TunerUsb.FAILED ? " = handshake failed, as it must" : ""));
                            worker.post(new Runnable() {
                                @Override
                                public void run() {
                                    UsbHelper.getInstance().detach(name);
                                    Log.i(TAG, "fake tuner: detached, JNI path survived");
                                    thread.quit();
                                }
                            });
                        }

                        @Override
                        public void onScanIndex(int tableIndex) {
                        }

                        @Override
                        public void onServiceFound(RadioServiceDabImpl service) {
                        }

                        @Override
                        public void onServiceStarted(RadioServiceDabImpl service) {
                        }

                        @Override
                        public void onServiceStopped(RadioServiceDabImpl service) {
                        }

                        @Override
                        public void onReception(boolean rfLock, int quality) {
                        }
                    }));
                } catch (Throwable t) {
                    Log.e(TAG, "fake tuner cannot be built on this Android version: " + t);
                    thread.quit();
                }
            }
        });
    }

    // name, ensemble, kHz, EId, SId. The three Deutschlandradio rows carry the real DAB
    // identifiers so the RadioDNS logo download can be exercised; the rest are invented.
    private static final String[][] STATIONS = {
            {"radioeins", "rbb Berlin", "194064", "10bd", "d901"},
            {"Fritz", "rbb Berlin", "194064", "10bd", "d902"},
            {"rbb 88.8", "rbb Berlin", "194064", "10bd", "d903"},
            {"rbb24 Inforadio", "rbb Berlin", "194064", "10bd", "d904"},
            {"radio3", "rbb Berlin", "194064", "10bd", "d905"},
            {"Antenne Brandenburg", "rbb Berlin", "194064", "10bd", "d906"},
            {"Deutschlandfunk", "DR Deutschland", "178352", "10bc", "d210"},
            {"Dlf Kultur", "DR Deutschland", "178352", "10bc", "d220"},
            {"Dlf Nova", "DR Deutschland", "178352", "10bc", "d230"},
            {"Klassik Radio", "DR Deutschland", "178352", "10bc", "d90a"},
            {"Schwarzwaldradio", "DR Deutschland", "178352", "10bc", "d90b"},
            {"sunshine live", "DR Deutschland", "178352", "10bc", "d90c"},
    };

    private static ArrayList<Station> stations(int count) {
        ArrayList<Station> list = new ArrayList<Station>();
        for (int i = 0; i < count; i++) {
            Station s = new Station();
            s.sid = Integer.parseInt(STATIONS[i][4], 16);
            s.id = Station.dabId(s.sid);
            s.name = STATIONS[i][0];
            s.ecc = 0xE0;
            Station.Loc l = new Station.Loc();
            l.ensemble = STATIONS[i][1];
            l.khz = Integer.parseInt(STATIONS[i][2]);
            l.eid = Integer.parseInt(STATIONS[i][3], 16);
            s.locs.add(l);
            list.add(s);
        }
        return list;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String mode = intent.getStringExtra("mode");
        StationStore store = App.of(context).stations;
        IBinder binder = peekService(context, new Intent(context, RadioService.class));
        RadioService radio = binder instanceof RadioService.LocalBinder ? ((RadioService.LocalBinder) binder).service() : null;

        if ("fakeusb".equals(mode)) {
            fakeTuner();
            return;
        }
        if ("set".equals(mode)) {
            // One integer setting (theme, vis, accent ...), to stage screenshots without tapping.
            App.of(context).settings.set(intent.getStringExtra("key"), intent.getIntExtra("value", 0));
            return;
        }
        if ("text".equals(mode)) {
            // Replaces the text line of whatever is playing, for a staged screenshot.
            if (radio != null) {
                radio.dls = intent.getStringExtra("text");
                int quality = radio.quality;
                radio.quality = -1; // forces onReception() to refresh the UI
                radio.onReception(radio.rfLock, quality);
            }
            return;
        }
        if ("loadlib".equals(mode)) {
            // Loads libirtdab and runs its JNI_OnLoad, which resolves all Java peer classes.
            try {
                org.omri.radio.impl.UsbHelper.create(new org.omri.radio.impl.UsbHelper.Host() {
                    @Override
                    public void requestPermission(android.hardware.usb.UsbDevice device) {
                    }

                    @Override
                    public android.hardware.usb.UsbDeviceConnection openDevice(android.hardware.usb.UsbDevice device) {
                        return null;
                    }
                });
                android.util.Log.i("WelleDemo", "libirtdab loaded and initialised");
            } catch (Throwable t) {
                android.util.Log.e("WelleDemo", "libirtdab failed to load: " + t);
            }
            return;
        }
        if ("clear".equals(mode)) {
            store.applyScan(new ArrayList<Station>(), false);
        } else if ("scan".equals(mode) && radio != null) {
            radio.usbState = UsbLink.READY;
            radio.scanFound.clear();
            radio.scanFound.addAll(stations(12).subList(6, 12));
            radio.scanIndex = 16;
            radio.scanPaused = true;
            radio.scanDone = false;
        } else {
            ArrayList<Station> list = stations(12);
            store.applyScan(list, false);
            for (int i = 0; i < 11; i++) store.presets[i] = list.get(i).id;
            store.save();
            if (radio != null) {
                int index = intent.getIntExtra("station", 0);
                radio.usbState = UsbLink.READY;
                radio.source = RadioService.SRC_DAB;
                radio.station = store.find(list.get(index).id);
                radio.wantPlay = true;
                radio.playState = RadioService.PLAYING;
                radio.status = "";
                // "text" replaces the line for staged screenshots; without it the line says Demo.
                String text = intent.getStringExtra("text");
                radio.dls = text != null ? text : "Demo · no tuner · Der schöne Morgen";
                radio.formatKnown = true;
                radio.dabPlus = true;
                radio.stereo = true;
                radio.slide = null;
            }
        }
        // onReception() is the public entry that also refreshes the UI; -1 forces a change.
        if (radio != null) {
            radio.quality = -1;
            radio.onReception(!"clear".equals(mode), "clear".equals(mode) ? 0 : 5);
        }
    }
}
