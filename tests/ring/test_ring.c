/*
 * Host test of the cluster video ring (shim/src/cluster_video_shm.h): the writer (libgal's cluster
 * sink) and the readers (libkb_dm.so) use exactly these functions on the unit.
 *
 * A small arena (64 KB, 16 index entries) so that wrapping, overwriting and index reuse happen in
 * a few frames. KBCV_COPY is hooked: when asked, the writer runs in the middle of a reader's copy,
 * which is what a reader on another core preempted at the worst moment would see. A last part runs
 * a writer and a reader thread against each other and checks every frame the reader accepts.
 */
#define KBCV_ARENA     65536u
#define KBCV_INDEX     16u
#define KBCV_FRAME_MAX 16384u
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <pthread.h>

struct kbcv;
static void (*g_during_copy)(void);
static int g_inside;
static void hooked_copy(unsigned char *d, const unsigned char *s, unsigned n)
{
  if (g_during_copy && !g_inside) {           /* the writer gets in after the first half */
    void (*f)(void) = g_during_copy;
    memcpy(d, s, n / 2);
    g_inside = 1; f(); g_inside = 0;
    memcpy(d + n / 2, s + n / 2, n - n / 2);
    return;
  }
  memcpy(d, s, n);
}
#define KBCV_COPY(d, s, n) hooked_copy((unsigned char *)(d), (const unsigned char *)(s), (n))
#include "../../shim/src/cluster_video_shm.h"

static int failed;
#define CHECK(what, cond) do { if (!(cond)) { failed++; printf("  FAIL %s (line %d)\n", what, __LINE__); } } while (0)

static struct kbcv *v;
static unsigned char buf[KBCV_FRAME_MAX + 64], out[KBCV_FRAME_MAX + 64];

/* frame content that names its sequence: byte i = (seq * 31 + i) & 0xff */
static void fill(unsigned char *b, unsigned seq, unsigned n) { unsigned i; for (i = 0; i < n; i++) b[i] = (unsigned char)(seq * 31u + i); }
static int matches(const unsigned char *b, unsigned seq, unsigned n) { unsigned i; for (i = 0; i < n; i++) if (b[i] != (unsigned char)(seq * 31u + i)) return 0; return 1; }
static unsigned put(unsigned n, unsigned flags)
{
  unsigned seq = v->write_seq + 1u; if (!seq) seq = 1u;
  fill(buf, seq, n);
  return kbcv_put(v, 0, 0, buf, n, flags, KBCV_FRAME_MAX);
}
static void fresh(void) { memset(v, 0, sizeof(*v)); kbcv_ring_reset(v); }

static void basics(void)
{
  unsigned s, n = 0, f = 0;
  unsigned char pre[3] = { 0, 0, 1 };
  fresh();
  s = put(1000, 0);
  CHECK("first frame is sequence 1", s == 1 && v->write_seq == 1);
  CHECK("round trip", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && n == 1000 && f == 0 && matches(out, 1, 1000));
  s = put(500, KBCV_FLAG_IDR);
  CHECK("flags come back", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && f == KBCV_FLAG_IDR);
  CHECK("the first frame is still there", kbcv_get(v, 1, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, 1, 1000));
  CHECK("a frame never written is gone", kbcv_get(v, 3, out, sizeof(out), &n, &f) == KBCV_GONE);
  CHECK("sequence 0 is never a frame", kbcv_get(v, 0, out, sizeof(out), &n, &f) == KBCV_GONE);
  CHECK("too big for the reader", kbcv_get(v, 1, out, 999, &n, &f) == KBCV_TOO_BIG && n == 1000);
  fill(buf, 9, 200);
  s = kbcv_put(v, pre, 3, buf, 200, 0, KBCV_FRAME_MAX);
  CHECK("prefix and data are one frame", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && n == 203
        && out[0] == 0 && out[2] == 1 && matches(out + 3, 9, 200));
  CHECK("empty frame refused", kbcv_put(v, 0, 0, buf, 0, 0, KBCV_FRAME_MAX) == 0);
  CHECK("over the caller's limit refused", kbcv_put(v, 0, 0, buf, 300, 0, 299) == 0);
  CHECK("over KBCV_FRAME_MAX refused", kbcv_put(v, 0, 0, buf, KBCV_FRAME_MAX + 1, 0, ~0u) == 0);
  CHECK("a refused frame takes no sequence", v->write_seq == s);
  CHECK("max frame is recorded", v->max_frame == 1000);
}

static void wrapping(void)
{
  unsigned i, s = 0, n, f, at;
  fresh();
  for (i = 0; i < 4; i++) s = put(15000, 0);       /* 60 000 of 65 536 bytes */
  s = put(10000, 0);                                /* does not fit before the end */
  at = kbcv_entry_of(v, s)->pos & (KBCV_ARENA - 1u);
  CHECK("a frame that would cross the end starts at the beginning", at == 0);
  CHECK("... and reads back whole", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && n == 10000 && matches(out, s, 10000));
  CHECK("the frame it overwrote is gone", kbcv_get(v, 1, out, sizeof(out), &n, &f) == KBCV_GONE);
  CHECK("the next ones are still there", kbcv_get(v, 2, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, 2, 15000));
  for (i = 0; i < 3; i++) s = put(15000, 0);
  CHECK("after another lap the old frames are gone", kbcv_get(v, 2, out, sizeof(out), &n, &f) == KBCV_GONE);
  CHECK("... the newest is there", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, s, 15000));
}

