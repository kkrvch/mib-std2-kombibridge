# Architecture & reverse-engineering notes

How AAtoKombi gets Android Auto navigation onto a non-nav MIB2 STD2 cluster, and the evidence
behind each design decision.

## The two blockers

### Blocker 1 — the SAL never enables the AA navigation bridge
Android Auto's turn-by-turn is carried by Google's GAL receiver library
(`libext.google.gal.receiver.so`). That library *fully implements* the navigation endpoint:

```
nm -D libgal | grep NavigationStatusEndpoint
  0008a6a0  handleNavigationStatus
  0008a6c8  handleNavigationDistanceEvent
  0008aab4  handleNavigationNextTurnEvent
  0008abc8  routeMessage
  0015e928  vtable
```

But the stock SAL (`tsd.mibstd2.sal.main`) references it **zero times** — it only ever sets up
`BluetoothEndpoint`:

```
strings SAL | grep -oE '[A-Za-z]+Endpoint' | sort | uniq -c
   7 BluetoothEndpoint
   6 ProtocolEndpoint
   # NavigationStatusEndpoint / PhoneStatusEndpoint / MediaPlaybackStatusEndpoint : 0
```

The SAL *does* import the machinery to register services (`GalReceiver::registerService`,
`init`, `start`) — it simply never registers the nav one. The HMI-facing DSI bridge for nav is
also stubbed at runtime ("Unsupported DSI function called by HMI").

**Empirical confirmation:** before the shim there is no AA nav data anywhere in the HMI; after
the shim (whose only job is to register the endpoint) the data appears in `/dev/shmem/aa_nav`.

→ **Fix:** the shim registers `NavigationStatusEndpoint` itself. See [`../shim/`](../shim/).

### Blocker 2 — the cluster won't take nav in the "nav" slot
The proper path would be the navsd BAP function group (LSG 50) → cluster. But on a unit that
isn't nav-coded the cluster never subscribes to it: force-starting the navsd control unit at
runtime did not make the cluster engage (and disturbed the phone/audio BAP). The cluster simply
isn't configured to expect navigation from this head unit. And the head unit cannot produce any nav data.

→ **Fix:** render the nav text in a widget the cluster *does* read — the **media now-playing**
widget (`CurrentStationInfo`, audio BAP LSG 49). That's the one that shows the "Android Auto"
label when a phone is connected, so it is provably subscribed and drawn. We replace that label
with the live maneuver/street/distance. It's a passive text widget, so there are no side
effects.

→ **On a nav-capable cluster (e.g. Amundsen) this blocker does not apply:** the navsd group *is*
subscribed, so the same reader (`AANavReader`) drives the real cluster **Navigation** menu — arrow
+ distance + street, with the cluster's own approach animation — instead of the media widget. The
output path is chosen at runtime by `ClusterCaps.isNavCapable()`. See
[NAVIGATION_VIA_NAVSD.md](NAVIGATION_VIA_NAVSD.md).

## Data flow

```
phone ── AA ──► libgal (shim) ──► /dev/shmem/aa_nav ──► jar ──► CurrentStationInfo ──► cluster
                 registers nav      "seq status event       reads &        (media widget,
                 endpoint, writes    side angle number      formats        LSG 49)
                 each callback       dist time unit road"
```

`/dev/shmem` is a RAM filesystem (the SAL itself logs there), so nothing touches flash. (This is
the non-nav path. On a nav-capable cluster the jar feeds the same shmem data into the navsd BAP
functions instead of `CurrentStationInfo` — see [NAVIGATION_VIA_NAVSD.md](NAVIGATION_VIA_NAVSD.md).)

## What Android Auto actually projects (and what it does not)

The GAL receiver's *legacy* nav surface — everything protocol 1.3 could carry:

```
NavigationNextTurnEvent          → Event, TurnSide, TurnAngle, TurnNumber, Road, Image
NavigationNextTurnDistanceEvent  → DistanceMeters, TimeToTurnSeconds, DisplayDistanceUnit
NavigationStatus                 → Status
```

That is maneuver type, side, road name and distance/time **to the next turn**, and nothing else —
no lanes, no ETA, no distance to the destination.

**This is no longer the whole story.** The shim now advertises protocol **1.7**, where the phone
sends one `NavigationState` (0x8006) snapshot plus a `NavigationCurrentPosition` (0x8007) instead
of the legacy events, and that snapshot *does* carry the lanes of the junction ahead and the
distance/time to the destination. The bump is not additive: at 1.7 the legacy events stop
entirely, so it is a full migration of the maneuver source. See `misc/LANE_GUIDANCE.md` for the
wire format and the reverse-engineering behind it.

