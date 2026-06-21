# shim — patched `libext.google.gal.receiver.so`

Google's GAL receiver library already implements every Android Auto projection endpoint
(Navigation, Phone, Media, …) but the stock TechniSat SAL only registers `BluetoothEndpoint`.
The shim adds back the navigation **and** media now-playing ones: it patches the tail of
`GalReceiver::init` to call a tiny injected routine that constructs Google's
`NavigationStatusEndpoint` and `MediaPlaybackStatusEndpoint`, gives each our listener, and
registers them. Captured turn-by-turn is written to `/dev/shmem/aa_nav` and the now-playing track
to `/dev/shmem/aa_media` for the jar to read.

## Files
- `src/navshim_fs.cpp` — the freestanding shim: the nav and media listeners, the IPC writers,
  `gal_nav_inject()`, and the `init` trampoline. `g_ref[]` are **placeholders** (zeros) — the
  injector fills in the per-firmware addresses.
- `inject.py` — opens the target libgal, **auto-resolves** the per-firmware addresses from its
  own `.dynsym` / relocations / `.plt`, bakes them into the blob's `.galrefs`, appends the linked
  blob as a new `PT_LOAD` segment, extends the relocation table, and patches the `BL` in the tail
  of `GalReceiver::init` (auto-located) to jump to the trampoline.
- `DESIGN.md` — the navigation reverse-engineering spec (endpoint layout, vtable slots, protocol
  message ids, listener interface, injection mechanics).
- `DESIGN_MEDIA.md` — the same spec for the `MediaPlaybackStatusEndpoint` (now-playing) path.

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

| `.galrefs` slot | how `inject.py` resolves it |
|---|---|
| `registerService` | `.dynsym` symbol `_ZN11GalReceiver15registerServiceEP20ProtocolEndpointBase` |
| `NavigationStatusEndpoint::start` | `.dynsym` symbol `_ZN24NavigationStatusEndpoint5startEv` |
| `NavigationStatusEndpoint` vtable | `.dynsym` symbol `_ZTV24NavigationStatusEndpoint` |
| `MediaPlaybackStatusEndpoint` vtable | `.dynsym` symbol `_ZTV27MediaPlaybackStatusEndpoint` |
| `memset` GOT slot | the `R_ARM_JUMP_SLOT` relocation whose symbol is `memset` |
| `onChannelOpened` PLT stub | the `.plt` stub that resolves the `onChannelOpened` GOT slot |
| `init` tail `BL` offset | scan `GalReceiver::init` (exported) for the `BL` whose target is that PLT stub |

`inject.py` prints the resolved values on every run; compare them against `nm -D libgal` if you
want to double-check. If the `BL` auto-locator fails on some exotic build, override it with
`--bl-offset 0xNNNN`.
