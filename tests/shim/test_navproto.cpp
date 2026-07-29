// Host unit test for shim/src/navproto.h — the hand-rolled Android Auto 1.7 wire decoders.
// They parse attacker-adjacent data (whatever the phone sends), run inside the HMI's SAL process and
// have no allocator, so bounds behaviour matters more than features. Build+run:
//
//     tests/shim/run.sh         (clang++ -fsanitize=address,undefined)
//
// The two "golden" frames are transcribed from Desktop Head Unit captures: a 5-lane motorway
// junction and a roundabout. Field values are the captured ones; road names, ETA and the like are
// placeholders — no real trip data goes into the repository.
#include <cstdio>
#include <cstring>
#include <cstdlib>

#include "../../shim/src/navproto.h"

static int g_fail = 0;
#define CHECK(cond, ...) do{ if(!(cond)){ g_fail++; \
    std::printf("  FAIL %s:%d  ", __FILE__, __LINE__); std::printf(__VA_ARGS__); std::printf("\n"); } }while(0)

// ---- tiny protobuf encoder -------------------------------------------------------------------
struct Buf {
  unsigned char b[1024]; int n;
  Buf(): n(0) {}
  void varint(unsigned long long v){ do{ unsigned char c=v&0x7f; v>>=7; if(v)c|=0x80; b[n++]=c; }while(v); }
  void tag(int f,int w){ varint((unsigned)((f<<3)|w)); }
  void v(int f,unsigned long long x){ tag(f,0); varint(x); }
  void neg1(int f){ tag(f,0); varint(0xFFFFFFFFFFFFFFFFull); }   // int32 -1 on the wire
  void s(int f,const char* str){ int L=(int)std::strlen(str); tag(f,2); varint((unsigned)L);
                                 std::memcpy(b+n,str,L); n+=L; }
  void m(int f,const Buf& sub){ tag(f,2); varint((unsigned)sub.n);
                                std::memcpy(b+n,sub.b,sub.n); n+=sub.n; }
};

static Buf lane(int shape,int hi,int shape2=-1,int hi2=0){
  Buf dir; dir.v(1,(unsigned)shape); if(hi) dir.v(2,1);
  Buf ln;  ln.m(1,dir);
  if(shape2>=0){ Buf d2; d2.v(1,(unsigned)shape2); if(hi2) d2.v(2,1); ln.m(1,d2); }
  return ln;
}

static void reset(){ std::memset(&g_st,0,sizeof g_st); g_nLane=0; std::memset(g_lane,0,sizeof g_lane); }

// ---- golden frame 1: "Use the 2nd from the right lane to turn right", 5 lanes ------------------
static void test_five_lanes(){
  reset();
  const char* road = "Use the 2nd from the right lane to turn right";
  Buf mv;  mv.v(1,8); mv.neg1(2); mv.neg1(3);      // 8 = TURN_NORMAL_RIGHT, no roundabout fields
  Buf rd;  rd.s(1,road);
  Buf step; step.m(1,mv); step.m(4,rd);
  step.m(3,lane(1,0)); step.m(3,lane(1,0)); step.m(3,lane(1,0));   // STRAIGHT ×3
  step.m(3,lane(3,1));                                             // SLIGHT_RIGHT, highlighted
  step.m(3,lane(3,0));                                             // SLIGHT_RIGHT
  Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);

  CHECK(g_st.event==4, "event=%u want 4 (TURN)", g_st.event);
  CHECK(g_st.side==2,  "side=%u want 2 (RIGHT)", g_st.side);
  CHECK(g_st.number==0 && g_st.angle==0, "number/angle=%d/%d want 0/0 (-1 means absent)",
        g_st.number, g_st.angle);
  CHECK(std::strcmp(g_st.road,road)==0, "road='%s'", g_st.road);
  CHECK(g_nLane==5, "nLane=%u want 5", g_nLane);
  const u8 want[5] = { 2,2,2, (3<<1)|1, (3<<1)|0 };
  for(int i=0;i<5 && g_nLane==5;i++)
    CHECK(g_lane[i]==want[i], "lane[%d]=%u want %u", i, g_lane[i], want[i]);
}

