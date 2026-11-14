package de.aatokombi;

/**
 * Steering-wheel keys as the head unit's key panel sees them (DSI KeyPanel updateKey2 /
 * updateEncoder2, called from the AslTargetSystemKeyPanelHandling shadow on the DSI thread).
 *
 * Key codes on the MQB multifunction wheel (keyboard 4 = MFW), from zakklmv/MHI2-Carplay-Maps
 * (src/vcdiag VcLayout.java, measured on a MQB MU1440 + AID 10.5; GPL-3.0, only the facts used):
 *   key 40  roller press = "OK"; states 1 pressed, 0 released, 3 / 4 / 5 held (~0.8 / ~2 / ~3 s)
 *   key 35  View on MHI2 (a short press steps the AID view, a long one opens its view menu);
 *           on MIB2 STD2 only with the patched J5 key panel, as KEYPANEL_VIEW_CODE
 * Not yet seen on this MIB2 STD2 unit: every keyboard-4 event is logged at DEBUG.
 *
 * OK held (MODE_HOLD_STATE reached once per press) asks for the next cluster mode;
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
    /** KEY_MFW_CANCEL: the MQB wheel's BACK / widget-switch key (car 2026-11-07: states 1/0,
     *  held 3/4/5 like the roller). */
    public static final int KEY_BACK = 41;
    public static final int KEY_VIEW = 35;

    /**
     * Key-panel hold state that counts as "held": 3 = first level (~0.8 s), 4 (~2 s), 5 (~3 s).
     * Careful: in some AID views a held OK has its own meaning (e.g. resetting trip data).
     */
    private static final int MODE_HOLD_STATE = 4;
    /**
     * DSI key code (keyboard 4) of the steering-wheel View key with the patched J5 key panel
     * (native/keypanel: the stock key table drops MQB 0x23; the patch maps it to this code, default
     * 59). The key is then logged as "wheel View", counted in /dev/shmem/aa_view_key and kept from
     * the stock key handling (its stock meaning would be MQB 0x22, absent on the MQB wheel).
     * -1: no patched key panel, code not treated specially. The patch is on hold (docs/
     * CLUSTER_VIEW_AND_KEYS.md); must match keypanel_patch.py --view-dsi.
     */
    private static final int KEYPANEL_VIEW_CODE = 59;

    private static boolean okFired;
    private static volatile boolean cycleRequested;

    private WheelKeys() {}

    /** Keyboards logged: all but the touch ones (9 touchpad, 13..16 touchscreens). */
    private static boolean logged(int keyboard) {
        return keyboard != 9 && (keyboard < 13 || keyboard > 16);
    }

    private static int viewPresses;
    private static boolean viewHeld;

    /**
     * View from the patched J5 key panel (KEYPANEL_VIEW_CODE). A short press steps the AID
     * view; a long one opens the AID view menu and does not step it (zakklmv, MHI2). Counted on
     * release when no hold state (3/4/5) came; the count goes to /dev/shmem/aa_view_key
     * "presses state". Returns true: the stock key handling must not see this key.
     */
    private static boolean viewKey(int keyboard, int code, int state) {
        if (KEYPANEL_VIEW_CODE < 0 || code != KEYPANEL_VIEW_CODE
                || (keyboard != KEYBOARD_MFW && keyboard != KEYBOARD_MFW_3GP)) return false;
        if (state == 1) {
            viewHeld = false;
        } else if (state >= 3) {
            viewHeld = true;
        } else if (state == 0) {
            if (!viewHeld) viewPresses++;
            ClusterVideo.log("wheel View " + (viewHeld ? "long press (view menu)" : "press #" + viewPresses));
            writeView(viewPresses + " " + (viewHeld ? 1 : 0) + "\n");
        }
        return true;
    }

    private static void writeView(String s) {
        java.io.FileOutputStream o = null;
        try {
            o = new java.io.FileOutputStream("/dev/shmem/aa_view_key");
            o.write(s.getBytes());
        } catch (Throwable t) {
        } finally {
            try { if (o != null) o.close(); } catch (Throwable t) {}
        }
    }

    /** Returns true when the key is ours alone (the stock handling must skip it). */
    public static boolean key(int keyboard, int code, int state, int p4, int p5) {
        // p5 = -1: the old updateKey (no second parameter). Every keyboard is logged: car
        // 2026-11-01 showed no keyboard-4 key at all, while the roller turns came as encoder 40 on
        // keyboard 4 (the "3GP" wheel would be keyboard 8).
        if (logged(keyboard)) {
            ClusterVideo.log("wheel key kbd " + keyboard + " code " + code + " state " + state + " (" + p4 + "," + p5 + ")");
        }
        int modeKey = Config.ClusterMap.MODE_KEY == Config.ClusterMap.MODE_KEY_BACK ? KEY_BACK
                : Config.ClusterMap.MODE_KEY == Config.ClusterMap.MODE_KEY_ROLLER ? KEY_OK : -1;
        if (viewKey(keyboard, code, state)) return true;
        if ((keyboard != KEYBOARD_MFW && keyboard != KEYBOARD_MFW_3GP) || modeKey < 0 || code != modeKey) return false;
        if (state == 1 || state == 0) {
            okFired = false;
        } else if (state >= MODE_HOLD_STATE && !okFired) {
            okFired = true;
            if (Config.ClusterMap.MODE_CYCLE) {
                cycleRequested = true;
                ClusterVideo.log("wheel OK held: next cluster mode requested");
            }
        }
        return false;
    }

    public static void encoder(int keyboard, int encoder, int steps, int p4, int p5) {
        if (!logged(keyboard)) return;
        ClusterVideo.log("wheel encoder kbd " + keyboard + " enc " + encoder + " steps " + steps + " (" + p4 + "," + p5 + ")");
    }

    /**
     * Car 2026-11-03: on the system key-panel instance only the rollers' turns (encoders 40, 44)
     * and one roller's press (key 44) arrive; OK (right roller press) and View never do. On MHI2
     * the DSI key panel has several instances and the MHI2 DSI traces (joeyQuery/MHI2-altScreen
     * docs/cluster-controls.md) show View (35) and the roller press (40) there; luka-dev's Audi
     * MHI2Q code registers its own DSIKeyPanelListener next to the stock one. So: one extra
     * listener on each other instance 0..OTHER_INSTANCES, logging "wheel key/encoder iN ..." only
     * (no actions, a duplicate of a system-instance key must not cycle twice). Never throws.
     */
    private static final int OTHER_INSTANCES = 7;

    public static void attachOtherInstances(de.vw.mib.asl.framework.api.dsiproxy.DSIProxy proxy, int systemInstance) {
        ClusterVideo.log("key panel: system instance " + systemInstance);
        for (int i = 0; i <= OTHER_INSTANCES; i++) {
            if (i == systemInstance) continue;
            try {
                org.dsi.ifc.keypanel.DSIKeyPanel panel = (org.dsi.ifc.keypanel.DSIKeyPanel)
                        proxy.getService(null, org.dsi.ifc.keypanel.DSIKeyPanel.class, i);
                InstanceListener l = new InstanceListener(i);
                proxy.addResponseListener(null, org.dsi.ifc.keypanel.DSIKeyPanelListener.class, i, l);
                if (panel != null) {
                    panel.setNotification(new int[]{25, 23}, l);
                }
                ClusterVideo.log("key panel instance " + i + ": listener attached" + (panel == null ? " (no service)" : ""));
            } catch (Throwable t) {
                ClusterVideo.log("key panel instance " + i + ": " + t);
            }
        }
    }

    private static final class InstanceListener extends de.vw.mib.asl.internal.system.abtfeatures.DsiKeyPanelListenerAdapter {
        private final int instance;
        InstanceListener(int instance) { this.instance = instance; }
        public void updateKey2(int keyboard, int code, int state, int p4, int p5) {
            ClusterVideo.log("wheel key i" + instance + " kbd " + keyboard + " code " + code + " state " + state + " (" + p4 + "," + p5 + ")");
        }
        public void updateEncoder2(int keyboard, int encoder, int steps, int p4, int p5) {
            ClusterVideo.log("wheel encoder i" + instance + " kbd " + keyboard + " enc " + encoder + " steps " + steps + " (" + p4 + "," + p5 + ")");
        }
    }

    /** Taken once by ClusterVideo.poll. */
    static boolean takeCycleRequest() {
        if (!cycleRequested) return false;
        cycleRequested = false;
        return true;
    }
}
