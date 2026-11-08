/*
 * Decode mode (default since 2026-11-08, car 2026-11-07: full-screen AA map on the AID).
 * The libgal (mode 1 in the shared memory) asks the phone for 1280x720 and crops its SPS to the
 * 1010x376 middle. Here, in DisplayManager, the stream is played with mm-renderer - the way the SAL
 * plays Android Auto's main video - into a window that DisplayManager manages as the cluster map:
 * group "DisplayManagerWindowGroup", id "DispId[DISPLAYABLE_MAP]Clone1" (DisplayManager decodes
 * "DispId[X]" into displayable X and "CloneN" into display N; the Navi's own map windows are
 * "DispId[DISPLAYABLE_MAP]" / "...Clone1" (DISPLAYID_CLUSTER) / "...Clone2"). Ours takes the
 * cluster map's place while it exists; the stock map comes back when it closes (car 2026-11-07).
 * DisplayManager's stock encoder encodes the whole composed cluster display, so the AID gets the
 * AA map in the stock stream: no pass-through, no key-frame trouble on the AID.
 * Optional /tsd/var/aatokombi/decode-window: "x y w h" of the window on the cluster display
 * (default 0 0 and the cropped size), "AVC1" (FourCC), "junction" (DISPLAYABLE_MAP_JUNCTION_VIEW).
 *
 * Reverse engineered from the SAL (tsd.mibstd2.sal.main, tsd::sal::appconnect::video
 * H264VideoPlayer): mmr_connect("/pps/services/multimedia/renderer/control"), a context with
 * "prefetch_mode"="-1", output "screen:/?wingrp=..&winid=..&dstx..&dsth.." type "video", input
 * "mdevpush:/dev/name/local/mdev?fourcc=H264&video.frame_rate=..&video.depth=16&video.width=..
 * &video.height=..&video.duration=0" type "track" with "VideoDecoderLowLatencyMode"="1", then
 * name_open() of that mdev path and MsgSendv of: a 24-byte header {u16 0, u16 0, 12 x 0,
 * u32 kind, u32 size} + payload; kind 2 = format {mtype 2, FourCC 'H264' big-endian, w, h,
 * depth 16, fps, 0, 0}, kind 4 = decoder config (SPS+PPS), kind 3 = 8-byte timestamp + access
 * unit. Ours uses its own mdev name, so the SAL's player is not touched.
 */
#define DEC_WIN_FILE    VAR_DIR "/decode-window"
#define DEC_RUN_FILE    "/dev/shmem/aatokombi-decode.running"   /* RAM: a crash skips until reboot */
#define DEC_MDEV        "/dev/name/local/kbaa_cluster"
#define DEC_MMR_SERVICE "/pps/services/multimedia/renderer/control"

typedef void *(*mmr_connect_t)(const char *);
typedef void *(*mmr_create_t)(void *, const char *, unsigned, int);
typedef int (*mmr_attach_t)(void *, const char *, const char *);
typedef int (*mmr_ctx_fn_t)(void *);
typedef void (*mmr_disc_t)(void *);
typedef const unsigned *(*mmr_err_t)(void *);
typedef int (*mmr_parm_t)(void *, void *);
typedef void *(*dict_new_t)(void);
typedef void *(*dict_set_t)(void *, const char *, const char *);

static struct {
    mmr_connect_t connect; mmr_create_t create; mmr_attach_t out, in;
    mmr_ctx_fn_t play, stop, destroy; mmr_disc_t disconnect; mmr_err_t err;
    mmr_parm_t ctxparm, inparm; dict_new_t dnew; dict_set_t dset;
} M;
static int dec_coid = -1;
static uint8_t dec_au[KBCV_SLOT_BYTES];

