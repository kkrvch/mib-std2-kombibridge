/*
 * Reconstructed from the stock MIBHMI class (javap). navsd-shadow: while Android Auto guides, report
 * the road the car is on (NavState.currentRoad, AA 0x8007 current_road) as the cluster's current
 * position info (fid 19) instead of the unit's own nav engine.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.aatokombi.Config;
import de.adi961.miblogger.MIBLogger;
import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.CurrentPositionInfo_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public class CurrentPositionInfo
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener {
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{738, 733};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$CurrentPositionInfo_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile CurrentPositionInfo INSTANCE = null;
    public static void poke() {
        CurrentPositionInfo i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: INSTANCE latch; the stock listener only on a nav-capable cluster (the
        // unit's own nav keeps re-sending when AA is idle), wrapped so a non-routing engine cannot
        // break navsd startup. Like stock, no initial status (null).
        INSTANCE = this;
        if (ClusterCaps.isNavCapable()) {
            try { this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
        }
        return null;
    }

    protected CurrentPositionInfo_Status dequeueBAPEntity() {
        return (CurrentPositionInfo_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$CurrentPositionInfo_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$CurrentPositionInfo_Status = CurrentPositionInfo.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.CurrentPositionInfo_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$CurrentPositionInfo_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 19;
    }

    private void setCurrentPositionInfoStatusData(CurrentPositionInfo_Status currentPositionInfo_Status) {
        try {
            String aaRoad = NavState.currentRoad;
            if (ClusterCaps.isNavCapable() && NavState.ACTIVE && aaRoad != null && aaRoad.length() > 0) {
                // navsd-shadow delta: the AA current road (0x8007 current_road), when the phone names
                // it. Google Maps does not send it at all (trip dump 2026-10), so without it we fall
                // through to the stock value below: the unit's own map-matched street, which is what
                // the cluster showed under the FOLLOW_STREET arrow before this shadow existed.
                currentPositionInfo_Status.positionInfo.setContent(aaRoad);
            } else if (ClusterCaps.isNavCapable()) {
                // Stock: the position only while the unit's own navigation is NOT guiding.
                NavigationService navigationService = this.getNavigationService();
                String string = navigationService.getCurrentPositionInfo();
                int navStatus = navigationService.getNavigationStatus();
                String out = (string == null || navStatus != 0) ? "" : string;
                currentPositionInfo_Status.positionInfo.setContent(out);
                logStock(string, navStatus, out);
            } else {
                currentPositionInfo_Status.positionInfo.setContent("");
            }
        } catch (Throwable t) {
            // leave empty on any failure; never crash navsd
        }
    }

    // Debug-only, change-triggered: what the stock path saw and sent (diagnosing the AID caption).
    private static String lastStockLog = null;
    private static void logStock(String raw, int navStatus, String out) {
        if (Config.LOG_LEVEL > MIBLogger.DEBUG) {
            return;
        }
        String line = "CurrentPositionInfo stock: raw='" + raw + "' navStatus=" + navStatus
                + " out='" + out + "' aaActive=" + NavState.ACTIVE;
        if (!line.equals(lastStockLog)) {
            lastStockLog = line;
            MIBLogger.getInstance().debug(line);
        }
    }

    private void sendCurrentPositionInfoStatus(CurrentPositionInfo_Status currentPositionInfo_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(currentPositionInfo_Status, this);
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
        // navsd-shadow delta: a listener was only registered on a nav-capable cluster (see init).
        if (ClusterCaps.isNavCapable()) {
            try { this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
        }
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void process(int n) {
        this.sendCurrentPositionInfoStatus(this.computeCurrentPositionInfoStatus());
    }

    protected CurrentPositionInfo_Status computeCurrentPositionInfoStatus() {
        CurrentPositionInfo_Status currentPositionInfo_Status = this.dequeueBAPEntity();
        this.setCurrentPositionInfoStatusData(currentPositionInfo_Status);
        return currentPositionInfo_Status;
    }

    public void processHMIEvent(int n) {
    }

    public void updateNavigationData(NavigationService navigationService, int n) {
        this.process(-1);
    }

    static /* synthetic */ Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            throw new NoClassDefFoundError(classNotFoundException.getMessage());
        }
    }
}