// ---- golden frame 2: roundabout with a real exit number + angle --------------------------------
static void test_roundabout(){
  reset();
  Buf mv; mv.v(1,35); mv.v(2,2); mv.v(3,270);   // 35 = ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
  Buf rd; rd.s(1,"Example Road / Second Ring");
  Buf step; step.m(1,mv); step.m(4,rd);
  step.m(3,lane(4,1)); step.m(3,lane(4,0)); step.m(3,lane(1,0));
  Buf st; st.m(1,step);
  Buf second; second.m(1,mv);                    // a follow-up step must be IGNORED
  st.m(1,second);
  parse_navstate(st.b,(u32)st.n);

  CHECK(g_st.event==13, "event=%u want 13 (ROUNDABOUT_ENTER_AND_EXIT)", g_st.event);
  CHECK(g_st.side==2,   "side=%u want 2 (CCW -> RIGHT, as the 1.3 feed sent)", g_st.side);
  CHECK(g_st.number==2, "number=%d want 2", g_st.number);
  CHECK(g_st.angle==270,"angle=%d want 270", g_st.angle);
  CHECK(g_nLane==3, "nLane=%u want 3 (only the first step)", g_nLane);
  const u8 want[3] = { (4<<1)|1, (4<<1)|0, (1<<1)|0 };
  for(int i=0;i<3 && g_nLane==3;i++) CHECK(g_lane[i]==want[i], "lane[%d]=%u want %u", i, g_lane[i], want[i]);
}

// ---- a lane that allows several directions: the routed one wins --------------------------------
static void test_multi_direction_lane(){
  reset();
  Buf step; step.m(3,lane(4,0, 1,1));            // NORMAL_LEFT (no) + STRAIGHT (highlighted)
  Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(g_nLane==1, "nLane=%u want 1", g_nLane);
  CHECK(g_lane[0]==((1<<1)|1), "lane[0]=%u want %u (STRAIGHT, highlighted)", g_lane[0], (1<<1)|1);
}

// ---- lane count is capped, and a lane-less frame CLEARS the previous lanes ---------------------
static void test_caps_and_clearing(){
  reset();
  Buf step; for(int i=0;i<20;i++) step.m(3,lane(1,0));
  Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(g_nLane==MAX_LANES, "nLane=%u want %u (capped)", g_nLane, (unsigned)MAX_LANES);

  Buf mv2; mv2.v(1,36);                          // STRAIGHT, no lanes at all
  Buf step2; step2.m(1,mv2);
  Buf st2; st2.m(1,step2);
  parse_navstate(st2.b,(u32)st2.n);
  CHECK(g_nLane==0, "nLane=%u want 0 (a lane-less frame must clear)", g_nLane);
  CHECK(g_st.event==14 && g_st.side==3, "event/side=%u/%u want 14/3 (STRAIGHT)", g_st.event, g_st.side);
}

// ---- "a step was parsed" is the caller's guidance-is-running signal ----------------------------
// At 1.7 the stock dispatcher is bypassed, so its status callback is gone and this return value is
// what sets status=ACTIVE. It must therefore be true ONLY when a step really arrived: anything
// looser and a stray frame would light up the cluster with no route.
static void test_navstate_reports_step(){
  reset();
  Buf mv; mv.v(1,8);
  Buf step; step.m(1,mv);
  Buf st; st.m(1,step);
  CHECK(parse_navstate(st.b,(u32)st.n)==1, "a frame with a step must report 1");

  reset();
  Buf empty;                                       // no fields at all
  CHECK(parse_navstate(empty.b,(u32)empty.n)==0, "an empty frame must report 0");

  reset();
  Buf other; other.v(9,1);                         // only fields we do not care about
  CHECK(parse_navstate(other.b,(u32)other.n)==0, "a step-less frame must report 0");

  reset();
  Buf junk; junk.s(1,"not a submessage we can walk");
  parse_navstate(junk.b,(u32)junk.n);              // must not crash; value is whatever it parsed to
}

