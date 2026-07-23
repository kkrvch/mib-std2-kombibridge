package de.vw.mib.asl.internal.androidauto.target;

import de.aatokombi.Config;
import de.adi961.miblogger.MIBLogger;
import de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;
import de.vw.mib.timer.Timer;

import java.io.BufferedReader;
import java.io.FileReader;

/**
 * Reads the Android Auto now-playing track from /dev/shmem/aa_media (written by the patched GAL
 * receiver / navshim MediaPlaybackStatusEndpoint, see work/shim/) and shows it on the cluster's
 * media widget via {@link CurrentStationInfo}. Gated by {@link Config#SHOW_MEDIA}.
 *
 * IPC line (the shim rewrites the whole line, O_TRUNC):
 *   seq &lt;Song&gt;\t&lt;Artist&gt;\t&lt;Album&gt;\t&lt;posSec&gt;\t&lt;durSec&gt;
 * The seq is separated by the first space; the text/number fields are tab-separated. posSec/durSec
 * feed the optional progress bar ({@link Config#SHOW_MEDIA_PROGRESS}); they are appended after the
 * three track strings so an older 3-field line still parses (pos/dur then default to 0 = no bar).
 *
 * Disconnect handling is event-driven, not timed: the shim writes an explicit {@code connected} flag
 * (field 7) that flips to 0 the instant the AA session tears down (its GalReceiver::shutdown hook), and
 * a {@code session} epoch (field 8) that bumps on every reconnect (GalReceiver::init). We clear the
 * now-playing text immediately on connected=0 and on a new session, and KEEP the last track while
 * connected=1 (so a paused track no longer blanks after a few seconds). A long
 * {@link Config#AA_STALE_BACKSTOP_MS} backstop only covers a hard yank that produced no teardown call.
 */
public class ShmemMediaReader implements Runnable {

    private static final String PATH = "/dev/shmem/aa_media";
    private static final long POLL_MS = 500L;

    private Timer timer;
    private long lastSeq = -1L;
    private long lastSeqChangeMs = 0L;
    private long lastSession = -1L;   // AA session epoch from the shim; change => phone reconnected
    // AID cover art: the shim's coverSeq starts at 0; -2 forces a first applyCoverArt on the
    // first metadata event. The target is used only for the (AID-only) cover push — the
    // now-playing text still sinks to CurrentStationInfo below, unchanged.
    private final AndroidAutoTarget target;
    private long lastCoverSeq = -2L;

    public ShmemMediaReader(AndroidAutoTarget target) {
        this.target = target;
    }

    /** Create and start the repeating poll timer on the framework timer thread. */
    public void start() {
        if (!Config.SHOW_MEDIA) {
            return; // now-playing feature disabled
        }
        try {
            this.timer = ServiceManager.timerManager.createTimer(
                    "AATOKOMBI_MEDIA_POLL", POLL_MS, true, this, Timer.TIMER_THREAD_INVOKER);
            this.timer.start();
            MIBLogger.getInstance().info("ShmemMediaReader timer started, polling " + PATH);
        } catch (Throwable t) {
            MIBLogger.getInstance().error("ShmemMediaReader timer start failed: " + t);
        }
    }

    /** Called by the framework timer every POLL_MS. Must never throw. */
    public void run() {
        try {
            processOnce();
        } catch (Throwable t) {
            // Never let the poll disturb the HMI.
        }
    }

