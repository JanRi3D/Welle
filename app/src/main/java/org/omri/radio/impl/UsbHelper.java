package org.omri.radio.impl;

import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;

/**
 * JNI entry points of libirtdab. The class name, the native method names and the two
 * callbacks ({@link #requestPermission}, {@link #openDevice}) are looked up by name from
 * native code (platformspecific/android/native-lib.cpp, jusbdevice.cpp): do not rename.
 *
 * Threading contract: the native side caches the JNIEnv of the thread that calls
 * {@link #attach}, so attach, permission and detach must all come from one thread.
 */
public final class UsbHelper {

    /** Supplied by the app: how to ask for USB permission and hand over an opened device. */
    public interface Host {
        void requestPermission(UsbDevice device);
        UsbDeviceConnection openDevice(UsbDevice device);
    }

    private static UsbHelper sInstance;
    private static boolean sLoaded;

    private final Host mHost;

    private UsbHelper(Host host) {
        mHost = host;
    }

    /** @throws UnsatisfiedLinkError if libirtdab is missing for this ABI. */
    public static synchronized UsbHelper create(Host host) {
        if (!sLoaded) {
            System.loadLibrary("irtdab");
            sLoaded = true;
        }
        sInstance = new UsbHelper(host);
        sInstance.created();
        return sInstance;
    }

    /** Called from native code. */
    public static UsbHelper getInstance() {
        return sInstance;
    }

    /** Called from native code. */
    @SuppressWarnings("unused")
    private void requestPermission(UsbDevice device) {
        mHost.requestPermission(device);
    }

    /** Called from native code; must not return null once permission was reported as granted. */
    @SuppressWarnings("unused")
    private UsbDeviceConnection openDevice(UsbDevice device) {
        return mHost.openDevice(device);
    }

    public void attach(TunerUsb tuner) { deviceAttached(tuner); }
    public void detach(String deviceName) { deviceDetached(deviceName); }
    public void permission(String deviceName, boolean granted) { devicePermission(deviceName, granted); }
    public void startService(String deviceName, RadioServiceDabImpl service) { startSrv(deviceName, service); }
    public void stopService(String deviceName) { stopSrv(deviceName); }
    /** @param startIndex index into the 41-entry Band III table (see DabChannels). */
    public void startScan(String deviceName, int startIndex) { startServiceScan(deviceName, startIndex); }
    public void stopScan(String deviceName) { stopServiceScan(deviceName); }

    private native void created();
    private native void deviceDetached(String deviceName);
    private native void deviceAttached(TunerUsb usbDevice);
    private native void devicePermission(String deviceName, boolean granted);
    private native void startSrv(String deviceName, org.omri.radioservice.RadioServiceDab service);
    private native void stopSrv(String deviceName);
    private native void startServiceScan(String deviceName, int startIndex);
    private native void stopServiceScan(String deviceName);
}
