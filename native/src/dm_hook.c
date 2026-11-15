/*
 * libkb_dm.so — LD_PRELOAD library for DisplayManager (tsd.mibstd2.system.displaymanager).
 *
 * While the patched libgal (SAL process) streams Android Auto's cluster video into the shared
 * memory of cluster_video_shm.h, DisplayManager's cluster video reader gets those H.264 access
 * units instead of the ones its own encoder made from the cluster map; everything after that —
 * TsEncoder (MPEG-TS), MostSink, the RingIO to the J5, MOST, the cluster's decoder — is stock.
 * When the phone's stream stops (alive_ms older than KBCV_ALIVE_MS), the reader is back on the
 * stock encoder from its next key frame.
 *
 * Interposed (exported here, so the run-time linker binds the callers' PLT slots to us):
 *   mmfbq_get_and_wait(q, abs_deadline)   libOpenMAXAL.so.1 — only the call that returns into
 *                                          BufferQueueGetAndWait (DisplayManager's reader);
 *                                          every other caller is passed straight through
 *   mmfbq_done(q, e)                       our element is never handed to the real one
 *   RingIO::writeAquire(p, n, partial)     libtsd.common.so — only from MostSink::write: while
 *                                          the phone owns the cluster a full ring is waited for
 *                                          (RING_WAIT_MS) instead of cutting the frame
 *
 * Design follows mst2-cluster-nav's DisplayManager hook (https://github.com/perkthefox/
 * mst2-cluster-nav, native/src/dm/dm.c, Anton Perk, CC BY-NC-SA 4.0): the same seam, the stock
 * frames handed back to the encoder, start at an IDR, the ring wait in MostSink, the call-site
 * checks. Written anew for Android Auto and this repository's shared memory.
 * Firmware facts (re-verified on the 0480 dump):
 *   libOpenMAXAL.so.1 sha256 c31fe983...: mmfbq_get 0x893c, mmfbq_done 0x8b9c,
 *     mmfbq_get_and_wait 0x8e4c; BufferQueueGetAndWait calls it at 0x1ac38, returns to 0x1ac3c
 *     (pop {r4, pc}); queue: head element at +0, mutex +0x10, cond +0x18, flush flag +0x2c;
 *     element: next at +0x18, data at +4, size at +8 (what the reader hands to TsEncoder).
 *   displaymanager sha256 932efd26...: MostSink::write "bl RingIO::writeAquire" at 0x126f74,
 *     returning to "subs sb, r0, #0" at 0x126f78.
 * Any mismatch: nothing is replaced (logged once).
 *
 * Safety: kill switch (file aatokombi-video-disable on any mounted SD card, or
 * /tsd/var/aatokombi/video-off) checked every 2 s; crash guard (two quick restarts while busy
 * switch the hook off for good, see guard_*).
 */
#include "kb_qnx.h"
#include "cluster_video_shm.h"

#define EXPORT __attribute__((visibility("default")))

#define OMX_LIB             "libOpenMAXAL.so.1"
#define OMX_GET             0x893cu
#define OMX_DONE            0x8b9cu
#define OMX_GET_AND_WAIT    0x8e4cu
#define OMX_READER_RETURN   0x1ac3cu
#define ARM_POP_R4_PC       0xe8bd8010u

#define COMMON_LIB          "libtsd.common.so"
#define RING_ACQUIRE        "_ZN3tsd6common9messaging6RingIO11writeAquireERPvRjb"
#define MOST_SINK_CALL      0x126f74u
#define MOST_SINK_RETURN    0x126f78u
#define ARM_BL_ACQUIRE      0xebff9ffcu
#define ARM_SUBS_SB_R0      0xe2509000u
#define RING_WAIT_MS        300u
#define IDR_FALLBACK_MS     1000u

#define WAIT_SLICE_MS       50
#define SWITCH_CHECK_MS     2000u
#define LOG_PATH            "/dev/shmem/aatokombi-dm.log"
#define LOG_MAX             65536
#define VAR_DIR             "/tsd/var/aatokombi"
#define OFF_FILE            VAR_DIR "/video-off"
#define BUSY_FILE           VAR_DIR "/dm-busy"
#define STRIKES_FILE        VAR_DIR "/dm-strikes"
#define KILL_NAME           "aatokombi-video-disable"

struct element { uint32_t w0; uint8_t *data; uint32_t size; uint32_t rest[5]; };

