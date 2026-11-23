/*
 * Android Auto cluster video: SAL (writer, the patched libgal's cluster sink) -> DisplayManager
 * (reader, native/libkb_dm.so) through POSIX shared memory /dev/shmem/aatokombi-cluster-video.
 *
 * Single writer, single reader. Since version 4 the frames go into a BYTE ring (arena) of
 * KBCV_ARENA bytes, each frame contiguous, with an index of the last KBCV_INDEX frames. Before,
 * every frame took a fixed 256 000-byte slot: the phone's 1280x720 stream averages ~19 KB a
 * frame, so 93 % of the 6.1 MB ring was empty, and its key frames (up to 281 453 bytes on the
 * car, 2026-11-15) did not fit at all and were dropped. Now 4 MB hold a few seconds of video.
 *
 * Writer (kbcv_put): reserves the bytes - arena_head moves past them BEFORE they are written - then
 * copies the frame and publishes its index entry with a sequence lock (entry.seq is 0 while it is
 * filled). Reader (kbcv_get): takes the entry, copies the bytes out, and keeps the copy only if the
 * entry still names the same sequence AND arena_head has not moved more than KBCV_ARENA past the
 * frame's start (else the writer has begun to overwrite it). All positions are logical byte
 * counts, wrapping at 2^32; KBCV_ARENA is a power of two, so "position mod KBCV_ARENA" is the
 * place in the arena across that wrap too. A frame that would run past the arena's end starts at
 * its beginning instead (the skipped tail counts as written).
 *
 * Access units are Annex-B H.264 with SPS+PPS in front of every IDR (the decoders need them).
 *
 * The writer owns the cluster while it refreshes alive_ms (monotonic ms, at least every 500 ms
 * while the phone's cluster channel is started); the reader gives the cluster back to the stock
 * encoder when alive_ms is older than KBCV_ALIVE_MS. want_idr is the reader's request for a key
 * frame; the writer clears it when it acted on it.
 *
 * Shared by C (native/) and the freestanding C++ shim: plain types only, no library calls (the
 * includer may define KBCV_COPY(dst, src, n) to use its memcpy). tests/ring runs this file on the
 * host. The firmware facts this protocol is built around (DisplayManager's encoder queue in
 * libOpenMAXAL, the 250 000-byte frame limit of the cluster decoder) come from the research
 * published in https://github.com/perkthefox/mst2-cluster-nav (Anton Perk, CC BY-NC-SA 4.0);
 * this file is an independent implementation.
 */
#ifndef KB_CLUSTER_VIDEO_SHM_H
#define KB_CLUSTER_VIDEO_SHM_H

#define KBCV_SHM_NAME     "/aatokombi-cluster-video"
#define KBCV_MAGIC        0x5643424bu   /* "KBCV" little-endian */
#define KBCV_VERSION      4u
#define KBCV_HEADER_BYTES 256u          /* everything before index[]: fixed, see struct kbcv */
#ifndef KBCV_ARENA
#define KBCV_ARENA        (4u << 20)    /* bytes of video; a power of two */
#endif
#ifndef KBCV_INDEX
#define KBCV_INDEX        512u          /* frames the index remembers (~17 s at 30 fps) */
#endif
#ifndef KBCV_FRAME_MAX
#define KBCV_FRAME_MAX    (512u << 10)  /* largest frame the ring takes (decode mode) */
#endif
#define KBCV_AU_MAX       250000u       /* the cluster decoder's frame limit (pass-through mode) */
#define KBCV_ALIVE_MS     1500u
#define KBCV_FLAG_IDR     1u

typedef struct { int count; unsigned owner; } kbcv_sem_t;   /* QNX sem_t (sync_t) */

struct kbcv_entry {
  volatile unsigned seq;           /* 0 while the writer fills the entry */
  unsigned pos;                    /* logical arena position of the first byte */
  unsigned size;
  unsigned flags;
};

