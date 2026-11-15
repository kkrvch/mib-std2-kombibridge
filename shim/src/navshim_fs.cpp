// navshim_fs.cpp — FREESTANDING shim for libext.google.gal.receiver.so (MIB2 STD2).
// Built with system clang (no QNX SDK):
//   clang -target armv7-none-eabi -marm -mfloat-abi=soft -ffreestanding -nostdlib
//         -fno-exceptions -fno-rtti -fno-builtin -Os -fPIC -c
//
// References to libgal code/data go through the .galrefs section (a table
// of absolute VAs that the injector marks R_ARM_RELATIVE → the loader adds the load base).
// libc is resolved at runtime by a hand-rolled dlsym: via the already-resolved memset GOT slot
// (libgal imports memset) → find the base of libc.so.3 → walk its .dynsym.
//
// Entry point gal_nav_inject(receiver) is called from the patched tail of GalReceiver::init.
// -----------------------------------------------------------------------------
typedef unsigned char  u8;
typedef unsigned short u16;
typedef unsigned int   u32;
typedef int            i32;
typedef unsigned long  usize;

#include "video_pipeline.h"

// ===== .galrefs: absolute VAs in libgal; the injector relativizes them (R_ARM_RELATIVE) =====
// At runtime g_ref[i] = libbase + value. All target functions are ARM (bit0 = 0).
enum { R_REGISTER=0, R_NAV_VTABLE=1, R_GOT_MEMSET=2, R_ONCHOPEN=3, R_MEDIA_VTABLE=4,
       R_MR_SHUTDOWN=5, R_VIDEO_DATA=6, R_VIDEO_CONFIG=7, R_QUEUE_OUT=8,
       R_ROUTE_GOT=9, R_ROUTE_FN=10, R_QOUT_GOT=11, R_QOUT_FN=12, N_REFS };
// These slots are PLACEHOLDERS (0). The injector (inject.py) resolves the per-firmware absolute
// VAs from the TARGET libgal's own .dynsym / relocations / .plt and writes them into these slots
// before relativizing them. So the shim is no longer locked to one libgal build — see shim/README.md.
// Order MUST match inject.py's resolved[] list.
//   R_REGISTER     = GalReceiver::registerService            (.dynsym symbol)
//   R_NAV_VTABLE   = _ZTV24NavigationStatusEndpoint          (.dynsym symbol; vptr = +8 below)
//   R_GOT_MEMSET   = memset GOT slot                         (R_ARM_JUMP_SLOT reloc)
//   R_ONCHOPEN     = onChannelOpened PLT stub                (the call displaced from init's tail)
//   R_MEDIA_VTABLE = _ZTV27MediaPlaybackStatusEndpoint       (.dynsym symbol; vptr = +8 below)
//   R_MR_SHUTDOWN  = MessageRouter::shutdown                 (.dynsym symbol; call displaced from
//                                                             GalReceiver::shutdown's 1st BL)
//   R_VIDEO_DATA   = VideoSink::handleDataAvailable           (.dynsym symbol; continuation +16)
//   R_VIDEO_CONFIG = VideoSink::handleCodecConfig             (.dynsym symbol; continuation +16)
//   R_QUEUE_OUT    = MessageRouter::queueOutgoing             (.dynsym symbol; cluster-sink sends)
//   R_ROUTE_GOT/FN = MessageRouter::routeMessage   GOT slot (R_ARM_JUMP_SLOT) / function (.dynsym)
//   R_QOUT_GOT/FN  = MessageRouter::queueOutgoing  GOT slot (R_ARM_JUMP_SLOT) / function (.dynsym)
extern "C" __attribute__((section(".galrefs"), used))
volatile u32 g_ref[N_REFS] = { 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0 };
static inline u32 ref(int i){ return g_ref[i]; }            // = libbase + VA

// ===== minimal freestanding libc =====
static void* my_memset(void* d,int c,usize n){ u8* p=(u8*)d; while(n--) *p++=(u8)c; return d; }
static usize my_strlen(const char* s){ const char* p=s; while(*p) ++p; return (usize)(p-s); }
static char* u2dec(char* o,u32 v){ char t[12]; int n=0; if(!v){*o++='0';*o=0;return o;}
  while(v){ t[n++]='0'+v%10; v/=10; } while(n--) *o++=t[n]; *o=0; return o; }
static char* i2dec(char* o,i32 v){ if(v<0){*o++='-'; return u2dec(o,(u32)(-v)); } return u2dec(o,(u32)v); }

// ===== hand-rolled dlsym over libc.so.3 (via the memset GOT slot) =====
// We use stdio (fopen/fwrite/fclose) instead of open/write/close so we don't depend on the
// numeric O_* flag values (which on QNX may differ from Linux); fopen takes a mode string.
typedef void* (*fopen_t)(const char*,const char*);
typedef usize (*fwrite_t)(const void*,usize,usize,void*);
typedef int   (*fclose_t)(void*);
static fopen_t  p_fopen;  static fwrite_t p_fwrite; static fclose_t p_fclose;
// Optional (trip dump timestamps only): clock_gettime(CLOCK_REALTIME=0, struct timespec{u32,u32}*).
typedef int   (*clock_gettime_t)(int,void*);
static clock_gettime_t p_clock_gettime;
// Cluster video (CLUSTER_SINK only): POSIX shared memory, a process-shared semaphore and one
// ticker thread. QNX values: O_RDWR 2, O_CREAT 0x100, PROT_READ|PROT_WRITE 0x300, MAP_SHARED 1.
typedef int   (*shm_open_t)(const char*,int,int);
typedef int   (*ftruncate_t)(int,long);
typedef void* (*mmap_t)(void*,usize,int,int,int,long);
typedef int   (*close_t)(int);
typedef int   (*sem_init_t)(void*,int,unsigned);
typedef int   (*sem_post_t)(void*);
typedef int   (*pthread_create_t)(void*,const void*,void*(*)(void*),void*);
typedef int   (*usleep_t)(unsigned);
static shm_open_t p_shm_open; static ftruncate_t p_ftruncate; static mmap_t p_mmap; static close_t p_close;
static sem_init_t p_sem_init; static sem_post_t p_sem_post;
static pthread_create_t p_pthread_create; static usleep_t p_usleep;
static usize (*p_fread)(void*,usize,usize,void*);

// minimal ELF32
struct Ehdr{ u8 ident[16]; u16 type,machine; u32 version,entry,phoff,shoff,flags;
             u16 ehsize,phentsize,phnum,shentsize,shnum,shstrndx; };
struct Phdr{ u32 type,offset,vaddr,paddr,filesz,memsz,flags,align; };
struct Sym { u32 name,value,size; u8 info,other; u16 shndx; };
enum { PT_DYNAMIC=2 };
enum { DT_NULL=0, DT_HASH=4, DT_STRTAB=5, DT_SYMTAB=6 };

static int streq(const char* a,const char* b){ while(*a&&*a==*b){++a;++b;} return *a==*b; }
static int is_elf(const Ehdr* e){ return e->ident[0]==0x7f&&e->ident[1]=='E'&&e->ident[2]=='L'&&e->ident[3]=='F'&&e->type==3; }