static void *(*real_get_and_wait)(void *, const struct timespec *);
static void *(*real_get)(void *);
static int (*real_done)(void *, void *);
static int (*real_acquire)(void *, void **, unsigned *, int);
static uintptr_t reader_return;          /* 0: unknown build, nothing replaced */
static int most_sink_ok = -1;

static struct kbcv *kv;
static unsigned kv_last_try;
static int kv_version_logged;
static void *cluster_queue;
static struct element ours;
static uint8_t frame[KBCV_SLOT_BYTES];
static unsigned next_seq, generation;
static int phone, need_idr, wait_stock_idr, ring_stalled;
/* The AID's decoder shows the first picture after the stock stream broken (purple, smeared) until
 * the next key frame, even when that first picture IS a key frame (car 2026-11-04). So the first
 * phone IDR after each switch from the stock map goes to the encoder twice: the second copy is
 * then the reference for the phone's P-frames. Off while VAR_DIR/no-dup-idr exists. */
#define NO_DUP_FILE         VAR_DIR "/no-dup-idr"
static int dup_pending, dup_now, frame_idr;
static uint32_t frame_size;
static unsigned idr_wait_ms;
static int disabled_for_good;
static unsigned switch_checked, switch_off;
static unsigned busy_written;
static unsigned ring_wait_max, ring_report_ms;

/* ---------- small helpers ---------- */
static unsigned now_ms(void)
{
    struct timespec t;
    clock_gettime(CLOCK_MONOTONIC, &t);
    return (unsigned)t.tv_sec * 1000u + (unsigned)t.tv_nsec / 1000000u;
}
static long now_rt(void)
{
    struct timespec t;
    clock_gettime(CLOCK_REALTIME, &t);
    return t.tv_sec;
}
static int exists(const char *path)
{
    int fd = open(path, O_RDONLY);
    if (fd < 0) return 0;
    close(fd);
    return 1;
}
static char *put(char *o, const char *s) { while (*s) *o++ = *s++; return o; }
static char *put_u(char *o, unsigned v)
{
    char t[12]; int n = 0;
    do { t[n++] = (char)('0' + v % 10); v /= 10; } while (v);
    while (n) *o++ = t[--n];
    return o;
}
static void log_line(const char *a, unsigned v, int with_v)
{
    char b[400], *o = b;
    int fd = open(LOG_PATH, O_WRONLY | O_CREAT | O_APPEND, 0666);
    if (fd < 0) return;
    if (lseek(fd, 0, SEEK_END) > LOG_MAX) { close(fd); return; }
    o = put_u(o, now_ms()); o = put(o, " dm: "); o = put(o, a);
    if (with_v) { *o++ = ' '; o = put_u(o, v); }
    *o++ = '\n';
    write(fd, b, (size_t)(o - b));
    close(fd);
}
#define LOG(s) log_line(s, 0, 0)
#define LOGV(s, v) log_line(s, v, 1)

static unsigned read_u(const char *path)
{
    char t[16]; int fd = open(path, O_RDONLY); ssize_t n; unsigned v = 0, i;
    if (fd < 0) return 0;
    n = read(fd, t, sizeof(t) - 1);
    close(fd);
    for (i = 0; n > 0 && i < (unsigned)n && t[i] >= '0' && t[i] <= '9'; i++) v = v * 10 + (unsigned)(t[i] - '0');
    return v;
}
static void write_u(const char *path, unsigned v)
{
    char t[16], *o = put_u(t, v);
    int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (fd < 0) return;
    *o++ = '\n';
    write(fd, t, (size_t)(o - t));
    close(fd);
}

/* ---------- kill switch and crash guard ---------- */
static int switched_off(void)
{
    unsigned now = now_ms();
    if (disabled_for_good) return 1;
    if (!switch_checked || now - switch_checked >= SWITCH_CHECK_MS) {
        char p[48]; int i, off = exists(OFF_FILE);
        for (i = 0; !off && i < 10; i++) {
            char *o = put(p, "/media/mp00"); *o++ = (char)('0' + i); *o++ = '/';
            o = put(o, KILL_NAME); *o = 0;
            off = exists(p);
        }
        if (off != (int)switch_off) LOG(off ? "switched off (kill switch)" : "switched on");
        switch_off = (unsigned)off;
        switch_checked = now ? now : 1;
    }
    return (int)switch_off;
}

/*
 * busy file = realtime seconds of the last minute in which phone frames replaced stock ones.
 * Found at start and younger than 180 s: DisplayManager died while we were working and the unit
 * came straight back. Two such starts in a row: off until video-off is removed by reinstalling.
 */
