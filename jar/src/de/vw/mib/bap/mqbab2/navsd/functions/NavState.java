package de.vw.mib.bap.mqbab2.navsd.functions;

/**
 * Shared state the navsd BAP function shadows read. NavShmemReader writes it from the shim's
 * /dev/shmem/aa_nav on the framework timer thread, then pokes the functions to re-send.
 *
 * Starts inactive/empty; ACTIVE flips on once a route is guiding. Java 1.4 (cf48): no generics,
 * plain volatile statics.
 */
public final class NavState {
    public static volatile boolean ACTIVE         = false;  // off until the shim delivers a route
    // Nav-capable cluster switch. false (default): the functions only ever speak for Android Auto;
    // when AA is inactive they go idle and the stock nav-service is never touched -- correct, and the
    // safe default, for clusters with no own routing engine (e.g. Bolero). true: on a routing-capable
    // cluster (e.g. Amundsen) the functions register the stock nav listeners and fall back to the
    // unit's own navigation when AA is inactive, so the built-in nav still draws. Set per build.
    public static volatile boolean NAV_CAPABLE    = false;
    public static volatile int     mainElement    = 13;     // VW MAIN_ELEMENT (13 = TURN), default
    public static volatile int     direction      = 192;    // VW direction angle 0..255 (192 = RIGHT), default
    public static volatile int     zLevelGuidance = 0;
    public static volatile String  street         = "";     // next-turn road -> TurnToInfo (fid 20)
    public static volatile int     distanceMeters = 0;

    private NavState() {}
}
