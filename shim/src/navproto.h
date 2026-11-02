// navproto.h — Android Auto navigation wire decoders. No ARM/libgal dependency, so tests/shim can
// run them on the host. Included by navshim_fs.cpp.
//
// The phone picks ONE generation from the protocol version the head unit advertises, never both. We
// advertise 1.7, so everything arrives as 0x8006 NavigationState / 0x8007 NavigationCurrentPosition
// and is parsed here; the legacy 0x8004/0x8005 events (which libgal decoded for us, no parsing) stop
// coming altogether. The decoders below fill the same fields either generation used, so the jar is
// unaware of any of this.
#ifndef NAVPROTO_H
#define NAVPROTO_H

#ifndef NAVSHIM_TYPES_DEFINED
typedef unsigned char  u8;
typedef unsigned short u16;
typedef unsigned int   u32;
typedef int            i32;
typedef unsigned long  usize;
#endif

// ===== shared state ==========================================================================
// The legacy feed defines the IPC line-1 format:
//   NextTurnEvent = Road, TurnSide, Event, Image, TurnAngle, TurnNumber
//   DistanceEvent = DistanceMeters, TimeToTurnSeconds, _, DisplayUnit
// distText = the phone's OWN rendering of the distance to the manoeuvre ("450 m", "0.3 mi"): 1.7
// sends it next to the metres, already rounded the way the phone shows it and in the phone's units.
// Empty on 1.3 (the legacy event has no such string) and whenever 1.7 omits it, and the jar then
// formats the metres itself — so this is an upgrade, never a dependency.
// destDist/destTime are the 1.7-only distance and remaining travel time to the DESTINATION, as
// opposed to dist/time which are to the next manoeuvre. Both stay 0 on 1.3 (the legacy feed has no
// such fields), and 0 is also how "the phone has not said yet" is spelled — at 0 m / 0 s you have
// arrived and guidance ends anyway, so the two are not worth telling apart.
// curRoad = the road the car is on NOW (0x8007 current_road, 1.7 only), as opposed to road = the
// road the next manoeuvre turns into. Empty = the phone did not say.
// next* = steps[1], the manoeuvre AFTER the current one (1.7 only): the cluster can draw it as the
// small "then" arrow (navsd ManeuverDescriptor.maneuver_2). Same (event, side) vocabulary as the
// current one; nextValid 0 = the phone sent no second step (or one we cannot name).
// destName = the address of the FIRST destination in 0x8006 (NavigationDestination.address, 1.7
// only), as one free-form string ("ул. Ленина 1"); the jar puts it into navsd DestinationInfo.
// Empty = the phone did not say.
struct NavState{ u32 seq,status,event,side,dist,time,unit; i32 angle,number; char road[64];
                 char distText[16]; u32 destDist,destTime;
                 u32 nextValid,nextEvent,nextSide; i32 nextAngle,nextNumber;
                 char curRoad[64]; char destName[96];
                 char destEta[16];   // phone's arrival clock for the nearest destination ("17:42")
                 char cue[96];       // steps[0] instruction text ("Turn left onto ..."), "" = none
               };
static NavState g_st;

// Lane guidance (1.7 only) goes out as IPC line 2, so line 1 stays byte-identical:
//   "2v <count> <n0> <lane0> ... <n1> <lane...> ..."
// Each lane keeps every direction Android Auto supplied; lane = shape*2 + isHighlighted.
// shape: 0 UNKNOWN, 1 STRAIGHT, 2/3 SLIGHT_L/R, 4/5 NORMAL_L/R, 6/7 SHARP_L/R, 8/9 U_TURN_L/R.
// The cluster shows at most 6 lanes; we cap at 8 and let the jar clamp.
enum { MAX_LANES = 8, MAX_LANE_DIRECTIONS = 3 };
// Sanity bounds for the 1.7 distance/time fields — anything else means "the phone doesn't know".
enum { MAX_DIST_M = 20000000, MAX_TIME_S = 1000000 };
static u8  g_lane[MAX_LANES];                                  // primary (highlighted else first)
static u8  g_laneDirs[MAX_LANES][MAX_LANE_DIRECTIONS];
static u8  g_laneDirCount[MAX_LANES];
static u32 g_nLane;

