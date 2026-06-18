/*
 * Decompiled with CFR 0.152.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.common.api.configuration.ConfigurationService;
import de.vw.mib.bap.mqbab2.common.api.configuration.ConfigurationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.ActiveRgType_SetGet;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.ActiveRgType_Status;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;

public final class ActiveRgType
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener,
ConfigurationServiceListener {
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{1110044, -2147483643, Integer.MIN_VALUE};
    private int _currentBapRgType = 3;
    private boolean isWaitingForDsiAck = false;
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ActiveRgType_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile ActiveRgType INSTANCE = null;
    public static void poke() {
        ActiveRgType i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: drop getNavigationService()/getConfigurationService()
        // (the NavigationASLDataAdapter / map-switch landmine on a non-routing engine);
        // rgtype is forced from NavState in setCurrentRGType below.
        INSTANCE = this;
        // Start the /dev/shmem/aa_nav -> NavState poll on the framework timer (once). The shim
        // (patched libgal) writes real AA turn-by-turn there; the reader fills NavState and pokes
        // the navsd functions. Must never break navsd startup.
        try { NavShmemReader.ensureStarted(); } catch (Throwable t) {}
        return this.computeActiveRgType();
    }

    protected ActiveRgType_Status dequeueBAPEntity() {
        return (ActiveRgType_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ActiveRgType_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ActiveRgType_Status = ActiveRgType.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.ActiveRgType_Status")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ActiveRgType_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 39;
    }

    public void process(int n) {
        ActiveRgType_Status activeRgType_Status = this.dequeueBAPEntity();
        this.setCurrentRGType(activeRgType_Status);
        this.sendRgType(activeRgType_Status);
    }

    private ActiveRgType_Status computeActiveRgType() {
        ActiveRgType_Status activeRgType_Status = this.dequeueBAPEntity();
        this.setCurrentRGType(activeRgType_Status);
        return activeRgType_Status;
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void requestAcknowledge() {
        this.notifyFsgSetupIfWaitingForAck(new Boolean(false));
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
        ActiveRgType_SetGet activeRgType_SetGet = (ActiveRgType_SetGet)bAPEntity;
        if (ActiveRgType.inputParametersValid(activeRgType_SetGet)) {
            if (this.getNavigationService().getMapSwitchState() == 0 || this.getNavigationService().getMapSwitchState() == 1 || this.getNavigationService().getMapSwitchState() == 2) {
                this.setCurrentRgType(activeRgType_SetGet.rgtype);
            }
            ActiveRgType_Status activeRgType_Status = this.computeActiveRgType();
            propertyListener.statusProperty(activeRgType_Status, this);
        } else {
            propertyListener.requestError(65, this);
        }
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    private void determineInternalSwitchState() {
        if (this.getLogger().isTraceEnabled(16)) {
            this.getLogger().trace(16).append("getNavigationService().getMapSwitchState() = ").append(this.getNavigationService().getMapSwitchState()).log();
        }
        if (this.getNavigationService().getMapSwitchState() == 1) {
            this.setCurrentRgType(0);
        } else if (this.getNavigationService().getMapSwitchState() == 2) {
            this.setCurrentRgType(3);
        }
    }

    private static boolean inputParametersValid(ActiveRgType_SetGet activeRgType_SetGet) {
        boolean bl;
        switch (activeRgType_SetGet.rgtype) {
            case 0: 
            case 1: 
            case 2: 
            case 3: 
            case 255: {
                bl = true;
                break;
            }
            default: {
                bl = false;
            }
        }
        return bl;
    }

    private void setCurrentRGType(ActiveRgType_Status activeRgType_Status) {
        // navsd-shadow delta: force RG_TYPE_RGI_FROM_BAP_FUNCTION_MANEUVER_DESCRIPTIOR (0)
        // so the cluster draws OUR ManeuverDescriptor; 3 (MOST_MAP) waits for a map video.
        try {
            activeRgType_Status.rgtype = NavState.ACTIVE ? 0 : 3;
        } catch (Throwable t) {
            activeRgType_Status.rgtype = 3;
        }
    }

    private void sendRgType(ActiveRgType_Status activeRgType_Status) {
        this.getDelegate().getPropertyListener(this).statusProperty(activeRgType_Status, this);
    }

    private void notifyFsgSetupIfWaitingForAck(Boolean bl) {
        int[] nArray = new int[]{53};
        this.context.updateStages(this, nArray, bl);
    }

    private void setCurrentRgType(int n) {
        this._currentBapRgType = n;
    }

    private int getCurrentRgType() {
        return this._currentBapRgType;
    }

    public void processHMIEvent(int n) {
    }

    public void updateNavigationData(NavigationService navigationService, int n) {
        if (this.getLogger().isTraceEnabled(16)) {
            this.getLogger().trace(16).append("updateNavigationData(NavigationService, int).getNavigationService().getMapSwitchState() = ").append(this.getNavigationService().getMapSwitchState()).log();
        }
        if (n == -2147483643) {
            switch (this.getNavigationService().getMapSwitchState()) {
                case 3: {
                    this.setCurrentRgType(0);
                    this.notifyFsgSetupIfWaitingForAck(new Boolean(true));
                    break;
                }
                case 1: {
                    this.setCurrentRgType(0);
                    this.notifyFsgSetupIfWaitingForAck(new Boolean(false));
                    break;
                }
                case 5: {
                    this.setCurrentRgType(3);
                    this.notifyFsgSetupIfWaitingForAck(new Boolean(true));
                    break;
                }
                case 2: {
                    this.setCurrentRgType(3);
                    this.notifyFsgSetupIfWaitingForAck(new Boolean(false));
                    break;
                }
                default: {
                    this.notifyFsgSetupIfWaitingForAck(new Boolean(false));
                }
            }
        }
        this.process(-1);
    }

    public void updateConfigurationData(ConfigurationService configurationService, int n) {
        this.determineInternalSwitchState();
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
