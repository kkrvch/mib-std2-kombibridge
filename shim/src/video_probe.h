// video_probe.h - capture-only probe of the MAIN display's VideoSink callbacks (reverse-engineering
// aid; part of navshim_fs.cpp, included once from it).
//
// Compiled into every blob but inert: inject.py redirects the two VideoSink entry points here only
// when called with --video-probe (VIDEO_PROBE=1), after checking their prologues. Nothing of the
// navigation, media or cluster-sink paths depends on it.
#ifndef NAVSHIM_VIDEO_PROBE_H
#define NAVSHIM_VIDEO_PROBE_H

// ===== capture-only VideoSink probe ============================================================
// The probe is inert in the normal build: inject.py changes the target function entries only when
// invoked with --video-probe. When enabled, the trampolines below replay the target prologue and
// jump to the original function at +16 after recording bounded callback metadata.
#ifndef VIDEO_PROBE_DEEP
#define VIDEO_PROBE_DEEP 0
#endif
static const char VIDEO_PROBE_PATH[] = "/dev/shmem/aa_video_probe";
static const char VIDEO_PROBE_SAMPLES_PATH[] = "/dev/shmem/aa_video_probe_samples";
static const char VIDEO_PROBE_PAYLOAD_PATH[] = "/dev/shmem/aa_video_probe_payload";
static const char VIDEO_PROBE_CONFIG_PATH[] = "/dev/shmem/aa_video_probe_config";
static const char VIDEO_PROBE_FORENSIC_PATH[] = "/dev/shmem/aa_video_probe_forensic.bin";
static const char VIDEO_PROBE_CANDIDATES_PATH[] = "/dev/shmem/aa_video_probe_candidates.bin";
static u32 g_videoDataCalls;
static u32 g_videoConfigCalls;
static u32 g_videoLastDataThis;
static u32 g_videoLastConfigThis;
static u32 g_videoLastCfgA1;
static u32 g_videoLastCfgA2;
static u32 g_videoLastCfgA3;
static u8 g_videoPayload[512];
static u32 g_videoPayloadLen;
static u8 g_videoConfig[512];
static u32 g_videoConfigLen;
static u32 g_videoLastA1;
static u32 g_videoLastA2;
static u32 g_videoLastA3;
static u32 g_videoLastR3;
static u32 g_videoLastStack0;
static u32 g_videoLastStack1;
static u32 g_videoLastStack2;
static u32 g_videoLastStack3;
static u32 g_videoLastStack4;
static u32 g_videoLastSelf18;
static u32 g_videoLastSelf34;
static u32 g_videoLastVptr;
static u32 g_videoLastStack0w0;
static u32 g_videoLastStack0w1;
static u32 g_videoLastStack0w2;
static u32 g_videoLastStack0w3;
static u32 g_videoLastBufObj;
static u32 g_videoLastBufW0;
static u32 g_videoLastBufW1;
static u32 g_videoLastBufW2;
static u32 g_videoLastBufW3;
static u32 g_videoLastBufW4;
static u32 g_videoLastBufW5;
static u32 g_videoLastBufW6;
static u32 g_videoLastBufW7;
static u32 g_videoLastBufW8;
static u32 g_videoLastBufW9;
static u32 g_videoLastBufW10;
static u32 g_videoLastBufW11;
static u32 g_videoLastIoPtr;
static u32 g_videoLastIo0;
static u32 g_videoLastIo2;
static u32 g_videoLastIo3;
static u32 g_videoLastPayloadBase;
static u32 g_videoLastPayloadSize;
static u32 g_videoLastDescW0;
static u32 g_videoLastDescW1;
static u32 g_videoLastDescW2;
static u32 g_videoLastDescW3;
static u32 g_videoLastDescW4;
static u32 g_videoLastDescW5;
static u32 g_videoLastDescW6;
static u32 g_videoLastDescW7;
static u32 g_videoLastCalcData;
static u32 g_videoLastCalcLen;

