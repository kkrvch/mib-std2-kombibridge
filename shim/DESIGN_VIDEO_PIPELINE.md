# MST2 Android Auto video pipeline

This is the implementation plan after the target GAL probe proved that Android Auto delivers
Annex-B H.264 access units to `VideoSink`.

## Confirmed input contract

- `VideoSink::handleCodecConfig(void*, u32)` delivers 28-byte Annex-B SPS/PPS
  (`67 42 00 1f ...`, `68 ce 0d 88`): Baseline profile, level 3.1, coded **800x480**.
- `VideoSink::handleDataAvailable(this, u64 ts, const shared_ptr<IoBuffer>&, u32 hdr)` (ARM EABI):
  `r1` is padding, `r2:r3` = timestamp lo:hi in microseconds, `[sp+0]` = `&shared_ptr`,
  `[sp+4]` = `hdr` = 10. The `IoBuffer*` is `[[sp+0]+4]`; its words are
  `{data, capacity, start, end}`.
- Access unit = `data + start + hdr`, length `end - start - hdr`. The forensic snapshot
  (`n=257`) gives `data=0x0067dd18, start=0, end=0x56cb` → a 22,209-byte AU beginning
  `00 00 00 01 21` (non-IDR slice). The 8 bytes after the 2-byte AAP message id,
  `00 00 00 01 0c 50 13 2e`, are the big-endian timestamp `0x1_0c50132e`, identical to `r3:r2`.
  The earlier "filler NAL 12 + `+2` offset" reading was wrong; it is fixed in `video_pipeline.h`,
  the probe and `tools/analyze-aa-video.js`.
- **Which stream this is:** only one `VideoSink` (`this` is the same for data and config) was
  observed, and its SPS is 800x480 — the same size as the SAL player's
  `mdevpush:...video.width=800&video.height=480` → `DISPLAYABLE_SAL`. This is the **main HU
  projection** (the whole AA UI), not a cluster map stream. A map-only stream would need a second,
  cluster-type video sink advertised to the phone (see "Open decision" below).

### Open decision: where the map pixels come from

The target GAL's protobuf (lite 2.x, no unknown-field storage) has only
`MediaSinkService{available_type, audio_type, audio_configs, video_configs,
available_while_in_call}`; there is no `display_type`/`display_id`, so the stock objects cannot
advertise a cluster display. Two routes exist:

1. **Second, cluster-type sink.** Build a second `VideoSink` endpoint by hand (the same technique
   the shim already uses for `NavigationStatusEndpoint`/`MediaPlaybackStatusEndpoint`) and patch
   the serialized `ServiceDiscoveryResponse` at byte level to add `display_type = CLUSTER` to that
   service. Whether the phone then renders a map-only stream for this head unit is unverified.
2. **Reuse the main 800x480 stream.** Always available, but it shows whatever the HU screen shows
   (full AA UI), and only while AA has video focus on the HU.

### Cluster sink experiment (`CLUSTER_SINK=1`)

Chosen route: a second, cluster-type sink. The first build only answers whether the phone streams
to it; nothing is decoded or shown. Implementation: `shim/src/cluster_sink.h`.

Recovered target facts it relies on (MST2 libgal, `.dynsym` addresses of the shipped copy):

| Item | Evidence |
|---|---|
| `Controller::sendServiceDiscoveryResponse` @`0x705ec` | builds the SDR, `populateServiceDiscoveryResponse`, `marshallProto(6, …)`, then exactly one `bl queueOutgoing(router, ch, data, len)` @`0x70894`; the buffer is freed right after, so the router copies it |
| `MessageRouter::queueOutgoing` @`0x8e608` | `(router, u8 channel, void*, u32)`; forwards to `ChannelManager::queueOutgoing` |
| Field numbers (`k*FieldNumber` constants) | `SDR.services=1`; `Service.id=1, media_sink_service=3`; `MediaSinkService.available_type=1, video_configs=4`; `VideoConfiguration.codec_resolution=1, frame_rate=2, width_margin=3, height_margin=4, density=5` |
| `ProtocolEndpointBase::onChannelOpened` | sets `+5 = channel`, `+4 = 1`; every stock send checks `+4` |
| `MediaSinkBase::routeMessage` | `0x0000` data, `0x0001` codec config, `0x8000` setup, `0x8001` start, `0x8002` stop, `0x8007` focus request |
| `VideoSink::handleSetup` | accepts codec type 3 only, then `sendConfig(2)` (READY) and `setVideoFocus(1, unsolicited=1)` |
| `ackFrames` / `sendConfig` / `setVideoFocus` | `Ack 0x8004 {1: session, 2: count}`, `Config 0x8003 {1: status, 2: max_unacked, 3: index}`, `VideoFocusNotification 0x8008 {1: focus, 2: unsolicited}` |

