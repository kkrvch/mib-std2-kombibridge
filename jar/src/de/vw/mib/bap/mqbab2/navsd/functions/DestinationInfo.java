/*
 * Reconstructed from the stock MIBHMI class (javap). navsd-shadow: while Android Auto guides, report
 * the AA destination (0x8006 NavigationDestination.address, IPC line 7) as the cluster's destination
 * info (fid 46) instead of the unit's own nav engine.
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
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationDestinationInfo;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationStopoverInfo;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.DestinationInfo_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public class DestinationInfo
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener {
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{732, 3799, 3800};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DestinationInfo_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile DestinationInfo INSTANCE = null;
    public static void poke() {
        DestinationInfo i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    // Debug-only, change-triggered: what was last sent, so the trip log shows it once per change.
    private static String lastLog = null;

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: INSTANCE latch; the stock listener only on a nav-capable cluster, wrapped
        // so a non-routing engine cannot break navsd startup (same pattern as CurrentPositionInfo).
        INSTANCE = this;
        if (ClusterCaps.isNavCapable()) {
            try { this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
        }
        return this.computeDestinationInfoStatus();
    }

    protected DestinationInfo_Status dequeueBAPEntity() {
        return (DestinationInfo_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DestinationInfo_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DestinationInfo_Status = DestinationInfo.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.DestinationInfo_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DestinationInfo_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 46;
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
        // navsd-shadow delta: a listener was only registered on a nav-capable cluster (see init).
        if (ClusterCaps.isNavCapable()) {
            try { this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
        }
    }

    public void process(int n) {
        this.getDelegate().getPropertyListener(this).statusProperty(this.computeDestinationInfoStatus(), this);
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    /** Every field to "nothing": the dequeued entity may still carry the previous frame's content. */
    private static void clear(DestinationInfo_Status s) {
        s.position.latitude = 0;
        s.position.longitude = 0;
        s.poi_Type = 0;                      // POI_TYPE_NO_POI
        s.poi_Description.setContent("");
        s.street.setContent("");
        s.town.setContent("");
        s.state.setContent("");
        s.postalCode.setContent("");
        s.country.setContent("");
        s.totalNumOfStopovers = 0;
        s.stopover_Sn = 0;
    }

    private DestinationInfo_Status computeDestinationInfoStatus() {
        DestinationInfo_Status destinationInfo_Status = this.dequeueBAPEntity();
        try {
            String aaDest = NavState.destinationName;
            if (ClusterCaps.isNavCapable() && NavState.ACTIVE && aaDest != null && aaDest.length() > 0) {
                // navsd-shadow delta: AA sends the destination as ONE free-form string (an address or a
                // place name), so it goes whole into the street field; everything else stays empty --
                // no coordinates, no POI type, no stopover count (later legs are not forwarded).
                clear(destinationInfo_Status);
                destinationInfo_Status.street.setContent(aaDest);
                log("aa", aaDest);
            } else {
                NavigationService navigationService = this.getNavigationService();
                if (navigationService.getRouteGuidanceState() != 0) {
                    // Stock: the unit's own route.
                    NavigationDestinationInfo navigationDestinationInfo = navigationService.getNavigationDestinationInfo();
                    destinationInfo_Status.position.latitude = navigationDestinationInfo.getDestinationInfoLatitude();
                    destinationInfo_Status.position.longitude = navigationDestinationInfo.getDestinationInfoLongitude();
                    destinationInfo_Status.poi_Type = navigationDestinationInfo.getDestinationInfoPoiType();
                    destinationInfo_Status.poi_Description.setContent(navigationDestinationInfo.getDestinationInfoPoiDescription());
                    destinationInfo_Status.street.setContent(navigationDestinationInfo.getDestinationInfoStreet());
                    destinationInfo_Status.town.setContent(navigationDestinationInfo.getDestinationInfoTown());
                    destinationInfo_Status.state.setContent(navigationDestinationInfo.getDestinationInfoState());
                    destinationInfo_Status.postalCode.setContent(navigationDestinationInfo.getDestinationInfoPostalCode());
                    destinationInfo_Status.country.setContent(navigationDestinationInfo.getDestinationInfoCountry());
                    NavigationStopoverInfo navigationStopoverInfo = navigationService.getStopoverInfo();
                    destinationInfo_Status.totalNumOfStopovers = navigationStopoverInfo.getTotalNumberOfStopovers();
                    destinationInfo_Status.stopover_Sn = navigationStopoverInfo.getCurrentStopover();
                    log("stock", navigationDestinationInfo.getDestinationInfoStreet());
                } else {
                    // navsd-shadow delta: stock leaves the entity as dequeued here; cleared so an AA
                    // destination cannot outlive its route.
                    clear(destinationInfo_Status);
                    log("none", "");
                }
            }
        } catch (Throwable t) {
            // never crash navsd; whatever was filled so far goes out
        }
        return destinationInfo_Status;
    }

    private static void log(String src, String street) {
        if (Config.LOG_LEVEL > MIBLogger.DEBUG) {
            return;
        }
        String line = "DestinationInfo out: src=" + src + " street='" + street + "'";
        if (!line.equals(lastLog)) {
            lastLog = line;
            MIBLogger.getInstance().debug(line);
        }
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