// ===== minimal protobuf reader ===============================================================
// Hand-rolled: libgal's protobuf is not reachable from freestanding code. Every helper is bounded by
// the buffer end, so a malformed frame can only stop parsing early.
struct PB { const u8* p; const u8* e; };
static u32 pb_left(PB* s){ return (u32)(s->e - s->p); }
static u32 pb_var(PB* s){ u32 v=0; int sh=0;
  while(s->p < s->e){ u8 b=*s->p++; if(sh<=28) v |= (u32)(b&0x7f)<<sh; if(!(b&0x80)) break; sh+=7; }
  return v; }                                    // 10-byte negatives saturate; callers range-check
static int pb_tag(PB* s,u32* f,u32* w){ if(s->p>=s->e) return 0; u32 t=pb_var(s); if(!t) return 0;
  *f=t>>3; *w=t&7; return 1; }
static int pb_sub(PB* s,PB* o){ u32 n=pb_var(s); if(n>pb_left(s)) return 0;
  o->p=s->p; o->e=s->p+n; s->p+=n; return 1; }
static void pb_skip(PB* s,u32 w){
  if(w==0){ pb_var(s); }
  else if(w==2){ u32 n=pb_var(s); if(n>pb_left(s)) n=pb_left(s); s->p+=n; }
  else if(w==5){ s->p += (pb_left(s)>=4)?4:pb_left(s); }
  else if(w==1){ s->p += (pb_left(s)>=8)?8:pb_left(s); }
  else s->p=s->e;                                 // unknown wire type: stop, never run off the end
}
static void pb_str(PB* s,char* dst,int cap){
  u32 n=pb_var(s); if(n>pb_left(s)) n=pb_left(s);
  int j=0;
  for(u32 i=0;i<n;i++){ char c=(char)s->p[i];
    if(c=='\n'||c=='\r'||c=='\t') c=' ';          // line 1 is newline-delimited; never break it
    // Invisible characters the cluster font draws as a hyphen or a box: Google Maps puts SOFT
    // HYPHENS into long Russian words ("Направляй\u00ADтесь" came out "Направляй-тесь", 2026-10).
    // Dropped: U+00AD, U+200B..U+200D (zero-width), U+2060 (word joiner), U+FEFF (BOM).
    { const u8* q=s->p+i; u32 left=n-i;
      if(left>=2 && q[0]==0xC2 && q[1]==0xAD){ i+=1; continue; }
      if(left>=3 && q[0]==0xE2 && q[1]==0x80 && q[2]>=0x8B && q[2]<=0x8D){ i+=2; continue; }
      if(left>=3 && q[0]==0xE2 && q[1]==0x81 && q[2]==0xA0){ i+=2; continue; }
      if(left>=3 && q[0]==0xEF && q[1]==0xBB && q[2]==0xBF){ i+=2; continue; } }
    if(j<cap-1) dst[j++]=c; }
  // Cut short: never leave half a UTF-8 sequence at the end (Cyrillic is 2 bytes a letter), or the
  // jar decodes a replacement glyph onto the cluster. Back off to the lead byte of the partial char.
  if((u32)j<n && j>0){
    int k=j; while(k>0 && (((u8)dst[k-1])&0xC0)==0x80) k--;   // k-1 = lead byte (or ASCII)
    if(k>0){ u8 lead=(u8)dst[k-1]; int need=(lead>=0xF0)?4:(lead>=0xE0)?3:(lead>=0xC0)?2:1;
             if(j-(k-1)<need) j=k-1; }
  }
  dst[j]=0; s->p+=n;
}

