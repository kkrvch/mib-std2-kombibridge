/* PQ navsd DistanceTimeToDestination shadow (fid 21): distance AND remaining travel time to the
 * destination from NavState when AA active. PQ merges into this one function what MQB splits over
 * DistanceToDestination (fn 21) and TimeToDestination (fn 22), which is why there is one shadow here
 * and two there. */
package de.vw.mib.bap.mqbpq.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunction;
import de.vw.mib.bap.functions.BAPFunctionController;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.navsd.functions.AANavReader;
import de.vw.mib.bap.mqbab2.navsd.functions.NavState;
import de.vw.mib.bap.mqbpq.ServiceManager;
import de.vw.mib.bap.mqbpq.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbpq.generated.navsd.serializer.DistanceTimeToDestination_Status;
import de.vw.mib.bap.mqbpq.navsd.api.stages.DistanceTimeToDestinationStage;

public final class DistanceTimeToDestination
extends DistanceTimeToDestinationStage {
    private static final int NUMBER_OF_VALUES = 0;
    private static final int INVALID_DISTANCE = 0;
    private static final int INVALID_TIME = 0;

    private static volatile DistanceTimeToDestination INSTANCE = null;
    public static boolean isActive() { return INSTANCE != null; }
    public static void poke() {
        DistanceTimeToDestination i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        super.init(bAPStageInitializer);
        INSTANCE = this;
        return this.computeDistanceToDestinationStatus();
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void process(int n) {
        this.sendDistanceToDestination(this.computeDistanceToDestinationStatus());
    }

    private static int formatTime(int n) {
        return n >= 0 ? n : -1;
    }

    private static int formatDate(int n) {
        return n > 0 ? n : -1;
    }

    /** The Android Auto source: remaining travel time, never an arrival clock (see MQB TimeToDestination). */
    private void setTimeInfoFromAndroidAuto(DistanceTimeToDestination_Status distanceTimeToDestination_Status) {
        int seconds = NavState.destinationSeconds;
        try {
            distanceTimeToDestination_Status.timeInfo.navigationTimeFormat = this.getSystemTimeFormat() == 0 ? 1 : 0;
        } catch (Throwable t) {
            distanceTimeToDestination_Status.timeInfo.navigationTimeFormat = 0;
        }
        int[] hm = AANavReader.remainingHoursMinutes(seconds);
        // timeInfoType 0 = remaining travel time (the arrival-clock form is type 1 and needs the
        // calendar fields, which a duration does not have). -1 is this function's "no value": the
        // stock writes it whenever its own guidance is not running, so the cluster knows the shape.
        distanceTimeToDestination_Status.timeInfo.timeInfoType = 0;
        distanceTimeToDestination_Status.timeInfo.hour = seconds > 0 ? hm[0] : -1;
        distanceTimeToDestination_Status.timeInfo.minute = seconds > 0 ? hm[1] : -1;
        distanceTimeToDestination_Status.timeInfo.day = -1;
        distanceTimeToDestination_Status.timeInfo.month = -1;
        distanceTimeToDestination_Status.timeInfo.year = -1;
    }

    private void setTimeInfo(DistanceTimeToDestination_Status distanceTimeToDestination_Status) {
        int n;
        int n2;
        int n3;
        int n4;
        int n5;
        if (NavState.ACTIVE) {   // AA active -> NavState.destinationSeconds; else stock
            this.setTimeInfoFromAndroidAuto(distanceTimeToDestination_Status);
            return;
        }
        distanceTimeToDestination_Status.timeInfo.navigationTimeFormat = this.getSystemTimeFormat() == 0 ? 1 : 0;
        if (this.getTimeToDestinationTimeInfo() == 1) {
            n5 = DistanceTimeToDestination.formatTime(this.getTimeToDestinationMinutes());
            n4 = DistanceTimeToDestination.formatTime(this.getTimeToDestinationHours());
            n3 = DistanceTimeToDestination.formatDate(this.getTimeToDestinationDay());
            n2 = DistanceTimeToDestination.formatDate(this.getTimeToDestinationMonth());
            n = DistanceTimeToDestination.formatDate(this.getTimeToDestinationYear());
        } else {
            n5 = DistanceTimeToDestination.formatTime(this.getRemainingTravelTimeMinute());
            n4 = DistanceTimeToDestination.formatTime(this.getRemainingTravelTimeHour());
            n3 = -1;
            n2 = -1;
            n = -1;
        }
        if (this.getRouteGuidanceState() != 1) {
            n5 = -1;
            n4 = -1;
            n3 = -1;
            n2 = -1;
            n = -1;
        }
        distanceTimeToDestination_Status.timeInfo.timeInfoType = this.getTimeToDestinationTimeInfo();
        distanceTimeToDestination_Status.timeInfo.minute = n5;
        distanceTimeToDestination_Status.timeInfo.hour = n4;
        distanceTimeToDestination_Status.timeInfo.day = n3;
        distanceTimeToDestination_Status.timeInfo.month = n2;
        distanceTimeToDestination_Status.timeInfo.year = n;
    }

    private void setDistanceAndUnit(DistanceTimeToDestination_Status distanceTimeToDestination_Status) {
        if (NavState.ACTIVE) {   // AA active -> NavState.destinationMeters through the stock formatter
            int meters = NavState.destinationMeters;
            if (meters <= 0) {
                distanceTimeToDestination_Status.distanceToDestination.distance = -1;
                distanceTimeToDestination_Status.distanceToDestination.unit = 255;
                return;
            }
            int[] nArray = new int[2];
            try {
                switch (this.getCurrentDistanceUnit()) {
                    case 1: {
                        ServiceManager.serviceManager.fixFormat.cnv2Distance1Miles(meters, nArray);
                        break;
                    }
                    default: {
                        ServiceManager.serviceManager.fixFormat.cnv2Distance1Kilometers(meters, nArray);
                    }
                }
                distanceTimeToDestination_Status.distanceToDestination.distance = nArray[0];
                distanceTimeToDestination_Status.distanceToDestination.unit = nArray[1];
            } catch (Throwable t) {
                // formatter unavailable -> raw metres, as the next-manoeuvre distance shadow does.
                distanceTimeToDestination_Status.distanceToDestination.distance = meters;
                distanceTimeToDestination_Status.distanceToDestination.unit = 0;
            }
            return;
        }
        if (this.getDistanceToDestinationUnit() == 255) {
            int[] nArray = new int[2];
            switch (this.getCurrentDistanceUnit()) {
                case 1: {
                    ServiceManager.serviceManager.fixFormat.cnv2Distance1Miles(this.getDistanceToDestinationDistance(), nArray);
                    break;
                }
                default: {
                    ServiceManager.serviceManager.fixFormat.cnv2Distance1Kilometers(this.getDistanceToDestinationDistance(), nArray);
                }
            }
            distanceTimeToDestination_Status.distanceToDestination.unit = nArray[1];
            distanceTimeToDestination_Status.distanceToDestination.distance = nArray[0];
        } else {
            distanceTimeToDestination_Status.distanceToDestination.unit = this.getDistanceToDestinationUnit();
            distanceTimeToDestination_Status.distanceToDestination.distance = this.getDistanceToDestinationDistance();
        }
    }

    private void setValidityInformation(DistanceTimeToDestination_Status distanceTimeToDestination_Status) {
        // navsd-shadow delta: the stock asks the unit's OWN route guidance, which is not running while
        // AA guides — it would blank everything we just wrote. Gate on our own state instead.
        if (NavState.ACTIVE) {
            return;
        }
        if (this.getRouteGuidanceState() != 1) {
            distanceTimeToDestination_Status.distanceToDestination.distance = -1;
        }
    }

    private void sendDistanceToDestination(DistanceTimeToDestination_Status distanceTimeToDestination_Status) {
        this.getDelegate().getPropertyListener((BAPFunctionController)this).statusProperty((BAPEntity)distanceTimeToDestination_Status, (Property)this);
    }

    private DistanceTimeToDestination_Status computeDistanceToDestinationStatus() {
        DistanceTimeToDestination_Status distanceTimeToDestination_Status = this.dequeueBAPEntity();
        this.setDistanceAndUnit(distanceTimeToDestination_Status);
        this.setTimeInfo(distanceTimeToDestination_Status);
        this.setValidityInformation(distanceTimeToDestination_Status);
        return distanceTimeToDestination_Status;
    }
}
