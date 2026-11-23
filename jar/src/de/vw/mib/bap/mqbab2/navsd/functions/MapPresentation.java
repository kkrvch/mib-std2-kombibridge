/*
 * Decompiled with CFR 0.152 from the stock class (MIBHMI-fixed.jar). navsd-shadow delta: every
 * request from the cluster is logged (de.aatokombi.BapTrace). Stock answers nothing (the function
 * is switched off in the stock FunctionList); our FunctionList shadow announces it, so this class
 * now behaves as an FSG property: it keeps the cluster's ASG_HMI_State and echoes it as Status
 * on init, get and setGet (an unanswered setGet would make the cluster retry).
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.Map_Presentation_SetGet;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.Map_Presentation_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public class MapPresentation
extends Function
implements Property,
ASLNavSDConstants {
    static /* synthetic */ Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$Map_Presentation_Status;

    private boolean largeMapView;
    private boolean leftSideMenueOpen;
    private boolean rightSideMenueOpen;

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        return FunctionList.mapPresentationAnnounced() ? this.status() : null;
    }

    private Map_Presentation_Status status() {
        Map_Presentation_Status s = this.dequeueBAPEntity();
        s.asg_Hmi_State.largeMapView = this.largeMapView;
        s.asg_Hmi_State.leftSideMenueOpen = this.leftSideMenueOpen;
        s.asg_Hmi_State.rightSideMenueOpen = this.rightSideMenueOpen;
        return s;
    }

    protected Map_Presentation_Status dequeueBAPEntity() {
        return (Map_Presentation_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$Map_Presentation_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$Map_Presentation_Status = MapPresentation.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.Map_Presentation_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$Map_Presentation_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 54;
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
    }

    public void process(int n) {
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        de.aatokombi.BapTrace.log("MapPresentation", "getProperty", bAPEntity);
        if (FunctionList.mapPresentationAnnounced()) {
            propertyListener.statusProperty(this.status(), this);
        }
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        de.aatokombi.BapTrace.log("MapPresentation", "setGetProperty", bAPEntity);
        de.aatokombi.ClusterView.presentation(bAPEntity);
        if (bAPEntity instanceof Map_Presentation_SetGet) {
            Map_Presentation_SetGet sg = (Map_Presentation_SetGet)bAPEntity;
            this.largeMapView = sg.asg_Hmi_State.largeMapView;
            this.leftSideMenueOpen = sg.asg_Hmi_State.leftSideMenueOpen;
            this.rightSideMenueOpen = sg.asg_Hmi_State.rightSideMenueOpen;
        }
        if (FunctionList.mapPresentationAnnounced()) {
            propertyListener.statusProperty(this.status(), this);
        }
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        de.aatokombi.BapTrace.log("MapPresentation", "ackProperty", bAPEntity);
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
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
