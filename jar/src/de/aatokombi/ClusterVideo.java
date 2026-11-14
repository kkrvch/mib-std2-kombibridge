package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

import java.io.FileInputStream;

/**
 * State of Android Auto's cluster video, as the patched libgal (CLUSTER_SINK build) reports it in
 * /dev/shmem/aa_cluster_video: "<alive> <dm> <tick> <session>\n", rewritten about once a second
 * while the phone's cluster channel is open. dm = 1 only while DisplayManager's hook (libkb_dm.so)
 * really hands the phone's frames to the cluster. The stock cluster map is hidden only then: hiding
 * it while nothing replaces it freezes the cluster map (car, 2026-10-28, hook removed). session = 1
 * while the phone's cluster channel is open (also when it sends no video). The state counts only
 * while its tick moved within STALE_MS (a dead SAL stops the ticks).
 *
 * Commands to the libgal go through /dev/shmem/aa_cluster_cmd (see sendCommand).
 *
 * Driver modes on the AID while Android Auto is connected (Config.ClusterMap.MODE_CYCLE, switched by
 * the head unit's map/arrows button through ActiveRgType, see switchTo):
 *   MODE_AA_MAP    the phone's map (video on, rgtype 3)
 *   MODE_ARROWS    this mod's turn arrows (video off, rgtype 0)
 *   MODE_STOCK_MAP the unit's own map (video off, rgtype 3)
 *
 * poll() is called from AANavReader's timer; it reads the file at most every READ_MS.
 * Java 1.3 source; never throws.
 */
public final class ClusterVideo {
    public static final String PATH = "/dev/shmem/aa_cluster_video";
    public static final String CMD_PATH = "/dev/shmem/aa_cluster_cmd";
    /** Present = libgal gives the phone cluster video focus only during route guidance. */
    public static final String GUIDANCE_ONLY_FLAG = "/dev/shmem/aa_cluster_guidance_only";
    /** Present = libgal never gives the phone cluster video focus (Config.Navigation.ENABLED false). */
    public static final String VIDEO_OFF_FLAG = "/dev/shmem/aa_cluster_video_off";
    private static final long READ_MS = 900L;
    private static final long STALE_MS = 3000L;

    public static final int MODE_AA_MAP = 0;
    public static final int MODE_ARROWS = 1;
    public static final int MODE_STOCK_MAP = 2;

    private static long readMs, tickMs;
    private static String lastTick = "";
    private static boolean alive, session;
    private static int mode = MODE_AA_MAP;
    private static int lastMapMode = MODE_STOCK_MAP;

    private static int cmdSeq = -1;
    private static int cmdLogged, cmdErrLogged;
    private static final String[] cmdLines = new String[4];
    private static int cmdNext;

    private ClusterVideo() {}

    /**
     * HMI start: make the flag files for the libgal match the settings - GUIDANCE_ONLY_FLAG
     * (Config.ClusterMap.AA_MAP_ONLY_DURING_GUIDANCE) and VIDEO_OFF_FLAG (no AA map at all, when
     * Config.Navigation.ENABLED is false: the map is managed by the navigation reader, which then
     * does not run). The flags live in RAM, so they are gone after a reboot and never outlive the jar.
     */
    public static void applyMode() {
        syncFlag(GUIDANCE_ONLY_FLAG, Config.ClusterMap.AA_MAP_ONLY_DURING_GUIDANCE);
        syncFlag(VIDEO_OFF_FLAG, !Config.Navigation.ENABLED);
    }

    private static void syncFlag(String path, boolean on) {
        try {
            java.io.File f = new java.io.File(path);
            if (on) {
                if (!f.exists()) {
                    java.io.FileWriter w = new java.io.FileWriter(path);
                    try { w.write("1\n"); } finally { w.close(); }
                }
            } else if (f.exists()) {
                f.delete();
            }
        } catch (Throwable t) {
            // the libgal then streams whenever AA is connected
        }
    }

    /** The phone's map is on the AID right now (DisplayManager hook running). */
    public static boolean alive() {
        return alive;
    }

    /** An AA session with an open cluster channel (CLUSTER_SINK libgal), with or without video. */
    public static boolean session() {
        return session;
    }

    public static int mode() {
        return mode;
    }

    /** The cluster guidance type for the current driver mode: 0 arrows, 3 map. */
    public static int rgTypeForMode() {
        return mode == MODE_ARROWS ? 0 : 3;
    }

    /**
     * The head unit's map/arrows button (menu "Navigation"), seen as the guidance type the unit
     * and the cluster switch to: 0 arrows, 3 map. It has only two states, so the three modes are
     * walked as AA map -> arrows -> stock map -> arrows -> AA map: every switch to the map takes
     * the map mode NOT used last time. The map modes need the unit's switch on "map": with it on
     * "arrows" the unit's cluster map (and with it DisplayManager's cluster encoder, which carries
     * the AA video too) is presumably off. The same type again (the cluster's echo) changes nothing.
     */
    /** Next mode for the steering-wheel mode key hold: AA map -> arrows -> stock map -> AA map. */
    public static synchronized void nextMode() {
        if (mode != MODE_ARROWS) lastMapMode = mode;
        mode = (mode + 1) % 3;
        sendCommand("video " + (mode == MODE_AA_MAP ? 1 : 0));
        log("cluster mode -> " + modeName(mode) + " (mode key held)");
    }

    /** The head unit's switch as last seen (0 arrows, 3 map, -1 unknown), recorded always. */
    private static int huType = -1;

