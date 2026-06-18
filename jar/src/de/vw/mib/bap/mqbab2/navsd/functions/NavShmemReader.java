package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.timer.Timer;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Live input half of the navsd-native AA-nav-on-Maxidot path.
 *
 * Reads Android Auto turn-by-turn from /dev/shmem/aa_nav (written by the patched GAL receiver /
 * navshim), maps the raw GAL fields to the VW navsd constants (ASLNavSDConstants), writes them into
 * {@link NavState}, and pokes the navsd BAP function shadows so the cluster's Navigation menu
 * redraws the real maneuver.
 *
 * Runs as a REPEATING framework timer (TimerManager + TIMER_THREAD_INVOKER), so the poke()/BAP send
 * happens on the same framework-managed thread the stock navsd timers use, not a foreign thread.
 *
 * IPC line (the shim rewrites the whole line on each event, O_TRUNC):
 *   seq status event side angle number dist time unit road
 * status = GAL NavigationStatus (0=UNAVAILABLE, 1=ACTIVE, 2=INACTIVE); event = AA NextTurnEnum;
 * side = AA TurnSide (1=LEFT, 2=RIGHT, 3=UNSPECIFIED); the rest are the raw NextTurn/Distance fields.
 *
 * Java 1.4 (cf48): no generics, no autoboxing, no enhanced-for.
 */
public final class NavShmemReader implements Runnable {

    // Diagnostic: when true, each mapped maneuver is written to /dev/shmem/aa_navsd.dbg (RAM, no
    // flash). Off by default so production does not rewrite a file every poll. Flip to debug a unit.
    private static final boolean DEBUG = false;

    private static final String PATH = "/dev/shmem/aa_nav";
    private static final String DBG  = "/dev/shmem/aa_navsd.dbg";
    private static final long POLL_MS = 300L;
    // If aa_nav stops advancing (seq frozen) this long, treat nav as stopped (phone disconnected /
    // route ended) and clear the override so the cluster leaves guidance view.
    private static final long STALE_MS = 15000L;

    private static volatile boolean started = false;
    private static NavShmemReader instance = null;
    private Timer timer;

    private long lastSeq = -1L;
    private int  lastStatus = -1;
    private long lastSeqChangeMs = 0L;
    private boolean cleared = true;

    private NavShmemReader() {}

    /** Create and start the repeating poll timer once, on the framework timer thread. */
    public static synchronized void ensureStarted() {
        if (started) {
            return;
        }
        started = true;
        try {
            instance = new NavShmemReader();
            instance.timer = ServiceManager.timerManager.createTimer(
                    "AANAV_NAVSD_POLL", POLL_MS, true, instance, Timer.TIMER_THREAD_INVOKER);
            instance.timer.start();
            dbg("NavShmemReader started, polling " + PATH);
        } catch (Throwable t) {
            // If the framework timer isn't available yet, leave started=false so a later init() retries.
            started = false;
            dbg("NavShmemReader start failed: " + t);
        }
    }

    /** Called by the framework timer every POLL_MS. Must never throw. */
    public void run() {
        try {
            processOnce();
        } catch (Throwable t) {
            // never let the poll disturb the HMI
        }
    }

    private void processOnce() {
        String line = readLine();
        if (line == null || line.length() == 0) {
            maybeClearStale();
            return;
        }
        // seq status event side angle number dist time unit road
        String[] p = split(line, 10);
        if (p == null) {
            maybeClearStale();
            return;
        }
        long seq;
        int status, event, side, angle, number, dist, time, unit;
        try {
            seq    = Long.parseLong(p[0]);
            status = Integer.parseInt(p[1]);
            event  = Integer.parseInt(p[2]);
            side   = Integer.parseInt(p[3]);
            angle  = Integer.parseInt(p[4]);
            number = Integer.parseInt(p[5]);
            dist   = Integer.parseInt(p[6]);
            time   = Integer.parseInt(p[7]);
            unit   = Integer.parseInt(p[8]);
        } catch (NumberFormatException ex) {
            return;
        }
        String road = p[9];

        if (seq == lastSeq) {
            maybeClearStale();
            return;
        }
        lastSeq = seq;
        lastSeqChangeMs = System.currentTimeMillis();

        // status != ACTIVE -> drop the override; cluster leaves guidance view.
        if (status != 1) {
            if (status != lastStatus) {
                lastStatus = status;
            }
            clearNav();
            dbg("seq=" + seq + " status=" + status + " -> INACTIVE");
            return;
        }
        lastStatus = status;

        // Map GAL fields -> VW navsd constants and publish to NavState.
        int mainElement = mapMainElement(event, side);
        int direction   = mapDirection(event, side, angle);
        NavState.mainElement    = mainElement;
        NavState.direction      = direction;
        NavState.street         = (road != null) ? road : "";   // next-turn road -> TurnToInfo
        NavState.distanceMeters = dist;            // raw GAL DistanceMeters (run through stock formatter in the shadow)
        NavState.zLevelGuidance = 0;
        NavState.ACTIVE         = true;
        cleared = false;

        // Push to the cluster: maneuver + distance every tick; rg-type/status only matter on the
        // ACTIVE edge but poking them is cheap and keeps the four functions coherent.
        ActiveRgType.poke();
        RGStatus.poke();
        ManeuverDescriptor.poke();
        DistanceToNextManeuver.poke();
        TurnToInfo.poke();          // next-turn street line ("turn into <road>")

        dbg("seq=" + seq + " event=" + event + " side=" + side + " -> main=" + mainElement
                + " dir=" + direction + " dist=" + dist + " road=" + road);
    }

