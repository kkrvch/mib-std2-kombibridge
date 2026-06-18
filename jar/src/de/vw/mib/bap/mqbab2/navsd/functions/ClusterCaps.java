package de.vw.mib.bap.mqbab2.navsd.functions;

import de.vw.mib.bap.mqbab2.common.api.APIFactory;
import de.vw.mib.bap.mqbab2.common.api.configuration.ConfigurationService;

/**
 * Runtime detection of whether THIS head unit's cluster is coded for navigation, so a single build
 * self-selects its output path:
 *   nav-capable (e.g. Amundsen) -> drive the real cluster Navigation menu via the navsd shadows
 *   non-nav     (e.g. Bolero)   -> route the maneuver into the media now-playing widget
 *
 * The firmware itself uses the same flag to pick which navsd binding factory to build
 * (NavSDFunctionControlUnit.getBindingFactory -> ConfigurationService.isNavigationFeatureSelected()):
 * a non-navigation unit ends up with idle/null navsd functions. We reuse that exact, canonical
 * signal instead of a static per-build switch -- replacing NavState.NAV_CAPABLE.
 *
 * Conservative by construction: any failure (APIs not ready, unexpected throw) -> reported as
 * non-nav, i.e. the media path, which is the already-proven Bolero behaviour. The result is cached
 * only on a successful read, so an early call before the common API is up does not latch a wrong
 * answer -- a later call re-evaluates.
 *
 * Java 1.4 (cf48): no enums, plain int tri-state, plain volatile static.
 */
public final class ClusterCaps {
    private static final int UNKNOWN = 0;
    private static final int NAV = 1;
    private static final int NON_NAV = 2;
    private static volatile int _state = UNKNOWN;

    /** True iff the cluster is coded for navigation (navsd output usable). Cached after first success. */
    public static boolean isNavCapable() {
        int s = _state;
        if (s != UNKNOWN) {
            return s == NAV;
        }
        try {
            ConfigurationService cfg = APIFactory.getAPIFactory().getConfigurationService();
            boolean nav = cfg.isNavigationFeatureSelected();
            _state = nav ? NAV : NON_NAV;   // latch only on a clean read
            return nav;
        } catch (Throwable t) {
            return false;                   // do NOT latch: assume non-nav for now, re-check later
        }
    }

    private ClusterCaps() {}
}
