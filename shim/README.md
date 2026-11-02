# shim — patched `libext.google.gal.receiver.so`

Google's GAL receiver library already implements every Android Auto projection endpoint
(Navigation, Phone, Media, …) but the stock TechniSat SAL only registers `BluetoothEndpoint`.
The shim adds back the navigation **and** media now-playing ones: it patches the tail of
`GalReceiver::init` to call a tiny injected routine that constructs Google's
`NavigationStatusEndpoint` and `MediaPlaybackStatusEndpoint`, gives each our listener, and
registers them. Captured turn-by-turn is written to `/dev/shmem/aa_nav` and the now-playing track
to `/dev/shmem/aa_media` for the jar to read.

It also hooks `GalReceiver::shutdown` — the symmetric session-teardown edge — to signal
disconnect: both IPC files carry a `connected` flag (flipped to 0 the moment the session tears
down) and a `session` epoch (bumped on every reconnect by the `init` hook), so the jar clears
stale nav/track the instant the phone drops instead of freezing the last frame.

The **advertised AA protocol version is raised to 1.7** (`build.sh` passes `--aa-protocol-minor 7`).
The phone then sends the modern `NavigationState` / `NavigationCurrentPosition` messages — lane
guidance, the distance and travel time to the destination, a rerouting state — and stops sending the
legacy turn events the stock library decoded for us. Those messages are ours to parse, so the nav
endpoint's `routeMessage` vtable slot is replaced with `nav_route()` and the stock dispatcher is
never called: it is a 1.3-era parser, and handed a 1.7 payload it eventually answers the phone
"unexpected message" — which the phone answers by tearing the session down, once per route
recalculation. No 1.3 fallback is left, so a session that negotiates below 1.7 has no nav at all.
That case is diagnosable rather than silent: bypassing the stock dispatcher also throws away the
only record of what else the phone sends on this channel, so `nav_route()` keeps a bounded census of
the message ids it swallows in `/dev/shmem/aa_msgids` (one line per id per content change, plus
per-session totals). Legacy `8004`/`8005` ids showing up there mean the session did not negotiate 1.7.

## Files
- `src/navshim_fs.cpp` — the freestanding shim: the nav and media listeners, the IPC writers,
  `gal_nav_inject()`, the `init` trampoline, and the `shutdown` trampoline (`gal_shutdown_hook`).
  `g_ref[]` are **placeholders** (zeros) — the injector fills in the per-firmware addresses.
- `src/navproto.h` — the hand-rolled AA 1.7 wire decoders (a bounded protobuf reader plus the
  maneuver / lane / distance schema). Deliberately free of any ARM or libgal dependency so it can be
  compiled and unit-tested on the host: `tests/shim/run.sh` runs it under ASan+UBSan.
- `inject.py` — opens the target libgal, **auto-resolves** the per-firmware addresses from its
  own `.dynsym` / relocations / `.plt`, bakes them into the blob's `.galrefs`, appends the linked
  blob as a new `PT_LOAD` segment, extends the relocation table, and patches **two** `BL`s (both
  auto-located): the tail call in `GalReceiver::init` → `init_trampoline` (session up) and the
  first call in `GalReceiver::shutdown` → `shutdown_trampoline` (session down). With
  `--aa-protocol-minor` it also rewrites the version byte `Controller::sendVersionRequest` stores —
  located by its `strb` instruction, so no absolute address is baked in.
- `DESIGN.md` — the navigation reverse-engineering spec (endpoint layout, vtable slots, protocol
  message ids, listener interface, injection mechanics).
- `DESIGN_MEDIA.md` — the same spec for the `MediaPlaybackStatusEndpoint` (now-playing) path.
- `DESIGN_VIDEO.md` — the MST2/i.MX6 capture-only VideoSink probe boundary; it deliberately does
  not select an AID display surface or alter the existing nav/media endpoints.
- `DESIGN_VIDEO_PIPELINE.md` — the next full H.264 → i.MX6 VPU → QNX Screen → cluster/MOST
  pipeline, based on the confirmed target firmware exports and the recovered callback contract.
- `re/FindDisconnect.java` — the Ghidra RE helper that located the `GalReceiver::shutdown`
  teardown edge the disconnect hook patches.

