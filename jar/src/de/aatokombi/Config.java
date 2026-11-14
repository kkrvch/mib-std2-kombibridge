package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * User-facing compile-time switches of the AAtoKombi HMI mod, grouped by what they control:
 *
 *   Config.Navigation     turn-by-turn on the instrument cluster
 *   Config.ManeuverStages how the manoeuvre arrow develops as the turn gets closer
 *   Config.Media          now-playing track and cover art
 *   Config.ClusterMap     Android Auto's map on the AID and the driver's cluster modes
 *   Config.Advanced       compatibility and logging
 *
 * Flip a value here and rebuild; no other file needs touching. Technical constants (key codes,
 * timeouts, protocol values) are NOT here: they live in the class that uses them.
 *
 * (Plain boolean/int constants — the mod compiles at -source/-target 1.3. They are compile-time
 * constants, also when read through a nested class, so every gate below is inlined and
 * dead-code-eliminated by javac: a disabled feature leaves no bytecode behind. build.sh prints the
 * values of the last build as "Group.NAME value", so keep the declarations on one line:
 * public static final TYPE NAME = value;)
 *
 * Dependencies between switches ("needs X" = has no effect, or does something unmanaged, without X):
 *   Navigation.*, ManeuverStages.*   need Navigation.ENABLED
 *   Media.SHOW_PROGRESS, SHOW_COVER_ART   need Media.ENABLED
 *   ClusterMap.*                     need Navigation.ENABLED, the CLUSTER_SINK libgal and libkb_dm.so
 *   Navigation.ENABLED and Media.ENABLED both false -> AAtoKombi feeds nothing to the cluster
 *   (stock label, stock navsd).
 */
public final class Config {
    private Config() {}

    // ===== turn-by-turn on the cluster ======================================================
    public static final class Navigation {
        private Navigation() {}

        /**
         * Draw AA turn-by-turn navigation on the cluster: the navsd Navigation menu on a
         * nav-capable cluster (AID), the media now-playing widget on a non-nav one. false -> the
         * manoeuvre is never fed to the cluster (navsd stays stock, the media widget shows only the
         * track / stock label).
         *
         * Also the master switch of the AA map on the AID (see ClusterMap): the code that manages it
         * (hiding the unit's own cluster map, the cluster modes, commands to the libgal) runs on the
         * timer of the navigation reader, which starts only when this is true.
         */
        public static final boolean ENABLED = true;

        /**
         * Draw a second, smaller "then" arrow next to the main manoeuvre on the navsd Navigation
         * menu (ManeuverDescriptor.maneuver_2), the way the stock navigation shows a manoeuvre that
         * follows closely on the current one:
         *   current manoeuvre near (see ManeuverStages.NEAR_*) -> the manoeuvre AFTER it (AA steps[1]);
         *   current manoeuvre still far (FOLLOW_STREET)        -> the current manoeuvre, as a preview.
         * Nothing is drawn when that second arrow would just be another "follow the street". Needs
         * the shim's IPC line 5 (protocol 1.7). Meant for the AID; a cluster that does not draw
         * maneuver_2 simply ignores it, as it does for the stock navigation.
         */
        public static final boolean SHOW_NEXT_MANEUVER = true;

        /**
         * When the near manoeuvre is a left/right TURN and the phone's NEXT step (AA steps[1], IPC
         * line 5) is a turn the other way, draw the stock DOUBLE_TURN glyph instead of the plain
         * turn arrow. Hardware-verified on the AID (2026-10): direction 64 = "left, then right",
         * 192 = "right, then left"; there is no same-side variant. AA gives no distance between the
         * two steps, so any second step counts. No navigator sent a second step so far, so this is
         * dormant until one does.
         */
        public static final boolean SHOW_DOUBLE_TURN = true;

        /**
         * Draw a multi-spoke junction schematic (several side-street glyphs positioned around the
         * manoeuvre arrow, instead of at most one) on the navsd Navigation menu, for roundabouts
         * (from the AA exit number/bearing) and for ordinary turns where Android Auto has sent lane
         * data (from the distinct lane directions, attached to whichever of FOLLOW_STREET / TURN /
         * TURN_ON_MAINROAD the manoeuvre already uses). Reverse-engineered from the stock
         * Guidance/GuidanceModel widget in MIBHMI.jar - see JunctionSchematic and
         * docs/NAVIGATION_VIA_NAVSD.md.
         *
         * Only nav-capable clusters (ClusterCaps.isNavCapable()) get the schematic; the runtime
         * gate is in AANavReader.publishNavsd().
         */
        public static final boolean SHOW_JUNCTION_SCHEMATIC = true;
    }