// ---- road name vs spoken instruction -----------------------------------------------------------
// Step field 2 is the instruction and field 4 the road, not the other way round — a swap that hid
// for as long as it did because both wrap a string in their own field 1 and Google Maps fills both
// with the same text. Here they differ, so the two can never be confused again.
static void test_road_vs_instruction(){
  reset();
  Buf cue;  cue.s(1,"Turn right onto Main Street");
  Buf road; road.s(1,"Main Street");
  Buf step; step.m(2,cue); step.m(4,road);
  Buf st;   st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(std::strcmp(g_st.road,"Main Street")==0,
        "road='%s' want 'Main Street' (road_info wins over the instruction)", g_st.road);

  // Field order must not decide it: the same step with the road first.
  reset();
  Buf step2; step2.m(4,road); step2.m(2,cue);
  Buf st2;   st2.m(1,step2);
  parse_navstate(st2.b,(u32)st2.n);
  CHECK(std::strcmp(g_st.road,"Main Street")==0, "road='%s' want 'Main Street' whatever the order",
        g_st.road);

  // A phone that sends no road_info at all: the instruction is better than an empty street line.
  reset();
  Buf step3; step3.m(2,cue);
  Buf st3;   st3.m(1,step3);
  parse_navstate(st3.b,(u32)st3.n);
  CHECK(std::strcmp(g_st.road,"Turn right onto Main Street")==0,
        "road='%s' want the instruction as fallback", g_st.road);

  // road_names is REPEATED (a junction can be "A4" and "E40"); one line on the cluster => the first.
  reset();
  Buf multi; multi.s(1,"A4"); multi.s(1,"E40");
  Buf step4; step4.m(4,multi);
  Buf st4;   st4.m(1,step4);
  parse_navstate(st4.b,(u32)st4.n);
  CHECK(std::strcmp(g_st.road,"A4")==0, "road='%s' want 'A4' (first of the repeated names)", g_st.road);

  // An empty road name must not win over a usable instruction.
  reset();
  Buf blank; blank.s(1,"");
  Buf step5; step5.m(4,blank); step5.m(2,cue);
  Buf st5;   st5.m(1,step5);
  parse_navstate(st5.b,(u32)st5.n);
  CHECK(std::strcmp(g_st.road,"Turn right onto Main Street")==0,
        "road='%s' want the instruction when road_info is empty", g_st.road);
}

// ---- NavigationState (0x8003): the guidance lifecycle ------------------------------------------
// REROUTING=3 is the value the stock enum stops short of, and answering it wrongly costs either the
// AA session (what the stock did) or a cluster that blanks on every recalculation.
static void test_navstatus(){
  Buf a; a.v(1,1);
  CHECK(parse_navstatus(a.b,(u32)a.n)==1, "ACTIVE must map to 1");
  Buf i; i.v(1,2);
  CHECK(parse_navstatus(i.b,(u32)i.n)==2, "INACTIVE must map to 2");
  Buf u; u.v(1,0);
  CHECK(parse_navstatus(u.b,(u32)u.n)==0, "UNAVAILABLE must map to 0");
  Buf r; r.v(1,3);
  CHECK(parse_navstatus(r.b,(u32)r.n)==3, "REROUTING must reach the jar as 3, which shows it");

  // Anything we cannot read must leave the previous status standing, which is what -1 means.
  Buf huge; huge.v(1,9);
  CHECK(parse_navstatus(huge.b,(u32)huge.n)==-1, "an undefined state must be ignored");
  Buf empty;
  CHECK(parse_navstatus(empty.b,(u32)empty.n)==-1, "an empty message must be ignored");
  Buf other; other.v(7,1);
  CHECK(parse_navstatus(other.b,(u32)other.n)==-1, "a message without field 1 must be ignored");
  Buf wrongtype; wrongtype.s(1,"ACTIVE");
  CHECK(parse_navstatus(wrongtype.b,(u32)wrongtype.n)==-1, "field 1 as a string must be ignored");
}

