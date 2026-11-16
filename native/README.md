# native/ — DisplayManager hook for the Android Auto cluster map

`libkb_dm.so` is loaded into DisplayManager (`tsd.mibstd2.system.displaymanager`) through
`LD_PRELOAD` in `/tsd/etc/system/cpu.conf`. While the patched libgal (built with `CLUSTER_SINK=1`)
streams Android Auto's cluster video into `/dev/shmem/aatokombi-cluster-video`
(`shim/src/cluster_video_shm.h`), DisplayManager's cluster reader gets the phone's H.264 access
units instead of its own encoder's. TS packing, the ring to the J5, MOST and the cluster's decoder
stay stock. When the phone stops, the cluster is back on the stock map from the stock encoder's
next key frame.

## Credits

The DisplayManager seam (the encoder's `mmfbq_*` buffer queue in `libOpenMAXAL.so.1`, the
`MostSink` → `RingIO::writeAquire` call site, the cluster decoder's limits) was found and published
by Anton Perk in [mst2-cluster-nav](https://github.com/perkthefox/mst2-cluster-nav)
(CC BY-NC-SA 4.0), where it carries CarPlay's cluster map. This directory is an independent
implementation for Android Auto; the firmware facts were re-checked on the `MST2_EU_SK_ZR_P0480T`
dump (`libOpenMAXAL.so.1` sha256 `c31fe983…`, displaymanager `932efd26…`). Any other build: the
hook passes everything through and logs it.

## Comparison with mst2-cluster-nav, and what was taken from it

| | mst2-cluster-nav (CarPlay) | this repository (Android Auto) |
|---|---|---|
| Source of the stream | SAL hook: second AirPlay screen, own TCP receiver, AES-CTR | patched libgal: second AAP video sink (cluster display) |
| Geometry | phone encodes 1010×376 (1024×384 coded), same as the stock stream | phone encodes 1280×720 with 244 lines of margin; SPS cropped to 1280×476 |
| Bitrate cap | socket read throttled to 10 Mbit/s, 250 ms burst | **taken over**: 10 Mbit/s, 250 ms burst, enforced by delaying AAP ACKs (`CLUSTER_KBPS`) |
| Key frames | `forceKeyFrame` command | none in AAP: video focus NATIVE→PROJECTED (`CLUSTER_IDR_MODE`) |
| SAL → DM hand-off | shm, 24 × 256 000 B slots, seq-lock, semaphore, `alive_ms`, `want_idr` | **same design**, own layout (`shim/src/cluster_video_shm.h`) |
| DM seam | `mmfbq_get_and_wait` return-address check, queue found by SPS, stock frames returned, start at IDR, back to stock at its next IDR | **same**, independently written |
| MOST ring | wait up to 300 ms in `RingIO::writeAquire` from MostSink, 10 s stats line | **taken over** |
| Stock cluster map | hidden while the phone video runs (`setMapInvisible` event, resent every 2 s) | **taken over** in the jar (`de.aatokombi.KombiMap`, `Config.HIDE_STOCK_KOMBI_MAP_DURING_AA_VIDEO`); state from libgal via `/dev/shmem/aa_cluster_video` |
| Safety | kill switch, trial boot, `runHMI.sh` guard, crash guard, atomic `cpu.conf` edits with backups | kill switch, crash guard, copy-and-compare before the `cpu.conf` edit, stock copy kept; **no trial boot / `runHMI.sh` guard yet** |

What reaches MOST: while the phone owns the cluster, the stock encoder's frames are handed back
to it and never reach the TS packer, so the MOST channel carries only the phone's stream. Hiding
the stock map stops the navigation from drawing it and the encoder from encoding it (CPU); the
bitrate cap is what keeps the MOST channel from overflowing.

## Build

`CLUSTER_SINK=1 ./build.sh MIBHMI.jxe libext.google.gal.receiver.so dist-cluster` builds it together
with the jar and libgal (Docker). Stand-alone: `sh native/build.sh` (clang + ld.lld, no QNX SDK).

## Install / remove (telnet)

```sh
# FTP libkb_dm.so and install_dm_hook.sh / uninstall_dm_hook.sh to /tmp first
sh /tmp/install_dm_hook.sh /tmp/libkb_dm.so      # then reboot
sh /tmp/uninstall_dm_hook.sh                     # then reboot
```

Never install together with mst2-cluster-nav: both hook the same DisplayManager functions.

**Risk:** if the library named in `LD_PRELOAD` cannot be loaded, DisplayManager does not start and
the unit resets. The installer copies and compares the file before it edits `cpu.conf`; the stock
`cpu.conf` is kept in `/tsd/var/aatokombi/cpu.conf.stock`.

## Which firmware builds the hook supports, and how you find out

The hook is tied to one build of two files (firmware 0480): the buffer-queue functions of
`libOpenMAXAL.so.1` with the reader's call site, and - for the MOST ring wait of the pass-through
mode - one call site in the DisplayManager binary. It never assumes: inside DisplayManager it
compares them with what it knows and, on any difference, replaces nothing (log line
`libOpenMAXAL build differs: cluster video stays stock`). An unsupported firmware therefore gets
the stock cluster map, not a crash.

`install_dm_hook.sh` asks the same question **before** it edits `cpu.conf`: it loads the copied
library into a harmless process (`LD_PRELOAD=... KB_DM_CHECK=1 ls`) and reads its answer from
`/dev/shmem/aatokombi-dm.check`:

    loaded=1 omx=ok most=ok mmr=ok ring=ok

| field | ok means | otherwise |
|---|---|---|
| (the file exists) | the library can be loaded on this unit | **no install**: as a preload it would keep DisplayManager from starting |
| `omx` | libOpenMAXAL is the known build | `differs`: **no install** (the hook would be inert). `unknown`: could not be looked at from that process; decided again inside DisplayManager |
| `most` | MostSink call site as known | pass-through mode runs without the ring wait |
| `mmr` | mm-renderer client functions present | decode mode (the default) may not work |
| `ring` | `RingIO::writeAquire` found | pass-through ring wait unavailable |

`FORCE=1 sh install_dm_hook.sh ...` installs regardless. After the reboot the log must say
`libOpenMAXAL 0480 layout confirmed`. The check has been run on a host only (the ELF reader and the
installer's decisions); on a unit it is new - if it refuses a unit where the hook is known to work,
that is a bug in the check, and `FORCE=1` is the way round it.

## Off switches and diagnostics

- An SD card with an empty file `aatokombi-video-disable`, or `/tsd/var/aatokombi/video-off`:
  pass-through within 2 s.
- Crash guard, both modes: `/tsd/var/aatokombi/dm-busy` exists while the phone's video is on the
  cluster (written once per video session, removed when it ends normally). Found at start = the
  session before ended in a crash or power cut: the log says `the previous video session did not
  end normally, strike N`. Two in a row write `video-off` (reinstall, or remove `video-off` and
  `dm-strikes`, to clear). If that line appears after an ordinary ignition-off, tell the author:
  the guard would then switch the hook off after two trips.
- Shared memory with the libgal: since version 4 a 4 MB byte ring with an index of the last 512
  frames (`shim/src/cluster_video_shm.h`, host test `tests/ring`). Version 3 had 24 fixed slots of
  250 000 bytes, too small for the decode mode's key frames. libgal and libkb_dm.so of different
  versions do not talk to each other (stock map); install them together.
- Log: `/dev/shmem/aatokombi-dm.log` (at 64 KB it becomes `aatokombi-dm.log.1` and a new one starts). Counters (`rd_frames`, `rd_state`, ring waits) also appear in
  libgal's `/dev/shmem/aa_cluster_probe`.
