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

    /**
     * Push the Android Auto album cover to the instrument cluster. AID / colour Virtual Cockpit ONLY.
     *
     * The shim writes the raw AlbumArt bytes (MediaPlaybackMetadata field 4) to /dev/shmem/aa_cover;
     * {@link de.vw.mib.asl.internal.androidauto.target.AndroidAutoTarget#applyCoverArt(int)} stages
     * them into the HMI image cache and points TrackInfo list-58's __cover / __is_cover_available at
     * them. The STOCK CoverArt usecase (MediaCoverArtAdapter reads list 58) then forwards the cover to
     * the cluster via DSIKombiPictureServer.responseCoverArt — but only if the cluster advertises the
     * MOST cover-art capability (datapool 1077026816). A monochrome cluster never requests it, so this
     * is inert (and harmless) there. Independent of the now-playing text path (CurrentStationInfo).
     *
     * Requires SHOW_MEDIA. Default OFF: experimental, verifiable only on an AID-equipped car.
     */
    public static final boolean SHOW_COVER_ART = false;

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

    // ===== disconnect handling =============================================================
    /**
     * Backstop timeout (ms) for the ONE disconnect case the GAL library gives no signal for:
     * the phone is physically yanked and the SAL never tears the receiver down, so the
     * GalReceiver::shutdown hook never fires and the connected flag stays stuck at 1.
     *
     * The normal disconnect edge is event-driven and instant, NOT timed: the patched shim flips an
     * explicit {@code connected} flag to 0 the moment the AA session tears down (its
     * GalReceiver::shutdown hook), and bumps a {@code session} epoch on every reconnect
     * (GalReceiver::init). The readers clear immediately on connected=0 and on a new session, and
     * KEEP the last frame indefinitely while connected=1 (so Waze frozen at a light no longer blanks).
     *
     * This backstop only bites when connected has been stuck at 1 with no new data for this long — i.e.
     * a hard yank with no teardown callback. Kept generous so a long stationary stop never trips it.
     */
    public static final long AA_STALE_BACKSTOP_MS = 300000L;   // 5 min

    // ===== logging =========================================================================
    /**
     * Log verbosity, compiled into the jar: MIBLogger.TRACE | DEBUG | INFO | ERROR | SILENT.
     * This is the sole source of the level (the former /media/mp000/MIBLogger SD override is gone).
     */
    public static final int LOG_LEVEL = MIBLogger.INFO;
}
