// Cluster video sink experiment (opt-in: CLUSTER_SINK=1, inject.py --cluster-sink).
//
// Question this answers on the car, in one session: if the head unit advertises a second,
// cluster-type H.264 video sink, does the phone open it and stream a separate (map) video?
//
// Why it is built this way (all from the target libgal, see shim/DESIGN_VIDEO_PIPELINE.md):
//  * The GAL's protobuf-lite 2.x MediaSinkService has only fields 1..5 (no display_id/display_type)
//    and no unknown-field storage, so the stock objects cannot carry "this is a cluster display".
//    Instead the serialized ServiceDiscoveryResponse is extended at byte level: the one
//    `bl MessageRouter::queueOutgoing` in Controller::sendServiceDiscoveryResponse is redirected to
//    sdr_queue_hook, which appends one more `services` element (repeated field => plain append).
//  * The endpoint is our own object on the stock ProtocolEndpointBase slots (the technique already
//    used for the nav/media endpoints); routeMessage is ours, so no hidden VideoSink/IVideoSinkCallbacks
//    ABI is involved. Replies use the stock wire format seen in MediaSinkBase:
//      Setup 0x8000 -> Config 0x8003 {status=2 READY, max_unacked, configuration_indices=0}
//                      + VideoFocusNotification 0x8008 {focus=1 PROJECTED, unsolicited=1}
//      Start 0x8001 {session_id=1, configuration_index=2}; Stop 0x8002
//      VideoFocusRequest 0x8007 -> VideoFocusNotification {focus=1, unsolicited=0}
//      CodecConfig 0x0001 / Data 0x0000 (+8-byte BE timestamp) -> Ack 0x8004 {session_id, ack=1}
//  * Nothing is decoded or displayed. Frames are acknowledged immediately so the phone never stalls;
//    only counters and bounded prefixes go to /dev/shmem (RAM).
//
// Discovery contents follow a working two-display head unit (DHU capture documented in
// chopinwong01/mhi2-android-auto-video-vc, src/video_sink_hook.c; field numbers also in
// mrmees/open-android-auto oaa/av/AVChannelData.proto, InputChannelConfigData.proto):
//   main video    MediaSinkService  6:0 7:0      (display_id 0, display_type MAIN)
//   main input    InputSourceService 5:0         (display_id 0)
//   cluster video MediaSinkService  6:1 7:1      (display_id 1, display_type CLUSTER)
//   cluster input InputSourceService {keycodes [23, 65536], 5:1}
// That project measured that a cluster video sink WITHOUT the paired cluster input service makes
// the phone stop responding (ping timeouts), so all four are emitted. The main entries are
// edited in place (lengths re-encoded); the two cluster entries are appended.

#ifndef CLUSTER_SINK
#define CLUSTER_SINK 0
#endif

#if CLUSTER_SINK

#ifndef CLUSTER_FIELD_DISPLAY_ID
#define CLUSTER_FIELD_DISPLAY_ID 6      // MediaSinkService.display_id
#endif
#ifndef CLUSTER_FIELD_DISPLAY_TYPE
#define CLUSTER_FIELD_DISPLAY_TYPE 7    // MediaSinkService.display_type
#endif
#ifndef CLUSTER_FIELD_INPUT_DISPLAY_ID
#define CLUSTER_FIELD_INPUT_DISPLAY_ID 5 // InputSourceService.display_id
#endif
#ifndef CLUSTER_DISPLAY_TYPE
#define CLUSTER_DISPLAY_TYPE 1          // DISPLAY_TYPE_CLUSTER (MAIN = 0)
#endif
#ifndef CLUSTER_DISPLAY_ID
#define CLUSTER_DISPLAY_ID 1
#endif
#ifndef CLUSTER_RESOLUTION
#define CLUSTER_RESOLUTION 1            // VideoCodecResolutionType 1 = 800x480 (the only one the AID takes)
#endif
// Geometry. The AID shows the decoded picture 1:1 from its top-left corner in the stock window
// (1024x384 coded, cropped to 1010x376: stock SPS Baseline 3.0, crop right 14 / bottom 8 - never
// left or top). AA has no 1010x376 format.
//  - 1280x720 with margins + a crop on all four sides (2026-10-29..11-02): DisplayManager dropped
//    every stream ~0.2 s after its first frame.
//  - 800x480, crop bottom 104 (2026-11-03): played for minutes. Picture 800x376 left-aligned, the
//    right ~210 px of the window empty, so the car marker sat ~105 px left of the window centre.
//  - 1280x720 WITHOUT margins, cropped right 270 / bottom 344 (2026-11-03): dropped the same way.
//    slog 2026-11-05: ~0.2 s after the first 1280 frame the AID reports its MOST stream sink
//    unavailable ("StreamSink becomes unavailable"), the HMI switches the cluster transmission off
//    and DisplayManager stops the encoder. The AID's decoder refuses the format: not fixable here.
//  - 800x480 cropped bottom 104 (default): a 210 px left inset moves the centre of the phone's
//    layout to the centre of the window. The picture cannot be moved right: a crop only removes
//    pixels, and the phone codes one slice per frame (probe 2026-11-04), so no wider picture can be
//    built from its slices. An SPS VUI sample aspect ratio 101:80 did not stretch it (2026-11-04).
#ifndef CLUSTER_WIDTH_MARGIN
#define CLUSTER_WIDTH_MARGIN 0
#endif
#ifndef CLUSTER_HEIGHT_MARGIN
#define CLUSTER_HEIGHT_MARGIN 0
#endif
#ifndef CLUSTER_CROP_TOP
#define CLUSTER_CROP_TOP 0                                        // pixels, even
#endif
#ifndef CLUSTER_CROP_LEFT
#define CLUSTER_CROP_LEFT 0                                       // pixels, even
#endif
#ifndef CLUSTER_CROP_BOTTOM
#define CLUSTER_CROP_BOTTOM 104                                   // pixels, even
#endif
#ifndef CLUSTER_CROP_RIGHT
#define CLUSTER_CROP_RIGHT 0                                      // pixels, even
#endif
// Content insets (VideoConfiguration field 11 UiConfig, subfield 2 {1 top, 2 bottom, 3 left,
// 4 right}, pixels of the coded frame): the AID's status bar on top (56) and trip bar at the bottom
// (70, safe area y 56..306 measured by mst2-cluster-nav), plus whatever the crop cuts away.
#ifndef CLUSTER_INSET_TOP
#define CLUSTER_INSET_TOP 56
#endif
#ifndef CLUSTER_INSET_BOTTOM
#define CLUSTER_INSET_BOTTOM (70 + CLUSTER_CROP_BOTTOM)
#endif
#ifndef CLUSTER_INSET_LEFT
#define CLUSTER_INSET_LEFT 210
#endif
#ifndef CLUSTER_INSET_RIGHT
#define CLUSTER_INSET_RIGHT CLUSTER_CROP_RIGHT
#endif
// Decode mode (default since 2026-11-08). The head unit decodes the phone's cluster stream itself
// (libkb_dm.so: mm-renderer into the cluster display's map window, DisplayManager's stock encoder
// sends that display to the AID; car 2026-11-07: full-screen AA map). The AID never sees the
// phone's H.264, so the phone may send 1280x720: the SPS crop keeps the 1010x376 middle, shown 1:1.
// The CLUSTER_* geometry above is the pass-through mode (phone H.264 straight to the AID, 800x480),
// used while /tsd/var/aatokombi/passthrough exists (read at each connection). Insets of each mode:
// /tsd/var/aatokombi/insets (pass-through) and /tsd/var/aatokombi/insets-1280 (decode).
#define CLUSTER_PASSTHROUGH_FILE "/tsd/var/aatokombi/passthrough"
#ifndef CLUSTER_DEC_INSET_TOP
#define CLUSTER_DEC_INSET_TOP 170
#endif
#ifndef CLUSTER_DEC_INSET_BOTTOM
#define CLUSTER_DEC_INSET_BOTTOM 250
#endif
#ifndef CLUSTER_DEC_INSET_LEFT
#define CLUSTER_DEC_INSET_LEFT 500
#endif
#ifndef CLUSTER_DEC_INSET_RIGHT
#define CLUSTER_DEC_INSET_RIGHT 500
#endif
#ifndef CLUSTER_DEC_CROP_LEFT
#define CLUSTER_DEC_CROP_LEFT 134
#endif
#ifndef CLUSTER_DEC_CROP_RIGHT
#define CLUSTER_DEC_CROP_RIGHT 136
#endif
#ifndef CLUSTER_DEC_CROP_TOP
#define CLUSTER_DEC_CROP_TOP 110
#endif
#ifndef CLUSTER_DEC_CROP_BOTTOM
#define CLUSTER_DEC_CROP_BOTTOM 234
#endif
struct ClGeom { u32 decode, res, w, h, crop[4] /* left right top bottom */, ins[4] /* top bottom left right */; };
static ClGeom g_geo = { 0u, CLUSTER_RESOLUTION, 800u, 480u,
                        { CLUSTER_CROP_LEFT, CLUSTER_CROP_RIGHT, CLUSTER_CROP_TOP, CLUSTER_CROP_BOTTOM },
                        { CLUSTER_INSET_TOP, CLUSTER_INSET_BOTTOM, CLUSTER_INSET_LEFT, CLUSTER_INSET_RIGHT } };
