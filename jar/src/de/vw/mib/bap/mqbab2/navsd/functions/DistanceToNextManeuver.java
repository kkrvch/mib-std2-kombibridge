/*
 * Decompiled with CFR 0.152.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationDistanceToNextManeuver;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.iterator.elements.NavigationManeuverDescriptorElement;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.common.api.system.SystemService;
import de.vw.mib.bap.mqbab2.common.api.system.SystemServiceListener;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.DistanceToNextManeuver_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;
import java.util.Iterator;

public final class DistanceToNextManeuver
extends Function
implements Property,
ASLNavSDConstants,
SystemServiceListener,
NavigationServiceListener {
    private static final int NUMBER_OF_VALUES = 2;
    private static final int WUSCHKE_DISTANCE_KM_CONSTANT = 200;
    private static final int WUSCHKE_DISTANCE_MILES_CONSTANT = 125;
    private static final int INVALID_DISTANCE = -1;
    protected static final int[] SYSTEM_LISTENER_IDS = new int[]{1584};
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{732, 741, 751};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToNextManeuver_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile DistanceToNextManeuver INSTANCE = null;
    public static void poke() {
        DistanceToNextManeuver i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: drop system/nav listeners; return the NavState-filled status
        // so the distance is sent at navsd startup (stock returned null here).
        INSTANCE = this;
        DistanceToNextManeuver_Status s = this.dequeueBAPEntity();
        this.setDistanceToNextManeuverStatusData(s);
        return s;
    }

    protected DistanceToNextManeuver_Status dequeueBAPEntity() {
        return (DistanceToNextManeuver_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToNextManeuver_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToNextManeuver_Status = DistanceToNextManeuver.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.DistanceToNextManeuver_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToNextManeuver_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 18;
    }

    private int verifiedBapBargraphSetting() {
        int n;
        NavigationService navigationService = this.getNavigationService();
        NavigationDistanceToNextManeuver navigationDistanceToNextManeuver = navigationService.getDistanceToNextManeuver();
        Iterator iterator = navigationService.getManeuverDescriptor();
        if (iterator.hasNext()) {
            NavigationManeuverDescriptorElement navigationManeuverDescriptorElement = (NavigationManeuverDescriptorElement)iterator.next();
            switch (navigationManeuverDescriptorElement.getMainelement()) {
                case 3: 
                case 4: 
                case 5: 
                case 13: 
                case 14: 
                case 15: 
                case 16: 
                case 17: 
                case 18: 
                case 19: 
                case 20: 
                case 21: 
                case 22: 
                case 23: 
                case 24: 
                case 25: 
                case 26: 
                case 27: 
                case 32: 
                case 33: 
                case 34: 
                case 35: {
                    n = navigationDistanceToNextManeuver.getDistanceToNextManeuverBargraphOnOff();
                    break;
                }
                default: {
                    n = 0;
                    break;
                }
            }
        } else {
            n = navigationDistanceToNextManeuver.getDistanceToNextManeuverBargraphOnOff();
        }
        return n;
    }

    private boolean showDistanceToNextManeuver() {
        boolean bl;
        Iterator iterator = this.getNavigationService().getManeuverDescriptor();
        if (iterator.hasNext()) {
            NavigationManeuverDescriptorElement navigationManeuverDescriptorElement = (NavigationManeuverDescriptorElement)iterator.next();
            switch (navigationManeuverDescriptorElement.getMainelement()) {
                case 8: 
                case 9: 
                case 10: {
                    bl = false;
                    break;
                }
                default: {
                    bl = true;
                    break;
                }
            }
        } else {
            bl = false;
        }
        return bl;
    }

    private void setDistanceToNextManeuverStatusData(DistanceToNextManeuver_Status distanceToNextManeuver_Status) {
        // navsd-shadow delta: distance from NavState (real metres from the shim), but run through the
        // STOCK formatter so the cluster shows the correct figure. Writing raw metres into the field
        // displayed as 1/10 (the cluster expects the formatter's value+unit pair, not raw metres).
        try {
            if (NavState.ACTIVE && NavState.distanceMeters > 0) {
                int[] nArray = new int[2];
                try {
                    if (this.getSystemService().getCurrentDistanceUnit() == 1) {
                        this.getFixFormatter().cnv2Distance2withMinDistanceMiles(NavState.distanceMeters, nArray);
                    } else {
                        this.getFixFormatter().cnvDistance2KilometersExt(NavState.distanceMeters, nArray);
                    }
                    distanceToNextManeuver_Status.distanceToNextManeuver.distance = nArray[0];
                    distanceToNextManeuver_Status.distanceToNextManeuver.unit = nArray[1];
                } catch (Throwable tf) {
                    // formatter/system service unavailable -> raw metres (better than nothing)
                    distanceToNextManeuver_Status.distanceToNextManeuver.distance = NavState.distanceMeters;
                    distanceToNextManeuver_Status.distanceToNextManeuver.unit = 0;
                }
                distanceToNextManeuver_Status.bargraphInfo.bargraphOnOff = 1;
                distanceToNextManeuver_Status.bargraphInfo.bargraph = Math.min(100, NavState.distanceMeters * 100 / 300);
                distanceToNextManeuver_Status.validityInformation.distanceToNextManeuverValid = true;
            } else {
                distanceToNextManeuver_Status.distanceToNextManeuver.distance = -1;
                distanceToNextManeuver_Status.distanceToNextManeuver.unit = 255;
                distanceToNextManeuver_Status.bargraphInfo.bargraphOnOff = 0;
                distanceToNextManeuver_Status.validityInformation.distanceToNextManeuverValid = false;
            }
        } catch (Throwable t) {
            distanceToNextManeuver_Status.validityInformation.distanceToNextManeuverValid = false;
        }
    }

    private void sendDistanceToNextManeuverStatus(DistanceToNextManeuver_Status distanceToNextManeuver_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(distanceToNextManeuver_Status, this);
    }

    public void process(int n) {
        DistanceToNextManeuver_Status distanceToNextManeuver_Status = this.dequeueBAPEntity();
        this.setDistanceToNextManeuverStatusData(distanceToNextManeuver_Status);
        this.sendDistanceToNextManeuverStatus(distanceToNextManeuver_Status);
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
        // navsd-shadow delta: no listeners were registered, nothing to remove.
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    private void computeDistanceAndDistanceUnit(DistanceToNextManeuver_Status distanceToNextManeuver_Status, boolean bl) {
        if (bl) {
            NavigationDistanceToNextManeuver navigationDistanceToNextManeuver = this.getNavigationService().getDistanceToNextManeuver();
            int[] nArray = new int[2];
            switch (this.getSystemService().getCurrentDistanceUnit()) {
                case 1: {
                    this.getFixFormatter().cnv2Distance2withMinDistanceMiles(navigationDistanceToNextManeuver.getDistanceToNextManeuverDistance(), nArray);
                    break;
                }
                default: {
                    this.getFixFormatter().cnvDistance2KilometersExt(navigationDistanceToNextManeuver.getDistanceToNextManeuverDistance(), nArray);
                }
            }
            distanceToNextManeuver_Status.distanceToNextManeuver.distance = nArray[0];
            distanceToNextManeuver_Status.distanceToNextManeuver.unit = nArray[1];
        } else {
            distanceToNextManeuver_Status.distanceToNextManeuver.distance = -1;
            distanceToNextManeuver_Status.distanceToNextManeuver.unit = 255;
        }
    }

    public void processHMIEvent(int n) {
    }

    public void updateNavigationData(NavigationService navigationService, int n) {
        this.process(-1);
    }

    public void updateSystemData(SystemService systemService, int n) {
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
