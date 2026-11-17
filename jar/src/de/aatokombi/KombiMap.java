package de.aatokombi;

import de.adi961.miblogger.MIBLogger;
import de.vw.mib.asl.framework.api.framework.ASLFrameworkFactory;
import de.vw.mib.asl.framework.internal.framework.ServiceManager;

/**
 * Hides the unit's own cluster map while Android Auto's cluster video owns the AID, and shows it
 * again afterwards.
 *
 * The events are the stock ones NavigationMapAdapter.setMapInvisible / setMapVisible send (and the
 * stock ChangeDataRateSequence / MostUnlockUseCase use when the cluster asks for no map):
 * MapViewerKombiStateMain goes to MapViewerKombiStateInvisible and the navigation stops drawing the
 * cluster map. Nothing in BAP reads that visibility, so the cluster is not told. A DataRate change
 * of the cluster may show the map again, so "invisible" is repeated every RESEND_MS.
 *
 * Event values, the resend and sending on the ASL thread follow mst2-cluster-nav (Anton Perk,
 * https://github.com/perkthefox/mst2-cluster-nav, KombiMap.java, CC BY-NC-SA 4.0), where they were
 * tested in a car on the same HMI build (H29.319.120). The same values are in the stock
 * NavigationMapAdapter.setMapVisible / setMapInvisible of the unit's MIBHMI converted with the
 * fixed tools/jxe2jar (the old conversion had garbled them to -171438016 / -154660800).
 *
 * Java 1.3 source; never throws into the HMI.
 */
public final class KombiMap {
    private static final int SET_VISIBLE = 1074841845;
    private static final int SET_INVISIBLE = 1074841846;
    private static final long RESEND_MS = 2000L;
    /**
     * The stock map comes back only after the phone's video has been gone this long. Every
     * "visible" makes the unit restart its cluster map (mapSwitchState 5 -> 2) and the AID shows
     * "map activation"; short gaps (stream restart, waiting for a key frame) flapped it (car,
     * 2026-11-01). libkb_dm.so hands the stock frames over during such gaps by itself.
     */
    private static final long SHOW_AFTER_MS = 4000L;

    private static long videoMs;

    private static boolean hidden;
    private static long sentMs;

    private KombiMap() {}

    /** About once a second: is Android Auto's cluster video alive. */
    public static void phoneVideo(boolean video) {
        long now = System.currentTimeMillis();
        if (video) videoMs = now;
        if (video && (!hidden || now - sentMs >= RESEND_MS)) {
            if (!hidden) {
                MIBLogger.getInstance().info("AA cluster video: stock cluster map hidden");
            }
            hidden = true;
            sentMs = now;
            send(SET_INVISIBLE);
        } else if (!video && hidden && now - videoMs >= SHOW_AFTER_MS) {
            hidden = false;
            MIBLogger.getInstance().info("AA cluster video ended: stock cluster map shown");
            send(SET_VISIBLE);
        }
    }

    /** Fire the HMI event {@code event} on the ASL thread; never throws. */
    private static void send(final int event) {
        try {
            ASLFrameworkFactory.getASLFrameworkAPI().getServices().getASLThreadSwitch().enqueue(
                new Runnable() {
                    public void run() {
                        try {
                            ServiceManager.eventMain.getEventDispatcher().sendSafe(
                                ServiceManager.mGenericEventFactory.newEvent(event));
                        } catch (Throwable t) {
                            MIBLogger.getInstance().error("cluster map event not sent: " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            MIBLogger.getInstance().error("ASL thread not available: " + t);
        }
    }
}
