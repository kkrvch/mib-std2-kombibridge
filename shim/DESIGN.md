# Shim design — reverse-engineering of `NavigationStatusEndpoint`

(Addresses below are Ghidra image-base = `nm` value + 0x10000, from the dev unit's libgal; the
layout is build-independent, only the absolute addresses differ.)

## The idea
Android Auto navigation reception is **fully implemented inside the GAL library** — the
`NavigationStatusEndpoint` class does discovery (advertising the service to the phone), start
(requesting navigation), protobuf parsing and dispatch. It is simply **never instantiated** (no
constructor is exported, only destructors; and the SAL never creates it). So the shim only has to:
**construct the endpoint, give it our own listener, and register it with the router.** The
protocol does not need reversing — we reuse the library's code.

```
phone (AA) → GAL lib (SHIM): build NavigationStatusEndpoint + our listener + register
   → routeMessage(protobuf) → handle*Event → OUR listener → /dev/shmem → HMI jar
```

Both ends are ours (swappable lib + our jar). Core SAL and its DSI stub are untouched.

## Protocol (`routeMessage` @0x85950, vtable slot +0x1c)
Incoming protobuf by msg-id (`param_2`):
- `0x8003` = NavigationStatus              → handleNavigationStatus
- `0x8004` = NavigationNextTurnEvent       → handleNavigationNextTurnEvent
- `0x8005` = NavigationNextTurnDistanceEvent → handleNavigationDistanceEvent

Outgoing: `start()` @0x8576c sends NavigationStatusStart (msg `0x8001`).

## `NavigationStatusEndpoint` object layout (vptr → vtable @0x10a4c8 + 8)
- `+0x00` vptr
- `+0x04` byte — started flag (set/checked by start())
- `+0x05` byte — channel id (queueOutgoing)
- `+0x08` MessageRouter*   ← set at registration
- `+0x0c` byte — SERVICE ID ← registerService stores the endpoint in `router[(id+0x40)*4]`;
  addDiscoveryInfo advertises `Service.id = this[0xc]`
- `+0x14` int — NavigationStatusService field (advertised)
- `+0x18/0x1c/0x20` image options (w/h/depth) — for the cluster image when clusterType==1
- `+0x24` int — InstrumentClusterType ← validated by `*_InstrumentClusterType_IsValid` and advertised
- `+0x28` ptr — refcounted shared (atomic; operator_delete in dtor)
- `+0x2c` LISTENER* ← **where the data goes (our hook)**

## ProtocolEndpointBase vtable (NavigationStatusEndpoint, vptr = vtable+8)
`+0x8` ~dtorD1, `+0xc` ~dtorD0, `+0x10` onChannelClosed, `+0x14` mayOpenChannel,
`+0x18` onChannelOpened, `+0x1c` routeMessage, `+0x20` handleRawMessage, `+0x24` addDiscoveryInfo.

## Listener interface (the object at endpoint+0x2c) — THIS is what we implement
vtable of our listener:
- `+0x04` dtor
- `+0x08` onStatus(uint32 status)   // NavigationStatus enum: UNAVAILABLE=0, ACTIVE=1, INACTIVE=2
- `+0x0c` onNextTurn(Road* str, TurnSide, Event, Image* str, TurnAngle, TurnNumber)
- `+0x10` onDistance(DistanceMeters, TimeToTurnSeconds, field3, DisplayUnit)

(The exact fields the device handler forwards to onNextTurn/onDistance — and which protobuf
"has" bits gate them — are what `navshim_fs.cpp` encodes; see its comments.)

## registerService (MessageRouter @0x86100)
```
if (ep[0xc] != 0xff && router[(ep[0xc]+0x40)*4] == 0) { router[(ep[0xc]+0x40)*4] = ep; return 1; }
```
`GalReceiver::registerService` is a thin thunk over it. The MessageRouter is embedded at
`receiver+4`. The service id is **not a magic constant** — it is a free channel id (Video/Audio/
Input/BT/Phone already took theirs); the shim scans the router table and picks the first free slot.

## Injection
`GalReceiver::init` builds the receiver and registers its own control endpoint. We patch the
**tail of init** so it additionally calls `gal_nav_inject(receiver)`, which constructs the
NavigationStatusEndpoint, points `+0x2c` at our listener, and registers it. The library is the
swappable `/tsd/lib/sal/gal/libext.google.gal.receiver.so` — we patch **that**, never the core
SAL. `start()` has no caller in the library; we let the phone open the nav channel after auth
(see the note in `navshim_fs.cpp` on why start() must not run at init).

## Constants
- InstrumentClusterType valid = {1,2}. Type 1 = image (uses ImageOptions). **Type 2 = enum/text**
  → we use 2 for the monochrome cluster: the phone sends maneuver-enum + roadName + distance,
  exactly what the handlers decode.
- Endpoint sizeof ≈ 0x30 (the deleting dtor calls operator_delete without a size); we allocate
  0x40 and build the object by hand (memset 0, vptr=&vtable+8, router, serviceId, clusterType=2,
  listener).
- maneuver enum = standard AA NextTurnEnum {UNKNOWN, DEPART, NAME_CHANGE, SLIGHT_TURN, TURN,
  ON/OFF_RAMP, FORK, MERGE, ROUNDABOUT_ENTER/EXIT, STRAIGHT, FERRY*, DESTINATION} +
  TurnSide{LEFT, RIGHT, UNSPECIFIED}.

## The same recipe for other endpoints
The library also exposes `PhoneStatusEndpoint`, `MediaPlaybackStatusEndpoint`,
`NotificationEndpoint` — all ignored by the SAL. The same construct + listener + register pattern
applies (see ../docs/ARCHITECTURE.md).
