package de.vw.mib.bap.mqbab2.navsd.functions;

import de.aatokombi.Config;
import de.adi961.miblogger.MIBLogger;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.asl.internal.androidauto.target.NavigationHandler;
import de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo;
import de.vw.mib.timer.Timer;
import org.dsi.ifc.androidauto2.Constants;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.StringTokenizer;

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
 * Parses the file once, so there is a single source of truth and a single staleness owner.
 *
 * Runs on a REPEATING framework timer (TimerManager + TIMER_THREAD_INVOKER) so the BAP send /
 * CurrentStationInfo refresh happen on the framework-managed thread the stock timers use, not a
 * foreign thread. Started once from AndroidAutoTarget (which exists on every AA-capable unit), so it
 * does not depend on whether the navsd shadows were constructed.
 *
 * IPC line 1 (the shim rewrites the whole file on each event, O_TRUNC):
 *   seq status event side angle number dist time unit connected session road
 * status = AA NavigationState (0=UNAVAILABLE, 1=ACTIVE, 2=INACTIVE, 3=REROUTING, 1.7 only);
 * event = AA NextTurnEnum; side = AA TurnSide (1=LEFT, 2=RIGHT, 3=UNSPECIFIED); AA proto enums ==
 * DSI Constants 1:1. The tagged lines after it: see readLines().
 *
 * Java 1.4 (cf48): no generics, no autoboxing, no enhanced-for. Must never throw into the HMI.
 */
public final class AANavReader implements Runnable {

    private static final String PATH = "/dev/shmem/aa_nav";
    private static final long POLL_MS = 300L;
    private static final int[] NO_LANES = new int[]{ 0 };
    // English, like the manoeuvre words on the media path: the firmware's own strings are not
    // reachable from here, and a mixed-language line would read worse than a consistent one.
    private static final String REROUTING_TEXT = "Rerouting...";

    private static volatile boolean started = false;
    private static AANavReader instance = null;

    private final NavigationHandler handler;   // media path only; may be null on a nav-capable unit
    private Timer timer;

    private long lastSeq = -1L;
    private int  lastStatus = -1;
    private long lastSeqChangeMs = 0L;
    private long lastSession = -1L;   // AA session epoch from the shim; change => phone reconnected
    private boolean cleared = true;
    private boolean lastImminent = true;   // log only the edges (see publishNavsd)

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
        String[] lines = readLines();
        String line = (lines != null) ? lines[0] : null;
        if (line == null || line.length() == 0) {
            maybeClearStale();
            return;
        }
        String[] p = split(line, 12);
        if (p == null) {
            maybeClearStale();
            return;
        }
        long seq, session;
        int status, event, side, angle, number, dist, time, unit, connected;
        try {
            seq    = Long.parseLong(p[0]);
            status = Integer.parseInt(p[1]);
            event  = Integer.parseInt(p[2]);
            side   = Integer.parseInt(p[3]);
            angle  = Integer.parseInt(p[4]);
            number = Integer.parseInt(p[5]);
            dist   = Integer.parseInt(p[6]);
            // time is an UNSIGNED 32-bit field: Waze writes 4294967295 (0xFFFFFFFF, "ETA unknown"),
            // which overflows Integer.parseInt and would throw NumberFormatException.
            // Google sends 0.
            time   = (int) Long.parseLong(p[7]);
            unit   = Integer.parseInt(p[8]);
            connected = Integer.parseInt(p[9]);   // AA session up (0 = GalReceiver::shutdown fired)
            session   = Long.parseLong(p[10]);    // AA session epoch (bumps on each reconnect)
        } catch (NumberFormatException ex) {
            return;
        }
        String road = p[11];

        // New AA session (phone reconnected): drop any stale content and re-arm the seq tracker, so a
        // yank+replug never leaves the previous session's maneuver on the cluster.
        if (session != lastSession) {
            lastSession = session;
            lastSeq = -1L;
            lastStatus = -1;   // force a focus re-grab on the new session's first ACTIVE maneuver
            clearActive();
        }

        // Authoritative disconnect from the GAL library: AA session torn down -> clear immediately,
        // no timeout. The shim flushes connected=0 from its GalReceiver::shutdown hook (without bumping
        // seq), so this must be checked BEFORE the frozen-seq branch.
        if (connected == 0) {
            if (lastStatus == 1 && handler != null) {
                try { handler.navigationFocus(Constants.NAVFOCUS_NATIVE); } catch (Throwable t) {}
            }
            lastStatus = -1;   // non-active sentinel: forces a PROJECTED re-grab when guidance resumes
            lastSeq = seq;
            clearActive();
            return;
        }