#ifndef CLUSTER_VIEWING_DISTANCE
#define CLUSTER_VIEWING_DISTANCE 700    // mm, as mib-cluster-map sends for its cluster screen
#endif
#ifndef CLUSTER_UI_THEME
#define CLUSTER_UI_THEME 0              // 0 automatic (follows the phone), 1 light, 2 dark
#endif
// Android keycodes the cluster input service declares: DPAD up/down/left/right/centre, zoom in /
// out (168/169) and the rotary controller (65536). The phone binds only declared keys.
#ifndef CLUSTER_KEYCODES
#define CLUSTER_KEYCODES 19,20,21,22,23,168,169,65536
#endif
#define CLUSTER_CMD_FILE "/dev/shmem/aa_cluster_cmd"
// "Map only during route guidance" (the jar's Config.ClusterMap.AA_MAP_ONLY_DURING_GUIDANCE creates
// GUIDANCE_ONLY_FLAG at HMI start). Without guidance the phone is not given video focus on the
// cluster channel, so it sends nothing; when guidance starts it gets PROJECTED focus and starts
// with codec config + IDR; CLUSTER_GUIDANCE_HOLD_MS after guidance ends focus goes back to NATIVE.
#define CLUSTER_GUIDANCE_ONLY_FLAG "/dev/shmem/aa_cluster_guidance_only"
// "No AA map at all" (the jar creates VIDEO_OFF_FLAG at HMI start when Config.Navigation.ENABLED is
// false and removes it otherwise): the phone is never given video focus on the cluster channel.
// Without the flag (no jar, or navigation on) the video is allowed, as before. Polled once a second.
#define CLUSTER_VIDEO_OFF_FLAG "/dev/shmem/aa_cluster_video_off"
#ifndef CLUSTER_GUIDANCE_HOLD_MS
#define CLUSTER_GUIDANCE_HOLD_MS 10000
#endif
// How to get a key frame when DisplayManager needs one (AA has no key-frame request):
//   2 = take video focus away (NATIVE) and give it back (PROJECTED): the phone restarts the
//       stream with codec config + IDR (car 2026-10-27: 20 restarts in ~1.5 min, the AID kept
//       re-activating its map; off by default),
//   1 = repeat PROJECTED focus only, 0 = never ask (wait for the phone's own IDR, about one a
//       minute: the AA map then took up to ~2 min to appear, car 2026-10-29),
// Default 0 since car 2026-11-01: 39 repeated PROJECTED focuses brought no IDR, and the 7 focus
// cycles restarted the stream WITHOUT an IDR (the phone's next one came ~19.5 s later each time),
// every cycle also flapping the stock map and the AID's "map activation". libkb_dm.so now keeps
// the stock map on screen until the phone's key frame is there instead.
//   3 = first repeat PROJECTED; if DisplayManager still has no key frame
//       CLUSTER_IDR_GAP_MS later, one NATIVE->PROJECTED cycle, at most one per
//       CLUSTER_IDR_CYCLE_GAP_MS. The test-2 loop also came from libkb_dm.so throwing away the
//       restart IDR (it jumped to the newest frame); it now resynchronises on the newest IDR.
// Whether the phone answers a repeated PROJECTED with an IDR is not verified.
#ifndef CLUSTER_IDR_MODE
#define CLUSTER_IDR_MODE 4
#endif
//   4 = (default, 2026-11-01) only after DisplayManager has waited CLUSTER_IDR_WAIT_MS for a key
//       frame: ONE NATIVE->PROJECTED cycle with NATIVE held CLUSTER_IDR_NATIVE_MS, at most one per
//       CLUSTER_IDR_CYCLE_GAP_MS. Why the long hold: car 2026-11-01, Starts after a long NATIVE
//       (mode switch, guidance) began with an IDR (libkb_dm.so "resync on a key frame, frames back
//       3"), the 800 ms cycles of mode 3 did not (next IDR ~19.5 s later). Not verified that 2 s
//       is long enough: aa_cluster_probe start_idr_ms* shows it (ms from Start to the first IDR).
//       libkb_dm.so keeps the stock map on the AID meanwhile, KombiMap waits 4 s before showing it.
#ifndef CLUSTER_IDR_WAIT_MS
#define CLUSTER_IDR_WAIT_MS 2000
#endif
#ifndef CLUSTER_IDR_NATIVE_MS
#define CLUSTER_IDR_NATIVE_MS 2000
#endif
#ifndef CLUSTER_IDR_CYCLE_GAP_MS
#define CLUSTER_IDR_CYCLE_GAP_MS 10000
#endif
// Bitrate cap of the cluster stream (kbit/s of H.264). The AID's MOST video channel carries about
// 12.3 Mbit/s of MPEG-TS; a phone burst above it overfills DisplayManager's ring to the J5 and the
// picture breaks up. AA has flow control by acknowledgement (Config.max_unacked), so frames are
// acknowledged only as the budget allows: the phone then waits and skips frames itself. The value
// and the 250 ms burst follow mst2-cluster-nav's car-tested 10 Mbit/s budget for CarPlay (there the
// socket read is throttled instead of the ACKs).
#ifndef CLUSTER_KBPS
#define CLUSTER_KBPS 10000
#endif
#ifndef CLUSTER_BURST_MS
#define CLUSTER_BURST_MS 250
#endif
#ifndef CLUSTER_IDR_GAP_MS
#define CLUSTER_IDR_GAP_MS 3000
#endif
#ifndef CLUSTER_FRAME_RATE
#define CLUSTER_FRAME_RATE 2            // VideoFrameRateType 2 = 30 fps
#endif
#ifndef CLUSTER_DENSITY
#define CLUSTER_DENSITY 125             // 12.3" 1440x540 cockpit panel, car-tested on MHI2
#endif
#ifndef CLUSTER_DECODER_DEPTH
#define CLUSTER_DECODER_DEPTH 3
#endif
#ifndef CLUSTER_MAX_UNACKED
#define CLUSTER_MAX_UNACKED 2
#endif

#include "cluster_video_shm.h"
static struct kbcv* g_kv;              // cluster video shared memory, mapped on the first Start
static volatile u32 g_focusOn;         // 1 = PROJECTED granted on the cluster channel
static u32 g_guideOnly, g_guideChecked, g_guideMs, g_videoOff, g_videoOffChecked;
static volatile u32 g_hmiVideo = 1;   // jar: 0 = the driver chose arrows / the stock map
static volatile int g_budget;          // bytes that may be acknowledged now (may go negative)
static volatile u32 g_budgetMs;
static volatile u32 g_ackPending;      // data frames received but not yet acknowledged

enum { CL_CODEC_H264_BP = 3 };          // MediaCodecType; VideoSink::handleSetup accepts only 3

typedef int (*qout_t)(u32 router,u32 channel,const void* data,u32 len);

static u8  g_clusterEp[EP_BYTES] __attribute__((aligned(8)));
static u32 g_clusterVT[VT_MAX]   __attribute__((aligned(8)));
static u8  g_clusterId = 0xff;
static u8  g_clusterInId = 0xff;
static u8  g_clusterInEp[EP_BYTES] __attribute__((aligned(8)));
static u8  g_sdrBuf[8192];

struct ClusterProbe {
  u32 sdrSeen, sdrPatched, sdrLen, sdrMainVideo, sdrMainInput, sdrError, inMsgs, inLastId, setups, starts, stops, focusReq, cfgMsgs, frames, idr, other;
  u32 sessionId, setupCodec, lastOtherId, bytes, maxAu, lastTsHi, lastTsLo, sent, sendFail;
  u32 cfgLen, prefixCount, spsCropped, focusChanges, bindReplies, bindReqLen, inputsSent, cmdSeq;
  u32 insetsFile, insets[4], cmdReads, cmdBytes, cmdLines, decodeMode, fullFrame;
  u32 turnData, liveSent, live[4], liveBad;
  u32 idrSoft, idrCycle, secondIdr, ackConsumed, ackTimeouts, focusGated, dhu;
  u32 startIdrMs[4], startIdrN;   // ms from each Start to its first IDR (newest in [startIdrN-1&3])
  u8  bindReq[32];
  u8  cfg[512];
  u8  prefix[4][64];
};
static ClusterProbe g_cl;

static const char CL_PATH[]        = "/dev/shmem/aa_cluster_probe";
static const char CL_CFG_PATH[]    = "/dev/shmem/aa_cluster_config";
static const char CL_PREFIX_PATH[] = "/dev/shmem/aa_cluster_frames";

static u8* cl_varint(u8* o,u32 v){ while(v>=0x80){ *o++=(u8)(v|0x80); v>>=7; } *o++=(u8)v; return o; }
static u8* cl_field_u(u8* o,u32 field,u32 v){ o=cl_varint(o,field<<3); return cl_varint(o,v); }
static u8* cl_field_bytes(u8* o,u32 field,const u8* p,u32 n){
  o=cl_varint(o,(field<<3)|2); o=cl_varint(o,n); for(u32 i=0;i<n;i++) *o++=p[i]; return o;
}
static int cl_read_varint(const u8* p,u32 n,u32* i,u32* v){
  u32 r=0,s=0;
  while(*i<n && s<35){ u8 b=p[(*i)++]; r|=(u32)(b&0x7f)<<s; s+=7; if(!(b&0x80)){ *v=r; return 1; } }
  return 0;
}
// First varint field `want` of a flat message; 0 if absent.
static u32 cl_get_u(const u8* p,u32 n,u32 want){
  u32 i=0,key,v;
  while(i<n && cl_read_varint(p,n,&i,&key)){
    u32 f=key>>3, t=key&7;
    if(t==0){ if(!cl_read_varint(p,n,&i,&v)) return 0; if(f==want) return v; }
    else if(t==2){ if(!cl_read_varint(p,n,&i,&v) || v>n-i) return 0; i+=v; }
    else if(t==5) i+=4; else if(t==1) i+=8; else return 0;
  }
  return 0;
}

