// Bounded Android Auto video ingress.
//
// This file deliberately stops at the ownership boundary between VideoSink and a future
// decoder/output worker.  It does not call the decoder from the GAL callback and it does not
// allocate or write files there.
//
// Callback ABI (VideoSink::handleDataAvailable(this, u64 ts, const shared_ptr<IoBuffer>&, u32 hdr),
// ARM EABI, confirmed on the 2026-10-26 forensic package):
//   r0 = this, r1 = padding (u64 must start in an even register), r2:r3 = timestamp (lo:hi, usec)
//   [sp+0] = &shared_ptr<IoBuffer>      -> IoBuffer* = [[sp+0] + 4]
//   [sp+4] = hdr = 10                    (2-byte AAP media msg id + 8-byte BE timestamp)
// IoBuffer words: [0]=data, [1]=capacity, [2]=start, [3]=end.
//   message      = data + start .. data + end
//   access unit  = data + start + hdr, length = end - start - hdr
// The forensic bytes at data+start+2 are 00 00 00 01 0c 50 13 2e = the BE timestamp
// 0x1_0c50132e that also arrived in r3:r2; the first real NAL (00 00 00 01 21, non-IDR slice)
// starts at +hdr.  An earlier reading of those 8 bytes as "filler NAL 12" was wrong.

// VIDEO_PIPELINE is opt-in at build time.  Keeping this in a small freestanding header makes the
// normal AAtoKombi build byte-for-byte unchanged unless the explicit pipeline flag is requested.

#ifndef VIDEO_PIPELINE
#define VIDEO_PIPELINE 0
#endif

#if VIDEO_PIPELINE

enum {
  VP_MAX_AU = 65536,
  VP_QUEUE_DEPTH = 3,
  VP_CONFIG_MAX = 512,
  VP_FLAG_IDR = 1u,
  VP_FLAG_CONFIG = 2u,
  VP_STAGE_STOPPED = 0u,
  VP_STAGE_WAIT_IDR = 1u,
  VP_STAGE_RUNNING = 2u,
  VP_STAGE_ERROR = 3u,
};

struct VideoPipelinePacket {
  u32 length;
  u32 sequence;
  u32 flags;
  u8 bytes[VP_MAX_AU];
};

struct VideoPipelineState {
  VideoPipelinePacket queue[VP_QUEUE_DEPTH];
  u8 config[VP_CONFIG_MAX];
  u32 config_len;
  u32 head;
  u32 tail;
  u32 queued;
  u32 sequence;
  u32 accepted;
  u32 dropped;
  u32 malformed;
  u32 stage;
};

static VideoPipelineState g_videoPipeline;

static int vp_valid_ptr(u32 p){ return p>=0x1000u && p<0x20000000u; }

static void vp_clear_queue(){
  g_videoPipeline.head=0; g_videoPipeline.tail=0; g_videoPipeline.queued=0;
}

static int vp_start_code(const volatile u8* p,u32 n,u32 i,u32* prefix){
  if(i+3u<=n && p[i]==0 && p[i+1]==0 && p[i+2]==1){ *prefix=3; return 1; }
  if(i+4u<=n && p[i]==0 && p[i+1]==0 && p[i+2]==0 && p[i+3]==1){ *prefix=4; return 1; }
  return 0;
}

static u32 vp_flags(const volatile u8* p,u32 n){
  u32 flags=0, i=0;
  while(i+3u<n){
    u32 prefix=0;
    if(!vp_start_code(p,n,i,&prefix)){ ++i; continue; }
    u32 nal=i+prefix;
    if(nal<n){
      u32 type=p[nal]&0x1fu;
      if(type==5u) flags|=VP_FLAG_IDR;
      if(type==7u || type==8u) flags|=VP_FLAG_CONFIG;
    }
    i=nal+1u;
  }
  return flags;
}

static void video_pipeline_reset(){
  vp_clear_queue();
  g_videoPipeline.stage=VP_STAGE_STOPPED;
  g_videoPipeline.config_len=0;
}

static void video_pipeline_on_config(const u8* src,u32 len){
  if(!src || !len || len>VP_CONFIG_MAX){ g_videoPipeline.stage=VP_STAGE_ERROR; return; }
  for(u32 i=0;i<len;i++) g_videoPipeline.config[i]=src[i];
  g_videoPipeline.config_len=len;
  vp_clear_queue();
  g_videoPipeline.stage=VP_STAGE_WAIT_IDR;
}

// Extract one access unit from the exact callback stack/descriptor contract.  The caller must not
// retain `src`; it points into the GAL-owned shared buffer and is copied immediately below.
static int video_pipeline_extract(u32 orig_sp,const volatile u8** src,u32* len){
  if(!orig_sp || !vp_valid_ptr(orig_sp)) return 0;
  volatile const u32* stack=(volatile const u32*)orig_sp;
  u32 sp_ref=stack[0], hdr=stack[1];
  if(!vp_valid_ptr(sp_ref)) return 0;
  u32 iobuf=((volatile const u32*)sp_ref)[1];     // shared_ptr's IoBuffer* (target prologue: [r5+4])
  if(!vp_valid_ptr(iobuf)) return 0;
  volatile const u32* io=(volatile const u32*)iobuf;
  u32 base=io[0], start=io[2], end=io[3];
  if(!vp_valid_ptr(base) || end<start || hdr>0x40u || end-start<=hdr) return 0;
  u32 n=end-start-hdr;                            // oversize is counted as a drop by the caller
  u32 p=base+start+hdr;
  if(p<base || !vp_valid_ptr(p) || p+n<p || p+n>0x20000000u) return 0;
  *src=(volatile const u8*)p; *len=n; return 1;
}

static void video_pipeline_on_data(u32 orig_sp){
  const volatile u8* src=0; u32 len=0;
  if(!video_pipeline_extract(orig_sp,&src,&len)){ g_videoPipeline.malformed++; return; }
  // Parse the NAL headers in-place first; only an accepted unit is copied into the queue.  Do not
  // put a 64 KiB temporary on the GAL callback stack.
  u32 flags=vp_flags(src,len);
  if(g_videoPipeline.stage==VP_STAGE_WAIT_IDR && !(flags&VP_FLAG_IDR)){
    g_videoPipeline.dropped++; return;
  }
  if(len>VP_MAX_AU){ g_videoPipeline.dropped++; return; }
  if(g_videoPipeline.queued>=VP_QUEUE_DEPTH){
    // Keep the newest access unit; dropping the oldest is preferable to stalling GAL/ACK.
    g_videoPipeline.tail=(g_videoPipeline.tail+1u)%VP_QUEUE_DEPTH;
    g_videoPipeline.queued--; g_videoPipeline.dropped++;
  }
  VideoPipelinePacket* q=&g_videoPipeline.queue[g_videoPipeline.head];
  q->length=len; q->sequence=++g_videoPipeline.sequence; q->flags=flags;
  for(u32 i=0;i<len;i++) q->bytes[i]=src[i];
  g_videoPipeline.head=(g_videoPipeline.head+1u)%VP_QUEUE_DEPTH;
  g_videoPipeline.queued++; g_videoPipeline.accepted++;
  g_videoPipeline.stage=VP_STAGE_RUNNING;
}

static void video_pipeline_on_disconnect(){ video_pipeline_reset(); }

#endif // VIDEO_PIPELINE
