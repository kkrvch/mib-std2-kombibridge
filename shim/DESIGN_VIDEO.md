# Video probe design — MST2 / i.MX6

This document records the first implementation boundary for Android Auto map
video. It is intentionally a probe design, not a firmware patch: it must not
change the existing navigation or media endpoints and must not select an AID
display surface yet.

## Target evidence

The target file is the dumped
`tsd/lib/sal/gal/libext.google.gal.receiver.so`. Its dynamic symbol table
contains the generic GAL video sink classes:

- `VideoSink`: `handleSetup`, `handleCodecConfig`, `handleDataAvailable`,
  `playbackStart`, `playbackStop`, `setVideoFocus`, `addDiscoveryInfo`;
- `MediaSinkBase`: `routeMessage`, `handleStart`, `handleStop`, `sendConfig`,
  `ackFrames`, `resetAllowedConfigurations`;
- `GalReceiver::getEncodedFrame` and `GalReceiver::allocateBuffer`.

The video vtables are present in the same DSO. Their constructors are not
exported, so a second sink cannot be created by copying the MHI2 C++ object
layout. The first probe must recover the target object's construction and
callback ABI from this binary.

## Probe stages

1. **Static ABI recovery.** Identify where the stock receiver creates or owns
   its `VideoSink`, the exact vtable slots, object fields used by
   `MediaSinkBase`, and the callback/shared-pointer type passed to
   `handleDataAvailable`.
2. **Capture-only interception.** Add a separate hook around the target
   `handleCodecConfig`/`handleDataAvailable` path. Record only bounded metadata
   in `/dev/shmem`: session, sink identity, configuration, coded dimensions,
   frame count, payload length, NAL framing, SPS/PPS/IDR observations and ACK
   result. Do not forward frames to a display and do not alter the existing
   nav/media registration.
3. **Secondary-sink registration.** If the phone does not deliver a cluster
   stream to the stock sink, construct/register a second target-compatible
   `VideoSink` using the recovered ABI. Its service id must be selected from the
   live `MessageRouter`, as in the existing shim; no MHI2 service id or vtable
   address may be used.
4. **Renderer proof.** Only after a stream is observed, decode a bounded sample
   with the i.MX6 VPU and display a test frame in a private QNX Screen surface.
5. **AID output.** Reuse the target `DisplayManager` displayable/clone and
   `OpenMAXEncoder`/`MostSink` path. The map surface, geometry and priority are
   selected from runtime evidence; direct MOST packet generation remains a
   fallback.

## Safety boundary

Until stage 2 has a reproducible stream contract, do not change HMI gates,
`AAtoKombi`, BAP guidance, the current nav/media shim, boot scripts or
`DISPLAYABLE_MAP`. A failed probe must leave the existing AA guidance and OEM
navigation behavior unchanged.

