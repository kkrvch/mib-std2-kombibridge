/* PQ navsd TurnToInfo shadow (fid 20): street from NavState.street when AA active. */
package de.vw.mib.bap.mqbpq.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunction;
import de.vw.mib.bap.functions.BAPFunctionController;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.navsd.functions.NavState;
import de.vw.mib.bap.mqbpq.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbpq.generated.navsd.serializer.TurnToInfo_Status;
import de.vw.mib.bap.mqbpq.navsd.api.stages.TurnToInfoStage;

public class TurnToInfo
extends TurnToInfoStage {
    public static String EMPTY_STRING = "";
    public static String LINE_FEED = "\n";

    private static volatile TurnToInfo INSTANCE = null;
    public static boolean isActive() { return INSTANCE != null; }
    public static void poke() {
        TurnToInfo i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        INSTANCE = this;
        return super.init(bAPStageInitializer);
    }

    public void process(int n) {
        this.sendTurnToInfoStatus(this.computeTurnToInfoStatus());
    }

    private TurnToInfo_Status computeTurnToInfoStatus() {
        TurnToInfo_Status turnToInfo_Status = this.dequeueBAPEntity();
        this.setTurnToInfoStatusData(turnToInfo_Status);
        return turnToInfo_Status;
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

    private void sendTurnToInfoStatus(TurnToInfo_Status turnToInfo_Status) {
        this.getDelegate().getPropertyListener((BAPFunctionController)this).statusProperty((BAPEntity)turnToInfo_Status, (Property)this);
    }

    protected void setTurnToInfoStatusData(TurnToInfo_Status turnToInfo_Status) {
        if (NavState.ACTIVE) {   // AA active -> NavState.street; else stock
            String s = (NavState.street != null) ? NavState.street : EMPTY_STRING;
            turnToInfo_Status.turnToInfo.setContent(s);
            return;
        }
        String string = this.getTurnToInfoSignPost();
        String string2 = this.getTurnToInfoStreet();
        String string3 = string != null && string.length() > 0 && string2 != null && string2.length() > 0 ? new StringBuffer().append(string2).append(LINE_FEED).append(string).toString() : (string != null && string.length() > 0 ? string : (string2 != null && string2.length() > 0 ? string2 : EMPTY_STRING));
        turnToInfo_Status.turnToInfo.setContent(string3);
    }
}