// Protobuf length-delimited field with an arbitrary payload; returns 0 on overflow.
static u8* cl_ld(u8* o,u8* end,u32 field,const u8* p,u32 n,const u8* extra,u32 en){
  u8 hdr[10]; u8* h=cl_varint(hdr,(field<<3)|2); h=cl_varint(h,n+en);
  u32 hn=(u32)(h-hdr);
  if(o+hn+n+en>end) return 0;
  for(u32 i=0;i<hn;i++) *o++=hdr[i];
  for(u32 i=0;i<n;i++) *o++=p[i];
  for(u32 i=0;i<en;i++) *o++=extra[i];
  return o;
}
// Walk a message: for each field call back with (field, wiretype, start, end-of-field, payload, plen).
// Returns 0 on malformed input.
static int cl_next(const u8* p,u32 n,u32* i,u32* field,u32* wt,u32* fstart,const u8** pl,u32* pn){
  u32 key,v; *fstart=*i;
  if(!cl_read_varint(p,n,i,&key)) return 0;
  *field=key>>3; *wt=key&7; *pl=0; *pn=0;
  if(*wt==0){ if(!cl_read_varint(p,n,i,&v)) return 0; }
  else if(*wt==2){ if(!cl_read_varint(p,n,i,&v) || v>n-*i) return 0; *pl=p+*i; *pn=v; *i+=v; }
  else if(*wt==5){ if(n-*i<4) return 0; *i+=4; }
  else if(*wt==1){ if(n-*i<8) return 0; *i+=8; }
  else return 0;
  return 1;
}
// Rewrite one Service: tag the main video sink 6:0 7:0 and the main input source 5:0.
static u8* cl_rewrite_service(u8* o,u8* end,const u8* sv,u32 sn){
  u8 tmp[1024]; u8* t=tmp; u32 i=0,f,wt,fs,pn; const u8* pl;
  while(i<sn){
    if(!cl_next(sv,sn,&i,&f,&wt,&fs,&pl,&pn)) return 0;
    if(f==3 && wt==2 && cl_get_u(pl,pn,1)==CL_CODEC_H264_BP && !cl_get_u(pl,pn,CLUSTER_FIELD_DISPLAY_TYPE)){
      u8 x[4]={(u8)(CLUSTER_FIELD_DISPLAY_ID<<3),0,(u8)(CLUSTER_FIELD_DISPLAY_TYPE<<3),0};
      t=cl_ld(t,tmp+sizeof tmp,3,pl,pn,x,4); g_cl.sdrMainVideo++;
    } else if(f==4 && wt==2){
      u8 x[2]={(u8)(CLUSTER_FIELD_INPUT_DISPLAY_ID<<3),0};
      t=cl_ld(t,tmp+sizeof tmp,4,pl,pn,x,2); g_cl.sdrMainInput++;
    } else {
      if(t+(i-fs)>tmp+sizeof tmp) return 0;
      for(u32 k=fs;k<i;k++) *t++=sv[k];
    }
    if(!t) return 0;
  }
  return cl_ld(o,end,1,tmp,(u32)(t-tmp),0,0);
}
// Content insets for tuning on the car without a rebuild: /tsd/var/aatokombi/insets holds
// "top bottom left right" (pixels); it is read each time the phone connects, so a change takes
// effect on the next connection. No file (or a bad one): the CLUSTER_INSET_* values.
// (A run-time 0x8009 UpdateUiConfigRequest, car 2026-11-05, made the phone drop the connection.)
#define CLUSTER_INSETS_FILE "/tsd/var/aatokombi/insets"
#define CLUSTER_INSETS_DEC_FILE "/tsd/var/aatokombi/insets-1280"
#define CLUSTER_FULL_FRAME_FILE "/tsd/var/aatokombi/full-frame"
// Experiment (from the Android Auto 18.1 UI-config analysis): UiConfig field 5 is a repeated
// UiElement the car itself shows; 5 = NAVIGATION_TURN_DATA_AVAILABLE ("the car shows turn data",
// CarDisplayUiFeatures bit 16). While this file exists the cluster's UiConfig announces it at
// connect: does the nav app then hide its own turn card on the cluster?
#define CLUSTER_TURN_DATA_FILE "/tsd/var/aatokombi/turn-data"
// Pass-through or decode mode, at each connection (the phone's resolution goes into the SDR).
static void cl_choose_geometry(){
  u32 pass=0;
  if(p_fopen && p_fclose){ void* f=p_fopen(CLUSTER_PASSTHROUGH_FILE,"r"); if(f){ p_fclose(f); pass=1; } }
  if(pass){
    g_geo.decode=0; g_geo.res=CLUSTER_RESOLUTION; g_geo.w=800u; g_geo.h=480u;
    g_geo.crop[0]=CLUSTER_CROP_LEFT; g_geo.crop[1]=CLUSTER_CROP_RIGHT; g_geo.crop[2]=CLUSTER_CROP_TOP; g_geo.crop[3]=CLUSTER_CROP_BOTTOM;
    g_geo.ins[0]=CLUSTER_INSET_TOP; g_geo.ins[1]=CLUSTER_INSET_BOTTOM; g_geo.ins[2]=CLUSTER_INSET_LEFT; g_geo.ins[3]=CLUSTER_INSET_RIGHT;
  } else {
    g_geo.decode=1; g_geo.res=2; g_geo.w=1280u; g_geo.h=720u;           // VideoCodecResolutionType 2
    g_geo.crop[0]=CLUSTER_DEC_CROP_LEFT; g_geo.crop[1]=CLUSTER_DEC_CROP_RIGHT;
    g_geo.crop[2]=CLUSTER_DEC_CROP_TOP;  g_geo.crop[3]=CLUSTER_DEC_CROP_BOTTOM;
    // Owner's choice 2026-11-13: window 134 110 1010 376 and insets 180 250 500 500 (top bottom
    // left right) - acceptable in every AID view; the fallback until there are per-view layouts
    // (run-time insets: /dev/shmem/aa_cluster_insets). Before: 228 242 134 136 with the window at
    // y=100 (car 2026-11-10). 2026-11-17: top 170, the cluster's street plate above the AA map is
    // now empty (Config.ClusterMap.HIDE_STREET_ON_AA_MAP).
    g_geo.ins[0]=CLUSTER_DEC_INSET_TOP;  g_geo.ins[1]=CLUSTER_DEC_INSET_BOTTOM;
    g_geo.ins[2]=CLUSTER_DEC_INSET_LEFT; g_geo.ins[3]=CLUSTER_DEC_INSET_RIGHT;
    // Layout check (diagnostics): /tsd/var/aatokombi/full-frame - no crop, libkb_dm shows the whole
    // 1280x720 frame scaled into the window, so where each navigation app draws its cards for the
    // insets in use can be seen (and photographed) on the AID.
    { void* f = p_fopen ? p_fopen(CLUSTER_FULL_FRAME_FILE,"r") : 0;
      if(f){ p_fclose(f); g_geo.crop[0]=0; g_geo.crop[1]=0; g_geo.crop[2]=0; g_geo.crop[3]=0; g_cl.fullFrame=1; }
      else g_cl.fullFrame=0; }
  }
  g_cl.decodeMode=g_geo.decode;
}
static void cl_load_insets(u32* in4){
  for(u32 j=0;j<4;j++) in4[j]=g_geo.ins[j];
  g_cl.insetsFile=0;
  if(p_fopen && p_fread && p_fclose){
    void* f=p_fopen(g_geo.decode ? CLUSTER_INSETS_DEC_FILE : CLUSTER_INSETS_FILE,"r");
    if(f){
      char b[64]; usize n=p_fread(b,1,sizeof b-1,f); p_fclose(f); b[n]=0;
      u32 v[4], k=0; const char* c=b;
      while(k<4){
        while(*c==' '||*c=='\t'||*c==',') c++;
        if(*c<'0'||*c>'9') break;
        u32 x=0; while(*c>='0'&&*c<='9'&&x<10000u){ x=x*10u+(u32)(*c-'0'); c++; }
        v[k++]=x;
      }
      if(k==4 && v[0]+v[1]<g_geo.h && v[2]+v[3]<g_geo.w){ for(u32 j=0;j<4;j++) in4[j]=v[j]; g_cl.insetsFile=1; }
      else g_cl.insetsFile=2;                                    // present but unusable
    }
  }
  for(u32 j=0;j<4;j++) g_cl.insets[j]=in4[j];
}
// Head unit identity as Google's Desktop Head Unit (plan item 7), the same values MIB2 Toolbox's
// "Android Auto custom apps patch" writes into MIB2 High's gal.json: with them the phone lets
// third-party (sideloaded) apps run on Android Auto. Only while /tsd/var/aatokombi/dhu exists,
// read at each connection. ServiceDiscoveryResponse fields of this libgal: 2 make, 3 model,
// 4 year, 5 vehicle_id, 7 head_unit_make, 8 head_unit_model, 9 sw build, 10 sw version.
// Not verified that current Android Auto versions still honour it; the phone will probably see
// a new car.
#define CLUSTER_DHU_FILE "/tsd/var/aatokombi/dhu"
static u32 cl_dhu_on(){
  if(!p_fopen || !p_fclose) return 0;
  void* f=p_fopen(CLUSTER_DHU_FILE,"r"); if(!f) return 0;
  p_fclose(f); return 1;
}
static u32 cl_slen(const char* t){ u32 n=0; while(t[n]) n++; return n; }
static u8* cl_field_str(u8* o,u8* end,u32 field,const char* t){
  u32 n=cl_slen(t); if(o+n+6>end) return 0;
  return cl_field_bytes(o,field,(const u8*)t,n);
}
static u8* cl_put_dhu(u8* o,u8* end){
  static const char* const v[11]={0,0,"Google","Desktop Head Unit","2015","0001",0,
                                  "Google","Desktop Head Unit","2015-09-16-2258745","1.0-windows"};
  for(u32 f=2;f<=10;f++){ if(!v[f]) continue; o=cl_field_str(o,end,f,v[f]); if(!o) return 0; }
  return o;
}
// out <- 2-byte id + rewritten SDR + cluster video service + cluster input service.
static u32 cl_rewrite_sdr(const u8* in,u32 n,u8* out,u32 cap){
  u8* o=out; u8* end=out+cap;
  if(n<2) return 0;
  cl_choose_geometry();
  *o++=in[0]; *o++=in[1];
  const u8* p=in+2; u32 pn_all=n-2, i=0, f, wt, fs, pn; const u8* pl;
  u32 dhu=cl_dhu_on(); g_cl.dhu=dhu;
  while(i<pn_all){
    if(!cl_next(p,pn_all,&i,&f,&wt,&fs,&pl,&pn)) return 0;
    if(f==1 && wt==2){ o=cl_rewrite_service(o,end,pl,pn); if(!o) return 0; }
    else if(dhu && wt==2 && f>=2 && f<=10 && f!=6){ /* replaced below */ }
    else { if(o+(i-fs)>end) return 0; for(u32 k=fs;k<i;k++) *o++=p[k]; }
  }
  if(dhu){ o=cl_put_dhu(o,end); if(!o) return 0; }
  // cluster video
  u8 vc[64], ms[112], sv[128]; u8* q;
  q=vc; q=cl_field_u(q,1,g_geo.res); q=cl_field_u(q,2,CLUSTER_FRAME_RATE);
  q=cl_field_u(q,3,CLUSTER_WIDTH_MARGIN); q=cl_field_u(q,4,CLUSTER_HEIGHT_MARGIN); q=cl_field_u(q,5,CLUSTER_DENSITY);
  q=cl_field_u(q,6,CLUSTER_DECODER_DEPTH);
  q=cl_field_u(q,7,CLUSTER_VIEWING_DISTANCE);
  {
    // UiConfig (field 11): field 2 = content insets {top,bottom,left,right} -> the phone's
    // DisplayParams.contentInsets; field 4 = UI theme. Field 1 is deliberately NOT used: it
    // shrinks the encoded picture and draws black borders (mib-cluster-map, car-tested).
    u8 ui[40]; u8* t=ui;
    u32 ins[4]; cl_load_insets(ins);
    if(ins[0]+ins[1]+ins[2]+ins[3]){
      u8 in[24]; u8* r=in;
      for(u32 k=0;k<4;k++) if(ins[k]) r=cl_field_u(r,k+1,ins[k]);
      t=cl_field_bytes(t,2,in,(u32)(r-in));
    }
    t=cl_field_u(t,4,CLUSTER_UI_THEME);
    { void* f = p_fopen ? p_fopen(CLUSTER_TURN_DATA_FILE,"r") : 0;
      if(f){ p_fclose(f); t=cl_field_u(t,5,5); g_cl.turnData=1; } else g_cl.turnData=0; }
    q=cl_field_bytes(q,11,ui,(u32)(t-ui));
  }
  u32 nvc=(u32)(q-vc);
  q=ms; q=cl_field_u(q,1,CL_CODEC_H264_BP); q=cl_field_bytes(q,4,vc,nvc);
  q=cl_field_u(q,CLUSTER_FIELD_DISPLAY_ID,CLUSTER_DISPLAY_ID);
  q=cl_field_u(q,CLUSTER_FIELD_DISPLAY_TYPE,CLUSTER_DISPLAY_TYPE);
  u32 nms=(u32)(q-ms);
  q=sv; q=cl_field_u(q,1,g_clusterId); q=cl_field_bytes(q,3,ms,nms);
  o=cl_ld(o,end,1,sv,(u32)(q-sv),0,0); if(!o) return 0;
  // cluster input: keycodes (packed varints), display_id 1. mib-cluster-map found the phone binds
  // only the keys advertised here; its zoom test sent 168/169 without advertising them.
  static const u32 keycodes[]={CLUSTER_KEYCODES};
  u8 keys[48]; u8* kq=keys;
  for(u32 i=0;i<sizeof keycodes/sizeof keycodes[0];i++) kq=cl_varint(kq,keycodes[i]);
  u8 is[64]; q=is; q=cl_field_bytes(q,1,keys,(u32)(kq-keys));
  q=cl_field_u(q,CLUSTER_FIELD_INPUT_DISPLAY_ID,CLUSTER_DISPLAY_ID);
  u8 sv2[80]; u8* r=sv2; r=cl_field_u(r,1,g_clusterInId); r=cl_field_bytes(r,4,is,(u32)(q-is));
  o=cl_ld(o,end,1,sv2,(u32)(r-sv2),0,0); if(!o) return 0;
  return (u32)(o-out);
}

