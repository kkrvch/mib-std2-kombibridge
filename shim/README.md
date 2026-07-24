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

## Files
- `src/navshim_fs.cpp` — the freestanding shim: the nav and media listeners, the IPC writers,
  `gal_nav_inject()`, the `init` trampoline, and the `shutdown` trampoline (`gal_shutdown_hook`).
  `g_ref[]` are **placeholders** (zeros) — the injector fills in the per-firmware addresses.
- `inject.py` — opens the target libgal, **auto-resolves** the per-firmware addresses from its
  own `.dynsym` / relocations / `.plt`, bakes them into the blob's `.galrefs`, appends the linked
  blob as a new `PT_LOAD` segment, extends the relocation table, and patches **two** `BL`s (both
  auto-located): the tail call in `GalReceiver::init` → `init_trampoline` (session up) and the
  first call in `GalReceiver::shutdown` → `shutdown_trampoline` (session down).
- `DESIGN.md` — the navigation reverse-engineering spec (endpoint layout, vtable slots, protocol
  message ids, listener interface, injection mechanics).
- `DESIGN_MEDIA.md` — the same spec for the `MediaPlaybackStatusEndpoint` (now-playing) path.
- `re/FindDisconnect.java` — the Ghidra RE helper that located the `GalReceiver::shutdown`
  teardown edge the disconnect hook patches.

## Build
```sh
./build.sh /your/unit/libext.google.gal.receiver.so   # -> build/libext.google.gal.receiver.so
```
Requirements: `clang`, `ld.lld` (`brew install lld`), `python3` + `pyelftools`.

## Per-firmware addresses — auto-resolved
The addresses differ between libgal builds (even between two copies of the same build — e.g. the
on-device copy and the firmware-image copy are shifted by 8 bytes). `inject.py` therefore derives
them **from the target libgal you pass it**, and bakes them into the blob's `.galrefs` slots
before relativizing — nothing is hardcoded:

The six `.galrefs` slots (in `g_ref[]` enum order):

| `.galrefs` slot | how `inject.py` resolves it |
|---|---|
| `registerService` | `.dynsym` symbol `_ZN11GalReceiver15registerServiceEP20ProtocolEndpointBase` |
| `NavigationStatusEndpoint` vtable | `.dynsym` symbol `_ZTV24NavigationStatusEndpoint` |
| `memset` GOT slot | the `R_ARM_JUMP_SLOT` relocation whose symbol is `memset` |
| `onChannelOpened` PLT stub | the `.plt` stub that resolves the `onChannelOpened` GOT slot |
| `MediaPlaybackStatusEndpoint` vtable | `.dynsym` symbol `_ZTV27MediaPlaybackStatusEndpoint` |
| `MessageRouter::shutdown` | `.dynsym` symbol `_ZN13MessageRouter8shutdownEv` (the call the shutdown hook displaces + re-runs) |

Plus the two `BL` patch sites (auto-located, each overridable):

| `BL` patch site | how `inject.py` resolves it | override |
|---|---|---|
| `init` tail `BL` | scan `GalReceiver::init` for the `BL` whose target is the `onChannelOpened` PLT stub | `--bl-offset 0xNNNN` |
| `shutdown` first `BL` | scan `GalReceiver::shutdown` for the first `BL` to the `MessageRouter::shutdown` PLT stub | `--bl-offset-shutdown 0xNNNN` |

`inject.py` prints the resolved values on every run; compare them against `nm -D libgal` if you
want to double-check. If a `BL` auto-locator fails on some exotic build, override it with
`--bl-offset 0xNNNN` (init) or `--bl-offset-shutdown 0xNNNN` (shutdown).
