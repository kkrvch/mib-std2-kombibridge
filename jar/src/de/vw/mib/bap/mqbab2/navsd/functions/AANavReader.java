package de.vw.mib.bap.mqbab2.navsd.functions;

import de.adi961.miblogger.MIBLogger;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.androidauto.target.NavigationHandler;
import de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo;
import de.vw.mib.timer.Timer;
import org.dsi.ifc.androidauto2.Constants;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Single Android-Auto turn-by-turn reader + output router.
 *
 * Reads /dev/shmem/aa_nav (written by the patched GAL receiver / navshim) on one framework timer,
 * parses it once, and routes the maneuver to exactly ONE output, chosen at runtime by
 * {@link ClusterCaps}:
 *   nav-capable cluster (Amundsen) -> the real cluster Navigation menu, via NavState + the navsd
 *                                     BAP function shadows (poke -> process -> BAP send);
 *   non-nav cluster     (Bolero)   -> the media now-playing widget, via {@link CurrentStationInfo}.
 *
 * Replaces the two former readers (ShmemNavReader for media, NavShmemReader for navsd) so the file
 * is parsed once and there is a single source of truth + a single staleness owner.
 *
 * Runs on a REPEATING framework timer (TimerManager + TIMER_THREAD_INVOKER) so the BAP send /
 * CurrentStationInfo refresh happen on the framework-managed thread the stock timers use, not a
 * foreign thread. Started once from AndroidAutoTarget (which exists on every AA-capable unit), so it
 * does not depend on whether the navsd shadows were constructed.
 *
 * IPC line (the shim rewrites the whole line on each event, O_TRUNC):
 *   seq status event side angle number dist time unit road
 * status = GAL NavigationStatus (0=UNAVAILABLE, 1=ACTIVE, 2=INACTIVE); event = AA NextTurnEnum;
 * side = AA TurnSide (1=LEFT, 2=RIGHT, 3=UNSPECIFIED). AA proto enums == DSI Constants 1:1.
 *
 * Java 1.4 (cf48): no generics, no autoboxing, no enhanced-for. Must never throw into the HMI.
 */
public final class AANavReader implements Runnable {

    private static final boolean DEBUG = false;

    private static final String PATH = "/dev/shmem/aa_nav";
    private static final long POLL_MS = 300L;
    // If aa_nav stops advancing (seq frozen) this long, treat nav as stopped (phone disconnected /
    // route ended) and clear the override so the cluster leaves guidance view.
    private static final long STALE_MS = 15000L;

    private static volatile boolean started = false;
    private static AANavReader instance = null;

    private final NavigationHandler handler;   // media path only; may be null on a nav-capable unit
    private Timer timer;

    private long lastSeq = -1L;
    private int  lastStatus = -1;
    private long lastSeqChangeMs = 0L;
    private boolean cleared = true;

    private AANavReader(NavigationHandler handler) {
        this.handler = handler;
    }