// Text for the probe file. Every helper checks the room that is left (g_clEnd, set by the one
// function that formats) and writes nothing when its piece does not fit, so a longer probe line
// is cut short instead of running over the buffer on the receiving thread's stack.
static char* g_clEnd;
static u32 g_clCut;                    // pieces dropped for lack of room (0 unless the buffer is too small)
static int cl_room(char* o,u32 n){ if(g_clEnd && o+n<=g_clEnd) return 1; g_clCut++; return 0; }
static void cl_hex(char*& o,const u8* p,u32 n){
  static const char h[]="0123456789abcdef";
  if(!cl_room(o,2u*n)) return;
  for(u32 i=0;i<n;i++){ *o++=h[p[i]>>4]; *o++=h[p[i]&15]; }
}
static void cl_put(char*& o,const char* s){ if(!cl_room(o,cl_slen(s))) return; while(*s) *o++=*s++; }
// "<key><decimal>": 11 = ten digits + the terminator u2dec writes.
static void cl_pv(char*& o,const char* k,u32 v){ if(!cl_room(o,cl_slen(k)+11u)) return; while(*k) *o++=*k++; o=u2dec(o,v); }
static void cl_kv(char*& o,const char* k,u32 v){ if(!cl_room(o,cl_slen(k)+12u)) return; while(*k) *o++=*k++; o=u2dec(o,v); *o++=' '; }
static void cl_ch(char*& o,char c){ if(cl_room(o,1)) *o++=c; }

static void cluster_flush(){
  char b[2048]; char* o=b;
  g_clEnd=b+sizeof b-1; g_clCut=0;                 // one byte kept for the final newline
  cl_kv(o,"id=",g_clusterId==0xff?0:g_clusterId);
  cl_kv(o,"sdr_seen=",g_cl.sdrSeen); cl_kv(o,"sdr_patched=",g_cl.sdrPatched); cl_kv(o,"sdr_len=",g_cl.sdrLen);
  cl_kv(o,"sdr_main_video=",g_cl.sdrMainVideo); cl_kv(o,"sdr_main_input=",g_cl.sdrMainInput);
  cl_kv(o,"sdr_error=",g_cl.sdrError); cl_kv(o,"input_id=",g_clusterInId==0xff?0:g_clusterInId);
  cl_kv(o,"input_msgs=",g_cl.inMsgs); cl_kv(o,"input_last_id=",g_cl.inLastId);
  cl_kv(o,"setup=",g_cl.setups); cl_kv(o,"setup_codec=",g_cl.setupCodec);
  cl_kv(o,"start=",g_cl.starts); cl_kv(o,"session=",g_cl.sessionId); cl_kv(o,"stop=",g_cl.stops);
  cl_kv(o,"focus_req=",g_cl.focusReq); cl_kv(o,"focus_on=",g_focusOn);
  cl_kv(o,"focus_changes=",g_cl.focusChanges); cl_kv(o,"idr_soft=",g_cl.idrSoft); cl_kv(o,"idr_cycle=",g_cl.idrCycle); cl_kv(o,"second_idr=",g_cl.secondIdr);
  cl_kv(o,"start_idr_n=",g_cl.startIdrN);
  cl_kv(o,"start_idr_ms0=",g_cl.startIdrMs[0]); cl_kv(o,"start_idr_ms1=",g_cl.startIdrMs[1]);
  cl_kv(o,"start_idr_ms2=",g_cl.startIdrMs[2]); cl_kv(o,"start_idr_ms3=",g_cl.startIdrMs[3]); cl_kv(o,"bind_replies=",g_cl.bindReplies);
  cl_kv(o,"inputs_sent=",g_cl.inputsSent); cl_kv(o,"cmd_seq=",g_cl.cmdSeq); cl_kv(o,"hmi_video=",g_hmiVideo);
  cl_put(o,"bind_req="); cl_hex(o,g_cl.bindReq,g_cl.bindReqLen); cl_ch(o,' '); cl_kv(o,"guidance_only=",g_guideOnly); cl_kv(o,"video_off=",g_videoOff); cl_kv(o,"nav_status=",g_st.status); cl_kv(o,"codec_cfg=",g_cl.cfgMsgs); cl_kv(o,"cfg_len=",g_cl.cfgLen);
  cl_kv(o,"sps_cropped=",g_cl.spsCropped);
  cl_kv(o,"insets_file=",g_cl.insetsFile); cl_kv(o,"insets_t=",g_cl.insets[0]); cl_kv(o,"insets_b=",g_cl.insets[1]);
  cl_kv(o,"insets_l=",g_cl.insets[2]); cl_kv(o,"insets_r=",g_cl.insets[3]);
  cl_kv(o,"cmd_reads=",g_cl.cmdReads); cl_kv(o,"cmd_bytes=",g_cl.cmdBytes); cl_kv(o,"cmd_lines=",g_cl.cmdLines);
  cl_kv(o,"shm=",g_kv?1:0); cl_kv(o,"shm_frames=",g_kv?g_kv->frames:0); cl_kv(o,"shm_idr_req=",g_kv?g_kv->idr_requests:0);
  cl_kv(o,"shm_big=",g_kv?g_kv->dropped_big:0); cl_kv(o,"shm_max_frame=",g_kv?g_kv->max_frame:0); cl_kv(o,"rd_frames=",g_kv?g_kv->rd_frames:0);
  cl_kv(o,"rd_state=",g_kv?g_kv->rd_state:0); cl_kv(o,"rd_ring_waits=",g_kv?g_kv->rd_ring_waits:0);
  cl_kv(o,"rd_ring_lost=",g_kv?g_kv->rd_ring_lost:0);
  cl_kv(o,"ack_pending=",g_ackPending); cl_kv(o,"ack_consumed=",g_cl.ackConsumed); cl_kv(o,"ack_timeouts=",g_cl.ackTimeouts);
  cl_kv(o,"focus_gated=",g_cl.focusGated); cl_kv(o,"dhu=",g_cl.dhu); cl_kv(o,"kbps_cap=",CLUSTER_KBPS);
  cl_kv(o,"frames=",g_cl.frames); cl_kv(o,"idr=",g_cl.idr); cl_kv(o,"bytes=",g_cl.bytes);
  cl_kv(o,"max_au=",g_cl.maxAu); cl_kv(o,"ts_hi=",g_cl.lastTsHi); cl_kv(o,"ts_lo=",g_cl.lastTsLo);
  cl_kv(o,"sent=",g_cl.sent); cl_kv(o,"send_fail=",g_cl.sendFail);
  cl_kv(o,"other=",g_cl.other); cl_kv(o,"last_other_id=",g_cl.lastOtherId);
  cl_pv(o,"field_display_id=",CLUSTER_FIELD_DISPLAY_ID);
  cl_pv(o," field_display_type=",CLUSTER_FIELD_DISPLAY_TYPE);
  cl_pv(o," display_type=",CLUSTER_DISPLAY_TYPE);
  cl_pv(o," resolution=",g_geo.res); cl_pv(o," decode_mode=",g_geo.decode); cl_pv(o," full_frame=",g_cl.fullFrame); cl_pv(o," turn_data=",g_cl.turnData);
  cl_pv(o," live_sent=",g_cl.liveSent); cl_pv(o," live_bad=",g_cl.liveBad);
  cl_pv(o," live_t=",g_cl.live[0]); cl_pv(o," live_b=",g_cl.live[1]);
  cl_pv(o," live_l=",g_cl.live[2]); cl_pv(o," live_r=",g_cl.live[3]); g_clEnd=b+sizeof b; cl_ch(o,'\n');
  write_file(CL_PATH,b,(usize)(o-b));
  write_file(CL_CFG_PATH,(const char*)g_cl.cfg,g_cl.cfgLen);
  write_file(CL_PREFIX_PATH,(const char*)g_cl.prefix,g_cl.prefixCount*64u);
}

static void cluster_reset(){
  my_memset(&g_cl,0,sizeof g_cl);
}
static void cluster_video_off();

static void cl_send_on(u8* ep,u32 type,const u8* body,u32 n){
  u8 m[64];
  if(n>sizeof m-2 || !ep_open(ep)){ g_cl.sendFail++; return; }       // channel open (stock flag)
  m[0]=(u8)(type>>8); m[1]=(u8)type;
  for(u32 i=0;i<n;i++) m[2+i]=body[i];
  qout_t q=(qout_t)ref(R_QUEUE_OUT);
  if(!q){ g_cl.sendFail++; return; }
  q(*(u32*)(ep+lay(L_EP_ROUTER)), ep[lay(L_EP_CHANNEL)], m, n+2);
  g_cl.sent++;
}
static void cl_send(u32 type,const u8* body,u32 n){ cl_send_on(g_clusterEp,type,body,n); }
static void cl_send_focus_mode(u32 mode,u32 unsolicited){
  u8 b[8]; u8* o=b; o=cl_field_u(o,1,mode); o=cl_field_u(o,2,unsolicited?1:0);
  cl_send(0x8008,b,(u32)(o-b));
}
static void cl_send_focus(u32 unsolicited){
  u8 b[8]; u8* o=b; o=cl_field_u(o,1,1); o=cl_field_u(o,2,unsolicited?1:0);
  cl_send(0x8008,b,(u32)(o-b));
}
static void cl_ack(){
  u8 b[12]; u8* o=b; o=cl_field_u(o,1,g_cl.sessionId); o=cl_field_u(o,2,1);
  cl_send(0x8004,b,(u32)(o-b));
}

static u32 cl_nal_idr(const u8* p,u32 n){
  for(u32 i=0;i+4<n;i++){
    if(p[i]==0 && p[i+1]==0 && p[i+2]==1){ u32 t=p[i+3]&0x1f; if(t==5) return 1; i+=2; }
  }
  return 0;
}


// ===== cluster video -> DisplayManager (shared memory, cluster_video_shm.h) =====================
static u32 g_kvTried;
static volatile u32 g_clStarted;     // between the phone's Start and Stop on the cluster channel
static volatile u32 g_idrState;      // 0 idle, 1 focus taken away, waiting to give it back
static volatile u32 g_idrT, g_idrLast, g_idrSoftT, g_idrCycleT, g_wantSince, g_startIdrT;
static u32 g_dmPrev, g_secondIdrDone, g_secondIdrAt;          // reset per AA session (cluster_register); Done = ms of the last one
#ifndef CLUSTER_SECOND_IDR_MS
#define CLUSTER_SECOND_IDR_MS 1500
#endif
static u32 g_tickerUp;
// The ticker thread outlives every AA session. g_clSession = 1 only between cluster_register and
// the next GalReceiver::shutdown / init: outside it the ticker sends nothing (the MessageRouter is
// shut down and the endpoints are about to be cleared). g_tickBusy = 1 while a tick is at work, so
// the session's end can wait for the tick in flight (cluster_video_off). No lock: a spin lock here
// most likely hung the SAL at the phone's disconnect (2026-11-05).
static volatile u32 g_clSession, g_tickBusy;
static u8  g_spsPps[160]; static u32 g_spsPpsLen;   // rewritten SPS + PPS, Annex-B

static u32 cl_now_ms(){
  u32 ts[2]={0,0}; if(p_clock_gettime) (void)p_clock_gettime(2,ts);   // CLOCK_MONOTONIC
  return ts[0]*1000u+ts[1]/1000000u;
}