The hook rewrites the serialized response the way a working two-display head unit declares it
(DHU capture documented in `chopinwong01/mhi2-android-auto-video-vc` `src/video_sink_hook.c`;
field numbers also in `mrmees/open-android-auto` `oaa/av/AVChannelData.proto`):

| Entry | Wire content |
|---|---|
| main video (stock, edited) | `MediaSinkService` + `6:0 7:0` (display_id 0, display_type MAIN) |
| main input (stock, edited) | `InputSourceService` + `5:0` |
| cluster video (appended) | `available_type 3`, `video_configs {800x480, 30 fps, margins 0, dpi 125, decoder depth 3}`, `6:1 7:1` |
| cluster input (appended) | `InputSourceService {keycodes [23, 65536], 5:1}` on its own registered endpoint |

That project measured that a cluster video sink without the paired cluster input service makes the
phone stop responding, so the experiment emits all four. If the rewrite cannot find exactly one main
H.264 sink it sends the stock bytes unchanged (`sdr_error`). The constants remain overridable through
`CLUSTER_DEFINES`.

RAM-only output: `/dev/shmem/aa_cluster_probe` (counters), `aa_cluster_config` (codec config
bytes), `aa_cluster_frames` (64-byte prefixes of the first four access units). `sdr_patched=1` and
`setup=0` means the phone ignored the sink; `frames>0` plus a config whose SPS differs from the main
800x480 stream is the positive result.

### Output path (step 60)

Chosen: no decoding on the head unit. The phone's cluster stream (requested 1280x720, 244 lines of
height margin, SPS cropped to 1280x476) goes from libgal into `/dev/shmem/aatokombi-cluster-video`
and `native/libkb_dm.so` feeds it to DisplayManager's stock TS/MOST path in place of the cluster
encoder's output (seam found by mst2-cluster-nav, re-verified on the 0480 dump). The decoder/QNX
Screen route described below is therefore not pursued.

## Target decoder/output components

The firmware contains the i.MX6 decoder filter:

```text
/lib/dll/mmedia/imx6_video_decoder.so
  imxmmf_open
  imxmmf_init
  imxmmf_fill_input
  imxmmf_decode
  imxmmf_get_video_width
  imxmmf_get_video_heigh
  imxmmf_getPlaneInfo
  imxmmf_flush / imxmmf_close
```

The static ARM body gives a few safe ABI facts that are useful before any runtime call is wired:

- `imxmmf_get_video_width(ctx)` returns `*(u32 *)(ctx + 0x18)` and
  `imxmmf_get_video_heigh(ctx)` returns `*(u32 *)(ctx + 0x14)`.
- `imxmmf_input_size(ctx)` reads `ctx + 0x34`; `imxmmf_reset_input(ctx)` clears `ctx + 0x30`
  and `ctx + 0x34`; `imxmmf_send_input(ctx)` sets `ctx + 0x30` to one.
- `imxmmf_bitstreambuffer_size()` returns the decoder's fixed `0x602` input-buffer unit in this
  firmware. These are observations from the shipped ARM code, not a substitute for the complete
  private context ABI.
- `imxmmf_fill_input` has the shape `(ctx, input, length, ...)` in its ARM entry sequence, while
  `imxmmf_decode` consumes the context and internal input state. The remaining output-frame and
  plane structure must be recovered from the body/DisplayManager call site before invoking it.

Therefore the current pipeline does not guess a decoder function-pointer ABI or call it from the
GAL process yet. The bounded ingress queue is the stable hand-off point while that private ABI is
recovered.

The DisplayManager image-output path contains the target classes:

```text
DisplayableQNXScreen
OpenMAXEncoder
OpenMAXEncoderScreenWriter::Input/Output
MostSink
TsEncoder
```