// ---- NavigationCurrentPosition -> distance / time / unit ---------------------------------------
// A frame carries BOTH distances; taking the wrong one is exactly the bug that reached the car
// (12 km to the destination shown where 60 m to the turn belonged), so both are present here with
// values that cannot be confused.
static void test_curpos(){
  reset();
  Buf sdist; sdist.v(1,465); sdist.s(2,"450 m"); sdist.v(3,1);   // 1 = METERS, same enum as legacy
  Buf step;  step.m(1,sdist); step.v(2,24);                      // + 24 s to the manoeuvre
  Buf ddist; ddist.v(1,13500); ddist.s(2,"14 km"); ddist.v(3,2); // 2 = KILOMETRES
  Buf dest;  dest.m(1,ddist); dest.s(2,"12:34"); dest.v(3,1978);
  Buf road;  road.s(1,"R1");
  Buf cp;    cp.m(1,step); cp.m(2,dest); cp.m(3,road);
  parse_curpos(cp.b,(u32)cp.n);
  CHECK(g_st.dist==465, "dist=%u want 465 (the STEP distance, not the destination's 13500)", g_st.dist);
  CHECK(g_st.time==24,  "time=%u want 24 (time to step, not the 1978 to arrival)", g_st.time);
  CHECK(g_st.unit==1,   "unit=%u want 1 (metres, not the destination's kilometres)", g_st.unit);
  CHECK(std::strcmp(g_st.distText,"450 m")==0,
        "distText='%s' want '450 m' (the STEP string, not the destination's '14 km')", g_st.distText);
  CHECK(g_st.destDist==13500, "destDist=%u want 13500 (the destination's, not the step's 465)",
        g_st.destDist);
  CHECK(g_st.destTime==1978,  "destTime=%u want 1978 (to arrival, not the 24 to the step)",
        g_st.destTime);

  // The three NavigationDistance fields are committed together: an unknown distance must not leave
  // the previous text standing next to a new number, and a frame without the string must clear it
  // so the jar goes back to formatting the metres.
  Buf d2; d2.v(1,120); d2.v(3,1);                  // metres only, no display string
  Buf sd2; sd2.m(1,d2);
  Buf cp2; cp2.m(1,sd2);
  parse_curpos(cp2.b,(u32)cp2.n);
  CHECK(g_st.dist==120,       "dist=%u want 120", g_st.dist);
  CHECK(g_st.distText[0]==0,  "distText='%s' want empty (no string in this frame)", g_st.distText);

  Buf d3; d3.neg1(1); d3.s(2,"900 m");             // unknown metres + a string: neither may land
  Buf sd3; sd3.m(1,d3);
  Buf cp3; cp3.m(1,sd3);
  parse_curpos(cp3.b,(u32)cp3.n);
  CHECK(g_st.dist==120,      "dist=%u want 120 kept", g_st.dist);
  CHECK(g_st.distText[0]==0, "distText='%s' want still empty (-1 metres => nothing commits)",
        g_st.distText);

  Buf d4; d4.v(1,480); d4.s(2,"0.3 mi"); d4.v(3,3);  // imperial: exactly what we cannot format ourselves
  Buf sd4; sd4.m(1,d4);
  Buf cp4; cp4.m(1,sd4);
  parse_curpos(cp4.b,(u32)cp4.n);
  CHECK(std::strcmp(g_st.distText,"0.3 mi")==0, "distText='%s' want '0.3 mi'", g_st.distText);
}

// ---- an over-long distance string must be truncated, not overflow -----------------------------
static void test_long_dist_text(){
  reset();
  Buf d; d.v(1,100); d.s(2,"a very long distance string from the phone"); 
  Buf sd; sd.m(1,d);
  Buf cp; cp.m(1,sd);
  parse_curpos(cp.b,(u32)cp.n);
  CHECK(std::strlen(g_st.distText)==sizeof(g_st.distText)-1, "distText len=%zu want %zu",
        std::strlen(g_st.distText), sizeof(g_st.distText)-1);
}

// ---- every maneuver must map onto the legacy event vocabulary the jar already speaks ----------
// The media (non-nav) path switches on `event` with tables that stop at 17 (DESTINATION); emitting
// anything outside 1..17 there renders a blank word and a generic arrow.
static void test_event_vocabulary(){
  for(int type=1; type<=50; type++){          // DHU's own bounds check says 1..50 are valid
    reset();
    Buf mv; mv.v(1,(unsigned)type);
    Buf step; step.m(1,mv);
    Buf st; st.m(1,step);
    parse_navstate(st.b,(u32)st.n);
    CHECK(g_st.event<=17, "type %d -> event %u, outside the legacy 0..17 vocabulary", type, g_st.event);
    CHECK(g_st.side>=1 && g_st.side<=3, "type %d -> side %u, want 1..3", type, g_st.side);
  }
  reset();
  Buf mv; mv.v(1,41);                              // DESTINATION_LEFT
  Buf step; step.m(1,mv); Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(g_st.event==17 && g_st.side==1, "DESTINATION_LEFT -> %u/%u want 17/1", g_st.event, g_st.side);

  // The last entry of the table: it is one past the end of the range the plan had written down, and
  // dropping off the end lands on "invalid" -> a generic turn arrow with no word under it.
  reset();
  Buf mv2; mv2.v(1,50);                            // FERRY_TRAIN_RIGHT
  Buf step2; step2.m(1,mv2); Buf st2; st2.m(1,step2);
  parse_navstate(st2.b,(u32)st2.n);
  CHECK(g_st.event==16 && g_st.side==2, "FERRY_TRAIN_RIGHT -> %u/%u want 16/2", g_st.event, g_st.side);
}

