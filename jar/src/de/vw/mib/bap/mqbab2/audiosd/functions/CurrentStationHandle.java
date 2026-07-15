/*
 * AAtoKombi shadow of the stock audiosd CurrentStationHandle (BAP fid 22).
 *
 * The centred cluster now-playing view only binds album cover art when the active source has a VALID
 * station handle; AA never gives one (fsgHandle = -1), so its cover shows only in the side wells.
 * Two gated changes fix it (both no-ops when Config.SHOW_COVER_ART is off — compile-time constant, so
 * javac drops the synthesis entirely):
 *   1. setCurrentStationHandleForMedia(): synthesize a stable fsgHandle = 1 when the media browser
 *      gives no pos id, so the stock CoverArt usecase can answer with a matching valid handle.
 *   2. setCurrentStationHandleData(): route audio component 6 (smartphone integration) through the
 *      media handler instead of falling to default/setInvalid.
 *
 * The rest is the verbatim stock class, kept 1.3-source-safe (explicit Boolean.booleanValue(),
 * NoClassDefFoundError(String) instead of the 1.4 initCause()).
 */
package de.vw.mib.bap.mqbab2.audiosd.functions;

import de.aatokombi.Config;
import de.adi961.miblogger.MIBLogger;
import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.functions.Property;
import de.vw.mib.bap.functions.PropertyListener;
import de.vw.mib.bap.mqbab2.audiosd.api.ASLAudioSDConstants;
import de.vw.mib.bap.mqbab2.common.api.media.MediaService;
import de.vw.mib.bap.mqbab2.common.api.media.MediaServiceListener;
import de.vw.mib.bap.mqbab2.common.api.radio.RadioService;
import de.vw.mib.bap.mqbab2.common.api.radio.RadioServiceListener;
import de.vw.mib.bap.mqbab2.common.api.radio.datatypes.iterator.elements.RadioReceptionListElement;
import de.vw.mib.bap.mqbab2.common.api.sound.SoundService;
import de.vw.mib.bap.mqbab2.common.api.sound.SoundServiceListener;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.common.api.tvtuner.TvTunerService;
import de.vw.mib.bap.mqbab2.common.api.tvtuner.TvTunerServiceListener;
import de.vw.mib.bap.mqbab2.common.arrays.FsgArrayListComplete;
import de.vw.mib.bap.mqbab2.common.arrays.FsgArrayListWindowed;
import de.vw.mib.bap.mqbab2.generated.audiosd.serializer.CurrentStation_Handle_Status;
import java.util.Iterator;

