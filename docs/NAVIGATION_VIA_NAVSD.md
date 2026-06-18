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
verbatim copy of the stock class with a small delta: it fills its status from the shared
`NavState` instead of the (absent) nav engine, and drops the nav-service listeners that assume a
routing engine.

`NavShmemReader` polls `/dev/shmem/aa_nav` on the framework timer, maps the GAL fields to the VW
navsd constants (`ASLNavSDConstants`), writes them into `NavState`, and pokes each function
(`process(-1)` -> recompute -> BAP send). It is bootstrapped once from `ActiveRgType.init()`, so
the module is self-contained and does not touch the Android Auto target.

```
phone (AA) -> patched GAL (shim) -> /dev/shmem/aa_nav -> NavShmemReader -> NavState
                                                                              |
        ActiveRgType / RGStatus / ManeuverDescriptor / DistanceToNextManeuver / TurnToInfo
                                                                              |
                                                              cluster Navigation menu
```

## Functions driven

| Function | fid | Drives | Source field |
|---|---|---|---|
| `ActiveRgType` | 39 | the gate: `rgtype = 0` (RGI_FROM_BAP_FUNCTION_MANEUVER_DESCRIPTOR) so the cluster draws our maneuver; stock `3` (MOST_MAP) waits for a map video we cannot supply | `NavState.ACTIVE` |
| `RGStatus` | 17 | route-guidance active flag | `NavState.ACTIVE` |
| `ManeuverDescriptor` | 23 | the arrow: `mainElement` + `direction` | GAL event + side |
| `DistanceToNextManeuver` | 18 | distance to the turn + the approach-segment bargraph | GAL dist |
| `TurnToInfo` | 20 | the "turn into <street>" line | GAL road |

`ActiveRgType.rgtype = 0` is the real gate (the analogue of the property-732 path on non-nav units,
but on the correct producer).

## GAL -> VW navsd mapping

- `event` (AA NextTurnEnum) -> `mainElement` (`MAIN_ELEMENT_*`): DEPART/NAME_CHANGE/STRAIGHT ->
  FOLLOW_STREET, TURN/SLIGHT/SHARP -> TURN, UTURN -> UTURN, ON/OFF_RAMP -> EXIT_*_RAMP / EXIT_*,
  FORK -> FORK_2, MERGE -> TURN_ON_MAINROAD, ROUNDABOUT -> ROUNDABOUT_TRS_*, DESTINATION -> ARRIVED.
- `side` (AA TurnSide) + `event` -> `direction`, the arrow rotation as a 0..255 CCW angle
  (0 = straight, 64 = left, 128 = u-turn, 192 = right).
- `dist` (GAL DistanceMeters) -> run through the stock formatter
  (`getFixFormatter().cnvDistance2KilometersExt`) so the value + unit match what the cluster
  expects; writing raw metres displays roughly 1/10 of the real distance.

## Notes / limits

- `ManeuverDescriptor.maneuver_1.sidestreets` is junction side-road **geometry**, not a label;
  leave it empty. Feeding the road name there makes the cluster draw a spurious approach road. The
  street name belongs in `TurnToInfo`.
- Distance-to-destination and route ETA are not available — Android Auto does not project them to
  the car, the same as on a factory unit.
- `NavState` starts inactive; set `ACTIVE = true` (and e.g. `street`, `mainElement`, `direction`)
  for a static bench plate with no phone connected.

## HW-TUNE

- `direction` currently uses coarse side buckets. The exact `angle` and the roundabout exit
  `number` are present in `/dev/shmem/aa_nav` and can drive a precise arrow once their AA sign
  convention is confirmed on a drive.
