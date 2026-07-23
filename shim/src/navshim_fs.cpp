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

// ===== .galrefs: absolute VAs in libgal; the injector relativizes them (R_ARM_RELATIVE) =====
// At runtime g_ref[i] = libbase + value. All target functions are ARM (bit0 = 0).
enum { R_REGISTER=0, R_NAV_VTABLE=1, R_GOT_MEMSET=2, R_ONCHOPEN=3, R_MEDIA_VTABLE=4,
       R_MR_SHUTDOWN=5, N_REFS };
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
__attribute__((section(".galrefs"), used))
volatile u32 g_ref[N_REFS] = { 0, 0, 0, 0, 0, 0 };
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
  }
}

// ===== IPC: a dumb pipe of raw fields for the HMI jar (ShmemNavReader does the semantics) =====
// /dev/shmem is a RAM fs on this QNX (the SAL itself writes iap2_carplay.log there) → no flash.
// Line format (rewritten with O_TRUNC on every event):
//   seq status event side angle number dist time unit connected session road
static const char IPC_PATH[] = "/dev/shmem/aa_nav";
// Field map (from the device libgal parser):
//   NextTurnEvent listener args = Road(str), TurnSide, Event, Image(str), TurnAngle, TurnNumber
//   DistanceEvent              = DistanceMeters, TimeToTurnSeconds, field3, DisplayUnit
//   Status                     = status
struct NavState{ u32 seq,status,event,side,dist,time,unit; i32 angle,number; char road[64]; };
static NavState g_st;

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

// Write a buffer to a file via stdio (mode "w" = create/truncate).
static void write_file(const char* path, const char* buf, usize len){
  if(!p_fopen||!p_fwrite||!p_fclose) return;
  void* f=p_fopen(path,"w");
  if(f){ (void)p_fwrite(buf,1,len,f); p_fclose(f); }
}

