/* PQ navsd RouteGuidanceStatus shadow (fid 17): rg_Status forced from NavState.ACTIVE. */
package de.vw.mib.bap.mqbpq.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunction;
import de.vw.mib.bap.functions.BAPFunctionController;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.navsd.functions.NavState;
import de.vw.mib.bap.mqbpq.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbpq.generated.navsd.serializer.RouteGuidance_Status_Status;
import de.vw.mib.bap.mqbpq.navsd.api.stages.RouteGuidanceStatusStage;

public final class RouteGuidanceStatus
extends RouteGuidanceStatusStage {
    private Boolean instrumentClusterActionStatus = null;

    private static volatile RouteGuidanceStatus INSTANCE = null;
    public static boolean isActive() { return INSTANCE != null; }
    public static void poke() {
        RouteGuidanceStatus i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    private void setRouteGuidanceStatus(RouteGuidance_Status_Status routeGuidance_Status_Status) {
        if (NavState.ACTIVE) {   // AA active -> guidance ON; else stock
            routeGuidance_Status_Status.rg_Status = 1;
            return;
        }
        routeGuidance_Status_Status.rg_Status = this.getNavigationStatus() != 0 ? 0 : (this.instrumentClusterActionStatus != null ? (this.instrumentClusterActionStatus.booleanValue() ? 1 : 0) : (this.getRouteGuidanceState() == 0 ? 0 : 1));
    }

    private void sendRouteGuidanceStatus(RouteGuidance_Status_Status routeGuidance_Status_Status) {
        this.getDelegate().getPropertyListener((BAPFunctionController)this).statusProperty((BAPEntity)routeGuidance_Status_Status, (Property)this);
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        super.init(bAPStageInitializer);
        INSTANCE = this;
        return this.computeRGStatusStatus();
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, (BAPFunction)this);
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
        this.process(-1);
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
        this.sendRouteGuidanceStatus(this.computeRGStatusStatus());
    }

    private RouteGuidance_Status_Status computeRGStatusStatus() {
        RouteGuidance_Status_Status routeGuidance_Status_Status = this.dequeueBAPEntity();
        this.setRouteGuidanceStatus(routeGuidance_Status_Status);
        return routeGuidance_Status_Status;
    }

    protected void setActionStateRunning(Boolean bl) {
        this.instrumentClusterActionStatus = bl;
        this.process(-1);
    }
}
