# jar — HMI Java mod (`AAtoKombi.jar`)

Loaded on the unit via `-Xbootclasspath/p:` (prepended ahead of the firmware classes), so our
classes shadow the stock ones with the same fully-qualified names.

## Sources (`src/`)

Our own code:
- `de/aatokombi/Config.java` — the single file of compile-time switches (`SHOW_NAV`, `SHOW_MEDIA`,
  `SHOW_MEDIA_PROGRESS`, `SHOW_COVER_ART`, `SUPPRESS_NAV_ACTIVE_PLACEHOLDER`,
  `AA_STALE_BACKSTOP_MS`, `LOG_LEVEL`). Every gate is a compile-time constant, so a disabled
  feature is dead-code-eliminated and leaves no bytecode behind. Parts of the turn-by-turn feed that
  the runtime already gates — the lane row and the destination distance / travel time (present only
  when the phone sends them), the rerouting notice (protocol 1.7 only) — deliberately have no switch
  of their own.
- `de/adi961/miblogger/MIBLogger.java` — logger (also mirrors to `/dev/shmem/aatokombi.log`).
- `de/vw/mib/bap/mqbab2/navsd/functions/AANavReader.java` — polls `/dev/shmem/aa_nav`
  (written by the shim), maps the maneuver/street/distance/lanes/destination, and drives **both**
  outputs: the navsd cluster Navigation menu on a nav-capable cluster, or the media now-playing
  widget otherwise. Also owns the text renderers the media path needs (`laneRow`, `destinationRow`)
  and `selectLanes`, the six-lane clamp both paths share. Replaces the two former readers
  (`ShmemNavReader` / `NavShmemReader`).
- `…/navsd/functions/{NavState,ClusterCaps}.java` — the shared `NavState` carrier and
  `ClusterCaps` (runtime nav-capable detection via `ConfigurationService`).
- `…/target/ShmemMediaReader.java` — polls `/dev/shmem/aa_media` (written by the shim) and feeds
  the real now-playing track into the cluster when no route guidance is active.
- `…/target/NavigationHandler.java` — holds the `aaRouteGuidanceActive` flag that gates the
  media-widget injection (set from the nav status, read by `CurrentStationInfo`).

Shadows of stock classes (faithful copies of the decompiled firmware class + a minimal change).
Both cluster BAP stacks are shadowed so one build covers either — the **MQB** (`mqbab2`) and the
older **PQ** (`mqbpq`) variant. The readers above write shared static holders that both stacks read.

MQB (`mqbab2`):
- `…/target/AndroidAutoTarget.java` — the AA target; we wire in `NavigationHandler` and start the
  `AANavReader` / `ShmemMediaReader` pipeline.
- `…/navsd/functions/{ActiveRgType,RGStatus,ManeuverDescriptor,DistanceToNextManeuver,TurnToInfo}.java`
  — the navsd BAP nav functions that draw the cluster Navigation menu (arrow + distance + street)
  on a nav-capable cluster. See [../docs/NAVIGATION_VIA_NAVSD.md](../docs/NAVIGATION_VIA_NAVSD.md).
- `…/navsd/functions/{LaneGuidance,DistanceToDestination,TimeToDestination}.java` — the rest of that
  menu, fed by the protocol-1.7 fields: the lane row (fn 24 — a BAP *Array*, not a Property, so it
  diffs against the previous list and the cluster pulls with `getArray()`), and the distance (fn 21)
  and remaining travel time (fn 22) to the destination. Each keeps its stock body for the unit's own
  navigation and takes the AA branch only while `NavState.ACTIVE`.
- `…/audiosd/functions/CurrentStationInfo.java` — the audio "now playing" BAP function the
  **cluster reads**. We inject the nav text in place of the "Android Auto" label when AA route
  guidance is active (the fallback output on a non-nav cluster). Four rows: street — replaced by the
  lane row of the junction ahead while the phone sends one / maneuver word + roundabout exit / arrow
  + distance (big) / distance + time to the destination. Row layout, the glyph set and why the lanes
  live in the street row are documented in [../docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md).
- `…/audiosd/functions/CurrentStationHandle.java` — synthesises a valid station handle for the AA
  source so the centred cover-art view binds (gated on `SHOW_COVER_ART`; a no-op otherwise).
- `…/common/api/androidauto/AndroidAutoASLDataAdapter.java` — forces
  `isAndroidAutoRouteGuidanceActive()` false under `SUPPRESS_NAV_ACTIVE_PLACEHOLDER` to hide the
  stock *"Navigation on the mobile device is active"* placeholder (folds in the former
  `NavActiveIgnore.jar`).

PQ (`mqbpq`):
- `…/mqbpq/audiosd/functions/CurrentStationInfo.java` — the PQ media shadow: shows live AA nav /
  now-playing in the 3 info lines while AA is connected (fed from the MQB shadow's static holders).
- `…/mqbpq/navsd/functions/{RouteGuidanceStatus,ManeuverDescriptor,DistanceToNextManeuver,TurnToInfo}.java`
  — the PQ navsd nav functions (the PQ equivalent of the MQB navsd set above).

Compile-only:
- `org/dsi/ifc/androidauto2/Constants.java` — stub so the sources compile. The real values are
  ROM-inlined on the device, so `build.sh` **excludes** `Constants.class` from the jar.

## Build

> **Normally you don't run this directly.** The repo-root [`../build.sh`](../build.sh) is the
> supported entry point: it converts your `MIBHMI.jxe`, stages `lib/MIBHMI.jar`, and invokes this
> script inside the Docker toolchain (right JDK 8) for you — together with the shim. The steps below
> are the raw module build, for when you already have a JDK 8 and `lib/MIBHMI.jar` in place.

```sh
cp /your/firmware/MIBHMI.jar lib/MIBHMI.jar
JAVAC=/jdk8/bin/javac JAR=/jdk8/bin/jar ./build.sh   # -> build/AAtoKombi.jar
```

JDK 8 is required because `javac` must still accept `-source/-target 1.3` (the J9 VM on the
unit loads major-version 47 classes).

## Editing / shadowing another stock class
To override a different stock class: decompile it from `MIBHMI.jar` (CFR works well), drop it in
`src/` under its real package, make your change, and fix the usual `-source 1.3` decompile
artifacts:
- comment out `@Override` (not valid in 1.3),
- make autoboxing explicit (`Integer`→`.intValue()`, `Boolean`→`.booleanValue()`),
- give any uninitialised `static final` (CFR-inlined constants) a dummy initialiser.
