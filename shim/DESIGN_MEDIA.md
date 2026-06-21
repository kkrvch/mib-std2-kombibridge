# Shim design — reverse-engineering of `MediaPlaybackStatusEndpoint`

Companion to [`DESIGN.md`](DESIGN.md) (Navigation). Same idea: Google's GAL library fully
implements `MediaPlaybackStatusEndpoint` (advertise → phone opens channel → protobuf parse →
dispatch to a listener), but the stock SAL never instantiates it. The shim constructs it, gives
it our listener, and registers it — so the real now-playing track (Song/Artist/Album) reaches the
HMI. Addresses below are from the dev unit's libgal (auto-resolved by `inject.py`; the
*offsets* documented here are build-independent).

## Relevant exports
```
0x83ad0  MediaPlaybackStatusEndpoint::routeMessage(u8, u16 msgId, IoBuffer&)
0x83584  MediaPlaybackStatusEndpoint::handleMediaPlaybackStatus(const MediaPlaybackStatus&)
0x836f0  MediaPlaybackStatusEndpoint::handleMediaPlaybackMetadata(const MediaPlaybackMetadata&)
0x83498  MediaPlaybackStatusEndpoint::addDiscoveryInfo(ServiceDiscoveryResponse*)
0x15e790 _ZTV27MediaPlaybackStatusEndpoint   (vptr = &vtable + 8)
```

## Protocol (`routeMessage`)
Incoming protobuf dispatched by msg-id (`param_2`):
- `0x8001` = MediaPlaybackStatus    → handleMediaPlaybackStatus
- `0x8003` = MediaPlaybackMetadata  → handleMediaPlaybackMetadata

## Endpoint object layout (differs from Navigation!)
`MediaPlaybackStatusEndpoint` shares the `ProtocolEndpointBase` base (vptr/router/serviceId/
channel/started), but its **listener pointer is at `+0x18`** (Navigation's is at `+0x2c`):
- `+0x00` vptr
- `+0x04` started flag
- `+0x05` channel id
- `+0x08` MessageRouter*           ← base class, set at registration (same as nav)
- `+0x0c` byte SERVICE ID          ← `addDiscoveryInfo` advertises `this[0xc]`; registerService uses it
- `+0x18` LISTENER*                ← **our hook** (both handlers null-check & use `[this,#0x18]`)

`addDiscoveryInfo` (0x83498) only reads `this[0xc]` (service id) — there is **no
InstrumentClusterType** to set (that was nav-specific). So construction is simpler than nav.

## Listener interface (object at endpoint+0x18) — what we implement
Listener vtable (vptr points at slot s0; same convention as nav's ListVT):
- `+0x04` dtor
- `+0x08` onStatus(...)    ← from handleMediaPlaybackStatus (playback state/flags/seconds) — not used yet
- `+0x0c` onMetadata(self, std::string arr[5], int durationSeconds, int rating)

### Metadata fields (`MediaPlaybackMetadata`, from `k*FieldNumber` + parser offsets)
Parsed-struct offsets (field-number order; first five are `std::string`, CoW = a single `char*`):
```
+0x10 Song(1)  +0x14 Artist(2)  +0x18 Album(3)  +0x1c AlbumArt(4,bytes)  +0x20 Playlist(5)
+0x24 DurationSeconds(6)   +0x28 Rating(7)
```
`handleMediaPlaybackMetadata` copies the five strings into the listener arg buffer in this
**reordered** sequence (this is the order our listener receives `arr[0..4]`):
```
arr[0] = Song      (from +0x10)
arr[1] = Album     (from +0x18)
arr[2] = Artist    (from +0x14)
arr[3] = AlbumArt  (from +0x1c)   — binary, ignored (mono cluster)
arr[4] = Playlist  (from +0x20)
```
plus `durationSeconds` (+0x24) and `rating` (+0x28) passed after the array. Each `arr[i]` is a
4-byte CoW `std::string`; its char data = `*(char**)&arr[i]` (same `rd_string` trick as nav).

→ For the now-playing feature we read **arr[0]=Song, arr[2]=Artist, arr[1]=Album**.

## Construction / registration (mirrors nav, see navshim_fs.cpp gal_nav_inject)
memset 0x40-byte static buffer; `vptr = &_ZTV27MediaPlaybackStatusEndpoint + 8`; set router at
`+0x08`, a free service id (via `pick_free_id`) at `+0x0c` and `+0x05`, listener at **`+0x18`**;
then `GalReceiver::registerService(receiver, ep)`. No `start()` at init — the phone opens the
channel after auth and pushes status/metadata, exactly like nav.

## Other endpoints
`PhoneStatusEndpoint` is the remaining one (caller id); same recipe — see ARCHITECTURE.md.