struct VideoSample {
  u32 n,sec,msec,r1,r2,r3,s0,s1,s2,s3,s4,self18,self34,vptr;
  u32 s0w0,s0w1,s0w2,s0w3,io0,io2,io3,pbase,plen,calcData,calcLen,desc0,desc1,desc2,desc3,desc4,desc5,desc6,desc7,bufobj;
  u32 b0,b1,b2,b3,b4,b5,b6,b7,b8,b9,b10,b11;
};
static VideoSample g_videoSamples[8];
static u32 g_videoSamplePos;
static u32 g_videoSampleCount;

// Binary forensic snapshot. It deliberately preserves raw words instead of applying a guessed
// IoBuffer layout, so one runtime session can be analyzed offline after the unit is back on the
// bench. The ring keeps eight sampled callbacks (every 32 data callbacks).
struct VideoForensic {
  u32 n,self,a1,a2,r3,orig_sp,stack0,stack1,stack2,stack3,stack4;
  u32 self_words[32];
  u32 stack_words[32];
  u32 stack0w0_words[32];
  u32 stack0w1_words[32];
  u32 descriptor_words[16];
};
struct VideoForensicFile {
  u32 magic,version,count;
  VideoForensic samples[8];
};
static VideoForensicFile g_videoForensic;

struct VideoCandidate {
  u32 index,base,off,end,len,copied;
  u8 bytes[512];
};
struct VideoCandidateSample {
  u32 n,count;
  VideoCandidate candidates[8];
};
struct VideoCandidateFile {
  u32 magic,version,count;
  VideoCandidateSample samples[8];
};
static VideoCandidateFile g_videoCandidates;

static int probe_ptr(u32 p){ return p>=0x1000u && p<0x20000000u; }
static void probe_copy_words(u32* dst,u32 addr,u32 count){
  if(!probe_ptr(addr)){ for(u32 i=0;i<count;i++) dst[i]=0; return; }
  volatile const u32* p=(volatile const u32*)addr;
  for(u32 i=0;i<count;i++) dst[i]=p[i];
}
static void probe_collect_candidates(VideoCandidateSample* out,const u32* d,u32 n){
  out->count=0;
  // Scan every possible 4-word base/offset/end grouping. This intentionally does not assume
  // whether the descriptor is IoBuffer, a vector, or a nested shared object.
  for(u32 i=0;i+3<n && out->count<8;i++){
    u32 base=d[i], off=d[i+2], end=d[i+3];
    if(!probe_ptr(base) || off>0x20000u || end<=off+2u || end-off-2u>0x20000u) continue;
    u32 pb=base+off+2u, len=end-off-2u;
    if(pb<base || pb>=0x20000000u || pb+len>0x20000000u) continue;
    int duplicate=0; for(u32 k=0;k<out->count;k++) if(out->candidates[k].base==base && out->candidates[k].off==off && out->candidates[k].end==end) duplicate=1;
    if(duplicate) continue;
    VideoCandidate* c=&out->candidates[out->count++]; c->index=i; c->base=base; c->off=off; c->end=end; c->len=len;
    c->copied=len; if(c->copied>sizeof c->bytes) c->copied=sizeof c->bytes;
    const volatile u8* src=(const volatile u8*)pb;
    for(u32 j=0;j<c->copied;j++) c->bytes[j]=src[j];
    for(u32 j=c->copied;j<sizeof c->bytes;j++) c->bytes[j]=0;
  }
}

