package de.aatokombi;

/**
 * Steering-wheel keys as the head unit's key panel sees them (DSI KeyPanel updateKey2 /
 * updateEncoder2, called from the AslTargetSystemKeyPanelHandling shadow on the DSI thread).
 *
 * Key codes on the MQB multifunction wheel (keyboard 4 = MFW), from zakklmv/MHI2-Carplay-Maps
 * (src/vcdiag VcLayout.java, measured on an MHI2 MU1440 + AID 10.5; GPL-3.0, only the facts used):
 *   key 40  roller press = "OK"; states 1 pressed, 0 released, 3 / 4 / 5 held (~0.8 / ~2 / ~3 s)
 *   key 35  View on MHI2 (a short press steps the AID view, a long one opens its view menu);
 *           on MIB2 STD2 only with the patched J5 key panel, as KEYPANEL_VIEW_CODE
 * Not yet seen on this MIB2 STD2 unit: every keyboard-4 event is logged at DEBUG.
 *
 * The mode key held (MODE_HOLD_STATE reached once per press) asks for the next cluster mode;
 * the switch itself runs on the AANavReader timer (ClusterVideo.poll), not on the DSI thread.
 * Java 1.3 source; never throws.
 */
public final class WheelKeys {
    public static final int KEYBOARD_MFW = 4;
    public static final int KEYBOARD_MFW_3GP = 8;
    /**
     * The press of the volume roller (Mute in the stock HMI) on the development car (MIB2 STD2)
     * arrives as key 44 (car 2026-11-02, the driver's confirmation). It is not an "OK" key: the
     * MHI2 of zakklmv sends its OK (roller press) as key 40; here no key 40 arrived (40 is
     * only the other roller's turn, encoder 40).
     */
    public static final int KEY_VOLUME_ROLLER = 44;
    /** KEY_MFW_CANCEL: the development car's wheel: BACK / widget-switch key (car 2026-11-07: states 1/0,
     *  held 3/4/5 like the roller). */
    public static final int KEY_BACK = 41;
    public static final int KEY_VIEW = 35;

    /**
     * Key-panel hold state that counts as "held": 3 = first level (~0.8 s), 4 (~2 s), 5 (~3 s).
     * Careful: some AID views give a held wheel key a meaning of their own.
     */
    private static final int MODE_HOLD_STATE = 4;
    /**
     * DSI key code (keyboard 4) of the steering-wheel View key with the patched J5 key panel
     * (native/keypanel: the stock key table drops MQB 0x23; the patch maps it to this code, default
     * 59). The key is then logged as "wheel View", counted in /dev/shmem/aa_view_key and kept from
     * the stock key handling. Must match keypanel_patch.py --view-dsi.
     *
     * Only on a unit where that patch is installed (viewCode()): without it code 59 is the stock
     * key MQB 0x22 - absent on the development car's wheel, but a real key on other wheels, and swallowing it
     * there would take that key away from the driver.
     */
    private static final int KEYPANEL_VIEW_CODE = 59;
    /** Installed by native/keypanel/install_keypanel_patch.sh, removed by its uninstall script. */
    private static final String KEYPANEL_PATCH_FILE = "/tsd/lib/aatokombi/j5/tsd.mibstd2.keypanel";
    /** The patch's own off switch (startup_main then starts the stock key panel). */
    private static final String KEYPANEL_PATCH_OFF_FILE = "/tsd/var/aatokombi/no-keypanel-patch";

    static int viewCode = -2;   // -2 not looked up yet, -1 no patched key panel (package: host tests)

    /** The decision itself: the View code only with the patched key panel installed and not off. */
    static int viewCodeFor(boolean patchInstalled, boolean patchOff) {
        return (patchInstalled && !patchOff) ? KEYPANEL_VIEW_CODE : -1;
    }

    /** KEYPANEL_VIEW_CODE when the patched key panel is installed and on, else -1. Looked up once. */
    private static int viewCode() {
        if (viewCode == -2) {
            int c = -1;
            try {
                c = viewCodeFor(new java.io.File(KEYPANEL_PATCH_FILE).exists(),
                        new java.io.File(KEYPANEL_PATCH_OFF_FILE).exists());
            } catch (Throwable t) {
                // not readable: no patch
            }
            viewCode = c;
            ClusterVideo.log("wheel View key: " + (c < 0 ? "no patched key panel, code "
                    + KEYPANEL_VIEW_CODE + " stays a stock key" : "patched key panel, code " + c));
        }
        return viewCode;
    }

    private static boolean modeKeyFired;
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
        if ((keyboard != KEYBOARD_MFW && keyboard != KEYBOARD_MFW_3GP) || code != KEYPANEL_VIEW_CODE
                || viewCode() != code) return false;
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
        int modeKey = Config.ClusterMap.MODE_KEY == Config.ClusterMap.ModeKey.BACK ? KEY_BACK
                : Config.ClusterMap.MODE_KEY == Config.ClusterMap.ModeKey.VOLUME_ROLLER ? KEY_VOLUME_ROLLER : -1;
        if (viewKey(keyboard, code, state)) return true;
        if ((keyboard != KEYBOARD_MFW && keyboard != KEYBOARD_MFW_3GP) || modeKey < 0 || code != modeKey) return false;
        if (state == 1 || state == 0) {
            modeKeyFired = false;
        } else if (state >= MODE_HOLD_STATE && !modeKeyFired) {
            modeKeyFired = true;
            if (Config.ClusterMap.MODE_CYCLE) {
                cycleRequested = true;
                ClusterVideo.log("wheel mode key held: next cluster mode requested");
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
     * Diagnostics only, so only at Config.Advanced.LOG_LEVEL DEBUG or TRACE: an everyday build
     * registers nothing on the other key panel instances.
     */
    private static final int OTHER_INSTANCES = 7;

    public static void attachOtherInstances(de.vw.mib.asl.framework.api.dsiproxy.DSIProxy proxy, int systemInstance) {
        if (Config.Advanced.LOG_LEVEL > de.adi961.miblogger.MIBLogger.DEBUG) {
            return;
        }
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
