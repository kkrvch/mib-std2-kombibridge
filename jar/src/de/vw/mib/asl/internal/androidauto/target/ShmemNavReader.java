package de.vw.mib.asl.internal.androidauto.target;

import de.adi961.miblogger.MIBLogger;
import de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo;
import org.dsi.ifc.androidauto2.Constants;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.timer.Timer;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Reads Android Auto turn-by-turn from /dev/shmem/aa_nav (written by the patched GAL receiver /
 * navshim, see ../shim/) and renders the maneuver into the cluster's media now-playing widget via
 * {@link CurrentStationInfo} — the cluster won't take nav in its own slot, so we hijack the media
 * widget (see docs/ARCHITECTURE.md). {@link NavigationHandler#aaRouteGuidanceActive} is also driven
 * so that widget knows route guidance is active.
 *
 * Runs as a REPEATING framework timer (TimerManager + TIMER_THREAD_INVOKER) rather than a raw
 * Thread, so the CurrentStationInfo refresh happens on the same framework-managed thread the stock
 * timers use — not on a foreign thread the framework does not know about.
 *
 * IPC line (the shim rewrites the whole line on each event, O_TRUNC):
 *   seq status event side angle number dist time unit road
 * where status is the GAL NavigationStatus enum (0=UNAVAILABLE, 1=ACTIVE, 2=INACTIVE) and the rest
 * are the raw NavigationNextTurnEvent / DistanceEvent fields. Raw values are logged at DEBUG.
 */
public class ShmemNavReader implements Runnable {

    private static final String PATH = "/dev/shmem/aa_nav";
    private static final long POLL_MS = 300L;
    // If aa_nav stops advancing (seq frozen) for this long, treat nav as stopped/disconnected and
    // clear the nav-in-media override. The shim only writes on GAL callbacks, so on phone
    // disconnect the file just freezes — this is the fallback that un-sticks it.
    private static final long STALE_MS = 15000L;

    private final NavigationHandler handler;
    private Timer timer;

    private long lastSeq = -1L;
    private int lastStatus = -1;
    private long lastSeqChangeMs = 0L;

    public ShmemNavReader(NavigationHandler handler) {
        this.handler = handler;
    }

    /** Create and start the repeating poll timer on the framework timer thread. */
    public void start() {
        try {
            this.timer = ServiceManager.timerManager.createTimer(
                    "AATOKOMBI_NAV_POLL", POLL_MS, true, this, Timer.TIMER_THREAD_INVOKER);
            this.timer.start();
            MIBLogger.getInstance().info("ShmemNavReader timer started, polling " + PATH);
        } catch (Throwable t) {
            MIBLogger.getInstance().error("ShmemNavReader timer start failed: " + t);
        }
    }

    /** Called by the framework timer every POLL_MS. Must never throw. */
    public void run() {
        try {
            processOnce();
        } catch (Throwable t) {
            // Never let the poll disturb the HMI.
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
        int status, protoEvent, protoSide, angle, number, dist, time, unit;
        try {
            seq = Long.parseLong(p[0]);
            status = Integer.parseInt(p[1]);
            protoEvent = Integer.parseInt(p[2]);
            protoSide = Integer.parseInt(p[3]);
            angle = Integer.parseInt(p[4]);
            number = Integer.parseInt(p[5]);
            dist = Integer.parseInt(p[6]);
            time = Integer.parseInt(p[7]);
            unit = Integer.parseInt(p[8]);
        } catch (NumberFormatException ex) {
            return;
        }
        String road = p[9];

        if (seq == lastSeq) {
            // no new data -> the shim hasn't written (e.g. phone disconnected). Clear if stale.
            maybeClearStale();
            return;
        }
        lastSeq = seq;
        lastSeqChangeMs = System.currentTimeMillis();

        // Semantic GAL fields (logged at DEBUG, visible at the diagnostic default level).
        MIBLogger.getInstance().debug("aa_nav seq=" + seq + " st=" + status
                + " event=" + protoEvent + " side=" + protoSide + " angle=" + angle
                + " number=" + number + " dist=" + dist + " time=" + time
                + " unit=" + unit + " road=" + road);

        // 1) route-guidance active flag from status (1 = ACTIVE).
        if (status != lastStatus) {
            lastStatus = status;
            handler.navigationFocus(status == 1
                    ? Constants.NAVFOCUS_PROJECTED
                    : Constants.NAVFOCUS_NATIVE);
        }
        // nav not active -> clear the nav-in-media override so the stock "Android Auto" returns.
        if (status != 1) {
            clearNav();
            return;
        }

        // Render the maneuver into CurrentStationInfo (audiosd LSG 49 — the widget the cluster draws
        // the "Android Auto" label in). Field -> line mapping is described just below.
        int event = mapEventEnum(protoEvent);
        int side = mapSideEnum(protoSide);
        // Cluster layout (top->bottom): secondary, tertiary, PRIMARY(big), quaternary.
        //   PRIMARY  (big) = arrow + distance  (the glance line, e.g. "→ 300 m"; just the arrow if dist unknown)
        //   secondary(top) = street
        //   tertiary       = maneuver words    ("Turn right")
        String arrow = arrowForManeuver(event, side);
        String word = wordFor(event, side);
        String street = (road != null && road.length() > 0) ? road : "";
        String distStr = formatDistance(dist);
        CurrentStationInfo.navPrimary = (distStr.length() > 0) ? (arrow + " " + distStr) : arrow;
        CurrentStationInfo.navSecondary = street;
        CurrentStationInfo.navTertiary = word;
        CurrentStationInfo.navQuaternary = quaternaryFor(event, number);  // Q4: roundabout exit #
        CurrentStationInfo.pokeNav();
    }

    // Glyphs the cluster font actually renders
    private static final String A_LEFT   = "◄";  // U+25C4 left pointer
    private static final String A_RIGHT  = "►";  // U+25BA right pointer
    private static final String A_UP     = "▲";  // U+25B2 up triangle (straight / depart / continue)
    private static final String A_DOWN   = "▼";  // U+25BC down triangle (u-turn)
    private static final String A_ROUND  = "○";  // U+25CB ring (roundabout)
    private static final String A_ARRIVE = "★";  // U+2605 star (destination)

    /** Glyph for the big PRIMARY line, from AA NextTurnEnum (event) + TurnSide (side). Uses only
     *  glyphs the cluster font renders: bold ◄ ► ▲ ▼, ring ○ for roundabout, ★ for arrive. */
    private String arrowForManeuver(int event, int side) {
        switch (event) {
            case 6:  return A_DOWN;    // U-turn -> ▼
            case 11:
            case 12:
            case 13: return A_ROUND;   // roundabout -> ○
            case 17: return A_ARRIVE;  // destination -> ★
            default:
                if (side == 1) return A_LEFT;
                if (side == 2) return A_RIGHT;
                return A_UP;
        }
    }

    /** Maneuver words for the tertiary line (no arrow), from NextTurnEnum (event) + TurnSide (side). */
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

    /** Q4 small line: roundabout exit number (AA TurnNumber), only for roundabout maneuvers. */
    private String quaternaryFor(int event, int number) {
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
            return dist + " m";
        }
        return (dist / 1000) + "," + ((dist % 1000) / 100) + " km";
    }

    /** Clear the nav-in-media override if it has gone stale (shim stopped writing). */
    private void maybeClearStale() {
        if (CurrentStationInfo.navPrimary == null) {
            return; // already cleared
        }
        if (System.currentTimeMillis() - lastSeqChangeMs > STALE_MS) {
            clearNav();
        }
    }

    private void clearNav() {
        if (CurrentStationInfo.navPrimary != null) {
            MIBLogger.getInstance().debug("nav stopped/stale -> clearing nav-in-media");
        }
        CurrentStationInfo.navPrimary = null;
        CurrentStationInfo.navSecondary = null;
        CurrentStationInfo.navTertiary = null;
        CurrentStationInfo.navQuaternary = null;
        NavigationHandler.aaRouteGuidanceActive = false;
        CurrentStationInfo.pokeNav();
    }

    // GAL NextTurnEnum value -> DSI Constants.NAVIGATIONTURNEVENT_*.
    // If the DSI enum mirrors Google's values 1:1 this is identity. [HW-TUNE]
    // CONFIRMED: AA proto NextTurnEnum / TurnSide values == DSI Constants values (1:1, identity).
    // (proto Event=1=DEPART==NAVIGATIONTURNEVENT_DEPART; TurnSide=3=UNSPECIFIED, etc.)
    private int mapEventEnum(int gal) { return gal; }
    // GAL TurnSide -> DSI Constants.NAVIGATIONTURNSIDE_*. [HW-TUNE]
    private int mapSideEnum(int gal) { return gal; }

    /** Split into n-1 tokens by single space, last token keeps the remainder (road may contain spaces). */
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
                try {
                    br.close();
                } catch (Exception ex) {
                    // ignore
                }
            }
        }
    }
}
