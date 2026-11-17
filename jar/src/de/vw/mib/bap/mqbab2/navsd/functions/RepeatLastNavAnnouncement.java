/*
 * Decompiled with CFR 0.152 from the stock MIBHMI class. navsd-shadow delta: every request from
 * the cluster is logged (de.aatokombi.BapTrace), behaviour otherwise stock.
 *
 * Could not load the following classes:
 *  de.vw.mib.bap.datatypes.BAPEntity
 *  de.vw.mib.bap.functions.BAPFunction
 *  de.vw.mib.bap.functions.BAPFunctionController
 *  de.vw.mib.bap.functions.BAPFunctionListener
 *  de.vw.mib.bap.functions.Method
 *  de.vw.mib.bap.functions.MethodListener
 *  de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService
 *  de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener
 *  de.vw.mib.bap.mqbab2.common.api.sound.SoundService
 *  de.vw.mib.bap.mqbab2.common.api.stages.BAPStage
 *  de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer
 *  de.vw.mib.bap.mqbab2.common.api.stages.Function
 *  de.vw.mib.bap.mqbab2.generated.navsd.serializer.RepeatLastNavAnnouncement_Result
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunction;
import de.vw.mib.bap.functions.BAPFunctionController;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Method;
import de.vw.mib.bap.functions.MethodListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.sound.SoundService;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.RepeatLastNavAnnouncement_Result;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public final class RepeatLastNavAnnouncement
extends Function
implements Method,
ASLNavSDConstants,
NavigationServiceListener {
    private int currentActionStatus = 0;
    private static final int ACTION_STATUS_IDLE = 0;
    private static final int ACTION_STATUS_ACTIVE = 1;
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{732, 730};
    static /* synthetic */ Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RepeatLastNavAnnouncement_Result;

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        this.getNavigationService().addNavigationServiceListener((NavigationServiceListener)this, NAVIGATION_LISTENER_IDS);
        return null;
    }

    protected RepeatLastNavAnnouncement_Result dequeueBAPEntity() {
        return (RepeatLastNavAnnouncement_Result)this.context.dequeueBAPEntity((BAPStage)this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RepeatLastNavAnnouncement_Result == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RepeatLastNavAnnouncement_Result = RepeatLastNavAnnouncement.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.RepeatLastNavAnnouncement_Result")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$RepeatLastNavAnnouncement_Result);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 35;
    }

    public void process(int n) {
        if (this.currentActionStatus == 1 && this.getNavigationService().getGuidanceAnnouncementActive() == 1) {
            this.sendResult(0);
            this.currentActionStatus = 0;
        }
    }

    public void abortResult(BAPEntity bAPEntity, MethodListener methodListener) {
        de.aatokombi.BapTrace.log("RepeatLastNavAnnouncement", "abortResult", bAPEntity);
        if (this.currentActionStatus == 1) {
            this.getNavigationService().stopCurrentNavAnnouncement();
            this.sendResult(methodListener, 2);
            this.currentActionStatus = 0;
        } else {
            methodListener.requestError(80, (BAPFunction)this);
        }
    }

    public void startResult(BAPEntity bAPEntity, MethodListener methodListener) {
        de.aatokombi.BapTrace.log("RepeatLastNavAnnouncement", "startResult", bAPEntity);
        NavigationService navigationService = this.getNavigationService();
        SoundService soundService = this.getSoundService();
        if (navigationService.getRouteGuidanceState() == 1 && this.currentActionStatus == 0 && soundService.getVolumeNavi() > soundService.getVolumeNaviMinPopup()) {
            if (navigationService.getGuidanceAnnouncementActive() == 1) {
                this.sendResult(0);
            } else {
                navigationService.repeatLastNavAnnouncement();
                this.currentActionStatus = 1;
            }
        } else {
            this.sendResult(1);
        }
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void requestAcknowledge() {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
        this.currentActionStatus = 0;
    }

    public void uninitialize() {
        this.getNavigationService().removeNavigationServiceListener((NavigationServiceListener)this, NAVIGATION_LISTENER_IDS);
    }

    private void sendResult(int n) {
        RepeatLastNavAnnouncement_Result repeatLastNavAnnouncement_Result = this.dequeueBAPEntity();
        repeatLastNavAnnouncement_Result.repeatLna_Result = n;
        this.getDelegate().getMethodListener((BAPFunctionController)this).result((BAPEntity)repeatLastNavAnnouncement_Result, (Method)this);
    }

    private void sendResult(MethodListener methodListener, int n) {
        RepeatLastNavAnnouncement_Result repeatLastNavAnnouncement_Result = this.dequeueBAPEntity();
        repeatLastNavAnnouncement_Result.repeatLna_Result = n;
        methodListener.result((BAPEntity)repeatLastNavAnnouncement_Result, (Method)this);
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