static int kv_map(){
  if(g_kv) return 1;
  if(g_kvTried || !p_shm_open || !p_ftruncate || !p_mmap || !p_close) return 0;
  g_kvTried=1;
  int fd=p_shm_open(KBCV_SHM_NAME,0x0002|0x0100,0666);
  if(fd<0) return 0;
  if(p_ftruncate(fd,(long)sizeof(struct kbcv))<0){ p_close(fd); return 0; }
  void* m=p_mmap(0,sizeof(struct kbcv),0x0300,0x0001,fd,0);
  p_close(fd);
  if(!m || m==(void*)-1) return 0;
  struct kbcv* v=(struct kbcv*)m;
  if(v->magic!=KBCV_MAGIC || v->version!=KBCV_VERSION){
    // New memory (RAM, every boot), or one a libgal of another layout made: nothing in it counts.
    v->magic=0; __sync_synchronize();
    kbcv_ring_reset(v);
    v->sem_ok=0;
  }
  if(!v->sem_ok){
    // A semaphore a reader may already sleep on is never re-made.
    v->sem_ok = (p_sem_init && p_sem_init(&v->frame_ready,1,0)==0) ? 1u : 0u;
  }
  v->alive_ms=0;
  v->version=KBCV_VERSION;
  __sync_synchronize();
  v->magic=KBCV_MAGIC;
  g_kv=v;
  return 1;
}

// --- SPS crop rewrite (Baseline/Main/Extended only; anything else is passed through) ---------
struct BitR { const u8* p; u32 n, pos; };
struct BitW { u8* p; u32 cap, pos; int ok; };
static u32 br_bit(BitR* r){ if(r->pos>=r->n*8u){ r->pos++; return 0; } u32 b=(r->p[r->pos>>3]>>(7-(r->pos&7)))&1u; r->pos++; return b; }
static u32 br_u(BitR* r,u32 k){ u32 v=0; while(k--) v=(v<<1)|br_bit(r); return v; }
static u32 br_ue(BitR* r){ u32 z=0; while(!br_bit(r)){ if(++z>31) return 0; } return (z? ((1u<<z)-1u+br_u(r,z)) : 0u); }
static void bw_bit(BitW* w,u32 b){
  if(w->pos>=w->cap*8u){ w->ok=0; return; }
  u8* q=&w->p[w->pos>>3]; u32 sh=7-(w->pos&7);
  if(b) *q|=(u8)(1u<<sh); else *q&=(u8)~(1u<<sh);
  w->pos++;
}
static void bw_u(BitW* w,u32 v,u32 k){ while(k--) bw_bit(w,(v>>k)&1u); }
static void bw_ue(BitW* w,u32 v){ u32 x=v+1, z=0; while((x>>z)>1u) z++; bw_u(w,0,z); bw_u(w,x,z+1); }

// nal: one SPS NAL without start code (header byte first). Returns the new NAL length, 0 = keep.
static u32 sps_crop(const u8* nal,u32 n,u8* out,u32 cap){
  u8 rb[96]; u32 rn=0;
  if(n<4 || n>sizeof rb) return 0;
  for(u32 i=1;i<n;i++){                              // unescape 00 00 03
    if(i+2<n && nal[i]==0 && nal[i+1]==0 && nal[i+2]==3){ rb[rn++]=0; rb[rn++]=0; i+=2; continue; }
    rb[rn++]=nal[i];
  }
  u32 last=rn*8u; while(last>0 && !((rb[(last-1)>>3]>>(7-((last-1)&7)))&1u)) last--;
  if(!last) return 0;
  u32 stop=last-1;                                   // position of rbsp_stop_one_bit
  u8 wb[100]; my_memset(wb,0,sizeof wb);
  BitR r={rb,rn,0}; BitW w={wb,sizeof wb,0,1};
  u32 profile=br_u(&r,8); bw_u(&w,profile,8);
  if(profile!=66 && profile!=77 && profile!=88) return 0;
  bw_u(&w,br_u(&r,16),16);                           // constraint flags + level
  bw_ue(&w,br_ue(&r));                               // seq_parameter_set_id
  bw_ue(&w,br_ue(&r));                               // log2_max_frame_num_minus4
  u32 poc=br_ue(&r); bw_ue(&w,poc);
  if(poc==0) bw_ue(&w,br_ue(&r));
  else if(poc==1) return 0;
  bw_ue(&w,br_ue(&r));                               // max_num_ref_frames
  bw_bit(&w,br_bit(&r));                             // gaps_in_frame_num_allowed
  bw_ue(&w,br_ue(&r)); bw_ue(&w,br_ue(&r));          // pic width / height in mbs - 1
  u32 fmo=br_bit(&r); bw_bit(&w,fmo);
  if(!fmo) return 0;                                 // field coding: not expected, keep stock
  bw_bit(&w,br_bit(&r));                             // direct_8x8_inference
  u32 cl=0,cr=0,ct=0,cb=0;
  if(br_bit(&r)){ cl=br_ue(&r); cr=br_ue(&r); ct=br_ue(&r); cb=br_ue(&r); }
  bw_bit(&w,1);
  bw_ue(&w,cl+g_geo.crop[0]/2); bw_ue(&w,cr+g_geo.crop[1]/2);
  bw_ue(&w,ct+g_geo.crop[2]/2); bw_ue(&w,cb+g_geo.crop[3]/2);
  if(r.pos>stop) return 0;
  while(r.pos<stop) bw_bit(&w,br_bit(&r));           // VUI and anything else, verbatim
  bw_bit(&w,1); while(w.pos&7) bw_bit(&w,0);         // rbsp trailing bits
  if(!w.ok) return 0;
  u32 wn=w.pos>>3, o=0, zeros=0;
  if(cap<2) return 0;
  out[o++]=nal[0];
  for(u32 i=0;i<wn;i++){                             // re-escape
    if(zeros>=2 && wb[i]<=3){ if(o>=cap) return 0; out[o++]=3; zeros=0; }
    if(o>=cap) return 0;
    out[o++]=wb[i]; zeros = wb[i]==0 ? zeros+1 : 0;
  }
  return o;
}

// Codec config (Annex-B SPS + PPS): keep it with the SPS cropped, to put in front of every IDR.
static void cl_store_config(const u8* p,u32 n){
  u32 o=0, i=0;
  g_spsPpsLen=0;
  while(i+3<n){
    u32 sc = (p[i]==0&&p[i+1]==0&&p[i+2]==1)?3u : (i+4<=n&&p[i]==0&&p[i+1]==0&&p[i+2]==0&&p[i+3]==1)?4u:0u;
    if(!sc){ i++; continue; }
    u32 s=i+sc, e=s;
    while(e+3<=n && !(p[e]==0&&p[e+1]==0&&(p[e+2]==1||(e+3<n&&p[e+2]==0&&p[e+3]==1)))) e++;
    if(e+3>n) e=n;
    u32 len=e-s; if(!len){ i=s; continue; }
    if(o+4+len+16>sizeof g_spsPps) return;
    g_spsPps[o++]=0; g_spsPps[o++]=0; g_spsPps[o++]=0; g_spsPps[o++]=1;
    u32 w = ((p[s]&0x1f)==7) ? sps_crop(p+s,len,g_spsPps+o,sizeof g_spsPps-o) : 0;
    if(w){ o+=w; g_cl.spsCropped++; }
    else { for(u32 k=0;k<len;k++) g_spsPps[o++]=p[s+k]; }
    i=e;
  }
  g_spsPpsLen=o;
}

static u32 cl_nal_flags(const u8* p,u32 n,u32* hasSps){
  u32 idr=0; *hasSps=0;
  for(u32 i=0;i+3<n;i++){
    if(p[i]==0 && p[i+1]==0 && p[i+2]==1){
      u32 t=p[i+3]&0x1f; if(t==5) idr=1; if(t==7) *hasSps=1;
      if(t==1 || t==5) break;                        // the first slice ends the header NALs
      i+=2;
    }
  }
  return idr;
}


static u32 g_pubSeq;                 // ring slot of the frame just published (0 = none)
static void cl_publish(const u8* au,u32 n){
  struct kbcv* v=g_kv; if(!v || !au || !n) return;
  u32 hasSps=0, idr=cl_nal_flags(au,n<4096?n:4096,&hasSps);
  u32 pre = (idr && !hasSps) ? g_spsPpsLen : 0;
  // The limit is the decoder's: the AID's own (250 000 bytes) when its H.264 is passed through,
  // the head unit's in decode mode, where only the ring's size counts (key frames of the 1280x720
  // stream reach ~280 KB, car 2026-11-15: all of them used to be dropped).
  u32 max = g_geo.decode ? KBCV_FRAME_MAX : KBCV_AU_MAX;
  // an SPS inside the AU (not seen from AA so far) goes out uncropped; the config copy wins.
  u32 seq=kbcv_put(v,g_spsPps,pre,au,n,idr?KBCV_FLAG_IDR:0,max);
  if(!seq){ v->dropped_big++; v->want_idr=1; return; }
  g_pubSeq=seq;
  v->frames++;
  if(idr){
    v->idr++; g_idrSoftT=0; g_wantSince=0;
    if(g_startIdrT){ g_cl.startIdrMs[g_cl.startIdrN&3u]=cl_now_ms()-g_startIdrT; g_cl.startIdrN++; g_startIdrT=0; }
  }
  __sync_synchronize();
  if(v->waiting && v->sem_ok && p_sem_post) p_sem_post(&v->frame_ready);
}

static u32 g_stateMs, g_stateTick, g_stateSent;

