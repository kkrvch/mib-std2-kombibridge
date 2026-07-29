/*
 * Decompiled with CFR 0.152. navsd-shadow: remaining travel time to the destination (fn 22) from
 * Android Auto.
 *
 * The stock body is kept verbatim for the case it was written for — the unit's own navigation. The
 * only delta is the INSTANCE/poke() hook and an AA branch in setTimeInfo(), taken while Android Auto
 * guides, when the nav engine has nothing to say.
 *
 * The BAP time field carries either of two forms, chosen by timeInfoType: a clock time of ARRIVAL,
 * or the REMAINING travel time. AA supplies both (an "17:11" string and a seconds count); we send
 * the remaining time, which needs no assumption about the phone's timezone agreeing with the car's.
 *
 * Java 1.3: de-genericised, @Override dropped, and NoClassDefFoundError.initCause spelled the way
 * the other shadows spell it.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationRemainingTravelTime;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationTimeToDestination;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.common.api.system.SystemService;
import de.vw.mib.bap.mqbab2.common.api.system.SystemServiceListener;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.TimeToDestination_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public final class TimeToDestination
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener,
SystemServiceListener {
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{732, 737153024, 750, 753930240};
    protected static final int[] SYSTEM_LISTENER_IDS = new int[]{1643};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TimeToDestination_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile TimeToDestination INSTANCE = null;
    public static void poke() {
        TimeToDestination i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: the INSTANCE latch, and the listeners only on a nav-capable cluster —
        // the same rule DistanceToNextManeuver follows, so a non-nav unit (where the shadows are
        // still constructed by the "All" binding factory) never gets navsd traffic pushed at it.
        // The stock return value (null) is kept: this function is driven by poke().
        INSTANCE = this;
        if (ClusterCaps.isNavCapable()) {
            try {
                this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS);
                this.getSystemService().addSystemServiceListener(this, SYSTEM_LISTENER_IDS);
            } catch (Throwable t) {}
        }
        return null;
    }

    protected TimeToDestination_Status dequeueBAPEntity() {
        return (TimeToDestination_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TimeToDestination_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TimeToDestination_Status = TimeToDestination.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.TimeToDestination_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TimeToDestination_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 22;
    }

    private static int formatTime(int n) {
        return n > 0 ? n : 0;
    }

    private void setTimeInfo(TimeToDestination_Status timeToDestination_Status) {
        int n;
        int n2;
        int n3;
        int n4;
        int n5;
        // navsd-shadow delta. isNavCapable() is not redundant with ACTIVE — it also keeps this MQB
        // function stock on a PQ unit, where AANavReader's own navCapable() answers true.
        if (ClusterCaps.isNavCapable() && NavState.ACTIVE) {
            this.setTimeInfoFromAndroidAuto(timeToDestination_Status);
            return;
        }
        NavigationService navigationService = this.getNavigationService();
        timeToDestination_Status.timeInfo.navigationTimeFormat = this.getSystemService().getSystemTimeFormat() == 0 ? 1 : 0;
        if (navigationService.getTimeToDestinationTimeInfo() == 1) {
            NavigationTimeToDestination navigationTimeToDestination = navigationService.getTimeToDestination();
            n5 = navigationTimeToDestination.getTimeToDestinationMinutes();
            n4 = navigationTimeToDestination.getTimeToDestinationHours();
            n3 = navigationTimeToDestination.getTimeToDestinationDays();
            n2 = navigationTimeToDestination.getTimeToDestinationMonths();
            n = navigationTimeToDestination.getTimeToDestinationYears();
        } else {
            NavigationRemainingTravelTime navigationRemainingTravelTime = navigationService.getRemainingTravelTime();
            n5 = navigationRemainingTravelTime.getRemainingTravelTimeMinutes();
            n4 = navigationRemainingTravelTime.getRemainingTravelTimeHours();
            n3 = 0;
            n2 = 0;
            n = 0;
        }
        if (navigationService.getRouteGuidanceState() == 1) {
            timeToDestination_Status.validityInformation.timeInfo_MinuteIsValid = n5 >= 0;
            timeToDestination_Status.validityInformation.timeInfo_HourIsValid = n4 >= 0;
            timeToDestination_Status.validityInformation.timeInfo_DayIsAvailableToBeDisplayed = n3 > 0;
            timeToDestination_Status.validityInformation.timeInfo_MonthIsAvailableToBeDisplayed = n2 > 0;
            timeToDestination_Status.validityInformation.timeInfo_YearIsAvailableToBeDisplayed = n > 0;
        } else {
            timeToDestination_Status.validityInformation.timeInfo_MinuteIsValid = false;
            timeToDestination_Status.validityInformation.timeInfo_HourIsValid = false;
            timeToDestination_Status.validityInformation.timeInfo_DayIsAvailableToBeDisplayed = false;
            timeToDestination_Status.validityInformation.timeInfo_MonthIsAvailableToBeDisplayed = false;
            timeToDestination_Status.validityInformation.timeInfo_YearIsAvailableToBeDisplayed = false;
        }
        timeToDestination_Status.timeInfo.timeInfoType = navigationService.getTimeToDestinationTimeInfo();
        timeToDestination_Status.timeInfo.minute = TimeToDestination.formatTime(n5);
        timeToDestination_Status.timeInfo.hour = TimeToDestination.formatTime(n4);
        timeToDestination_Status.timeInfo.day = TimeToDestination.formatTime(n3);
        timeToDestination_Status.timeInfo.month = TimeToDestination.formatTime(n2);
        timeToDestination_Status.timeInfo.year = TimeToDestination.formatTime(n);
    }

    /**
     * The Android Auto source for the block above. Note what is NOT set: the stock reads
     * getTimeToDestinationTimeInfo() to decide the form, but that is the nav engine's own preference
     * and it is not running — we can only produce the remaining-time form, so we say so explicitly.
     * The seconds -> h:mm conversion lives in AANavReader with the other AA -> cluster mappings,
     * where it is covered by the host test.
     */
    private void setTimeInfoFromAndroidAuto(TimeToDestination_Status timeToDestination_Status) {
        int seconds = NavState.destinationSeconds;
        boolean valid = seconds > 0;
        try {
            timeToDestination_Status.timeInfo.navigationTimeFormat = this.getSystemService().getSystemTimeFormat() == 0 ? 1 : 0;
        } catch (Throwable t) {
            timeToDestination_Status.timeInfo.navigationTimeFormat = 0;
        }
        int[] hm = AANavReader.remainingHoursMinutes(seconds);
        timeToDestination_Status.timeInfo.timeInfoType = 0;   // remaining travel time, not arrival clock
        timeToDestination_Status.timeInfo.hour = hm[0];
        timeToDestination_Status.timeInfo.minute = hm[1];
        timeToDestination_Status.timeInfo.day = 0;
        timeToDestination_Status.timeInfo.month = 0;
        timeToDestination_Status.timeInfo.year = 0;
        timeToDestination_Status.validityInformation.timeInfo_MinuteIsValid = valid;
        timeToDestination_Status.validityInformation.timeInfo_HourIsValid = valid;
        // A remaining time is a duration, so the calendar fields never apply — the stock only ever
        // sets them for the arrival-clock form.
        timeToDestination_Status.validityInformation.timeInfo_DayIsAvailableToBeDisplayed = false;
        timeToDestination_Status.validityInformation.timeInfo_MonthIsAvailableToBeDisplayed = false;
        timeToDestination_Status.validityInformation.timeInfo_YearIsAvailableToBeDisplayed = false;
    }

    private void sendtimeToDestinationStatus(TimeToDestination_Status timeToDestination_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(timeToDestination_Status, this);
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
                this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS);
                this.getSystemService().removeSystemServiceListener(this, SYSTEM_LISTENER_IDS);
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
        this.sendtimeToDestinationStatus(this.computeTimeToDestinationStatus());
    }

    private TimeToDestination_Status computeTimeToDestinationStatus() {
        TimeToDestination_Status timeToDestination_Status = this.dequeueBAPEntity();
        this.setTimeInfo(timeToDestination_Status);
        return timeToDestination_Status;
    }

    public void processHMIEvent(int n) {
    }

    public void updateSystemData(SystemService systemService, int n) {
        this.process(-1);
    }

    public void updateNavigationData(NavigationService navigationService, int n) {
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
}
