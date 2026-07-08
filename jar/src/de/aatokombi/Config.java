package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * Central compile-time switches for the AAtoKombi HMI mod.
 * Flip a flag here and rebuild; no other file needs touching.
 */
public final class Config {
    private Config() {}

    /**
     * DIAGNOSTIC layout probe. When true, the AA media widget is replaced with line markers
     * P1/S2/T3/Q4 (all four lines, types 0) so the cluster's real line count / order / icon
     * rendering can be read on the unit. MUST be false for normal builds.
     */
    public static final boolean PROBE_ENABLED = false;

    /**
     * Master switch for the now-playing track feature (AA MediaPlaybackStatusEndpoint).
     * false -> ShmemMediaReader is not started and the cluster keeps the stock "Android Auto"
     * label (navigation still works). Turns off now-playing without touching the rest of the mod.
     */
    public static final boolean MEDIA_ENABLED = true;

    /**
     * Master switch for feeding AA route-guidance (arrow/distance/street) into the media
     * now-playing widget on a NON-nav cluster. true -> during guidance the widget shows the
     * maneuver (original AAtoKombi behaviour). false -> the maneuver is never injected, so the
     * widget always shows just song title/artist/album. (Nav-capable clusters use the real navsd
     * menu and are unaffected either way.)
     */
    public static final boolean NAV_ENABLED = true;

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

    /**
     * Log verbosity, compiled into the jar: MIBLogger.TRACE | DEBUG | INFO | ERROR | SILENT.
     * This is the sole source of the level (the former /media/mp000/MIBLogger SD override is gone).
     */
    public static final int LOG_LEVEL = MIBLogger.INFO;
}
