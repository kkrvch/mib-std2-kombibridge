package de.vw.mib.bap.mqbab2.navsd.functions;

/**
 * Shared state the navsd BAP function shadows read. AANavReader writes it from the shim's
 * /dev/shmem/aa_nav on the framework timer thread, then pokes the functions to re-send.
 *
 * Everything one cluster function shows together is ONE immutable object behind ONE volatile
 * reference (Maneuver, Streets, Distance, Destination, LaneState): a reader on another thread - a
 * shadow answering an event of the unit's own navigation - takes the reference once and can never
 * pair a new arrow with an old direction or a new street with an old flag. same() tells AANavReader
 * whether a function has anything new to send.
 *
 * Starts inactive/empty; ACTIVE flips on once a route is guiding. Java 1.4 (cf48): no generics.
 */
public final class NavState {
    public static volatile boolean ACTIVE = false;
    // Nav-capable vs non-nav is detected at runtime; see ClusterCaps.

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * The small "then" arrow next to the main one (ManeuverDescriptor.maneuver_2,
     * Config.Navigation.SHOW_NEXT_MANEUVER). While the current manoeuvre is near, it is the one
     * AFTER it (AA steps[1]); while it is still far and maneuver_1 shows FOLLOW_STREET, it is the
     * current manoeuvre itself, as a preview.
     */
    public static final class NextManeuver {
        public final int mainElement;
        public final int direction;
        public final String sideStreets;

        public NextManeuver(int mainElement, int direction, String sideStreets) {
            this.mainElement = mainElement;
            this.direction = direction;
            this.sideStreets = (sideStreets != null) ? sideStreets : "";
        }

        boolean same(NextManeuver o) {
            return o != null && mainElement == o.mainElement && direction == o.direction
                    && sideStreets.equals(o.sideStreets);
        }
    }

    /**
     * ManeuverDescriptor (fid 23): the arrow. mainElement = VW MAIN_ELEMENT, direction = VW angle
     * 0..255 CCW, sideStreets = junction geometry (Maneuver_1.sidestreets, a BAPString(17); on a
     * nav-capable cluster possibly a multi-character schematic, see JunctionSchematic), next = the
     * second arrow or null.
     */
    public static final class Maneuver {
        public final int mainElement;
        public final int direction;
        public final String sideStreets;
        public final int zLevelGuidance;
        public final NextManeuver next;

        public Maneuver(int mainElement, int direction, String sideStreets, int zLevelGuidance, NextManeuver next) {
            this.mainElement = mainElement;
            this.direction = direction;
            this.sideStreets = (sideStreets != null) ? sideStreets : "";
            this.zLevelGuidance = zLevelGuidance;
            this.next = next;
        }

        public boolean same(Maneuver o) {
            return o != null && mainElement == o.mainElement && direction == o.direction
                    && zLevelGuidance == o.zLevelGuidance && sideStreets.equals(o.sideStreets)
                    && (next == null ? o.next == null : next.same(o.next));
        }
    }
    public static final Maneuver NO_MANEUVER = new Maneuver(13, 192, "", 0, null);   // TURN, right: the old defaults
    public static volatile Maneuver maneuver = NO_MANEUVER;

    /**
     * The street lines. street -> TurnToInfo (fid 20): the next-turn road, or the current road
     * while the manoeuvre is far. currentRoad = the road the car is on (1.7 0x8007, IPC line 6; ""
     * = the phone did not say) -> CurrentPositionInfo (fid 19). wantsCurrentPosition: TurnToInfo
     * should take the unit's own map-matched street (not near yet, and AA named no current road),
     * falling back to nextRoad.
     */
    public static final class Streets {
        public final String street;
        public final String currentRoad;
        public final String nextRoad;
        public final boolean wantsCurrentPosition;

        public Streets(String street, String currentRoad, String nextRoad, boolean wantsCurrentPosition) {
            this.street = (street != null) ? street : "";
            this.currentRoad = (currentRoad != null) ? currentRoad : "";
            this.nextRoad = (nextRoad != null) ? nextRoad : "";
            this.wantsCurrentPosition = wantsCurrentPosition;
        }

        public boolean same(Streets o) {
            return o != null && wantsCurrentPosition == o.wantsCurrentPosition && eq(street, o.street)
                    && eq(currentRoad, o.currentRoad) && eq(nextRoad, o.nextRoad);
        }
    }
    public static final Streets NO_STREETS = new Streets("", "", "", false);
    public static volatile Streets streets = NO_STREETS;

    /**
     * DistanceToNextManeuver (fid 18): raw metres (the stock formatter is in the shadow);
     * approaching = the real manoeuvre is drawn, not FOLLOW_STREET -> bargraph on.
     */
    public static final class Distance {
        public final int meters;
        public final boolean approaching;

        public Distance(int meters, boolean approaching) {
            this.meters = meters;
            this.approaching = approaching;
        }

        public boolean same(Distance o) {
            return o != null && meters == o.meters && approaching == o.approaching;
        }
    }
    public static final Distance NO_DISTANCE = new Distance(0, false);
    public static volatile Distance distance = NO_DISTANCE;

    /**
     * The whole trip rather than the next manoeuvre (1.7 only, 0 = the phone has not said):
     * DistanceToDestination (fid 21) and TimeToDestination (fid 22). seconds = remaining travel
     * time, NOT a clock; eta = the phone's own arrival clock, verbatim ("17:42", "5:42 PM"; IPC
     * line 8), "" = not said.
     */
    public static final class Destination {
        public final int meters;
        public final int seconds;
        public final String eta;

        public Destination(int meters, int seconds, String eta) {
            this.meters = meters;
            this.seconds = seconds;
            this.eta = (eta != null) ? eta : "";
        }

        public boolean same(Destination o) {
            return o != null && meters == o.meters && seconds == o.seconds && eta.equals(o.eta);
        }
    }
    public static final Destination NO_DESTINATION = new Destination(0, 0, "");
    public static volatile Destination destination = NO_DESTINATION;

    // Lane guidance (1.7 only), fn 24. A lane can carry several directions (for example straight
    // OR left), so the display/BAP primary and the complete direction list are ONE carrier.
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

        private static boolean sameInts(int[] a, int[] b) {
            if (a == b) return true;
            if (a == null || b == null || a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) {
                if (a[i] != b[i]) return false;
            }
            return true;
        }

        public boolean same(LaneState o) {
            if (o == this) return true;
            if (o == null || !sameInts(lanes, o.lanes)) return false;
            if (directions == o.directions) return true;
            if (directions == null || o.directions == null || directions.length != o.directions.length) return false;
            for (int i = 0; i < directions.length; i++) {
                if (!sameInts(directions[i], o.directions[i])) return false;
            }
            return true;
        }
    }
    public static final LaneState  NO_LANES = new LaneState(new int[]{ 0 }, new int[0][]);
    public static volatile LaneState laneState = NO_LANES;

    private NavState() {}
}
