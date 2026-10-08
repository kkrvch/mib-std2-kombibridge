package de.vw.mib.bap.mqbab2.navsd.functions;

/**
 * Shared state the navsd BAP function shadows read. AANavReader writes it from the shim's
 * /dev/shmem/aa_nav on the framework timer thread, then pokes the functions to re-send.
 *
 * Starts inactive/empty; ACTIVE flips on once a route is guiding. Java 1.4 (cf48): no generics,
 * plain volatile statics.
 */
public final class NavState {
    public static volatile boolean ACTIVE         = false;
    // Nav-capable vs non-nav is detected at runtime; see ClusterCaps.
    public static volatile int     mainElement    = 13;     // VW MAIN_ELEMENT (13 = TURN), default
    public static volatile int     direction      = 192;    // VW direction angle 0..255 (192 = RIGHT), default
    public static volatile int     zLevelGuidance = 0;
    public static volatile String  street         = "";     // TurnToInfo (fid 20): next-turn road, or the current road while far
    // The road the car is on now (1.7 0x8007 current_road, IPC line 6); "" = the phone did not say.
    // -> CurrentPositionInfo (fid 19), and TurnToInfo during FOLLOW_STREET (see AANavReader.turnToStreet).
    public static volatile String  currentRoad    = "";
    // TurnToInfo while the manoeuvre is not yet near and AA named no current road: take the unit's own
    // map-matched street (NavigationService.getCurrentPositionInfo), falling back to nextRoad.
    public static volatile boolean streetWantsCurrentPosition = false;
    public static volatile String  nextRoad       = "";
    // Junction-glyph geometry -> ManeuverDescriptor col3 (Maneuver_1.sidestreets, a BAPString(17)).
    // On a nav-capable cluster this may be a MULTI-character schematic string (see JunctionSchematic)
	// -- each character is an independent side-street spoke on the same 0..255 CCW scale as `direction`,
	// decoded by the stock Guidance/GuidanceModel widget. Which form is written for a given frame is
	// decided entirely by AANavReader.publishNavsd(); this field itself carries either one opaquely.
    public static volatile String  sideStreets    = "";
    public static volatile int     distanceMeters = 0;
    // On the approach segment, i.e. the real manoeuvre is drawn, not FOLLOW_STREET -> bargraph on.
    public static volatile boolean approaching    = false;

    // The small "then" arrow next to the main one (ManeuverDescriptor.maneuver_2, Config.SHOW_NEXT_MANEUVER).
    // While the current manoeuvre is near, it is the one AFTER it (AA steps[1]); while it is still far
    // and maneuver_1 shows FOLLOW_STREET, it is the current manoeuvre itself, as a preview. One
    // immutable carrier published in a single volatile write; null = no second arrow.
    public static final class NextManeuver {
        public final int mainElement;
        public final int direction;
        public final String sideStreets;

        public NextManeuver(int mainElement, int direction, String sideStreets) {
            this.mainElement = mainElement;
            this.direction = direction;
            this.sideStreets = (sideStreets != null) ? sideStreets : "";
        }
    }
    public static volatile NextManeuver nextManeuver = null;

    // Lane guidance (1.7 only), fn 24. A lane can carry several directions (for example straight
    // OR left), so publish the display/BAP primary and the complete direction list as ONE immutable
    // carrier. A reader can never pair a fresh primary arrow with stale alternatives.
    //   lanes[0] = count, then per lane: primary direction (0..255 CCW) and guidanceInfo
    //   directions[i] = every direction the phone supplied for lane i, primary included.
    // count 0 = no lanes -> the cluster row goes off.
    public static final int        MAX_LANES            = 8;      // shim cap; the cluster shows 6
    public static final int        MAX_LANE_DIRECTIONS  = 3;      // bounded IPC / renderer payload
    public static final class LaneState {
        public final int[] lanes;
        public final int[][] directions;

        public LaneState(int[] lanes, int[][] directions) {
            this.lanes = lanes;
            this.directions = directions;
        }
    }
    public static final LaneState  NO_LANES = new LaneState(new int[]{ 0 }, new int[0][]);
    public static volatile LaneState laneState = NO_LANES;

    // Destination, i.e. the whole trip rather than the next manoeuvre (1.7 only, 0 = the phone has
    // not said). Two separate fields on purpose: they drive two separate cluster functions
    // (DistanceToDestination fid 21, TimeToDestination fid 22), never one displayed pair.
    public static volatile int     destinationMeters  = 0;
    public static volatile int     destinationSeconds = 0;  // remaining travel time, NOT a clock ETA
    // The phone's own arrival clock for the destination, verbatim ("17:42", "5:42 PM"; IPC line 8,
    // 1.7 only); "" = not said. Used only for the arrival-clock form of TimeToDestination (fid 22).
    public static volatile String  destinationEta = "";

    private NavState() {}
}