static void cl_refill(u32 now){
  u32 last=g_budgetMs; g_budgetMs=now;
  if(!last) return;
  u32 dt=now-last; if(dt>CLUSTER_BURST_MS) dt=CLUSTER_BURST_MS;
  int add=(int)(dt*(u32)(CLUSTER_KBPS/8));                    // kbit/s / 8 = bytes per ms
  int cap=(int)(CLUSTER_BURST_MS*(u32)(CLUSTER_KBPS/8));
  int b=g_budget+add; if(b>cap) b=cap; g_budget=b;
}
// Acknowledgements. AA's flow control: the phone keeps at most max_unacked frames in flight.
// Two ways to acknowledge a data frame:
//  - by budget (always so before 2026-11-05): when it arrives, if the CLUSTER_KBPS budget allows;
//  - by consumption (default while DisplayManager shows the phone's video): only once libkb_dm.so
//    has taken it from the ring (kv->rd_seq), so the phone is paced by what really goes to the
//    cluster over MOST, not by our estimate. Not while the stock map shows (DisplayManager does
//    not read the ring then; the phone would stall), not with an older libkb_dm.so (rd_caps 0),
//    not while /tsd/var/aatokombi/ack-on-arrival exists. Safety: a frame waits at most
//    CLUSTER_ACK_MAX_WAIT_MS.
#ifndef CLUSTER_ACK_MAX_WAIT_MS
#define CLUSTER_ACK_MAX_WAIT_MS 500
#endif
#define CLUSTER_ACK_FILE "/tsd/var/aatokombi/ack-on-arrival"
#define CL_PEND 64u
// Lock-free single-producer / single-consumer queue of unacknowledged frames: the receiving thread
// (cl_ack_paced) only appends at g_pendTail, the ticker (cl_release_acks) only acknowledges and
// advances g_pendHead. (A spin lock here, 2026-11-05, most likely hung the SAL at the phone's
// disconnect and the head unit rebooted.) g_ackPending mirrors tail - head for the probe.
static u32 g_pendSeq[CL_PEND], g_pendT[CL_PEND];
static volatile u32 g_pendHead, g_pendTail, g_drainReq;
static u32 g_ackFileChk, g_ackFile;
static u32 cl_ack_by_consumption(u32 now){
  struct kbcv* v=g_kv;
  if(!v || !v->rd_caps || v->rd_state!=1 || !v->rd_alive_ms || now-v->rd_alive_ms>=1000u) return 0;
  if(!g_ackFileChk || now-g_ackFileChk>=2000u){
    g_ackFileChk=now|1u; g_ackFile=0;
    if(p_fopen && p_fclose){ void* f=p_fopen(CLUSTER_ACK_FILE,"r"); if(f){ p_fclose(f); g_ackFile=1; } }
  }
  return g_ackFile ? 0u : 1u;
}
// Ticker only: acknowledge what may be acknowledged now.
static void cl_release_acks(u32 now){
  cl_refill(now);
  u32 drain=g_drainReq; if(drain) g_drainReq=0;
  u32 cons=cl_ack_by_consumption(now);
  struct kbcv* v=g_kv;
  for(;;){
    u32 h=g_pendHead; __sync_synchronize();
    if(h==g_pendTail) break;
    u32 k=h%CL_PEND, sq=g_pendSeq[k];
    u32 ok;
    if(drain) ok=1;
    else if(now-g_pendT[k] >= (u32)CLUSTER_ACK_MAX_WAIT_MS){ ok=1; g_cl.ackTimeouts++; }
    else if(cons) ok = (!sq || (v && (i32)(v->rd_seq - sq) >= 0)) ? 1u : 0u;
    else ok = g_budget>=0 ? 1u : 0u;
    if(!ok) break;
    __sync_synchronize();
    g_pendHead=h+1u;
    if(cons && !drain) g_cl.ackConsumed++;
    cl_ack();
  }
  g_ackPending=g_pendTail-g_pendHead;
}
// Receiving thread: a data frame of n bytes arrived (published as ring slot seq, 0 = not published).
static void cl_ack_paced(u32 n,u32 seq){
  u32 now=cl_now_ms();
  __sync_fetch_and_sub(&g_budget,(int)n);
  u32 t=g_pendTail;
  if(t-g_pendHead >= CL_PEND-1u){ cl_ack(); return; }     // queue full: never lose a credit
  g_pendSeq[t%CL_PEND]=seq; g_pendT[t%CL_PEND]=now;
  __sync_synchronize();
  g_pendTail=t+1u;
  g_ackPending=g_pendTail-g_pendHead;
}
// Start / Stop: the ticker returns every credit the phone still counts (within ~10 ms).
static void cl_ack_all(){ g_drainReq=1; }

// /dev/shmem/aa_cluster_video for the HMI: "<alive> <dm> <tick>". alive = the phone's cluster
// stream is started; dm = DisplayManager is actually handing those frames to the cluster
// (libkb_dm.so heartbeat). The jar hides the stock cluster map only when dm is 1, so without the
// DisplayManager hook (or with it switched off) the stock map keeps running.
static u32 g_stateDm;
static void cl_state_file(u32 now,u32 alive){
  struct kbcv* v=g_kv;
  u32 dm = (alive && v && v->rd_alive_ms && now - v->rd_alive_ms < 2000u) ? 1u : 0u;
  if(now-g_stateMs<1000u && alive==g_stateSent && dm==g_stateDm) return;
  g_stateMs=now; g_stateSent=alive; g_stateDm=dm; g_stateTick++;
  // 4th field: the cluster channel of an AA session is open (also without video, e.g. no route)
  char b[40]; char* o=b; *o++= alive?'1':'0'; *o++=' '; *o++= dm?'1':'0'; *o++=' '; o=u2dec(o,g_stateTick);
  *o++=' '; *o++= (g_clSession && ep_open(g_clusterEp))?'1':'0'; *o++='\n';
  write_file("/dev/shmem/aa_cluster_video",b,(usize)(o-b));
}

// ---- video focus: map always, or only during route guidance ----
static u32 cl_guidance_only(u32 now){
  if(!g_guideChecked || now-g_guideChecked>=1000u){
    g_guideChecked=now|1u;
    void* f = p_fopen ? p_fopen(CLUSTER_GUIDANCE_ONLY_FLAG,"r") : 0;
    g_guideOnly = f ? 1u : 0u;
    if(f) p_fclose(f);
  }
  return g_guideOnly;
}
// Receiver readiness (plan item 4): the phone gets focus on the cluster only while DisplayManager's
// cluster reader runs (libkb_dm.so rd_ready_ms: the cluster takes video at all), so its Start and
// first key frame come when they can be shown, and it does not encode for nothing while the AID
// shows something else. Ready after CLUSTER_READY_ON_MS of reader activity, not ready after
// CLUSTER_READY_OFF_MS without. Off (old behaviour) with an older libkb_dm.so (rd_caps 0) or
// unless /tsd/var/aatokombi/focus-gate exists (see below).
#ifndef CLUSTER_READY_ON_MS
#define CLUSTER_READY_ON_MS 300
#endif
#ifndef CLUSTER_READY_OFF_MS
#define CLUSTER_READY_OFF_MS 5000
#endif
// Car 2026-11-07: still 11 focus drops by this check (the map restarted each time), cause not
// found: OFF by default now, on only while /tsd/var/aatokombi/focus-gate exists.
#define CLUSTER_FOCUS_GATE_FILE "/tsd/var/aatokombi/focus-gate"
static u32 g_readySince, g_readyChk, g_focusAlways;
static u32 cl_receiver_ready(u32 now){
  struct kbcv* v=g_kv;
  if(!v || !v->rd_caps) return 1u;
  if(!g_readyChk || now-g_readyChk>=2000u){
    g_readyChk=now|1u; g_focusAlways=1;
    if(p_fopen && p_fclose){ void* f=p_fopen(CLUSTER_FOCUS_GATE_FILE,"r"); if(f){ p_fclose(f); g_focusAlways=0; } }
  }
  if(g_focusAlways) return 1u;
  u32 r=v->rd_ready_ms;
  u32 fresh = (r && now-r < 1000u) ? 1u : 0u;
  if(fresh){ if(!g_readySince) g_readySince=now|1u; }
  if(!r || now-r >= (u32)CLUSTER_READY_OFF_MS){ g_readySince=0; return 0u; }
  if(!g_readySince) return g_focusOn;                 // stale but not yet off: keep what we have
  return (now-g_readySince >= (u32)CLUSTER_READY_ON_MS || g_focusOn) ? 1u : 0u;
}
static u32 cl_video_off(u32 now){
  if(!g_videoOffChecked || now-g_videoOffChecked>=1000u){
    g_videoOffChecked=now|1u;
    void* f = p_fopen ? p_fopen(CLUSTER_VIDEO_OFF_FLAG,"r") : 0;
    g_videoOff = f ? 1u : 0u;
    if(f && p_fclose) p_fclose(f);
  }
  return g_videoOff;
}
// Whether the phone should stream the cluster map now.
static u32 cl_want_video(u32 now){
  if(!g_hmiVideo || cl_video_off(now)) return 0u;
  if(!cl_receiver_ready(now)){ return 0u; }
  if(!cl_guidance_only(now)) return 1u;
  if(g_st.status==1u || g_st.status==3u){ g_guideMs=now|1u; return 1u; }   // ACTIVE / REROUTING
  return (g_guideMs && now-g_guideMs < (u32)CLUSTER_GUIDANCE_HOLD_MS) ? 1u : 0u;
}
static u32 g_focusChangeT;
static void cl_apply_focus(u32 want){
  if(want==g_focusOn) return;
  u32 now=cl_now_ms();
  if(g_focusChangeT && now-g_focusChangeT<2000u) return;   // no flapping
  g_focusChangeT=now|1u;
  if(!want && g_kv && g_kv->rd_caps && g_hmiVideo) g_cl.focusGated++;
  g_focusOn=want;
  cl_send_focus_mode(want?1u:2u,1);                    // PROJECTED / NATIVE, unsolicited
  g_cl.focusChanges++;
}

// ---- input to the phone on the cluster input channel ----
// InputReport 0x8001: {1 timestamp ns, 4 KeyEvent{1 Key{1 code, 2 down, 3 metastate, 4 longpress}}}
// or {1 timestamp, 6 RelativeEvent{1 Rel{1 code, 2 delta}}} (field numbers as mib-cluster-map
// read them from the receiver's k*FieldNumber constants).
static u8* cl_varint64(u8* o,u32 hi,u32 lo){
  for(;;){
    u32 b=lo&0x7fu; lo=(lo>>7)|(hi<<25); hi>>=7;
    if(!hi && !lo){ *o++=(u8)b; return o; }
    *o++=(u8)(b|0x80u);
  }
}
static void cl_send_input(u32 code,u32 relative,i32 value){
  if(!ep_open(g_clusterInEp)){ g_cl.sendFail++; return; }
  u32 ms=cl_now_ms();
  // ns = ms * 1e6 as 64-bit: hi/lo of ms*1000000
  u32 lo = ms*1000000u;
  u32 hi = (u32)(((unsigned long long)ms*1000000ull)>>32);
  u8 in[24]; u8* t=in;
  t=cl_field_u(t,1,code);
  if(relative){ t=cl_varint(t,(2<<3)|0); t= value>=0 ? cl_varint(t,(u32)value) : cl_varint64(t,0xffffffffu,(u32)value); }
  else { t=cl_field_u(t,2,value?1u:0u); t=cl_field_u(t,3,0); t=cl_field_u(t,4,0); }
  u8 ev[32]; u8* e=ev; e=cl_field_bytes(e,1,in,(u32)(t-in));
  u8 m[56]; u8* o=m;
  o=cl_varint(o,(1<<3)|0); o=cl_varint64(o,hi,lo);
  o=cl_field_bytes(o,relative?6:4,ev,(u32)(e-ev));
  cl_send_on(g_clusterInEp,0x8001,m,(u32)(o-m));
  g_cl.inputsSent++;
}
static void cl_key(u32 code){ cl_send_input(code,0,1); cl_send_input(code,0,0); }

