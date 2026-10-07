package org.omri.radio.impl;

import android.hardware.usb.UsbDevice;

import org.omri.radioservice.RadioServiceDab;

import java.util.Collections;
import java.util.List;

/**
 * Java peer of the native tuner object. Every method below except the constructor is called
 * from libirtdab threads (jtunerusbdevice.cpp) and looked up by name and signature.
 */
public final class TunerUsb {

    public static final int READY = 0;
    public static final int FAILED = 1;
    public static final int FREQUENCY_LOCKED = 2;
    public static final int FREQUENCY_NOT_LOCKED = 3;
    public static final int SCAN_IN_PROGRESS = 4;

    /** Callbacks arrive on native threads. */
    public interface Listener {
        void onTunerEvent(int type);
        /** Index into the 41-entry Band III table now being scanned; 41 means finished. */
        void onScanIndex(int tableIndex);
        void onServiceFound(RadioServiceDabImpl service);
        void onServiceStarted(RadioServiceDabImpl service);
        void onServiceStopped(RadioServiceDabImpl service);
        /** @param quality 0 (worst) .. 6 (best), derived from the channel error rate. */
        void onReception(boolean rfLock, int quality);
    }

    private final UsbDevice mDevice;
    private final Listener mListener;

    public TunerUsb(UsbDevice device, Listener listener) {
        mDevice = device;
        mListener = listener;
    }

    public UsbDevice getUsbDevice() {
        return mDevice;
    }

    @SuppressWarnings("unused")
    void callBack(int type) {
        mListener.onTunerEvent(type);
    }

    @SuppressWarnings("unused")
    List<RadioServiceDab> getRadioServices() {
        return Collections.emptyList();
    }

    @SuppressWarnings("unused")
    void scanProgressCallback(int tableIndex) {
        mListener.onScanIndex(tableIndex);
    }

    @SuppressWarnings("unused")
    void serviceFound(RadioServiceDab service) {
        mListener.onServiceFound((RadioServiceDabImpl) service);
    }

    @SuppressWarnings("unused")
    void serviceStarted(RadioServiceDab service) {
        mListener.onServiceStarted((RadioServiceDabImpl) service);
    }

    @SuppressWarnings("unused")
    void serviceStopped(RadioServiceDab service) {
        mListener.onServiceStopped((RadioServiceDabImpl) service);
    }

    @SuppressWarnings("unused")
    void receptionStatistics(boolean rfLock, int quality) {
        mListener.onReception(rfLock, quality);
    }
}
