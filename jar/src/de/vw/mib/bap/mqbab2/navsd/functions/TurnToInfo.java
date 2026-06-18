/*
 * Decompiled with CFR 0.152. navsd-shadow: drive turnToInfo (the "turn into <street>" line the
 * cluster Navigation menu renders) from NavState.street instead of the absent nav engine.
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
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.TurnToInfo_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public class TurnToInfo
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener {
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{744};
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TurnToInfo_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile TurnToInfo INSTANCE = null;
    public static void poke() {
        TurnToInfo i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: drop the nav-service listener (we drive from NavState).
        INSTANCE = this;
        return this.computeTurnToInfoStatus();
    }

    protected TurnToInfo_Status dequeueBAPEntity() {
        return (TurnToInfo_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TurnToInfo_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TurnToInfo_Status = TurnToInfo.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.TurnToInfo_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$TurnToInfo_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 20;
    }

    public void process(int n) {
        this.sendTurnToInfoStatus(this.computeTurnToInfoStatus());
    }

    private TurnToInfo_Status computeTurnToInfoStatus() {
        TurnToInfo_Status turnToInfo_Status = this.dequeueBAPEntity();
        // navsd-shadow delta: street straight from NavState (the AA next-turn road), not the nav engine.
        try {
            if (NavState.ACTIVE && NavState.street != null) {
                turnToInfo_Status.turnToInfo.setContent(NavState.street);
            } else {
                turnToInfo_Status.turnToInfo.setContent("");
            }
            turnToInfo_Status.signPost.setContent("");
        } catch (Throwable t) {
            // leave empty on any failure; never crash navsd
        }
        return turnToInfo_Status;
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

    private void sendTurnToInfoStatus(TurnToInfo_Status turnToInfo_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(turnToInfo_Status, this);
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