static void guard_init(void)
{
    if (exists(BUSY_FILE)) {
        long t = (long)read_u(BUSY_FILE);
        if (t && now_rt() - t < 180) {
            unsigned s = read_u(STRIKES_FILE) + 1;
            write_u(STRIKES_FILE, s);
            LOGV("restart while busy, strike", s);
            if (s >= 2) {
                write_u(OFF_FILE, 1);
                disabled_for_good = 1;
                LOG("off for good after repeated crashes; reinstall to enable");
            }
        } else {
            write_u(STRIKES_FILE, 0);
        }
        unlink(BUSY_FILE);
    }
}
static void guard_busy(int on)
{
    if (on) { write_u(BUSY_FILE, (unsigned)now_rt()); busy_written = now_ms(); }
    else { unlink(BUSY_FILE); write_u(STRIKES_FILE, 0); busy_written = 0; }
}
static void guard_tick(void)
{
    if (busy_written && now_ms() - busy_written >= 60000u) guard_busy(1);
}

/* ---------- resolution and build checks ---------- */
static void resolve(void)
{
    void *h;
    uintptr_t base;
    if (real_done) return;
    h = dlopen(OMX_LIB, RTLD_LAZY);
    real_get_and_wait = h ? (void *(*)(void *, const struct timespec *))dlsym(h, "mmfbq_get_and_wait") : 0;
    real_get = h ? (void *(*)(void *))dlsym(h, "mmfbq_get") : 0;
    real_done = h ? (int (*)(void *, void *))dlsym(h, "mmfbq_done") : 0;
    if (!real_get_and_wait || !real_get || !real_done) {
        LOG("libOpenMAXAL symbols not found");
        for (;;) usleep(1000000);                    /* cannot pass through: never return junk */
    }
    base = (uintptr_t)real_get_and_wait - OMX_GET_AND_WAIT;
    if ((uintptr_t)real_get == base + OMX_GET && (uintptr_t)real_done == base + OMX_DONE &&
        *(const uint32_t *)(base + OMX_READER_RETURN) == ARM_POP_R4_PC &&
        (*(const uint32_t *)(base + OMX_READER_RETURN - 4) & 0x0f000000u) == 0x0b000000u) {
        reader_return = base + OMX_READER_RETURN;
        LOG("libOpenMAXAL 0480 layout confirmed");
    } else {
        LOG("libOpenMAXAL build differs: cluster video stays stock");
    }
}

static int kv_open(void)
{
    unsigned now = now_ms();
    int fd;
    void *p;
    if (kv) return 1;
    if (kv_last_try && now - kv_last_try < 1000u) return 0;
    kv_last_try = now ? now : 1;
    fd = shm_open(KBCV_SHM_NAME, O_RDWR, 0);
    if (fd < 0) return 0;
    p = mmap(NULL, sizeof(struct kbcv), PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED || !p) return 0;
    if (((struct kbcv *)p)->magic != KBCV_MAGIC || ((struct kbcv *)p)->version != KBCV_VERSION) {
        /* Not ours (yet): the libgal sets the magic last, or it is built for another layout.
         * Unmap before the next try - this runs once a second, and the mapping is ~6 MB. */
        unsigned magic = ((struct kbcv *)p)->magic, version = ((struct kbcv *)p)->version;
        munmap(p, sizeof(struct kbcv));
        if (magic == KBCV_MAGIC && !kv_version_logged) {
            kv_version_logged = 1;
            LOGV("cluster video memory has another version (libgal and libkb_dm.so differ): stock map only, version", version);
        }
        return 0;
    }
    kv = (struct kbcv *)p;
    LOG("cluster video memory found");
    return 1;
}

/* pass-through only: in decode mode (kv->mode 1, dec_player.c) the stock encoder path stays */
static int phone_alive(void)
{
    unsigned a;
    if (switched_off() || !kv_open() || kv->mode == 1) return 0;
    a = kv->alive_ms;
    return a && kv->write_seq && now_ms() - a < KBCV_ALIVE_MS;
}

/*
 * The stock encoder's SPS/PPS (everything before its first slice NAL), for the cluster geometry:
 * the first one and every one that differs from the last go to STOCK_SPS_FILE (overwritten) and
 * as hex into the log, at most STOCK_SPS_LOGS times.
 */