// ---- a NavigationState frame is a snapshot: what it omits must NOT survive from the last one -----
// The expensive case is the roundabout: only the *_WITH_ANGLE variants carry the exit number and
// angle, so without a reset the next plain ROUNDABOUT_EXIT inherits them and the cluster draws a
// confidently wrong exit.
static void test_snapshot_clears(){
  reset();
  Buf mv; mv.v(1,35); mv.v(2,2); mv.v(3,270);      // ROUNDABOUT_ENTER_AND_EXIT_CCW_WITH_ANGLE
  Buf rd; rd.s(1,"Example Road");
  Buf step; step.m(1,mv); step.m(2,rd); step.m(3,lane(4,1));
  Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(g_st.number==2 && g_st.angle==270 && g_nLane==1, "setup: %d/%d/%u",
        g_st.number, g_st.angle, g_nLane);

  Buf mv2; mv2.v(1,46);                            // ROUNDABOUT_EXIT_CCW, no number, no angle, no road
  Buf step2; step2.m(1,mv2);
  Buf st2; st2.m(1,step2);
  parse_navstate(st2.b,(u32)st2.n);
  CHECK(g_st.event==12 && g_st.side==2, "event/side=%u/%u want 12/2", g_st.event, g_st.side);
  CHECK(g_st.number==0, "number=%d want 0 (the previous exit number must not survive)", g_st.number);
  CHECK(g_st.angle==0,  "angle=%d want 0 (the previous exit angle must not survive)", g_st.angle);
  CHECK(g_st.road[0]==0,"road='%s' want empty (the previous road must not survive)", g_st.road);
  CHECK(g_nLane==0,     "nLane=%u want 0", g_nLane);

  // ...but a frame with NO parsable step must leave the last manoeuvre alone. On the media widget a
  // clear here would blank the street and the manoeuvre word for a poll cycle, and a truncated frame
  // is not a statement that the manoeuvre is gone.
  parse_navstate((const u8*)"",0);
  CHECK(g_st.event==12 && g_st.side==2, "empty frame -> %u/%u, must keep the last step (12/2)",
        g_st.event, g_st.side);

  // Same for a frame cut short mid-step: the step's own length prefix no longer fits, the step is
  // rejected whole, and the cluster keeps showing the last manoeuvre instead of flickering to blank.
  Buf mv3; mv3.v(1,7);                             // TURN_NORMAL_LEFT
  Buf step3; step3.m(1,mv3); step3.m(2,rd);
  Buf st3; st3.m(1,step3);
  parse_navstate(st3.b,(u32)st3.n - 3);
  CHECK(g_st.event==12 && g_st.side==2, "truncated frame -> %u/%u, must keep the last step (12/2)",
        g_st.event, g_st.side);
}

// ---- "-1" (unknown) distance/time must NOT clobber the last good countdown ---------------------
// Seen live on DHU at 0 m to the manoeuvre: the phone sends -1, which as an unsigned varint
// saturates to 4294967295 and would overflow the jar's Integer.parseInt, dropping the whole line.
static void test_unknown_distance(){
  reset();
  Buf d1; d1.v(1,465); d1.v(3,1);
  Buf sd1; sd1.m(1,d1); sd1.v(2,24);
  Buf cp1; cp1.m(1,sd1);
  parse_curpos(cp1.b,(u32)cp1.n);
  CHECK(g_st.dist==465 && g_st.time==24, "setup: dist/time=%u/%u", g_st.dist, g_st.time);

  Buf d2; d2.neg1(1); d2.v(3,1);
  Buf sd2; sd2.m(1,d2); sd2.neg1(2);
  Buf cp2; cp2.m(1,sd2);
  parse_curpos(cp2.b,(u32)cp2.n);
  CHECK(g_st.dist==465, "dist=%u want 465 kept (-1 means unknown)", g_st.dist);
  CHECK(g_st.time==24,  "time=%u want 24 kept", g_st.time);
}

