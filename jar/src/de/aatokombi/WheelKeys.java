package de.aatokombi;

/**
 * Steering-wheel keys as the head unit's key panel sees them (DSI KeyPanel updateKey2 /
 * updateEncoder2, called from the AslTargetSystemKeyPanelHandling shadow on the DSI thread).
 *
 * Key codes on the MQB multifunction wheel (keyboard 4 = MFW), from zakklmv/MHI2-Carplay-Maps
 * (src/vcdiag VcLayout.java, measured on a MQB MU1440 + AID 10.5; GPL-3.0, only the facts used):
 *   key 40  roller press = "OK"; states 1 pressed, 0 released, 3 / 4 / 5 held (~0.8 / ~2 / ~3 s)
 *   key 35  View (a short press steps the AID view, a long one opens its view menu)
 * Not yet seen on this MIB2 STD2 unit: every keyboard-4 event is logged at DEBUG.
 *
 * OK held (Config.CLUSTER_OK_HOLD_STATE reached once per press) asks for the next cluster mode;
 * the switch itself runs on the AANavReader timer (ClusterVideo.poll), not on the DSI thread.
 * Java 1.3 source; never throws.
 */
public final class WheelKeys {
    public static final int KEYBOARD_MFW = 4;
    public static final int KEYBOARD_MFW_3GP = 8;
    /**
     * OK on THIS car (the development car, MIB2 STD2): the press of the RIGHT roller arrives as key 44
     * (car 2026-11-02, the driver's confirmation). The MQB MHI2 of zakklmv sends its roller press
     * as key 40; here no key 40 arrived (40 is only the left roller's turn, encoder 40).
     */
    public static final int KEY_OK = 44;
    public static final int KEY_VIEW = 35;

    private static boolean okFired;
    private static volatile boolean cycleRequested;

    private WheelKeys() {}

    /** Keyboards logged: all but the touch ones (9 touchpad, 13..16 touchscreens). */
    private static boolean logged(int keyboard) {
        return keyboard != 9 && (keyboard < 13 || keyboard > 16);
    }

    public static void key(int keyboard, int code, int state, int p4, int p5) {
        // p5 = -1: the old updateKey (no second parameter). Every keyboard is logged: car
        // 2026-11-01 showed no keyboard-4 key at all, while the roller turns came as encoder 40 on
        // keyboard 4 (the "3GP" wheel would be keyboard 8).
        if (logged(keyboard)) {
            ClusterVideo.log("wheel key kbd " + keyboard + " code " + code + " state " + state + " (" + p4 + "," + p5 + ")");
        }
        if ((keyboard != KEYBOARD_MFW && keyboard != KEYBOARD_MFW_3GP) || code != KEY_OK) return;
        if (state == 1 || state == 0) {
            okFired = false;
        } else if (state >= Config.CLUSTER_OK_HOLD_STATE && !okFired) {
            okFired = true;
            if (Config.CLUSTER_OK_CYCLE) {
                cycleRequested = true;
                ClusterVideo.log("wheel OK held: next cluster mode requested");
            }
        }
    }

    public static void encoder(int keyboard, int encoder, int steps, int p4, int p5) {
        if (!logged(keyboard)) return;
        ClusterVideo.log("wheel encoder kbd " + keyboard + " enc " + encoder + " steps " + steps + " (" + p4 + "," + p5 + ")");
    }

    /** Taken once by ClusterVideo.poll. */
    static boolean takeCycleRequest() {
        if (!cycleRequested) return false;
        cycleRequested = false;
        return true;
    }
}