static int dec_resolve(void)
{
    void *h, *hs;
    if (M.connect) return 1;
    h = dlopen("libmmrndclient.so.1", RTLD_LAZY);
    hs = dlopen("libstrm.so.1", RTLD_LAZY);
    if (!h) { LOG("dec: libmmrndclient.so.1 not loaded"); return 0; }
    M.create = (mmr_create_t)dlsym(h, "mmr_context_create");
    M.out = (mmr_attach_t)dlsym(h, "mmr_output_attach");
    M.in = (mmr_attach_t)dlsym(h, "mmr_input_attach");
    M.play = (mmr_ctx_fn_t)dlsym(h, "mmr_play");
    M.stop = (mmr_ctx_fn_t)dlsym(h, "mmr_stop");
    M.destroy = (mmr_ctx_fn_t)dlsym(h, "mmr_context_destroy");
    M.disconnect = (mmr_disc_t)dlsym(h, "mmr_disconnect");
    M.err = (mmr_err_t)dlsym(h, "mmr_error_info");
    M.ctxparm = (mmr_parm_t)dlsym(h, "mmr_context_parameters");
    M.inparm = (mmr_parm_t)dlsym(h, "mmr_input_parameters");
    if (hs) { M.dnew = (dict_new_t)dlsym(hs, "strm_dict_new"); M.dset = (dict_set_t)dlsym(hs, "strm_dict_set"); }
    if (!M.create || !M.out || !M.in || !M.play || !M.stop || !M.destroy || !M.disconnect) {
        LOG("dec: mm-renderer client functions missing");
        return 0;
    }
    M.connect = (mmr_connect_t)dlsym(h, "mmr_connect");
    return M.connect != NULL;
}

static void dec_log_err(const char *what, void *ctx)
{
    const unsigned *e = (ctx && M.err) ? M.err(ctx) : NULL;
    LOGV(what, e ? e[0] : 0xffffffffu);
}

static void *dec_dict1(const char *k, const char *v)
{
    void *d;
    if (!M.dnew || !M.dset) return NULL;
    d = M.dnew();
    return d ? M.dset(d, k, v) : NULL;
}

/* QNX TimerTimeout(): unblock a MsgSendv that the mdev does not answer (car 2026-11-07: the
 * first data packet blocked the test thread for good). _NTO_TIMEOUT_SEND | _NTO_TIMEOUT_REPLY. */
typedef int (*timer_timeout_t)(int, int, const void *, const unsigned long long *, unsigned long long *);
static timer_timeout_t dec_tt;
static unsigned dec_timeouts;
static int mdev_send(unsigned kind, const void *a, unsigned an, const void *b, unsigned bn)
{
    int rc;
    uint32_t hdr[6];
    iov_t iov[3];
    int n = 0;
    hdr[0] = 0; hdr[1] = 0; hdr[2] = 0; hdr[3] = 0; hdr[4] = kind; hdr[5] = an + bn;
    iov[n].base = hdr; iov[n].len = sizeof(hdr); n++;
    if (an) { iov[n].base = (void *)a; iov[n].len = an; n++; }
    if (bn) { iov[n].base = (void *)b; iov[n].len = bn; n++; }
    if (dec_tt) { unsigned long long ns = 500000000ull; dec_tt(CLOCK_MONOTONIC, (1 << 4) | (1 << 6), NULL, &ns, NULL); }
    rc = MsgSendv(dec_coid, iov, n, NULL, 0);
    if (rc < 0) dec_timeouts++;
    return rc;
}

/* bytes before the first slice NAL: the SPS+PPS in front of an IDR (libgal puts them there) */
static unsigned dec_config_len(const uint8_t *p, unsigned n)
{
    unsigned i;
    for (i = 0; i + 4 < n; i++)
        if (p[i] == 0 && p[i + 1] == 0 && p[i + 2] == 1) {
            unsigned t = p[i + 3] & 0x1f;
            if (t == 1 || t == 5) return (i > 0 && p[i - 1] == 0) ? i - 1 : i;
            i += 2;
        }
    return 0;
}

static unsigned dec_parse(const char *b, unsigned *v, unsigned max)
{
    unsigned k = 0;
    while (*b && k < max) {
        while (*b && (*b < '0' || *b > '9')) b++;
        if (!*b) break;
        v[k] = 0;
        while (*b >= '0' && *b <= '9') { v[k] = v[k] * 10 + (unsigned)(*b - '0'); b++; }
        k++;
    }
    return k;
}

static int dec_has_word(const char *b, const char *w)
{
    for (; *b; b++) {
        const char *p = b, *q = w;
        while (*q && *p == *q) { p++; q++; }
        if (!*q) return 1;
    }
    return 0;
}

static int dec_has_avc1(const char *b)
{
    for (; *b; b++) if (b[0] == 'A' && b[1] == 'V' && b[2] == 'C' && b[3] == '1') return 1;
    return 0;
}

static int dec_stream_alive(void)
{
    unsigned a = kv->alive_ms;
    return kv->mode == 1 && a && kv->write_seq && now_ms() - a < KBCV_ALIVE_MS;
}