static void resolve_libc(){
  // 1) runtime address of memset from libgal's GOT slot
  u32 got = ref(R_GOT_MEMSET);            // GOT slot address
  u32 memset_rt = *(volatile u32*)got;    // memset address in libc.so.3
  if(!memset_rt) return;
  // 2) base of libc.so.3: scan pages downward to the ELF magic
  u32 base = memset_rt & ~0xfffu;
  int found=0;
  for(int i=0;i<0x4000;i++,base-=0x1000){
    if(is_elf((const Ehdr*)base)){ found=1; break; }
  }
  if(!found) return;                        // no ELF base found → bail out safely (no logging)
  const Ehdr* e=(const Ehdr*)base;
  if(e->phoff==0 || e->phnum==0 || e->phnum>64) return;   // sanity
  // 3) find PT_DYNAMIC → DT_SYMTAB/STRTAB/HASH
  const Phdr* ph=(const Phdr*)(base+e->phoff);
  u32 dynv=0;
  for(int i=0;i<e->phnum;i++) if(ph[i].type==PT_DYNAMIC){ dynv=ph[i].vaddr; break; }
  if(!dynv) return;
  const u32* dyn=(const u32*)(base+dynv);   // DT pairs [tag,val]
  u32 symtab=0,strtab=0,hash=0;
  for(int i=0;i<4096 && dyn[i*2]!=DT_NULL;i++){
    u32 tag=dyn[i*2], val=dyn[i*2+1];
    if(tag==DT_SYMTAB) symtab=val; else if(tag==DT_STRTAB) strtab=val; else if(tag==DT_HASH) hash=val;
  }
  if(!symtab||!strtab||!hash) return;
  // DT values in the loaded .so are usually already absolute (if not, add base):
  if(symtab<base) symtab+=base; if(strtab<base) strtab+=base; if(hash<base) hash+=base;
  u32 nchain=((const u32*)hash)[1];          // number of symbols
  if(nchain>0x20000) nchain=0x20000;         // cap, so a bad hash doesn't walk into garbage
  const Sym* syms=(const Sym*)symtab; const char* str=(const char*)strtab;
  for(u32 i=0;i<nchain;i++){
    const char* nm=str+syms[i].name; u32 va=syms[i].value; if(!va) continue;
    u32 addr=(va<base)? base+va : va;
    if(streq(nm,"fopen"))       p_fopen =(fopen_t)addr;
    else if(streq(nm,"fwrite")) p_fwrite=(fwrite_t)addr;
    else if(streq(nm,"fclose")) p_fclose=(fclose_t)addr;
    else if(streq(nm,"clock_gettime")) p_clock_gettime=(clock_gettime_t)addr;
    else if(streq(nm,"shm_open"))  p_shm_open=(shm_open_t)addr;
    else if(streq(nm,"ftruncate")) p_ftruncate=(ftruncate_t)addr;
    else if(streq(nm,"mmap"))      p_mmap=(mmap_t)addr;
    else if(streq(nm,"close"))     p_close=(close_t)addr;
    else if(streq(nm,"sem_init"))  p_sem_init=(sem_init_t)addr;
    else if(streq(nm,"sem_post"))  p_sem_post=(sem_post_t)addr;
    else if(streq(nm,"pthread_create")) p_pthread_create=(pthread_create_t)addr;
    else if(streq(nm,"usleep"))    p_usleep=(usleep_t)addr;
    else if(streq(nm,"fread"))     p_fread=(usize(*)(void*,usize,usize,void*))addr;
  }
}

// ===== IPC: a dumb pipe of raw fields for the HMI jar (AANavReader does the semantics) =====
// /dev/shmem is a RAM fs on this QNX (the SAL itself writes iap2_carplay.log there) → no flash.
// The whole file is rewritten with O_TRUNC on every event. Line 1 is always present:
//   seq status event side angle number dist time unit connected session road
// The tagged lines that may follow it are described at ipc_flush().
static const char IPC_PATH[] = "/dev/shmem/aa_nav";
// Field map (from the device libgal parser):
//   NextTurnEvent listener args = Road(str), TurnSide, Event, Image(str), TurnAngle, TurnNumber
//   DistanceEvent              = DistanceMeters, TimeToTurnSeconds, field3, DisplayUnit
//   Status                     = status
#define NAVSHIM_TYPES_DEFINED 1
#include "navproto.h"   // NavState/g_st, lane state, and both wire decoders (host-unit-tested)

// ===== connection state =====================================================================
// g_session: bumped once per AA session in gal_nav_inject (each GalReceiver::init = new session).
//   Written into both IPC files so the jar distinguishes a fresh session (seq restarts at 0) from a
//   mid-session glitch, and drops stale content on reconnect.
// g_navConn / g_mediaConn: 1 while the AA session is up, 0 after teardown. Cleared by the
//   GalReceiver::shutdown hook (the authoritative disconnect edge) and re-asserted whenever nav/media
//   data actually flows (belt-and-suspenders: connected can't be stuck at 0 while frames arrive).
static u32 g_session   = 0;
static u32 g_navConn   = 0;
static u32 g_mediaConn = 0;

// Write a buffer to a file via stdio. mode "w" = create/truncate (the state files, which are always
// rewritten whole), "a" = append (the message-id census, which is a timeline and must survive the
// session teardown it is meant to explain).
static void write_file_mode(const char* path, const char* buf, usize len, const char* mode){
  if(!p_fopen||!p_fwrite||!p_fclose) return;
  void* f=p_fopen(path,mode);
  if(f){ (void)p_fwrite(buf,1,len,f); p_fclose(f); }
}
static void write_file(const char* path, const char* buf, usize len){
  write_file_mode(path,buf,len,"w");
}

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
#if VIDEO_PIPELINE
  // The original VideoSink remains responsible for its normal ACK/backpressure path after the
  // trampoline returns; this hook only performs the bounded ownership copy.
  video_pipeline_on_data(orig_sp);
#endif
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
#if VIDEO_PIPELINE
  if(a1>=0x1000u && a1<0x20000000u && a2>0 && a2<=VP_CONFIG_MAX)
    video_pipeline_on_config((const u8*)a1,a2);
#endif
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

// ===== trip dump: everything the phone sent, for offline diagnosis (DEBUG builds only) ===========
// Off unless the HMI jar runs at Config.Advanced.LOG_LEVEL <= DEBUG: the jar then creates DUMP_FLAG at HMI
// start, and the shim checks it once per AA session, on the session's first message. Two append-only
// text files, one per channel, in /dev/shmem only (RAM: never on the SD card; lost on reboot, so copy
// them off before the unit restarts):
//   aa_dump_nav.log    R <id> <len> <hex payload>   every message on the navigation channel, raw
//                      > <line>                     every line handed to the jar via aa_nav
//   aa_dump_media.log  S ... / M ...                status / metadata callbacks, decoded + raw words
//                      > <line>                     every line handed to the jar via aa_media
// Every line starts with "<unix seconds>.<ms>" (or 0.000 if clock_gettime is not available), and a
// session is framed by "= session N start" / "= session N end". Cover-art bytes are never dumped.
// Written through a RAM buffer flushed every few seconds / ~12 KB, so the SAL thread that runs these
// callbacks does at most one small open-append-close per flush; each file stops at DUMP_MAX.
static const char DUMP_FLAG[] = "/dev/shmem/aa_debug";
static const char DUMP_DIR[] = "/dev/shmem/";
static const char DUMP_NAV_NAME[]   = "aa_dump_nav.log";
static const char DUMP_MEDIA_NAME[] = "aa_dump_media.log";
enum { DUMP_BUF = 16384, DUMP_FLUSH_AT = 12288, DUMP_FLUSH_S = 5, DUMP_HEX_MAX = 2048 };
static const u32 DUMP_MAX = 8u*1024u*1024u;   // per file; it lives in RAM
struct Dump { char path[64]; char buf[DUMP_BUF]; u32 n; u32 total; u32 lastFlushS; u32 full; };
static Dump g_dnav, g_dmedia;
static u32 g_dumpState;                // 0 = not decided this session, 1 = on, 2 = off