// ===== 1.7 schema, recovered from DHU's generated parsers BY TAG (never by the order the
// sub-message constructors appear — that mistake once put the distance-to-destination on the
// cluster where the distance-to-manoeuvre belonged) ===========================================
// CORRECTED 2026-07-28 against the published schema (mrmees/open-android-auto,
// oaa/navigation/NavigationNotificationMessage.proto): fields 2 and 4 of the step were the wrong way
// round here. Both wrap a string in their own field 1, so reading the cue as the road name looked
// perfectly fine — Google Maps fills both, usually identically. Their names for these messages
// differ from ours (NavigationNotification / NavigationNextTurnDistanceEvent); the wire is the same.
//   NavigationState { repeated NavigationStep steps=1; repeated NavigationDestination dests=2; }
//   NavigationStep  { NavigationManeuver maneuver=1; NavigationText instruction=2;
//                     repeated NavigationLane lanes=3; NavigationRoadInfo road_info=4; }
//   NavigationText  { text=1 }      NavigationRoadInfo { repeated string road_names=1 }
//   NavigationDestination { string address=1; ChargingStationDetails charging_station=2; }
//   NavigationDestination { address=1; ChargingStationDetails charging_station=2 }
//   NavigationManeuver { type=1; roundabout_exit_number=2; roundabout_exit_angle=3; }
//   NavigationLane  { repeated LaneDirection dir=1 }   LaneDirection { shape=1; is_highlighted=2; }
//   NavigationCurrentPosition { NavigationStepDistance step=1; NavigationDestinationDistance dest=2;
//                               NavigationText current_road=3 }   (NavigationText { text=1 })
//   NavigationStepDistance { NavigationDistance distance=1; time_to_step_seconds=2 }
//   NavigationDestinationDistance { NavigationDistance distance=1; eta=2(string);
//                                   time_to_arrival_seconds=3 }
//   NavigationDistance { meters=1; display=2(string); units=3 }
// NavigationDistance.units and the legacy DistanceUnits share one numbering (both DHU accessors
// index the same name table), so g_st.unit needs no translation.

// NavigationType (1..50) -> the legacy (event, side) pair the jar already speaks. 1.7 bakes the side
// into the type name; 1.3 sent it separately (1=LEFT, 2=RIGHT, 3=UNSPECIFIED). CCW roundabouts map
// to RIGHT because that is what the 1.3 feed sent for them here, keeping the cluster glyph unchanged.
// Valid range is 1..50, taken from the bounds check in DHU's own name table getter
// (InstrumentCluster::getManeuverTypeText: `sub w8,w0,#1; cmp w8,#49; b.hi <default>`).
#define MV(e,s) (u8)(((e)<<2)|(s))
enum { N_MANEUVER = 51 };                     // index 0 (invalid) + types 1..50
static const u8 g_mvmap[N_MANEUVER] = {
  MV(0,3),                                    /*  0 invalid                  */
  MV(1,3), MV(2,3),                           /*  1 DEPART   2 NAME_CHANGE   */
  MV(9,1), MV(9,2),                           /*  3 KEEP_LEFT/RIGHT -> FORK  */
  MV(3,1), MV(3,2),                           /*  5 TURN_SLIGHT_L/R          */
  MV(4,1), MV(4,2),                           /*  7 TURN_NORMAL_L/R          */
  MV(5,1), MV(5,2),                           /*  9 TURN_SHARP_L/R           */
  MV(6,1), MV(6,2),                           /* 11 U_TURN_L/R               */
  MV(7,1), MV(7,2), MV(7,1), MV(7,2),         /* 13 ON_RAMP slight/normal    */
  MV(7,1), MV(7,2), MV(6,1), MV(6,2),         /* 17 ON_RAMP sharp / u-turn   */
  MV(8,1), MV(8,2), MV(8,1), MV(8,2),         /* 21 OFF_RAMP slight/normal   */
  MV(9,1), MV(9,2),                           /* 25 FORK_L/R                 */
  MV(10,1), MV(10,2), MV(10,3),               /* 27 MERGE_L/R/unspecified    */
  MV(0,3), MV(0,3),                           /* 30,31 unassigned            */
  MV(13,1), MV(13,1), MV(13,2), MV(13,2),     /* 32 RNDBT_ENTER_AND_EXIT CW/CCW (+WITH_ANGLE) */
  MV(14,3),                                   /* 36 STRAIGHT                 */
  MV(15,3), MV(16,3),                         /* 37 FERRY_BOAT / FERRY_TRAIN */
  /* all four destinations collapse onto the legacy DESTINATION(17): the media widget's word/arrow
     tables stop there and would render blank for 18/19, and nothing downstream tells them apart */
  MV(17,3), MV(17,3), MV(17,1), MV(17,2),     /* 39 DESTINATION{,_STRAIGHT,_LEFT,_RIGHT} */
  MV(11,1), MV(12,1), MV(11,2), MV(12,2),     /* 43 RNDBT ENTER/EXIT CW, CCW */
  MV(15,1), MV(15,2), MV(16,1), MV(16,2)      /* 47 FERRY_BOAT_L/R, FERRY_TRAIN_L/R */
};

