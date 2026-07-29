/*
 * Decompiled with CFR 0.152. navsd-shadow: distance to the destination (fn 21) from Android Auto.
 *
 * Stock body kept verbatim for the unit's own navigation; the deltas are the INSTANCE/poke() hook
 * and an AA branch in setDistanceAndUnit()/setValidityInformation().
 *
 * The metres go through the STOCK fix formatter exactly as the stock path does: BAP carries a
 * value plus a unit code, not text, and the pair has to follow the car's own km/miles setting —
 * which is why the phone's ready-made "11 km" string is not used here (unlike the manoeuvre
 * countdown on the media widget, where a string is all there is).
 *
 * Java 1.3: de-genericised, @Override dropped, and the stock's private constants removed — the
 * firmware's class file no longer carries their values.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationDistanceToDestination;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.common.api.system.SystemService;
import de.vw.mib.bap.mqbab2.common.api.system.SystemServiceListener;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.DistanceToDestination_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public final class DistanceToDestination
extends Function
implements Property,
ASLNavSDConstants,
SystemServiceListener,
NavigationServiceListener {
    protected static final int[] SYSTEM_LISTENER_IDS;
    protected static final int[] NAVIGATION_LISTENER_IDS;
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToDestination_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile DistanceToDestination INSTANCE = null;
    public static void poke() {
        DistanceToDestination i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: the INSTANCE latch, and the listeners only on a nav-capable cluster
        // (see TimeToDestination.init). The stock return value — the computed status — is kept, and
        // at startup AA is inactive, so it is the stock branch that fills it.
        INSTANCE = this;
        if (ClusterCaps.isNavCapable()) {
            try {
                this.getSystemService().addSystemServiceListener(this, SYSTEM_LISTENER_IDS);
                this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS);
            } catch (Throwable t) {}
        }
        return this.computeDistanceToDestinationStatus();
    }

    protected DistanceToDestination_Status dequeueBAPEntity() {
        return (DistanceToDestination_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToDestination_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToDestination_Status = DistanceToDestination.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.DistanceToDestination_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$DistanceToDestination_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 21;
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
        // navsd-shadow delta: listeners were only registered on a nav-capable cluster (see init).
        if (ClusterCaps.isNavCapable()) {
            try {
                this.getSystemService().removeSystemServiceListener(this, SYSTEM_LISTENER_IDS);
                this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS);
            } catch (Throwable t) {}
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
        this.sendDistanceToDestination(this.computeDistanceToDestinationStatus());
    }

    private void setDistanceAndUnit(DistanceToDestination_Status distanceToDestination_Status) {
        // navsd-shadow delta (see the class comment for why the formatter is not bypassed).
        if (ClusterCaps.isNavCapable() && NavState.ACTIVE) {
            this.setDistanceAndUnitFromAndroidAuto(distanceToDestination_Status);
            return;
        }
        NavigationDistanceToDestination navigationDistanceToDestination = this.getNavigationService().getDistanceToDestination();
        switch (navigationDistanceToDestination.getDistanceToDestinationType()) {
            case 1: {
                distanceToDestination_Status.distanceToDestinationType.distanceToStopover = true;
                break;
            }
            default: {
                distanceToDestination_Status.distanceToDestinationType.distanceToStopover = false;
            }
        }
        if (navigationDistanceToDestination.getDistanceToDestinationDistanceUnit() == 255) {
            int[] nArray = new int[2];
            switch (this.getSystemService().getCurrentDistanceUnit()) {
                case 1: {
                    this.getFixFormatter().cnv2Distance1Miles(navigationDistanceToDestination.getDistanceToDestinationDistance(), nArray);
                    break;
                }
                default: {
                    this.getFixFormatter().cnv2Distance1Kilometers(navigationDistanceToDestination.getDistanceToDestinationDistance(), nArray);
                }
            }
            distanceToDestination_Status.distanceToDestination.unit = nArray[1];
            distanceToDestination_Status.distanceToDestination.distance = nArray[0];
        } else {
            distanceToDestination_Status.distanceToDestination.unit = navigationDistanceToDestination.getDistanceToDestinationDistanceUnit();
            distanceToDestination_Status.distanceToDestination.distance = navigationDistanceToDestination.getDistanceToDestinationDistance();
        }
    }

    /** The Android Auto source for the block above. */
    private void setDistanceAndUnitFromAndroidAuto(DistanceToDestination_Status distanceToDestination_Status) {
        // AA's destination list is nearest-first and says nothing about what kind of stop each entry
        // is, so a waypoint cannot be told from the final destination: report the neutral value the
        // stock uses for everything that is not explicitly a stopover.
        distanceToDestination_Status.distanceToDestinationType.distanceToStopover = false;
        int meters = NavState.destinationMeters;
        int[] nArray = new int[2];
        try {
            if (this.getSystemService().getCurrentDistanceUnit() == 1) {
                this.getFixFormatter().cnv2Distance1Miles(meters, nArray);
            } else {
                this.getFixFormatter().cnv2Distance1Kilometers(meters, nArray);
            }
            distanceToDestination_Status.distanceToDestination.distance = nArray[0];
            distanceToDestination_Status.distanceToDestination.unit = nArray[1];
        } catch (Throwable t) {
            // formatter/system service unavailable -> raw metres (better than nothing), as the
            // next-manoeuvre distance shadow does.
            distanceToDestination_Status.distanceToDestination.distance = meters;
            distanceToDestination_Status.distanceToDestination.unit = 0;
        }
    }

    private void setValidityInformation(DistanceToDestination_Status distanceToDestination_Status) {
        // navsd-shadow delta: the stock asks the unit's OWN route guidance, which is not running
        // while AA guides — the same trap that kept the lane row empty. Gate on our own state.
        if (ClusterCaps.isNavCapable() && NavState.ACTIVE) {
            distanceToDestination_Status.validityInformation.distanceToDestinationValid = NavState.destinationMeters > 0;
            return;
        }
        distanceToDestination_Status.validityInformation.distanceToDestinationValid = this.getNavigationService().getRouteGuidanceState() == 1;
    }

    private void sendDistanceToDestination(DistanceToDestination_Status distanceToDestination_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(distanceToDestination_Status, this);
    }

    private DistanceToDestination_Status computeDistanceToDestinationStatus() {
        DistanceToDestination_Status distanceToDestination_Status = this.dequeueBAPEntity();
        this.setDistanceAndUnit(distanceToDestination_Status);
        this.setValidityInformation(distanceToDestination_Status);
        return distanceToDestination_Status;
    }

    public void processHMIEvent(int n) {
    }

    public void updateNavigationData(NavigationService navigationService, int n) {
        this.process(-1);
    }

    public void updateSystemData(SystemService systemService, int n) {
        this.process(-1);
    }

    static Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            // -source 1.3 has no Throwable.initCause; same form the other shadows use.
            throw new NoClassDefFoundError(string);
        }
    }

    static {
        SYSTEM_LISTENER_IDS = new int[]{1584};
        NAVIGATION_LISTENER_IDS = new int[]{732, 749};
    }
}