// Commands from the HMI jar: /dev/shmem/aa_cluster_cmd holds the last few lines
// "<seq> <cmd> <args>"; every line with a sequence above the last one done is executed.
//   key <code>              press + release
//   rot <code> <delta>      relative event (rotary)
//   rep <code> <ms>         repeat "key <code>" every <ms> until "rep 0 0" (continuous zoom)
//   video <0|1>             0: no AA video on the cluster (driver chose arrows / stock map)
// The first read after the libgal starts only notes the highest sequence in the file (left over
// from an earlier session) and executes nothing; the jar re-sends its video state per session.
// A repeat stops by itself after CLUSTER_REP_MAX_MS, in case the stop command is lost.
#ifndef CLUSTER_REP_MAX_MS
#define CLUSTER_REP_MAX_MS 6000
#endif
static u32 g_cmdChecked, g_cmdLast, g_cmdPrimed, g_repCode, g_repMs, g_repT, g_repStart;
static int cl_parse_int(const char*& p,i32* v){
  while(*p==' ') p++;
  int neg=0; if(*p=='-'){ neg=1; p++; }
  if(*p<'0'||*p>'9') return 0;
  i32 x=0; while(*p>='0'&&*p<='9'){ x=x*10+(*p-'0'); p++; }
  *v=neg?-x:x; return 1;
}
static int cl_word(const char*& p,const char* w){
  while(*p==' ') p++;
  const char* q=p; while(*w && *q==*w){ q++; w++; }
  if(*w || (*q && *q!=' ')) return 0;
  p=q; return 1;
}
// Content insets on the fly: 0x8009 UpdateUiConfigRequest {1: UiConfig{2: insets{1 top, 2 bottom,
// 3 left, 4 right}}} on the cluster video channel. From the phone's code (AA 18.1,
// the UI-config class): below protocol 4.3 a request WITH the theme (field 4) is a
// protocol error - that dropped the connection on 2026-11-05 - and the request is merged into the
// current UiConfig field by field, so all four insets go out, zeros included.
// Sources: /dev/shmem/aa_cluster_insets "top bottom left right" (RAM, gone after a reboot; sent
// when its text changes and again after every video Start) and the command "insets t b l r".
#define CLUSTER_LIVE_INSETS_FILE "/dev/shmem/aa_cluster_insets"
static u32 g_liveChk, g_liveHave, g_liveStartSeen;
static char g_liveTxt[48];
static void cl_send_live_insets(const u32* v){
  if(v[0]+v[1]>=g_geo.h || v[2]+v[3]>=g_geo.w){ g_cl.liveBad++; return; }
  u8 in[24], ui[32], m[40]; u8* r=in;
  for(u32 k=0;k<4;k++) r=cl_field_u(r,k+1,v[k]);
  u8* t=cl_field_bytes(ui,2,in,(u32)(r-in));
  u8* o=cl_field_bytes(m,1,ui,(u32)(t-ui));
  cl_send(0x8009,m,(u32)(o-m));
  g_cl.liveSent++; for(u32 k=0;k<4;k++) g_cl.live[k]=v[k];
}
static void cl_poll_live_insets(u32 now){
  if(g_liveChk && now-g_liveChk<500u) return;
  g_liveChk=now|1u;
  if(!g_clStarted || !ep_open(g_clusterEp) || !p_fopen || !p_fread || !p_fclose) return;
  u32 fresh = g_liveStartSeen!=g_cl.starts;            // a new Start: the phone is back to the SDR insets
  void* f=p_fopen(CLUSTER_LIVE_INSETS_FILE,"r");
  if(!f){ g_liveTxt[0]=0; g_liveStartSeen=g_cl.starts; return; }
  char b[48]; usize n=p_fread(b,1,sizeof b-1,f); p_fclose(f); b[n]=0;
  u32 same=1; for(u32 k=0;k<sizeof b;k++){ if(b[k]!=g_liveTxt[k]){ same=0; break; } if(!b[k]) break; }
  if(same && !fresh) return;
  for(u32 k=0;k<sizeof b;k++){ g_liveTxt[k]=b[k]; if(!b[k]) break; }
  g_liveStartSeen=g_cl.starts;
  u32 v[4], k=0; const char* c=b;
  while(k<4){
    while(*c==' '||*c=='\t'||*c==',') c++;
    if(*c<'0'||*c>'9') break;
    u32 x=0; while(*c>='0'&&*c<='9'&&x<10000u){ x=x*10u+(u32)(*c-'0'); c++; }
    v[k++]=x;
  }
  if(k==4) cl_send_live_insets(v); else g_cl.liveBad++;
}
static void cl_poll_commands(u32 now){
  if(g_repCode && now-g_repStart>=(u32)CLUSTER_REP_MAX_MS) g_repCode=0;
  if(g_repCode && g_repMs && now-g_repT>=g_repMs){ g_repT=now; cl_key(g_repCode); }
  if(g_cmdChecked && now-g_cmdChecked<100u) return;
  g_cmdChecked=now|1u;
  if(!p_fopen || !p_fread) return;
  void* f=p_fopen(CLUSTER_CMD_FILE,"r");
  if(!f){ g_cmdPrimed=1; return; }
  u32 prime = !g_cmdPrimed; g_cmdPrimed=1;
  char buf[512]; usize n=p_fread(buf,1,sizeof buf-1,f); p_fclose(f);
  buf[n]=0; g_cl.cmdReads++; g_cl.cmdBytes=(u32)n;
  const char* p=buf;
  while(*p){
    const char* line=p; while(*p && *p!='\n') p++;
    const char* e=p;
    // A line without its newline is one the jar is still writing (or the read was cut short):
    // leave it for the next poll. Taking its sequence now would lose the command for good.
    if(!*p) break;
    p++;
    char l[96]; u32 k=0; while(line<e && k<sizeof l-1) l[k++]=*line++; l[k]=0;
    const char* c=l; i32 seq,a=0,b=0;
    if(!cl_parse_int(c,&seq) || (u32)seq<=g_cmdLast) continue;
    g_cmdLast=(u32)seq; g_cl.cmdSeq=(u32)seq; g_cl.cmdLines++;
    if(prime) continue;
    if(cl_word(c,"key") && cl_parse_int(c,&a)) cl_key((u32)a);
    else if(cl_word(c,"rot") && cl_parse_int(c,&a) && cl_parse_int(c,&b)) cl_send_input((u32)a,1,b);
    else if(cl_word(c,"rep") && cl_parse_int(c,&a) && cl_parse_int(c,&b)){
      g_repCode=(u32)a; g_repMs=(u32)b; g_repT=now; g_repStart=now; if(a) cl_key((u32)a);
    }
    else if(cl_word(c,"video") && cl_parse_int(c,&a)) g_hmiVideo = a?1u:0u;
    else if(cl_word(c,"insets")){
      i32 t4[4]; u32 ok=1; for(u32 j=0;j<4;j++) if(!cl_parse_int(c,&t4[j]) || t4[j]<0) ok=0;
      if(ok && g_clStarted && ep_open(g_clusterEp)){ u32 v[4]; for(u32 j=0;j<4;j++) v[j]=(u32)t4[j]; cl_send_live_insets(v); }
    }
  }
}

// Heartbeat + key-frame requests, independent of incoming frames (a still map sends none).
#ifndef CLUSTER_SECOND_IDR_GAP_MS
#define CLUSTER_SECOND_IDR_GAP_MS 15000
#endif
#define CLUSTER_SECOND_IDR_FILE "/tsd/var/aatokombi/second-idr"
static u32 g_secondIdrChk, g_secondIdrFile;
static u32 cl_second_idr_on(){                      // the switch file, checked at most every 2 s
  u32 now=cl_now_ms();
  if(!g_secondIdrChk || now-g_secondIdrChk>=2000u){
    g_secondIdrChk=now|1u; g_secondIdrFile=0;
    if(p_fopen && p_fclose){ void* f=p_fopen(CLUSTER_SECOND_IDR_FILE,"r"); if(f){ p_fclose(f); g_secondIdrFile=1; } }
  }
  return g_secondIdrFile;
}
// One pass of the ticker while an AA session with the cluster channel exists.
static void cl_tick(u32 now){
  u32 alive = (g_clStarted || g_idrState) ? 1u : 0u;
  cl_release_acks(now);
  cl_state_file(now,alive);
  cl_poll_commands(now);
  cl_poll_live_insets(now);
  if(g_cl.setups && ep_open(g_clusterEp) && !g_idrState) cl_apply_focus(cl_want_video(now));
  struct kbcv* v=g_kv; if(!v) return;
  if(alive) v->alive_ms = now ? now : 1u;
  else if(v->alive_ms) v->alive_ms=0;
  // The first picture after the AID switches to the phone stays broken until the phone's NEXT key
  // frame (car 2026-11-03: garbage for ~30 s, the phone's IDR interval, or until a mode switch).
  // Car 2026-11-04: broken even when the switch happened on the phone's IDR, so the AID's decoder
  // seems to need a second key frame after the stock stream. Default now: libkb_dm.so hands that
  // first IDR to the encoder twice. Fallback, only while /tsd/var/aatokombi/second-idr exists:
  // CLUSTER_SECOND_IDR_MS after EVERY switch to the phone one restart for a fresh IDR (the stock
  // map shows for the ~3 s of it); at most one per CLUSTER_SECOND_IDR_GAP_MS, so the switch back
  // after its own restart does not start another.
  {
    u32 dmNow = (v->rd_alive_ms && now - v->rd_alive_ms < 2000u) ? 1u : 0u;
    if(CLUSTER_SECOND_IDR_MS && dmNow && !g_dmPrev && !g_secondIdrAt &&
       (!g_secondIdrDone || now-g_secondIdrDone>=(u32)CLUSTER_SECOND_IDR_GAP_MS) && cl_second_idr_on())
      g_secondIdrAt = (now + (u32)CLUSTER_SECOND_IDR_MS) | 1u;
    g_dmPrev = dmNow;
    if(g_secondIdrAt && (i32)(now - g_secondIdrAt) >= 0 && g_clStarted && g_focusOn && !g_idrState){
      g_secondIdrAt=0; g_secondIdrDone=now|1u; g_cl.secondIdr++;
      g_idrState=1; g_idrT=now; cl_send_focus_mode(2,1);
    }
  }
  if(CLUSTER_IDR_MODE==4 && g_idrState==1 && now-g_idrT>=(u32)CLUSTER_IDR_NATIVE_MS){
    g_idrState=0; cl_send_focus_mode(1,1);
  }
  if(CLUSTER_IDR_MODE==4 && v->want_idr){
    v->want_idr=0; if(!g_wantSince) g_wantSince=now|1u;
  }
  if(CLUSTER_IDR_MODE==4 && g_wantSince && now-g_wantSince>=(u32)CLUSTER_IDR_WAIT_MS && g_clStarted &&
     g_focusOn && !g_idrState && (!g_idrCycleT || now-g_idrCycleT>=(u32)CLUSTER_IDR_CYCLE_GAP_MS)){
    g_wantSince=0; g_idrCycleT=now|1u; g_cl.idrCycle++; v->idr_requests++;
    g_idrState=1; g_idrT=now; cl_send_focus_mode(2,1);        // restart: Start comes with an IDR
  }
  if((CLUSTER_IDR_MODE==2 || CLUSTER_IDR_MODE==3) && g_idrState==1 && now-g_idrT>=800u){
    g_idrState=0; cl_send_focus_mode(1,1);                  // give the cluster back to the phone
  }
  if(v->want_idr && g_clStarted && !g_idrState && now-g_idrLast>=CLUSTER_IDR_GAP_MS){
    v->want_idr=0; v->idr_requests++; g_idrLast=now;
    if(CLUSTER_IDR_MODE==2){ g_idrState=1; g_idrT=now; cl_send_focus_mode(2,1); }
    else if(CLUSTER_IDR_MODE==1){ cl_send_focus_mode(1,1); }
    else if(CLUSTER_IDR_MODE==3 && g_focusOn){
      if(g_idrSoftT && now-g_idrSoftT < 3u*CLUSTER_IDR_GAP_MS &&
         (!g_idrCycleT || now-g_idrCycleT >= (u32)CLUSTER_IDR_CYCLE_GAP_MS)){
        g_idrSoftT=0; g_idrCycleT=now|1u; g_cl.idrCycle++;
        g_idrState=1; g_idrT=now; cl_send_focus_mode(2,1);      // the soft request did not help
      } else {
        g_idrSoftT=now|1u; g_cl.idrSoft++; cl_send_focus_mode(1,1);
      }
    }
  }
}
// No AA session: nothing is sent. Frames still waiting for their acknowledgement belong to the
// session that ended; the phone counts anew in the next one.
static void cl_tick_idle(u32 now){
  g_pendHead=g_pendTail; g_ackPending=0; g_drainReq=0; g_repCode=0;
  cl_state_file(now,0);
  if(g_kv && g_kv->alive_ms) g_kv->alive_ms=0;
}
static void* cl_ticker(void*){
  for(;;){
    if(p_usleep) p_usleep(10000); else return 0;
    u32 now=cl_now_ms();
    g_tickBusy=1; __sync_synchronize();          // store busy, then load session (see cluster_video_off)
    u32 session=g_clSession;
    if(session) cl_tick(now); else cl_tick_idle(now);
    __sync_synchronize(); g_tickBusy=0;
    // No AA session: nothing to pace and nothing to poll - ten passes a second instead of a
    // hundred (the thread lives as long as the SAL). The first pass of a new session comes at
    // most 100 ms after cluster_register, long before the phone's Setup.
    if(!session) p_usleep(90000);
  }
  return 0;
}

