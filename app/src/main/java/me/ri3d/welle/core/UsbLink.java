package me.ri3d.welle.core;

/** State machine of the USB tuner connection; pure so the transitions can be unit-tested. */
public final class UsbLink {
    private UsbLink() { }

    /** Search switched off ("do not search for a USB adapter"). */
    /** For logs, indexed by state. */
    public static final String[] NAMES = {"off", "no device", "permission", "denied", "unsupported", "starting", "ready", "disconnected"};

    public static final int OFF = 0;
    public static final int NO_DEVICE = 1;
    /** Device found, waiting for the answer to the USB permission dialog. */
    public static final int PERMISSION = 2;
    public static final int DENIED = 3;
    /** Wrong descriptors, could not be opened, or did not answer the tuner handshake. */
    public static final int UNSUPPORTED = 4;
    public static final int STARTING = 5;
    public static final int READY = 6;
    /** Was in use and got unplugged. */
    public static final int DISCONNECTED = 7;

    public static final int EV_DISABLED = 0;
    public static final int EV_SEARCH_NONE = 1;
    public static final int EV_SEARCH_FOUND = 2;
    public static final int EV_BAD_DESCRIPTORS = 3;
    public static final int EV_GRANTED = 4;
    public static final int EV_DENIED = 5;
    public static final int EV_OPEN_FAILED = 6;
    public static final int EV_TUNER_READY = 7;
    public static final int EV_TUNER_FAILED = 8;
    public static final int EV_DETACHED = 9;

    /** True while a native tuner object exists for the device. */
    public static boolean attached(int state) {
        return state == PERMISSION || state == STARTING || state == READY;
    }

    public static int next(int state, int event) {
        switch (event) {
            case EV_DISABLED:
                return OFF;
            case EV_SEARCH_NONE:
                return attached(state) ? state : NO_DEVICE;
            case EV_SEARCH_FOUND:
                return attached(state) ? state : PERMISSION;
            case EV_BAD_DESCRIPTORS:
                return attached(state) ? state : UNSUPPORTED;
            case EV_GRANTED:
                return state == PERMISSION ? STARTING : state;
            case EV_DENIED:
                return state == PERMISSION ? DENIED : state;
            case EV_OPEN_FAILED:
                return state == PERMISSION ? UNSUPPORTED : state;
            case EV_TUNER_READY:
                // Also sent when a scan ends; late callbacks after an unplug are ignored.
                return state == STARTING || state == READY ? READY : state;
            case EV_TUNER_FAILED:
                return state == STARTING ? UNSUPPORTED : state;
            case EV_DETACHED:
                if (attached(state)) return DISCONNECTED;
                return state == OFF ? OFF : NO_DEVICE;
            default:
                return state;
        }
    }
}