// Writes through pointers so the current step and the follow-up step share one decoder.
static void parse_maneuver_to(PB s,u32* event,u32* side,i32* number,i32* angle){
  u32 f,w;
  while(pb_tag(&s,&f,&w)){
    if(f==1&&w==0){ u32 t=pb_var(&s); u8 m=(t<N_MANEUVER)?g_mvmap[t]:g_mvmap[0];
                    *event=(u32)(m>>2); *side=(u32)(m&3); }
    else if(f==2&&w==0){ u32 v=pb_var(&s); *number=(v>=1&&v<=99 )?(i32)v:0; }  // -1 => absent
    else if(f==3&&w==0){ u32 v=pb_var(&s); *angle =(v>=1&&v<=360)?(i32)v:0; }  // -1 => absent
    else pb_skip(&s,w);
  }
}
static void parse_maneuver(PB s){ parse_maneuver_to(s,&g_st.event,&g_st.side,&g_st.number,&g_st.angle); }

// steps[1]: only its manoeuvre is kept — the cluster's "then" arrow has no street, lanes or distance
// of its own. Called after parse_step, which has already cleared next* for this snapshot.
static void parse_next_step(PB s){
  u32 ev=0,sd=3; i32 num=0,ang=0; u32 f,w;
  while(pb_tag(&s,&f,&w)){
    if(f==1&&w==2){ PB d; if(!pb_sub(&s,&d)) break; parse_maneuver_to(d,&ev,&sd,&num,&ang); }
    else pb_skip(&s,w);
  }
  if(ev==0) return;                              // unnamed / invalid type: nothing to draw
  g_st.nextEvent=ev; g_st.nextSide=sd; g_st.nextNumber=num; g_st.nextAngle=ang; g_st.nextValid=1;
}

// A lane may allow several directions ("left and straight"). Keep all of them for the IPC;
// `g_lane` remains the highlighted direction when there is one, else the first, for the BAP
// laneDirection field that can carry only one primary arrow.
static void parse_lane(PB s){
  if(g_nLane>=MAX_LANES) return;
  u32 f,w; u8 dirs[MAX_LANE_DIRECTIONS]; int n=0,primary=0,hi=0;
  while(pb_tag(&s,&f,&w)){
    if(f==1&&w==2){
      PB d; if(!pb_sub(&s,&d)) break;
      u32 df,dw; int sh=0,h=0;
      while(pb_tag(&d,&df,&dw)){
        if(df==1&&dw==0) sh=(int)pb_var(&d);
        else if(df==2&&dw==0) h=pb_var(&d)?1:0;
        else pb_skip(&d,dw);
      }
      if(sh>=0 && sh<=9){
        int packed=(sh<<1)|h, exists=-1;
        for(int i=0;i<n;i++) if((dirs[i]>>1)==sh){ exists=i; break; }
        if(exists>=0){
          if(h) dirs[exists]=(u8)packed;            // preserve recommendation on duplicates
        } else if(n<MAX_LANE_DIRECTIONS) {
          dirs[n++]=(u8)packed;
        }
      }
    } else pb_skip(&s,w);
  }
  if(n){
    int selected=0;
    for(int i=0;i<n;i++) if((dirs[i]&1) && !hi){ selected=i; hi=1; }
    for(int i=0;i<n;i++) g_laneDirs[g_nLane][i]=dirs[i];
    g_laneDirCount[g_nLane]=(u8)n;
    g_lane[g_nLane]=dirs[selected];
    g_nLane++;
  }
}