static void dump_now(u32* sec, u32* ms){
  u32 ts[2]; ts[0]=0; ts[1]=0;
  if(p_clock_gettime) (void)p_clock_gettime(0, ts);
  *sec=ts[0]; *ms=ts[1]/1000000u;
}
static void dump_path(Dump* d, const char* dir, const char* name){
  int j=0;
  for(int i=0; dir[i] && j<(int)sizeof d->path-1; i++) d->path[j++]=dir[i];
  for(int i=0; name[i] && j<(int)sizeof d->path-1; i++) d->path[j++]=name[i];
  d->path[j]=0;
}
static void dump_flush(Dump* d){
  if(!d->n) return;
  if(!d->full && d->path[0]){
    write_file_mode(d->path, d->buf, d->n, "a");
    d->total += d->n;
    if(d->total >= DUMP_MAX){
      static const char note[] = "= dump full, stopping\n";
      write_file_mode(d->path, note, sizeof note - 1, "a");
      d->full = 1;
    }
  }
  d->n = 0;
  u32 ms; dump_now(&d->lastFlushS, &ms);
}
static void dump_put(Dump* d, const char* s, usize n){
  if(d->n + n > DUMP_BUF) dump_flush(d);
  if(n > DUMP_BUF) n = DUMP_BUF;
  for(usize i=0;i<n;i++) d->buf[d->n++] = s[i];
}
static void dump_str(Dump* d, const char* s){ dump_put(d, s, my_strlen(s)); }
static void dump_dec(Dump* d, u32 v){ char t[12]; char* e=u2dec(t,v); dump_put(d,t,(usize)(e-t)); }
static void dump_hexword(Dump* d, u32 v){
  static const char h[]="0123456789abcdef"; char t[8];
  for(int i=0;i<8;i++) t[i]=h[(v>>(28-4*i))&0xf];
  dump_put(d,t,8);
}
// "<sec>.<ms> <tag> " -- the start of every record.
static void dump_begin(Dump* d, char tag){
  u32 sec, ms; dump_now(&sec,&ms);
  dump_dec(d,sec); dump_put(d,".",1);
  char t[3]; t[0]=(char)('0'+ms/100); t[1]=(char)('0'+(ms/10)%10); t[2]=(char)('0'+ms%10);
  dump_put(d,t,3); dump_put(d," ",1); dump_put(d,&tag,1); dump_put(d," ",1);
}
static void dump_end(Dump* d){
  dump_put(d,"\n",1);
  u32 sec, ms; dump_now(&sec,&ms);
  if(d->n >= DUMP_FLUSH_AT || sec - d->lastFlushS >= DUMP_FLUSH_S) dump_flush(d);
}
static void dump_session(Dump* d, const char* what){
  dump_begin(d,'='); dump_str(d,"session "); dump_dec(d,g_session); dump_put(d," ",1);
  dump_str(d,what); dump_end(d);
}
// Decided once per session, on its first message: by then the HMI (and the jar's flag) is up.
static int dump_on(){
  if(g_dumpState) return g_dumpState==1;
  g_dumpState = 2;
  if(!p_fopen || !p_fclose) return 0;
  void* f = p_fopen(DUMP_FLAG,"r");
  if(!f) return 0;
  p_fclose(f);
  dump_path(&g_dnav, DUMP_DIR, DUMP_NAV_NAME);
  void* t = p_fopen(g_dnav.path,"a");
  if(t){ p_fclose(t); dump_path(&g_dmedia, DUMP_DIR, DUMP_MEDIA_NAME); g_dumpState = 1; }
  if(g_dumpState != 1) return 0;
  g_dnav.n=0; g_dmedia.n=0;
  dump_session(&g_dnav,"start"); dump_session(&g_dmedia,"start");
  return 1;
}
// Session over (teardown, or a new session replacing the old one): close it out on disk.
static void dump_close(){
  if(g_dumpState==1){
    dump_session(&g_dnav,"end"); dump_session(&g_dmedia,"end");
    dump_flush(&g_dnav); dump_flush(&g_dmedia);
  }
  g_dumpState = 0;
}
// Every line of an IPC buffer, as "> <line>".
static void dump_ipc(Dump* d, const char* b, usize len){
  usize i=0;
  while(i<len){
    usize j=i; while(j<len && b[j]!='\n') j++;
    dump_begin(d,'>'); dump_put(d,b+i,j-i); dump_end(d);
    i=j+1;
  }
}
static void dump_nav_raw(u32 id, const u8* p, u32 len){
  Dump* d=&g_dnav; static const char h[]="0123456789abcdef";
  dump_begin(d,'R'); dump_hexword(d,id); dump_put(d," ",1); dump_dec(d,len); dump_put(d," ",1);
  u32 n = len<DUMP_HEX_MAX ? len : DUMP_HEX_MAX;
  for(u32 i=0;p && i<n;i++){ char t[2]; t[0]=h[p[i]>>4]; t[1]=h[p[i]&0xf]; dump_put(d,t,2); }
  if(n<len) dump_str(d," +more");
  dump_end(d);
}