        if (seq == lastSeq) {
            // Connected but no new maneuver (e.g. Waze frozen at a light). KEEP the last frame; the
            // long backstop only fires for a hard yank that produced no teardown call.
            maybeClearStale();
            return;
        }
        lastSeq = seq;
        lastSeqChangeMs = System.currentTimeMillis();

        // Verbosity is driven solely by Config.LOG_LEVEL (no private DEBUG flag). Compile-time constant
        // -> the per-poll string is not even built unless the jar is compiled at DEBUG/TRACE.
        if (Config.LOG_LEVEL <= MIBLogger.DEBUG) {
            MIBLogger.getInstance().debug("aa_nav seq=" + seq + " st=" + status + " conn=" + connected
                    + " sess=" + session + " event=" + event + " side=" + side + " angle=" + angle
                    + " number=" + number + " dist=" + dist + " time=" + time + " unit=" + unit
                    + " dest=" + taggedLine(lines, '4')   // raw line: this runs before it is applied
                    + " q4='" + quaternaryFor(event, number) + "' road=" + road);
        }

        // status: 1 ACTIVE, 3 REROUTING (1.7 only) -> guiding; anything else means guidance stopped
        // (arrived / route paused) though the channel is open. Rerouting MUST count as guiding: the
        // phone flips 1<->3 on every recalculation, several times a trip, and treating 3 as "stopped"
        // would blank the cluster each time.
        boolean rerouting = (status == 3);
        boolean guiding = (status == 1) || rerouting;
        // Track the NORMALISED status, so the 1<->3 flapping is not an edge: on the media path an
        // edge re-grabs the navigation focus, and doing that mid-recalculation would be gratuitous.
        int norm = guiding ? 1 : status;
        if (!guiding) {
            if (norm != lastStatus && handler != null) {
                try { handler.navigationFocus(Constants.NAVFOCUS_NATIVE); } catch (Throwable t) {}
            }
            lastStatus = norm;
            clearActive();
            return;
        }

        boolean statusEdge = (norm != lastStatus);
        lastStatus = norm;
        cleared = false;
        NavState.lanes = packLanes(taggedLine(lines, '2'));   // only for a frame we are about to publish
        String distText = taggedLine(lines, '3');  // phone-rendered distance, 1.7 only; null on 1.3
        applyDestination(taggedLine(lines, '4'));  // distance/time to destination, 1.7 only

