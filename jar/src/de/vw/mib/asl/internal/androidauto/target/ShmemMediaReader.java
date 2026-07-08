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
 * media widget via {@link CurrentStationInfo}. Gated by {@link Config#MEDIA_ENABLED}.
 *
 * IPC line (the shim rewrites the whole line, O_TRUNC):
 *   seq &lt;Song&gt;\t&lt;Artist&gt;\t&lt;Album&gt;
 * The seq is separated by the first space; the three text fields are tab-separated.
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

    /** Create and start the repeating poll timer on the framework timer thread. */
    public void start() {
        if (!Config.MEDIA_ENABLED) {
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

        // song \t artist \t album  (the remainder after the seq)
        String rest = line.substring(sp + 1);
        String song = "", artist = "", album = "";
        int t1 = rest.indexOf('\t');
        if (t1 >= 0) {
            song = rest.substring(0, t1);
            int t2 = rest.indexOf('\t', t1 + 1);
            if (t2 >= 0) {
                artist = rest.substring(t1 + 1, t2);
                album = rest.substring(t2 + 1);
            } else {
                artist = rest.substring(t1 + 1);
            }
        } else {
            song = rest;
        }

        MIBLogger.getInstance().debug("aa_media seq=" + seq
                + " song=" + song + " artist=" + artist + " album=" + album);

        CurrentStationInfo.mediaTitle = song;
        CurrentStationInfo.mediaArtist = artist;
        CurrentStationInfo.mediaAlbum = album;
        CurrentStationInfo.pokeNav();
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
            CurrentStationInfo.pokeNav();
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