    // ===== how the manoeuvre arrow develops as the turn gets closer ========================
    /**
     * The arrow goes through up to three stages: FOLLOW_STREET (the plain "follow the street"
     * arrow while the manoeuvre is far), PREPARE (the stock "prepare" glyph) and the manoeuvre
     * itself, which is always the last one. Needs Navigation.ENABLED.
     */
    public static final class ManeuverStages {
        private ManeuverStages() {}

        /**
         * Which stages come before the manoeuvre itself:
         *   FOLLOW_STREET true,  PREPARE true  -> FOLLOW_STREET -> PREPARE -> manoeuvre
         *   FOLLOW_STREET true,  PREPARE false -> FOLLOW_STREET -> manoeuvre (switch at NEAR_*)
         *   FOLLOW_STREET false, PREPARE true  -> PREPARE -> manoeuvre (PREPARE from any distance
         *                                         until near; manoeuvres with no prepare glyph show
         *                                         the manoeuvre at once)
         *   FOLLOW_STREET false, PREPARE false -> the manoeuvre at once, whatever the distance
         */
        public static final boolean FOLLOW_STREET = true;
        public static final boolean PREPARE = true;

        /**
         * How close the manoeuvre must be before the cluster draws it; further than BOTH gates it
         * draws FOLLOW_STREET (see AANavReader.maneuverImminent). Two gates because 400 m is half a
         * minute in town but twelve seconds on a motorway. Integer.MAX_VALUE = always show.
         */
        public static final int NEAR_METERS = 500;
        public static final int NEAR_SECONDS = 40;

        /**
         * The middle stage between FOLLOW_STREET and the manoeuvre itself: inside EITHER gate (but
         * not yet near, see above) the cluster draws the stock "prepare" glyph - PREPARE_TURN,
         * PREPARE_ROUNDABOUT, PREPARE_SQUARE or PREPARE_UTURN, turned the way of the manoeuvre
         * (AANavReader.prepareElementFor). Manoeuvres without a prepare glyph (exits, forks, merges,
         * the destination) stay FOLLOW_STREET in this stage. 0 and 0 = no prepare stage.
         */
        public static final int PREPARE_METERS = 1000;
        public static final int PREPARE_SECONDS = 80;
    }

    // ===== now-playing =====================================================================
    public static final class Media {
        private Media() {}

        /**
         * Show the now-playing track (song / artist / album) when no route guidance is active,
         * or when music is selected with the wheel. false -> stock "Android Auto" label instead.
         */
        public static final boolean ENABLED = true;

        /**
         * Add a 4th line with an ASCII playback progress bar + elapsed/total time (e.g.
         * "1:23 ----|----- 3:45") to the now-playing track widget. The cluster has NO graphical
         * media progress bar reachable from the head unit (the audiosd PlayPosition function, fid
         * 52, is auto-generated dead code - not served, no cluster sink), so this is rendered as
         * text in the CurrentStationInfo Q4 slot.
         *
         * Position (PlaybackSeconds) comes from the patched GAL MediaPlaybackStatusEndpoint status
         * callback; duration from its metadata callback (see shim/DESIGN_MEDIA.md).
         *
         * Layout cost: showing Q4 needs the 4-line media layout (pi_Type=0). When this flag is OFF
         * the track widget keeps the stock 3-line layout (pi_Type=72, big title). Needs ENABLED.
         */
        public static final boolean SHOW_PROGRESS = false;

        /**
         * Push the Android Auto album cover to the instrument cluster. AID / colour Virtual Cockpit
         * ONLY.
         *
         * The shim writes the raw AlbumArt bytes (MediaPlaybackMetadata field 4) to
         * /dev/shmem/aa_cover; AndroidAutoTarget.applyCoverArt(int) stages them into the HMI image
         * cache and points TrackInfo list-58's __cover / __is_cover_available at them. The STOCK
         * CoverArt usecase (MediaCoverArtAdapter reads list 58) then forwards the cover to the
         * cluster via DSIKombiPictureServer.responseCoverArt - but only if the cluster advertises
         * the MOST cover-art capability (datapool 1077026816). A monochrome cluster never requests
         * it, so this is inert (and harmless) there. Independent of the now-playing text path
         * (CurrentStationInfo). Needs ENABLED.
         */
        public static final boolean SHOW_COVER_ART = true;
    }

    // ===== Android Auto's map on the AID ====================================================
    /**
     * Needs the CLUSTER_SINK libgal and native/libkb_dm.so on the unit (without them the phone
     * sends no cluster video, MODE_CYCLE is inert and START_ON_MAP picks the unit's own map), a
     * nav-capable cluster (AID) and Navigation.ENABLED. While the phone's video is on the AID the
     * unit's own cluster map is hidden by the mod (KombiMap) - not a setting any more: the built-in
     * navigation stops drawing it and DisplayManager stops encoding it, and both come back when the
     * phone's video has been gone for a few seconds.
     */
    public static final class ClusterMap {
        private ClusterMap() {}

