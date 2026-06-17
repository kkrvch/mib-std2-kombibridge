# jar — HMI Java mod (`AAtoKombi.jar`)

Loaded on the unit via `-Xbootclasspath/p:` (prepended ahead of the firmware classes), so our
classes shadow the stock ones with the same fully-qualified names.

## Sources (`src/`)

Our own code:
- `de/adi961/miblogger/MIBLogger.java` — logger (also mirrors to `/dev/shmem/aatokombi.log`).
- `de/vw/mib/asl/internal/androidauto/target/ShmemNavReader.java` — polls `/dev/shmem/aa_nav`
  (written by the shim), formats the maneuver/street/distance, drives the cluster.
- `…/target/ShmemMediaReader.java` — polls `/dev/shmem/aa_media` (written by the shim) and feeds
  the real now-playing track into the cluster when no route guidance is active.
- `…/target/NavigationHandler.java` — holds the `aaRouteGuidanceActive` flag that gates the
  injection (set from the nav status, read by `CurrentStationInfo`).

Shadows of stock classes (faithful copies of the decompiled firmware class + a minimal change):
- `…/target/AndroidAutoTarget.java` — the AA target; we wire in `NavigationHandler` and start the
  `ShmemNavReader` / `ShmemMediaReader` pipeline.
- `de/vw/mib/bap/mqbab2/audiosd/functions/CurrentStationInfo.java` — the audio "now playing"
  BAP function the **cluster reads**. We inject the nav text in place of the "Android Auto"
  label when AA route guidance is active.

Compile-only:
- `org/dsi/ifc/androidauto2/Constants.java` — stub so the sources compile. The real values are
  ROM-inlined on the device, so `build.sh` **excludes** `Constants.class` from the jar.

## Build

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
