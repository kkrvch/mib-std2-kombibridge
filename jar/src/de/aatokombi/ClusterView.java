package de.aatokombi;

/**
 * What the AID currently shows around the map, as the cluster reports it over BAP navsd:
 *  - MapPresentation (function 54) setGet: ASG_HMI_State {largeMapView, leftSideMenueOpen,
 *    rightSideMenueOpen} - the AID's view (large map or not) and its side menus;
 *  - MapViewAndOrientation setGet: mapVisibility {lvdsMapIsVisible, supplementaryMapViewIsVisible}.
 * Every change is logged (INFO) and written to /dev/shmem/aa_cluster_view as
 * "large left right lvds supplementary changes" for the native side. Groundwork for per-view crop
 * and insets of the AA cluster map (plan 2026-11-08); until now only diagnostics.
 * Java 1.3 source; never throws.
 */
public final class ClusterView {
    public static final String PATH = "/dev/shmem/aa_cluster_view";

    private static int large = -1, left = -1, right = -1, lvds = -1, supp = -1;
    private static int changes;

    private ClusterView() {}

    /** MapPresentation setGet / status entity (any object with a public asg_Hmi_State). */
    public static void presentation(Object entity) {
        try {
            Object st = field(entity, "asg_Hmi_State");
            if (st == null) return;
            update(bool(st, "largeMapView"), bool(st, "leftSideMenueOpen"), bool(st, "rightSideMenueOpen"), lvds, supp);
        } catch (Throwable t) {
            // never disturb navsd
        }
    }

    /** MapViewAndOrientation setGet entity (public mapVisibility). */
    public static void visibility(Object entity) {
        try {
            Object v = field(entity, "mapVisibility");
            if (v == null) return;
            update(large, left, right, bool(v, "lvdsMapIsVisible"), bool(v, "supplementaryMapViewIsVisible"));
        } catch (Throwable t) {
            // never disturb navsd
        }
    }

    /** Store a new cluster view state; on a change log it and rewrite the state file. */
    private static synchronized void update(int l, int lm, int rm, int lv, int sp) {
        if (l == large && lm == left && rm == right && lv == lvds && sp == supp) return;
        large = l; left = lm; right = rm; lvds = lv; supp = sp; changes++;
        String s = large + " " + left + " " + right + " " + lvds + " " + supp + " " + changes;
        de.adi961.miblogger.MIBLogger.getInstance().info("cluster view: large=" + large + " left=" + left + " right=" + right
                + " lvds=" + lvds + " supplementary=" + supp + " (#" + changes + ")");
        try {
            java.io.FileWriter w = new java.io.FileWriter(PATH);
            try { w.write(s + "\n"); } finally { w.close(); }
        } catch (Throwable t) {
            // /dev/shmem missing: diagnostics only
        }
    }

    /** A public field of {@code o} by reflection (the struct's class differs between builds). */
    private static Object field(Object o, String name) throws Exception {
        if (o == null) return null;
        return o.getClass().getField(name).get(o);
    }

    /** A boolean field as 1 / 0, or -1 when it is missing or not a boolean. */
    private static int bool(Object o, String name) {
        try {
            Object v = field(o, name);
            return v instanceof Boolean ? (((Boolean) v).booleanValue() ? 1 : 0) : -1;
        } catch (Throwable t) {
            return -1;
        }
    }
}