        /**
         * Which cluster view an AA route guidance session STARTS in (BAP ActiveRgType, set once when
         * guidance begins):
         *   true  -> the map view (rgtype 3). With the phone's video on the AID this is ANDROID
         *            AUTO'S map; without it (no CLUSTER_SINK libgal, no phone video yet, see
         *            AA_MAP_ONLY_DURING_GUIDANCE) it is the car's OWN navigation map (current
         *            position, stock rendering) - it does NOT draw the AA route.
         *   false -> the turn arrows this mod builds from BAP (ManeuverDescriptor / TurnToInfo / ...),
         *            the original AAtoKombi behaviour; the map is then only a button press away.
         * This only picks the starting state. Afterwards the driver switches with the unit's
         * Navigation "Map" button (the one that moves map output between the unit and the cluster),
         * which keeps working as it always does, and with MODE_CYCLE also with the steering wheel.
         */
        public static final boolean START_ON_MAP = true;

        /**
         * Android Auto's map on the AID only while AA route guidance runs. true: without guidance
         * the phone gets no video focus on the cluster channel and sends no cluster video, so the
         * AID shows the unit's own map; when guidance starts the phone is given focus and the AA map
         * appears (it starts with a key frame), and 10 s after guidance ends the stock map is back.
         * false: the AA map whenever Android Auto is connected. Read by the libgal through the flag
         * file ClusterVideo.GUIDANCE_ONLY_FLAG, about once a second.
         */
        public static final boolean AA_MAP_ONLY_DURING_GUIDANCE = false;

        /**
         * EXPERIMENTAL. The driver's three cluster modes while Android Auto is connected with the
         * CLUSTER_SINK libgal: the unit's map/arrows button (menu "Navigation") walks
         *   AA map -> arrows -> the unit's own map -> arrows -> AA map
         * (the button has two states; every switch to the map takes the map mode not used last
         * time), and holding the key chosen by MODE_KEY on the steering wheel (held for about 2 s) steps AA map -> arrows -> stock map directly, if the head unit receives that
         * key (MQB MHI2 does; unverified on STD2). Careful: in some AID views a held OK has its own
         * meaning (e.g. resetting trip data) and the mode switch happens there too.
         * false: stock ActiveRgType behaviour - the button toggles arrows / map, and the map is the
         * AA map whenever the phone's video is on.
         */
        public static final boolean MODE_CYCLE = true;

        /** Which steering-wheel key, held, steps the cluster mode (with MODE_CYCLE). */
        public static final int MODE_KEY_NONE = 0;
        /** The roller press (Mute; key code 44 on the MQB MIB2 STD2 wheel, see WheelKeys.KEY_OK). */
        public static final int MODE_KEY_ROLLER = 1;
        /** BACK, the widget-switch key (WheelKeys.KEY_BACK); a short press keeps its stock job. */
        public static final int MODE_KEY_BACK = 2;
        public static final int MODE_KEY = MODE_KEY_ROLLER;
    }

    // ===== compatibility and logging ========================================================
    public static final class Advanced {
        private Advanced() {}

        /**
         * Suppress the stock "Navigation on the mobile device is active" cluster placeholder.
         *
         * The placeholder is cluster navsd InfoStates (BAP fid 38) state 6, which fires when
         * AndroidAutoASLDataAdapter.isAndroidAutoRouteGuidanceActive() (= ASLDatapool.getBoolean
         * (2910005)) is true - set true on every AA nav-focus grant regardless of an active route.
         * When this flag is on, AAtoKombi's AndroidAutoASLDataAdapter shadow forces that reader to
         * false (the single point InfoStates consults for the AA branch), so the cluster nav surface
         * stays free for AAtoKombi's own turn-by-turn injection. Set false for pure-stock behaviour.
         * Replaces the separate NavActiveIgnore.jar (navignore). Verified sole+complete driver of
         * the AA placeholder by firmware bytecode audit (CarPlay/CarLife share the same InfoStates
         * state but never fire for an AA phone).
         */
        public static final boolean SUPPRESS_NAV_ACTIVE_PLACEHOLDER = true;

        /**
         * Log verbosity, compiled into the jar: MIBLogger.TRACE | DEBUG | INFO | ERROR | SILENT.
         * This is the sole source of the level.
         *
         * DEBUG (or TRACE) also switches on the shim's trip dump (see de.aatokombi.TripDump): every
         * message the phone sends on the navigation and media channels, raw, plus every line handed
         * to the jar, appended to /dev/shmem/aa_dump_nav.log and /dev/shmem/aa_dump_media.log (RAM
         * only, never the SD card; gone after a reboot). Each file stops at 8 MB.
         */
        public static final int LOG_LEVEL = MIBLogger.DEBUG;
    }
}