    private void processOnce() {
        String line = readLine();
        if (line == null || line.length() == 0) {
            maybeClearStale();
            return;
        }
        int sp = line.indexOf(' ');
        if (sp < 0) {
            maybeClearStale();
            return;
        }
        long seq;
        try {
            seq = Long.parseLong(line.substring(0, sp));
        } catch (NumberFormatException ex) {
            return;
        }

        // Tab-split the remainder. Trailing fields are optional/appended over time.
        //   f: song artist album posSec durSec coverSeq coverLen connected session
        String rest = line.substring(sp + 1);
        String[] f = splitTabs(rest);
        // connected: present+valid => use it; absent (older shim) OR torn/garbage (partial read of the
        // truncate-rewritten file) => default 1 (assume connected), so we never spuriously clear.
        int connected = (int) parseLongSafe(f.length > 7 ? f[7] : null, 1L);
        long session  = parseLongSafe(f.length > 8 ? f[8] : null, -1L);

        // New AA session (phone reconnected): drop stale now-playing and re-arm the seq tracker.
        if (session != lastSession) {
            lastSession = session;
            lastSeq = -1L;
            clearNowPlaying();
        }

        // Authoritative disconnect from the GAL library: AA session torn down -> clear immediately,
        // no timeout. The shim flushes connected=0 from its GalReceiver::shutdown hook without bumping
        // seq, so this is checked BEFORE the frozen-seq branch.
        if (connected == 0) {
            lastSeq = seq;
            clearNowPlaying();
            return;
        }

        if (seq == lastSeq) {
            // Connected but no new heartbeat (e.g. playback paused). KEEP the last track; the long
            // backstop only fires for a hard yank that produced no teardown call.
            maybeClearStale();
            return;
        }
        lastSeq = seq;
        lastSeqChangeMs = System.currentTimeMillis();

        String song   = f.length > 0 ? f[0] : "";
        String artist = f.length > 1 ? f[1] : "";
        String album  = f.length > 2 ? f[2] : "";
        int posSec = parseIntSafe(f.length > 3 ? f[3] : null);
        int durSec = parseIntSafe(f.length > 4 ? f[4] : null);

        // Verbosity driven solely by Config.LOG_LEVEL; compile-time gate so this per-poll string is
        // not built unless compiled at DEBUG/TRACE.
        if (Config.LOG_LEVEL <= MIBLogger.DEBUG) {
            MIBLogger.getInstance().debug("aa_media seq=" + seq + " conn=" + connected + " sess=" + session
                    + " song=" + song + " artist=" + artist + " album=" + album
                    + " pos=" + posSec + " dur=" + durSec);
        }

        CurrentStationInfo.mediaTitle = song;
        CurrentStationInfo.mediaArtist = artist;
        CurrentStationInfo.mediaAlbum = album;
        CurrentStationInfo.mediaPosSec = posSec;
        CurrentStationInfo.mediaDurSec = durSec;
        CurrentStationInfo.pokeNav();

        // Cover art (AID only): fields 5=coverSeq, 6=coverLen (appended after pos/dur by the shim).
        // coverSeq advances on every metadata event (track switch); re-evaluate the cover only then,
        // not on the ~1s heartbeat. coverLen 0 => phone sent no art => clear it. Fully gated by
        // SHOW_COVER_ART so a non-AID build never touches the kombipictureserver path.
        if (Config.SHOW_COVER_ART && target != null) {
            long coverSeq = parseLongSafe(f.length > 5 ? f[5] : null, -1L);
            int coverLen = parseIntSafe(f.length > 6 ? f[6] : null);
            if (coverSeq != lastCoverSeq) {
                lastCoverSeq = coverSeq;
                target.applyCoverArt(coverLen);
            }
        }
    }

    /** Parse a long, defaulting on null / garbage. */
    private static long parseLongSafe(String s, long dflt) {
        if (s == null || s.length() == 0) {
            return dflt;
        }
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ex) {
            return dflt;
        }
    }

    /** Split on tabs (no regex — the mod targets 1.3). Empty trailing fields are preserved. */
    private static String[] splitTabs(String s) {
        int n = 1;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\t') n++;
        }
        String[] out = new String[n];
        int start = 0, idx = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == '\t') {
                out[idx++] = s.substring(start, i);
                start = i + 1;
            }
        }
        out[idx] = s.substring(start);
        return out;
    }

    /** Parse a non-negative int, defaulting to 0 on null / garbage. */
    private static int parseIntSafe(String s) {
        if (s == null || s.length() == 0) {
            return 0;
        }
        try {
            int v = Integer.parseInt(s.trim());
            return v < 0 ? 0 : v;
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // Backstop for a hard yank with no teardown call (connected=0 and session change clear promptly
    // in processOnce): fires only when the connected flag is stuck at 1 past AA_STALE_BACKSTOP_MS.
    private void maybeClearStale() {
        if (CurrentStationInfo.mediaTitle == null) {
            return; // nothing shown
        }
        if (System.currentTimeMillis() - lastSeqChangeMs > Config.AA_STALE_BACKSTOP_MS) {
            clearNowPlaying();
        }
    }

    /** Clear the now-playing override (and the AID cover) when playback stops / the phone disconnects. */
    private void clearNowPlaying() {
        if (CurrentStationInfo.mediaTitle == null) {
            return; // already clear
        }
        CurrentStationInfo.mediaTitle = null;
        CurrentStationInfo.mediaArtist = null;
        CurrentStationInfo.mediaAlbum = null;
        CurrentStationInfo.mediaPosSec = 0;
        CurrentStationInfo.mediaDurSec = 0;
        CurrentStationInfo.pokeNav();
        if (Config.SHOW_COVER_ART && target != null) {
            try { target.applyCoverArt(0); } catch (Throwable t) {}
            lastCoverSeq = -2L;
        }
        MIBLogger.getInstance().debug("aa_media cleared now-playing");
    }

    private String readLine() {
        BufferedReader br = null;
        try {
            br = new BufferedReader(new FileReader(PATH));
            return br.readLine();
        } catch (Exception ex) {
            return null; // file absent yet / transient -> ignore
        } finally {
            if (br != null) {
                try {
                    br.close();
                } catch (Exception ex) {
                    // ignore
                }
            }
        }
    }
}
