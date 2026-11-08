/*
 * Decompiled with CFR 0.152 from the stock class (MIBHMI-fixed.jar). navsd-shadow delta: every
 * request from the cluster is logged (de.aatokombi.BapTrace), behaviour otherwise stock.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.Map_Presentation_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public class MapPresentation
extends Function
implements Property,
ASLNavSDConstants {
    static /* synthetic */ Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$Map_Presentation_Status;

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        return null;
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
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        de.aatokombi.BapTrace.log("MapPresentation", "setGetProperty", bAPEntity);
        de.aatokombi.ClusterView.presentation(bAPEntity);
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