static void probe_hex(char*& o,u32 v){
  static const char h[]="0123456789abcdef";
  for(int i=0;i<8;i++) *o++=h[(v>>(28-4*i))&0xf];
}
static void video_probe_flush(){
  if(!p_fopen || !p_fwrite || !p_fclose) return;
  char b[1024]; char* o=b;
  static const char a[]="data="; static const char c[]=" config=";
  static const char d[]=" data_this=0x"; static const char e[]=" config_this=0x";
  static const char ca1[]=" cfg1=0x"; static const char ca2[]=" cfg2=0x"; static const char ca3[]=" cfg3=0x";
  static const char pl[]=" payload_len="; static const char cl[]=" config_len=";
  static const char f[]=" a1=0x"; static const char g[]=" a2=0x"; static const char h[]=" a3=0x"; static const char r[]=" r3=0x";
  static const char k0[]=" stack0=0x"; static const char k1[]=" stack1=0x";
  static const char k2[]=" stack2=0x"; static const char k3[]=" stack3=0x"; static const char k4[]=" stack4=0x";
  static const char s18[]=" self18=0x"; static const char s34[]=" self34=0x"; static const char vp[]=" vptr=0x";
  static const char w0[]=" stack0w0=0x"; static const char w1[]=" stack0w1=0x";
  static const char w2[]=" stack0w2=0x"; static const char w3[]=" stack0w3=0x";
  static const char bo[]=" bufobj=0x"; static const char b0[]=" buf0=0x"; static const char b1[]=" buf1=0x";
  static const char b2[]=" buf2=0x"; static const char b3[]=" buf3=0x"; static const char b4[]=" buf4=0x"; static const char b5[]=" buf5=0x";
  static const char ip[]=" io=0x"; static const char i0[]=" io0=0x"; static const char i2[]=" io2=0x"; static const char i3[]=" io3=0x"; static const char pb[]=" pbase=0x";
  static const char cd[]=" calcdata=0x"; static const char cln[]=" calclen=0x";
  static const char d0[]=" desc0=0x"; static const char d1[]=" desc1=0x"; static const char d2[]=" desc2=0x"; static const char d3[]=" desc3=0x";
  for(usize i=0;i<sizeof(a)-1;i++) *o++=a[i]; o=u2dec(o,g_videoDataCalls);
  for(usize i=0;i<sizeof(c)-1;i++) *o++=c[i]; o=u2dec(o,g_videoConfigCalls);
  for(usize i=0;i<sizeof(d)-1;i++) *o++=d[i]; probe_hex(o,g_videoLastDataThis);
  for(usize i=0;i<sizeof(e)-1;i++) *o++=e[i]; probe_hex(o,g_videoLastConfigThis);
  for(usize i=0;i<sizeof(ca1)-1;i++) *o++=ca1[i]; probe_hex(o,g_videoLastCfgA1);
  for(usize i=0;i<sizeof(ca2)-1;i++) *o++=ca2[i]; probe_hex(o,g_videoLastCfgA2);
  for(usize i=0;i<sizeof(ca3)-1;i++) *o++=ca3[i]; probe_hex(o,g_videoLastCfgA3);
  for(usize i=0;i<sizeof(pl)-1;i++) *o++=pl[i]; o=u2dec(o,g_videoPayloadLen);
  for(usize i=0;i<sizeof(cl)-1;i++) *o++=cl[i]; o=u2dec(o,g_videoConfigLen);
  for(usize i=0;i<sizeof(f)-1;i++) *o++=f[i]; probe_hex(o,g_videoLastA1);
  for(usize i=0;i<sizeof(g)-1;i++) *o++=g[i]; probe_hex(o,g_videoLastA2);
  for(usize i=0;i<sizeof(h)-1;i++) *o++=h[i]; probe_hex(o,g_videoLastA3);
  for(usize i=0;i<sizeof(r)-1;i++) *o++=r[i]; probe_hex(o,g_videoLastR3);
  for(usize i=0;i<sizeof(k0)-1;i++) *o++=k0[i]; probe_hex(o,g_videoLastStack0);
  for(usize i=0;i<sizeof(k1)-1;i++) *o++=k1[i]; probe_hex(o,g_videoLastStack1);
  for(usize i=0;i<sizeof(k2)-1;i++) *o++=k2[i]; probe_hex(o,g_videoLastStack2);
  for(usize i=0;i<sizeof(k3)-1;i++) *o++=k3[i]; probe_hex(o,g_videoLastStack3);
  for(usize i=0;i<sizeof(k4)-1;i++) *o++=k4[i]; probe_hex(o,g_videoLastStack4);
  for(usize i=0;i<sizeof(s18)-1;i++) *o++=s18[i]; probe_hex(o,g_videoLastSelf18);
  for(usize i=0;i<sizeof(s34)-1;i++) *o++=s34[i]; probe_hex(o,g_videoLastSelf34);
  for(usize i=0;i<sizeof(vp)-1;i++) *o++=vp[i]; probe_hex(o,g_videoLastVptr);
  for(usize i=0;i<sizeof(w0)-1;i++) *o++=w0[i]; probe_hex(o,g_videoLastStack0w0);
  for(usize i=0;i<sizeof(w1)-1;i++) *o++=w1[i]; probe_hex(o,g_videoLastStack0w1);
  for(usize i=0;i<sizeof(w2)-1;i++) *o++=w2[i]; probe_hex(o,g_videoLastStack0w2);
  for(usize i=0;i<sizeof(w3)-1;i++) *o++=w3[i]; probe_hex(o,g_videoLastStack0w3);
  for(usize i=0;i<sizeof(bo)-1;i++) *o++=bo[i]; probe_hex(o,g_videoLastBufObj);
  for(usize i=0;i<sizeof(b0)-1;i++) *o++=b0[i]; probe_hex(o,g_videoLastBufW0);
  for(usize i=0;i<sizeof(b1)-1;i++) *o++=b1[i]; probe_hex(o,g_videoLastBufW1);
  for(usize i=0;i<sizeof(b2)-1;i++) *o++=b2[i]; probe_hex(o,g_videoLastBufW2);
  for(usize i=0;i<sizeof(b3)-1;i++) *o++=b3[i]; probe_hex(o,g_videoLastBufW3);
  for(usize i=0;i<sizeof(b4)-1;i++) *o++=b4[i]; probe_hex(o,g_videoLastBufW4);
  for(usize i=0;i<sizeof(b5)-1;i++) *o++=b5[i]; probe_hex(o,g_videoLastBufW5);
  // Append the callback's actual IoBuffer descriptor chain after the compact object summary.
  for(usize i=0;i<sizeof(ip)-1;i++) *o++=ip[i]; probe_hex(o,g_videoLastIoPtr);
  for(usize i=0;i<sizeof(i0)-1;i++) *o++=i0[i]; probe_hex(o,g_videoLastIo0);
  for(usize i=0;i<sizeof(i2)-1;i++) *o++=i2[i]; probe_hex(o,g_videoLastIo2);
  for(usize i=0;i<sizeof(i3)-1;i++) *o++=i3[i]; probe_hex(o,g_videoLastIo3);
  for(usize i=0;i<sizeof(pb)-1;i++) *o++=pb[i]; probe_hex(o,g_videoLastPayloadBase);
  for(usize i=0;i<sizeof(cd)-1;i++) *o++=cd[i]; probe_hex(o,g_videoLastCalcData);
  for(usize i=0;i<sizeof(cln)-1;i++) *o++=cln[i]; probe_hex(o,g_videoLastCalcLen);
  for(usize i=0;i<sizeof(d0)-1;i++) *o++=d0[i]; probe_hex(o,g_videoLastDescW0);
  for(usize i=0;i<sizeof(d1)-1;i++) *o++=d1[i]; probe_hex(o,g_videoLastDescW1);
  for(usize i=0;i<sizeof(d2)-1;i++) *o++=d2[i]; probe_hex(o,g_videoLastDescW2);
  for(usize i=0;i<sizeof(d3)-1;i++) *o++=d3[i]; probe_hex(o,g_videoLastDescW3); *o++='\n';
  write_file(VIDEO_PROBE_PATH,b,(usize)(o-b));

  char s[8192]; char* q=s;
  static const char hdr[]="n sec msec r1 r2 r3 stack0 stack1 stack2 stack3 stack4 self18 self34 vptr s0w0 s0w1 s0w2 s0w3 io0 io2 io3 pbase plen calcdata calclen desc0 desc1 desc2 desc3 desc4 desc5 desc6 desc7 bufobj b0 b1 b2 b3 b4 b5 b6 b7 b8 b9 b10 b11\n";
  for(usize i=0;i<sizeof(hdr)-1;i++) *q++=hdr[i];
  u32 first=(g_videoSampleCount>8u)?(g_videoSamplePos&7u):0u;
  u32 count=(g_videoSampleCount>8u)?8u:g_videoSampleCount;
  for(u32 k=0;k<count;k++){
    VideoSample* z=&g_videoSamples[(first+k)&7u];
    u32 vals[46]={z->n,z->sec,z->msec,z->r1,z->r2,z->r3,z->s0,z->s1,z->s2,z->s3,z->s4,z->self18,z->self34,z->vptr,
      z->s0w0,z->s0w1,z->s0w2,z->s0w3,z->io0,z->io2,z->io3,z->pbase,z->plen,z->calcData,z->calcLen,z->desc0,z->desc1,z->desc2,z->desc3,z->desc4,z->desc5,z->desc6,z->desc7,z->bufobj,z->b0,z->b1,z->b2,z->b3,z->b4,z->b5,z->b6,z->b7,z->b8,z->b9,z->b10,z->b11};
    for(int j=0;j<46;j++){ if(j) *q++=' '; probe_hex(q,vals[j]); } *q++='\n';
  }
  write_file(VIDEO_PROBE_SAMPLES_PATH,s,(usize)(q-s));
  write_file(VIDEO_PROBE_PAYLOAD_PATH,(const char*)g_videoPayload,g_videoPayloadLen);
  write_file(VIDEO_PROBE_CONFIG_PATH,(const char*)g_videoConfig,g_videoConfigLen);
#if VIDEO_PROBE_DEEP
  write_file(VIDEO_PROBE_FORENSIC_PATH,(const char*)&g_videoForensic,sizeof g_videoForensic);
  write_file(VIDEO_PROBE_CANDIDATES_PATH,(const char*)&g_videoCandidates,sizeof g_videoCandidates);
#endif
}
extern "C" __attribute__((used)) void video_probe_data(u32 self,u32 a1,u32 a2,u32 orig_r3,u32 orig_sp){
  g_videoDataCalls++; g_videoLastDataThis=self; g_videoLastA1=a1; g_videoLastA2=a2;
  g_videoLastR3=orig_r3;
  g_videoLastA3=orig_sp ? ((u32*)orig_sp)[2] : 0;
  g_videoLastStack0=orig_sp ? ((u32*)orig_sp)[0] : 0;
  g_videoLastStack1=orig_sp ? ((u32*)orig_sp)[1] : 0;
  g_videoLastStack2=orig_sp ? ((u32*)orig_sp)[2] : 0;
  g_videoLastStack3=orig_sp ? ((u32*)orig_sp)[3] : 0;
  g_videoLastStack4=orig_sp ? ((u32*)orig_sp)[4] : 0;
  g_videoLastSelf18=self ? *(volatile u32*)(self+0x18) : 0;
  g_videoLastSelf34=self ? *(volatile u32*)(self+0x34) : 0;
  g_videoLastVptr=self ? *(volatile u32*)self : 0;
  g_videoLastStack0w0=0; g_videoLastStack0w1=0; g_videoLastStack0w2=0; g_videoLastStack0w3=0;
  g_videoLastBufObj=0; g_videoLastBufW0=0; g_videoLastBufW1=0; g_videoLastBufW2=0;
  g_videoLastBufW3=0; g_videoLastBufW4=0; g_videoLastBufW5=0;
  g_videoLastBufW6=0; g_videoLastBufW7=0; g_videoLastBufW8=0; g_videoLastBufW9=0;
  g_videoLastBufW10=0; g_videoLastBufW11=0;
  // Target prologue: r5 = original stack0, then r12 = [r5 + 4] = stack0w1 (the IoBuffer*). It is
  // assigned below, after stack0w1 has actually been read: assigning it here (when stack0w1 had just
  // been zeroed) is why every io/desc field and the payload stayed empty in the 2026-10-25/26 runs.
  g_videoLastIoPtr=0; g_videoLastIo0=0; g_videoLastIo2=0; g_videoLastIo3=0;
  g_videoLastPayloadBase=0; g_videoLastPayloadSize=0; g_videoPayloadLen=0;
  g_videoLastDescW0=0; g_videoLastDescW1=0; g_videoLastDescW2=0; g_videoLastDescW3=0;
  g_videoLastDescW4=0; g_videoLastDescW5=0; g_videoLastDescW6=0; g_videoLastDescW7=0;
  g_videoLastCalcData=0; g_videoLastCalcLen=0;
  g_videoForensic.magic=0x56465231u; // "VFR1"
  g_videoForensic.version=1; g_videoForensic.count=0;
  g_videoCandidates.magic=0x56434131u; // "VCA1"
  g_videoCandidates.version=1; g_videoCandidates.count=0;
  // These words are read by the original callback itself. Restrict the extra reads to the
  // observed user-space range and sample once per 32 callbacks to keep the hook lightweight.
  if((g_videoDataCalls & 31u)==1u && g_videoLastStack0>=0x1000u && g_videoLastStack0<0x20000000u){
    volatile u32* sw=(volatile u32*)g_videoLastStack0;
    g_videoLastStack0w0=sw[0]; g_videoLastStack0w1=sw[1];
    g_videoLastStack0w2=sw[2]; g_videoLastStack0w3=sw[3];
    g_videoLastIoPtr=g_videoLastStack0w1;
    u32 p=g_videoLastStack0w0;
    if(p>=0x1000u && p<0x20000000u){
      volatile u32* pw=(volatile u32*)p;
      g_videoLastBufObj=pw[2];
      p=g_videoLastBufObj;
      if(p>=0x1000u && p<0x20000000u){
        volatile u32* bw=(volatile u32*)p;
        g_videoLastBufW0=bw[0]; g_videoLastBufW1=bw[1]; g_videoLastBufW2=bw[2];
        g_videoLastBufW3=bw[3]; g_videoLastBufW4=bw[4]; g_videoLastBufW5=bw[5];
        g_videoLastBufW6=bw[6]; g_videoLastBufW7=bw[7]; g_videoLastBufW8=bw[8];
        g_videoLastBufW9=bw[9]; g_videoLastBufW10=bw[10]; g_videoLastBufW11=bw[11];
        u32 base=g_videoLastBufW2, end=g_videoLastBufW3;
        if(base>=0x1000u && end>base && end<0x20000000u && (end-base)<=0x2000u){
          u32 n=end-base; if(n>sizeof g_videoPayload) n=sizeof g_videoPayload;
          const volatile u8* src=(const volatile u8*)base;
          for(u32 i=0;i<n;i++) g_videoPayload[i]=src[i];
          g_videoPayloadLen=n;
        }
      }
    }
  }
  // The target prologue uses r12=[stack0+4] and then reads io[0], io[2], io[3]. payload =
  // io[0]+io[2]+hdr, length = io[3]-io[2]-hdr (hdr = stack1). Sampled callbacks only, copy capped.
  g_videoPayloadLen=0;
  if((g_videoDataCalls & 31u)==1u && g_videoLastIoPtr>=0x1000u && g_videoLastIoPtr<0x20000000u){
    volatile u32* io=(volatile u32*)g_videoLastIoPtr;
    g_videoLastDescW0=io[0]; g_videoLastDescW1=io[1]; g_videoLastDescW2=io[2]; g_videoLastDescW3=io[3];
    g_videoLastDescW4=io[4]; g_videoLastDescW5=io[5]; g_videoLastDescW6=io[6]; g_videoLastDescW7=io[7];
    g_videoLastIo0=g_videoLastDescW0; g_videoLastIo2=g_videoLastDescW2; g_videoLastIo3=g_videoLastDescW3;
    g_videoLastCalcData=g_videoLastIo0+g_videoLastIo2+g_videoLastStack1;
    g_videoLastCalcLen=g_videoLastIo3-g_videoLastIo2-g_videoLastStack1;
    // IoBuffer {data, capacity, start, end}; the access unit follows the hdr=stack1 (=10) bytes of
    // AAP media header (msg id + BE timestamp). The cap only bounds the copy, not the AU size.
    u32 base=g_videoLastIo0, off=g_videoLastIo2, end=g_videoLastIo3, hdr=g_videoLastStack1;
    if(base>=0x1000u && base<0x20000000u && hdr<=0x40u && end>off+hdr && end-off-hdr<=0x40000u){
      u32 pb=base+off+hdr; u32 n=end-off-hdr;
      if(pb>=base && pb<0x20000000u && pb+n<=0x20000000u){
        g_videoLastPayloadBase=pb; g_videoLastPayloadSize=n;
        if(n>sizeof g_videoPayload) n=sizeof g_videoPayload;
        const volatile u8* src=(const volatile u8*)pb;
        for(u32 i=0;i<n;i++) g_videoPayload[i]=src[i]; g_videoPayloadLen=n;
      }
    }
  }
  if((g_videoDataCalls & 31u)==1u){
#if VIDEO_PROBE_DEEP
    VideoForensic* f=&g_videoForensic.samples[g_videoForensic.count & 7u];
    f->n=g_videoDataCalls; f->self=self; f->a1=a1; f->a2=a2; f->r3=orig_r3; f->orig_sp=orig_sp;
    f->stack0=g_videoLastStack0; f->stack1=g_videoLastStack1; f->stack2=g_videoLastStack2;
    f->stack3=g_videoLastStack3; f->stack4=g_videoLastStack4;
    probe_copy_words(f->stack_words,orig_sp,32);
    probe_copy_words(f->self_words,self,32);
    probe_copy_words(f->stack0w0_words,g_videoLastStack0w0,32);
    probe_copy_words(f->stack0w1_words,g_videoLastStack0w1,32);
    probe_copy_words(f->descriptor_words,g_videoLastIoPtr,16);
    g_videoForensic.count++;
    VideoCandidateSample* cs=&g_videoCandidates.samples[g_videoCandidates.count & 7u];
    cs->n=g_videoDataCalls; probe_collect_candidates(cs,f->stack0w1_words,32); g_videoCandidates.count++;
#endif
    VideoSample* z=&g_videoSamples[g_videoSamplePos & 7u];
    u32 ts[2]={0,0}; if(p_clock_gettime) (void)p_clock_gettime(0,ts);
    z->n=g_videoDataCalls; z->sec=ts[0]; z->msec=ts[1]/1000000u; z->r1=a1; z->r2=a2; z->r3=orig_r3;
    z->s0=g_videoLastStack0; z->s1=g_videoLastStack1; z->s2=g_videoLastStack2; z->s3=g_videoLastStack3; z->s4=g_videoLastStack4;
    z->self18=g_videoLastSelf18; z->self34=g_videoLastSelf34; z->vptr=g_videoLastVptr;
    z->s0w0=g_videoLastStack0w0; z->s0w1=g_videoLastStack0w1; z->s0w2=g_videoLastStack0w2; z->s0w3=g_videoLastStack0w3;
    z->io0=g_videoLastIo0; z->io2=g_videoLastIo2; z->io3=g_videoLastIo3; z->pbase=g_videoLastPayloadBase; z->plen=g_videoLastPayloadSize;
    z->calcData=g_videoLastCalcData; z->calcLen=g_videoLastCalcLen;
    z->desc0=g_videoLastDescW0; z->desc1=g_videoLastDescW1; z->desc2=g_videoLastDescW2; z->desc3=g_videoLastDescW3;
    z->desc4=g_videoLastDescW4; z->desc5=g_videoLastDescW5; z->desc6=g_videoLastDescW6; z->desc7=g_videoLastDescW7;
    z->bufobj=g_videoLastBufObj;
    z->b0=g_videoLastBufW0; z->b1=g_videoLastBufW1; z->b2=g_videoLastBufW2; z->b3=g_videoLastBufW3; z->b4=g_videoLastBufW4; z->b5=g_videoLastBufW5;
    z->b6=g_videoLastBufW6; z->b7=g_videoLastBufW7; z->b8=g_videoLastBufW8; z->b9=g_videoLastBufW9; z->b10=g_videoLastBufW10; z->b11=g_videoLastBufW11;
    g_videoSamplePos=(g_videoSamplePos+1u)&7u; if(g_videoSampleCount<8u) g_videoSampleCount++;
  }
  if((g_videoDataCalls & 31u)==1u) video_probe_flush();
}
extern "C" __attribute__((used)) void video_probe_config(u32 self,u32 a1,u32 a2,u32 a3){
  g_videoConfigCalls++; g_videoLastConfigThis=self; g_videoLastCfgA1=a1; g_videoLastCfgA2=a2; g_videoLastCfgA3=a3;
  g_videoConfigLen=0;
  if(a1>=0x1000u && a1<0x20000000u && a2>0 && a2<=sizeof g_videoConfig){
    const volatile u8* src=(const volatile u8*)a1; g_videoConfigLen=a2;
    for(u32 i=0;i<g_videoConfigLen;i++) g_videoConfig[i]=src[i];
  }
  video_probe_flush();
}
// Called only after the original prologue has made a private argument-save area. A normal C
// helper is safe here because r0-r3 are restored by the trampoline before it branches onward.
extern "C" __attribute__((used)) u32 video_data_continue(){ return ref(R_VIDEO_DATA)+16; }
extern "C" __attribute__((used)) u32 video_config_continue(){ return ref(R_VIDEO_CONFIG)+16; }