The Java/QNX interface inventory adds an important existing control path: the stock HMI exposes
`org.dsi.ifc.displaymanagement.DSIDisplayManagement` methods `startComponent`, `stopComponent`,
`setDisplayType`, `setUpdateRate`, `createImageDisplayable` and
`requestUpdateImageDisplayable`. Its constants include `DISPLAYTYPE_H264_MOST` and
`DISPLAYID_CLUSTER`. The DisplayManager binary also contains the strings `FPK video channel`,
`Built FPK video connection` and `Closed FPK video connection`, plus `MostSink` and
`OpenMAXEncoderScreenWriter` vtables. This makes the existing FPK/DisplayManager path the primary
output candidate; a new raw MOST protocol should not be invented until this route is ruled out.

This interface is an output-control seam, not proof that a GAL-process decoder surface can be
passed through it directly. The remaining task is to map the decoded buffer ownership into the
existing `screen_writer`/`MostSink` input contract.

## Stock SAL video implementation

The SAL executable itself already contains the Android/GAL video implementation. Its exported
RTTI/vtables include `tsd::sal::gal::video::GalVideoCallbacks`, the `IVideoSinkCallbacks` ABI
used by `VideoSink + 0x34`, `tsd::sal::appconnect::video::H264VideoGALE`, `H264VideoPlayer`,
and their `IH264Video*` interfaces, plus `CVideoChannelModule` singleton storage.

Adjacent SAL strings name the existing lifecycle operations `configureCodec`,
`configureVideoFormat`, `attachInput`, `processVideoData`, `attachOutput`, `play`, `stop`, and
`deactivate`, as well as the `mdevpush:<...>` input format. The same code contains the normal HU
output URL format `screen:/wingrp%s&winid%s&dstx...`; it is an actual H.264 player path rather
than an unused declaration.

The DisplayManager dump independently contains `Start transmission to cluster instrument`,
`Stop transmission to cluster instrument`, `Cannot get cluster encoder`, and `Cannot get cluster
instrument display!`, alongside the FPK video channel open/build/close messages. The working
patch should therefore feed or redirect the existing SAL player and request its cluster/FPK
output, rather than instantiate a second VPU or invent a raw MOST transport.

`tools/analyze-video-seams.js` prints these symbols, vtable words, and strings offline. It is
inventory/evidence only and does not alter runtime binaries.

### Callback and comparative implementation notes

The recovered `GalVideoCallbacks` vtable is the concrete bridge behind the callback object used
by `VideoSink`:

```text
VideoSink + 0x34 -> IVideoSinkCallbacks vptr
  +0x0c  dataAvailableCallback  -> GalVideoCallbacks thunk 0x29ad80
  +0x10  codecConfigCallback    -> thunk 0x29ad78
  +0x14  setupCallback           -> thunk 0x29ad38
  +0x18  playbackStartCallback   -> thunk 0x29ad30
  +0x1c  playbackStopCallback    -> thunk 0x29ad28
```

The names are also present in the stripped SAL string/RTTI area (`dataAvailableCallback`,
`codecConfigCallback`, `setupCallback`, `playbackStartCallback`, `playbackStopCallback`). The
thunks forward into the native `GalVideo` implementation; this is evidence that the stock SAL
path already owns the AA video lifecycle. It is not yet a license to replace the callback vptr:
the object lifetime and shared-pointer ownership still have to be recovered from the setup path.

The next layer is also visible statically: `H264VideoGALE` methods are forwarding wrappers which
load the contained object at `this + 4` and dispatch through its virtual table. The recovered
targets include these player slots:

```text
H264VideoGALE +0x18 -> contained vtable +0x10 -> H264VideoPlayer 0x1ca06c
H264VideoGALE +0x1c -> contained vtable +0x14 -> H264VideoPlayer 0x1cbf14
H264VideoGALE +0x20 -> contained vtable +0x18 -> H264VideoPlayer 0x1cb16c
H264VideoGALE +0x24 -> contained vtable +0x30 -> H264VideoPlayer 0x1ca92c
H264VideoGALE +0x28 -> contained vtable +0x28 -> H264VideoPlayer 0x1caaf0
H264VideoGALE +0x2c -> contained vtable +0x2c -> H264VideoPlayer 0x1cb88c
```

This narrows the static call graph to the existing SAL player rather than a guessed decoder ABI.
The addresses are firmware-specific evidence only; the shim must resolve and validate them before
any future runtime hook is considered.

