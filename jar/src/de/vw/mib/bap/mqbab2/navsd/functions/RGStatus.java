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
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.RG_Status_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public final class RGStatus
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener {
    private Boolean instrumentClusterActionStatus = null;
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{732, 733};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RG_Status_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile RGStatus INSTANCE = null;
    public static void poke() {
        RGStatus i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: drop the nav-service listener (we drive from NavState).
        INSTANCE = this;
        return this.computeRGStatusStatus();
    }

    protected RG_Status_Status dequeueBAPEntity() {
        return (RG_Status_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RG_Status_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RG_Status_Status = RGStatus.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.RG_Status_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RG_Status_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
        switch (bAPStage.getFunctionId()) {
            case 34: {
                this.setActionStateRunning((Boolean)object);
                break;
            }
        }
    }

    public int getFunctionId() {
        return 17;
    }

    private void setRouteGuidanceStatus(RG_Status_Status rG_Status_Status) {
        // navsd-shadow delta: route-guidance active flag straight from NavState
        // instead of deriving it from the absent/non-routing nav engine.
        try {
            rG_Status_Status.rg_Status = NavState.ACTIVE ? 1 : 0;
        } catch (Throwable t) {
            rG_Status_Status.rg_Status = 0;
        }
    }

    private void sendRouteGuidanceStatus(RG_Status_Status rG_Status_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(rG_Status_Status, this);
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
        this.process(-1);
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

    public void process(int n) {
        this.sendRouteGuidanceStatus(this.computeRGStatusStatus());
    }

    private RG_Status_Status computeRGStatusStatus() {
        RG_Status_Status rG_Status_Status = this.dequeueBAPEntity();
        this.setRouteGuidanceStatus(rG_Status_Status);
        return rG_Status_Status;
    }

    protected void setActionStateRunning(Boolean bl) {
        this.instrumentClusterActionStatus = bl;
        this.process(-1);
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
