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
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.iterator.elements.NavigationManeuverDescriptorElement;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.ManeuverDescriptor_Status;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.ManeuverDescriptor_Status$Maneuver_1;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;
import java.util.Iterator;

public final class ManeuverDescriptor
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener {
    private static final int MANEUVER_ZERO = 0;
    private static final int MANEUVER_ONE = 1;
    private static final int MANEUVER_TWO = 2;
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{732, 751};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ManeuverDescriptor_Status;

    // navsd-shadow delta: live re-send hook. NavShmemReader updates NavState then calls poke()
    // (on the framework timer thread) to push the new maneuver as a BAP status.
    private static volatile ManeuverDescriptor INSTANCE = null;
    public static void poke() {
        ManeuverDescriptor i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: drop the nav-service listener (we drive from NavState).
        INSTANCE = this;
        return this.computeManeuverDescriptorStatus();
    }

    protected ManeuverDescriptor_Status dequeueBAPEntity() {
        return (ManeuverDescriptor_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ManeuverDescriptor_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ManeuverDescriptor_Status = ManeuverDescriptor.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.ManeuverDescriptor_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ManeuverDescriptor_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 23;
    }

    private int mapToBAPMainElement(int n) {
        int n2;
        switch (n) {
            case 33: {
                n2 = 16;
                break;
            }
            case 32: {
                n2 = 15;
                break;
            }
            default: {
                n2 = n;
            }
        }
        return n2;
    }

    private ManeuverDescriptor_Status$Maneuver_1 validateManeuverData(int n, int n2, String string, int n3) {
        ManeuverDescriptor_Status$Maneuver_1 maneuverDescriptor_Status$Maneuver_1 = new ManeuverDescriptor_Status$Maneuver_1();
        maneuverDescriptor_Status$Maneuver_1.direction = n;
        maneuverDescriptor_Status$Maneuver_1.mainElement = this.mapToBAPMainElement(n2);
        maneuverDescriptor_Status$Maneuver_1.sidestreets.setContent(string);
        maneuverDescriptor_Status$Maneuver_1.zLevelGuidance = n3;
        switch (maneuverDescriptor_Status$Maneuver_1.mainElement) {
            case 1: 
            case 2: 
            case 3: 
            case 4: 
            case 5: 
            case 8: 
            case 9: 
            case 10: 
            case 34: 
            case 35: 
            case 38: {
                maneuverDescriptor_Status$Maneuver_1.zLevelGuidance = 0;
                maneuverDescriptor_Status$Maneuver_1.sidestreets.setEmptyString();
                break;
            }
            case 11: {
                maneuverDescriptor_Status$Maneuver_1.zLevelGuidance = 0;
                break;
            }
            case 17: 
            case 18: 
            case 19: 
            case 20: 
            case 25: 
            case 26: 
            case 27: 
            case 28: 
            case 29: 
            case 30: 
            case 31: {
                maneuverDescriptor_Status$Maneuver_1.sidestreets.setEmptyString();
                break;
            }
        }
        return maneuverDescriptor_Status$Maneuver_1;
    }

    private void fillManeuverDescriptor(ManeuverDescriptor_Status maneuverDescriptor_Status) {
        ManeuverDescriptor_Status$Maneuver_1 maneuverDescriptor_Status$Maneuver_1;
        NavigationManeuverDescriptorElement navigationManeuverDescriptorElement;
        NavigationService navigationService = this.getNavigationService();
        Iterator iterator = navigationService.getManeuverDescriptor();
        int n = 0;
        while (iterator.hasNext()) {
            iterator.next();
            ++n;
        }
        iterator = navigationService.getManeuverDescriptor();
        if (n > 0) {
            navigationManeuverDescriptorElement = (NavigationManeuverDescriptorElement)iterator.next();
            maneuverDescriptor_Status$Maneuver_1 = this.validateManeuverData(navigationManeuverDescriptorElement.getDirection(), navigationManeuverDescriptorElement.getMainelement(), navigationManeuverDescriptorElement.getSideStreets(), navigationManeuverDescriptorElement.getZLevelGuidance());
            maneuverDescriptor_Status.maneuver_1.direction = maneuverDescriptor_Status$Maneuver_1.direction;
            maneuverDescriptor_Status.maneuver_1.mainElement = maneuverDescriptor_Status$Maneuver_1.mainElement;
            maneuverDescriptor_Status.maneuver_1.sidestreets.setContent(maneuverDescriptor_Status$Maneuver_1.sidestreets);
            maneuverDescriptor_Status.maneuver_1.zLevelGuidance = maneuverDescriptor_Status$Maneuver_1.zLevelGuidance;
        }
        if (n > 1) {
            navigationManeuverDescriptorElement = (NavigationManeuverDescriptorElement)iterator.next();
            maneuverDescriptor_Status$Maneuver_1 = this.validateManeuverData(navigationManeuverDescriptorElement.getDirection(), navigationManeuverDescriptorElement.getMainelement(), navigationManeuverDescriptorElement.getSideStreets(), navigationManeuverDescriptorElement.getZLevelGuidance());
            maneuverDescriptor_Status.maneuver_2.direction = maneuverDescriptor_Status$Maneuver_1.direction;
            maneuverDescriptor_Status.maneuver_2.mainElement = maneuverDescriptor_Status$Maneuver_1.mainElement;
            maneuverDescriptor_Status.maneuver_2.sidestreets.setContent(maneuverDescriptor_Status$Maneuver_1.sidestreets);
            maneuverDescriptor_Status.maneuver_2.zLevelGuidance = maneuverDescriptor_Status$Maneuver_1.zLevelGuidance;
        }
        if (n > 2) {
            navigationManeuverDescriptorElement = (NavigationManeuverDescriptorElement)iterator.next();
            maneuverDescriptor_Status$Maneuver_1 = this.validateManeuverData(navigationManeuverDescriptorElement.getDirection(), navigationManeuverDescriptorElement.getMainelement(), navigationManeuverDescriptorElement.getSideStreets(), navigationManeuverDescriptorElement.getZLevelGuidance());
            maneuverDescriptor_Status.maneuver_3.direction = maneuverDescriptor_Status$Maneuver_1.direction;
            maneuverDescriptor_Status.maneuver_3.mainElement = maneuverDescriptor_Status$Maneuver_1.mainElement;
            maneuverDescriptor_Status.maneuver_3.sidestreets.setContent(maneuverDescriptor_Status$Maneuver_1.sidestreets);
            maneuverDescriptor_Status.maneuver_3.zLevelGuidance = maneuverDescriptor_Status$Maneuver_1.zLevelGuidance;
        }
    }

    private void sendManeuverDescriptor(ManeuverDescriptor_Status maneuverDescriptor_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(maneuverDescriptor_Status, this);
    }

    public void process(int n) {
        this.sendManeuverDescriptor(this.computeManeuverDescriptorStatus());
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
        // navsd-shadow delta: no listener was registered, nothing to remove.
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    private ManeuverDescriptor_Status computeManeuverDescriptorStatus() {
        ManeuverDescriptor_Status maneuverDescriptor_Status = this.dequeueBAPEntity();
        // navsd-shadow delta: do NOT gate on getRouteGuidanceState() (always 0 when our
        // own nav engine isn't routing); fill from NavState instead of the nav service.
        // The stock cleanup switch in validateManeuverData is reused verbatim.
        try {
            if (NavState.ACTIVE) {
                // sidestreets ("" here): that field is the JUNCTION side-road geometry, not a label.
                // Feeding the road name made the cluster draw a spurious unfilled approach road before
                // the turn. The street name lives in TurnToInfo; AA gives no side-road geometry -> empty.
                ManeuverDescriptor_Status$Maneuver_1 m = this.validateManeuverData(
                        NavState.direction, NavState.mainElement, "", NavState.zLevelGuidance);
                maneuverDescriptor_Status.maneuver_1.direction = m.direction;
                maneuverDescriptor_Status.maneuver_1.mainElement = m.mainElement;
                maneuverDescriptor_Status.maneuver_1.sidestreets.setContent(m.sidestreets);
                maneuverDescriptor_Status.maneuver_1.zLevelGuidance = m.zLevelGuidance;
            }
        } catch (Throwable t) {
            // leave status empty (== stock no-route); a throw here crashes navsd startup
        }
        return maneuverDescriptor_Status;
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