// A new AA session starts: the per-session counters and last-seen words go back to zero.
static void video_probe_reset(){
  g_videoDataCalls=0; g_videoConfigCalls=0; g_videoLastDataThis=0; g_videoLastConfigThis=0;
  g_videoLastA1=0; g_videoLastA2=0; g_videoLastA3=0;
  g_videoLastCfgA1=0; g_videoLastCfgA2=0; g_videoLastCfgA3=0;
  g_videoPayloadLen=0; g_videoConfigLen=0;
  g_videoLastR3=0;
  g_videoLastStack0=0; g_videoLastStack1=0;
  g_videoLastStack2=0; g_videoLastStack3=0; g_videoLastStack4=0;
  g_videoLastSelf18=0; g_videoLastSelf34=0;
  g_videoLastVptr=0;
  g_videoLastIoPtr=0; g_videoLastIo0=0; g_videoLastIo2=0; g_videoLastIo3=0;
  g_videoLastPayloadBase=0; g_videoLastPayloadSize=0;
  g_videoLastDescW0=0; g_videoLastDescW1=0; g_videoLastDescW2=0; g_videoLastDescW3=0;
  g_videoLastDescW4=0; g_videoLastDescW5=0; g_videoLastDescW6=0; g_videoLastDescW7=0;
  g_videoLastCalcData=0; g_videoLastCalcLen=0;
  g_videoLastBufW6=0; g_videoLastBufW7=0; g_videoLastBufW8=0; g_videoLastBufW9=0;
  g_videoLastBufW10=0; g_videoLastBufW11=0;
  g_videoSamplePos=0; g_videoSampleCount=0;
}

