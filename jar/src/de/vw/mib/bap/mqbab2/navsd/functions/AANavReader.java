package de.vw.mib.bap.mqbab2.navsd.functions;

import de.aatokombi.Config;
import de.aatokombi.Strings;
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
 * On a nav-capable cluster, publishNavsd() additionally builds a multi-spoke junction schematic
 * (JunctionSchematic.buildRoundabout / buildOrdinary) instead of the single-glyph roundabout spoke
 * -- see the `schematic` gate at the top of that method: Config.SHOW_JUNCTION_SCHEMATIC &&
 * ClusterCaps.isNavCapable() (see Config.SHOW_JUNCTION_SCHEMATIC's own doc).
 * Every time the schematic path actually fires it hex-dumps the resulting mainElement/sidestreets
 * bytes via logSchematic(), at DEBUG (Config.LOG_LEVEL) -- the fastest way to check what a unit was
 * actually sent, against what it renders, without a BAP sniffer.
 *
 * Java 1.4 (cf48): no generics, no autoboxing, no enhanced-for. Must never throw into the HMI.
 */
public final class AANavReader implements Runnable {

    private static final String PATH = "/dev/shmem/aa_nav";
    private static final long POLL_MS = 300L;
    // The rerouting notice of the MEDIA path (the nav-capable path uses the stock RECALC_ROUTE glyph
    // instead), in the cluster's language like the manoeuvre words (de.aatokombi.Strings).
    private static String reroutingText() {
        return Strings.get(Strings.REROUTING);
    }

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
    private final RingMemory ring = new RingMemory();   // roundabout exit-step continuity (see RingMemory)

    /**
     * Google Maps splits a roundabout in two steps: "enter and leave at exit N, bearing A" while
     * approaching, then -- once the car is on the ring -- a separate ROUNDABOUT_EXIT step with NO
     * exit number and NO bearing (seen 2026-10: type 35 exit 3 / 225 deg, then type 46 alone for the
     * last ~200 m). Drawn as is, the cluster's roundabout lost its exit mid-roundabout. This keeps
     * the geometry of the ring step for the exit step that directly follows it; an exit step with no
     * ring step before it (route joined on the ring, after a recalculation) is flagged instead, and
     * the caller draws the stock EXIT_ROUNDABOUT glyph. Host-testable, one instance per reader.
     */
    static final class RingMemory {
        private int lastEvent = 0;        // event of the current step
        private int prevStepEvent = 0;    // event of the step before it
        private boolean valid = false;    // angle/number/side below belong to the current ring
        private int angle = 0;
        private int number = 0;
        private int side = 0;

        /**
         * @return {angle, number, exitOnly(0/1), side} to use for this frame. The exit step that
         * follows a ring step also keeps the RING's side: 2GIS sends its "leave the roundabout" step
         * as the clockwise variant even on a counter-clockwise ring (2026-10), which flipped the
         * glyph to ROUNDABOUT_TRS_LEFT halfway round.
         */
        int[] apply(int event, int sideIn, int angleIn, int numberIn) {
            if (event != lastEvent) {
                prevStepEvent = lastEvent;
                lastEvent = event;
            }
            boolean ringStep = (event == 11 || event == 13);
            if (ringStep) {
                if (angleIn > 0 || numberIn > 0) {
                    valid = true;
                    angle = angleIn;
                    number = numberIn;
                    side = sideIn;
                }
                return new int[]{ angleIn, numberIn, 0, sideIn };
            }
            if (event == 12) {
                boolean sameRing = valid && (prevStepEvent == 11 || prevStepEvent == 13);
                if (angleIn > 0 || numberIn > 0) {
                    return new int[]{ angleIn, numberIn, 0, sameRing ? side : sideIn };  // its own geometry
                }
                if (sameRing) {
                    return new int[]{ angle, number, 0, side };        // same ring, keep its picture
                }
                return new int[]{ 0, 0, 1, sideIn };                   // nothing to draw it from
            }
            valid = false;                                             // left the ring
            return new int[]{ angleIn, numberIn, 0, sideIn };
        }

        void reset() {
            lastEvent = 0;
            prevStepEvent = 0;
            valid = false;
        }
    }

    static final int MAIN_ELEMENT_EXIT_ROUNDABOUT_TRS_RIGHT = 26;
    static final int MAIN_ELEMENT_EXIT_ROUNDABOUT_TRS_LEFT = 27;

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
            MIBLogger.getInstance().info("AANavReader started, polling " + PATH
                    + ", cluster language '" + Strings.language() + "'");
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
                    + " lanes=" + taggedLine(lines, '2')  // raw shim payload: "<count> <lane0> <lane1> ..."
                    + " next=" + taggedLine(lines, '5')   // raw shim payload: "<event> <side> <angle> <number>"
                    + " cur='" + taggedLine(lines, '6') + "'"  // the road the car is on now
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
        NavState.laneState = packLaneState(taggedLine(lines, '2')); // only for a frame we are about to publish
        String distText = taggedLine(lines, '3');  // phone-rendered distance, 1.7 only; null on 1.3
        applyDestination(taggedLine(lines, '4'));  // distance/time to destination, 1.7 only
        String eta = taggedLine(lines, '8');       // the phone's arrival clock string, 1.7 only
        NavState.destinationEta = (eta != null) ? eta : "";

        // Router: one parse, exactly one output, chosen by the runtime cluster type.
        if (navCapable()) {
            if (statusEdge) {
                // Must run BEFORE publishNavsd() below, so the very first ActiveRgType.poke() of
                // this guidance session already reports the right rgtype instead of whatever was
                // left over from a previous session.
                ActiveRgType.primeForAAStart(Config.FORCE_MAP_ON_KOMBI);
            }
            // No rerouting TEXT here: the cluster gets the stock RECALC_ROUTE glyph instead and
            // renders its own, locale-dependent caption for it (see publishNavsd).
            publishNavsd(event, side, angle, number, road, dist, time,
                    packNextManeuver(taggedLine(lines, '5')), taggedLine(lines, '6'), rerouting);
        } else {
            if (statusEdge && handler != null) {
                try { handler.navigationFocus(Constants.NAVFOCUS_PROJECTED); } catch (Throwable t) {}
            }
            publishMedia(event, side, number, streetFor(rerouting, road), dist, distText,
                    taggedLine(lines, '9'));
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
            return reroutingText();
        }
        return road;
    }

    static final int MAIN_ELEMENT_RECALC_ROUTE = 10;   // ASL MAIN_ELEMENT C10
    static final int MAIN_ELEMENT_FOLLOW_STREET = 11;
    static final int MAIN_ELEMENT_TURN = 13;
    static final int MAIN_ELEMENT_TURN_ON_MAINROAD = 14;
    // The stock "get ready" glyphs (ASL MAIN_ELEMENT C28..C31, confirmed by name in the generated
    // ASLNavigationBapPropertyManager constants).
    static final int MAIN_ELEMENT_DOUBLE_TURN = 35;    // ASL C35 / BAP 0x23, drawn only at 64 / 192
    static final int MAIN_ELEMENT_PREPARE_TURN = 28;

    /**
     * DOUBLE_TURN direction for a near manoeuvre, or -1: only a left/right TURN followed by a next
     * step (AA steps[1]) that is a turn to the OTHER side. 64 = "left, then right", 192 = "right,
     * then left" (AID-verified; the PQ shim in MIBHMI decomposes it the same way).
     */
    static int doubleTurnDirection(int mainElement, int side, int[] next) {
        if (mainElement != MAIN_ELEMENT_TURN || next == null || (side != 1 && side != 2)) {
            return -1;
        }
        int nextSide = next[1];
        if (mapMainElement(next[0], nextSide) != MAIN_ELEMENT_TURN || nextSide == side
                || (nextSide != 1 && nextSide != 2)) {
            return -1;
        }
        return (side == 1) ? 64 : 192;
    }
    static final int MAIN_ELEMENT_PREPARE_ROUNDABOUT = 29;
    static final int MAIN_ELEMENT_PREPARE_SQUARE = 30;
    static final int MAIN_ELEMENT_PREPARE_UTURN = 31;

    /**
     * Middle stage (Config.MANEUVER_PREPARE_*): not near yet, but inside either prepare gate.
     * Only meaningful when maneuverImminent() is false; 0 on a field = "the phone has not said".
     */
    static boolean maneuverPreparing(int dist, int time) {
        if (time > 0 && time <= Config.MANEUVER_PREPARE_SECONDS) {
            return true;
        }
        return dist > 0 && dist <= Config.MANEUVER_PREPARE_METERS;
    }

    /**
     * The direction a PREPARE_* glyph can actually be drawn with. The stock renderer (MIBHMI
     * Guidance.doPrepareTurn / doPrepareUTurn) knows PREPARE_TURN only at 0 / 64 / 192 and
     * PREPARE_UTURN only at 64 / 192 -- anything else draws NOTHING, which the AID did for every
     * slight (224/32) and sharp (96/160) turn in the prepare stage (2026-10). So the turn's own
     * direction is folded onto its side; PREPARE_ROUNDABOUT / PREPARE_SQUARE ignore it anyway.
     */
    // Directions each glyph has an image for, from the stock renderer (MIBHMI de.vw.mib.widgets.Guidance
    // do<Glyph>()); any other direction draws nothing at all. null = the glyph ignores direction.
    private static final int[] DIRS_TURN = { 0, 32, 64, 96, 160, 192, 224 };
    private static final int[] DIRS_RING = { 0, 16, 32, 48, 64, 80, 96, 112, 128, 144, 160, 176, 192, 208, 224, 240 };
    private static final int[] DIRS_SIDES = { 64, 192 };
    private static final int[] DIRS_SIDES_AHEAD = { 0, 64, 192 };
    private static final int[] DIRS_EXIT_RIGHT = { 0, 192, 224 };
    private static final int[] DIRS_EXIT_LEFT = { 0, 32, 64 };

    static int[] renderableDirections(int mainElement) {
        switch (mainElement) {
            case 13: case 14:                   return DIRS_TURN;         // TURN, TURN_ON_MAINROAD
            case 21: case 22: case 23: case 24: return DIRS_RING;         // ROUNDABOUT_TRS_*, SQUARE_TRS_*
            case 12: case 19: case 25: case 31:
            case 34: case 35:                   return DIRS_SIDES;        // CHANGE_LANE, FORK_2, UTURN, PREPARE_UTURN, MICHIGAN, DOUBLE
            case 20: case 28:                   return DIRS_SIDES_AHEAD;  // FORK_3, PREPARE_TURN
            case 15: case 32:                   return DIRS_EXIT_RIGHT;   // EXIT_RIGHT(_RAMP)
            case 16: case 33:                   return DIRS_EXIT_LEFT;    // EXIT_LEFT(_RAMP)
            default:                            return null;
        }
    }

    /** The nearest direction (on the 0..255 circle) the glyph can draw; unchanged if it ignores it. */
    static int renderableDirection(int mainElement, int direction) {
        int[] set = renderableDirections(mainElement);
        if (set == null) {
            return direction;
        }
        int d = direction & 0xFF;
        int best = set[0];
        int bestDist = 256;
        for (int i = 0; i < set.length; i++) {
            int diff = Math.abs(set[i] - d);
            if (diff > 128) {
                diff = 256 - diff;
            }
            if (diff < bestDist) {
                bestDist = diff;
                best = set[i];
            }
        }
        return best;
    }

    static int prepareDirection(int prepareElement, int direction) {
        if (prepareElement != MAIN_ELEMENT_PREPARE_TURN && prepareElement != MAIN_ELEMENT_PREPARE_UTURN) {
            return direction;
        }
        int d = direction & 0xFF;
        if (d == 0 || d == 128) {
            return (prepareElement == MAIN_ELEMENT_PREPARE_TURN) ? 0 : 64;
        }
        return (d < 128) ? 64 : 192;
    }

    /**
     * The prepare glyph for a manoeuvre glyph, or -1 when the cluster has none for it (exit, fork,
     * merge, arrival...): those stay FOLLOW_STREET until they are near.
     */
    static int prepareElementFor(int mainElement) {
        switch (mainElement) {
            case 13:                               // TURN
            case 14: return MAIN_ELEMENT_PREPARE_TURN;        // TURN_ON_MAINROAD
            case 21:                               // ROUNDABOUT_TRS_RIGHT
            case 22: return MAIN_ELEMENT_PREPARE_ROUNDABOUT;  // ROUNDABOUT_TRS_LEFT
            case 23:                               // SQUARE_TRS_RIGHT
            case 24: return MAIN_ELEMENT_PREPARE_SQUARE;      // SQUARE_TRS_LEFT
            case 25: return MAIN_ELEMENT_PREPARE_UTURN;       // UTURN
            default: return -1;
        }
    }

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
    private void publishNavsd(int event, int side, int angle, int number, String road, int dist, int time,
                              int[] next, String currentRoad, boolean rerouting) {
        de.aatokombi.MediaViewMode.guidance(false);
        // Only the glyph waits: street, countdown and destination keep running meanwhile.
        boolean imminent = maneuverImminent(dist, time);
        if (imminent != lastImminent) {
            // Whether the phone sends a countdown at all cannot be read off the cluster.
            lastImminent = imminent;
            MIBLogger.getInstance().info("nav maneuver " + (imminent ? "shown" : "deferred (follow street)")
                    + ": dist=" + dist + "m time=" + time + "s");
        }
		
        int[] ringFix = ring.apply(event, side, angle, number);
        angle = ringFix[0];
        number = ringFix[1];
        side = ringFix[3];
        boolean exitOnlyStep = ringFix[2] == 1;
        int effAngle = effectiveAngle(event, angle, number);    // real bearing (Maps) or synth from exit number (Yandex)
        // The manoeuvre's own glyph. It is what maneuver_1 shows once the manoeuvre is near; while it
        // is still far, maneuver_1 shows FOLLOW_STREET and this glyph can only appear as the preview
        // in maneuver_2 (Config.SHOW_NEXT_MANEUVER) -- so it is only worth building in those cases.
        int mainElement = mapMainElement(event, side);
        int direction = mapDirection(event, side, effAngle);
        if (exitOnlyStep) {
            // A bare "leave the roundabout" with no ring geometry to keep: the stock exit glyph.
            mainElement = (side == 1) ? MAIN_ELEMENT_EXIT_ROUNDABOUT_TRS_LEFT : MAIN_ELEMENT_EXIT_ROUNDABOUT_TRS_RIGHT;
            direction = 0;
        }
		// Exit-road spoke on a roundabout, "" otherwise.
        String sideStreets = buildRingSideStreets(event, effAngle);
		
		if (imminent || Config.SHOW_NEXT_MANEUVER) {
			// Multi-spoke junction schematic (JunctionSchematic) -- STRICTLY gated on top of the
			// compile-time switch: only on a cluster that is nav-capable (navsd is actually driving
			// this cluster at all).
			boolean schematic = Config.SHOW_JUNCTION_SCHEMATIC
					&& ClusterCaps.isNavCapable();

			if (schematic && !exitOnlyStep && (event == 11 || event == 12 || event == 13)) {
				// Roundabout: always meaningful here, since AA gives an exit number on every roundabout
				// maneuver regardless of whether lane data is also available.
				sideStreets = JunctionSchematic.buildRoundabout(effAngle, number,
						mainElement != 22);                // 22 = ROUNDABOUT_TRS_LEFT (clockwise ring)
				logSchematic("roundabout", mainElement, direction, sideStreets);
			} else if (schematic && (mainElement == MAIN_ELEMENT_FOLLOW_STREET
					|| mainElement == MAIN_ELEMENT_TURN || mainElement == MAIN_ELEMENT_TURN_ON_MAINROAD)) {
				// Ordinary follow/turn: attach side streets to the SAME mainElement mapMainElement()
				// already picked -- deliberately NOT SQUARE_TRS_LEFT/RIGHT (VW's glyph for a large
				// plaza/square-shaped junction, not an ordinary road crossing; see
				// JunctionSchematic.buildOrdinary()). Only worth it once there are at least two distinct
				// lane directions to draw (buildOrdinary returns null otherwise); until the phone starts
				// sending lanes for this junction this falls through to the plain arrow, exactly as
				// before -- mainElement itself is never touched either way.
                String ordinary = JunctionSchematic.buildOrdinaryFromLaneState(mainElement, NavState.laneState);
				if (ordinary != null) {
					sideStreets = ordinary;
					logSchematic("ordinary", mainElement, direction, sideStreets);
				}
			}
		}

        // Second, smaller arrow (maneuver_2): the manoeuvre after this one, or this one as a preview.
        // In the prepare stage maneuver_1 already shows this manoeuvre, so maneuver_2 behaves as near.
        boolean preparing = !imminent && maneuverPreparing(dist, time)
                && prepareElementFor(mainElement) >= 0;
        boolean shown = imminent || preparing;     // maneuver_1 names the manoeuvre itself
        NavState.nextManeuver   = Config.SHOW_NEXT_MANEUVER
                ? nextManeuverFor(shown, mainElement, direction, sideStreets, next) : null;
        int doubleDir = (Config.SHOW_DOUBLE_TURN && imminent && !rerouting)
                ? doubleTurnDirection(mainElement, side, next) : -1;
        if (doubleDir >= 0) {
            mainElement = MAIN_ELEMENT_DOUBLE_TURN;
            direction = doubleDir;
            sideStreets = "";
        }
        if (preparing) {
            // Middle stage: the stock "get ready" glyph turned the manoeuvre's way, no junction
            // geometry yet (the stock validator clears sidestreets for these glyphs anyway).
            mainElement = prepareElementFor(mainElement);
            direction = prepareDirection(mainElement, direction);
            sideStreets = "";
        } else if (!imminent) {
            // Far: maneuver_1 is a plain "follow the street", with no junction geometry around it.
            mainElement = MAIN_ELEMENT_FOLLOW_STREET;
            direction = 0;
            sideStreets = "";
        }
        if (rerouting) {
            // The phone is recalculating (status 3): the stock "recalculating route" glyph, as the
            // unit's own navigation shows it, with nothing of the route being replaced around it --
            // no second arrow, no street (the cluster captions RECALC_ROUTE itself, in its own
            // language), no countdown, no lanes. Shown exactly as long as the phone says so.
            mainElement = MAIN_ELEMENT_RECALC_ROUTE;
            direction = 0;
            sideStreets = "";
            NavState.nextManeuver = null;
            dist = 0;
            imminent = false;
            shown = false;
            NavState.laneState = NavState.NO_LANES;
        }
        direction = renderableDirection(mainElement, direction);   // never a direction the glyph lacks
        NavState.mainElement    = mainElement;
        NavState.direction      = direction;
        NavState.currentRoad    = (currentRoad != null) ? currentRoad : "";
        // The street line names the road the manoeuvre turns into only once it is near; while the
        // arrow is FOLLOW_STREET or the PREPARE_* glyph it names the road the car is on (user
        // request 2026-10). AA's current road when the phone sends one; otherwise TurnToInfo asks
        // the unit's own map-matching for it (NavState.streetWantsCurrentPosition).
        NavState.street         = rerouting ? ""
                : turnToStreet(road, currentRoad, imminent);                      // -> TurnToInfo
        NavState.nextRoad       = (road != null) ? road : "";
        NavState.streetWantsCurrentPosition = !rerouting && !imminent
                && (currentRoad == null || currentRoad.length() == 0);
        NavState.sideStreets    = sideStreets;
        NavState.approaching    = imminent;
        NavState.distanceMeters = dist;                          // raw metres (stock formatter in the shadow)
        NavState.zLevelGuidance = 0;
        NavState.ACTIVE         = true;
        if (Config.LOG_LEVEL <= MIBLogger.DEBUG) {
            // What actually goes out on BAP this frame -- the other half of the shim's trip dump.
            NavState.NextManeuver nm = NavState.nextManeuver;
            MIBLogger.getInstance().debug("navsd out: imminent=" + imminent + " prep=" + preparing
                    + (rerouting ? " REROUTE" : "")
                    + " m1=" + mainElement + "/" + direction + " ss=" + sideStreets.length()
                    + " m2=" + (nm == null ? "-" : (nm.mainElement + "/" + nm.direction))
                    + " street='" + NavState.street + "' pos='" + NavState.currentRoad + "'");
        }
        // MQB navsd shadows (no-op on PQ):
        ActiveRgType.poke();
        RGStatus.poke();
        ManeuverDescriptor.poke();
        DistanceToNextManeuver.poke();
        TurnToInfo.poke();
        CurrentPositionInfo.poke();          // fn 19
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

    /**
     * The TurnToInfo line. While the manoeuvre is still far the cluster shows FOLLOW_STREET, and the
     * road you are following is the natural caption for it -- so the CURRENT road (IPC line 6) goes
     * there, and only once the manoeuvre is near does the line switch to the road it turns into.
     * The rerouting notice always wins (it arrives here in place of the road, see streetFor), and with
     * no current road known the next-turn road stays, as before.
     */
    static String turnToStreet(String nextRoad, String currentRoad, boolean imminent) {
        String next = (nextRoad != null) ? nextRoad : "";
        if (imminent || reroutingText().equals(next)) {
            return next;
        }
        if (currentRoad != null && currentRoad.length() > 0) {
            return currentRoad;
        }
        return next;
    }

    /**
     * What the small second arrow (ManeuverDescriptor.maneuver_2) shows, or null for none.
     *   far  -> the current manoeuvre itself (cur*), previewing what the FOLLOW_STREET arrow leads to;
     *   near -> the manoeuvre after it (IPC line 5, i.e. AA steps[1]), as the stock "then" arrow.
     * Another "follow the street" is never worth a second arrow, so that case is null too.
     */
    static NavState.NextManeuver nextManeuverFor(boolean imminent, int curMain, int curDir,
                                                 String curSideStreets, int[] next) {
        if (!imminent) {
            if (curMain == MAIN_ELEMENT_FOLLOW_STREET) {
                return null;
            }
            return new NavState.NextManeuver(curMain, curDir, curSideStreets);
        }
        if (next == null) {
            return null;
        }
        int event = next[0];
        int side = next[1];
        int effAngle = effectiveAngle(event, next[2], next[3]);
        int main = mapMainElement(event, side);
        if (main == MAIN_ELEMENT_FOLLOW_STREET) {
            return null;
        }
        return new NavState.NextManeuver(main, mapDirection(event, side, effAngle),
                buildRingSideStreets(event, effAngle));
    }

    // Debug-only: logs the raw bytes that go into ManeuverDescriptor.sidestreets (plus mainElement
    // and the arrow's own direction, which nothing else currently logs) whenever the schematic path
    // actually fires (i.e. the `schematic` gate in publishNavsd() was true AND there was enough data
    // to build one), so a first run on a new/untested unit shows exactly what was sent in
    // aatokombi.log -- without needing a BAP sniffer -- to check against what actually renders on the
    // cluster. Gated on Config.LOG_LEVEL like every other debug line in this class: the hex-dump is
    // not built at all unless it will be printed, so this costs nothing on a normal (INFO) build.
    private static void logSchematic(String kind, int mainElement, int direction, String sideStreets) {
        if (Config.LOG_LEVEL > MIBLogger.DEBUG) {
            return;
        }
        StringBuffer hex = new StringBuffer(3 * sideStreets.length());
        for (int i = 0; i < sideStreets.length(); i++) {
            if (i > 0) {
                hex.append(' ');
            }
            int v = sideStreets.charAt(i) & 0xFF;
            if (v < 16) {
                hex.append('0');
            }
            hex.append(Integer.toHexString(v));
        }
        MIBLogger.getInstance().debug("junction schematic (" + kind + "): mainElement=" + mainElement
                + " direction=" + direction
                + " sidestreets[" + sideStreets.length() + "]={" + hex + "}");
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
        if (event == 17 || event == 18 || event == 19) {
            // Arrival (ARRIVED glyph): the shim folds DESTINATION_LEFT/RIGHT onto 17 + side, so the
            // side is the only place "the destination is on your left/right" survives. Turned the way
            // a turn would be; straight/unspecified stays 0. [HW-TUNE: whether the AID rotates the
            // ARRIVED glyph at all is unverified]
            if (side == 1) return 64;
            if (side == 2) return 192;
            return 0;
        }
        if (event == 1 || event == 2 || event == 14) {
            return 0;                              // depart / name-change / straight
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

    // Roundabout exit-road geometry for the maneuver glyph (col3 sidestreets), used when the
    // JunctionSchematic path is off (Config.SHOW_JUNCTION_SCHEMATIC=false, or the `schematic` gate
    // at the top of publishNavsd() is false) -- see JunctionSchematic.buildRoundabout() for the
    // richer multi-spoke string this class normally builds instead. One SIDE_STREET_*
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
    private void publishMedia(int event, int side, int number, String road, int dist, String distText,
                              String cue) {
        de.aatokombi.MediaViewMode.guidance(true);
        // Cluster layout (top->bottom): secondary, tertiary, PRIMARY(big), quaternary.
        //   secondary = street, or the lane row while there is one; tertiary = maneuver word
        //   (+ roundabout exit); PRIMARY = arrow + distance ("> 300 m"); quaternary = destination.
        String arrow = arrowForManeuver(event, side);
        // The manoeuvre line: the navigator's own instruction text when it says more than the street
        // (IPC line 9 -- the shim only writes it then), otherwise our word in the cluster's language.
        String word = (cue != null && cue.length() > 0 && !cue.equals(road)) ? cue : wordFor(event, side);
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
        // The media widget deliberately stays one glyph per cell. Combined-lane alternatives are
        // preserved for navsd geometry/BAP, but rendering several glyphs here makes the already
        // tight six-cell row illegible.
        String lanes = laneRow(NavState.laneState.lanes);
        CurrentStationInfo.navSecondary =
                (lanes.length() > 0 && !reroutingText().equals(street)) ? lanes : street;
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

    static String wordFor(int event, int side) {
        switch (event) {
            case 1:  return Strings.get(Strings.START);
            case 2:  return Strings.get(Strings.CONTINUE);
            case 3:  return side == 1 ? Strings.get(Strings.BEAR_LEFT) : side == 2 ? Strings.get(Strings.BEAR_RIGHT) : Strings.get(Strings.CONTINUE);
            case 4:  return side == 1 ? Strings.get(Strings.TURN_LEFT) : side == 2 ? Strings.get(Strings.TURN_RIGHT) : Strings.get(Strings.TURN);
            case 5:  return side == 1 ? Strings.get(Strings.SHARP_LEFT) : side == 2 ? Strings.get(Strings.SHARP_RIGHT) : Strings.get(Strings.TURN);
            case 6:  return Strings.get(Strings.U_TURN);
            case 7:  return Strings.get(Strings.ON_RAMP);
            case 8:  return Strings.get(Strings.EXIT);
            case 9:  return Strings.get(Strings.FORK);
            case 10: return Strings.get(Strings.MERGE);
            case 11:
            case 12:
            case 13: return Strings.get(Strings.ROUNDABOUT);
            case 14: return Strings.get(Strings.STRAIGHT);
            case 15:
            case 16: return Strings.get(Strings.FERRY);
            case 17: return Strings.get(Strings.ARRIVE);
            default: return "";
        }
    }

    private String quaternaryFor(int event, int number) {
        if ((event == 11 || event == 12 || event == 13) && number > 0) {
            return Strings.get(Strings.RING_EXIT) + " " + number;
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

    /** All raw VW-angle directions for one lane, with a safe primary-only fallback. */
    static int[] laneDirections(NavState.LaneState state, int laneIndex) {
        if (state != null && state.directions != null && laneIndex >= 0
                && laneIndex < state.directions.length && state.directions[laneIndex] != null
                && state.directions[laneIndex].length > 0) {
            return state.directions[laneIndex];
        }
        if (state != null && state.lanes != null && laneIndex >= 0
                && 1 + 2 * laneIndex < state.lanes.length) {
            return new int[]{ state.lanes[1 + 2 * laneIndex] };
        }
        return new int[0];
    }

    /** BAP laneSidestreets encoding for every alternative beyond laneDirection. */
    static String laneSideStreets(NavState.LaneState state, int laneIndex) {
        int[] directions = laneDirections(state, laneIndex);
        if (directions.length < 2) {
            return "";
        }
        int primary = (state != null && state.lanes != null && 1 + 2 * laneIndex < state.lanes.length)
                ? state.lanes[1 + 2 * laneIndex] : directions[0];
        StringBuffer out = new StringBuffer(directions.length - 1);
        for (int i = 0; i < directions.length; i++) {
            if (directions[i] != primary && out.toString().indexOf((char) directions[i]) < 0) {
                out.append((char) directions[i]);
            }
        }
        return out.toString();
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
            return ((meters + 500) / 1000) + " " + Strings.get(Strings.UNIT_KM);
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
            return hm[0] + " " + Strings.get(Strings.UNIT_H) + " " + hm[1] + " " + Strings.get(Strings.UNIT_MIN);
        }
        return hm[1] + " " + Strings.get(Strings.UNIT_MIN);
    }

    /** "300 m" / "1,2 km" / "" when unknown. */
    static String formatDistance(int dist) {
        if (dist <= 0) {
            return "";
        }
        if (dist < 1000) {
            if (dist < 10) {
                return dist + " " + Strings.get(Strings.UNIT_M);   // very short: show the exact metres
            }
            return (((dist + 5) / 10) * 10) + " " + Strings.get(Strings.UNIT_M);   // else round to nearest 10 m, like Google
        }
        return (dist / 1000) + "," + ((dist % 1000) / 100) + " " + Strings.get(Strings.UNIT_KM);
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
        de.aatokombi.MediaViewMode.guidance(false);
        if (cleared) {
            return;
        }
        cleared = true;
        ring.reset();
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
                NavState.laneState = NavState.NO_LANES;
                NavState.nextManeuver = null;
                NavState.currentRoad = "";
                CurrentPositionInfo.poke();
                LaneGuidance.poke();
                // Zero before poking: a stale destination outliving its route would otherwise be the
                // one number on the cluster nobody can tell is stale.
                NavState.destinationMeters = 0;
                NavState.destinationSeconds = 0;
                NavState.destinationEta = "";
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
                NavState.laneState = NavState.NO_LANES;
                NavState.destinationMeters = 0;
                NavState.destinationSeconds = 0;
                NavState.destinationEta = "";
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
     * own distance string, "4 " = distance/time to the destination, "5 " = the manoeuvre after the
     * current one, "6 " = the road the car is on now, "7 " = the destination address (not used by
     * the jar since DestinationInfo was rolled back),
     * "8 " = the phone's arrival clock string,
     * "9 " = the instruction text when it differs from the road. Lane line "2v " is the current
     * variable-direction encoding; "2 " remains accepted for a jar deployed with an older shim.
     * Any element may be null.
     */
    private String[] readLines() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(PATH));
            // Line 1 plus every tagged line the shim may write (2v, 3, 4, 5, ...), with headroom: a
            // marker that does not fit here would silently never be seen.
            String[] out = new String[12];
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
            if (l != null && l.length() > 2 && l.charAt(0) == marker
                    && (l.charAt(1) == ' ' || (marker == '2' && l.length() > 3
                    && l.charAt(1) == 'v' && l.charAt(2) == ' '))) {
                return (marker == '2' && l.charAt(1) == 'v') ? l.substring(1) : l.substring(2);
            }
        }
        return null;
    }

    /**
     * Backward-compatible primary-arrow view of {@link #packLaneState(String)}. Existing unit tests
     * and callers that only understand one arrow per lane can keep using this helper.
     *
     * Angles are NOT clamped: laneDirection is a plain byte with no enumerated value set (unlike
     * laneType/guidanceInfo), and the stock navigation drives the full arrow family on a colour cluster.
     *
     */
    static int[] packLanes(String payload) {
        return packLaneState(payload).lanes;
    }

    /**
     * IPC lane decoder. Old "2 count packed..." frames become one direction per lane. New frames
     * are "2v count dirCount packed... dirCount packed...": `packed = shape*2 + highlighted`.
     * The primary arrow is the highlighted direction when present, else the first; the complete list
     * remains available to media, laneSidestreets and JunctionSchematic.
     */
    static NavState.LaneState packLaneState(String payload) {
        int[] packed = null;
        int[][] directions = null;
        int n = 0;
        if (payload != null && payload.length() > 0) {
            try {
                StringTokenizer st = new StringTokenizer(payload, " ");
                String first = st.nextToken();
                boolean v2 = "v".equals(first);
                int count = Integer.parseInt(v2 ? st.nextToken() : first);
                if (count > NavState.MAX_LANES) {
                    count = NavState.MAX_LANES;                   // shim caps at 8; be defensive
                }
                if (count > 0) {
                    packed = new int[1 + 2 * count];
                    directions = new int[count][];
                    while (n < count && st.hasMoreTokens()) {
                        int dirCount = v2 ? Integer.parseInt(st.nextToken()) : 1;
                        if (dirCount < 1 || dirCount > NavState.MAX_LANE_DIRECTIONS) {
                            break;
                        }
                        int[] dirs = new int[dirCount];
                        int primary = 0;
                        boolean highlighted = false;
                        for (int d = 0; d < dirCount; d++) {
                            if (!st.hasMoreTokens()) {
                                throw new NumberFormatException();
                            }
                            int lane = Integer.parseInt(st.nextToken());
                            dirs[d] = laneShapeToDirection(lane >> 1);
                            if (d == 0 || ((lane & 1) != 0 && !highlighted)) {
                                primary = dirs[d];
                                highlighted = (lane & 1) != 0;
                            }
                        }
                        packed[1 + 2 * n] = primary;
                        packed[2 + 2 * n] = highlighted ? 2 : 0;
                        directions[n] = dirs;
                        n++;
                    }
                }
            } catch (Exception ex) {
                n = 0;                                            // malformed -> treat as "no lanes"
            }
        }
        if (n == 0) {
            return NavState.NO_LANES;
        }
        packed[0] = n;
        if (n != directions.length) {
            int[][] trimmed = new int[n][];
            System.arraycopy(directions, 0, trimmed, 0, n);
            directions = trimmed;
        }
        return new NavState.LaneState(packed, directions);
    }

    /**
     * IPC line 5 (1.7 only): "&lt;event&gt; &lt;side&gt; &lt;angle&gt; &lt;number&gt;" of the manoeuvre AFTER the current
     * one, in line 1's vocabulary. The argument is the payload taggedLine() stripped the "5 " from.
     * Absent or malformed -> null (no next manoeuvre).
     */
    static int[] packNextManeuver(String payload) {
        if (payload == null || payload.length() == 0) {
            return null;
        }
        try {
            StringTokenizer st = new StringTokenizer(payload, " ");
            int event = Integer.parseInt(st.nextToken());
            int side = Integer.parseInt(st.nextToken());
            int angle = Integer.parseInt(st.nextToken());
            int number = Integer.parseInt(st.nextToken());
            if (event <= 0) {
                return null;
            }
            return new int[]{ event, side, angle, number };
        } catch (Exception ex) {
            return null;
        }
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

    /**
     * The phone's arrival clock string (IPC line 8) -&gt; {hour 0..23, minute 0..59}, or null when it
     * cannot be read. Accepts what the navigators were seen to send ("17:42", "7:51") plus the
     * 12-hour forms of an English phone ("5:42 PM", "12:05 am", "5:42 p.m."); "." also separates.
     * Anything else (a date, a day suffix, garbage) -&gt; null, and the cluster gets the remaining
     * time instead.
     */
    public static int[] parseEtaClock(String eta) {
        if (eta == null) {
            return null;
        }
        String s = eta.trim();
        int n = s.length();
        int i = 0;
        int h = 0;
        int hd = 0;
        while (i < n && hd < 2 && Character.isDigit(s.charAt(i))) {
            h = h * 10 + (s.charAt(i) - '0');
            i++;
            hd++;
        }
        if (hd == 0 || i >= n || (s.charAt(i) != ':' && s.charAt(i) != '.')) {
            return null;
        }
        i++;
        if (i + 2 > n || !Character.isDigit(s.charAt(i)) || !Character.isDigit(s.charAt(i + 1))) {
            return null;
        }
        int m = (s.charAt(i) - '0') * 10 + (s.charAt(i + 1) - '0');
        i += 2;
        String rest = s.substring(i).trim().toLowerCase();
        if (rest.length() > 0) {
            String ampm = rest.replace('.', ' ').trim();
            boolean pm = ampm.startsWith("p");
            boolean am = ampm.startsWith("a");
            if ((!pm && !am) || h < 1 || h > 12) {
                return null;
            }
            if (am) {
                h = (h == 12) ? 0 : h;
            } else {
                h = (h == 12) ? 12 : h + 12;
            }
        }
        if (h > 23 || m > 59) {
            return null;
        }
        return new int[]{ h, m };
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
