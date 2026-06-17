# AAtoKombi — Android Auto navigation on the MIB2 STD2 instrument cluster

Show Android Auto turn-by-turn navigation (and player info) on the
instrument cluster of a **VW MIB2 STD2 / MST2** unit (TechniSat / Preh).

```
   →  Turn right
      Hauptstrasse
      300 m
```

Stock MIB2 STD2 cannot do this: the firmware deliberately leaves the Android Auto
navigation bridge disabled, and the cluster is not coded for navigation. AAtoKombi adds the
missing pieces — without reflashing the firmware and **fully reversible**.

> **Status:** working proof of concept (navigation in the cluster's media widget).
> Personal/educational modding project. Use COMPLETELY at your own risk.

---

## How it works (in one picture)

```
 Android Auto (phone)
      │  turn-by-turn is inside Google's libext.google.gal.receiver.so,
      │  but the stock SAL never registers the navigation endpoint
      ▼
 ┌─ shim/ ──────────────────────────────────────────────┐
 │ patched libgal: registers Google's NavigationStatus   │
 │ endpoint itself, captures road/maneuver/distance,     │
 │ writes them to /dev/shmem/aa_nav                       │
 └───────────────────────────────────────────────────────┘
      │
      ▼  /dev/shmem/aa_nav   (plain text, one line per update)
 ┌─ jar/ ───────────────────────────────────────────────┐
 │ HMI Java mod (loaded via -Xbootclasspath/p): reads     │
 │ aa_nav and renders the text. The cluster won't accept  │
 │ nav in its "nav" slot, so we inject it into the MEDIA  │
 │ now-playing widget (CurrentStationInfo) — which the    │
 │ cluster already draws ("Android Auto" label).          │
 └───────────────────────────────────────────────────────┘
      │
      ▼
 Instrument cluster — Media tab shows the live maneuver
```

Two key findings drive the design (both verifiable on the binaries):

1. **The data is already in the firmware, just disabled.** Google's
   `libext.google.gal.receiver.so` fully implements `NavigationStatusEndpoint`
   (handlers + vtable), but the stock SAL references it **0 times** — it only registers
   `BluetoothEndpoint`. The shim registers it for us. (Same story for Phone and Media —
   see `docs/`.)
2. **The cluster won't take nav in the nav slot** (the unit isn't nav-coded), but it *does*
   render the media now-playing widget. So we put the nav text there.