public class CurrentStationHandle
extends Function
implements Property,
ASLAudioSDConstants,
MediaServiceListener,
RadioServiceListener,
SoundServiceListener,
TvTunerServiceListener {
    private CurrentStation_Handle_Status lastCurrentStationHandleStatus = null;
    private FsgArrayListComplete fullReceptionList = null;
    private FsgArrayListComplete fullRadioTvPresetList = null;
    private FsgArrayListWindowed currentMediaBrowserList;
    private boolean autoStoreRunning = false;
    // The stock class declares a set of blank static-final scalar constants whose values the compiler
    // inlined at every use site; CFR can't recover the initializers. They are never read here, so we
    // keep them non-final (a blank final would fail to compile) purely for structural fidelity.
    private static int CURRENT_STATION_IN_RECEPTIONLIST_BAP_POS_ID;
    private static int OFFSET_FOR_REF_HANDLE;
    private static int NO_HANDLE_REF_EXISTS;
    private static int INVALID_HANDLE_REF;
    private static long INVALID_DAB_ID;
    private static int INVALID_INDEX;
    private static int DAB_NO_ABS_POS_ID_EXITS;
    private static int INVALID_BAP_POS_ID;
    private static int INDEX_ID;
    private static int INDEX_ID_ABS_POSITION;
    private static int INDEX_ENSEMBLE_ID;
    private static int INDEX_PARENT_ABS_POSITION;
    private static int INDEX_ENSEMBLE_ID_ABS_POS;
    protected static int[] MEDIA_LISTENER_IDS;
    protected static int[] RADIO_LISTENER_IDS;
    protected static int[] TV_TUNER_LISTENER_IDS;
    protected static int[] SOUND_LISTENER_IDS;
    static /* synthetic */ Class class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$CurrentStation_Handle_Status;

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        this.getMediaService().addMediaServiceListener(this, MEDIA_LISTENER_IDS);
        this.getRadioService().addRadioServiceListener(this, RADIO_LISTENER_IDS);
        this.getSoundService().addSoundServiceListener(this, SOUND_LISTENER_IDS);
        this.getTvTunerService().addTvTunerServiceListener(this, TV_TUNER_LISTENER_IDS);
        return this.computeCurrentStationHandleStatus();
    }

    protected CurrentStation_Handle_Status dequeueBAPEntity() {
        return (CurrentStation_Handle_Status)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$CurrentStation_Handle_Status == null ? (class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$CurrentStation_Handle_Status = CurrentStationHandle.class$("de.vw.mib.bap.mqbab2.generated.audiosd.serializer.CurrentStation_Handle_Status")) : class$de$vw$mib$bap$mqbab2$generated$audiosd$serializer$CurrentStation_Handle_Status);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
        switch (bAPStage.getFunctionId()) {
            case 23: {
                this.setFullReceptionList((FsgArrayListComplete)object);
                break;
            }
            case 33: {
                this.setFullRadioTvPresetList((FsgArrayListComplete)object);
                break;
            }
            case 30: {
                this.setAutostoreRunning((Boolean)object);
                break;
            }
            case 36: {
                this.setCurrentMediaBrowserArrayList((FsgArrayListWindowed)object);
                break;
            }
        }
    }

    protected void setCurrentStationHandleSend(Integer n) {
        int[] nArray = new int[]{24, 21};
        this.context.updateStages(this, nArray, n);
    }

    protected void setCurrentStationHandleOutput(CurrentStation_Handle_Status currentStation_Handle_Status) {
        int[] nArray = new int[]{24};
        this.context.updateStages(this, nArray, currentStation_Handle_Status);
    }

    public int getFunctionId() {
        return 22;
    }

    private int[] findIdInPresetList(int n, int n2) {
        int n3;
        int n4;
        if (this.fullRadioTvPresetList != null && n2 != -1) {
            n4 = this.fullRadioTvPresetList.getBapPosID(AudioSDCommon.computeUniquePresetID(n, n2));
            if (n4 != 0) {
                n3 = this.fullRadioTvPresetList.getIndexOfBapPosId(n4);
                if (n3 == -1) {
                    n4 = 0;
                    n3 = 0;
                }
            } else {
                n3 = 0;
            }
        } else {
            n4 = 0;
            n3 = 0;
        }
        int[] nArray = new int[]{n4, ++n3};
        return nArray;
    }

    private int[] findIdInAmFmSiriusReceptionList(long l) {
        long l2;
        int[] nArray = this.findIdsInMappingTable(l);
        if (nArray[1] == -1 && this.fullReceptionList != null && (l2 = this.fullReceptionList.getInternalUserId(1)) != -1L) {
            nArray = this.findIdsInMappingTable(l2);
        }
        if (nArray[1] == -1) {
            nArray[1] = 0;
            nArray[0] = -1;
        }
        return nArray;
    }

    private int[] findIdInFMReceptionList(long l) {
        long l2;
        int[] nArray = this.findIdInAmFmSiriusReceptionList(l);
        if (nArray[0] == -1 && (l2 = AudioSDCommon.filterOutPIFromUniqueID(l)) != l) {
            nArray = this.findIdInAmFmSiriusReceptionList(AudioSDCommon.filterOutPIFromUniqueID(l));
        }
        return nArray;
    }

    private int[] findIdsInMappingTable(long l) {
        int n;
        int n2;
        int n3;
        int n4;
        int n5 = n4 = this.fullReceptionList != null ? this.fullReceptionList.getBapPosID(l) : 0;
        if (n4 != 0 && this.fullReceptionList != null) {
            n3 = n4;
            n2 = this.fullReceptionList.getIndexOfBapPosId(n4);
            n2 = n2 != -1 ? n2 + 1 : n2;
            n = (int)this.fullReceptionList.getInternalUserId(n4);
        } else {
            n3 = -1;
            n2 = -1;
            n = -1;
        }
        int[] nArray = new int[]{n3, n2, n};
        return nArray;
    }

    private long getParentID(RadioReceptionListElement radioReceptionListElement, int n) {
        long l = n > -1 && radioReceptionListElement != null ? radioReceptionListElement.getReceptionListElementParentUniqueId() : -1L;
        return l;
    }

    private RadioReceptionListElement getReceptionListElementAtPosition(int n) {
        Iterator iterator = this.getRadioService().getReceptionList();
        int n2 = 0;
        while (iterator.hasNext()) {
            RadioReceptionListElement radioReceptionListElement = (RadioReceptionListElement)iterator.next();
            if (n2 == n) {
                return radioReceptionListElement;
            }
            ++n2;
        }
        return null;
    }

    private int[] findIdInDABReceptionList(long l) {
        int[] nArray;
        int n;
        RadioReceptionListElement radioReceptionListElement;
        long l2;
        int n2 = -1;
        int n3 = 0;
        int n4 = 0;
        int n5 = 0;
        int[] nArray2 = this.findIdsInMappingTable(l);
        n2 = nArray2[0];
        n3 = nArray2[1];
        if (n3 == -1) {
            n2 = -1;
            n3 = 0;
        }
        if ((l2 = this.getParentID(radioReceptionListElement = this.getReceptionListElementAtPosition(n = nArray2[2]), n)) != -1L) {
            nArray = this.findIdsInMappingTable(l2);
            if ((radioReceptionListElement.getReceptionListElementType() & 4) == 4) {
                RadioReceptionListElement radioReceptionListElement2 = this.getReceptionListElementAtPosition(nArray[2]);
                long l3 = this.getParentID(radioReceptionListElement2, nArray[2]);
                nArray = this.findIdsInMappingTable(l3);
            } else {
                n4 = nArray[0];
                if (n4 == -1) {
                    n4 = 0;
                }
            }
            n5 = nArray[1];
            if (n5 == -1) {
                n5 = 0;
            }
        }
        nArray = new int[]{n2, n3, n4, n5};
        return nArray;
    }

    protected void setStationHandlesForRadio(CurrentStation_Handle_Status currentStation_Handle_Status) {
        switch (this.getRadioService().getCurrentStationBand()) {
            case 0: {
                long l = this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesStationListActiveID();
                int[] nArray = this.findIdInAmFmSiriusReceptionList(l);
                currentStation_Handle_Status.fsgHandle = nArray[0];
                currentStation_Handle_Status.fsgHandle_absolutePosition = nArray[1];
                int[] nArray2 = this.findIdInPresetList(4, this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesActivePresetIndex());
                currentStation_Handle_Status.presetList_Ref = nArray2[0];
                currentStation_Handle_Status.presetList_absolutePosition = nArray2[1];
                currentStation_Handle_Status.dab_EnsembleHandle = 0;
                currentStation_Handle_Status.dab_Ensemble_absolutePosition = 0;
                break;
            }
            case 2: {
                long l = this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesStationListActiveID();
                int[] nArray = this.findIdInDABReceptionList(l);
                currentStation_Handle_Status.fsgHandle = nArray[0];
                currentStation_Handle_Status.fsgHandle_absolutePosition = nArray[1];
                int[] nArray3 = this.findIdInPresetList(7, this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesActivePresetIndex());
                currentStation_Handle_Status.presetList_Ref = nArray3[0];
                currentStation_Handle_Status.presetList_absolutePosition = nArray3[1];
                currentStation_Handle_Status.dab_EnsembleHandle = nArray[2];
                currentStation_Handle_Status.dab_Ensemble_absolutePosition = nArray[3];
                break;
            }
            case 1: {
                long l = this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesStationListActiveID();
                int[] nArray = this.findIdInFMReceptionList(l);
                currentStation_Handle_Status.fsgHandle = nArray[0];
                currentStation_Handle_Status.fsgHandle_absolutePosition = nArray[1];
                int[] nArray4 = this.findIdInPresetList(1, this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesActivePresetIndex());
                currentStation_Handle_Status.presetList_Ref = nArray4[0];
                currentStation_Handle_Status.presetList_absolutePosition = nArray4[1];
                currentStation_Handle_Status.dab_EnsembleHandle = 0;
                currentStation_Handle_Status.dab_EnsembleHandle = 0;
                break;
            }
            case 3: {
                long l = this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesStationListActiveID();
                int[] nArray = this.findIdInAmFmSiriusReceptionList(l);
                currentStation_Handle_Status.fsgHandle = nArray[0];
                currentStation_Handle_Status.fsgHandle_absolutePosition = nArray[1];
                int[] nArray5 = this.findIdInPresetList(6, this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesActivePresetIndex());
                currentStation_Handle_Status.presetList_Ref = nArray5[0];
                currentStation_Handle_Status.presetList_absolutePosition = nArray5[1];
                currentStation_Handle_Status.dab_EnsembleHandle = 0;
                currentStation_Handle_Status.dab_Ensemble_absolutePosition = 0;
                break;
            }
            default: {
                this.setInvalidCurrentStationHandleStatus(currentStation_Handle_Status);
            }
        }
    }

    protected void setCurrentStationHandlesForTV(CurrentStation_Handle_Status currentStation_Handle_Status) {
        long l = this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesStationListActiveID();
        int[] nArray = this.findIdInAmFmSiriusReceptionList(l);
        currentStation_Handle_Status.fsgHandle = nArray[0];
        currentStation_Handle_Status.fsgHandle_absolutePosition = nArray[1];
        int[] nArray2 = this.findIdInPresetList(9, this.getRadioService().getCurrentStationIndices().getCurrentStationIndicesActivePresetIndex());
        currentStation_Handle_Status.presetList_Ref = nArray2[0];
        currentStation_Handle_Status.presetList_absolutePosition = nArray2[1];
        currentStation_Handle_Status.dab_EnsembleHandle = 0;
        currentStation_Handle_Status.dab_Ensemble_absolutePosition = 0;
    }

    private int computeCurrentActiveMediaPosId() {
        MediaService mediaService = this.getMediaService();
        int n = this.currentMediaBrowserList != null ? this.currentMediaBrowserList.getBapPosIdOrGenerate(MediaBrowser.createObjectID(mediaService.getActiveTrackInfo().getActiveTrackEntryId(), mediaService.getActiveTrackInfo().getActiveTrackContentType())) : 0;
        return n;
    }

    private void setCurrentStationHandleForMedia(CurrentStation_Handle_Status currentStation_Handle_Status) {
        MediaService mediaService = this.getMediaService();
        int n = this.computeCurrentActiveMediaPosId();
        if (Config.SHOW_COVER_ART && n == 0) {
            // AA gives no BAP media pos id -> stock would leave fsgHandle = -1 and the centred cluster
            // now-playing view would never bind the cover. Synthesize a stable positive handle so the
            // stock CoverArt usecase can answer responseCoverArt() with a matching valid station handle.
            currentStation_Handle_Status.fsgHandle = 1;
            currentStation_Handle_Status.fsgHandle_absolutePosition = 1;
            MIBLogger.getInstance().debug("CurrentStationHandle: synthesized fsgHandle=1 for AA (cover-art centred well)");
        } else {
            currentStation_Handle_Status.fsgHandle = n == 0 ? -1 : n;
            currentStation_Handle_Status.fsgHandle_absolutePosition = mediaService.getActiveTrackInfo().getActiveTrackAbsolutePosition() + 1;
        }
        currentStation_Handle_Status.presetList_Ref = 0;
        currentStation_Handle_Status.dab_EnsembleHandle = 0;
        currentStation_Handle_Status.dab_EnsembleHandle = 0;
    }

    private void setInvalidCurrentStationHandleStatus(CurrentStation_Handle_Status currentStation_Handle_Status) {
        currentStation_Handle_Status.fsgHandle = -1;
        currentStation_Handle_Status.fsgHandle_absolutePosition = 0;
        currentStation_Handle_Status.presetList_Ref = 0;
        currentStation_Handle_Status.dab_EnsembleHandle = 0;
        currentStation_Handle_Status.dab_EnsembleHandle = 0;
    }

    private void setCurrentStationHandleData(CurrentStation_Handle_Status currentStation_Handle_Status) {
        switch (this.getSoundService().getCurrentAudioComponent()) {
            case 1: {
                if (this.autoStoreRunning) {
                    this.setInvalidCurrentStationHandleStatus(currentStation_Handle_Status);
                    break;
                }
                this.setStationHandlesForRadio(currentStation_Handle_Status);
                break;
            }
            case 5: {
                this.setCurrentStationHandlesForTV(currentStation_Handle_Status);
                break;
            }
            case 2: {
                if (this.getMediaService().isBapMediaBrowserAvailable()) {
                    this.setCurrentStationHandleForMedia(currentStation_Handle_Status);
                    break;
                }
                this.setInvalidCurrentStationHandleStatus(currentStation_Handle_Status);
                break;
            }
            case 6: {
                // Audio component 6 = smartphone integration (Android Auto / CarPlay / MirrorLink).
                // Stock has no case here and falls to default -> setInvalid -> fsgHandle = -1, so the
                // centred cluster well never binds an AA cover. Route it through the media handler so
                // the synthesized handle above lets the cover attach. Byte-for-byte stock when the
                // compile-time flag is off (only the else branch survives constant folding).
                if (Config.SHOW_COVER_ART) {
                    this.setCurrentStationHandleForMedia(currentStation_Handle_Status);
                } else {
                    this.setInvalidCurrentStationHandleStatus(currentStation_Handle_Status);
                }
                break;
            }
            default: {
                this.setInvalidCurrentStationHandleStatus(currentStation_Handle_Status);
            }
        }
    }

    public void sendCurrentStationHandleStatus(CurrentStation_Handle_Status currentStation_Handle_Status) {
        if (this.getDelegate().getPropertyListener(this).statusProperty(currentStation_Handle_Status, this)) {
            this.lastCurrentStationHandleStatus = currentStation_Handle_Status;
            this.setCurrentStationHandleSend(new Integer(currentStation_Handle_Status.fsgHandle_absolutePosition));
        }
    }

    public void getProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void requestAcknowledge() {
        this.setCurrentStationHandleOutput(this.lastCurrentStationHandleStatus);
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
        this.autoStoreRunning = false;
    }

    public void uninitialize() {
        this.getMediaService().removeMediaServiceListener(this, MEDIA_LISTENER_IDS);
        this.getRadioService().removeRadioServiceListener(this, RADIO_LISTENER_IDS);
        this.getSoundService().removeSoundServiceListener(this, SOUND_LISTENER_IDS);
    }

    public void setGetProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void ackProperty(BAPEntity bAPEntity, PropertyListener propertyListener) {
        propertyListener.requestError(65, this);
    }

    public void process(int n) {
        this.sendCurrentStationHandleStatus(this.computeCurrentStationHandleStatus());
    }

    private CurrentStation_Handle_Status computeCurrentStationHandleStatus() {
        CurrentStation_Handle_Status currentStation_Handle_Status = this.dequeueBAPEntity();
        this.setCurrentStationHandleData(currentStation_Handle_Status);
        return currentStation_Handle_Status;
    }

    protected void setFullReceptionList(FsgArrayListComplete fsgArrayListComplete) {
        boolean bl = this.fullReceptionList != null;
        this.fullReceptionList = fsgArrayListComplete;
        if (bl) {
            this.process(-1);
        }
    }

    protected void setFullRadioTvPresetList(FsgArrayListComplete fsgArrayListComplete) {
        this.fullRadioTvPresetList = fsgArrayListComplete;
        this.process(-1);
    }

    protected void setAutostoreRunning(Boolean bl) {
        this.autoStoreRunning = bl.booleanValue();
        this.process(-1);
    }

    protected void setCurrentMediaBrowserArrayList(FsgArrayListWindowed fsgArrayListWindowed) {
        this.currentMediaBrowserList = fsgArrayListWindowed;
        this.process(-1);
    }

    public void processHMIEvent(int n) {
    }

    public void updateSoundData(SoundService soundService, int n) {
        this.process(-1);
    }

    public void updateRadioData(RadioService radioService, int n) {
        this.process(-1);
    }

    public void updateMediaData(MediaService mediaService, int n) {
        this.process(-1);
    }

    public void updateTvTunerData(TvTunerService tvTunerService, int n) {
        this.process(-1);
    }

    static /* synthetic */ Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            throw new NoClassDefFoundError(string);
        }
    }

    static {
        MEDIA_LISTENER_IDS = new int[]{492571648, 526126080};
        RADIO_LISTENER_IDS = new int[]{1324, 2486, 1412};
        TV_TUNER_LISTENER_IDS = new int[]{2662};
        SOUND_LISTENER_IDS = new int[]{1470};
    }
}