static void ipc_flush(){
  if(!p_fopen) return;
  // format: seq status event side angle number dist time unit connected session road
  // connected/session are inserted BEFORE road (road is the space-remainder field, may hold spaces).
  // Worst case ~123 numeric bytes + road[63] + '\n', plus the tagged lines below (variable lane
  // directions ~100, distance text ~20, destination ~24, next manoeuvre ~40, current road ~66) ->
  // ~470 worst case, plus the destination address ~98, ETA ~18 and instruction ~98 -> ~690, so the
  // buffer is 800.
  char b[800]; char* o=b;
  o=u2dec(o,g_st.seq);   *o++=' ';
  o=u2dec(o,g_st.status);*o++=' ';
  o=u2dec(o,g_st.event); *o++=' ';
  o=u2dec(o,g_st.side);  *o++=' ';
  o=i2dec(o,g_st.angle); *o++=' ';
  o=i2dec(o,g_st.number);*o++=' ';
  o=u2dec(o,g_st.dist);  *o++=' ';
  o=u2dec(o,g_st.time);  *o++=' ';
  o=u2dec(o,g_st.unit);  *o++=' ';
  o=u2dec(o,g_navConn);  *o++=' ';   // connected: nav channel up (0 = session torn down)
  o=u2dec(o,g_session);  *o++=' ';   // session epoch
  { usize n=my_strlen(g_st.road); for(usize i=0;i<n;i++) *o++=g_st.road[i]; }
  *o++='\n';
  // Lines 2+ (1.7 only), each tagged with its own leading marker and omitted when it has nothing to
  // say — the jar dispatches on the marker, so absence of one never shifts the other.
  //   "2v <n> <dirCount> <packed> ..."  lanes; every combined-lane direction is preserved
  //   "3 <text>"           the phone's own distance string; absent = format the metres ourselves
  //   "4 <metres> <secs>"  to the DESTINATION; absent = unknown, the cluster fields stay invalid
  //   "5 <event> <side> <angle> <number>"  the manoeuvre AFTER the current one (steps[1]); absent =
  //                        the phone sent none. Same vocabulary as line 1's event/side/angle/number.
  //   "6 <road>"           the road the car is on now (0x8007 current_road); absent = not known
  //   "7 <address>"        the destination (first NavigationDestination.address); absent = not known
  //   "8 <eta>"            the phone's arrival clock for it, verbatim ("17:42"); absent = not known
  //   "9 <text>"           the instruction text of the manoeuvre, only when it says more than the
  //                        road on line 1 (Google/2GIS usually repeat the road; Yandex sends none)
  if(g_nLane){
    *o++='2'; *o++='v'; *o++=' ';
    o=u2dec(o,g_nLane);
    for(u32 i=0;i<g_nLane && i<MAX_LANES;i++){
      *o++=' '; o=u2dec(o,g_laneDirCount[i]);
      for(u32 d=0;d<g_laneDirCount[i] && d<MAX_LANE_DIRECTIONS;d++){
        *o++=' '; o=u2dec(o,g_laneDirs[i][d]);
      }
    }
    *o++='\n';
  }
  if(g_st.distText[0]){
    *o++='3'; *o++=' ';
    { usize n=my_strlen(g_st.distText); for(usize i=0;i<n;i++) *o++=g_st.distText[i]; }
    *o++='\n';
  }
  // Absent until the phone has said something: the jar then leaves the two destination functions
  // reporting themselves invalid, which is what the cluster shows with no route of its own either.
  if(g_st.destDist||g_st.destTime){
    *o++='4'; *o++=' ';
    o=u2dec(o,g_st.destDist); *o++=' ';
    o=u2dec(o,g_st.destTime);
    *o++='\n';
  }
  if(g_st.nextValid){
    *o++='5'; *o++=' ';
    o=u2dec(o,g_st.nextEvent);  *o++=' ';
    o=u2dec(o,g_st.nextSide);   *o++=' ';
    o=i2dec(o,g_st.nextAngle);  *o++=' ';
    o=i2dec(o,g_st.nextNumber);
    *o++='\n';
  }
  if(g_st.curRoad[0]){
    *o++='6'; *o++=' ';
    { usize n=my_strlen(g_st.curRoad); for(usize i=0;i<n;i++) *o++=g_st.curRoad[i]; }
    *o++='\n';
  }
  if(g_st.destName[0]){
    *o++='7'; *o++=' ';
    { usize n=my_strlen(g_st.destName); for(usize i=0;i<n;i++) *o++=g_st.destName[i]; }
    *o++='\n';
  }
  if(g_st.destEta[0]){
    *o++='8'; *o++=' ';
    { usize n=my_strlen(g_st.destEta); for(usize i=0;i<n;i++) *o++=g_st.destEta[i]; }
    *o++='\n';
  }
  if(g_st.cue[0]){
    int same=1;
    for(usize i=0;i<sizeof g_st.cue;i++){
      char c=g_st.cue[i]; char r=(i<sizeof g_st.road)?g_st.road[i]:0;
      if(c!=r){ same=0; break; }
      if(!c) break;
    }
    if(!same){
      *o++='9'; *o++=' ';
      { usize n=my_strlen(g_st.cue); for(usize i=0;i<n;i++) *o++=g_st.cue[i]; }
      *o++='\n';
    }
  }
  write_file(IPC_PATH, b, (usize)(o-b));
  if(g_dumpState==1) dump_ipc(&g_dnav, b, (usize)(o-b));
}

// Copy a CoW std::string's data (data = *(char**)strObj) into a fixed buffer.
static void rd_str_to(void* strObj, char* dst, int cap){
  const char* r = strObj? *(const char**)strObj : 0;
  int n=0; if(r){ while(r[n] && n<cap-1){ dst[n]=r[n]; ++n; } }
  dst[n]=0;
}

// ===== listener: vtable as libgal expects (+4 dtor, +8 status, +0xc nextturn, +0x10 dist) =====
// NavigationStatus enum: UNAVAILABLE=0, ACTIVE=1, INACTIVE=2
static void l_dtor(void*){}
// The three legacy callbacks below are UNREACHABLE now: they are invoked from inside the stock
// dispatcher, which we no longer call. They stay because the endpoint still advertises the listener
// pointer at +0x2c and we have not proved that no other stock path reads it — a valid pointer to
// these costs nothing, a dangling one would cost the SAL.
static void l_status(void*, u32 s){
  g_navConn=1; g_st.status=s; g_st.seq++; ipc_flush();
}
// onNextTurn(self, Road*, TurnSide, Event, Image*, TurnAngle, TurnNumber)
static void l_nextturn(void*, void* road, u32 turnSide, u32 event, void* /*image*/, i32 angle, i32 number){
  g_navConn=1; g_st.side=turnSide; g_st.event=event; g_st.angle=angle; g_st.number=number;
  rd_str_to(road, g_st.road, sizeof g_st.road);
  g_st.seq++; ipc_flush();
}
// onDistance(self, DistanceMeters, TimeToTurnSeconds, field3, DisplayUnit)
static void l_distance(void*, u32 meters, u32 timeSec, u32 /*field3*/, u32 unit){
  g_navConn=1; g_st.dist=meters; g_st.time=timeSec; g_st.unit=unit; g_st.seq++; ipc_flush();
}

struct ListVT{ void* s0; void(*dtor)(void*); void(*status)(void*,u32);
               void(*nextturn)(void*,void*,u32,u32,void*,i32,i32); void(*distance)(void*,u32,u32,u32,u32); };
static const ListVT g_listVT = { 0, l_dtor, l_status, l_nextturn, l_distance };
struct Listener{ const ListVT* vt; };
static Listener g_listener = { &g_listVT };

// ===== MediaPlaybackStatusEndpoint: now-playing track + progress (see DESIGN_MEDIA.md) =====
// IPC for the jar: /dev/shmem/aa_media, rewritten on each media event (O_TRUNC). Fields after the
// three track strings are appended so the older 3-field parser still finds song/artist/album:
//   seq <Song>\t<Artist>\t<Album>\t<posSec>\t<durSec>\t<coverSeq>\t<coverLen>\t<connected>\t<session>
//       \t<state>\t<shuffle>\t<repeat>\t<repeatOne>
// state = MediaPlaybackStatus.State (0 not said yet, 1 STOPPED, 2 PLAYING, 3 PAUSED); the three
// flags are 0/1. All four come from the ~1/s status heartbeat (m_status).
static const char IPC_MEDIA[] = "/dev/shmem/aa_media";
// AlbumArt (cover) raw bytes -> RAM file, O_TRUNC-overwritten on each metadata event. /dev/shmem is a
// RAM fs (no flash wear). The HMI jar (AndroidAutoTarget.applyCoverArt) stages it and points TrackInfo
// list-58's __cover at it; the stock CoverArt usecase blits it to the AID cluster. AID-only feature.
static const char COVER_PATH[] = "/dev/shmem/aa_cover";
struct MediaState{ u32 seq; u32 pos; u32 dur; char song[80]; char artist[80]; char album[80];
                   u32 coverseq; u32 coverlen; u32 state, shuffle, repeat, repeatOne; };