    /** Create and start the repeating poll timer once, on the framework timer thread. */
    public static synchronized void ensureStarted(NavigationHandler handler) {
        if (started) {
            return;
        }
        started = true;
        try {
            instance = new AANavReader(handler);
            instance.timer = ServiceManager.timerManager.createTimer(
                    "AATOKOMBI_NAV_POLL", POLL_MS, true, instance, Timer.TIMER_THREAD_INVOKER);
            instance.timer.start();
            MIBLogger.getInstance().info("AANavReader started, polling " + PATH);
        } catch (Throwable t) {
            // If the framework timer isn't available yet, leave started=false so a later call retries.
            started = false;
            MIBLogger.getInstance().error("AANavReader start failed: " + t);
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

        if (DEBUG) {
            MIBLogger.getInstance().debug("aa_nav seq=" + seq + " st=" + status + " event=" + event
                    + " side=" + side + " angle=" + angle + " number=" + number + " dist=" + dist
                    + " time=" + time + " unit=" + unit + " q4='" + quaternaryFor(event, number, time)
                    + "' road=" + road);
        }

        // status != ACTIVE -> guidance stopped/disconnected: clear whichever path is live.
        if (status != 1) {
            if (status != lastStatus && handler != null) {
                try { handler.navigationFocus(Constants.NAVFOCUS_NATIVE); } catch (Throwable t) {}
            }
            lastStatus = status;
            clearActive();
            return;
        }

        boolean statusEdge = (status != lastStatus);
        lastStatus = status;
        cleared = false;

        // Router: one parse, exactly one output, chosen by the runtime cluster type.
        if (ClusterCaps.isNavCapable()) {
            publishNavsd(event, side, angle, number, road, dist);
        } else {
            if (statusEdge && handler != null) {
                try { handler.navigationFocus(Constants.NAVFOCUS_PROJECTED); } catch (Throwable t) {}
            }
            publishMedia(event, side, number, road, dist, time);
        }
    }

    // ===== nav-capable path: NavState + navsd shadows ==========================================
    private void publishNavsd(int event, int side, int angle, int number, String road, int dist) {
        int effAngle = effectiveAngle(event, angle, number);    // real bearing (Maps) or synth from exit number (Yandex)
        NavState.mainElement    = mapMainElement(event, side);
        NavState.direction      = mapDirection(event, side, effAngle);
        NavState.street         = (road != null) ? road : "";   // next-turn road -> TurnToInfo
        NavState.sideStreets    = buildRingSideStreets(event, effAngle);  // exit-road spoke on a roundabout, "" otherwise
        NavState.distanceMeters = dist;                          // raw metres (stock formatter in the shadow)
        NavState.zLevelGuidance = 0;
        NavState.ACTIVE         = true;
        ActiveRgType.poke();
        RGStatus.poke();
        ManeuverDescriptor.poke();
        DistanceToNextManeuver.poke();
        TurnToInfo.poke();
    }

    // GAL (AA) NextTurnEnum + TurnSide -> VW navsd MAIN_ELEMENT (ASLNavSDConstants values). [FW-TUNE]
    static int mapMainElement(int event, int side) {
        switch (event) {
            case 1:  return 11;                    // DEPART      -> FOLLOW_STREET
            case 2:  return 11;                    // NAME_CHANGE -> FOLLOW_STREET
            case 3:  return 13;                    // SLIGHT_TURN -> TURN
            case 4:  return 13;                    // TURN        -> TURN
            case 5:  return 13;                    // SHARP_TURN  -> TURN
            case 6:  return 25;                    // U_TURN      -> UTURN
            case 7:  return (side == 1) ? 33 : 32; // ON_RAMP     -> EXIT_LEFT/RIGHT_RAMP
            case 8:  return (side == 1) ? 16 : 15; // OFF_RAMP    -> EXIT_LEFT/RIGHT
            case 9:  return 19;                    // FORK        -> FORK_2
            case 10: return 14;                    // MERGE       -> TURN_ON_MAINROAD
            case 11:
            case 12:
            case 13: return (side == 1) ? 22 : 21; // ROUNDABOUT  -> TRS_LEFT/RIGHT
            case 14: return 11;                    // STRAIGHT    -> FOLLOW_STREET
            case 17:
            case 18:
            case 19: return 3;                     // DESTINATION / dest-left / dest-right -> ARRIVED
            default: return 13;                    // unknown     -> TURN
        }
    }

    // VW direction = arrow rotation, CCW 0..255. Hardware-verified on an Amundsen Maxidot: renderable
    // values are discrete 0 (straight) / 64 (left) / 192 (right); 128 (180/down) does NOT render on
    // the mono cluster. [HW-TUNE]
    //  - turns: 3 sharpness levels off the AA event enum (slight/normal/sharp), mirrored L/R;
    //  - roundabout: real exit bearing from the AA angle (compact ENTER_AND_EXIT carries it), else neutral;
    //    the 21/22 glyph is set in mapMainElement;
    //  - u-turn: the UTURN glyph (main=25) needs a valid side-direction to orient -> 64/192 (dir=128 = blank).
    static int mapDirection(int event, int side, int angle) {
        if (event == 6) {                          // u-turn: orient the UTURN glyph by side (64 left / 192 right)
            return (side == 2) ? 192 : 64;
        }
        if (event == 1 || event == 2 || event == 14
                || event == 17 || event == 18 || event == 19) {
            return 0;                              // depart / name-change / straight / arrival
        }
        if (event == 11 || event == 12 || event == 13) {    // roundabout: true exit bearing when sent
            if (angle >= 1 && angle <= 360) {
                return ((angle - 180) * 256 / 360) & 0xFF;   // 180 = straight across; sign hw-confirmed
            }
            return 0;                              // no exit angle (large roundabout) -> neutral up
        }
        int mag;                                   // ordinary turn: slight 32 / normal 64 / sharp 96
        switch (event) {
            case 3:  mag = 32; break;              // SLIGHT_TURN
            case 5:  mag = 96; break;              // SHARP_TURN
            default: mag = 64; break;              // TURN / ramp / fork / merge / unknown
        }
        if (side == 1) {
            return mag;                            // LEFT
        }
        if (side == 2) {
            return (256 - mag) & 0xFF;             // RIGHT (mirror)
        }
        return 0;                                  // unspecified -> straight
    }

    // Effective exit bearing for a roundabout. Google Maps sends the real angle; Yandex Navigator sends
    // angle=0 on every maneuver but a valid exit number, so synthesize a bearing from the number
    // (typical 4-way roundabout: 1=right, 2=straight, 3=left, 4=back-left). Hardware-confirmed: Yandex
    // roundabouts then point the right way. Maps and ordinary turns pass through unchanged. [HW-TUNE]
    static int effectiveAngle(int event, int angle, int number) {
        if ((event == 11 || event == 12 || event == 13) && number > 0 && (angle < 1 || angle > 360)) {
            int a = 90 + (number - 1) * 90;
            return (a > 315) ? 315 : a;
        }
        return angle;
    }

    // Roundabout exit-road geometry for the maneuver glyph (col3 sidestreets). One SIDE_STREET_*_EXIT
    // glyph at the taken-exit bearing; the cluster draws the entry leg itself, giving an entry+exit
    // ring. Non-roundabouts -> "" (no junction geometry). Glyph table = GuidanceModel.SIDE_STREET_*
    // exit codes per 22.5-deg compass position (entry at BASE/180): only the 45-deg slots carry an EXIT
    // glyph, and the bearing is 45-deg-quantized, so we snap to those. (Intermediate ROUNDABOUT stubs
    // exist too but this cluster does not render them, so we omit them.)
    private static final int[] RING_EXIT = {1, 47, 38, 31, 53, 23, 14, 7}; // FWD,RF,RIGHT,RB,BASE,LB,LEFT,LF
    static String buildRingSideStreets(int event, int angle) {
        if (event != 11 && event != 12 && event != 13) {
            return "";
        }
        if (angle < 1 || angle > 360) {
            return "";                             // no bearing (shouldn't happen post-effectiveAngle) -> plain glyph
        }
        int compass = ((180 - angle) % 360 + 360) % 360;   // exit bearing on the drawn circle
        int oct = ((compass + 22) / 45) & 7;               // nearest of the 8 exit-capable positions
        return String.valueOf((char) (RING_EXIT[oct] & 0xFF));
    }

    // ===== non-nav path: media now-playing widget =============================================
    private void publishMedia(int event, int side, int number, String road, int dist, int time) {
        // Cluster layout (top->bottom): secondary, tertiary, PRIMARY(big), quaternary.
        //   PRIMARY = arrow + distance ("> 300 m"); secondary = street; tertiary = maneuver word.
        String arrow = arrowForManeuver(event, side);
        String word = wordFor(event, side);
        String street = (road != null && road.length() > 0) ? road : "";
        String distStr = formatDistance(dist);
        CurrentStationInfo.navPrimary = (distStr.length() > 0) ? (arrow + " " + distStr) : arrow;
        CurrentStationInfo.navSecondary = street;
        CurrentStationInfo.navTertiary = word;
        CurrentStationInfo.navQuaternary = quaternaryFor(event, number, time);  // Q4: exit # else time-to-turn
        CurrentStationInfo.pokeNav();
    }

    // Glyphs the cluster font actually renders.
    private static final String A_LEFT   = "◄";  // left pointer
    private static final String A_RIGHT  = "►";  // right pointer
    private static final String A_UP     = "▲";  // up triangle (straight / depart / continue)
    private static final String A_DOWN   = "▼";  // down triangle (u-turn)
    private static final String A_ROUND  = "○";  // ring (roundabout)
    private static final String A_ARRIVE = "★";  // star (destination)

    private String arrowForManeuver(int event, int side) {
        switch (event) {
            case 6:  return A_DOWN;     // U-turn
            case 11:
            case 12:
            case 13: return A_ROUND;    // roundabout
            case 17: return A_ARRIVE;   // destination
            default:
                if (side == 1) return A_LEFT;
                if (side == 2) return A_RIGHT;
                return A_UP;
        }
    }

    private String wordFor(int event, int side) {
        switch (event) {
            case 1:  return "Start";
            case 2:  return "Continue";
            case 3:  return side == 1 ? "Bear left" : side == 2 ? "Bear right" : "Continue";
            case 4:  return side == 1 ? "Turn left" : side == 2 ? "Turn right" : "Turn";
            case 5:  return side == 1 ? "Sharp left" : side == 2 ? "Sharp right" : "Turn";
            case 6:  return "U-turn";
            case 7:  return "On-ramp";
            case 8:  return "Exit";
            case 9:  return "Fork";
            case 10: return "Merge";
            case 11:
            case 12:
            case 13: return "Roundabout";
            case 14: return "Straight";
            case 15:
            case 16: return "Ferry";
            case 17: return "Arrive";
            default: return "";
        }
    }

    private String quaternaryFor(int event, int number, int time) {
        // Q4: roundabout exit number only.
        if ((event == 11 || event == 12 || event == 13) && number > 0) {
            return "exit " + number;
        }
        return "";
    }

    /** "300 m" / "1,2 km" / "" when unknown. */
    private String formatDistance(int dist) {
        if (dist <= 0) {
            return "";
        }
        if (dist < 1000) {
            if (dist < 10) {
                return dist + " m";           // very short: show the exact metres
            }
            return (((dist + 5) / 10) * 10) + " m";   // else round to nearest 10 m, like Google
        }
        return (dist / 1000) + "," + ((dist % 1000) / 100) + " km";
    }

    // ===== shared lifecycle ====================================================================
    private void maybeClearStale() {
        if (cleared) {
            return;
        }
        if (System.currentTimeMillis() - lastSeqChangeMs > STALE_MS) {
            clearActive();
        }
    }

    /** Clear the output of the path this cluster actually drives. */
    private void clearActive() {
        if (cleared) {
            return;
        }
        cleared = true;
        // Mirror the router (processOnce): clear ONLY the active path. Crucially, on a non-nav
        // cluster we must not poke the navsd shadows -- poke()->process() emits navsd BAP regardless
        // of isNavCapable(), and the shadows ARE constructed on a non-nav EU unit (factory "All"), so
        // an un-gated poke here would push navsd traffic onto a non-nav cluster (Blocker 2).
        if (ClusterCaps.isNavCapable()) {
            try {
                NavState.ACTIVE = false;
                ActiveRgType.poke();
                RGStatus.poke();
                ManeuverDescriptor.poke();
                DistanceToNextManeuver.poke();
                TurnToInfo.poke();
            } catch (Throwable t) {
                // ignore
            }
        } else {
            try {
                CurrentStationInfo.navPrimary = null;
                CurrentStationInfo.navSecondary = null;
                CurrentStationInfo.navTertiary = null;
                CurrentStationInfo.navQuaternary = null;
                NavigationHandler.aaRouteGuidanceActive = false;
                CurrentStationInfo.pokeNav();
            } catch (Throwable t) {
                // ignore
            }
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
}