    // ---- GAL (Android Auto) -> VW navsd mapping --------------------------------------------------
    // AA NextTurnEnum: 1 DEPART,2 NAME_CHANGE,3 SLIGHT,4 TURN,5 SHARP,6 UTURN,7 ON_RAMP,8 OFF_RAMP,
    //                  9 FORK,10 MERGE,11/12/13 ROUNDABOUT,14 STRAIGHT,15/16 FERRY,17 DESTINATION.
    // AA TurnSide: 1 LEFT, 2 RIGHT, 3 UNSPECIFIED.
    // VW mainElement (ASLNavSDConstants MANEUVER_DESCRIPTOR_MAIN_ELEMENT): 11 FOLLOW_STREET, 13 TURN,
    //   14 TURN_ON_MAINROAD, 15 EXIT_RIGHT, 16 EXIT_LEFT, 19 FORK_2, 21/22 ROUND_ABOUT_TRS_RIGHT/LEFT,
    //   25 UTURN, 3 ARRIVED, 32/33 EXIT_RIGHT/LEFT_RAMP. [HW-TUNE]
    static int mapMainElement(int event, int side) {
        switch (event) {
            case 1:  return 11;                 // DEPART      -> FOLLOW_STREET
            case 2:  return 11;                 // NAME_CHANGE -> FOLLOW_STREET
            case 3:  return 13;                 // SLIGHT_TURN -> TURN
            case 4:  return 13;                 // TURN        -> TURN
            case 5:  return 13;                 // SHARP_TURN  -> TURN
            case 6:  return 25;                 // U_TURN      -> UTURN
            case 7:  return (side == 1) ? 33 : 32; // ON_RAMP  -> EXIT_LEFT/RIGHT_RAMP
            case 8:  return (side == 1) ? 16 : 15; // OFF_RAMP -> EXIT_LEFT/RIGHT
            case 9:  return 19;                 // FORK        -> FORK_2
            case 10: return 14;                 // MERGE       -> TURN_ON_MAINROAD
            case 11:
            case 12:
            case 13: return (side == 1) ? 22 : 21; // ROUNDABOUT -> TRS_LEFT/RIGHT
            case 14: return 11;                 // STRAIGHT    -> FOLLOW_STREET
            case 17: return 3;                  // DESTINATION -> ARRIVED
            default: return 13;                 // unknown     -> TURN
        }
    }

    // VW direction = arrow rotation, CCW angle 0..255 (0=straight, 64=left, 128=u-turn, 192=right);
    // 192=RIGHT. [HW-TUNE] angle-precise rotation deferred (GAL angle often 0).
    static int mapDirection(int event, int side, int angle) {
        if (event == 6) {
            return 128;                         // u-turn
        }
        if (event == 1 || event == 2 || event == 14) {
            return 0;                           // depart / continue / straight
        }
        if (side == 1) {
            return 64;                          // LEFT
        }
        if (side == 2) {
            return 192;                         // RIGHT
        }
        return 0;                               // unspecified -> straight
    }

    private void maybeClearStale() {
        if (cleared) {
            return;
        }
        if (System.currentTimeMillis() - lastSeqChangeMs > STALE_MS) {
            clearNav();
            dbg("stale -> cleared");
        }
    }

    private void clearNav() {
        if (cleared) {
            return;
        }
        NavState.ACTIVE = false;
        cleared = true;
        try {
            ActiveRgType.poke();
            RGStatus.poke();
            ManeuverDescriptor.poke();
            DistanceToNextManeuver.poke();
            TurnToInfo.poke();
        } catch (Throwable t) {
            // ignore
        }
    }

    /** Split into n-1 space-delimited tokens; the last token keeps the remainder (road may have spaces). */
    private String[] split(String s, int n) {
        String[] out = new String[n];
        int idx = 0;
        for (int i = 0; i < n - 1; i++) {
            int sp = s.indexOf(' ', idx);
            if (sp < 0) {
                return null;
            }
            out[i] = s.substring(idx, sp);
            idx = sp + 1;
        }
        out[n - 1] = s.substring(idx);
        return out;
    }

    private String readLine() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(PATH));
            return br.readLine();
        } catch (Exception ex) {
            return null; // file absent yet / transient -> ignore
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ex) {}
            }
        }
    }

    // On-device diagnostic (DEBUG only): one line to /dev/shmem (RAM, no flash) with the last mapped
    // maneuver. Best-effort, never throws.
    private static void dbg(String s) {
        if (!DEBUG) {
            return;
        }
        FileWriter w = null;
        try {
            w = new FileWriter(DBG, false);
            w.write(s);
            w.write("\n");
        } catch (Throwable t) {
            // ignore
        } finally {
            if (w != null) {
                try { w.close(); } catch (Throwable t) {}
            }
        }
    }
}