#define STOCK_SPS_FILE "/dev/shmem/aatokombi-stock-sps.bin"
#define STOCK_SPS_LOGS 8
static uint8_t last_sps[128];
static unsigned last_sps_len, sps_logged;
static void save_stock_sps(const uint8_t *p, uint32_t n)
{
    uint32_t i, end = n < sizeof(last_sps) ? n : sizeof(last_sps);
    for (i = 4; i + 4 < end; i++)                       /* stop at the first slice NAL */
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1 &&
            ((p[i + 3] & 0x1fu) == 1 || (p[i + 3] & 0x1fu) == 5)) { end = i > 0 && p[i - 1] == 0 ? i - 1 : i; break; }
    if (end == last_sps_len) {
        for (i = 0; i < end && p[i] == last_sps[i]; i++) ;
        if (i == end) return;
    }
    memcpy(last_sps, p, end);
    last_sps_len = end;
    {
        int fd = open(STOCK_SPS_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0666);
        if (fd >= 0) { write(fd, p, end); close(fd); }
    }
    if (sps_logged < STOCK_SPS_LOGS) {
        static const char h[] = "0123456789abcdef";
        char b[300], *o = b;
        sps_logged++;
        o = put(o, "stock SPS/PPS ");
        for (i = 0; i < end && o < b + sizeof(b) - 4; i++) { *o++ = h[p[i] >> 4]; *o++ = h[p[i] & 15]; }
        *o = 0;
        LOG(b);
    }
}

static int has_nal(const uint8_t *p, uint32_t n, unsigned type)
{
    uint32_t i;
    for (i = 0; i + 3 < n; i++)
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1 && (p[i + 3] & 0x1fu) == type) return 1;
    return 0;
}

/*
 * Resynchronise on the newest key frame still in the ring, not on the newest frame: the phone
 * sends an IDR only when its stream starts (and then about once a minute), so jumping to the head
 * threw away the starting IDR whenever the reader came a few frames late, and the AA map then
 * waited a minute or two for the next one (car, 2026-10-29). Returns the sequence BEFORE that IDR.
 */
static unsigned resync_seq(unsigned head)
{
    unsigned k, first = kv->gen_first_seq;
    for (k = 0; k + 1 < KBCV_SLOTS && k < head; k++) {
        unsigned seq = head - k;
        if (first && seq < first) break;               /* older stream: never start on its IDR */
        struct kbcv_slot *s = &kv->slot[seq % KBCV_SLOTS];
        if (s->seq == seq && (s->flags & KBCV_FLAG_IDR)) {
            if (k) LOGV("resync on a key frame, frames back", k);
            return seq - 1;
        }
    }
    return head;
}

/*
 * A key frame of the phone still in the ring. The switch from the stock map to the phone waits for
 * one and keeps handing over the stock frames until then: switching first and waiting for the
 * phone's next IDR (it sends one only every ~20 s) left the AID without any picture meanwhile,
 * showing "map activation" (car, 2026-11-01).
 */
static int ring_has_idr(void)
{
    unsigned head = kv->write_seq, k, first = kv->gen_first_seq;
    for (k = 0; k + 1 < KBCV_SLOTS && k < head; k++) {
        unsigned seq = head - k;
        if (first && seq < first) break;               /* older stream */
        struct kbcv_slot *s = &kv->slot[seq % KBCV_SLOTS];
        if (s->seq == seq && (s->flags & KBCV_FLAG_IDR)) return 1;
    }
    return 0;
}
static int idr_wait_logged;
static unsigned flush_ms, flush_count;

/* Next phone access unit into `frame`; 0 if none ready. */
static uint32_t take_frame(void)
{
    unsigned head = kv->write_seq;
    if (kv->generation != generation || head - next_seq >= KBCV_SLOTS) {
        if (kv->generation == generation) kv->rd_skipped++;
        generation = kv->generation;
        next_seq = resync_seq(head);
        need_idr = 1;
    }
    while (next_seq != head) {
        unsigned seq = ++next_seq, size;
        struct kbcv_slot *s = &kv->slot[seq % KBCV_SLOTS];
        kv->rd_seq = seq;                                  /* taken or skipped: the libgal may ack it */
        if (s->seq != seq) { need_idr = 1; continue; }
        size = s->size;
        if (size > sizeof(frame)) continue;
        memcpy(frame, s->data, size);
        __sync_synchronize();
        if (s->seq != seq) { need_idr = 1; continue; }
        if (need_idr && !(s->flags & KBCV_FLAG_IDR)) {
            kv->want_idr = 1;
            if (!idr_wait_ms) { idr_wait_ms = now_ms() | 1u; LOG("waiting for a key frame"); }
            continue;
        }
        if (need_idr && idr_wait_ms) { LOGV("key frame after ms", now_ms() - idr_wait_ms); idr_wait_ms = 0; }
        need_idr = 0;
        frame_idr = (s->flags & KBCV_FLAG_IDR) ? 1 : 0;
        kv->rd_frames++;
        return size;
    }
    if (need_idr) kv->want_idr = 1;
    return 0;
}

