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
    public static volatile String  street         = "";     // next-turn road -> TurnToInfo (fid 20)
    public static volatile String  sideStreets    = "";     // roundabout exit-road geometry (SIDE_STREET_* glyph byte) -> ManeuverDescriptor col3; "" for non-roundabouts
    public static volatile int     distanceMeters = 0;
    // On the approach segment, i.e. the real manoeuvre is drawn, not FOLLOW_STREET -> bargraph on.
    public static volatile boolean approaching    = false;

    // Lane guidance (1.7 only), fn 24. ONE packed array published in a single volatile write, so a
    // reader can never pair a fresh direction with a stale highlight:
    //   lanes[0] = count, then per lane: direction (0..255 CCW, as `direction`) and guidanceInfo
    //   (0 NOT_RECOMMENDED, 2 BEST_RECOMMENDATION). count 0 = no lanes -> the cluster row goes off.
    public static final int        MAX_LANES      = 8;      // shim cap; the cluster shows 6
    public static volatile int[]   lanes          = new int[]{ 0 };

    // Destination, i.e. the whole trip rather than the next manoeuvre (1.7 only, 0 = the phone has
    // not said). Two separate fields on purpose: they drive two separate cluster functions
    // (DistanceToDestination fid 21, TimeToDestination fid 22), never one displayed pair.
    public static volatile int     destinationMeters  = 0;
    public static volatile int     destinationSeconds = 0;  // remaining travel time, NOT a clock ETA

    private NavState() {}
}
