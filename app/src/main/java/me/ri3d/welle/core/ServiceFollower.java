package me.ri3d.welle.core;

/**
 * Decides when to retune a DAB service to another ensemble that carries it.
 *
 * An alternative is only ever a location the scan actually received with the same SId.
 * Reception has to be gone for LOST_MS before a switch (hysteresis), and switches are at
 * least COOLDOWN_MS apart, so a fringe area does not cause constant retuning.
 */
public final class ServiceFollower {
    public static final long LOST_MS = 6000;
    public static final long COOLDOWN_MS = 20000;

    private long lostSince = -1;
    private long lastSwitch = Long.MIN_VALUE / 2;

    /** Forget the reception history, e.g. after a manual tune. */
    public void reset() {
        lostSince = -1;
    }

    /** @return index of the location to switch to, or -1 to stay */
    public int tick(long nowMs, boolean enabled, boolean receiving, int locationCount, int currentLocation) {
        if (receiving) {
            lostSince = -1;
            return -1;
        }
        if (lostSince < 0) lostSince = nowMs;
        if (!enabled || locationCount < 2) return -1;
        if (nowMs - lostSince < LOST_MS || nowMs - lastSwitch < COOLDOWN_MS) return -1;
        lastSwitch = nowMs;
        lostSince = nowMs;
        return (currentLocation + 1) % locationCount;
    }
}
