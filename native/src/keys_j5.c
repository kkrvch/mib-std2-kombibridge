/*
 * Steering-wheel keys the J5 key panel drops, read from the J5 system log (included by dm_hook.c).
 *
 * The key panel service on the J5 processor (tsd.mibstd2.keypanel) translates the wheel's MQB key
 * code through a fixed table; a code that is not in it never reaches the head unit, but is logged:
 *   "MFW_MQB: mapMfwMqbEvent(): code not found in mapping table, original code: N"
 * (twice: the logger's record and the service's stdout line). View is N = 35 (car 2026-11-10);
 * 7 was also seen. This thread reads the J5 slogger device over qnet (/net/J5/dev/slog) and looks
 * for that text in the raw records. Only the press is known - no release, no hold.
 *
 * Results: log lines "keys: ..." and /dev/shmem/aa_wheel_keys "view_presses other_presses
 * last_code events". Off while /tsd/var/aatokombi/no-j5-keys exists (checked at start).
 *
 * Also logged here: whether the cluster's encoder reader is running ("keys: cluster video
 * taken / not taken") - a view without a map may stop the AID video, which would anchor the view
 * count. Untested.
 */
#define KEYS_OFF_FILE   VAR_DIR "/no-j5-keys"
#define KEYS_OUT_FILE   "/dev/shmem/aa_wheel_keys"
#define KEYS_VIEW_CODE  35u
#define KEYS_DEDUP_MS   250u

static volatile unsigned cluster_rd_ms;        /* last pass of the cluster encoder's reader */
static char keys_buf[8192];

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

static void *keys_thread(void *arg)
{
    static const char pat[] = "code not found in mapping table, original code: ";
    const unsigned plen = sizeof(pat) - 1;
    unsigned view = 0, other = 0, events = 0, last_code = 0, last_ms = 0, opened = 0, fail_logged = 0;
    unsigned rd_state = 2, rd_check = 0, bytes = 0, bytes_logged = 0;
    int fd = -1, drained = 0;
    (void)arg;
    if (exists(KEYS_OFF_FILE)) { LOG("keys: J5 key reading off (no-j5-keys)"); return NULL; }
    for (;;) {
        ssize_t n;
        unsigned t = now_ms();
        if (t - rd_check >= 500u) {                    /* does the AID take the cluster video? */
            unsigned r = cluster_rd_ms, on = (r && t - r < 1500u) ? 1u : 0u;
            rd_check = t;
            if (on != rd_state) { rd_state = on; LOG(on ? "keys: cluster video taken" : "keys: cluster video not taken"); }
        }
        if (fd < 0) {
            fd = keys_open();
            if (fd < 0) {
                if (!fail_logged) { fail_logged = 1; LOG("keys: J5 log not reachable yet"); }
                usleep(5000000);
                continue;
            }
            opened = now_ms(); drained = 0;
        }
        n = read(fd, keys_buf, sizeof(keys_buf));
        if (n < 0) { LOG("keys: J5 log read error, reopening"); close(fd); fd = -1; usleep(5000000); continue; }
        if (!drained && (n == 0 || now_ms() - opened >= 2000u)) {
            drained = 1;                               /* what was logged before we came is skipped */
            LOGV("keys: old J5 log skipped, bytes", bytes);
            if (n > 0) continue;
        }
        if (n == 0) { usleep(50000); continue; }
        bytes += (unsigned)n;
        if (!drained) continue;
        if (!bytes_logged) { bytes_logged = 1; LOG("keys: reading new J5 log records"); }
        {
            unsigned i, lim = (unsigned)n >= plen ? (unsigned)n - plen : 0;
            for (i = 0; i < lim; i++) {
                unsigned k, code = 0, d = 0;
                if (keys_buf[i] != pat[0]) continue;
                for (k = 1; k < plen && keys_buf[i + k] == pat[k]; k++) ;
                if (k < plen) continue;
                for (k = i + plen; k < (unsigned)n && keys_buf[k] >= '0' && keys_buf[k] <= '9' && d < 4; k++, d++)
                    code = code * 10u + (unsigned)(keys_buf[k] - '0');
                if (!d) continue;
                t = now_ms();
                if (code == last_code && t - last_ms < KEYS_DEDUP_MS) continue;   /* the stdout copy */
                last_code = code; last_ms = t; events++;
                if (code == KEYS_VIEW_CODE) { view++; LOGV("keys: View press", view); }
                else { other++; LOGV("keys: unmapped wheel code", code); }
                keys_write(view, other, code, events);
                i += plen;
            }
        }
    }
    return NULL;
}

static void keys_start(void)
{
    int t;
    if (pthread_create(&t, NULL, keys_thread, NULL) != 0) LOG("keys: thread not started");
}