Note on distance: the device's `handleNavigationDistanceEvent` forwards `DistanceMeters` plus
`TimeToTurnSeconds` and a display-unit field to the listener. `DistanceMeters` reads 0 while
stationary but populates while driving (confirmed on a drive log); `TimeToTurnSeconds` stayed 0
even on the road. The distance is run through the cluster's own formatter so the value + unit
match what it expects.

## The media widget's four lines
The one free-form canvas on a non-nav cluster. The slots do **not** render in declaration order —
measured on the cluster, top to bottom:

| row | BAP field | route guidance active | Android Auto connected, no route |
|---|---|---|---|
| 1 | `secondaryInformation` | street, `Rerouting...`, or the **lane row** while there is one | artist |
| 2 | `tertiaryInformation` | maneuver word + roundabout exit | album |
| 3 | `primaryInformation` (**big font**) | arrow glyph + distance to the turn | track title |
| 4 | `quaternaryInformation` | distance + time to the destination | progress bar (`SHOW_MEDIA_PROGRESS`) |

```
| x | x | ► |         <- lanes, only in the last few hundred metres
Turn right
► 300 m
36 min / 11 km
```

`pi_Type` acts as a **layout selector** rather than a content tag here: `0` renders all four rows,
`72` (the type the stock "Android Auto" label uses) selects a 3-row layout that drops row 4. The
other `*_Type` fields appear inert in this widget — the big font is the primary slot positionally.

**Why the lane row sits in row 1.** It went in row 4 first and was unreadable on the car: the cells
have to be padded (`| x | x | ► |`, six lanes = 25 characters) and row 4 measures about 24, in a
proportional font whose bar glyph is narrow. Row 1 is the widest — long street names live there —
so that is where it goes. Losing the street name for the last few hundred metres is the deliberate
trade: at that point the junction is in sight and which lane matters far more than what it is
called. The rerouting notice still wins the row, since row 1 is the only place that notice appears
and lanes carried over from a route being recalculated are stale anyway.

The now-playing title used to scroll in row 4 as a marquee and no longer does — the route wants all
four rows, and music is fully visible whenever no route is guiding.

Glyphs: only `◄ ► ▲ ▼ ○ ★` are known to render in this font. Everything else we emit is plain
ASCII (`x` for a lane that does not carry the route, `|` cell separators, `/` in the trip row) —
an unknown character draws as a box.

## Media now-playing (built)
The same recipe also registers `MediaPlaybackStatusEndpoint`: the shim writes the real track to
`/dev/shmem/aa_media` (`seq Song\tArtist\tAlbum`) and the jar shows it in the media widget when no
route guidance is active. During guidance the route owns all four rows unless the user selects music with the wheel.
On the MQB non-nav path, BAP `DedicatedAudioControl` NEXT/PREV select music/navigation while
guidance is active and both navigation and media display are enabled. The separate MFL track-button path stays stock.
Nav-capable clusters retain navsd output and stock BAP controls.

## Other endpoints (researched, not built)
The same library exposes more endpoints the SAL likewise ignores:
- `PhoneStatusEndpoint` — caller name/number/type/photo, call state, duration, signal strength.
  → a "caller ID from AA on the cluster" feature (inject into the telephone BAP `CallState`/
  `CallInfo`, which the cluster already renders).
- `NotificationEndpoint` — radio/source-focus notifications (not user messages).

## Version specificity
- **Scripts / mechanism** — portable across STD2.
- **jar** — shadows are faithful copies of the dev unit's stock classes; within a firmware branch they
  are usually compatible, but strictly should be recompiled against the target `MIBHMI.jar`.
- **shim** - `inject.py` **auto-resolves** all per-firmware addresses
  from the target libgal's own `.dynsym` / relocations / `.plt`, and auto-locates the `BL` patch
  site by scanning `GalReceiver::init` for the call to the `onChannelOpened` PLT stub. The same
  shim sources adapt to any STD2 libgal build. (Confirmed necessary in practice: even two copies
  of the same build — the device copy vs the firmware-image copy — have addresses shifted by 8 bytes, so the
  old hardcoded values were wrong for the firmware-image libgal.) See
  [`../shim/README.md`](../shim/README.md).