/* newest IDR still in the ring of the current stream, 0 = none */
static unsigned dec_newest_idr(void)
{
    unsigned head = kv->write_seq, k, first = kv->gen_first_seq;
    for (k = 0; k + 1 < KBCV_SLOTS && k < head; k++) {
        unsigned seq = head - k;
        struct kbcv_slot *s;
        if (first && seq < first) break;
        s = &kv->slot[seq % KBCV_SLOTS];
        if (s->seq == seq && (s->flags & KBCV_FLAG_IDR)) return seq;
    }
    return 0;
}

/* no libgcc here (freestanding link): unsigned division by shift and subtract */
static unsigned dec_udiv(unsigned n, unsigned d)
{
    unsigned q = 0, bit = 1;
    if (!d) return 0;
    while (d < n && !(d & 0x80000000u)) { d <<= 1; bit <<= 1; }
    while (bit) { if (n >= d) { n -= d; q |= bit; } d >>= 1; bit >>= 1; }
    return q;
}

static void dec_run(void)
{
    char cfg[64], url[360], *o;
    unsigned r[4], next, sent = 0, report = now_ms(), cfg_sent = 0, i, start = now_ms(), asked = 0;
    unsigned cw = kv->coded_w ? kv->coded_w : 800, ch = kv->coded_h ? kv->coded_h : 480;
    int fd, avc1 = 0, junction = 0, out, in, tries;
    ssize_t n;
    void *conn, *ctx;
    r[0] = 0; r[1] = 0; r[2] = kv->width ? kv->width : 1010; r[3] = kv->height ? kv->height : 376;
    if (r[2] > 1010 || r[3] > 376) {             /* uncropped (full-frame check): fit, keep the aspect */
        unsigned w = r[2], h = r[3];
        if (w * 376 > h * 1010) { r[3] = dec_udiv(h * 1010, w); r[2] = 1010; }
        else { r[2] = dec_udiv(w * 376, h); r[3] = 376; }
        r[2] &= ~1u; r[3] &= ~1u; r[0] = (1010 - r[2]) >> 1;
    }
    fd = open(DEC_WIN_FILE, O_RDONLY);
    if (fd >= 0) {
        n = read(fd, cfg, sizeof(cfg) - 1);
        close(fd);
        if (n > 0) {
            unsigned v[4];
            cfg[n] = 0;
            if (dec_parse(cfg, v, 4) == 4) { r[0] = v[0]; r[1] = v[1]; r[2] = v[2]; r[3] = v[3]; }
            avc1 = dec_has_avc1(cfg); junction = dec_has_word(cfg, "junction");
        }
    }
    if (!dec_resolve()) return;
    if (!dec_tt) { void *hc = dlopen("libc.so.3", RTLD_LAZY); if (hc) dec_tt = (timer_timeout_t)dlsym(hc, "TimerTimeout"); }
    if (!dec_tt) LOG("dec: TimerTimeout not found, sends may block");
    conn = M.connect(DEC_MMR_SERVICE);
    if (!conn) { LOG("dec: mmr_connect failed"); return; }
    ctx = M.create(conn, "kbaa_cluster", 0, 0x140);
    if (!ctx) { LOG("dec: mmr_context_create failed"); M.disconnect(conn); return; }
    if (M.ctxparm) { void *d = dec_dict1("prefetch_mode", "-1"); if (d && M.ctxparm(ctx, d) < 0) dec_log_err("dec: context parameters failed, error", ctx); }
    o = put(url, "screen:/?wingrp=DisplayManagerWindowGroup&winid=DispId[");
    o = put(o, junction ? "DISPLAYABLE_MAP_JUNCTION_VIEW" : "DISPLAYABLE_MAP"); o = put(o, "]Clone1&dstx="); o = put_u(o, r[0]);
    o = put(o, "&dsty="); o = put_u(o, r[1]); o = put(o, "&dstw="); o = put_u(o, r[2]);
    o = put(o, "&dsth="); o = put_u(o, r[3]); *o = 0;
    out = M.out(ctx, url, "video");
    if (out < 0) { dec_log_err("dec: output attach failed, error", ctx); goto done; }
    o = put(url, "mdevpush:" DEC_MDEV "?fourcc="); o = put(o, avc1 ? "AVC1" : "H264");
    o = put(o, "&video.frame_rate=30&video.depth=16&video.width="); o = put_u(o, cw);
    o = put(o, "&video.height="); o = put_u(o, ch); o = put(o, "&video.duration=0"); *o = 0;
    in = M.in(ctx, url, "track");
    if (in < 0) { dec_log_err("dec: input attach failed, error", ctx); goto done; }
    if (M.inparm) { void *d = dec_dict1("VideoDecoderLowLatencyMode", "1"); if (d && M.inparm(ctx, d) < 0) dec_log_err("dec: input parameters failed, error", ctx); }
    for (tries = 0; tries < 20 && dec_coid < 0; tries++) { dec_coid = name_open(DEC_MDEV, 0); if (dec_coid < 0) usleep(100000); }
    if (dec_coid < 0) { LOG("dec: name_open of the mdev failed"); goto done; }
    if (M.play(ctx) < 0) { dec_log_err("dec: play failed, error", ctx); goto done; }
    {
        uint32_t f[8];
        f[0] = 2; f[1] = avc1 ? 0x41564331u : 0x48323634u; f[2] = cw; f[3] = ch; f[4] = 16; f[5] = 30; f[6] = 0; f[7] = 0;
        if (mdev_send(2, f, sizeof(f), NULL, 0) < 0) LOG("dec: format packet failed");
    }
    LOGV("dec: playing, coded width", cw); LOGV("dec: window width", r[2]); LOGV("dec: window height", r[3]);
    i = dec_newest_idr();
    next = i ? i - 1 : kv->write_seq;                 /* start at the newest key frame still in the ring */
    while (dec_stream_alive() && !switched_off()) {
        unsigned head = kv->write_seq;
        kv->rd_alive_ms = now_ms() | 1u;              /* the HMI hides its own cluster map meanwhile */
        if (head - next >= KBCV_SLOTS) { next = head - 1; cfg_sent = 0; }
        if (head == next) usleep(5000);
        while (next != head) {
            unsigned seq = ++next, size, flags;
            struct kbcv_slot *s = &kv->slot[seq % KBCV_SLOTS];
            unsigned long long ts;
            if (s->seq != seq) continue;
            size = s->size; flags = s->flags;
            if (size > sizeof(dec_au)) continue;
            memcpy(dec_au, s->data, size);
            __sync_synchronize();
            if (s->seq != seq) continue;
            if (!cfg_sent) {
                unsigned cl;
                if (!(flags & KBCV_FLAG_IDR)) continue;
                cl = dec_config_len(dec_au, size);
                if (cl) mdev_send(4, dec_au, cl, NULL, 0);
                cfg_sent = 1;
                LOGV("dec: first key frame after ms", now_ms() - start);
            }
            ts = (unsigned long long)now_ms() * 1000ull;
            if (mdev_send(3, &ts, 8, dec_au, size) >= 0) sent++;
        }
        i = now_ms();
        if (!cfg_sent && !asked && i - start >= 2000u) { asked = 1; kv->want_idr = 1; LOG("dec: no key frame for 2 s, asking for one"); }
        if (i - report >= 60000u) { report = i; LOGV("dec: frames sent", sent); if (dec_timeouts) LOGV("dec: send errors/timeouts", dec_timeouts); }
    }
    LOGV("dec: stopping, frames sent", sent);
    if (dec_timeouts) LOGV("dec: send errors/timeouts", dec_timeouts);
    M.stop(ctx);
done:
    kv->rd_alive_ms = 0;
    if (dec_coid >= 0) { name_close(dec_coid); dec_coid = -1; }
    M.destroy(ctx);
    M.disconnect(conn);
}

static void *dec_thread(void *arg)
{
    (void)arg;
    if (exists(DEC_RUN_FILE)) {                  /* the last run never ended: DisplayManager died in it */
        LOG("dec: last decode run did not end, decode mode off until the next reboot");
        return NULL;
    }
    for (;;) {
        usleep(200000);
        if (!kv || !dec_stream_alive() || switched_off()) continue;
        { int fd = open(DEC_RUN_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0666); if (fd >= 0) close(fd); }
        dec_timeouts = 0;
        LOG("dec: decode mode starts");
        dec_run();
        unlink(DEC_RUN_FILE);
        usleep(1000000);                       /* no tight retry loop after a failure */
    }
    return NULL;
}

static void dec_start(void)
{
    int t;
    if (pthread_create(&t, NULL, dec_thread, NULL) != 0) LOG("dec: thread not started");
}