static void cl_video_session_start(){
  if(!kv_map()) return;
  {
    struct kbcv* v=g_kv;
    v->width=g_geo.w-g_geo.crop[0]-g_geo.crop[1]; v->height=g_geo.h-g_geo.crop[2]-g_geo.crop[3];
    v->coded_w=g_geo.w; v->coded_h=g_geo.h; v->crop_x=g_geo.crop[0]; v->crop_y=g_geo.crop[2];
    __sync_synchronize();
    v->mode=g_geo.decode;
  }
  if(!g_tickerUp && p_pthread_create){
    u32 th=0; if(p_pthread_create(&th,0,cl_ticker,0)==0) g_tickerUp=1;
  }
}

// routeMessage slot (vptr+0x14): (this, channel, msgId, const shared_ptr<IoBuffer>&). 0 = handled.
int cluster_route(void* /*self*/,u32 /*channel*/,u32 msgId,const void* bufref){
  u32 id=msgId&0xffff;
  const u8* p=0; u32 n=0;
  {
    u32 mn; const u8* m=io_bytes(bufref,&mn);
    if(m && mn>2 && (mn-2)<0x100000){ p=m+2; n=mn-2; }
  }
  if(id==0x0000 || id==0x0001){
    // 0x0000 carries an 8-byte BE timestamp before the access unit; 0x0001 is codec config.
    const u8* au=p; u32 an=n;
    if(id==0x0000 && p && n>=8){
      g_cl.lastTsHi=((u32)p[0]<<24)|((u32)p[1]<<16)|((u32)p[2]<<8)|p[3];
      g_cl.lastTsLo=((u32)p[4]<<24)|((u32)p[5]<<16)|((u32)p[6]<<8)|p[7];
      au=p+8; an=n-8;
    }
    if(id==0x0001 && au) cl_store_config(au,an);
    g_pubSeq=0;
    if(id==0x0000 && au && g_kv) cl_publish(au,an);
    if(id==0x0001 || (g_cl.frames==0 && g_cl.cfgLen==0 && au && an<=sizeof g_cl.cfg)){
      if(id==0x0001) g_cl.cfgMsgs++;
      if(au && an<=sizeof g_cl.cfg){ for(u32 i=0;i<an;i++) g_cl.cfg[i]=au[i]; g_cl.cfgLen=an; }
    }
    if(id==0x0000){
      g_cl.frames++; g_cl.bytes+=an; if(an>g_cl.maxAu) g_cl.maxAu=an;
      if(au && cl_nal_idr(au,an<256?an:256)) g_cl.idr++;
      if(au && g_cl.prefixCount<4){
        u32 c=an<64?an:64; for(u32 i=0;i<c;i++) g_cl.prefix[g_cl.prefixCount][i]=au[i];
        g_cl.prefixCount++;
      }
    }
    if(id==0x0000) cl_ack_paced(an, g_kv ? g_pubSeq : 0u); else cl_ack();
    if(id==0x0001 || g_cl.frames<=4 || (g_cl.frames&63u)==0) cluster_flush();
    return 0;
  }
  if(id==0x8000){                                         // Setup {type}
    g_cl.setups++; g_cl.setupCodec=p?cl_get_u(p,n,1):0;
    u8 b[12]; u8* o=b; o=cl_field_u(o,1,2); o=cl_field_u(o,2,CLUSTER_MAX_UNACKED); o=cl_field_u(o,3,0);
    cl_send(0x8003,b,(u32)(o-b));
    // The ticker (focus on guidance start, state file, HMI commands) must run from Setup on: with
    // "map only during guidance" the phone gets NATIVE focus here and never sends Start, so a
    // ticker started at Start never ran and the map never came (car, 2026-10-30).
    cl_video_session_start();
    g_focusOn = cl_want_video(cl_now_ms());
    cl_send_focus_mode(g_focusOn?1u:2u,1);
  } else if(id==0x8001){                                  // Start {session_id, configuration_index}
    g_cl.starts++; g_cl.sessionId=p?cl_get_u(p,n,1):0;
    g_startIdrT=cl_now_ms()|1u;
    cl_video_session_start();
    if(g_kv){ g_kv->gen_first_seq = g_kv->write_seq + 1u; __sync_synchronize(); g_kv->generation++; }
    // The phone keeps unacknowledged frames counted across Stop/Start: return every credit.
    cl_ack_all();
    g_budget=(int)(CLUSTER_BURST_MS*(u32)(CLUSTER_KBPS/8)); g_budgetMs=cl_now_ms();
    g_clStarted=1;
  } else if(id==0x8002){
    g_cl.stops++;
    g_clStarted=0;
    cl_ack_all();
  } else if(id==0x8007){
    g_cl.focusReq++; cl_send_focus_mode(g_focusOn?1u:2u,0);
  } else {
    g_cl.other++; g_cl.lastOtherId=id;
  }
  cluster_flush();
  return 0;
}
// AA session over (GalReceiver::shutdown) or a new one: the cluster goes back to the stock map.
// Runs on the SAL's thread before MessageRouter::shutdown (gal_shutdown_hook) and before the
// endpoints are cleared (cluster_register). Store session, then load busy: either the ticker sees
// the session gone, or we see it busy and wait for that tick - at most 200 ms, never forever.
static void cluster_video_off(){
  g_clSession=0; __sync_synchronize();
  for(u32 i=0; i<200u && g_tickBusy && p_usleep; i++) p_usleep(1000);
  g_clStarted=0; g_idrState=0; if(g_kv) g_kv->alive_ms=0;
}

// addDiscoveryInfo slot: the service is added at byte level by sdr_queue_hook instead.
void cluster_discovery(void* /*self*/,void* /*sdr*/){}

// Replaces the single `bl MessageRouter::queueOutgoing` in Controller::sendServiceDiscoveryResponse.
// Same arguments; data = 2-byte message id (6) + serialized ServiceDiscoveryResponse.
extern "C" __attribute__((used)) int sdr_queue_hook(u32 router,u32 channel,const u8* data,u32 len){
  // Through the Bluetooth-skip wrapper (navshim_fs.cpp mr_queue_outgoing): this BL bypasses the
  // PLT/GOT the wrapper hooks, so the SDR would otherwise go out without SKIP_THIS_BLUETOOTH.
  qout_t q=(qout_t)(void*)&mr_queue_outgoing;
  g_cl.sdrSeen++;
  if(g_clusterId!=0xff && g_clusterInId!=0xff && data && len>=2){
    g_cl.sdrMainVideo=0; g_cl.sdrMainInput=0;
    u32 m=cl_rewrite_sdr(data,len,g_sdrBuf,sizeof g_sdrBuf);
    if(m && g_cl.sdrMainVideo==1){            // exactly one main video sink, else send stock bytes
      g_cl.sdrPatched++; g_cl.sdrLen=m;
      cluster_flush();
      return q(router,channel,g_sdrBuf,m);
    }
    g_cl.sdrError++;
  }
  cluster_flush();
  return q(router,channel,data,len);
}

// The cluster input service has no use yet; its endpoint only exists so that a channel-open for it
// lands on a registered service. Everything sent to it is counted and dropped.
// The cluster input channel: the phone's KeyBindingRequest (0x8002, the keys it wants) is
// answered with KeyBindingResponse 0x8003 {status 0}; input events go out from cl_send_input.
int cluster_input_route(void* /*self*/,u32 /*channel*/,u32 msgId,const void* bufref){
  u32 id=msgId&0xffff;
  g_cl.inMsgs++; g_cl.inLastId=id;
  if(id==0x8002){
    u32 mn; const u8* m=io_bytes(bufref,&mn);
    if(m && mn>2){ u32 n=mn-2; if(n>sizeof g_cl.bindReq) n=sizeof g_cl.bindReq;
      for(u32 i=0;i<n;i++) g_cl.bindReq[i]=m[2+i]; g_cl.bindReqLen=n; }
    u8 b[4]; u8* o=b; o=cl_field_u(o,1,0);
    cl_send_on(g_clusterInEp,0x8003,b,(u32)(o-b));
    g_cl.bindReplies++;
  }
  cluster_flush();
  return 0;
}
static u32 g_clusterInVT[VT_MAX] __attribute__((aligned(8)));

static void cluster_register(void* receiver,u32 router){
  cluster_video_off();
  g_clusterId=0xff; g_clusterInId=0xff;
  my_memset(g_clusterEp,0,sizeof g_clusterEp);
  my_memset(g_clusterInEp,0,sizeof g_clusterInEp);
  cluster_reset();
  g_dmPrev=0; g_secondIdrDone=0; g_secondIdrAt=0;
  if(!ref(R_QUEUE_OUT)) return;
  const u32* svt=(const u32*)ref(R_NAV_VTABLE);           // ProtocolEndpointBase slots 0..9
  u8 id=pick_free_id(router);
  if(id==0xff) return;
  vt_copy(g_clusterVT,svt); vt_copy(g_clusterInVT,svt);
  g_clusterVT[lay(L_VT_ROUTE)]=(u32)&cluster_route;
  g_clusterVT[lay(L_VT_DISCOVERY)]=(u32)&cluster_discovery;
  g_clusterInVT[lay(L_VT_ROUTE)]=(u32)&cluster_input_route;
  g_clusterInVT[lay(L_VT_DISCOVERY)]=(u32)&cluster_discovery;
  ep_init(g_clusterEp,(u32)g_clusterVT+8,router,id);
  ((reg_t)ref(R_REGISTER))(receiver,g_clusterEp);
  u8 iid=pick_free_id(router);                            // after the video sink took its slot
  if(iid==0xff || iid==id) return;                        // no pair -> discovery stays stock
  ep_init(g_clusterInEp,(u32)g_clusterInVT+8,router,iid);
  ((reg_t)ref(R_REGISTER))(receiver,g_clusterInEp);
  g_clusterId=id; g_clusterInId=iid;
  cluster_flush();
  __sync_synchronize(); g_clSession=1;                    // both endpoints are registered
}

#endif // CLUSTER_SINK