### MMR lifecycle evidence

The SAL executable exports the native MMR calls used by the player (`mmr_context_create`,
`mmr_context_parameters`, `mmr_input_parameters`, `mmr_input_attach`, `mmr_output_attach`,
`mmr_output_detach`, `mmr_play`, `mmr_stop`, `mmr_disconnect`, and
`mmr_context_destroy`). Direct ARM call sites connect them to the player code:

```text
H264VideoPlayer target 0x1ca21c  -> mmr_output_detach  (0x14a3b0)
H264VideoPlayer target 0x1caaf0  -> mmr_context_create/parameters,
                                      mmr_output_attach, context_destroy
H264VideoPlayer target 0x1cb494  -> mmr_stop           (0x14b160)
H264VideoPlayer target 0x1cb88c  -> mmr_input_attach   (0x14b8bc)
H264VideoPlayer target 0x1cbd44  -> mmr_play           (0x14b5a4)
H264VideoPlayer target 0x1cbf14  -> mmr_context_destroy/disconnect
```

This is the first static proof that the stock player already owns the MMR input/output
connection. The correct patch target is therefore a lifecycle decision around the existing player
and output parameters, not a replacement `imxmmf_*` decoder. The exact mapping of the remaining
configuration methods is still being recovered from their call sites.

### Core-snapshot confirmation

The retained QNX SAL core snapshots contain the player’s own log records, independently
confirming the static call graph and its live parameters:

```text
configureCodec() -> Video player config bytes = 28/29
attachInput()    -> mdevpush:/dev/name/local/mdev?fourcc=H264
                   video.frame_rate=30, video.width=800, video.height=480
attachOutput()   -> screen:/?wingrp=DisplayManagerWindowGroup
                   &winid=DispId[DISPLAYABLE_SAL]&dstx=0&dsty=0
                   &dstw=800&dsth=480
```

This confirms the real input/output URL format without another vehicle session. It also shows why
simply changing `winid` in the shim is insufficient: the stock player targets `DISPLAYABLE_SAL`;
cluster transmission is a separate DisplayManager/FPK lifecycle. `tools/scan-core-logs.js`
extracts these records offline from compressed core snapshots and does not modify them.

The MHI2-CarPlay-Maps example is useful as an architecture reference: it separates an AltScreen
to-cluster bridge from a supervisor and keeps the firmware-specific core/profile selection outside
the generic lifecycle. MST2 must use its own recovered SAL/DisplayManager ABI. In particular, the
MST2 path must keep one SAL video context and route it to the existing FPK/MostSink cluster
connection; a second decoder/output or a raw MOST writer would violate the constraints recorded
in the plan.

## Runtime design

1. **Ingress stage implemented behind `VIDEO_PIPELINE=1`:** on codec config, retain bounded SPS/PPS;
   on data callback, apply the recovered `IoBuffer` arithmetic, parse only NAL headers, and copy
   accepted access units into a three-slot, 64 KiB-per-slot RAM queue. Non-IDR units are dropped
   while waiting for recovery; queue overflow drops the oldest unit instead of stalling GAL.
2. On codec config, create/configure one decoder context and retain SPS/PPS until the first IDR.
3. On data callback, copy only the bounded access unit into a queue owned by the video worker;
   never decode on the GAL callback stack and never write a file from that callback.
4. Feed the queue to the i.MX6 decoder adapter. Drop non-IDR data while waiting for a fresh IDR
   after reconnect or decoder reset.
5. Present decoded frames on a private QNX Screen surface first. The surface must be owned by the
   worker lifecycle and destroyed on AA disconnect.
6. Connect that surface to the existing cluster displayable/encoder path only after decoder
   lifecycle is stable. Preserve OEM `DISPLAYABLE_MAP` and restore it when AA video focus ends.
7. ACK input frames only after the bounded copy/queue handoff, with a hard queue limit and reset
   handling for decoder backpressure.

The repository also contains `tools/analyze-aa-video.js`, a host-side parser for the forensic
candidate package. It selects the real Annex-B candidate using valid NAL types and confirms the
separate SPS/PPS config without requiring another HU session. It exports only the captured 512-byte
prefix, never presenting that prefix as a complete video file.

The deep forensic collector is not part of this pipeline. It is opt-in diagnostic code only and
must remain disabled in normal `VIDEO_PROBE=1` builds.
