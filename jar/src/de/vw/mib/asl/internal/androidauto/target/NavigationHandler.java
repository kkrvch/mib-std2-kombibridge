package de.vw.mib.asl.internal.androidauto.target;

import de.adi961.miblogger.MIBLogger;
import org.dsi.ifc.androidauto2.Constants;

/**
 * AAtoKombi route-guidance state holder (media-widget path).
 *
 * The output path is chosen at runtime by {@link de.vw.mib.bap.mqbab2.navsd.functions.ClusterCaps}:
 * on a nav-capable cluster AANavReader drives the real navsd Navigation menu (LSG 50); on a
 * non-nav STD2 the cluster never subscribes to navsd, so AANavReader instead routes the AA
 * navigation text into the audio now-playing widget (via
 * {@link de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo}).
 *
 * This handler is used only on the media-widget path: it tracks the route-guidance-active state
 * that gates the CurrentStationInfo injection. It makes no navsd / NavBAP calls of its own.
 */
public class NavigationHandler {

    public static volatile boolean aaRouteGuidanceActive = false;

    public NavigationHandler() {
    }

    public void navigationFocus(int focus) {
        aaRouteGuidanceActive = (focus == Constants.NAVFOCUS_PROJECTED);
        MIBLogger.getInstance().debug("navigationFocus focus=" + focus + " aaRouteGuidanceActive=" + aaRouteGuidanceActive);
    }

    public void handleNextTurnEvent(String road, int turnSide, int event, int turnAngle, int turnNumber) {
        MIBLogger.getInstance().debug("handleNextTurnEvent road=" + road + " side=" + turnSide
                + " event=" + event + " angle=" + turnAngle + " number=" + turnNumber);
    }

    public void handleUpdateNextTurnDistanceEvent(int distanceMeters, int timeSeconds) {
        MIBLogger.getInstance().debug("handleUpdateNextTurnDistanceEvent dist=" + distanceMeters + " time=" + timeSeconds);
    }
}
