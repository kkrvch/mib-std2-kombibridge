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
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.NavigationTurnToInfo;
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
        if (i != null) { try { i.process(-1); } catch (Throwable t) { de.aatokombi.Quiet.note("TurnToInfo#1", t); } }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: by default drop the nav-service listener (we drive from NavState).
        // On a nav-capable cluster register it so the unit's own nav re-sends when AA is inactive.
        INSTANCE = this;
        if (ClusterCaps.isNavCapable()) {
            try { this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) { de.aatokombi.Quiet.note("TurnToInfo#2", t); }
        }
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

    /**
     * Stock method, unchanged: the unit's own next-turn street and signpost. It is part of what the
     * stock class offers its subclasses - TurnToInfoArabic (not shadowed) overrides it and calls
     * this one - so the shadow must have it (found by tools/check_shadows.py api).
     */
    protected void setTurnToInfoStatusData(TurnToInfo_Status turnToInfo_Status) {
        NavigationTurnToInfo n = this.getNavigationService().getTurnToInfo();
        turnToInfo_Status.turnToInfo.setContent(n.getTurnToInfoStreet());
        turnToInfo_Status.signPost.setContent(n.getTurnToInfoSignPost());
    }

    private TurnToInfo_Status computeTurnToInfoStatus() {
        TurnToInfo_Status turnToInfo_Status = this.dequeueBAPEntity();
        // navsd-shadow delta: street straight from NavState (the AA next-turn road); on a nav-capable
        // cluster, when AA is inactive, the unit's own next-turn street + signpost instead.
        try {
            if (ClusterCaps.isNavCapable() && NavState.ACTIVE) {
                // navsd-shadow delta: with AA's own map on the AID the turnToInfo field stays empty -
                // it is the plate above the map (trip 2026-11-19: on a straight stretch the unit's own
                // guidance sends no turn-to street, and the plate is gone while the turn widget still
                // shows the street; trip 2026-11-20: with it empty the plate is gone). The widget
                // shows CurrentPositionInfo under FOLLOW_STREET only, so for the other manoeuvres the
                // street goes into the signPost field (EXPERIMENT 2026-11-20: whether the widget shows
                // the sign post and the plate does not). KombiMap re-sends this when the map comes or
                // goes.
                boolean plateOff = de.aatokombi.Config.ClusterMap.HIDE_PLATE_ON_AA_MAP
                        && de.aatokombi.KombiMap.aaMapShown();
                String street = aaStreetLine(this.getNavigationService());
                turnToInfo_Status.turnToInfo.setContent(plateOff ? "" : street);
                turnToInfo_Status.signPost.setContent(plateOff ? street : "");
            } else if (ClusterCaps.isNavCapable()) {
                // the stock path, through the stock method: a subclass may override it
                this.setTurnToInfoStatusData(turnToInfo_Status);
            } else {
                turnToInfo_Status.turnToInfo.setContent("");
                turnToInfo_Status.signPost.setContent("");
            }
        } catch (Throwable t) {
            // leave empty on any failure; never crash navsd
        }
        return turnToInfo_Status;
    }

    /**
     * The street line while AA guides (navsd-shadow delta): NavState's street; when that asks for
     * the current position (manoeuvre not near yet, AA named no current road) the unit's own
     * map-matched street (the value CurrentPositionInfo falls back to), else the next-turn road.
     */
    static String aaStreetLine(NavigationService ns) {
        NavState.Streets st = NavState.streets;       // one read: the line and what to do without it
        if (!st.wantsCurrentPosition) {
            return st.street;
        }
        String own = null;
        try {
            if (ns != null && ns.getNavigationStatus() == 0) {
                own = ns.getCurrentPositionInfo();
            }
        } catch (Throwable t) {
            own = null;
        }
        return (own != null && own.length() > 0) ? own : st.nextRoad;
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
            try { this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) { de.aatokombi.Quiet.note("TurnToInfo#3", t); }
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