Full write-up: [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## Repository layout

```
jar/      HMI Java mod  → builds AAtoKombi.jar
  src/        our sources (incl. faithful shadows of two stock classes)
  build.sh    one-command build (javac + jar)
  lib/        ← MIBHMI.jar (staged here by build.sh from the MIBHMI.jxe)

shim/     native libgal patch → builds the patched .so
  src/navshim_fs.cpp   freestanding ARM listener (nav + media → /dev/shmem)
  inject.py            ELF injector (auto-resolves addresses, adds the blob, patches init's BL)
  build.sh             compile + link + inject
  DESIGN.md            nav reverse-engineering spec
  DESIGN_MEDIA.md      now-playing (MediaPlaybackStatusEndpoint) reverse-engineering spec
  lib/        ← put unit's libext.google.gal.receiver.so here

docs/     architecture, the shim build/RE notes, phone/media research

build.sh          one-button build of BOTH artifacts from the toolbox dumps (all in Docker)
docker/           the build toolchain image (JDK 8 + clang + lld + pyelftools)
tools/
  extract-mibhmi.sh   (optional) install + verify a firmware's MIBHMI.jar against the shadows
```

The on-device **deployment** (SD card, Green Engineering Menu, activate/deactivate scripts)
lives in a separate toolbox repository —
[olli991/mib-std2-pq-zr-toolbox](https://github.com/olli991/mib-std2-pq-zr-toolbox) — this repo
only builds the two artifacts.

---

## Patch the unit (step by step)

You build the two artifacts **against the exact files from your own unit**, then deploy them back
with the toolbox. Nothing here is firmware-version-specific — the shim auto-adapts (verified on
P0253 / P0369 / P0480).

### 0. Prerequisites (once)
- **The deployment toolbox** ([olli991/mib-std2-pq-zr-toolbox](https://github.com/olli991/mib-std2-pq-zr-toolbox)) set up for your unit.
- **Docker** — the whole build runs in containers (jxe2jar + the JDK 8 / clang / lld / pyelftools
  toolchain), so nothing else needs installing.

### 1. Pull the two files off the unit (with the toolbox)
Use the toolbox's **"Dump files"** menu item to copy these from the unit to your computer:
- **`MIBHMI.jxe`** — the HMI executable (from `/tsd/hmi/ifs/`). `build.sh` turns it into the HMI
  compile classpath (`MIBHMI.jar`) for you, inside Docker.
- **`libext.google.gal.receiver.so`** — the stock GAL library (from `/tsd/lib/sal/gal/`). This is
  the file we patch, so it **must be the copy from the unit** (different firmware builds differ —
  that's exactly what the shim's auto-resolve handles).

> Keep an untouched backup of the original `libext.google.gal.receiver.so` — the toolbox's
> Disable step restores it, but a manual backup is cheap insurance.

### 2. Build both artifacts
```sh
./build.sh /path/to/your/MIBHMI.jxe /path/to/your/libext.google.gal.receiver.so
# -> dist/AAtoKombi.jar                  (the HMI mod)
# -> dist/libext.google.gal.receiver.so  (the patched libgal)
```
Everything runs in Docker: it converts the `.jxe` to a jar, builds the toolchain image (cached
after the first run), compiles both artifacts inside the container with the repo bind-mounted, and
drops the two deployables in `dist/`. Inputs and outputs are only ever bind-mounted.

`inject.py` prints the per-firmware addresses it **auto-resolved** from your libgal:
```
resolved galrefs from libgal:
    R_REGISTER     = 0x...   R_EP_START   = 0x...   R_NAV_VTABLE   = 0x...
    R_GOT_MEMSET   = 0x...   R_ONCHOPEN   = 0x...   R_MEDIA_VTABLE = 0x...
BL patch site: file offset 0x... [auto]
```
Optional sanity-check: these match `nm -D your_libgal`. If the `BL` auto-locator ever fails on an
unusual build, pass `--bl-offset 0xNNNN` to `inject.py`.

> The jar contains faithful **shadows** of two stock classes (`CurrentStationInfo`,
> `AndroidAutoTarget`). They are P0480-derived; within a firmware branch they are usually
> compatible — rebuilding against *your* `MIBHMI.jar` (which `build.sh` produces from the `.jxe`)
> keeps them in sync.

### 3. Deploy both artifacts back to the unit (with the toolbox)
Hand the two build outputs to the toolbox's install/enable step, which:
- installs `jar/build/AAtoKombi.jar` and adds it to the HMI `-Xbootclasspath/p` (in `runHMI.sh`),
- swaps in `shim/build/libext.google.gal.receiver.so` (keeping a backup of the original).

It's **fully reversible** — the toolbox's Disable removes the jar from the bootclasspath and
restores the original libgal. Nothing is written to flash by the mod itself (the captured data
goes through `/dev/shmem`, a RAM filesystem).

### 4. Verify on the unit
- Connect your phone over Android Auto and start navigation → the cluster's media tab shows the
  maneuver + street; play music with no active route → it shows the real Title/Artist/Album.
- If something's off, check `/dev/shmem/aa_nav.dbg` (should read `... libc=ok ... registered ...
  media-registered`) and the `MIBLogger` output for `ShmemNavReader` / `ShmemMediaReader` lines.

### Advanced (optional)
- **Verify the shadows against your firmware first.** If you already have a `MIBHMI.jar` (extract it
  from the dumped HMI IFS with `dumpifs`), check it before building:
  ```sh
  CFR=/path/to/cfr.jar tools/extract-mibhmi.sh /path/to/your/MIBHMI.jar
  ```
  It installs the jar at `jar/lib/MIBHMI.jar` and runs `javap` checks that the stock classes we
  shadow (`CurrentStationInfo`, `AndroidAutoTarget`, …) still expose the members our shadows rely
  on (with `$CFR` set it also decompiles them into `jar/reference/` for a manual diff against
  `jar/src/`).
- **Build a single piece locally** (without the Docker toolchain — needs **JDK 8** whose `javac`
  still accepts `-source/-target 1.3`, plus `clang`, `ld.lld`, `python3` + `pyelftools`):
  ```sh
  ( cd jar  && JAVAC=/jdk8/bin/javac JAR=/jdk8/bin/jar ./build.sh )      # -> jar/build/AAtoKombi.jar
  ( cd shim && ./build.sh /your/unit/libext.google.gal.receiver.so )    # -> shim/build/libext.google.gal.receiver.so
  ```
  (`jar/build.sh` still needs a `MIBHMI.jar` at `jar/lib/`; the `.jxe → .jar` conversion itself is
  Docker-only.)

---

## What works / what's planned

| Feature | State | Notes |
|---|---|---|
| Nav maneuver + street on cluster | ✅ working | via media widget |
| Distance / time-to-turn | 🟡 partial | shim only forwards the field the device handler passes; 0 in tests so far (needs a drive-test or a deeper distance hook) |
| Real now-playing track on cluster | ✅ built (HW test pending) | patched GAL `MediaPlaybackStatusEndpoint` → `/dev/shmem/aa_media` → media widget; shown when no route guidance is active. See `shim/DESIGN_MEDIA.md` |
| Caller ID from AA (phone) | 🔎 researched | AA has a full `PhoneStatusEndpoint`; same shim+shadow recipe |

ETA-to-destination is **not** available — Android Auto does not project it to the car
(only next-turn data). See `docs/ARCHITECTURE.md`.

## Roadmap
- ✅ Auto-resolve shim addresses from the target libgal (symbols + relocations + `.plt`, and the
  `init` `BL` site) so one package adapts to any firmware version without manual reversing.
- ✅ Real now-playing track from AA (`MediaPlaybackStatusEndpoint`) — same shim recipe as nav.
- Phone caller-ID feature.

## Credits
AAtoKombi is a port of **[adi961/mib2-android-auto-vc](https://github.com/adi961/mib2-android-auto-vc)**
(Android Auto navigation on the cluster for MIB2 **High** / MHI2) to MIB2 **STD2** / MST2. The HMI
side — the AA target handling, the navigation handler, and the `de.adi961.miblogger` logger — comes
from that project. adi961's mod in turn builds on the LSD/bootclasspath work of
[grajen3](https://github.com/grajen3/mib2-lsd-patching) and
[andrewleech](https://github.com/andrewleech). The STD2 shim and the media-widget approach are new
here.

## Legal
This project contains faithful reproductions of decompiled VW/TechniSat HMI classes (the two
"shadow" classes) needed to override stock behaviour. They are derived from firmware you own.
No firmware binaries (`MIBHMI.jar`, `libgal`) are distributed here — you extract them from
your own unit. For personal/educational use.
