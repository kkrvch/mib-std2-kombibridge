package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * Central compile-time switches for the AAtoKombi HMI mod.
 * Flip a value here and rebuild; no other file needs touching.
 *
 * (Plain boolean/int constants — the mod compiles at -source/-target 1.3. Because they are
 * compile-time constants, every gate below is inlined and dead-code-eliminated by javac, so a
 * disabled feature leaves no bytecode behind.)
 */
public final class Config {
    private Config() {}

    // ===== what AAtoKombi renders on the instrument cluster =================================
    /**
     * Draw AA turn-by-turn navigation on the cluster: the navsd Navigation menu on a nav-capable
     * cluster, the media now-playing widget on a non-nav one. false -> the maneuver is never fed to
     * the cluster (navsd stays stock, the media widget shows only the track / stock label).
     */
    public static final boolean SHOW_NAV = true;

    /**
     * Show the now-playing track (song / artist / album) in the media widget when no route guidance
     * is active. false -> the cluster keeps the stock "Android Auto" label instead.
     */
    public static final boolean SHOW_MEDIA = true;

    /**
     * Add a 4th line with an ASCII playback progress bar + elapsed/total time (e.g.
     * "1:23 ----|----- 3:45") to the now-playing track widget. The cluster has NO graphical media
     * progress bar reachable from the head unit (the audiosd PlayPosition function, fid 52, is
     * auto-generated dead code — not served, no cluster sink), so this is rendered as text in the
     * CurrentStationInfo Q4 slot.
     *
     * Position (PlaybackSeconds) comes from the patched GAL MediaPlaybackStatusEndpoint status
     * callback; duration from its metadata callback (see shim/DESIGN_MEDIA.md). Requires SHOW_MEDIA.
     *
     * Layout cost: showing Q4 needs the 4-line media layout (pi_Type=0). When this flag is OFF the
     * track widget keeps the stock 3-line layout (pi_Type=72, big title) exactly as before.
     */
    public static final boolean SHOW_MEDIA_PROGRESS = false;

    // Both SHOW_NAV and SHOW_MEDIA false -> AAtoKombi feeds nothing to the cluster (stock label,
    // stock navsd).

    // ===== diagnostics =====================================================================
    /**
     * DIAGNOSTIC layout probe. When true, the AA media widget is replaced with line markers
     * P1/S2/T3/Q4 (all four lines, types 0) so the cluster's real line count / order / icon
     * rendering can be read on the unit. MUST be false for normal builds.
     */
    public static final boolean PROBE_ENABLED = false;

    // ===== nav-active placeholder ==========================================================
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

    // ===== logging =========================================================================
    /**
     * Log verbosity, compiled into the jar: MIBLogger.TRACE | DEBUG | INFO | ERROR | SILENT.
     * This is the sole source of the level (the former /media/mp000/MIBLogger SD override is gone).
     */
    public static final int LOG_LEVEL = MIBLogger.INFO;
}
