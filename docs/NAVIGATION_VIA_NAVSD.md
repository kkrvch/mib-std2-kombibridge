# Navigation via navsd (nav-capable / Amundsen units)

An alternative output half for head units whose cluster **is** coded for navigation (e.g. Amundsen).
Instead of rendering the maneuver text in the media now-playing widget (see
[ARCHITECTURE.md](ARCHITECTURE.md)), it drives the real cluster **Navigation** menu through the
navsd BAP function group (LSG 50).

The input half is unchanged: the patched GAL receiver (`shim/`) writes Android Auto turn-by-turn to
`/dev/shmem/aa_nav`. This module reads that and feeds the navsd functions.

## Why a second path

On a non-nav-coded cluster the navsd group is never subscribed — the cluster effectively says "do
not send me navigation", and force-starting navsd only disturbs the phone/audio BAP. That is
Blocker 2 in [ARCHITECTURE.md](ARCHITECTURE.md), and it is why the media-widget path exists.

On a nav-capable cluster the navsd group **is** subscribed, so the maneuver can be drawn where it
belongs: arrow + distance + street, with the cluster's own approach-segment animation.

## How it works

The navsd functions are constructed by `NavSDBindingFactoryAll` via plain `new Xxx()`. Loading
faithful **shadows** of those classes ahead of the stock ones (`-Xbootclasspath/p`, the same
mechanism the rest of this project uses) makes the factory build ours instead. Each shadow is a
verbatim copy of the stock class with a small delta: while AA is guiding it fills its status from the
shared `NavState` instead of the nav engine. On a non-nav cluster it also drops the nav-service
listeners that assume a routing engine; on a nav-capable cluster it keeps them (detected at runtime,
see below) so the unit's own navigation still works when AA is inactive.

`AANavReader` polls `/dev/shmem/aa_nav` on the framework timer, maps the GAL fields to the VW
navsd constants (`ASLNavSDConstants`), writes them into `NavState`, and pokes each function
(`process(-1)` -> recompute -> BAP send). It is started once from `AndroidAutoTarget` and is the
single reader for both output paths (navsd cluster menu here, and the media now-playing widget on a
non-nav cluster — it replaced the former `ShmemNavReader` / `NavShmemReader` split).

```
phone (AA) -> patched GAL (shim) -> /dev/shmem/aa_nav -> AANavReader -> NavState
                                                                              |
        ActiveRgType / RGStatus / ManeuverDescriptor / DistanceToNextManeuver / TurnToInfo
                                                                              |
                                                              cluster Navigation menu
```

## Falling back to the unit's own navigation (runtime `ClusterCaps`)

Nav-capable vs non-nav is **not** a static per-build flag (the old `NavState.NAV_CAPABLE` is gone).
It is detected at runtime by `ClusterCaps.isNavCapable()`, which reads
`ConfigurationService.isNavigationFeatureSelected()` and caches the result.

On a non-routing cluster (e.g. Bolero) the shadows speak only for Android Auto: when AA is inactive
they report idle and never touch the stock nav-service, so those `getNavigationService()` /
`getConfigurationService()` calls are skipped entirely — the safe default.

On a routing-capable cluster (e.g. Amundsen) the shadows register the stock nav-service listeners
and, while AA is inactive, fall back to the unit's own nav engine for the maneuver / distance /
turn-to-street / RG-status / rgtype — so the built-in navigation keeps drawing on the cluster
instead of being blanked by an idle override. When AA is guiding, `NavState.ACTIVE` takes priority
regardless, and every stock-service call is wrapped so a non-routing engine can never crash navsd.

## Functions driven

| Function | fid | Drives | Source field |
|---|---|---|---|
| `ActiveRgType` | 39 | the gate: `rgtype = 0` (RGI_FROM_BAP_FUNCTION_MANEUVER_DESCRIPTOR) so the cluster draws our maneuver; stock `3` (MOST_MAP) waits for a map video we cannot supply | `NavState.ACTIVE` |
| `RGStatus` | 17 | route-guidance active flag | `NavState.ACTIVE` |
| `ManeuverDescriptor` | 23 | the arrow: `mainElement` + `direction` (+ a roundabout exit spoke in `sidestreets`); `maneuver_2` = the small "then" arrow (see below) | GAL event + side/angle/exit number; IPC line 5 |
| `DistanceToNextManeuver` | 18 | distance to the turn + the approach-segment bargraph | GAL dist |
| `TurnToInfo` | 20 | the street line: the road the manoeuvre turns into; while the manoeuvre is still far (FOLLOW_STREET), the road the car is on | GAL road; IPC line 6 |
| `CurrentPositionInfo` | 19 | the current road (stock fills it only while its own guidance is idle; the cluster may not show it during guidance) | IPC line 6 (0x8007 `current_road`) |
| `LaneGuidance` | 24 | the lane row of the junction ahead (a BAP **array**, max 6 lanes, not a property) | `NavState.lanes` |
| `DistanceToDestination` | 21 | distance to the destination | `NavState.destinationMeters` |
| `TimeToDestination` | 22 | remaining travel time (a duration, **not** an arrival clock) | `NavState.destinationSeconds` |

`ActiveRgType.rgtype = 0` is the real gate (the analogue of the property-732 path on non-nav units,
but on the correct producer).

## GAL -> VW navsd mapping

- `event` (AA NextTurnEnum) -> `mainElement` (`MAIN_ELEMENT_*`): DEPART/NAME_CHANGE/STRAIGHT ->
  FOLLOW_STREET, TURN/SLIGHT/SHARP -> TURN, UTURN -> UTURN, ON/OFF_RAMP -> EXIT_*_RAMP / EXIT_*,
  FORK -> FORK_2, MERGE -> TURN_ON_MAINROAD, ROUNDABOUT -> ROUNDABOUT_TRS_*, DESTINATION -> ARRIVED.
