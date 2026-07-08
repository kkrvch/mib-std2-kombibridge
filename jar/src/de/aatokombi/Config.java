package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * Central compile-time switches for the AAtoKombi HMI mod.
 * Flip a value here and rebuild; no other file needs touching.
 *
 * (Plain int/boolean constants, not a Java enum — the mod compiles at -source/-target 1.3, which
 * predates enums. Because they are compile-time constants, every gate below is inlined and
 * dead-code-eliminated by javac, so a disabled feature leaves no bytecode behind.)
 */
public final class Config {
    private Config() {}

    // ===== what AAtoKombi renders on the instrument cluster ==================================
    // Cluster-output modes, ordered by capability: OFF < MEDIA < NAV.
    /** AAtoKombi feeds nothing to the cluster — stock label, stock navsd (nav data never injected). */
    public static final int OFF   = 0;
    /** Now-playing track only (song / artist / album) — never turn-by-turn directions. */
    public static final int MEDIA = 1;
    /** AA turn-by-turn: the navsd Navigation menu on a nav-capable cluster, the media now-playing
     *  widget on a non-nav one; the now-playing track is shown when no route guidance is active. */
    public static final int NAV   = 2;

    /** The single cluster-output switch. Set to {@link #OFF}, {@link #MEDIA} or {@link #NAV}. */
    public static final int CLUSTER_MODE = NAV;

    // Derived gates — DO NOT edit; set CLUSTER_MODE above. Kept under the original call-site names so
    // every use stays readable, and (being compile-time constants) still dead-code-eliminates.
    //   NAV_ENABLED   → draw AA navigation anywhere on the cluster (navsd menu AND media widget).
    //   MEDIA_ENABLED → show the now-playing track (in NAV mode when idle, and in MEDIA mode).
    public static final boolean NAV_ENABLED   = CLUSTER_MODE == NAV;
    public static final boolean MEDIA_ENABLED = CLUSTER_MODE != OFF;

    // ===== diagnostics ======================================================================
    /**
     * DIAGNOSTIC layout probe. When true, the AA media widget is replaced with line markers
     * P1/S2/T3/Q4 (all four lines, types 0) so the cluster's real line count / order / icon
     * rendering can be read on the unit. MUST be false for normal builds.
     */
    public static final boolean PROBE_ENABLED = false;

    // ===== nav-active placeholder ===========================================================
    /**
     * Suppress the stock "Navigation on the mobile device is active" cluster placeholder.
     *
     * The placeholder is cluster navsd InfoStates (BAP fid 38) state 6, which fires when
     * AndroidAutoASLDataAdapter.isAndroidAutoRouteGuidanceActive() (= ASLDatapool.getBoolean(895953920))
     * is true — set true on every AA nav-focus grant regardless of an active route. When this flag is
     * on, AAtoKombi's AndroidAutoASLDataAdapter shadow forces that reader to false (the single point
     * InfoStates consults for the AA branch), so the cluster nav surface stays free for AAtoKombi's own
     * turn-by-turn injection. Set false for pure-stock behaviour. Replaces the separate
     * NavActiveIgnore.jar (navignore). Verified sole+complete driver of the AA placeholder by firmware
     * bytecode audit (CarPlay/CarLife share the same InfoStates state but never fire for an AA phone).
     */
    public static final boolean SUPPRESS_NAV_ACTIVE_PLACEHOLDER = true;

    // ===== logging ==========================================================================
    /**
     * Log verbosity, compiled into the jar: MIBLogger.TRACE | DEBUG | INFO | ERROR | SILENT.
     * This is the sole source of the level (the former /media/mp000/MIBLogger SD override is gone).
     */
    public static final int LOG_LEVEL = MIBLogger.INFO;
}