// Copy src into dst[cap], cut at a whole UTF-8 character.
static void str_fit(char* dst,int cap,const char* src){
  int j=0; while(src[j] && j<cap-1){ dst[j]=src[j]; j++; }
  if(src[j] && j>0){
    int k=j; while(k>0 && (((u8)dst[k-1])&0xC0)==0x80) k--;
    if(k>0){ u8 lead=(u8)dst[k-1]; int need=(lead>=0xF0)?4:(lead>=0xE0)?3:(lead>=0xC0)?2:1;
             if(j-(k-1)<need) j=k-1; }
  }
  dst[j]=0;
}
static const char* str_find(const char* h,const char* n){
  for(;*h;h++){ const char* a=h; const char* b=n; while(*b && *a==*b){ a++; b++; } if(!*b) return h; }
  return 0;
}
// Google Maps (ru) sometimes puts the whole instruction into the road name:
//   "здесь: ул. Садовая В сторону ул. Ленина"
// Too long for the cluster line, and the first half ("here: <the road you turn at>") says nothing
// the arrow does not. Kept: from "В сторону"/"в сторону" on; without it, the text after "здесь: ".
static const char* road_clean(const char* t){
  static const char* const here[]={ "здесь: ","Здесь: " };
  for(unsigned i=0;i<sizeof here/sizeof here[0];i++){
    const char* h=here[i]; const char* a=t; while(*h && *a==*h){ a++; h++; }
    if(*h) continue;                                         // no "здесь: " prefix
    const char* to=str_find(a," В сторону "); if(!to) to=str_find(a," в сторону ");
    return to ? to+1 : a;
  }
  return t;
}

// A step is a COMPLETE snapshot of the manoeuvre, so everything it owns is cleared before parsing:
// a field the phone omits means "not known now". Without this the roundabout exit number/angle of a
// *_WITH_ANGLE manoeuvre survived into the next, angle-less one and the cluster drew a confidently
// wrong exit. Deliberately at step level, not frame level: a 0x8006 that carries no parsable step
// must change NOTHING, or a single truncated frame blanks the street and the manoeuvre word on the
// media widget for a poll cycle. The 0x8007 fields (dist/time/unit) live in the other message and
// keep their last good value on purpose.
static void parse_step(PB s){
  PB s0=s;                                       // the whole step, for the second (cue) pass
  g_st.event=0; g_st.side=3;                     // 3 = TurnSide UNSPECIFIED, as the legacy feed sent
  g_st.number=0; g_st.angle=0; g_st.road[0]=0; g_nLane=0;
  g_st.nextValid=0; g_st.nextEvent=0; g_st.nextSide=3; g_st.nextAngle=0; g_st.nextNumber=0;
  // The cluster's street line wants a road name, so road_info wins and the instruction is only the
  // fallback for a phone that omits it. Held aside rather than written straight into g_st.road,
  // because protobuf fields may arrive in any order and field 2 must not overwrite field 4.
  // Both read into larger buffers than g_st.road so road_clean sees the whole text before the cut.
  char cue[160]; cue[0]=0;
  char rn[160]; rn[0]=0;
  u32 f,w;
  while(pb_tag(&s,&f,&w)){
    if(w!=2){ pb_skip(&s,w); continue; }
    PB d; if(!pb_sub(&s,&d)) break;
    if(f==1) parse_maneuver(d);
    else if(f==2){ u32 tf,tw;                    // NavigationText { text = 1 } — the spoken cue
                   while(pb_tag(&d,&tf,&tw)){
                     if(tf==1&&tw==2) pb_str(&d,cue,sizeof cue);
                     else pb_skip(&d,tw); } }
    else if(f==3) parse_lane(d);
    else if(f==4){ u32 rf,rw;                    // NavigationRoadInfo { repeated string road_names = 1 }
                   while(pb_tag(&d,&rf,&rw)){
                     // Repeated, and a junction can carry several ("A4", "E40"). One line on the
                     // cluster, so the first non-empty name is the one that fits.
                     if(rf==1&&rw==2 && !rn[0]) pb_str(&d,rn,sizeof rn);
                     else pb_skip(&d,rw); } }
  }
  str_fit(g_st.road,sizeof g_st.road,road_clean(rn[0] ? rn : cue));
  // The instruction itself, kept apart for the media widget's manoeuvre line (IPC line 9). Its own,
  // larger buffer: a spoken cue is longer than a road name. Re-read rather than copied from `cue`
  // above, which is sized for the road fallback.
  g_st.cue[0]=0;
  { PB r=s0; u32 rf,rw;
    while(pb_tag(&r,&rf,&rw)){
      if(rf==2&&rw==2){ PB d; if(!pb_sub(&r,&d)) break; u32 tf,tw;
        while(pb_tag(&d,&tf,&tw)){ if(tf==1&&tw==2) pb_str(&d,g_st.cue,sizeof g_st.cue); else pb_skip(&d,tw); } }
      else pb_skip(&r,rw);
    } }
}