struct kbcv {
  unsigned magic;
  unsigned version;
  volatile unsigned write_seq;     /* newest complete frame's sequence; 0 = none yet */
  volatile unsigned generation;    /* bumped on every (re)start of the phone's stream */
  volatile unsigned alive_ms;      /* writer's heartbeat while the stream is started; 0 = off */
  volatile unsigned want_idr;      /* reader -> writer */
  volatile unsigned waiting;       /* reader sleeps on frame_ready */
  volatile unsigned sem_ok;
  kbcv_sem_t frame_ready;
  unsigned width, height;          /* visible picture after the SPS crop */
  /* diagnostics, writer side */
  volatile unsigned frames, idr, dropped_big, idr_requests;
  /* diagnostics, reader side */
  volatile unsigned rd_frames, rd_skipped, rd_ring_waits, rd_ring_lost, rd_state;
  /* reader heartbeat: monotonic ms of the last pass in which DisplayManager handed phone frames
   * to the cluster (0 = not replacing). The HMI hides the stock cluster map only while it is fresh. */
  volatile unsigned rd_alive_ms;
  /* first frame sequence of the current generation (set by the writer at the phone's Start): a
   * key frame from before it belongs to the previous stream and must not start the reader */
  volatile unsigned gen_first_seq;
  /* reader: monotonic ms of DisplayManager's last pass through the cluster encoder's reader (it
   * runs while the cluster takes video at all, stock or phone): "the receiver is ready" */
  volatile unsigned rd_ready_ms;
  /* reader: newest frame sequence it has taken from the ring (handed on or skipped); the writer
   * acknowledges a phone frame only once the reader is past it */
  volatile unsigned rd_seq;
  /* reader: 1 = sets rd_ready_ms / rd_seq (an older libkb_dm.so leaves 0) */
  volatile unsigned rd_caps;
  /* writer, at every Start: 1 = decode mode (libkb_dm.so plays the stream with mm-renderer into
   * the cluster display's map window; DisplayManager's encoder sends it to the AID), 0 = the
   * phone's H.264 is passed through to the AID. Coded size and the crop's top-left corner. */
  volatile unsigned mode;
  unsigned coded_w, coded_h, crop_x, crop_y;
  /* writer: logical arena position past the last reserved byte (moves BEFORE the bytes are written) */
  volatile unsigned arena_head;
  /* diagnostics, writer side: the largest frame put into the ring */
  volatile unsigned max_frame;
  /*
   * The header is KBCV_HEADER_BYTES long whatever it holds, so index[] never moves. A new field
   * takes its place from `reserved` (the memory is zero-filled, so 0 must mean "not set / older
   * side"). Bump KBCV_VERSION whenever a field changes its meaning, size or place: the reader
   * refuses any other version (native/src/dm_hook.c kv_open), and the two libraries are installed
   * separately.
   */
  unsigned reserved[31];
  struct kbcv_entry index[KBCV_INDEX];
  unsigned char arena[KBCV_ARENA];
};

/* Compile-time checks, C99 and C++ alike: the header is KBCV_HEADER_BYTES, the arena a power of
 * two, and the largest frame fits several times. */
typedef char kbcv_header_size_check[
  (sizeof(struct kbcv) - KBCV_INDEX * sizeof(struct kbcv_entry) - KBCV_ARENA) == KBCV_HEADER_BYTES ? 1 : -1];
typedef char kbcv_arena_check[((KBCV_ARENA & (KBCV_ARENA - 1u)) == 0 && KBCV_FRAME_MAX * 4u <= KBCV_ARENA) ? 1 : -1];

/* ---------- the ring ------------------------------------------------------------------------ */
/* each side uses only half of these: no "unused function" warnings for the other half */
#define KBCV_FN static __attribute__((unused))
#ifndef KBCV_COPY
KBCV_FN void kbcv_copy(unsigned char *d, const unsigned char *s, unsigned n) { while (n--) *d++ = *s++; }
#define KBCV_COPY(d, s, n) kbcv_copy((unsigned char *)(d), (const unsigned char *)(s), (n))
#endif

KBCV_FN inline struct kbcv_entry *kbcv_entry_of(struct kbcv *v, unsigned seq)
{
  return &v->index[seq % KBCV_INDEX];
}

/* Fresh memory, or memory of another version: nothing in the ring counts. */
KBCV_FN void kbcv_ring_reset(struct kbcv *v)
{
  unsigned i;
  for (i = 0; i < KBCV_INDEX; i++) v->index[i].seq = 0;
  v->write_seq = 0; v->gen_first_seq = 0; v->arena_head = 0; v->rd_seq = 0;
  __sync_synchronize();
}

