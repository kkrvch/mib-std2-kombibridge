/*
 * Steering-wheel keys the J5 key panel drops, read from the J5 system log (included by dm_hook.c).
 *
 * The key panel service on the J5 processor (tsd.mibstd2.keypanel) translates the wheel's MQB key
 * code through a fixed table; a code that is not in it never reaches the head unit, but is logged:
 *   "MFW_MQB: mapMfwMqbEvent(): code not found in mapping table, original code: N"
 * (twice: the logger's record and the service's stdout line). View is N = 35 (car 2026-11-10);
 * 7 was also seen. The reader thread reads the J5 slogger device over qnet (/net/J5/dev/slog) and
 * looks for that text in the raw records. Only the press is known - no release, no hold.
 *
 * OFF by default since 2026-11-13. Car 2026-11-12/13: read() of /net/J5/dev/slog never returned 0
 * and kept returning data (1351484 bytes in 2 s); the old loop did not sleep while data came, so it
 * hammered the J5 slogger over qnet. The J5 also serves touch and keys: the head unit hung and
 * rebooted, also without AA. The reader now
 *   - runs only while KEYS_ON_FILE exists and KEYS_OFF_FILE does not (checked at start),
 *   - sleeps on every pass and keeps to KEYS_BYTES_PER_S,
 *   - skips the old log slowly; if the log has no end within KEYS_DRAIN_MAX_BYTES /
 *     KEYS_DRAIN_MAX_MS it stops for good,
 *   - stops for good when the same data comes again (KEYS_REPEAT_MAX) or the volume is implausible
 *     (KEYS_FULL_MAX full buffers in a row).
 * "Stops for good" = until DisplayManager restarts; the reason is logged ("keys: ... reader stopped").
 * Not verified on the car: whether the slogger gives the text contiguously in its raw records, and
 * what a read() at the end of the log returns.
 *
 * Results: log lines "keys: ..." and /dev/shmem/aa_wheel_keys "view_presses other_presses
 * last_code events".
 *
 * Separate from the reader and always on (aid_thread): whether the cluster's encoder reader is
 * running - log "aid: cluster video taken / not taken" and /dev/shmem/aa_cluster_taken
 * "state changes ms_of_last_change" (state 1 = taken). A view without a map may stop the AID
 * video, which would anchor the view count. Untested.
 */
#define KEYS_OFF_FILE   VAR_DIR "/no-j5-keys"
#define KEYS_ON_FILE    VAR_DIR "/j5-keys"
#define KEYS_OUT_FILE   "/dev/shmem/aa_wheel_keys"
#define AID_OUT_FILE    "/dev/shmem/aa_cluster_taken"
#define KEYS_VIEW_CODE  35u
#define KEYS_DEDUP_MS   250u

#define KEYS_PASS_US         150000u    /* sleep after every read, whatever it returned */
#define KEYS_BYTES_PER_S     32768u     /* more than this in one second: wait for the next second */
#define KEYS_DRAIN_MAX_BYTES 524288u    /* old log larger than this = no end, stop */
#define KEYS_DRAIN_MAX_MS    30000u
#define KEYS_FULL_MAX        40u        /* full buffers in a row after the old log (about 8 s) */
#define KEYS_REPEAT_MAX      4u         /* chunks seen before */
#define KEYS_HASHES          64u
#define KEYS_HASH_MIN        64u        /* shorter chunks are not compared */
#define KEYS_PAT             "code not found in mapping table, original code: "
#define KEYS_KEEP            (sizeof(KEYS_PAT) - 1 + 4)   /* the text + up to 4 digits */

static volatile unsigned cluster_rd_ms;        /* last pass of the cluster encoder's reader */
static char keys_buf[8192];

/* ---- does the AID take the cluster video? (always on) ---------------------------------------- */
static void aid_write(unsigned on, unsigned changes, unsigned t)
{
    char b[48], *o = b;
    int fd = open(AID_OUT_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) return;
    o = put_u(o, on); *o++ = ' '; o = put_u(o, changes); *o++ = ' '; o = put_u(o, t); *o++ = '\n';
    write(fd, b, (size_t)(o - b));
    close(fd);
}

static void *aid_thread(void *arg)
{
    unsigned state = 2, changes = 0;
    (void)arg;
    for (;;) {
        unsigned t = now_ms(), r = cluster_rd_ms, on = (r && t - r < 1500u) ? 1u : 0u;
        if (on != state) {
            if (state != 2) changes++;
            state = on;
            LOG(on ? "aid: cluster video taken" : "aid: cluster video not taken");
            aid_write(on, changes, t);
        }
        usleep(500000);
    }
    return NULL;
}

/* ---- J5 log reader (off by default) ----------------------------------------------------------- */
static void keys_write(unsigned view, unsigned other, unsigned last, unsigned events)
{
    char b[64], *o = b;
    int fd = open(KEYS_OUT_FILE, O_WRONLY | O_CREAT | O_TRUNC, 0666);
    if (fd < 0) return;
    o = put_u(o, view); *o++ = ' '; o = put_u(o, other); *o++ = ' ';
    o = put_u(o, last); *o++ = ' '; o = put_u(o, events); *o++ = '\n';
    write(fd, b, (size_t)(o - b));
    close(fd);
}

static int keys_open(void)
{
    static const char *const path[] = { "/net/J5/dev/slog", "/net/J5.net.intra/dev/slog", "/net/j5/dev/slog" };
    unsigned k;
    for (k = 0; k < sizeof(path) / sizeof(path[0]); k++) {
        int fd = open(path[k], O_RDONLY);
        if (fd >= 0) { LOGV("keys: J5 log opened, path", k); return fd; }
    }
    return -1;
}

