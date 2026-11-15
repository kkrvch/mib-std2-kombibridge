/*
 * Android Auto cluster video: SAL (writer, the patched libgal's cluster sink) -> DisplayManager
 * (reader, native/libkb_dm.so) through POSIX shared memory /dev/shmem/aatokombi-cluster-video.
 *
 * Single writer, single reader. Each slot is a sequence lock: the writer zeroes slot.seq, fills
 * the slot, then stores the new sequence in slot.seq and write_seq; the reader copies a slot and
 * keeps the copy only if slot.seq is unchanged afterwards. Access units are Annex-B H.264 with
 * SPS+PPS in front of every IDR (the cluster's decoder needs them there).
 *
 * The writer owns the cluster while it refreshes alive_ms (monotonic ms, at least every 500 ms
 * while the phone's cluster channel is started); the reader gives the cluster back to the stock
 * encoder when alive_ms is older than KBCV_ALIVE_MS. want_idr is the reader's request for a key
 * frame; the writer clears it when it acted on it.
 *
 * Shared by C (native/) and the freestanding C++ shim: fixed-width plain types only.
 * The firmware facts this protocol is built around (DisplayManager's encoder queue in
 * libOpenMAXAL, the 250 000-byte frame limit of the cluster decoder) come from the research
 * published in https://github.com/perkthefox/mst2-cluster-nav (Anton Perk, CC BY-NC-SA 4.0);
 * this file is an independent implementation.
 */
#ifndef KB_CLUSTER_VIDEO_SHM_H
#define KB_CLUSTER_VIDEO_SHM_H

#define KBCV_SHM_NAME   "/aatokombi-cluster-video"
#define KBCV_MAGIC      0x5643424bu   /* "KBCV" little-endian */
#define KBCV_VERSION    3u
#define KBCV_HEADER_BYTES 256u        /* everything before slot[]: fixed, see struct kbcv */
#define KBCV_SLOTS      24u
#define KBCV_SLOT_BYTES 256000u
#define KBCV_AU_MAX     250000u       /* the cluster decoder's frame limit */
#define KBCV_ALIVE_MS   1500u
#define KBCV_FLAG_IDR   1u

typedef struct { int count; unsigned owner; } kbcv_sem_t;   /* QNX sem_t (sync_t) */

struct kbcv_slot {
  volatile unsigned seq;
  unsigned size;
  unsigned flags;
  unsigned reserved;
  unsigned char data[KBCV_SLOT_BYTES];
};

struct kbcv {
  unsigned magic;
  unsigned version;
  volatile unsigned write_seq;     /* newest complete slot sequence; 0 = none yet */
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
  /* first slot sequence of the current generation (set by the writer at the phone's Start): a
   * key frame from before it belongs to the previous stream and must not start the reader */
  volatile unsigned gen_first_seq;
  /* reader: monotonic ms of DisplayManager's last pass through the cluster encoder's reader (it
   * runs while the cluster takes video at all, stock or phone): "the receiver is ready" */
  volatile unsigned rd_ready_ms;
  /* reader: newest slot sequence it has taken from the ring (handed to the cluster or skipped);
   * the writer acknowledges a phone frame only once the reader is past it */
  volatile unsigned rd_seq;
  /* reader: 1 = sets rd_ready_ms / rd_seq (an older libkb_dm.so leaves 0) */
  volatile unsigned rd_caps;
  /* writer, at every Start: 1 = decode mode (libkb_dm.so plays the stream with mm-renderer into
   * the cluster display's map window; DisplayManager's encoder sends it to the AID), 0 = the
   * phone's H.264 is passed through to the AID. Coded size and the crop's top-left corner. */
  volatile unsigned mode;
  unsigned coded_w, coded_h, crop_x, crop_y;
  /*
   * The header is KBCV_HEADER_BYTES long whatever it holds, so slot[] never moves. A new field
   * takes its place from `reserved` (the memory is zero-filled, so 0 must mean "not set / older
   * side"). Bump KBCV_VERSION whenever a field changes its meaning, size or place: the reader
   * refuses any other version (native/src/dm_hook.c kv_open), and the two libraries are installed
   * separately.
   */
  unsigned reserved[33];
  struct kbcv_slot slot[KBCV_SLOTS];
};

/* Compile-time check, C99 and C++ alike: the build fails if the header is not KBCV_HEADER_BYTES. */
typedef char kbcv_header_size_check[
  (sizeof(struct kbcv) - KBCV_SLOTS * sizeof(struct kbcv_slot)) == KBCV_HEADER_BYTES ? 1 : -1];

#endif