/*
 * Writer: one frame = pre (pn bytes, may be 0) followed by data (n bytes). Returns its sequence,
 * or 0 when the whole is empty or larger than max (and KBCV_FRAME_MAX).
 */
KBCV_FN unsigned kbcv_put(struct kbcv *v, const unsigned char *pre, unsigned pn,
                         const unsigned char *data, unsigned n, unsigned flags, unsigned max)
{
  unsigned total = pn + n, seq, pos, at;
  struct kbcv_entry *e;
  if (!total || total < n || total > max || total > KBCV_FRAME_MAX) return 0;
  seq = v->write_seq + 1u; if (!seq) seq = 1u;
  e = kbcv_entry_of(v, seq);
  e->seq = 0;
  pos = v->arena_head;
  at = pos & (KBCV_ARENA - 1u);
  if (at + total > KBCV_ARENA) { pos += KBCV_ARENA - at; at = 0; }   /* no frame across the end */
  __sync_synchronize();
  v->arena_head = pos + total;                /* reserve first: readers of what is overwritten see it */
  __sync_synchronize();
  if (pn) KBCV_COPY(v->arena + at, pre, pn);
  if (n) KBCV_COPY(v->arena + at + pn, data, n);
  e->pos = pos; e->size = total; e->flags = flags;
  __sync_synchronize();
  e->seq = seq;
  v->write_seq = seq;
  if (total > v->max_frame) v->max_frame = total;
  __sync_synchronize();
  return seq;
}

/* Whether the bytes of a frame starting at pos are still those the writer put there. */
KBCV_FN inline int kbcv_intact(struct kbcv *v, unsigned pos)
{
  return (unsigned)(v->arena_head - pos) <= KBCV_ARENA;
}

#define KBCV_GONE    0     /* never there, overwritten, or being written: skip it */
#define KBCV_OK      1
#define KBCV_TOO_BIG (-1)  /* larger than the caller's buffer (size in *size) */

/* Reader: frame seq into dst (cap bytes). *size / *flags are set on KBCV_OK (size also on TOO_BIG). */
KBCV_FN int kbcv_get(struct kbcv *v, unsigned seq, unsigned char *dst, unsigned cap,
                    unsigned *size, unsigned *flags)
{
  struct kbcv_entry *e = kbcv_entry_of(v, seq);
  unsigned pos, n, f;
  if (!seq || e->seq != seq) return KBCV_GONE;
  __sync_synchronize();
  pos = e->pos; n = e->size; f = e->flags;
  __sync_synchronize();
  if (e->seq != seq || !kbcv_intact(v, pos) || n > KBCV_FRAME_MAX) return KBCV_GONE;
  if (size) *size = n;
  if (n > cap) return KBCV_TOO_BIG;
  KBCV_COPY(dst, v->arena + (pos & (KBCV_ARENA - 1u)), n);
  __sync_synchronize();
  if (e->seq != seq || !kbcv_intact(v, pos)) return KBCV_GONE;
  if (flags) *flags = f;
  return KBCV_OK;
}

/* Reader: the flags of frame seq while it is still in the ring, -1 when it is not. No copy. */
KBCV_FN int kbcv_flags_of(struct kbcv *v, unsigned seq)
{
  struct kbcv_entry *e = kbcv_entry_of(v, seq);
  unsigned pos, f;
  if (!seq || e->seq != seq) return -1;
  __sync_synchronize();
  pos = e->pos; f = e->flags;
  __sync_synchronize();
  if (e->seq != seq || !kbcv_intact(v, pos)) return -1;
  return (int)f;
}

/* Reader: the newest key frame of the current stream still in the ring (its sequence), 0 = none. */
KBCV_FN unsigned kbcv_newest_idr(struct kbcv *v)
{
  unsigned head = v->write_seq, first = v->gen_first_seq, k;
  for (k = 0; k + 1u < KBCV_INDEX && k < head; k++) {
    unsigned seq = head - k;
    int f;
    if (first && seq < first) break;          /* older stream: never start on its key frame */
    f = kbcv_flags_of(v, seq);
    if (f < 0) break;                         /* older ones are gone too */
    if (f & KBCV_FLAG_IDR) return seq;
  }
  return 0;
}

#endif