static unsigned keys_hash(const char *p, unsigned n)       /* FNV-1a */
{
    unsigned h = 2166136261u, i;
    for (i = 0; i < n; i++) { h ^= (unsigned char)p[i]; h *= 16777619u; }
    return h ? h : 1u;
}

static void *keys_thread(void *arg)
{
    static const char pat[] = KEYS_PAT;
    static unsigned hashes[KEYS_HASHES];
    const unsigned plen = sizeof(pat) - 1;
    unsigned view = 0, other = 0, events = 0, last_code = 0, last_ms = 0, opened = 0, fail_logged = 0;
    unsigned bytes = 0, carry = 0, full_run = 0, repeats = 0, hash_n = 0, sec_start = 0, sec_bytes = 0;
    unsigned reopens = 0;
    int fd = -1, drained = 0;
    (void)arg;
    for (;;) {
        ssize_t n;
        unsigned t, room, len;
        if (fd < 0) {
            fd = keys_open();
            if (fd < 0) {
                if (!fail_logged) { fail_logged = 1; LOG("keys: J5 log not reachable yet"); }
                usleep(5000000);
                continue;
            }
            opened = now_ms(); drained = 0; bytes = 0; carry = 0; full_run = 0;
        }
        room = (unsigned)sizeof(keys_buf) - carry;
        n = read(fd, keys_buf + carry, room);
        usleep(KEYS_PASS_US);                          /* every pass, also while data keeps coming */
        if (n < 0) {
            close(fd); fd = -1;
            if (++reopens > 5u) { LOG("keys: J5 log read errors, reader stopped"); return NULL; }
            LOG("keys: J5 log read error, reopening");
            usleep(5000000);
            continue;
        }
        t = now_ms();
        if (t - sec_start >= 1000u) { sec_start = t; sec_bytes = 0; }
        sec_bytes += (unsigned)n;
        if (sec_bytes > KEYS_BYTES_PER_S) {            /* over the budget: rest of this second off */
            unsigned left = 1000u - (t - sec_start);
            usleep((left ? left : 1u) * 1000u);
        }
        bytes += (unsigned)n;
        if ((unsigned)n >= KEYS_HASH_MIN) {            /* the same data again? */
            unsigned h = keys_hash(keys_buf + carry, (unsigned)n), k;
            for (k = 0; k < KEYS_HASHES; k++) if (hashes[k] == h) break;
            if (k < KEYS_HASHES) {
                if (++repeats >= KEYS_REPEAT_MAX) {
                    LOGV("keys: J5 log repeats itself, reader stopped, bytes", bytes);
                    close(fd); return NULL;
                }
            } else hashes[hash_n++ % KEYS_HASHES] = h;
        }
        if (!drained) {                                /* what was logged before we came is skipped */
            if (n == 0 || (unsigned)n < room) {
                drained = 1; carry = 0;
                LOGV("keys: old J5 log skipped, bytes", bytes);
                LOG("keys: reading new J5 log records");
            } else if (bytes > KEYS_DRAIN_MAX_BYTES || t - opened > KEYS_DRAIN_MAX_MS) {
                LOGV("keys: J5 log has no end, reader stopped, bytes", bytes);
                close(fd); return NULL;
            }
            continue;
        }
        if (n == 0) { full_run = 0; if (!carry) continue; }
        if ((unsigned)n == room) {
            if (++full_run >= KEYS_FULL_MAX) {
                LOGV("keys: J5 log volume implausible, reader stopped, bytes", bytes);
                close(fd); return NULL;
            }
        } else full_run = 0;
        len = carry + (unsigned)n;
        {
            /* A text cut by the end of the buffer is kept (keep..len) and finished by the next read;
               a number at the very end is taken as complete once a read brings nothing. */
            unsigned i, keep = len > KEYS_KEEP ? len - (unsigned)KEYS_KEEP : 0;
            for (i = 0; i + plen <= len; i++) {
                unsigned k, code = 0, d = 0;
                if (keys_buf[i] != pat[0]) continue;
                for (k = 1; k < plen && keys_buf[i + k] == pat[k]; k++) ;
                if (k < plen) continue;
                for (k = i + plen; k < len && keys_buf[k] >= '0' && keys_buf[k] <= '9' && d < 4; k++, d++)
                    code = code * 10u + (unsigned)(keys_buf[k] - '0');
                if (k == len && d < 4 && n > 0) { keep = i; break; }           /* the number may go on */
                if (k > keep) keep = k;
                i = k - 1;
                if (!d) continue;
                t = now_ms();
                if (code == last_code && t - last_ms < KEYS_DEDUP_MS + KEYS_PASS_US / 1000u) continue;   /* the stdout copy */
                last_code = code; last_ms = t; events++;
                if (code == KEYS_VIEW_CODE) { view++; LOGV("keys: View press", view); }
                else { other++; LOGV("keys: unmapped wheel code", code); }
                keys_write(view, other, code, events);
            }
            if (keep > len) keep = len;
            carry = len - keep;
            for (i = 0; i < carry; i++) keys_buf[i] = keys_buf[keep + i];
        }
    }
    return NULL;
}

static void keys_start(void)
{
    int t;
    if (pthread_create(&t, NULL, aid_thread, NULL) != 0) LOG("aid: thread not started");
    if (!exists(KEYS_ON_FILE)) { LOG("keys: J5 key reading off (no j5-keys file)"); return; }
    if (exists(KEYS_OFF_FILE)) { LOG("keys: J5 key reading off (no-j5-keys)"); return; }
    LOG("keys: J5 key reading on (j5-keys)");
    if (pthread_create(&t, NULL, keys_thread, NULL) != 0) LOG("keys: thread not started");
}
