package me.ri3d.welle.core;

/**
 * What the radio does when Android moves audio focus. Kept free of Android classes so the
 * rules can be unit-tested; the constants equal those of AudioManager.
 *
 * Rules:
 *  - Duckable loss (navigation prompt): play at the "volume during navigation prompts"
 *    level, or silent if "mute on audio focus loss" is on.
 *  - Transient loss (phone call): silent.
 *  - In both cases the level only comes back on GAIN, never in between.
 *  - Permanent loss: exit if "exit on audio focus loss" is on; otherwise keep running
 *    silently if "mute on audio focus loss" is on; otherwise pause.
 */
public final class FocusPolicy {
    public static final int GAIN = 1;
    public static final int LOSS = -1;
    public static final int LOSS_TRANSIENT = -2;
    public static final int LOSS_CAN_DUCK = -3;

    public static final int NONE = 0;
    public static final int EXIT = 1;
    public static final int PAUSE = 2;

    /** Factor applied on top of the app volume: 1, the duck level, or 0. */
    public float gain = 1f;
    /** Silenced by a permanent loss; stays so until the user presses play again. */
    public boolean mutedByLoss;

    /** @return NONE, EXIT or PAUSE */
    public int onChange(int change, boolean exitOnLoss, boolean muteOnLoss, float duckGain) {
        switch (change) {
            case GAIN:
                gain = 1f;
                mutedByLoss = false;
                return NONE;
            case LOSS_CAN_DUCK:
                // A prompt arriving during a call must not raise the level from silent.
                if (gain > 0f) gain = muteOnLoss ? 0f : duckGain;
                return NONE;
            case LOSS_TRANSIENT:
                gain = 0f;
                return NONE;
            case LOSS:
                gain = 0f;
                if (exitOnLoss) return EXIT;
                if (muteOnLoss) {
                    mutedByLoss = true;
                    return NONE;
                }
                return PAUSE;
            default:
                return NONE;
        }
    }

    /** Call after focus was requested and granted again. */
    public void reset() {
        gain = 1f;
        mutedByLoss = false;
    }
}
