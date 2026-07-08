/*
 * Decompiled with CFR 0.152, then AAtoKombi delta applied (see isAndroidAutoRouteGuidanceActive).
 * Rewritten off @Override / enhanced-for so it compiles at -source/-target 1.3 like the rest of the mod.
 */
package de.vw.mib.bap.mqbab2.common.api.androidauto;

import de.aatokombi.Config;
import de.vw.mib.bap.mqbab2.common.api.APIFactoryInterface;
import de.vw.mib.bap.mqbab2.common.api.asl.ASLDataPoolAdapter;
import de.vw.mib.datapool.ASLDatapool;
import java.util.Iterator;
import java.util.List;

/**
 * AAtoKombi bootclasspath shadow of the stock AndroidAutoASLDataAdapter.
 *
 * Byte-faithful to stock EXCEPT {@link #isAndroidAutoRouteGuidanceActive()}: this is the SOLE reader
 * the cluster navsd {@code InfoStates} function (BAP fid 38) consults for the Android-Auto branch of
 * its "route guidance active" state — when it returns true, InfoStates reports state 6, which the
 * cluster shows as the "Navigation on the mobile device is active" placeholder. Forcing it false when
 * {@link Config#SUPPRESS_NAV_ACTIVE_PLACEHOLDER} is on collapses every datapool-895953920 writer at the
 * consumer (RequestHandler's hardcoded-true write + the ASLHandler cached-field republishes), and is
 * immune to the re-latch those republishes would otherwise cause. AAtoKombi's own turn-by-turn injection
 * does not read this method (it keys off NavigationHandler.aaRouteGuidanceActive), so suppressing it only
 * removes the stock placeholder. Set the flag false for pure-stock behaviour.
 */
public class AndroidAutoASLDataAdapter
extends ASLDataPoolAdapter
implements AndroidAutoService {
    private APIFactoryInterface apiFactory;
    private static final int[] ANDROID_AUTO_ASL_PROPERTY_IDS_TO_LISTEN_TO = new int[]{895953920};

    public AndroidAutoASLDataAdapter(APIFactoryInterface aPIFactoryInterface, ASLDatapool aSLDatapool) {
        super(aSLDatapool);
        this.apiFactory = aPIFactoryInterface;
        this.register();
    }

    public boolean isAndroidAutoRouteGuidanceActive() {
        // --- the ONLY behavioural change vs stock ---
        if (Config.SUPPRESS_NAV_ACTIVE_PLACEHOLDER) {
            return false;
        }
        return this.getDataPool().getBoolean(895953920, false);
    }

    public void datapoolValueChanged(int n) {
        this._notifyServiceDelegates(n);
    }

    private void _notifyServiceDelegates(int n) {
        List list = this.getRegisteredServiceDelegates(n);
        if (list != null) {
            Iterator it = list.iterator();
            while (it.hasNext()) {
                AndroidAutoServiceListener androidAutoServiceListener = (AndroidAutoServiceListener) it.next();
                androidAutoServiceListener.updateAndroidAutoData(this, n);
            }
        }
    }

    protected int[] getPropertyIds() {
        return ANDROID_AUTO_ASL_PROPERTY_IDS_TO_LISTEN_TO;
    }

    protected int[] getListIds() {
        return null;
    }

    protected void listValueChanged(int n) {
        this._notifyServiceDelegates(n);
    }

    public void addAndroidAutoServiceListener(AndroidAutoServiceListener androidAutoServiceListener, int[] nArray) {
        this.registerServiceListener((Object)androidAutoServiceListener, nArray);
    }

    public void removeAndroidAutoServiceListener(AndroidAutoServiceListener androidAutoServiceListener, int[] nArray) {
        this.removeServiceListener(androidAutoServiceListener, nArray);
    }
}
