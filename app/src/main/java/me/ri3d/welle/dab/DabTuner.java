package me.ri3d.welle.dab;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

import org.omri.radio.impl.RadioServiceDabImpl;
import org.omri.radio.impl.TunerUsb;
import org.omri.radio.impl.UsbHelper;

import java.util.Locale;

import me.ri3d.welle.core.UsbLink;

/**
 * USB transport and tuner control: finds the dongle through Android's USB host API, checks
 * its descriptors, asks for permission, hands the opened device to libirtdab and forwards
 * the library's callbacks to the main thread.
 *
 * Every call into the native library goes through one worker thread, because the library
 * keeps the JNIEnv of the thread that attached the device.
 */
public final class DabTuner implements UsbHelper.Host, TunerUsb.Listener {
    private static final String TAG = "WelleUsb";

    /** The only USB id libirtdab's Raon driver is written for. */
    public static final int VENDOR_ID = 0x16C0;
    public static final int PRODUCT_ID = 0x05DC;
    /** Bulk endpoints the driver uses (raontunerinput.h). */
    private static final int ENDPOINT_OUT = 0x02;
    private static final int ENDPOINT_IN = 0x82;

    private static final String ACTION_PERMISSION = "me.ri3d.welle.USB_PERMISSION";

    /** Callbacks on the main thread. */
    public interface Listener {
        void onUsbState(int state, String detail);
        void onScanIndex(int tableIndex);
        void onServiceFound(RadioServiceDabImpl service);
        void onServiceStarted(RadioServiceDabImpl service);
        void onReception(boolean rfLock, int quality);
    }

    private final Context ctx;
    private final UsbManager usb;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final HandlerThread thread = new HandlerThread("dab-usb");
    private final Handler worker;

    private UsbHelper helper;
    private UsbDevice device;
    private String deviceName;
    private volatile UsbDeviceConnection connection;
    private int state = UsbLink.NO_DEVICE;
    private String detail = "";
    private boolean registered;
    private boolean released;