// These two entry trampolines are enabled only by --video-probe. The injector verifies the target
// prologue words before patching, because replaying the wrong ARM prologue would corrupt the GAL
// stack. A plain B (rather than BL) preserves the caller's LR; the original function then returns
// normally after the continuation at +16.
__attribute__((naked,used)) extern "C" void video_data_trampoline(){
  __asm__ volatile(
    "mov  r12,sp\n"
    "push {r0-r3,lr}\n"
    "ldr  r3,[sp,#12]\n"
    "push {r12}\n"
    "bl   video_probe_data\n"
    "add  sp,sp,#4\n"
    "pop  {r0-r3,lr}\n"
    ".word 0xE1A0C00D\n"  // mov ip,sp
    ".word 0xE92DD870\n"  // push {r4,r5,r6,r11,lr}
    ".word 0xE24CB004\n"  // sub r11,ip,#4
    ".word 0xE24DD014\n"  // sub sp,sp,#20
    "push {r0-r3}\n"
    "bl   video_data_continue\n"
    "mov  lr,r0\n"
    "pop  {r0-r3}\n"
    "add  r12,sp,#40\n"  // restore ip = entry sp for the original body
    "bx   lr\n");
}
__attribute__((naked,used)) extern "C" void video_config_trampoline(){
  __asm__ volatile(
    "push {r0-r3,lr}\n"
    "bl   video_probe_config\n"
    "pop  {r0-r3,lr}\n"
    ".word 0xE5903034\n"  // ldr r3,[r0,#0x34]
    ".word 0xE1A0C00D\n"  // mov ip,sp
    ".word 0xE92DD800\n"  // push target prologue word
    ".word 0xE1A00003\n"  // mov r0,r3
    "push {r0-r3}\n"
    "bl   video_config_continue\n"
    "mov  lr,r0\n"
    "pop  {r0-r3}\n"
    "add  r12,sp,#40\n"  // restore ip = entry sp for the original body
    "bx   lr\n");
}

#endif
