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
     * While the patched libgal (CLUSTER_SINK build) streams Android Auto's cluster map to the AID
     * through native/libkb_dm.so, hide the unit's own cluster map: the built-in navigation then
     * stops drawing it and DisplayManager stops encoding it (CPU), the same way the stock HMI hides
     * it when the cluster asks for no map. Shown again when the phone's video stops. Uses the stock
     * NavigationMapAdapter events (see KombiMap). No effect without the CLUSTER_SINK libgal.
     */
    public static final boolean HIDE_STOCK_KOMBI_MAP_DURING_AA_VIDEO = true;

    /**
     * Android Auto map on the AID (CLUSTER_SINK libgal + native/libkb_dm.so) only while AA route
     * guidance runs. true: without guidance the phone gets no video focus on the cluster channel and
     * sends no cluster video, so the AID shows the unit's own map; when guidance starts the phone
     * is given focus and the AA map appears (it starts with a key frame), and 10 s after guidance
     * ends the stock map is back. false: the AA map whenever Android Auto is connected.
     * Read by the libgal through the flag file ClusterVideo.GUIDANCE_ONLY_FLAG, about once a second.
     */
    public static final boolean CLUSTER_MAP_ONLY_DURING_GUIDANCE = false;

    /**
     * EXPERIMENTAL. While Android Auto is connected with the CLUSTER_SINK libgal, the head unit's
     * map/arrows button (menu "Navigation") walks the cluster through three modes:
     *   AA map -> arrows -> the unit's own map -> arrows -> AA map
     * (the button has two states; every switch to the map takes the other map mode). Holding OK on
     * the steering wheel (CLUSTER_OK_HOLD_STATE, de.aatokombi.WheelKeys) steps AA map -> arrows ->
     * stock map directly, if the head unit receives that key (MQB MHI2 does; unverified here). false: stock ActiveRgType behaviour (button = arrows / AA map).
     */
    public static final boolean CLUSTER_OK_CYCLE = true;
    /**
     * Steering-wheel OK (roller press, key 40) held to this key-panel state switches to the next
     * mode, AA map -> arrows -> stock map -> AA map: 3 = first hold level (~0.8 s), 4 (~2 s),
     * 5 (~3 s). Careful: in some AID views a held OK has its own meaning (e.g. resetting trip data),
     * and the mode switch happens there too.
     */
    public static final int CLUSTER_OK_HOLD_STATE = 4;

    /**
     * EXPERIMENTAL. While the phone's map is on the AID, the steering-wheel zoom (the AID sends
     * BAP NavSD MapScale, fid 45: steps +-1, or continuous 127 / -128 until 0) goes to Android
     * Auto as keys on the cluster input channel instead of to the unit's own map. Whether the
     * phone zooms its cluster map on these keys is not verified. Every MapScale request is logged.
     */
    public static final boolean CLUSTER_ZOOM_TO_AA = true;
    /** Android KEYCODE_ZOOM_IN / KEYCODE_ZOOM_OUT, advertised by the libgal on the cluster input. */
    public static final int CLUSTER_KEY_ZOOM_IN = 168;
    public static final int CLUSTER_KEY_ZOOM_OUT = 169;
    /** true: BAP "+1" means zoom OUT (swap if the car zooms the wrong way). */
    public static final boolean CLUSTER_ZOOM_INVERT = false;
    /**
     * true: zoom as the AA rotary controller (keycode 65536, +-1 per step). Car 2026-11-03: the
     * phone ignored KEYCODE_ZOOM_IN/OUT (168/169) on the cluster input (196 sent, no zoom).
     * Car 2026-11-06: the rotary did not zoom either (32 sent); mib-cluster-map and mib2q-carplay
     * report the same - Android Auto's cluster map is not interactive. Back to the zoom keys.
     */
    public static final boolean CLUSTER_ZOOM_ROTARY = false;
    /** Repeat interval of a zoom key while the wheel is held (continuous zoom), ms. */
    public static final int CLUSTER_ZOOM_REPEAT_MS = 400;

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
     * When the near manoeuvre is a left/right TURN and the phone's NEXT step (AA steps[1], IPC line 5)
     * is a turn the other way, draw the stock DOUBLE_TURN glyph instead of the plain turn arrow.
     * Hardware-verified on the AID (2026-10): direction 64 = "left, then right", 192 = "right, then
     * left"; there is no same-side variant. AA gives no distance between the two steps, so any
     * second step counts. No navigator sent a second step so far, so this is dormant until one does.
     *
     * Requires SHOW_NAV.
     */
    public static final boolean SHOW_DOUBLE_TURN = true;

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
     * AndroidAutoASLDataAdapter.isAndroidAutoRouteGuidanceActive() (= ASLDatapool.getBoolean(2910005))
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