static void wait_frame(unsigned head)
{
    struct timespec t;
    if (!kv->sem_ok) { usleep(5000); return; }
    clock_gettime(CLOCK_MONOTONIC, &t);
    t.tv_nsec += WAIT_SLICE_MS * 1000000L;
    if (t.tv_nsec >= 1000000000L) { t.tv_sec++; t.tv_nsec -= 1000000000L; }
    kv->waiting = 1;
    __sync_synchronize();
    if (kv->write_seq == head) sem_timedwait_monotonic(&kv->frame_ready, &t);
    kv->waiting = 0;
}

static int past(const struct timespec *d)
{
    struct timespec n;
    if (!d) return 0;
    clock_gettime(CLOCK_REALTIME, &n);
    return n.tv_sec > d->tv_sec || (n.tv_sec == d->tv_sec && n.tv_nsec >= d->tv_nsec);
}

#include "dec_player.c"
#include "keys_j5.c"

static int dec_started;
EXPORT void *mmfbq_get_and_wait(void *q, const struct timespec *deadline)
{
    resolve();
    if (!dec_started) { dec_started = 1; dec_start(); keys_start(); }   /* decode-mode and J5 key threads */
    if (!reader_return || (uintptr_t)__builtin_return_address(0) != reader_return)
        return real_get_and_wait(q, deadline);

    if (q && q == cluster_queue) cluster_rd_ms = now_ms() | 1u;
    if (q && q == cluster_queue && !switched_off() && kv_open()) {
        kv->rd_caps = 1;
        kv->rd_ready_ms = now_ms() | 1u;                   /* the cluster takes video: phone may stream */
    }
    if (q && q == cluster_queue && phone_alive() && !phone && !ring_has_idr()) {
        if (!idr_wait_logged) { idr_wait_logged = 1; LOG("phone video waits for a key frame: stock map meanwhile"); }
        kv->want_idr = 1;                                  /* the libgal may restart the stream */
    } else if (q && q == cluster_queue && phone_alive()) {
        if (!phone) {
            idr_wait_logged = 0;
            phone = 1; need_idr = 1; guard_busy(1);
            dup_pending = !exists(NO_DUP_FILE); dup_now = 0;
            if (kv) next_seq = resync_seq(kv->write_seq);   /* start at the newest IDR in the ring */
            if (kv) kv->rd_state = 1;
            LOG("cluster: Android Auto video");
        }
        guard_tick();
        while (!((volatile int *)q)[0xb] && !past(deadline) && phone_alive()) {
            void *e;
            unsigned head, size;
            while ((e = real_get(q)) != NULL) real_done(q, e);   /* stock frame back, unused */
            if (dup_now) {                                 /* the same IDR once more */
                dup_now = 0;
                ours.data = frame; ours.size = frame_size; kv->rd_alive_ms = now_ms() | 1u;
                LOG("first key frame sent twice");
                return &ours;
            }
            head = kv->write_seq;
            size = take_frame();
            if (size) {
                if (dup_pending && frame_idr) { dup_pending = 0; dup_now = 1; frame_size = size; }
                ours.data = frame; ours.size = size; kv->rd_alive_ms = now_ms() | 1u; return &ours;
            }
            /* lost the picture (stream restart, frames skipped): back to the stock map until the
             * phone's next key frame rather than leaving the AID without frames */
            if (need_idr && idr_wait_ms && now_ms() - idr_wait_ms >= IDR_FALLBACK_MS) break;
            /* still the cluster's reader, also while a still map sends no frames (car
             * 2026-11-06: without this the libgal took focus away after 5 s, 26 times) */
            kv->rd_ready_ms = now_ms() | 1u;
            cluster_rd_ms = kv->rd_ready_ms;
            wait_frame(head);
        }
        if (past(deadline) && phone_alive() && !(need_idr && idr_wait_ms && now_ms() - idr_wait_ms >= IDR_FALLBACK_MS))
            return NULL;
        /*
         * DisplayManager flushed the cluster queue while the phone's video plays. Car 2026-11-02
         * (1280x720): every switch to the phone ended ~0.2 s later, then the stock path waited for
         * the stock encoder's key frame (~1 s) and the phone's IDR had left the ring by then, so
         * the AA map was gone for 30 s. Now: hand the flush back, stay on the phone and restart at
         * the newest IDR still in the ring. Three flushes within 5 s: give up to the stock map.
         */
        if (((volatile int *)q)[0xb] && phone_alive()) {
            unsigned now = now_ms();
            if (!flush_ms || now - flush_ms > 5000u) { flush_ms = now | 1u; flush_count = 0; }
            flush_count++;
            LOGV("cluster queue flushed while on the phone, count", flush_count);
            if (flush_count < 3 && ring_has_idr()) {
                next_seq = resync_seq(kv->write_seq);
                need_idr = 1;
                return NULL;
            }
        }
        if (!phone_alive()) LOG("phone video stopped (writer heartbeat gone)");
        else if (need_idr && idr_wait_ms && now_ms() - idr_wait_ms >= IDR_FALLBACK_MS) LOG("no key frame for 1 s");
    }
    if (phone) {
        phone = 0; wait_stock_idr = 1; guard_busy(0);
        dup_pending = 0; dup_now = 0;
        if (kv) kv->rd_alive_ms = 0;
        if (kv) kv->rd_state = 0;
        LOG("cluster: stock map");
    }
    for (;;) {
        struct element *el = (struct element *)real_get_and_wait(q, deadline);
        if (!el) return el;
        if (has_nal(el->data, el->size, 7)) {               /* SPS: the cluster encoder's queue */
            save_stock_sps(el->data, el->size);
            if (cluster_queue != q) { cluster_queue = q; LOG("cluster encoder queue found"); }
            wait_stock_idr = 0;
        }
        if (q == cluster_queue && wait_stock_idr && !has_nal(el->data, el->size, 5)) {
            real_done(q, el);                                /* P-frame without its reference */
            continue;
        }
        wait_stock_idr = 0;
        return el;
    }
}