static MediaState g_ms;

static void media_flush(){
  if(!p_fopen) return;
  // Worst case ~350 bytes (3×79 strings + 11 numeric fields) -> 420 leaves headroom.
  char b[420]; char* o=b;
  o=u2dec(o,g_ms.seq); *o++=' ';
  { const char* s=g_ms.song;   while(*s) *o++=*s++; } *o++='\t';
  { const char* s=g_ms.artist; while(*s) *o++=*s++; } *o++='\t';
  { const char* s=g_ms.album;  while(*s) *o++=*s++; } *o++='\t';
  o=u2dec(o,g_ms.pos); *o++='\t';
  o=u2dec(o,g_ms.dur); *o++='\t';
  o=u2dec(o,g_ms.coverseq); *o++='\t';   // field 5: bumps per metadata event (track switch)
  o=u2dec(o,g_ms.coverlen); *o++='\t';   // field 6: cover byte length (0 = phone sent no art)
  o=u2dec(o,g_mediaConn);   *o++='\t';   // field 7: connected (media channel up, 0 = torn down)
  o=u2dec(o,g_session);     *o++='\t';   // field 8: session epoch
  o=u2dec(o,g_ms.state);    *o++='\t';   // field 9: playback state (see the format above)
  o=u2dec(o,g_ms.shuffle);  *o++='\t';   // field 10: shuffle
  o=u2dec(o,g_ms.repeat);   *o++='\t';   // field 11: repeat (the list)
  o=u2dec(o,g_ms.repeatOne);              // field 12: repeat one (the track)
  *o++='\n';
  write_file(IPC_MEDIA, b, (usize)(o-b));
  if(g_dumpState==1) dump_ipc(&g_dmedia, b, (usize)(o-b));
}

// Listener vtable {s0, dtor, onStatus(+8), onMetadata(+0xc)}.
static void m_dtor(void*){}
// handleMediaPlaybackStatus → +8. Fires periodically (~1/s) while media plays. Bumping seq advances
// the position; disconnect is signalled explicitly via the connected flag (the GalReceiver::shutdown
// hook), NOT inferred from a frozen seq, so a paused track no longer self-clears.
//
// RE of handleMediaPlaybackStatus (0x83584 on the dev unit): it copies the message into a 16-byte
// record built at the caller's sp, i.e. the callee's stack arguments, and calls the listener with
// r0=listener r1=&record and r2/r3 = Repeat/RepeatOne (left in the argument registers). The record:
//   [sp+0x0] State (msg+0x14)   [sp+0x4] MediaSource (CoW std::string, msg+0x10)
//   [sp+0x8] PlaybackSeconds (msg+0x18)
//   [sp+0xc] u8 Shuffle (msg+0x1c), [sp+0xd] u8 Repeat (+0x1d), [sp+0xe] u8 RepeatOne (+0x1e),
//            [sp+0xf] padding (uninitialised -> masked off)
// so as declared arguments: a4=State, a5=MediaSource, 6=PlaybackSeconds, 7=the packed flag bytes.
// The flags are read from that one record word, not from r2/r3, so all three come from the same copy.
static u32 flag01(u32 b){ return b==1 ? 1u : 0u; }      // a bool byte; anything else = not set
static void m_status(void* /*self*/, void* /*rec*/, u32 repeat, u32 repeatOne,
                     u32 state, u32 /*source*/, u32 playbackSeconds, u32 flags){
  g_mediaConn=1;                        // data flowing => channel up
  if(dump_on()){                        // raw words as received: state, position, flag bytes, r2/r3
    Dump* d=&g_dmedia; dump_begin(d,'S');
    dump_str(d,"state="); dump_dec(d,state); dump_str(d," pos="); dump_dec(d,playbackSeconds);
    dump_str(d," flags="); dump_hexword(d,flags);
    dump_str(d," r2="); dump_dec(d,repeat); dump_str(d," r3="); dump_dec(d,repeatOne);
    dump_end(d);
  }
  g_ms.pos = playbackSeconds;
  g_ms.state     = (state<=3) ? state : 0u;   // 1 STOPPED, 2 PLAYING, 3 PAUSED; else "not said"
  g_ms.shuffle   = flag01( flags        & 0xffu);
  g_ms.repeat    = flag01((flags >>  8) & 0xffu);
  g_ms.repeatOne = flag01((flags >> 16) & 0xffu);
  g_ms.seq++; media_flush();
}
// handleMediaPlaybackMetadata → +0xc. arr = 5 CoW std::string in the order the device builds them:
//   arr[0]=Song, arr[1]=Album, arr[2]=Artist, arr[3]=AlbumArt(bytes), arr[4]=Playlist.
// durationSeconds (MediaPlaybackMetadata+0x24) is the track length — feeds the progress bar total.
static void m_metadata(void* /*self*/, void* arr, u32 a2, u32 a3){
  g_mediaConn=1;                        // data flowing => channel up
  if(dump_on()){                        // strings + the raw words after them (dur, rating) and r2/r3
    Dump* d=&g_dmedia; dump_begin(d,'M');
    if(arr){
      const u32* w=(const u32*)arr;
      char t[80];
      rd_str_to(&((void**)arr)[0], t, sizeof t); dump_str(d,"song='");   dump_str(d,t);
      rd_str_to(&((void**)arr)[2], t, sizeof t); dump_str(d,"' artist='"); dump_str(d,t);
      rd_str_to(&((void**)arr)[1], t, sizeof t); dump_str(d,"' album='"); dump_str(d,t);
      rd_str_to(&((void**)arr)[4], t, sizeof t); dump_str(d,"' playlist='"); dump_str(d,t);
      dump_str(d,"' w5="); dump_dec(d,w[5]); dump_str(d," w6="); dump_dec(d,w[6]);
    } else dump_str(d,"arr=null");
    dump_str(d," r2="); dump_hexword(d,a2); dump_str(d," r3="); dump_hexword(d,a3);
    dump_end(d);
  }
  void** a = (void**)arr;
  if(a){
    rd_str_to(&a[0], g_ms.song,   sizeof g_ms.song);
    rd_str_to(&a[2], g_ms.artist, sizeof g_ms.artist);
    rd_str_to(&a[1], g_ms.album,  sizeof g_ms.album);
    // DurationSeconds is NOT the 3rd register arg — the on-unit constant "56:28" proved r2 is
    // garbage. RE of handleMediaPlaybackMetadata@0x836f8: libgal builds the 5 CoW strings into
    // buf r7+0x00..+0x10 and packs durationSeconds at r7+0x14 and rating at r7+0x18 into the
    // SAME buffer, then passes r1=r7 as `arr`. So the real values live right after the strings:
    //   arr[5] (off 0x14) = DurationSeconds,  arr[6] (off 0x18) = Rating.
    g_ms.dur = ((const u32*)arr)[5];
    // AlbumArt = arr[3] (the 4th protobuf string, `bytes`). CONFIRMED by disassembly of
    // handleMediaPlaybackMetadata@0x836f0: each arr[i] is a GCC libstdc++ COW std::string whose
    // object IS the char* data pointer (the destructor does `data-0xc; cmp _S_empty_rep`, proving
    // COW layout). The _Rep header sits 0xc bytes BEFORE the data, so the exact byte length =
    // data[-3] (_M_length) and capacity = data[-2]. We copy _M_length bytes VERBATIM (binary blob —
    // NOT strlen, which would stop at the first 0x00 inside the JPEG) to COVER_PATH and bump coverseq
    // so the jar re-stages on each track switch. Fully sanity-gated: an implausible field => skip
    // (coverlen=0), never a bad deref. The AA-active state gate lives in the HMI jar (Config.Media.SHOW_COVER_ART).
    const u8* adata = (const u8*)a[3];
    g_ms.coverseq++;                       // new metadata => track switch => jar re-evaluates cover
    u32 alen = 0, acap = 0;
    if(adata){ alen = ((const u32*)adata)[-3]; acap = ((const u32*)adata)[-2]; }
    int sane = adata && (alen>0) && (alen<=262144) && (alen<=acap) && ((u32)adata>0x10000);
    if(sane){
      write_file(COVER_PATH, (const char*)adata, alen);   // raw JPEG/PNG bytes, O_TRUNC full overwrite
      g_ms.coverlen = alen;
    } else {
      g_ms.coverlen = 0;                   // phone sent no art / implausible field
    }
  } else {
    g_ms.dur = 0;
    g_ms.coverlen = 0;
  }
  g_ms.seq++; media_flush();
}
struct MediaVT{ void* s0; void(*dtor)(void*); void(*status)(void*,void*,u32,u32,u32,u32,u32,u32);
                void(*metadata)(void*,void*,u32,u32); };