// steps[0] is the manoeuvre the cluster draws; steps[1] the follow-up one, kept only as the "then"
// arrow (next*, see parse_next_step); anything after that is ignored. The clearing of the previous
// manoeuvre happens in parse_step, i.e. only once a step is actually there — see there.
//
// Returns 1 when a step was actually parsed. The caller uses that as the "guidance is running"
// signal: at 1.7 the stock dispatcher is bypassed entirely, so its status callback never fires and
// this message is the only evidence that a route exists.
// The destination is message-level, so it follows the same "complete snapshot" rule as the step,
// but only for a frame that carried a step: a stepless (truncated) frame changes nothing.
static int parse_navstate(const u8* p,u32 len){
  PB s; s.p=p; s.e=p+len;
  int first=1,second=0,gotDest=0; u32 f,w;
  char dest[sizeof g_st.destName]; dest[0]=0;
  while(pb_tag(&s,&f,&w)){
    if(f==1&&w==2){ PB d; if(!pb_sub(&s,&d)) break;
                    if(first){ parse_step(d); first=0; }
                    else if(!second){ parse_next_step(d); second=1; } }   // steps[2..] unused
    else if(f==2&&w==2){ PB d; if(!pb_sub(&s,&d)) break;
                    if(!gotDest){ u32 df,dw;                             // dests[1..] = later legs
                      while(pb_tag(&d,&df,&dw)){
                        if(df==1&&dw==2) pb_str(&d,dest,sizeof dest);
                        else pb_skip(&d,dw); }
                      gotDest=1; } }
    else pb_skip(&s,w);
  }
  if(!first){
    for(usize i=0;i<sizeof g_st.destName;i++){ g_st.destName[i]=dest[i]; if(!dest[i]) break; }
  }
  return !first;
}

// NavigationState (0x8003) = the guidance lifecycle: { state = 1 (enum) }.
//   0 UNAVAILABLE, 1 ACTIVE, 2 INACTIVE, 3 REROUTING
// REROUTING is the 1.7 addition the stock enum stops short of, and the reason the stock dispatcher
// had to go (see nav_route). It reaches the jar as 3 rather than folded into ACTIVE
// because the jar SHOWS it: during a recalculation the manoeuvre on the cluster is the old one.
// Returns the state, or -1 when there is nothing we recognise — the caller then keeps the last one.
static int parse_navstatus(const u8* p,u32 len){
  PB s; s.p=p; s.e=p+len; u32 f,w;
  while(pb_tag(&s,&f,&w)){
    if(f==1&&w==0){
      u32 v=pb_var(&s);
      if(v<=3) return (int)v;
      return -1;                                 // a value even 1.7 does not define: ignore it
    }
    pb_skip(&s,w);
  }
  return -1;
}

