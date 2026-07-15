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
 * Staleness / disconnect handling: the shim bumps seq on every MediaPlaybackStatus callback (the
 * periodic PlaybackSeconds position while playing), so aa_media advances ~1/s during playback.
 * If seq stops advancing for STALE_MS we treat it as playback-stopped / phone-disconnected and
 * clear the now-playing text — this is what un-sticks the cluster after an unexpected AA drop
 * (e.g. a wireless dongle dropping), where connType can stay "AA connected" until a reboot.
 */
public class ShmemMediaReader implements Runnable {

    private static final String PATH = "/dev/shmem/aa_media";
    private static final long POLL_MS = 500L;
    // aa_media advances ~1/s while playing (shim heartbeat on MediaPlaybackStatus). If it freezes
    // this long, playback stopped or the phone went away -> clear the now-playing override.
    private static final long STALE_MS = 15000L;

    private Timer timer;
    private long lastSeq = -1L;
    private long lastSeqChangeMs = 0L;
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
        if (seq == lastSeq) {
            // no new heartbeat -> playback stopped / phone gone. Clear if stale.
            maybeClearStale();
            return;
        }
        lastSeq = seq;
        lastSeqChangeMs = System.currentTimeMillis();

        // Tab-split the remainder into up to 5 fields; trailing pos/dur are optional (older shim
        // writes only song/artist/album).
        String rest = line.substring(sp + 1);
        String[] f = splitTabs(rest);
        String song   = f.length > 0 ? f[0] : "";
        String artist = f.length > 1 ? f[1] : "";
        String album  = f.length > 2 ? f[2] : "";
        int posSec = parseIntSafe(f.length > 3 ? f[3] : null);
        int durSec = parseIntSafe(f.length > 4 ? f[4] : null);

        MIBLogger.getInstance().debug("aa_media seq=" + seq
                + " song=" + song + " artist=" + artist + " album=" + album
                + " pos=" + posSec + " dur=" + durSec);

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

    /** Clear the now-playing override if aa_media has gone stale (playback stopped / disconnected). */
    private void maybeClearStale() {
        if (CurrentStationInfo.mediaTitle == null) {
            return; // nothing shown
        }
        if (System.currentTimeMillis() - lastSeqChangeMs > STALE_MS) {
            CurrentStationInfo.mediaTitle = null;
            CurrentStationInfo.mediaArtist = null;
            CurrentStationInfo.mediaAlbum = null;
            CurrentStationInfo.mediaPosSec = 0;
            CurrentStationInfo.mediaDurSec = 0;
            CurrentStationInfo.pokeNav();
            // Drop the AID cover too when playback goes stale / the phone disconnects.
            if (Config.SHOW_COVER_ART && target != null) {
                try { target.applyCoverArt(0); } catch (Throwable t) {}
                lastCoverSeq = -2L;
            }
            MIBLogger.getInstance().debug("aa_media stale -> cleared now-playing");
        }
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