## Build
```sh
./build.sh /your/unit/libext.google.gal.receiver.so   # -> build/libext.google.gal.receiver.so
# Optional, capture-only VideoSink callback probe (no display output):
VIDEO_PROBE=1 ./build.sh /your/unit/libext.google.gal.receiver.so
# Optional, heavyweight forensic mode (not for normal runtime use):
VIDEO_PROBE=1 VIDEO_PROBE_DEEP=1 ./build.sh /your/unit/libext.google.gal.receiver.so
# Optional, bounded H.264 ingress stage (no decoder/display yet):
VIDEO_PIPELINE=1 ./build.sh /your/unit/libext.google.gal.receiver.so
```
Requirements: `clang`, `ld.lld` (`brew install lld`), `python3` + `pyelftools`.

`VIDEO_PIPELINE=1` installs the same callback trampolines but adds a bounded RAM-only queue. It
retains SPS/PPS, drops non-IDR access units until recovery, copies accepted Annex-B units before
the stock callback continues to its normal ACK path, and resets the queue on the AA disconnect
edge. This is the ingestion stage of the patch; it does not yet claim to render decoded frames on
MOST/AID. Keep it separate from `VIDEO_PROBE_DEEP=1`: deep forensic scans were observed to reboot
the HU and are not part of the runtime pipeline.

## Per-firmware addresses — auto-resolved
The addresses differ between libgal builds (even between two copies of the same build — e.g. the
on-device copy and the firmware-image copy are shifted by 8 bytes). `inject.py` therefore derives
them **from the target libgal you pass it**, and bakes them into the blob's `.galrefs` slots
before relativizing — nothing is hardcoded:

The eight `.galrefs` slots (in `g_ref[]` enum order; the last two are optional probe targets):

| `.galrefs` slot | how `inject.py` resolves it |
|---|---|
| `registerService` | `.dynsym` symbol `_ZN11GalReceiver15registerServiceEP20ProtocolEndpointBase` |
| `NavigationStatusEndpoint` vtable | `.dynsym` symbol `_ZTV24NavigationStatusEndpoint` |
| `memset` GOT slot | the `R_ARM_JUMP_SLOT` relocation whose symbol is `memset` |
| `onChannelOpened` PLT stub | the `.plt` stub that resolves the `onChannelOpened` GOT slot |
| `MediaPlaybackStatusEndpoint` vtable | `.dynsym` symbol `_ZTV27MediaPlaybackStatusEndpoint` |
| `MessageRouter::shutdown` | `.dynsym` symbol `_ZN13MessageRouter8shutdownEv` (the call the shutdown hook displaces + re-runs) |
| `VideoSink::handleDataAvailable` | `.dynsym` symbol `_ZN9VideoSink19handleDataAvailableEyRK10shared_ptrI8IoBufferEj` (used only by `--video-probe`) |
| `VideoSink::handleCodecConfig` | `.dynsym` symbol `_ZN9VideoSink17handleCodecConfigEPvj` (used only by `--video-probe`) |

Plus the two `BL` patch sites (auto-located, each overridable):

| `BL` patch site | how `inject.py` resolves it | override |
|---|---|---|
| `init` tail `BL` | scan `GalReceiver::init` for the `BL` whose target is the `onChannelOpened` PLT stub | `--bl-offset 0xNNNN` |
| `shutdown` first `BL` | scan `GalReceiver::shutdown` for the first `BL` to the `MessageRouter::shutdown` PLT stub | `--bl-offset-shutdown 0xNNNN` |

`inject.py` prints the resolved values on every run; compare them against `nm -D libgal` if you
want to double-check. If a `BL` auto-locator fails on some exotic build, override it with
`--bl-offset 0xNNNN` (init) or `--bl-offset-shutdown 0xNNNN` (shutdown).
The probe uses two additional ARM `B` patches at the VideoSink entry points and refuses to write
if their target prologues differ from the verified target build.

When the probe is installed on the unit, it writes the following RAM-only artifacts under
`/dev/shmem/`: `aa_video_probe` (summary), `aa_video_probe_samples` (sample history),
`aa_video_probe_config` (bounded codec-config bytes), `aa_video_probe_payload` (bounded candidate
payload), `aa_video_probe_forensic.bin` (eight raw stack/object/descriptor snapshots for
offline analysis), and `aa_video_probe_candidates.bin` (candidate base/offset/end ranges with
up to 512 bytes from each range). Copy all of them before rebooting the unit.

`VIDEO_PROBE_DEEP=1` enables the large raw object/candidate snapshots. Keep it separate from the
normal probe: the deep mode adds direct memory scans and substantial RAM-file writes and is intended
for a single controlled forensic run.
