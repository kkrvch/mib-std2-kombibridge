package de.vw.mib.asl.internal.androidauto.target;

import de.adi961.miblogger.MIBLogger;
import org.dsi.ifc.androidauto2.Constants;

/**
 * AAtoKombi (track-text experiment build).
 *
 * The nav-BAP path (navsd LSG 50) is intentionally NOT used here: the cluster never subscribes to
 * it on this no-nav STD2. Instead AANavReader routes the AA navigation text into the
 * audio now-playing widget (via {@link de.vw.mib.bap.mqbab2.audiosd.functions.CurrentStationInfo}),
 * a BAP group the cluster already renders.
 *
 * This handler now only tracks the route-guidance-active state; every navsd / NavBAP /
 * ManeuverDescriptor call has been removed so we do not touch the BAP nav infrastructure at all
 * (that was the suspected cause of the phone/music regression).
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