EXPORT int mmfbq_done(void *q, void *e)
{
    resolve();
    if (e == &ours) return 0;
    return real_done(q, e);
}

int ring_acquire(void *ring, void **p, unsigned *n, int partial) __asm__(RING_ACQUIRE);
EXPORT int ring_acquire(void *ring, void **p, unsigned *n, int partial)
{
    uintptr_t from = (uintptr_t)__builtin_return_address(0);
    unsigned want = *n, start, now;
    int r;
    if (!real_acquire) {
        void *h = dlopen(COMMON_LIB, RTLD_LAZY);
        real_acquire = h ? (int (*)(void *, void **, unsigned *, int))dlsym(h, RING_ACQUIRE) : 0;
        if (!real_acquire) { LOG("RingIO::writeAquire not found"); for (;;) usleep(1000000); }
    }
    r = real_acquire(ring, p, n, partial);
    if (r || !phone || from != MOST_SINK_RETURN) { ring_stalled = ring_stalled && !r; return r; }
    if (most_sink_ok < 0) {
        most_sink_ok = *(const uint32_t *)MOST_SINK_CALL == ARM_BL_ACQUIRE &&
                       *(const uint32_t *)MOST_SINK_RETURN == ARM_SUBS_SB_R0;
        if (!most_sink_ok) LOG("MostSink call site differs: ring not waited for");
    }
    if (!most_sink_ok || ring_stalled) return r;
    start = now_ms();
    do {
        usleep(2000);
        *n = want;
        r = real_acquire(ring, p, n, partial);
        now = now_ms();
    } while (!r && now - start < RING_WAIT_MS);
    if (kv) kv->rd_ring_waits++;
    if (now - start > ring_wait_max) ring_wait_max = now - start;
    if (now - ring_report_ms >= 10000u) {           /* as mst2-cluster-nav: one line per 10 s */
        ring_report_ms = now;
        LOGV("MOST ring full, longest wait ms", ring_wait_max);
        ring_wait_max = 0;
    }
    if (!r) {
        ring_stalled = 1; need_idr = 1;
        if (kv) { kv->rd_ring_lost++; kv->want_idr = 1; }
    }
    return r;
}

EXPORT void kb_dm_init(void)
{
    LOG("loaded");
    guard_init();
}
