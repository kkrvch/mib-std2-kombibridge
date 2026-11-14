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
import de.aatokombi.ClusterVideo;
import de.aatokombi.Config;

public final class ActiveRgType
extends Function
implements Property,
ASLNavSDConstants,
NavigationServiceListener,
ConfigurationServiceListener {
    // Attribute IDs of the stock ActiveRgType. The old jxe->jar conversion byte-swapped ldc ints
    // ({485494784, 0x5000080, 128} / -1050869632 there); the fixed tools/jxe2jar gives exactly these
    // values from this firmware's stock class, so they are correct for MST2 as well as MHI2.
    protected static final int[] NAVIGATION_LISTENER_IDS = new int[]{1110044, -2147483643, Integer.MIN_VALUE};
    private static final int[] CONFIG_LISTENER_IDS = new int[]{-2147459647};
    private int _currentBapRgType = 3;
    private boolean isWaitingForDsiAck = false;
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$ActiveRgType_Status;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile ActiveRgType INSTANCE = null;
    public static void poke() {
        ActiveRgType i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

	/**
     * Called once by AANavReader, at the moment AA route guidance STARTS (NavState.ACTIVE
     * false->true), to pick the starting BAP rgtype for this guidance session: arrows (0) or the
     * stock map (3, MOST_MAP) per Config.ClusterMap.START_ON_MAP.
     */
    public static void primeForAAStart(boolean wantMap) {
        ActiveRgType i = INSTANCE;
        if (i == null) return;
        try { i.setCurrentRgType(wantMap ? 3 : 0); } catch (Throwable t) {}
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // navsd-shadow delta: detect the cluster at runtime (ClusterCaps) instead of a static
        // per-build NAV_CAPABLE flag. On a nav-capable cluster we register the stock nav/config
        // listeners and run the stock map-switch logic so the unit's own navigation still draws when
        // AA is inactive; on a non-nav cluster we skip registration (media path owns the output) and
        // never touch the nav service -- avoiding the NavigationASLDataAdapter / phone-audio-BAP
        // landmine (Blocker 2).
        INSTANCE = this;
        if (ClusterCaps.isNavCapable()) {
            // The AA feed itself is pumped by the single AANavReader (started once from
            // AndroidAutoTarget), which fills NavState and pokes these functions.
            try {
                this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS);
                this.getConfigurationService().addConfigurationListener(this, CONFIG_LISTENER_IDS);
                if (this.getConfigurationService().isMapSwitchingFeatureSelected()) {
                    this.setCurrentRgType(0);
                }
                this.determineInternalSwitchState();
            } catch (Throwable t) {}
        }
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
        // navsd-shadow delta: a listener was only registered on a nav-capable cluster (see init).
        if (ClusterCaps.isNavCapable()) {
            try { this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
        }
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    /**
     * Config.ClusterMap.MODE_CYCLE: the driver's three cluster modes (de.aatokombi.ClusterVideo) replace
     * the stock rgtype while an AA session has its cluster channel open on a nav-capable cluster.
     */
    private static int lastMapSwitchState = -1;

    private static boolean aaCycle() {
        return Config.ClusterMap.MODE_CYCLE && ClusterCaps.isNavCapable() && ClusterVideo.session();
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        ActiveRgType_SetGet activeRgType_SetGet = (ActiveRgType_SetGet)bAPEntity;
        boolean cycle = false;
        try {
            cycle = aaCycle();
            int sw = -1;
            try { sw = this.getNavigationService().getMapSwitchState(); } catch (Throwable t) {}
            ClusterVideo.log("ActiveRgType SetGet rgtype=" + activeRgType_SetGet.rgtype
                    + " current=" + this.getCurrentRgType() + " mapSwitchState=" + sw
                    + " aaSession=" + ClusterVideo.session() + " aaVideo=" + ClusterVideo.alive()
                    + " mode=" + ClusterVideo.modeName(ClusterVideo.mode()));
        } catch (Throwable t) {}
        if (cycle) {
            // car log 2026-10-30: the cluster sends this as part of the head unit's "Map" button
            // (SetGet rgtype=3, then mapSwitchState 5 -> 2); holding OK sends nothing
            int rq = activeRgType_SetGet.rgtype;
            if (rq == 0 || rq == 3) {
                ClusterVideo.switchTo(rq);
            }
            this.setCurrentRgType(ClusterVideo.rgTypeForMode());
            propertyListener.statusProperty(this.computeActiveRgType(), this);
            return;
        }
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
        if (aaCycle()) {
            activeRgType_Status.rgtype = ClusterVideo.rgTypeForMode();
            return;
        }
        // Behave exactly like stock: AA-inactive on a nav cluster (own nav keeps its gate),
        // AND the whole non-nav case -- where the media path owns the output and we must not hijack
        // navsd. Wrapped so a non-routing engine can never crash navsd.
        try {
            activeRgType_Status.rgtype = !this.getConfigurationService().isMapSwitchingFeatureSelected()
                    ? (this.getNavigationService().getKombiMapStatus() != 0 ? 3 : this.getCurrentRgType())
                    : this.getCurrentRgType();
        } catch (Throwable t) {
            activeRgType_Status.rgtype = this.getCurrentRgType();
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
            int before = this.getCurrentRgType();
            this.updateMapSwitch();
            try {
                int sw = this.getNavigationService().getMapSwitchState();
                ClusterVideo.log("ActiveRgType mapSwitchState=" + sw + " rgtype " + before + "->" + this.getCurrentRgType());
                // only a CHANGE of the head unit's switch (its "Map" button) picks a mode
                if (sw == 1 || sw == 3) ClusterVideo.noteHuSwitch(0);
                else if (sw == 2 || sw == 5) ClusterVideo.noteHuSwitch(3);
                // 3 -> 4 -> 1: to arrows, 5 -> 2: to the map (car log 2026-10-30)
                if (sw != lastMapSwitchState && aaCycle() && (sw == 1 || sw == 2 || sw == 3 || sw == 5)) {
                    ClusterVideo.switchTo(sw == 1 || sw == 3 ? 0 : 3);
                }
                lastMapSwitchState = sw;
            } catch (Throwable t) {}
        }
        this.process(-1);
    }

    private void updateMapSwitch() {
        {
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