static void parse_curpos(const u8* p,u32 len){
  PB s; s.p=p; s.e=p+len; u32 f,w; int gotDest=0;
  // The current road is part of every position update; one that does not carry it means "not known
  // here" (unnamed road, car park), so it is cleared rather than left showing the last street.
  char cur[sizeof g_st.curRoad]; cur[0]=0;
  while(pb_tag(&s,&f,&w)){
    if(f==1&&w==2){                              // NavigationStepDistance (field 2 is the destination)
      PB d; if(!pb_sub(&s,&d)) break;
      u32 df,dw;
      while(pb_tag(&d,&df,&dw)){
        if(df==1&&dw==2){ PB n; if(!pb_sub(&d,&n)) break;
          u32 nf,nw; u32 meters=0xffffffffu, unit=0xffffffffu; char disp[16]; disp[0]=0;
          while(pb_tag(&n,&nf,&nw)){
            if(nf==1&&nw==0) meters=pb_var(&n);
            else if(nf==2&&nw==2) pb_str(&n,disp,sizeof disp);   // the phone's own "450 m" / "0.3 mi"
            else if(nf==3&&nw==0) unit=pb_var(&n);
            else pb_skip(&n,nw);
          }
          // Committed as ONE group, so the text can never describe a different distance than the
          // metres beside it. AA sends -1 for "unknown" (seen at 0 m to the manoeuvre); as an
          // unsigned varint that saturates and would overflow the jar's Integer.parseInt, dropping
          // the whole line. Out of range => keep the previous values, so the countdown does not blank.
          if(meters<=MAX_DIST_M){
            g_st.dist=meters;
            if(unit<=7) g_st.unit=unit;
            int i=0; for(; i<(int)sizeof g_st.distText-1 && disp[i]; i++) g_st.distText[i]=disp[i];
            g_st.distText[i]=0;                  // absent string => cleared => the jar formats metres
          } }
        else if(df==2&&dw==0){ u32 v=pb_var(&d); if(v<=MAX_TIME_S) g_st.time=v; }
        else pb_skip(&d,dw);
      }
    }
    else if(f==2&&w==2){                         // NavigationDestinationDistance — REPEATED: one entry
      PB d; if(!pb_sub(&s,&d)) break;            // per remaining waypoint, nearest first.
      if(gotDest) continue;                      // only the nearest reaches the cluster; a stopover
      gotDest=1;                                 // is what the driver is heading for right now.
      char eta[sizeof g_st.destEta]; eta[0]=0;   // absent in this entry => cleared below
      u32 df,dw;
      while(pb_tag(&d,&df,&dw)){
        if(df==1&&dw==2){ PB n; if(!pb_sub(&d,&n)) break;
          u32 nf,nw;
          while(pb_tag(&n,&nf,&nw)){
            // Metres only. The phone also sends its own "11 km" and a unit here, but unlike the
            // manoeuvre countdown there is nowhere to put text: BAP DistanceToDestination carries a
            // number plus a unit code, so the cluster's own formatter has to produce the pair.
            if(nf==1&&nw==0){ u32 v=pb_var(&n); if(v<=MAX_DIST_M) g_st.destDist=v; }
            else pb_skip(&n,nw);
          } }
        // Field 2 is the phone's ETA clock string ("17:11", locale-formatted). It goes out verbatim
        // (IPC line 8) for the arrival-clock form of fid 22; the remaining seconds below stay the
        // default form. Committed independently of the metres: separate cluster functions.
        else if(df==2&&dw==2){ pb_str(&d,eta,sizeof eta); }
        else if(df==3&&dw==0){ u32 v=pb_var(&d); if(v<=MAX_TIME_S) g_st.destTime=v; }
        else pb_skip(&d,dw);
      }
      for(usize i=0;i<sizeof g_st.destEta;i++){ g_st.destEta[i]=eta[i]; if(!eta[i]) break; }
    }
    else if(f==3&&w==2){                         // NavigationText current_road { text = 1 }
      PB d; if(!pb_sub(&s,&d)) break;
      u32 tf,tw;
      while(pb_tag(&d,&tf,&tw)){
        if(tf==1&&tw==2 && !cur[0]) pb_str(&d,cur,sizeof cur);
        else pb_skip(&d,tw);
      }
    }
    else pb_skip(&s,w);
  }
  // Committed when the frame named a road, or was read to its end without one. A frame cut short
  // before field 3 says nothing about the road, so the last one stays (no flicker on a bad frame).
  if(cur[0] || s.p>=s.e){
    for(usize i=0;i<sizeof g_st.curRoad;i++){ g_st.curRoad[i]=cur[i]; if(!cur[i]) break; }
  }
}

#endif // NAVPROTO_H