static void index_reuse(void)
{
  unsigned i, n, f;
  fresh();
  for (i = 0; i < KBCV_INDEX; i++) put(10, 0);
  CHECK("all of the index readable", kbcv_get(v, 1, out, sizeof(out), &n, &f) == KBCV_OK);
  put(10, 0);
  CHECK("entry reused: the oldest is gone", kbcv_get(v, 1, out, sizeof(out), &n, &f) == KBCV_GONE);
  CHECK("its successor is there", kbcv_get(v, 2, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, 2, 10));
}

/* the writer during a reader's copy */
static void write_lap(void) { unsigned i; for (i = 0; i < 6; i++) put(15000, 0); }
static void write_one_small(void) { put(100, 0); }
static void torn_reads(void)
{
  unsigned s, n, f;
  fresh();
  s = put(15000, 0);
  g_during_copy = write_lap;
  CHECK("overwritten during the copy: not taken", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_GONE);
  g_during_copy = 0;
  fresh();
  s = put(15000, 0);
  g_during_copy = write_one_small;
  CHECK("a write elsewhere during the copy: still good", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, s, 15000));
  g_during_copy = 0;
  /* the entry is being filled (seq 0): not a frame */
  fresh();
  s = put(100, 0);
  kbcv_entry_of(v, s)->seq = 0;
  CHECK("entry in the writer's hands: gone", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_GONE && kbcv_flags_of(v, s) == -1);
}

static void key_frames(void)
{
  unsigned a, b, i;
  fresh();
  put(100, 0);
  a = put(100, KBCV_FLAG_IDR);
  put(100, 0); put(100, 0);
  CHECK("newest key frame", kbcv_newest_idr(v) == a);
  b = put(100, KBCV_FLAG_IDR);
  put(100, 0);
  CHECK("a newer key frame wins", kbcv_newest_idr(v) == b);
  v->gen_first_seq = b + 1u;
  CHECK("not one from before the stream's start", kbcv_newest_idr(v) == 0);
  v->gen_first_seq = 0;
  fresh();
  a = put(15000, KBCV_FLAG_IDR);
  for (i = 0; i < 5; i++) put(15000, 0);
  CHECK("an overwritten key frame does not count", kbcv_newest_idr(v) == 0);
  CHECK("no frames: no key frame", (fresh(), kbcv_newest_idr(v)) == 0);
}

static void counters_wrap(void)
{
  unsigned s, n, f, i;
  fresh();
  v->write_seq = 0xfffffffeu;
  s = put(10, 0);
  CHECK("sequence 0xffffffff", s == 0xffffffffu);
  s = put(10, 0);
  CHECK("then 1, never 0", s == 1u && kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK);
  fresh();
  v->arena_head = 0xffffffffu - 20000u;            /* the byte position wraps at 2^32 */
  for (i = 0; i < 4; i++) s = put(12000, 0);
  CHECK("position past 2^32: newest readable", kbcv_get(v, s, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, s, 12000));
  CHECK("... and the one before", kbcv_get(v, s - 1u, out, sizeof(out), &n, &f) == KBCV_OK && matches(out, s - 1u, 12000));
}

/* ---- two threads ---- */
static volatile int stop;
static unsigned long w_frames, r_ok, r_gone, r_bad;
static void *writer(void *arg)
{
  static unsigned char wb[KBCV_FRAME_MAX];
  unsigned r = 12345;
  (void)arg;
  while (!stop) {
    unsigned n, seq = v->write_seq + 1u;
    r = r * 1103515245u + 12345u; n = 1u + (r >> 8) % 9000u;
    if (!seq) seq = 1u;
    fill(wb, seq, n);
    kbcv_put(v, 0, 0, wb, n, (seq % 7u) ? 0 : KBCV_FLAG_IDR, KBCV_FRAME_MAX);
    w_frames++;
  }
  return 0;
}
static void *reader(void *arg)
{
  static unsigned char rb[KBCV_FRAME_MAX];
  unsigned next = 0;
  (void)arg;
  while (!stop) {
    unsigned head = v->write_seq, n, f;
    if (head - next >= KBCV_INDEX) next = head - 1u;
    while (next != head) {
      unsigned seq = ++next;
      int r = kbcv_get(v, seq, rb, sizeof(rb), &n, &f);
      if (r == KBCV_OK) { if (matches(rb, seq, n) && ((f & KBCV_FLAG_IDR) != 0) == (seq % 7u == 0)) r_ok++; else r_bad++; }
      else r_gone++;
    }
  }
  return 0;
}
static void threads(void)
{
  pthread_t a, b;
  fresh();
  stop = 0;
  pthread_create(&a, 0, writer, 0); pthread_create(&b, 0, reader, 0);
  {
    struct timespec t = { 1, 500000000L };
    nanosleep(&t, 0);
  }
  stop = 1;
  pthread_join(a, 0); pthread_join(b, 0);
  printf("  threads: %lu frames written, %lu read and checked, %lu skipped, %lu wrong\n", w_frames, r_ok, r_gone, r_bad);
  CHECK("no frame the reader accepted was wrong", r_bad == 0);
  CHECK("the reader got frames", r_ok > 1000);
}

int main(void)
{
  static const char *names[] = { "basics", "wrapping", "index reuse", "torn reads", "key frames", "counters wrap", "two threads" };
  void (*tests[])(void) = { basics, wrapping, index_reuse, torn_reads, key_frames, counters_wrap, threads };
  unsigned i;
  v = (struct kbcv *)calloc(1, sizeof(*v));
  for (i = 0; i < sizeof(tests) / sizeof(tests[0]); i++) {
    int before = failed;
    tests[i]();
    printf("%-24s%s\n", names[i], failed == before ? "ok" : "FAILED");
  }
  puts(failed ? "TESTS FAILED" : "all tests passed");
  return failed ? 1 : 0;
}