static const MediaVT g_mediaVT = { 0, m_dtor, m_status, m_metadata };
struct MediaListener{ const MediaVT* vt; };
static MediaListener g_mediaListener = { &g_mediaVT };

// ===== census of the messages we swallow =====================================================
// Bypassing the stock dispatcher throws away the only record of what else the phone sends here, and
// that list is what decides which ids deserve a parser of our own. Append-only, reads as a timeline:
//     sess 5                     <- session started
//     8003 2 08 01               <- id, length, payload; written on first sighting and on CHANGE
//     end 5  8003 214            <- totals, written when THAT session is torn down
// Nothing is written in steady state — per-message file I/O on this thread is the other thing we
// suspect of stalling the link.
static const char IDS_PATH[] = "/dev/shmem/aa_msgids";
enum { MAX_IDS = 16 };
static u16 g_idSeen[MAX_IDS];
static u32 g_idCnt[MAX_IDS];
static u32 g_nIds, g_idOverflow;

static char* u2hex(char* o,u32 v){
  const char* h="0123456789abcdef";
  int sh=28; while(sh>0 && !((v>>sh)&0xf)) sh-=4;
  for(; sh>=0; sh-=4) *o++=h[(v>>sh)&0xf];
  *o=0; return o;
}

// Totals for the session that is ending. One write per session, so nothing here runs per message.
static void ids_close_session(){
  if(!g_nIds && !g_idOverflow) return;
  // Worst case: "end " + 10, MAX_IDS x (2 spaces + 4 hex + space + 10), " +" + 10, newline = 300.
  char b[384]; char* o=b;
  *o++='e'; *o++='n'; *o++='d'; *o++=' '; o=u2dec(o,g_session);
  for(u32 i=0;i<g_nIds;i++){
    *o++=' '; *o++=' '; o=u2hex(o,g_idSeen[i]); *o++=' '; o=u2dec(o,g_idCnt[i]);
  }
  if(g_idOverflow){ *o++=' '; *o++='+'; o=u2dec(o,g_idOverflow); }   // more distinct ids than the table holds
  *o++='\n';
  write_file_mode(IDS_PATH, b, (usize)(o-b), "a");
}

static void ids_open_session(){
  char b[32]; char* o=b;
  *o++='s'; *o++='e'; *o++='s'; *o++='s'; *o++=' '; o=u2dec(o,g_session); *o++='\n';
  write_file_mode(IDS_PATH, b, (usize)(o-b), "a");
}

// Dump on a CHANGE of content, not on the first N sightings: a status message repeats itself while
// nothing happens, so counting sightings would record three copies of "guidance started" and miss
// the one frame that says it ended. Fingerprint = length + first bytes, which for a message this
// small is all of it. Bounded per id per session, so this can stay in the shipped build.
enum { IDS_DUMP_MAX = 8, IDS_DUMP_BYTES = 24, IDS_FP = 8 };
static u8 g_idFp[MAX_IDS][IDS_FP];
static u8 g_idDumps[MAX_IDS];
static void note_swallowed(u32 id,const u8* payload,u32 len){
  u32 slot = ~0u;
  for(u32 i=0;i<g_nIds;i++){
    if(g_idSeen[i]==(u16)id){ g_idCnt[i]++; slot = i; break; }
  }
  if(slot==~0u){
    if(g_nIds>=MAX_IDS){ g_idOverflow++; return; }    // table full: counted, not named
    slot = g_nIds;
    g_idSeen[slot]=(u16)id; g_idCnt[slot]=1; g_idDumps[slot]=0;
    for(u32 k=0;k<IDS_FP;k++) g_idFp[slot][k]=0xff;   // cannot collide with a real fingerprint
    g_nIds++;
  }
  u8 fp[IDS_FP];
  fp[0] = (u8)(len>255 ? 255 : len);
  for(u32 k=1;k<IDS_FP;k++) fp[k] = (payload && k-1<len) ? payload[k-1] : 0;
  int same = 1;
  for(u32 k=0;k<IDS_FP;k++) if(g_idFp[slot][k]!=fp[k]){ same = 0; break; }
  if(same) return;                                   // nothing new to say
  for(u32 k=0;k<IDS_FP;k++) g_idFp[slot][k]=fp[k];
  if(g_idDumps[slot]>=IDS_DUMP_MAX) return;          // a flapping message must not become a log flood
  g_idDumps[slot]++;
  char b[16+3*IDS_DUMP_BYTES]; char* o=b;
  o=u2hex(o,id); *o++=' '; o=u2dec(o,len);
  if(payload){
    u32 n = len<IDS_DUMP_BYTES ? len : IDS_DUMP_BYTES;
    for(u32 i=0;i<n;i++){
      const char* h="0123456789abcdef";
      *o++=' '; *o++=h[payload[i]>>4]; *o++=h[payload[i]&0xf];
    }
  }
  *o++='\n';
  write_file_mode(IDS_PATH, b, (usize)(o-b), "a");
}