    public DabTuner(Context context, Listener listener) {
        ctx = context.getApplicationContext();
        usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        this.listener = listener;
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    public int state() {
        return state;
    }

    public String detail() {
        return detail;
    }

    /** Starts listening for plug, unplug and permission results. */
    @SuppressWarnings("UnspecifiedRegisterReceiverFlag") // the flag exists from API 33 and is passed there
    public void start() {
        if (registered) return;
        IntentFilter f = new IntentFilter();
        f.addAction(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            ctx.registerReceiver(receiver, f);
        }
        registered = true;
    }

    /** Looks for the dongle and, if it is there and looks right, starts the permission flow. */
    public void search() {
        if (released) return;
        if (state == UsbLink.PERMISSION && device != null) {
            requestPermission(device); // the dialog was dismissed without an answer: ask again
            return;
        }
        if (UsbLink.attached(state)) return;
        UsbDevice found = null;
        if (usb != null) {
            try {
                for (UsbDevice d : usb.getDeviceList().values()) {
                    if (d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) found = d;
                }
            } catch (Exception e) {
                Log.w(TAG, "USB device list unavailable: " + e);
            }
        }
        if (found == null) {
            apply(UsbLink.EV_SEARCH_NONE, "");
            return;
        }
        Log.i(TAG, describe(found));
        // The id is a shared hobbyist VID/PID, so the id alone proves nothing: require the
        // interface layout the driver expects before sending it any command.
        String mismatch = mismatch(found);
        if (mismatch != null) {
            apply(UsbLink.EV_BAD_DESCRIPTORS, mismatch);
            return;
        }
        try {
            if (helper == null) helper = UsbHelper.create(this);
        } catch (UnsatisfiedLinkError e) {
            apply(UsbLink.EV_BAD_DESCRIPTORS, "libirtdab is not available for this CPU");
            return;
        }
        device = found;
        deviceName = found.getDeviceName();
        apply(UsbLink.EV_SEARCH_FOUND, "");
        final TunerUsb peer = new TunerUsb(found, this);
        worker.post(new Runnable() {
            @Override
            public void run() {
                helper.attach(peer); // calls back requestPermission() below
            }
        });
    }

    /** Stops using the dongle ("do not search for a USB adapter"). */
    public void disable() {
        dropDevice();
        apply(UsbLink.EV_DISABLED, "");
    }

    public void release() {
        released = true;
        if (registered) {
            try {
                ctx.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
            registered = false;
        }
        dropDevice();
        worker.post(new Runnable() {
            @Override
            public void run() {
                thread.quit();
            }
        });
    }

    public void play(final RadioServiceDabImpl service) {
        final String name = deviceName;
        if (state != UsbLink.READY || name == null) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                helper.startService(name, service);
            }
        });
    }

    public void stop() {
        final String name = deviceName;
        if (state != UsbLink.READY || name == null) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                helper.stopService(name);
            }
        });
    }

    /** @param startIndex index into DabChannels.KHZ */
    public void startScan(final int startIndex) {
        final String name = deviceName;
        if (state != UsbLink.READY || name == null) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                helper.startScan(name, startIndex);
            }
        });
    }

    public void stopScan() {
        final String name = deviceName;
        if (state != UsbLink.READY || name == null) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                helper.stopScan(name);
            }
        });
    }

    // ---- UsbHelper.Host (native worker thread) ---------------------------------------------

    @Override
    public void requestPermission(final UsbDevice d) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (released || usb == null) return;
                int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
                Intent i = new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName());
                try {
                    // Already-granted devices answer at once without a dialog.
                    usb.requestPermission(d, PendingIntent.getBroadcast(ctx, 0, i, flags));
                } catch (Exception e) {
                    onPermission(false);
                }
            }
        });
    }

    @Override
    public UsbDeviceConnection openDevice(UsbDevice d) {
        return connection;
    }

    // ---- TunerUsb.Listener (native threads) ------------------------------------------------

    @Override
    public void onTunerEvent(final int type) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (type == TunerUsb.READY) {
                    apply(UsbLink.EV_TUNER_READY, "");
                } else if (type == TunerUsb.FAILED && state == UsbLink.STARTING) {
                    dropDevice();
                    apply(UsbLink.EV_TUNER_FAILED, "the device did not answer the tuner handshake");
                }
            }
        });
    }

    @Override
    public void onScanIndex(final int tableIndex) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state == UsbLink.READY) listener.onScanIndex(tableIndex);
            }
        });
    }

    @Override
    public void onServiceFound(final RadioServiceDabImpl service) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state == UsbLink.READY) listener.onServiceFound(service);
            }
        });
    }

    @Override
    public void onServiceStarted(final RadioServiceDabImpl service) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state == UsbLink.READY) listener.onServiceStarted(service);
            }
        });
    }

    @Override
    public void onServiceStopped(RadioServiceDabImpl service) {
    }

    @Override
    public void onReception(final boolean rfLock, final int quality) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (state == UsbLink.READY) listener.onReception(rfLock, quality);
            }
        });
    }

    // ---- internals (main thread) -----------------------------------------------------------

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            String action = intent.getAction();
            if (ACTION_PERMISSION.equals(action)) {
                onPermission(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false));
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(action)) {
                UsbDevice d = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
                if (d != null && deviceName != null && deviceName.equals(d.getDeviceName())) {
                    dropDevice();
                    apply(UsbLink.EV_DETACHED, "");
                } else if (d != null && d.getVendorId() == VENDOR_ID && d.getProductId() == PRODUCT_ID) {
                    apply(UsbLink.EV_DETACHED, "");
                }
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(action)) {
                if (state != UsbLink.OFF) search();
            }
        }
    };

    private void onPermission(boolean granted) {
        if (state != UsbLink.PERMISSION || device == null) return;
        final String name = deviceName;
        if (granted) {
            UsbDeviceConnection c = null;
            try {
                c = usb.openDevice(device);
            } catch (Exception e) {
                Log.w(TAG, "openDevice failed: " + e);
            }
            if (c != null) {
                connection = c;
                apply(UsbLink.EV_GRANTED, "");
                worker.post(new Runnable() {
                    @Override
                    public void run() {
                        helper.permission(name, true); // native claims the interface and powers the tuner up
                    }
                });
                return;
            }
        }
        dropDevice();
        apply(granted ? UsbLink.EV_OPEN_FAILED : UsbLink.EV_DENIED, granted ? "the device could not be opened" : "");
    }

    /** Destroys the native tuner (joining its threads) and closes the connection. */
    private void dropDevice() {
        final String name = deviceName;
        final UsbDeviceConnection c = connection;
        deviceName = null;
        device = null;
        if (name == null || helper == null) return;
        worker.post(new Runnable() {
            @Override
            public void run() {
                try {
                    helper.detach(name);
                } finally {
                    if (c != null) {
                        c.close();
                        if (connection == c) connection = null;
                    }
                }
            }
        });
    }

    private void apply(int event, String why) {
        int next = UsbLink.next(state, event);
        if (next == state && why.equals(detail)) return;
        state = next;
        detail = why;
        listener.onUsbState(state, detail);
    }

    /** Null if interface 0 has the bulk endpoints the driver needs, else what is wrong. */
    static String mismatch(UsbDevice d) {
        if (d.getInterfaceCount() < 1) return "the device has no interface";
        UsbInterface itf = d.getInterface(0);
        boolean in = false, out = false;
        for (int i = 0; i < itf.getEndpointCount(); i++) {
            UsbEndpoint e = itf.getEndpoint(i);
            if (e.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
            if (e.getAddress() == ENDPOINT_OUT) out = true;
            if (e.getAddress() == ENDPOINT_IN) in = true;
        }
        return in && out ? null : "bulk endpoints 0x02/0x82 not found";
    }

    /** One-line descriptor summary for logcat and the About screen. */
    public static String describe(UsbDevice d) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "%04x:%04x class %02x", d.getVendorId(), d.getProductId(), d.getDeviceClass()));
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface itf = d.getInterface(i);
            sb.append(String.format(Locale.US, " | if%d %02x/%02x/%02x", i, itf.getInterfaceClass(), itf.getInterfaceSubclass(), itf.getInterfaceProtocol()));
            for (int e = 0; e < itf.getEndpointCount(); e++) {
                UsbEndpoint ep = itf.getEndpoint(e);
                sb.append(String.format(Locale.US, " ep%02x t%d", ep.getAddress(), ep.getType()));
            }
        }
        return sb.toString();
    }
}