- `side` (AA TurnSide) + `event` -> `direction`, the arrow rotation as a 0..255 CCW angle
  (0 = straight, 64 = left, 192 = right). Note: `128` (180°/down) does **not** render on the mono
  cluster, so a u-turn is mapped to 64/192 (the UTURN glyph needs a valid side to orient).
  Roundabouts use a precise bearing derived from the real `angle` (Maps) or synthesized from the
  exit `number` (Yandex) — see `mapDirection` / `effectiveAngle` in `AANavReader`.
- `dist` (GAL DistanceMeters) -> run through the stock formatter
  (`getFixFormatter().cnvDistance2KilometersExt`) so the value + unit match what the cluster
  expects; writing raw metres displays roughly 1/10 of the real distance.

## When the maneuver appears (distance gating)

AA names the next maneuver as soon as it is next on the route, so unfiltered the cluster holds a
right-turn arrow for the five kilometres before the turn (`AANavReader.maneuverImminent`):

| `dist` / `time` to the maneuver | `mainElement` | bargraph |
|---|---|---|
| `<= MANEUVER_NEAR_METERS` **or** `<= MANEUVER_NEAR_SECONDS` | the real maneuver | on |
| `<= MANEUVER_PREPARE_METERS` **or** `<= MANEUVER_PREPARE_SECONDS` | `PREPARE_TURN` / `PREPARE_ROUNDABOUT` / `PREPARE_SQUARE` / `PREPARE_UTURN`, turned the manoeuvre's way | off |
| further than all gates | `FOLLOW_STREET`, `direction = 0` | off |

The prepare stage (`AANavReader.maneuverPreparing` / `prepareElementFor`, defaults 1000 m / 80 s)
only exists for manoeuvres the cluster has a prepare glyph for: turns (incl. onto a main road),
roundabouts, squares and U-turns. Exits, forks, merges and the destination go straight from
`FOLLOW_STREET` to the real glyph. In the prepare stage the street line already shows the road the
manoeuvre turns into, and `maneuver_2` behaves as when near. `0`/`0` disables the stage.

Two gates because 400 m is half a minute in town but twelve seconds on a motorway; the countdown is
the phone's `time_to_step_seconds` (IPC field 8, protocol 1.7), and `0` on either field means "not
said". Only the glyph waits — street, countdown and destination keep running.

Stock does the same, which is where the shape comes from: its enum carries `FOLLOW_STREET` (11) and
`PREPARE_*` (28..31) beside the real maneuvers, and `verifiedBapBargraphSetting` enables the bargraph
only for the real ones (`{3,4,5,13..27,32..35}`) — the bargraph *is* the approach signal. The stock
thresholds are in the native nav engine (Java only forwards the DSI array in
`HsmTargetBap.dsiNavigationUpdateBapManeuverDescriptor`), so the two numbers above are ours.

## The second ("then") arrow — `maneuver_2`

`ManeuverDescriptor` carries up to three manoeuvres; the stock navigation fills `maneuver_2` with
the one that follows the current manoeuvre. With `Config.SHOW_NEXT_MANEUVER` (default on) the shim
keeps AA's `steps[1]` (IPC line `5 <event> <side> <angle> <number>`) and `AANavReader.nextManeuverFor`
picks what the second arrow shows:

| phase | `maneuver_1` | `maneuver_2` |
|---|---|---|
| manoeuvre near or in the prepare stage | the manoeuvre (or its `PREPARE_*` glyph) | the one after it (`steps[1]`) |
| manoeuvre still far | `FOLLOW_STREET` | the manoeuvre itself, as a preview |

A second arrow that would only say "follow the street" is left out (`NO_SYMBOL`), and the field is
rewritten on every send so a pooled status object never carries an old arrow over. AA gives no
distance between the two manoeuvres, so unlike the stock engine this cannot be limited to closely
spaced turns. Whether a given cluster draws `maneuver_2` at all is the cluster's business — it is
the AID this is aimed at.

## Notes / limits

- `ManeuverDescriptor.maneuver_1.sidestreets` is junction side-road **geometry**, not a label —
  never put a road name there (it draws a spurious approach road; the street name belongs in
  `TurnToInfo`). It is left empty for ordinary maneuvers. For roundabouts only, `AANavReader`
  synthesizes one EXIT-road spoke at the taken-exit bearing (`buildRingSideStreets`) so the cluster
  draws the correct exit.
- Lanes and the destination distance/time arrive only on protocol **1.7** (`NavigationState` /
  `NavigationCurrentPosition`), which the shim now advertises. The three functions above are gated
  on `NavState` rather than on `getRouteGuidanceState() == 1`: the unit's own route guidance is not
  running while AA drives, and trusting it is what kept the lane row empty the first time round.
- The travel time is the phone's *remaining seconds*, not its pre-rendered ETA string, so no
  timezone or clock-skew question arises.
- The six-lane clamp and the drop-NOT_RECOMMENDED-off-the-head rule live in
  `AANavReader.selectLanes`, shared with the non-nav media widget's text lane row — one AA frame
  must not render two different rows.
- `NavState` starts inactive; set `ACTIVE = true` (and e.g. `street`, `mainElement`, `direction`)
  for a static bench plate with no phone connected.

## HW-TUNE (done, hardware-confirmed)

- `direction` no longer uses only coarse side buckets: the exact `angle` and the roundabout exit
  `number` from `/dev/shmem/aa_nav` now drive a precise arrow (`effectiveAngle` / `mapDirection` in
  `AANavReader`). The AA sign convention was confirmed on a drive — Maps roundabouts use the real
  `angle`; Yandex sends `angle = 0`, so the bearing is synthesized from the exit `number`.