// ===== protocol 1.7: NavigationState (0x8006) / NavigationCurrentPosition (0x8007) ===========
// The stock routeMessage knows only 0x8003/0x8004/0x8005 and answers everything else with an
// "unexpected message" frame. We advertise 1.7, so this parser is the sole source of manoeuvres —
// the legacy generation is not merely unused, the phone stops sending it.
// Payload extraction mirrors the stock prologue:
// IoBuffer* = *(bufref+4); data = buf[0]+buf[2]+2, len = buf[3]-buf[2]-2 (2 bytes = the message id).
// We speak AA 1.7 and own this endpoint completely: the stock dispatcher is never called, not even
// for the ids we do not use. It is a 1.3-era parser, and handed a 1.7 payload under an id it thinks
// it knows it eventually fails and answers the phone "unexpected message" — which the phone answers
// by tearing the session down, once per route recalculation. Observed on the car; silence the phone
// tolerates, an error frame it does not.
extern "C" __attribute__((used))
// self/channel stay in the signature: this is a vtable slot, its shape is fixed by libgal.
int nav_route(void* /*self*/,u32 /*channel*/,u32 msgId,const void* bufref){
  u32 id = msgId & 0xffff;
  // Payload extraction, once, for both branches — the census needs the bytes too. Mirrors the stock
  // prologue: IoBuffer* = *(bufref+4); data = buf[0]+buf[2]+2, len = buf[3]-buf[2]-2 (2 = the id).
  const u8* payload = 0; u32 plen = 0;
  {
    const u32* io = bufref ? *(const u32* const*)((const u8*)bufref+4) : 0;
    if(io){
      const u8* base=(const u8*)io[0]; u32 off=io[2], size=io[3];
      if(base && size>off+2 && (size-off-2)<0x40000){ payload = base+off+2; plen = size-off-2; }
    }
  }
  if(dump_on()) dump_nav_raw(id,payload,plen);   // DEBUG only; before any parsing, so nothing is lost
  if(id==0x8006 || id==0x8007){
    if(payload){
      if(id==0x8006){
        // A parsed step doubles as "a route is running": the stock status callback is gone with the
        // stock dispatcher. 1 = ACTIVE, the same value that callback used to set.
        if(parse_navstate(payload,plen)) g_st.status=1;
      } else {
        parse_curpos(payload,plen);
      }
      g_navConn=1; g_st.seq++; ipc_flush();
    }
    return 0;                                    // handled (0 = ok, as the stock handlers return)
  }
  // 0x8003 is the guidance lifecycle, once the stock handler behind the status callback. We read it
  // ourselves — that is what makes "guidance ended" an event again instead of a timeout. It stays in
  // the census too: one line per state CHANGE is exactly the transition worth seeing.
  if(id==0x8003 && payload){
    int st = parse_navstatus(payload,plen);
    if(st>=0){
      // Guidance really stopped (0 UNAVAILABLE / 2 INACTIVE; 3 REROUTING is still guiding) -> drop
      // the destination. Unlike the manoeuvre it is not restated on every frame, so otherwise the
      // finished trip's distance and travel time would still be in the file when the NEXT route
      // starts, and the jar would publish them until that route's first 0x8007 lands. This used to
      // live in the legacy status callback, which nothing reaches any more.
      if(st!=1 && st!=3){ g_st.destDist=0; g_st.destTime=0; g_st.nextValid=0; g_st.curRoad[0]=0; g_st.destName[0]=0; g_st.destEta[0]=0; }
      g_navConn=1; g_st.status=(u32)st; g_st.seq++; ipc_flush();
    }
  }
  note_swallowed(id,payload,plen);               // the census is the only record left of these
  return 0;
}
static u32 g_navVT[10] __attribute__((aligned(8)));   // private copy of the 40-byte stock vtable

// ===== endpoint objects (layout from RE, sizeof ~0x30 → reserve 0x40) =====
static u8 g_navEp[0x40]   __attribute__((aligned(8)));
static u8 g_mediaEp[0x40] __attribute__((aligned(8)));
// Track the receiver we armed, so we don't double-register within one live session. GalReceiver::init
// runs again on every AA reconnect and re-initialises the MessageRouter (clearing our registrations),
// so we MUST re-register each session. The SAL may reuse the SAME GalReceiver object across a
// disconnect+reconnect (observed on hardware: projection returns but our endpoints stay dead) — so
// this latch is CLEARED in gal_shutdown_hook, not keyed on the pointer changing. Init after a shutdown
// therefore always re-arms; a second init on the same live session (no shutdown between) is skipped.
static void* g_inited_receiver = 0;

// ===== MessageRouter hooks: two control-channel fixes that AA 1.7 needs ========================
// libgal calls its own exported MessageRouter methods through the PLT, so pointing their GOT slots
// at the wrappers below catches every caller. The wrappers call the real functions directly (their
// .dynsym addresses), so the GOT is written once and never read back. The GOT lives in libgal's RW
// segment (no RELRO); installed on the first GalReceiver::init, kept for the life of the process.
typedef int (*mr_route_t)(void*,u32,const void*);
typedef int (*mr_qout_t)(void*,u32,const void*,u32);
enum { ID_SERVICE_DISCOVERY_RESPONSE = 0x0006, ID_BATTERY_STATUS_NOTIFICATION = 0x0017 };
static u8 g_sdpOut[2048];

// Outgoing: the ServiceDiscoveryResponse gets BluetoothChannel.adapter_address =
// "SKIP_THIS_BLUETOOTH" (navproto.h sdp_skip_bluetooth explains why). queueOutgoing copies the
// bytes (BluetoothEndpoint::sendPairingResponse frees its buffer right after the call), so a
// static buffer is fine; discovery runs once per session on the receive thread.
extern "C" __attribute__((used)) int mr_queue_outgoing(void* self, u32 ch, const void* data, u32 len){
  const u8* p=(const u8*)data;
  if(p && len>2 && len<sizeof g_sdpOut && (((u32)p[0]<<8)|p[1])==ID_SERVICE_DISCOVERY_RESPONSE){
    u32 nl = sdp_skip_bluetooth(p+2, len-2, g_sdpOut+2, sizeof g_sdpOut-2);
    if(nl){
      g_sdpOut[0]=p[0]; g_sdpOut[1]=p[1];
      return ((mr_qout_t)ref(R_QOUT_FN))(self, ch, g_sdpOut, nl+2);
    }
  }
  return ((mr_qout_t)ref(R_QOUT_FN))(self, ch, data, len);
}

// Incoming: BatteryStatusNotification (control channel, newer than libgal's 1.3 Controller) is
// accepted silently. Unhandled, MessageRouter answers it with an "unexpected message" frame; observed
// on the car twice at a low phone battery, each followed by the session ending within a second.
// It is a notification — the phone expects no reply.
extern "C" __attribute__((used)) int mr_route_message(void* self, u32 ch, const void* bufref){
  if((ch&0xff)==0 && bufref){
    const u32* io = *(const u32* const*)((const u8*)bufref+4);   // shared_ptr<IoBuffer> -> IoBuffer*
    if(io){
      const u8* base=(const u8*)io[0]; u32 off=io[2], size=io[3];
      if(base && size>=off+2 && (((u32)base[off]<<8)|base[off+1])==ID_BATTERY_STATUS_NOTIFICATION)
        return 0;
    }
  }
  return ((mr_route_t)ref(R_ROUTE_FN))(self, ch, bufref);
}
static u32 g_routerHooked = 0;
static void install_router_hooks(){
  if(g_routerHooked) return;
  g_routerHooked = 1;
  *(volatile u32*)ref(R_QOUT_GOT)  = (u32)&mr_queue_outgoing;
  *(volatile u32*)ref(R_ROUTE_GOT) = (u32)&mr_route_message;
}

typedef void (*reg_t)(void*,void*);

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