    public static void noteHuSwitch(int rgtype) {
        huType = rgtype;
    }

    public static synchronized void switchTo(int rgtype) {
        int m;
        if (rgtype == 0) {
            if (mode == MODE_ARROWS) return;
            lastMapMode = mode;
            m = MODE_ARROWS;
        } else {
            if (mode != MODE_ARROWS) return;
            m = lastMapMode == MODE_AA_MAP ? MODE_STOCK_MAP : MODE_AA_MAP;
        }
        mode = m;
        sendCommand("video " + (mode == MODE_AA_MAP ? 1 : 0));
        log("cluster mode -> " + modeName(mode));
    }

    public static String modeName(int m) {
        return m == MODE_AA_MAP ? "AA_MAP" : m == MODE_ARROWS ? "ARROWS" : "STOCK_MAP";
    }

    /**
     * One command line for the libgal ("key 168", "rep 169 350", "rep 0 0", "video 0", ...).
     * The file keeps the last 4 lines, each "<seq> <cmd>"; the libgal (polling every 100 ms)
     * runs every line whose sequence is above the last one it ran. The sequence continues from
     * the file after an HMI restart.
     */
    public static synchronized void sendCommand(String cmd) {
        try {
            if (cmdSeq < 0) {
                cmdSeq = lastSeqInFile();
            }
            cmdSeq++;
            cmdLines[cmdNext] = cmdSeq + " " + cmd;
            cmdNext = (cmdNext + 1) % cmdLines.length;
            StringBuffer sb = new StringBuffer();
            for (int i = 0; i < cmdLines.length; i++) {
                String l = cmdLines[(cmdNext + i) % cmdLines.length];
                if (l != null) {
                    sb.append(l).append('\n');
                }
            }
            java.io.FileWriter w = new java.io.FileWriter(CMD_PATH);
            try { w.write(sb.toString()); } finally { w.close(); }
            if (cmdLogged < 5) { cmdLogged++; log("command written: " + cmdSeq + " " + cmd); }
        } catch (Throwable t) {
            // no CLUSTER_SINK libgal: nothing reads the file anyway; but say why (car 2026-11-05:
            // zoom commands never reached the libgal, cmd_seq 0)
            if (cmdErrLogged < 5) { cmdErrLogged++; log("command not written: " + cmd + ": " + t); }
        }
    }

    private static int lastSeqInFile() {
        int max = 0;
        FileInputStream in = null;
        try {
            in = new FileInputStream(CMD_PATH);
            byte[] b = new byte[512];
            int n = in.read(b);
            int v = 0;
            boolean inNum = true;
            for (int i = 0; i < n; i++) {
                char c = (char) b[i];
                if (c == '\n') {
                    inNum = true;
                    v = 0;
                } else if (inNum && c >= '0' && c <= '9') {
                    v = v * 10 + (c - '0');
                    if (v > max) max = v;
                } else {
                    inNum = false;
                }
            }
        } catch (Throwable t) {
            // no file yet
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
        }
        return max;
    }

    public static void log(String s) {
        try { MIBLogger.getInstance().debug(s); } catch (Throwable t) {}
    }

    public static void poll() {
        if (WheelKeys.takeCycleRequest()) {
            if (session) {
                nextMode();
                try { de.vw.mib.bap.mqbab2.navsd.functions.ActiveRgType.poke(); } catch (Throwable t) {}
            } else {
                log("mode key held without an AA cluster session: ignored");
            }
        }
        long now = System.currentTimeMillis();
        if (now - readMs < READ_MS) {
            return;
        }
        readMs = now;
        boolean flag = false, sess = false;
        String tick = null;
        FileInputStream in = null;
        try {
            in = new FileInputStream(PATH);
            byte[] b = new byte[40];
            int n = in.read(b);
            if (n > 0) {
                String s = new String(b, 0, n).trim();
                // "<alive> <dm> <tick> [<session>]": alive and dm must both be 1
                flag = s.length() >= 3 && s.charAt(0) == '1' && s.charAt(2) == '1';
                int sp1 = s.indexOf(' ', 4);
                if (sp1 > 0) {
                    tick = s.substring(4, sp1);
                    sess = s.substring(sp1 + 1).trim().equals("1");
                } else {
                    tick = s.length() > 4 ? s.substring(4) : s;
                    sess = flag;   // older libgal without the session field
                }
            }
        } catch (Throwable t) {
            // no CLUSTER_SINK libgal, or no AA session yet
        } finally {
            try { if (in != null) in.close(); } catch (Throwable t) {}
        }
        if (tick != null && !tick.equals(lastTick)) {
            lastTick = tick;
            tickMs = now;
        }
        boolean fresh = now - tickMs < STALE_MS;
        alive = flag && fresh;
        boolean s = sess && fresh;
        if (s != session) {
            session = s;
            log("cluster session " + (s ? "open" : "closed") + ", mode " + modeName(mode));
            if (s) {
                // a (re)started libgal begins with video allowed: tell it the driver's choice
                if (Config.ClusterMap.MODE_CYCLE) {
                    // follow the unit's switch as it is now (arrows -> arrows mode)
                    if (huType == 0 && mode != MODE_ARROWS) {
                        lastMapMode = mode;
                        mode = MODE_ARROWS;
                    }
                    sendCommand("video " + (mode == MODE_AA_MAP ? 1 : 0));
                }
            } else {
                sendCommand("rep 0 0");
            }
        }
        KombiMap.phoneVideo(alive);
    }
}
