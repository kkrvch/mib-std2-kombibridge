/*
 * Decompiled with CFR 0.152. navsd-shadow: drive the cluster's lane row (fn 24) from Android Auto.
 *
 * Unlike the other navsd shadows this is a BAP *Array*, not a Property: it keeps a list, diffs it
 * against the previous one and reports the delta, and the cluster PULLS with getArray(). That stock
 * machinery is kept verbatim; the deltas are the element source and the INSTANCE/poke() hook.
 *
 * Java 1.3: de-genericised, @Override dropped, unboxing spelled out, and the stock's private
 * constants removed — the firmware's class file no longer carries their values.
 */
package de.vw.mib.bap.mqbab2.navsd.functions;

import de.aatokombi.Config;
import de.adi961.miblogger.MIBLogger;
import de.vw.mib.bap.array.requests.BAPStatusArray;
import de.vw.mib.bap.array.timer.Timer;
import de.vw.mib.bap.array.timer.TimerNotifier;
import de.vw.mib.bap.datatypes.ArrayHeader;
import de.vw.mib.bap.datatypes.BAPArrayData;
import de.vw.mib.bap.datatypes.BAPEntity;
import de.vw.mib.bap.functions.Array;
import de.vw.mib.bap.functions.ArrayListener;
import de.vw.mib.bap.functions.BAPFunctionListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationService;
import de.vw.mib.bap.mqbab2.common.api.navigation.NavigationServiceListener;
import de.vw.mib.bap.mqbab2.common.api.navigation.datatypes.iterator.elements.NavigationLaneGuidanceElement;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStage;
import de.vw.mib.bap.mqbab2.common.api.stages.BAPStageInitializer;
import de.vw.mib.bap.mqbab2.common.api.stages.Function;
import de.vw.mib.bap.mqbab2.common.arrays.FsgArrayMessageBlockingRequestTracker;
import de.vw.mib.bap.mqbab2.common.arrays.FsgArrayMessageBlockingTracker;
import de.vw.mib.bap.mqbab2.common.arrays.FsgArrayRequestData;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.LaneGuidance_ChangedArray;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.LaneGuidance_GetArray;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.LaneGuidance_LaneGuidance;
import de.vw.mib.bap.mqbab2.generated.navsd.serializer.LaneGuidance_StatusArray;
import de.vw.mib.bap.mqbab2.navsd.api.ASLNavSDConstants;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public final class LaneGuidance
extends Function
implements TimerNotifier,
Array,
ASLNavSDConstants,
NavigationServiceListener {
    private LaneGuidance_ChangedArray fullLaneGuidanceChangedArray = null;
    private LaneGuidance_ChangedArray actualLaneGuidanceList;
    private Timer timer = null;
    private FsgArrayMessageBlockingTracker _arrayRequestTracker;
    protected static final int[] NAVIGATION_LISTENER_IDS;
    static Class class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$LaneGuidance_ChangedArray;

    // navsd-shadow delta: live re-send hook (see ManeuverDescriptor.poke).
    private static volatile LaneGuidance INSTANCE = null;
    public static void poke() {
        LaneGuidance i = INSTANCE;
        if (i != null) { try { i.process(-1); } catch (Throwable t) {} }
    }

    public BAPEntity init(BAPStageInitializer bAPStageInitializer) {
        // Only the INSTANCE latch. The stock listener stays registered in every case: we drive this
        // function via poke(), and the guard in computeFullLaneGuidanceList keeps the AA branch off
        // where it does not belong — so the unit's own navigation keeps feeding the row when AA is idle.
        INSTANCE = this;
        try { this.getNavigationService().addNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
        this.timer = this.getTimerService().createTimer(this, 1000);
        this.fullLaneGuidanceChangedArray = new LaneGuidance_ChangedArray();
        this.actualLaneGuidanceList = new LaneGuidance_ChangedArray();
        this.computeFullLaneGuidanceList(this.fullLaneGuidanceChangedArray);
        return this.getLaneGuidanceListFullRangeUpdate();
    }

    protected LaneGuidance_ChangedArray dequeueBAPEntity() {
        return (LaneGuidance_ChangedArray)this.context.dequeueBAPEntity(this, class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$LaneGuidance_ChangedArray == null ? (class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$LaneGuidance_ChangedArray = LaneGuidance.class$("de.vw.mib.bap.mqbab2.generated.navsd.serializer.LaneGuidance_ChangedArray")) : class$de$vw$mib$bap$mqbab2$generated$navsd$serializer$LaneGuidance_ChangedArray);
    }

    public void setFunctionData(BAPStage bAPStage, Object object) {
    }

    public int getFunctionId() {
        return 24;
    }

    private FsgArrayMessageBlockingTracker getArrayRequestTracker() {
        if (this._arrayRequestTracker == null) {
            this._arrayRequestTracker = new FsgArrayMessageBlockingRequestTracker(this, this.getDelegate().getArrayListener(this));
        }
        return this._arrayRequestTracker;
    }

    public void process(int n) {
        if (this.timer.isRunning()) {
            this.timer.setUserInfo(new Integer(1));
        } else {
            this.actualLaneGuidanceList.reset();
            this.computeFullLaneGuidanceList(this.actualLaneGuidanceList);
            LaneGuidance_ChangedArray laneGuidance_ChangedArray = this.searchForChangeContent(this.actualLaneGuidanceList, this.fullLaneGuidanceChangedArray);
            if (laneGuidance_ChangedArray != null) {
                if (laneGuidance_ChangedArray.laneGuidanceOnOff == this.fullLaneGuidanceChangedArray.laneGuidanceOnOff) {
                    this.timer.retrigger(new Integer(0));
                }
                this.getArrayRequestTracker().reportChangedArray(laneGuidance_ChangedArray);
                LaneGuidance_ChangedArray laneGuidance_ChangedArray2 = this.fullLaneGuidanceChangedArray;
                this.fullLaneGuidanceChangedArray = this.actualLaneGuidanceList;
                this.actualLaneGuidanceList = laneGuidance_ChangedArray2;
            }
        }
    }

    public void getArray(BAPEntity bAPEntity, ArrayListener arrayListener) {
        // The cluster pulls this array itself; that it does so at all is hardware-confirmed, so what
        // is left to see here is the content. Behind DEBUG: this runs on every FSG request.
        if (Config.LOG_LEVEL <= MIBLogger.DEBUG) {
            try {
                MIBLogger.getInstance().debug("LaneGuidance.getArray: cluster requested "
                        + this.fullLaneGuidanceChangedArray.getArrayData().size() + " lane(s), onOff="
                        + this.fullLaneGuidanceChangedArray.laneGuidanceOnOff);
            } catch (Throwable t) {}
        }
        LaneGuidance_GetArray laneGuidance_GetArray = (LaneGuidance_GetArray)bAPEntity;
        if (this.getArrayRequestTracker().requestGetArray(laneGuidance_GetArray)) {
            FsgArrayRequestData fsgArrayRequestData = FsgArrayRequestData.computeArrayRequestData(laneGuidance_GetArray.getArrayHeader(), this.fullLaneGuidanceChangedArray.getArrayData().size(), laneGuidance_GetArray.getArrayHeader().start - 0, 8);
            LaneGuidance_StatusArray laneGuidance_StatusArray = new LaneGuidance_StatusArray();
            fsgArrayRequestData.setStatusArrayHeaderData(laneGuidance_StatusArray, laneGuidance_GetArray, null);
            this.setHeaderForLaneElementsForCorrectSerialization(laneGuidance_StatusArray.getArrayHeader());
            laneGuidance_StatusArray.laneGuidanceOnOff = this.fullLaneGuidanceChangedArray.laneGuidanceOnOff;
            fsgArrayRequestData.fillRequestData((BAPStatusArray)laneGuidance_StatusArray, this.fullLaneGuidanceChangedArray);
            fsgArrayRequestData.setTransmissionPosForConsecutiveArray(laneGuidance_StatusArray);
            this.transmitStatus(arrayListener, laneGuidance_StatusArray);
        }
    }

    private void setHeaderForLaneElementsForCorrectSerialization(ArrayHeader arrayHeader) {
        Iterator iterator = this.fullLaneGuidanceChangedArray.getArrayData().getIterator();
        while (iterator.hasNext()) {
            ((LaneGuidance_LaneGuidance)iterator.next()).setArrayHeader(arrayHeader);
        }
    }

    public void setGetArray(BAPEntity bAPEntity, ArrayListener arrayListener) {
        arrayListener.requestError(65, this);
    }

    public void requestAcknowledge() {
        this.getArrayRequestTracker().requestAcknowledge();
    }

    public void errorAcknowledge() {
    }

    public void initialize(boolean bl) {
    }

    public void uninitialize() {
        this.timer.stop();
        this.getArrayRequestTracker().reset();
        try { this.getNavigationService().removeNavigationServiceListener(this, NAVIGATION_LISTENER_IDS); } catch (Throwable t) {}
    }

    public void indicationError(int n, BAPFunctionListener bAPFunctionListener) {
        this.getArrayRequestTracker().indicationError(n);
    }

    private void transmitStatus(ArrayListener arrayListener, LaneGuidance_StatusArray laneGuidance_StatusArray) {
        this.getArrayRequestTracker().reportStatusArray(laneGuidance_StatusArray);
    }

    private LaneGuidance_ChangedArray getLaneGuidanceListFullRangeUpdate() {
        LaneGuidance_ChangedArray laneGuidance_ChangedArray = this.dequeueBAPEntity();
        laneGuidance_ChangedArray.laneGuidanceOnOff = this.fullLaneGuidanceChangedArray.laneGuidanceOnOff;
        laneGuidance_ChangedArray.getArrayHeader().setFullRangeUpdate(false);
        return laneGuidance_ChangedArray;
    }

    public void timerFired(Timer timer) {
        int n = ((Integer)timer.getUserInfo()).intValue();
        switch (n) {
            case 1: {
                this.process(-1);
                break;
            }
        }
    }

    /**
     * Build from Android Auto while it guides, else fall through to the stock ASL list 742. The AA
     * branch reproduces the stock element handling exactly — 6-element cap, drop non-recommended
     * lanes from the head beyond that, FURTHER_LANES_NOT_SHOWN markers — so the cluster sees a list
     * built by the rules it always saw. What AA cannot supply stays neutral.
     */
    private void computeFullLaneGuidanceList(LaneGuidance_ChangedArray laneGuidance_ChangedArray) {
        // isNavCapable() is not redundant with ACTIVE: AANavReader's own navCapable() also answers
        // true on a PQ unit, where this MQB function must stay stock.
        if (ClusterCaps.isNavCapable() && NavState.ACTIVE) {
            this.computeFromAndroidAuto(laneGuidance_ChangedArray);
            return;
        }
        Object object;
        NavigationService navigationService = this.getNavigationService();
        BAPArrayData bAPArrayData = laneGuidance_ChangedArray.getArrayData();
        Iterator iterator = navigationService.getLaneGuidance();
        int n2 = 0;
        while (iterator.hasNext()) {
            iterator.next();
            ++n2;
        }
        int n3 = Math.max(n2 - 6, 0);
        boolean bl = false;
        boolean bl2 = false;
        int n4 = 0;
        int n5 = 0;
        iterator = navigationService.getLaneGuidance();
        while (iterator.hasNext()) {
            object = (NavigationLaneGuidanceElement)iterator.next();
            LaneGuidance_LaneGuidance laneGuidance_LaneGuidance = new LaneGuidance_LaneGuidance(laneGuidance_ChangedArray.getArrayHeader());
            laneGuidance_LaneGuidance.laneDirection = ((NavigationLaneGuidanceElement)object).getDirection();
            laneGuidance_LaneGuidance.laneSidestreets.setContent(((NavigationLaneGuidanceElement)object).getSideStreets());
            laneGuidance_LaneGuidance.laneType = ((NavigationLaneGuidanceElement)object).getLaneType();
            laneGuidance_LaneGuidance.laneMarking_left = ((NavigationLaneGuidanceElement)object).getMarkingLeft();
            laneGuidance_LaneGuidance.laneMarking_right = ((NavigationLaneGuidanceElement)object).getMarkingRight();
            laneGuidance_LaneGuidance.laneDescription = ((NavigationLaneGuidanceElement)object).getLaneDescription();
            laneGuidance_LaneGuidance.guidanceInfo = ((NavigationLaneGuidanceElement)object).getLaneGuidanceInfo();
            if (n4 == 0 && n3 > 0 && laneGuidance_LaneGuidance.guidanceInfo == 0) {
                bl = true;
                --n3;
                continue;
            }
            if (n5++ < 6) {
                laneGuidance_LaneGuidance.setPos(n4++);
                bAPArrayData.add(laneGuidance_LaneGuidance);
                continue;
            }
            bl2 = true;
        }
        if (n2 > 6) {
            if (bl) {
                object = (LaneGuidance_LaneGuidance)bAPArrayData.getFirst();
                ((LaneGuidance_LaneGuidance)object).laneType = 23;
            }
            if (bl2) {
                object = (LaneGuidance_LaneGuidance)bAPArrayData.getLast();
                ((LaneGuidance_LaneGuidance)object).laneType = 24;
            }
        }
        laneGuidance_ChangedArray.laneGuidanceOnOff = navigationService.getRouteGuidanceState() == 1 ? (navigationService.isSetupLaneGuidanceActive() ? 1 : 0) : 0;
    }

    /** The Android Auto source for the list above. */
    private void computeFromAndroidAuto(LaneGuidance_ChangedArray laneGuidance_ChangedArray) {
        BAPArrayData bAPArrayData = laneGuidance_ChangedArray.getArrayData();
        int[] lanes = NavState.lanes;                    // one volatile read: count + pairs
        int total = (lanes != null && lanes.length > 0) ? lanes[0] : 0;
        if (total > (lanes.length - 1) / 2) {
            total = 0;
        }
        int drop = Math.max(total - 6, 0);
        boolean droppedHead = false;
        boolean droppedTail = false;
        int placed = 0;
        int seen = 0;
        for (int i = 0; i < total; i++) {
            LaneGuidance_LaneGuidance e = new LaneGuidance_LaneGuidance(laneGuidance_ChangedArray.getArrayHeader());
            e.laneDirection = lanes[1 + 2 * i];
            e.laneSidestreets.setEmptyString();
            e.laneType = 1;                              // NORMAL_LANE
            e.laneMarking_left = 0;
            e.laneMarking_right = 0;
            e.laneDescription = 0;                       // LANE_AVAILABLE
            e.guidanceInfo = lanes[2 + 2 * i];
            if (placed == 0 && drop > 0 && e.guidanceInfo == 0) {
                droppedHead = true;
                --drop;
                continue;
            }
            if (seen++ < 6) {
                e.setPos(placed++);
                bAPArrayData.add(e);
                continue;
            }
            droppedTail = true;
        }
        if (total > 6) {
            if (droppedHead) {
                ((LaneGuidance_LaneGuidance)bAPArrayData.getFirst()).laneType = 23;   // more to the left
            }
            if (droppedTail) {
                ((LaneGuidance_LaneGuidance)bAPArrayData.getLast()).laneType = 24;    // more to the right
            }
        }
        // Stock gates on own guidance running, which it is not while AA drives — so gate on having
        // lanes instead, but keep honouring the driver's setup item (datapool 731).
        int on = 0;
        if (placed > 0) {
            boolean setup = true;
            try { setup = this.getNavigationService().isSetupLaneGuidanceActive(); } catch (Throwable t) {}
            on = setup ? 1 : 0;
        }
        laneGuidance_ChangedArray.laneGuidanceOnOff = on;
    }

    // NB: the marking comparison below reads left vs RIGHT. That is the stock firmware's own typo,
    // kept deliberately; harmless here because we always send both markings as 0.
    private static boolean laneGuidanceElementsEqual(LaneGuidance_LaneGuidance laneGuidance_LaneGuidance, LaneGuidance_LaneGuidance laneGuidance_LaneGuidance2) {
        boolean bl;
        boolean bl2 = bl = laneGuidance_LaneGuidance.laneDirection == laneGuidance_LaneGuidance2.laneDirection && laneGuidance_LaneGuidance.laneType == laneGuidance_LaneGuidance2.laneType && laneGuidance_LaneGuidance.laneMarking_left == laneGuidance_LaneGuidance2.laneMarking_right && laneGuidance_LaneGuidance.laneDescription == laneGuidance_LaneGuidance2.laneDescription && laneGuidance_LaneGuidance.guidanceInfo == laneGuidance_LaneGuidance2.guidanceInfo;
        if (bl) {
            bl = laneGuidance_LaneGuidance.laneSidestreets.toString().compareTo(laneGuidance_LaneGuidance2.laneSidestreets.toString()) == 0;
        }
        return bl;
    }

    private List findChangedData(LaneGuidance_ChangedArray laneGuidance_ChangedArray, LaneGuidance_ChangedArray laneGuidance_ChangedArray2) {
        ArrayList arrayList = new ArrayList(4);
        int n = laneGuidance_ChangedArray.getArrayData().size() - laneGuidance_ChangedArray2.getArrayData().size();
        int n2 = Math.abs(n);
        int n3 = n < 0 ? -1 : (n == 0 ? 0 : 1);
        switch (n3) {
            case -1:
            case 1: {
                int n4 = 0;
                int n5 = 0;
                int n6 = -1;
                int n7 = laneGuidance_ChangedArray2.getArrayData().size();
                int n8 = laneGuidance_ChangedArray.getArrayData().size();
                while (n5 < n7 && n4 < n8) {
                    LaneGuidance_LaneGuidance laneGuidance_LaneGuidance;
                    boolean bl;
                    LaneGuidance_LaneGuidance laneGuidance_LaneGuidance2 = (LaneGuidance_LaneGuidance)laneGuidance_ChangedArray.getArrayData().get(n4);
                    boolean bl2 = bl = !LaneGuidance.laneGuidanceElementsEqual(laneGuidance_LaneGuidance2, laneGuidance_LaneGuidance = (LaneGuidance_LaneGuidance)laneGuidance_ChangedArray2.getArrayData().get(n5));
                    if (bl) {
                        if (n6 != -1 && n6 + 1 != laneGuidance_LaneGuidance.getPos()) {
                            return null;
                        }
                        arrayList.add(laneGuidance_LaneGuidance);
                        n6 = laneGuidance_LaneGuidance.getPos();
                    }
                    if (n3 == -1) {
                        ++n5;
                        if (!bl) continue;
                        ++n4;
                        continue;
                    }
                    ++n4;
                    if (!bl) continue;
                    ++n5;
                }
                break;
            }
            default: {
                BAPArrayData bAPArrayData = laneGuidance_ChangedArray.getArrayData();
                BAPArrayData bAPArrayData2 = laneGuidance_ChangedArray2.getArrayData();
                int n9 = laneGuidance_ChangedArray.getArrayData().size();
                for (int i = 0; i < n9; ++i) {
                    LaneGuidance_LaneGuidance laneGuidance_LaneGuidance;
                    LaneGuidance_LaneGuidance laneGuidance_LaneGuidance3 = (LaneGuidance_LaneGuidance)bAPArrayData.get(i);
                    if (LaneGuidance.laneGuidanceElementsEqual(laneGuidance_LaneGuidance3, laneGuidance_LaneGuidance = (LaneGuidance_LaneGuidance)bAPArrayData2.get(i))) continue;
                    arrayList.add(laneGuidance_LaneGuidance);
                }
            }
        }
        if (arrayList.size() != n2) {
            return null;
        }
        return arrayList;
    }

    private LaneGuidance_ChangedArray searchForChangeContent(LaneGuidance_ChangedArray laneGuidance_ChangedArray, LaneGuidance_ChangedArray laneGuidance_ChangedArray2) {
        // The stock size delta below is computed from the NEW array twice, so it is always 0 and the
        // "too big to patch, resend everything" guard never fires. Harmless for a nav engine whose
        // lane list barely moves; ours goes 3->5->0 constantly, so resend in full on any size change
        // rather than trust a patch path that has probably never run.
        //
        // Gated on the SAME condition as computeFullLaneGuidanceList: when the unit's own navigation
        // fills the list we must leave its delta handling exactly as it was, whatever we think of it.
        if (ClusterCaps.isNavCapable() && NavState.ACTIVE
                && laneGuidance_ChangedArray.getArrayData().size() != laneGuidance_ChangedArray2.getArrayData().size()) {
            LaneGuidance_ChangedArray full = this.getLaneGuidanceListFullRangeUpdate();
            full.laneGuidanceOnOff = laneGuidance_ChangedArray.laneGuidanceOnOff;
            return full;
        }
        LaneGuidance_ChangedArray laneGuidance_ChangedArray3 = null;
        int n = laneGuidance_ChangedArray.getArrayData().size() - laneGuidance_ChangedArray.getArrayData().size();
        int n2 = n < 0 ? -1 : (n == 0 ? 0 : 1);
        if ((n = Math.abs(n)) > 4 || laneGuidance_ChangedArray2.getArrayData().size() == 0 ^ laneGuidance_ChangedArray.getArrayData().size() == 0 || laneGuidance_ChangedArray.laneGuidanceOnOff != laneGuidance_ChangedArray2.laneGuidanceOnOff) {
            laneGuidance_ChangedArray3 = this.getLaneGuidanceListFullRangeUpdate();
        } else {
            List list = this.findChangedData(laneGuidance_ChangedArray, laneGuidance_ChangedArray2);
            if (list == null || list.size() != 0 && list.size() == laneGuidance_ChangedArray.getArrayData().size()) {
                laneGuidance_ChangedArray3 = this.getLaneGuidanceListFullRangeUpdate();
            } else if (list.size() != 0) {
                switch (n2) {
                    case 1: {
                        LaneGuidance_LaneGuidance laneGuidance_LaneGuidance;
                        if (list.isEmpty()) break;
                        laneGuidance_ChangedArray3 = this.dequeueBAPEntity();
                        ArrayHeader arrayHeader = laneGuidance_ChangedArray3.getArrayHeader();
                        arrayHeader.mode.shift = true;
                        arrayHeader.mode.arrayDirectionIsBackward = false;
                        arrayHeader.mode.arrayPositionIsTransmitted = true;
                        arrayHeader.setRecordAddress(15);
                        LaneGuidance_LaneGuidance laneGuidance_LaneGuidance2 = (LaneGuidance_LaneGuidance)list.get(0);
                        arrayHeader.start = laneGuidance_LaneGuidance2.getPos() != 0 ? laneGuidance_LaneGuidance2.getPos() - 0 : laneGuidance_LaneGuidance2.getPos();
                        arrayHeader.elements = list.size();
                        BAPArrayData bAPArrayData = laneGuidance_ChangedArray3.getArrayData();
                        int n3 = list.size();
                        for (int i = 0; i < n3; ++i) {
                            laneGuidance_LaneGuidance = (LaneGuidance_LaneGuidance)list.get(i);
                            bAPArrayData.add(laneGuidance_LaneGuidance);
                        }
                        LaneGuidance_LaneGuidance laneGuidance_LaneGuidance3 = (LaneGuidance_LaneGuidance)list.get(list.size() - 1);
                        laneGuidance_LaneGuidance = new LaneGuidance_LaneGuidance(laneGuidance_ChangedArray3.getArrayHeader());
                        laneGuidance_LaneGuidance.setPos(laneGuidance_LaneGuidance3.getPos() % list.size());
                        laneGuidance_ChangedArray3.getArrayData().add(laneGuidance_LaneGuidance);
                        break;
                    }
                    case -1: {
                        if (list.isEmpty()) break;
                        laneGuidance_ChangedArray3 = this.dequeueBAPEntity();
                        ArrayHeader arrayHeader = laneGuidance_ChangedArray3.getArrayHeader();
                        arrayHeader.mode.shift = true;
                        arrayHeader.mode.arrayDirectionIsBackward = true;
                        arrayHeader.mode.arrayPositionIsTransmitted = false;
                        arrayHeader.start = ((LaneGuidance_LaneGuidance)list.get(list.size() - 1)).getPos();
                        arrayHeader.elements = list.size();
                        break;
                    }
                    default: {
                        LaneGuidance_LaneGuidance laneGuidance_LaneGuidance;
                        laneGuidance_ChangedArray3 = this.dequeueBAPEntity();
                        ArrayHeader arrayHeader = laneGuidance_ChangedArray3.getArrayHeader();
                        arrayHeader.mode.shift = false;
                        arrayHeader.mode.arrayDirectionIsBackward = false;
                        arrayHeader.mode.arrayPositionIsTransmitted = true;
                        arrayHeader.setRecordAddress(15);
                        arrayHeader.setRecordAddress(0);
                        LaneGuidance_LaneGuidance laneGuidance_LaneGuidance4 = (LaneGuidance_LaneGuidance)list.get(0);
                        arrayHeader.start = laneGuidance_LaneGuidance4.getPos() != 0 ? laneGuidance_LaneGuidance4.getPos() - 0 : laneGuidance_LaneGuidance4.getPos();
                        arrayHeader.elements = list.size();
                        BAPArrayData bAPArrayData = laneGuidance_ChangedArray3.getArrayData();
                        int n4 = list.size();
                        for (int i = 0; i < n4; ++i) {
                            laneGuidance_LaneGuidance = (LaneGuidance_LaneGuidance)list.get(i);
                            bAPArrayData.add(laneGuidance_LaneGuidance);
                        }
                        LaneGuidance_LaneGuidance laneGuidance_LaneGuidance5 = (LaneGuidance_LaneGuidance)list.get(list.size() - 1);
                        laneGuidance_LaneGuidance = new LaneGuidance_LaneGuidance(laneGuidance_ChangedArray3.getArrayHeader());
                        laneGuidance_LaneGuidance.setPos(laneGuidance_LaneGuidance5.getPos() % list.size());
                        laneGuidance_ChangedArray3.getArrayData().add(laneGuidance_LaneGuidance);
                        break;
                    }
                }
            }
        }
        if (laneGuidance_ChangedArray3 != null) {
            laneGuidance_ChangedArray3.laneGuidanceOnOff = laneGuidance_ChangedArray.laneGuidanceOnOff;
        }
        return laneGuidance_ChangedArray3;
    }

    public void processHMIEvent(int n) {
    }

    public void updateNavigationData(NavigationService navigationService, int n) {
        this.process(-1);
    }

    static Class class$(String string) {
        try {
            return Class.forName(string);
        }
        catch (ClassNotFoundException classNotFoundException) {
            // -source 1.3 has no Throwable.initCause; same form the other shadows use.
            throw new NoClassDefFoundError(string);
        }
    }

    static {
        NAVIGATION_LISTENER_IDS = new int[]{732, 731, 742};
    }
}