        // Router: one parse, exactly one output, chosen by the runtime cluster type.
        if (navCapable()) {
            publishNavsd(event, side, angle, number, streetFor(rerouting, road), dist, time);
        } else {
            if (statusEdge && handler != null) {
                try { handler.navigationFocus(Constants.NAVFOCUS_PROJECTED); } catch (Throwable t) {}
            }
            publishMedia(event, side, number, streetFor(rerouting, road), dist, distText);
        }
    }

    // Nav-capable for the active stack. PQ (PQ audiosd shadow live): the mqbpq navsd functions are
    // built only on a nav-coded PQ unit, so their existence IS the signal. MQB: ClusterCaps.
    private boolean navCapable() {
        if (de.vw.mib.bap.mqbpq.audiosd.functions.CurrentStationInfo.isActive()) {
            return de.vw.mib.bap.mqbpq.navsd.functions.ManeuverDescriptor.isActive();
        }
        return ClusterCaps.isNavCapable();
    }

    /**
     * The street line while the phone recalculates — on both cluster paths, and only that line: the
     * glyph and the countdown stay the old route's, because a recalculation is over in a second or
     * two and blanking them would flicker on every one of the several per trip.
     */
    static String streetFor(boolean rerouting, String road) {
        if (rerouting) {
            return REROUTING_TEXT;
        }
        return road;
    }

    static final int MAIN_ELEMENT_FOLLOW_STREET = 11;

    /**
     * Whether the manoeuvre is close enough to draw; further out the cluster gets FOLLOW_STREET,
     * because AA names the manoeuvre as soon as it is next on the route — kilometres early.
     * 0 on either field means "the phone has not said"; neither known -> show it, as before.
     */
    static boolean maneuverImminent(int dist, int time) {
        if (time > 0 && time <= Config.MANEUVER_NEAR_SECONDS) {
            return true;
        }
        if (dist > 0 && dist <= Config.MANEUVER_NEAR_METERS) {
            return true;
        }
        return dist <= 0 && time <= 0;
    }

    // ===== nav-capable path: NavState + navsd shadows ==========================================
    private void publishNavsd(int event, int side, int angle, int number, String road, int dist, int time) {
        // Only the glyph waits: street, countdown and destination keep running meanwhile.
        boolean imminent = maneuverImminent(dist, time);
        if (imminent != lastImminent) {
            // Whether the phone sends a countdown at all cannot be read off the cluster.
            lastImminent = imminent;
            MIBLogger.getInstance().info("nav maneuver " + (imminent ? "shown" : "deferred (follow street)")
                    + ": dist=" + dist + "m time=" + time + "s");
        }
        int effAngle = effectiveAngle(event, angle, number);    // real bearing (Maps) or synth from exit number (Yandex)
        NavState.mainElement    = imminent ? mapMainElement(event, side) : MAIN_ELEMENT_FOLLOW_STREET;
        NavState.direction      = imminent ? mapDirection(event, side, effAngle) : 0;
        NavState.street         = (road != null) ? road : "";   // next-turn road -> TurnToInfo
        // Exit-road spoke on a roundabout, "" otherwise; also "" while far, or the junction geometry
        // would draw around a plain follow-street arrow.
        NavState.sideStreets    = imminent ? buildRingSideStreets(event, effAngle) : "";
        NavState.approaching    = imminent;
        NavState.distanceMeters = dist;                          // raw metres (stock formatter in the shadow)
        NavState.zLevelGuidance = 0;
        NavState.ACTIVE         = true;
        // MQB navsd shadows (no-op on PQ):
        ActiveRgType.poke();
        RGStatus.poke();
        ManeuverDescriptor.poke();
        DistanceToNextManeuver.poke();
        TurnToInfo.poke();
        LaneGuidance.poke();                 // fn 24
        DistanceToDestination.poke();        // fn 21
        TimeToDestination.poke();            // fn 22
        // PQ navsd shadows (no-op on MQB; PQ has no ActiveRgType/RGStatus):
        de.vw.mib.bap.mqbpq.navsd.functions.RouteGuidanceStatus.poke();
        de.vw.mib.bap.mqbpq.navsd.functions.ManeuverDescriptor.poke();
        de.vw.mib.bap.mqbpq.navsd.functions.DistanceToNextManeuver.poke();
        de.vw.mib.bap.mqbpq.navsd.functions.TurnToInfo.poke();
        de.vw.mib.bap.mqbpq.navsd.functions.DistanceTimeToDestination.poke();   // PQ merges fn 21+22
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
        if (event == 6) {
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
    private void publishMedia(int event, int side, int number, String road, int dist, String distText) {
        // Cluster layout (top->bottom): secondary, tertiary, PRIMARY(big), quaternary.
        //   secondary = street, or the lane row while there is one; tertiary = maneuver word
        //   (+ roundabout exit); PRIMARY = arrow + distance ("> 300 m"); quaternary = destination.
        String arrow = arrowForManeuver(event, side);
        String word = wordFor(event, side);
        String street = (road != null && road.length() > 0) ? road : "";
        // Prefer the phone's own string: it is already rounded the way the driver sees it on the
        // phone and, unlike ours, follows the phone's units (miles/yards). We only format the raw
        // metres when 1.3 or a 1.7 frame without the string leaves us nothing else. The nav-capable
        // path deliberately does NOT do this — there the cluster's own formatter owns the units.
        String distStr = (distText != null && distText.length() > 0) ? distText : formatDistance(dist);
        CurrentStationInfo.navPrimary = (distStr.length() > 0) ? (arrow + " " + distStr) : arrow;
        // The lane row takes the STREET line while the phone is sending lanes -- it is the widest
        // row (long street names live here), and the padded cells do not fit anywhere narrower. The
        // trade is deliberate: AA only sends lanes in the last few hundred metres, where the driver
        // can already see the junction and needs to know which lane far more than what it is called.
        // Exception: the rerouting notice keeps the row, because this is the one line that carries
        // it, and lanes held over from the route being recalculated are stale anyway.
        String lanes = laneRow(NavState.lanes);
        CurrentStationInfo.navSecondary =
                (lanes.length() > 0 && !REROUTING_TEXT.equals(street)) ? lanes : street;
        // The roundabout exit number rides with the manoeuvre word: "Roundabout exit 3" reads as one
        // phrase, and the exit is not worth a line of its own. (The PQ widget has always
        // concatenated the two into its third line, so this only makes MQB agree with it.)
        String exit = quaternaryFor(event, number);
        CurrentStationInfo.navTertiary = (exit.length() == 0) ? word
                : (word.length() > 0 ? (word + " " + exit) : exit);
        // How far the whole trip still has to go -- already parsed for this frame (processOnce), on
        // this path as much as on the nav-capable one; until now nothing read it here.
        CurrentStationInfo.navQuaternary =
                destinationRow(NavState.destinationMeters, NavState.destinationSeconds);
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

    private String quaternaryFor(int event, int number) {
        if ((event == 11 || event == 12 || event == 13) && number > 0) {
            return "exit " + number;
        }
        return "";
    }

    /**
     * Which lanes of a packed {@link NavState#lanes} array actually reach the driver, as indices
     * into it. The cluster shows at most six, and the stock navsd list thins an over-long row by
     * dropping NOT_RECOMMENDED lanes off the head first and the rest off the tail. Shared by both
     * output paths on purpose: one AA frame must not produce a six-element BAP array and a
     * differently-clamped text row. Empty when there are no lanes, or when the count disagrees with
     * the array length (a torn read that never happens, since the array is published whole).
     */
    static int[] selectLanes(int[] lanes) {
        int total = (lanes != null && lanes.length > 0) ? lanes[0] : 0;
        if (lanes == null || total > (lanes.length - 1) / 2) {
            total = 0;
        }
        int[] keep = new int[total < 6 ? total : 6];
        int drop = total - 6;
        int placed = 0;
        for (int i = 0; i < total; i++) {
            if (placed == 0 && drop > 0 && lanes[2 + 2 * i] == 0) {
                --drop;
                continue;                              // non-recommended lane off the head
            }
            if (placed < keep.length) {
                keep[placed++] = i;
            }
        }
        if (placed == keep.length) {
            return keep;
        }
        int[] out = new int[placed];
        System.arraycopy(keep, 0, out, 0, placed);
        return out;
    }

    /**
     * The lanes of the junction ahead as one text row: {@code "| x | x | > |"}. A lane that carries
     * the route shows its direction glyph, one that does not shows an {@code x}, and the bars read
     * as a cross-section of the road. "" when there are no lanes, so the caller can keep whatever
     * the row normally holds.
     *
     * The cells are padded. Without the spaces the glyphs and bars ran together into an unreadable
     * smear on the cluster (tried on the car 2026-07-30) — this font is proportional and its bar is
     * narrow. Six padded lanes come to 25 characters, which is why this goes in the street row
     * rather than the fourth slot: that one measures about 24.
     */
    static String laneRow(int[] lanes) {
        int[] keep = selectLanes(lanes);
        if (keep.length == 0) {
            return "";
        }
        StringBuffer b = new StringBuffer(4 * keep.length + 1);
        b.append('|');
        for (int i = 0; i < keep.length; i++) {
            int idx = keep[i];
            b.append(' ');
            b.append(lanes[2 + 2 * idx] != 0 ? laneGlyph(lanes[1 + 2 * idx]) : "x");
            b.append(" |");
        }
        return b.toString();
    }

    // Lane direction angle -> one of the four glyphs this font is known to render; the inverse of
    // laneShapeToDirection. The three sharpness levels per side collapse, because slight/normal/
    // sharp are not tellable apart in a one-character cell anyway. UNKNOWN packs to 0 exactly like
    // STRAIGHT, so it draws as an up triangle: the two are no longer distinguishable once packed,
    // and widening NavState for that alone would disturb the working navsd path.
    private static String laneGlyph(int direction) {
        switch (direction) {
            case 32: case 64: case 96:    return A_LEFT;
            case 160: case 192: case 224: return A_RIGHT;
            case 114: case 146:           return A_DOWN;
            default:                      return A_UP;     // 0 = STRAIGHT / UNKNOWN
        }
    }

    /**
     * The whole trip rather than the next manoeuvre — "36 min / 32 km" — for the media widget's
     * fourth slot whenever there are no lanes to put there. Plain ASCII separator on purpose: only
     * the six glyphs above are known to render in this font, and an unknown character draws as a
     * box. "" when the phone has said neither (protocol 1.3, or guidance that has just started).
     *
     * The distance is metric whatever the phone's units are: this is our own formatter, and the
     * phone's pre-rendered string (IPC line 3) is the distance to the next TURN, not to the
     * destination. The nav-capable path avoids the question entirely by handing raw metres to the
     * cluster's own formatter (see DistanceToDestination).
     */
    static String destinationRow(int meters, int seconds) {
        String time = formatRemaining(seconds);
        String dist = formatTripDistance(meters);
        if (time.length() == 0) {
            return dist;
        }
        if (dist.length() == 0) {
            return time;
        }
        return time + " / " + dist;
    }

    /**
     * How far the whole trip still has to go: "850 m" / "9,4 km" / "32 km". The tenth of a kilometre
     * that {@link #formatDistance} keeps is right for a manoeuvre a few hundred metres ahead and
     * noise on a number this size, so it is dropped past 10 km.
     */
    static String formatTripDistance(int meters) {
        if (meters >= 10000) {
            return ((meters + 500) / 1000) + " km";
        }
        return formatDistance(meters);
    }

    /** Remaining travel time as "36 min" / "1 h 25 min"; "" when unknown. */
    static String formatRemaining(int seconds) {
        if (seconds <= 0) {
            return "";
        }
        int[] hm = remainingHoursMinutes(seconds);
        if (hm[0] > 0) {
            return hm[0] + " h " + hm[1] + " min";
        }
        return hm[1] + " min";
    }

    /** "300 m" / "1,2 km" / "" when unknown. */
    static String formatDistance(int dist) {
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
    // Backstop for a hard yank with no teardown call (connected=0 and session change clear promptly
    // in processOnce): fires only when the connected flag is stuck at 1 past AA_STALE_BACKSTOP_MS.
    private void maybeClearStale() {
        if (cleared) {
            return;
        }
        if (System.currentTimeMillis() - lastSeqChangeMs > Config.AA_STALE_BACKSTOP_MS) {
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
        if (navCapable()) {
            try {
                NavState.ACTIVE = false;
                NavState.approaching = false;
                ActiveRgType.poke();
                RGStatus.poke();
                ManeuverDescriptor.poke();
                DistanceToNextManeuver.poke();
                TurnToInfo.poke();
                NavState.lanes = NO_LANES;
                LaneGuidance.poke();
                // Zero before poking: a stale destination outliving its route would otherwise be the
                // one number on the cluster nobody can tell is stale.
                NavState.destinationMeters = 0;
                NavState.destinationSeconds = 0;
                DistanceToDestination.poke();
                TimeToDestination.poke();
                de.vw.mib.bap.mqbpq.navsd.functions.RouteGuidanceStatus.poke();
                de.vw.mib.bap.mqbpq.navsd.functions.ManeuverDescriptor.poke();
                de.vw.mib.bap.mqbpq.navsd.functions.DistanceToNextManeuver.poke();
                de.vw.mib.bap.mqbpq.navsd.functions.TurnToInfo.poke();
                de.vw.mib.bap.mqbpq.navsd.functions.DistanceTimeToDestination.poke();
            } catch (Throwable t) {
                // ignore
            }
        } else {
            try {
                CurrentStationInfo.navPrimary = null;
                CurrentStationInfo.navSecondary = null;
                CurrentStationInfo.navTertiary = null;
                CurrentStationInfo.navQuaternary = null;
                // The fourth slot is fed from NavState now, so clear that too -- for the same
                // reason the nav-capable branch does: a destination that outlives its route is the
                // one number on the cluster nobody can tell is stale.
                NavState.lanes = NO_LANES;
                NavState.destinationMeters = 0;
                NavState.destinationSeconds = 0;
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

    /**
     * Line 1 is the manoeuvre (the shim normalises both protocol generations into it). The lines
     * after it are 1.7 extras, each tagged with its own marker and written only when it has content,
     * so they are matched by marker rather than by position: "2 " = lane guidance, "3 " = the phone's
     * own distance string, "4 " = distance/time to the destination. Any element may be null.
     */
    private String[] readLines() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(PATH));
            String[] out = new String[4];
            for (int i = 0; i < out.length; i++) {
                out[i] = br.readLine();
            }
            return out;
        } catch (Exception ex) {
            return null; // file absent yet / transient -> ignore
        } finally {
            if (br != null) {
                try { br.close(); } catch (Exception ex) {}
            }
        }
    }

    /** The extra line tagged with `marker`, without its 2-char prefix, or null if it isn't there. */
    static String taggedLine(String[] lines, char marker) {
        for (int i = 1; i < lines.length; i++) {
            String l = lines[i];
            if (l != null && l.length() > 2 && l.charAt(0) == marker && l.charAt(1) == ' ') {
                return l.substring(2);
            }
        }
        return null;
    }

    /**
     * Line 2 is "2 &lt;count&gt; &lt;lane0&gt; ...", each lane packed by the shim as
     * shape*2 + isHighlighted. The argument is the PAYLOAD taggedLine() already stripped the "2 "
     * marker from, i.e. "&lt;count&gt; &lt;lane0&gt; ...". Anything else clears the lanes.
     *
     * Angles are NOT clamped: laneDirection is a plain byte with no enumerated value set (unlike
     * laneType/guidanceInfo), and the stock navigation drives the full arrow family on a colour cluster.
     */
    static int[] packLanes(String payload) {
        int[] packed = null;
        int n = 0;
        if (payload != null && payload.length() > 0) {
            try {
                StringTokenizer st = new StringTokenizer(payload, " ");
                int count = Integer.parseInt(st.nextToken());
                if (count > NavState.MAX_LANES) {
                    count = NavState.MAX_LANES;                   // shim caps at 8; be defensive
                }
                if (count > 0) {
                    packed = new int[1 + 2 * count];
                    while (n < count && st.hasMoreTokens()) {
                        int lane = Integer.parseInt(st.nextToken());
                        packed[1 + 2 * n] = laneShapeToDirection(lane >> 1);
                        // AA gives a boolean per lane and several can be set at once, so "the single
                        // best" is not expressible: every highlighted lane becomes BEST_RECOMMENDATION.
                        packed[2 + 2 * n] = ((lane & 1) != 0) ? 2 : 0;
                        n++;
                    }
                }
            } catch (Exception ex) {
                n = 0;                                            // malformed -> treat as "no lanes"
            }
        }
        if (n == 0) {
            return NO_LANES;
        }
        packed[0] = n;
        return packed;
    }

    /**
     * Line 4, payload "&lt;metres&gt; &lt;seconds&gt;" (the marker is already stripped): distance and
     * remaining travel time to the destination. Absent on 1.3 and whenever the phone has not said —
     * then both go to 0 and the two cluster functions report themselves invalid, which is what the
     * cluster shows when its own navigation has no route either.
     */
    static int[] packDestination(String payload) {
        int meters = 0;
        int seconds = 0;
        if (payload != null && payload.length() > 0) {
            try {
                StringTokenizer st = new StringTokenizer(payload, " ");
                meters = Integer.parseInt(st.nextToken());
                seconds = Integer.parseInt(st.nextToken());
                if (meters < 0 || seconds < 0) {
                    meters = 0;
                    seconds = 0;
                }
            } catch (Exception ex) {
                meters = 0;                                   // malformed -> "not known"
                seconds = 0;
            }
        }
        return new int[]{ meters, seconds };
    }

    private void applyDestination(String payload) {
        int[] d = packDestination(payload);
        NavState.destinationMeters = d[0];
        NavState.destinationSeconds = d[1];
    }

    /**
     * Remaining travel time in seconds -&gt; the {hour, minute} pair TimeToDestination sends. Rounded
     * to the nearest minute the way a nav engine counts down, and clamped: TimeInfo.serialize pushes
     * both as a single byte, and a route long enough to overflow that is not one this line is meant
     * to show.
     */
    public static int[] remainingHoursMinutes(int seconds) {   // public: the PQ shadow lives in another package
        if (seconds <= 0) {
            return new int[]{ 0, 0 };
        }
        int minutes = (seconds + 30) / 60;
        int hours = minutes / 60;
        minutes = minutes - hours * 60;
        if (hours > 99) {
            hours = 99;
            minutes = 59;
        }
        return new int[]{ hours, minutes };
    }

    /** AA lane shape -&gt; VW laneDirection, the same 0..255 CCW scale the manoeuvre arrow uses. */
    static int laneShapeToDirection(int shape) {
        switch (shape) {
            case 1:  return 0;      // STRAIGHT
            case 2:  return 32;     // SLIGHT_LEFT
            case 3:  return 224;    // SLIGHT_RIGHT
            case 4:  return 64;     // NORMAL_LEFT
            case 5:  return 192;    // NORMAL_RIGHT
            case 6:  return 96;     // SHARP_LEFT
            case 7:  return 160;    // SHARP_RIGHT
            case 8:  return 114;    // U_TURN_LEFT
            case 9:  return 146;    // U_TURN_RIGHT
            default: return 0;      // UNKNOWN
        }
    }
}
