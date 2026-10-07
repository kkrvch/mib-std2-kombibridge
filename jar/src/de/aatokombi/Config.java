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
     * On a nav-capable cluster (Amundsen/AID), what the CENTRAL cluster screen shows while
     * Android Auto guides:
     *   false -> the arrow-based maneuver display this mod builds from BAP (ManeuverDescriptor /
     *            TurnToInfo / ...), the original AAtoKombi behaviour.
     *   true  -> the stock map instead (ActiveRgType.rgtype = 3 / MOST_MAP). This is the car's OWN
     *            navigation map (current position, stock rendering) -- it does NOT draw the AA
     *            route. So this trades the turn arrows for a plain "where am I" map. It only makes
	 *            sense on an AID cluster.
	 *
     * Independent of this flag, the stock "Map" button in the HU's Navigation menu (the one that
	 * switches map output between the HU and the cluster) keeps working exactly as it always does -
	 * this flag only picks the STARTING state for a given AA guidance session;
     * the driver can still switch either way afterwards through the normal stock mechanism.	
	 *
	 * Requires SHOW_NAV.
     */
    public static final boolean FORCE_MAP_ON_KOMBI = true;
	 
    /**
     * Draw a multi-spoke junction schematic (several side-street glyphs positioned around the
     * maneuver arrow, instead of at most one) on the navsd Navigation menu, for roundabouts (from
     * the AA exit number/bearing) and for ordinary turns where Android Auto has sent lane data (from
     * the distinct lane directions, attached to whichever of FOLLOW_STREET/TURN/TURN_ON_MAINROAD the
     * maneuver already uses. Reverse-engineered from the stock Guidance/GuidanceModel widget in
     * MIBHMI.jar — see JunctionSchematic and docs/NAVIGATION_VIA_NAVSD.md.
     *
     * This switch is a secondary, compile-time kill switch only. The REAL gate is at runtime, in
     * AANavReader.publishNavsd() (`schematic`): ClusterCaps.isNavCapable() — the cluster type
	 * actually driving navsd on a given build is already known, so a runtime capability
     * probe on top of it is unneeded ceremony. Every nav-capable cluster gets the schematic.
     *
     * Requires SHOW_NAV.
     */
    public static final boolean SHOW_JUNCTION_SCHEMATIC = true;

    /**
     * Draw a second, smaller "then" arrow next to the main manoeuvre on the navsd Navigation menu
     * (ManeuverDescriptor.maneuver_2), the way the stock navigation shows a manoeuvre that follows
     * closely on the current one:
     *   current manoeuvre near (see MANEUVER_NEAR_*) -> the manoeuvre AFTER it (AA steps[1]);
     *   current manoeuvre still far (FOLLOW_STREET)  -> the current manoeuvre itself, as a preview.
     * Nothing is drawn when that second arrow would just be another "follow the street". Needs the
     * shim's IPC line 5 (protocol 1.7). Meant for the AID; a cluster that does not draw maneuver_2
     * simply ignores it, as it does for the stock navigation.
     *
     * Requires SHOW_NAV.
     */
    public static final boolean SHOW_NEXT_MANEUVER = true;

    /**
     * Show the now-playing track (song / artist / album) when no route guidance is active,
     * or when music is selected with the wheel. false -> stock "Android Auto" label instead.
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
     * Requires SHOW_MEDIA.
     */
    public static final boolean SHOW_COVER_ART = true;

    // Both SHOW_NAV and SHOW_MEDIA false -> AAtoKombi feeds nothing to the cluster (stock label,
    // stock navsd).

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

    // ===== when the manoeuvre becomes THE manoeuvre =========================================
    /**
     * How close the manoeuvre must be before the cluster draws it; further than BOTH gates it draws
     * FOLLOW_STREET (see AANavReader.maneuverImminent). Two gates because 400 m is half a minute in
     * town but twelve seconds on a motorway. MAX_VALUE = always show, as before.
     */
    public static final int MANEUVER_NEAR_METERS = 500;
    public static final int MANEUVER_NEAR_SECONDS = 40;
    /**
     * The middle stage between FOLLOW_STREET and the manoeuvre itself: inside EITHER gate (but not yet
     * near, see above) the cluster draws the stock "prepare" glyph -- PREPARE_TURN, PREPARE_ROUNDABOUT,
     * PREPARE_SQUARE or PREPARE_UTURN, turned the way of the manoeuvre (AANavReader.prepareElementFor).
     * Manoeuvres without a prepare glyph (exits, forks, merges, the destination) stay FOLLOW_STREET
     * in this stage. 0 and 0 = no prepare stage, as before.
     */
    public static final int MANEUVER_PREPARE_METERS = 1000;
    public static final int MANEUVER_PREPARE_SECONDS = 80;

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
     *
     * DEBUG (or TRACE) also switches on the shim's trip dump (see de.aatokombi.TripDump): every
     * message the phone sends on the navigation and media channels, raw, plus every line handed to
     * the jar, appended to /dev/shmem/aa_dump_nav.log and /dev/shmem/aa_dump_media.log (RAM only,
     * never the SD card; gone after a reboot). Each file stops at 8 MB.
     */
    public static final int LOG_LEVEL = MIBLogger.DEBUG;
}