// ---- the road string must never break the newline-delimited IPC line ---------------------------
static void test_road_sanitising(){
  reset();
  Buf rd; rd.s(1,"line1\nline2\ttab");
  Buf step; step.m(4,rd);
  Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(std::strchr(g_st.road,'\n')==0 && std::strchr(g_st.road,'\t')==0,
        "road still contains a line break: '%s'", g_st.road);
  CHECK(std::strcmp(g_st.road,"line1 line2 tab")==0, "road='%s'", g_st.road);
}

// ---- a long road must be truncated, not overflow ----------------------------------------------
static void test_long_road(){
  reset();
  char big[400]; std::memset(big,'x',sizeof big-1); big[sizeof big-1]=0;
  Buf rd; rd.s(1,big);
  Buf step; step.m(4,rd);
  Buf st; st.m(1,step);
  parse_navstate(st.b,(u32)st.n);
  CHECK(std::strlen(g_st.road)==sizeof(g_st.road)-1, "road len=%zu want %zu",
        std::strlen(g_st.road), sizeof(g_st.road)-1);
}

// ---- every truncation of a valid frame must be survivable (ASan catches over-reads) ------------
static void test_truncations(){
  Buf mv; mv.v(1,8); mv.v(2,2); mv.v(3,270);
  Buf rd; rd.s(1,"Example Road / Second Ring");
  Buf step; step.m(1,mv); step.m(2,rd); step.m(3,lane(3,1)); step.m(3,lane(1,0));
  Buf st; st.m(1,step);
  for(int n=0;n<=st.n;n++){
    reset();
    unsigned char* copy=(unsigned char*)std::malloc(n?n:1);   // exact-size heap block => ASan traps
    std::memcpy(copy,st.b,n);
    parse_navstate(copy,(u32)n);
    parse_curpos(copy,(u32)n);
    std::free(copy);
  }
}

// ---- garbage must not crash either --------------------------------------------------------------
static void test_fuzz(){
  unsigned seed=12345;
  for(int it=0; it<20000; ++it){
    int n = (int)(seed % 96) + 1;
    unsigned char* buf=(unsigned char*)std::malloc(n);
    for(int i=0;i<n;i++){ seed = seed*1103515245u + 12345u; buf[i]=(unsigned char)(seed>>16); }
    reset();
    parse_navstate(buf,(u32)n);
    parse_curpos(buf,(u32)n);
    std::free(buf);
    seed = seed*1103515245u + 12345u;
  }
}

int main(){
  struct { const char* name; void(*fn)(); } tests[] = {
    {"five lanes (golden frame)",     test_five_lanes},
    {"roundabout exit + angle",       test_roundabout},
    {"multi-direction lane",          test_multi_direction_lane},
    {"lane cap + clearing",           test_caps_and_clearing},
    {"navstate reports a step",       test_navstate_reports_step},
    {"road name vs instruction",      test_road_vs_instruction},
    {"navigation state / rerouting",  test_navstatus},
    {"current position distances",    test_curpos},
    {"long distance string",          test_long_dist_text},
    {"unknown (-1) distance",         test_unknown_distance},
    {"legacy event vocabulary",       test_event_vocabulary},
    {"snapshot clears stale fields",  test_snapshot_clears},
    {"road sanitising",               test_road_sanitising},
    {"long road truncation",          test_long_road},
    {"truncated frames",              test_truncations},
    {"fuzz",                          test_fuzz},
  };
  for(unsigned i=0;i<sizeof tests/sizeof tests[0];i++){
    int before=g_fail;
    tests[i].fn();
    std::printf("%-32s %s\n", tests[i].name, (g_fail==before) ? "ok" : "FAILED");
  }
  std::printf("%s\n", g_fail ? "TESTS FAILED" : "all tests passed");
  return g_fail ? 1 : 0;
}