// Free service id. The range is deliberately narrow (0x10..0x40) so we don't read far past the
// router table: system services take the low ids, a free one is almost certainly here.
static u8 pick_free_id(u32 router){
  u32* tbl=(u32*)router;
  for(u32 id=0x10; id<0x40; ++id) if(tbl[id+0x40]==0) return (u8)id;
  return 0xff;
}

#include "cluster_sink.h"

extern "C" __attribute__((used)) void gal_nav_inject(void* receiver){
  if(receiver == g_inited_receiver) return;   // already armed for THIS receiver
  g_inited_receiver = receiver;               // re-arm on each new receiver (AA reconnect)
  resolve_libc();
  install_router_hooks();                          // before discovery is answered
  if(!p_fopen){ return; }                          // no libc → continuing would be pointless/unsafe
  my_memset(g_navEp,0,sizeof g_navEp);
  my_memset(g_mediaEp,0,sizeof g_mediaEp);
  my_memset(&g_st,0,sizeof g_st);
  my_memset(&g_ms,0,sizeof g_ms);
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
  // New AA session: bump the epoch (jar drops any stale content) and reset the connect flags.
  // Flush the reset (connected=0 + empty content + new epoch) NOW, before registration, so a stale
  // nav/track from the previous session clears immediately on replug; the data callbacks re-assert
  // connected=1 as soon as the phone starts streaming again.
  // Close the census of the session that just died BEFORE bumping the epoch, so its totals carry the
  // number of the session they describe; then start the new one's list.
  ids_close_session();
  dump_close();                                   // the old session's dump (if any) ends here
#if VIDEO_PIPELINE
  video_pipeline_reset();
#endif
  g_session++; g_navConn=0; g_mediaConn=0;
  ipc_flush();
  media_flush();
  g_nIds=0; g_idOverflow=0;
  ids_open_session();

  u32 router = (u32)receiver + 4;                 // MessageRouter is embedded at receiver+4
  u8 id = pick_free_id(router);
  if(id==0xff){ return; }
  // Private copy of the stock 40-byte vtable with slot 7 (vptr+0x14 = routeMessage) swapped for
  // ours; the stock table is left alone so libgal's own endpoints keep working. Slot offsets come
  // from its .rel.dyn entries: +0x00/+0x04 dtor, +0x08/+0x0c/+0x10 channel hooks, +0x14 routeMessage,
  // +0x18 handleRawMessage, +0x1c addDiscoveryInfo.
  { const u32* svt=(const u32*)ref(R_NAV_VTABLE);
    for(int i=0;i<10;i++) g_navVT[i]=svt[i];
    g_navVT[7]=(u32)&nav_route; }
  *(u32*)(g_navEp+0x00) = (u32)g_navVT + 8;       // vptr = our table + 8
  *(u32*)(g_navEp+0x08) = router;                 // MessageRouter*
  g_navEp[0x0c] = id;                             // service id
  g_navEp[0x05] = id;                             // channel
  *(u32*)(g_navEp+0x24) = 2;                      // InstrumentClusterType = 2 (enum/text, mono)
  *(u32*)(g_navEp+0x2c) = (u32)&g_listener;       // listener -> ours

  ((reg_t)ref(R_REGISTER))(receiver, g_navEp);    // register with the router
  // We never call NavigationStatusEndpoint::start() — after auth the phone sees the service in
  // discovery and opens the nav channel itself; a prematurely queued start message breaks auth.

  // --- MediaPlaybackStatusEndpoint (now-playing). Register AFTER nav so pick_free_id picks a
  //     different free slot. Listener pointer is at +0x18 (NOT +0x2c); no clusterType. ---
  u8 mid = pick_free_id(router);
  if(mid != 0xff){
    *(u32*)(g_mediaEp+0x00) = ref(R_MEDIA_VTABLE) + 8; // vptr = vtable+8
    *(u32*)(g_mediaEp+0x08) = router;                  // MessageRouter*
    g_mediaEp[0x0c] = mid;                             // service id
    g_mediaEp[0x05] = mid;                             // channel
    *(u32*)(g_mediaEp+0x18) = (u32)&g_mediaListener;   // listener -> ours (media: +0x18)
    ((reg_t)ref(R_REGISTER))(receiver, g_mediaEp);
  }
#if CLUSTER_SINK
  // Registered last, so the nav/media ids stay exactly what they were without the experiment.
  cluster_register(receiver, router);
#endif
}

// ===== hook on the tail of GalReceiver::init =====
// The injector replaces `bl onChannelOpened` with `bl init_trampoline`.
// At that point: r0=controlEp, r1=0, r4=receiver(this), lr=return address into init's epilogue.
typedef void (*onch_t)(void*,int);
extern "C" __attribute__((used)) void nav_init_hook(void* controlEp, int z, void* receiver){
  gal_nav_inject(receiver);                       // our registration (after init's own ones)
  ((onch_t)ref(R_ONCHOPEN))(controlEp, z);        // the displaced original onChannelOpened call
}
__attribute__((naked,used)) extern "C" void init_trampoline(){
  __asm__ volatile(
    "push {lr}\n"                                  // save the return address into init
    "mov  r2, r4\n"                                // receiver(this) -> 3rd argument
    "bl   nav_init_hook\n"                         // r0=controlEp, r1=0 already set by init
    "pop  {pc}\n");                                // return into init's epilogue
}

// ===== hook on GalReceiver::shutdown (the authoritative session-teardown edge) ================
// The SAL drives the receiver lifecycle from outside libgal: GalReceiver::init on connect,
// GalReceiver::shutdown on disconnect (both are exported symbols with no in-library callers). We
// already hook init; this is the symmetric teardown hook — and it replaces the per-endpoint
// onChannelClosed vtable interception entirely (no RAM vtable copies, no slot-offset assumptions).
//
// The injector repoints the FIRST `bl` in GalReceiver::shutdown (its call to MessageRouter::shutdown)
// to shutdown_trampoline. At that site: r0 = this+4 (MessageRouter*), r4 = this (receiver), and the
// caller recomputes r0 from r4 right after the call, so r0 need not survive our hook. We clear the
// connected flags, flush both IPC files (instant clear on the cluster), then run the displaced
// MessageRouter::shutdown so the library tears down exactly as before.
typedef void (*mrsd_t)(void*);
extern "C" __attribute__((used)) void gal_shutdown_hook(void* messageRouter){
  g_navConn = 0; g_mediaConn = 0;
#if VIDEO_PIPELINE
  video_pipeline_on_disconnect();
#endif
#if CLUSTER_SINK
  cluster_video_off();
#endif
  // Clear the arm-latch: teardown deregistered our endpoints (MessageRouter::shutdown below), so the
  // next GalReceiver::init MUST re-register even if the SAL reuses the same receiver object. Without
  // this, a same-object reconnect left our nav/media endpoints dead while AA projection came back.
  g_inited_receiver = 0;
  ipc_flush(); media_flush();
  dump_close();
  ((mrsd_t)ref(R_MR_SHUTDOWN))(messageRouter);   // displaced original MessageRouter::shutdown(this+4)
}
__attribute__((naked,used)) extern "C" void shutdown_trampoline(){
  __asm__ volatile(
    "push {lr}\n"                                  // save the return address into shutdown
    "bl   gal_shutdown_hook\n"                     // r0 = this+4 (MessageRouter*) already set
    "pop  {pc}\n");                                // return into shutdown's body (recomputes r0 from r4)
}
