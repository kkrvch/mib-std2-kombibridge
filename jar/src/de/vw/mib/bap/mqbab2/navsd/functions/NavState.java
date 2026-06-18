package de.vw.mib.bap.mqbab2.navsd.functions;

/**
 * Shared state the navsd BAP function shadows read. AANavReader writes it from the shim's
 * /dev/shmem/aa_nav on the framework timer thread, then pokes the functions to re-send.
 *
 * Starts inactive/empty; ACTIVE flips on once a route is guiding. Java 1.4 (cf48): no generics,
 * plain volatile statics.
 */
public final class NavState {
    public static volatile boolean ACTIVE         = false;  // off until the shim delivers a route
    // Nav-capable vs non-nav is no longer a static per-build flag: it is detected at runtime by
    // ClusterCaps.isNavCapable() (ConfigurationService.isNavigationFeatureSelected()), so one build
    // self-selects navsd output on a routing cluster (Amundsen) and the media path on a non-nav one
    // (Bolero). See ClusterCaps.
    public static volatile int     mainElement    = 13;     // VW MAIN_ELEMENT (13 = TURN), default
    public static volatile int     direction      = 192;    // VW direction angle 0..255 (192 = RIGHT), default
    public static volatile int     zLevelGuidance = 0;
    public static volatile String  street         = "";     // next-turn road -> TurnToInfo (fid 20)
    public static volatile int     distanceMeters = 0;

    private NavState() {}
}