static void ipc_flush(){
  if(!p_fopen) return;
  // format: seq status event side angle number dist time unit connected session road
  // connected/session are inserted BEFORE road (road is the space-remainder field, may hold spaces).
  // Worst case ~123 numeric bytes + road[63] + '\n' -> 256 leaves comfortable headroom.
  char b[256]; char* o=b;
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
  write_file(IPC_PATH, b, (usize)(o-b));
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
static void l_status(void*, u32 s){ g_navConn=1; g_st.status=s; g_st.seq++; ipc_flush(); }  // data flowing => channel up
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
static const char IPC_MEDIA[] = "/dev/shmem/aa_media";
// AlbumArt (cover) raw bytes -> RAM file, O_TRUNC-overwritten on each metadata event. /dev/shmem is a
// RAM fs (no flash wear). The HMI jar (AndroidAutoTarget.applyCoverArt) stages it and points TrackInfo
// list-58's __cover at it; the stock CoverArt usecase blits it to the AID cluster. AID-only feature.
static const char COVER_PATH[] = "/dev/shmem/aa_cover";
struct MediaState{ u32 seq; u32 pos; u32 dur; char song[80]; char artist[80]; char album[80];
                   u32 coverseq; u32 coverlen; };
static MediaState g_ms;

static void media_flush(){
  if(!p_fopen) return;
  // Worst case ~326 bytes (3×79 strings + 7 numeric fields) -> 420 leaves headroom.
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
  o=u2dec(o,g_session);                  // field 8: session epoch
  *o++='\n';
  write_file(IPC_MEDIA, b, (usize)(o-b));
}

// Listener vtable {s0, dtor, onStatus(+8), onMetadata(+0xc)}.
static void m_dtor(void*){}
// handleMediaPlaybackStatus → +8. Fires periodically (~1/s) while media plays, carrying the
// PlaybackSeconds position (msg+0x18, the only 32-bit field) as the 7th arg (callee [sp+8]). Bumping
// seq advances the position; disconnect is signalled explicitly via the connected flag (the
// GalReceiver::shutdown hook), NOT inferred from a frozen seq, so a paused track no longer self-clears.
//   arg index:  0=self 1=&src 2=repeat 3=repeatOne 4,5=src-temp-spill 6=PlaybackSeconds
static void m_status(void* /*self*/, void* /*src*/, u32 /*repeat*/, u32 /*repeatOne*/,
                     u32 /*a4*/, u32 /*a5*/, u32 playbackSeconds){
  g_mediaConn=1;                        // data flowing => channel up
  g_ms.pos = playbackSeconds;
  g_ms.seq++; media_flush();
}
// handleMediaPlaybackMetadata → +0xc. arr = 5 CoW std::string in the order the device builds them:
//   arr[0]=Song, arr[1]=Album, arr[2]=Artist, arr[3]=AlbumArt(bytes), arr[4]=Playlist.
// durationSeconds (MediaPlaybackMetadata+0x24) is the track length — feeds the progress bar total.
static void m_metadata(void* /*self*/, void* arr, u32 /*a2*/, u32 /*a3*/){
  g_mediaConn=1;                        // data flowing => channel up
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
    // (coverlen=0), never a bad deref. The AA-active state gate lives in the HMI jar (SHOW_COVER_ART).
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
struct MediaVT{ void* s0; void(*dtor)(void*); void(*status)(void*,void*,u32,u32,u32,u32,u32);
                void(*metadata)(void*,void*,u32,u32); };
static const MediaVT g_mediaVT = { 0, m_dtor, m_status, m_metadata };
struct MediaListener{ const MediaVT* vt; };
static MediaListener g_mediaListener = { &g_mediaVT };

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

typedef void (*reg_t)(void*,void*);

// Free service id. The range is deliberately narrow (0x10..0x40) so we don't read far past the
// router table: system services take the low ids, a free one is almost certainly here.
static u8 pick_free_id(u32 router){
  u32* tbl=(u32*)router;
  for(u32 id=0x10; id<0x40; ++id) if(tbl[id+0x40]==0) return (u8)id;
  return 0xff;
}

extern "C" __attribute__((used)) void gal_nav_inject(void* receiver){
  if(receiver == g_inited_receiver) return;   // already armed for THIS receiver
  g_inited_receiver = receiver;               // re-arm on each new receiver (AA reconnect)
  resolve_libc();
  if(!p_fopen){ return; }                          // no libc → continuing would be pointless/unsafe
  my_memset(g_navEp,0,sizeof g_navEp);
  my_memset(g_mediaEp,0,sizeof g_mediaEp);
  my_memset(&g_st,0,sizeof g_st);
  my_memset(&g_ms,0,sizeof g_ms);
  // New AA session: bump the epoch (jar drops any stale content) and reset the connect flags.
  // Flush the reset (connected=0 + empty content + new epoch) NOW, before registration, so a stale
  // nav/track from the previous session clears immediately on replug; the data callbacks re-assert
  // connected=1 as soon as the phone starts streaming again.
  g_session++; g_navConn=0; g_mediaConn=0;
  ipc_flush();
  media_flush();

  u32 router = (u32)receiver + 4;                 // MessageRouter is embedded at receiver+4
  u8 id = pick_free_id(router);
  if(id==0xff){ return; }
  *(u32*)(g_navEp+0x00) = ref(R_NAV_VTABLE) + 8;  // vptr = vtable+8
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
  // Clear the arm-latch: teardown deregistered our endpoints (MessageRouter::shutdown below), so the
  // next GalReceiver::init MUST re-register even if the SAL reuses the same receiver object. Without
  // this, a same-object reconnect left our nav/media endpoints dead while AA projection came back.
  g_inited_receiver = 0;
  ipc_flush(); media_flush();
  ((mrsd_t)ref(R_MR_SHUTDOWN))(messageRouter);   // displaced original MessageRouter::shutdown(this+4)
}
__attribute__((naked,used)) extern "C" void shutdown_trampoline(){
  __asm__ volatile(
    "push {lr}\n"                                  // save the return address into shutdown
    "bl   gal_shutdown_hook\n"                     // r0 = this+4 (MessageRouter*) already set
    "pop  {pc}\n");                                // return into shutdown's body (recomputes r0 from r4)
}
