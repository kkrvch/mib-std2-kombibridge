package de.aatokombi;
public final class MediaViewModeTest {
    private static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) throws Exception {
        Class.forName("de.vw.mib.bap.mqbab2.audiosd.functions.DedicatedAudioControl", false, MediaViewModeTest.class.getClassLoader()).getDeclaredMethods();
        MediaViewMode.guidance(false);
        check(!MediaViewMode.select(1, 0, 1));
        MediaViewMode.guidance(true);
        check(MediaViewMode.showNavigation());
        check(!MediaViewMode.select(0, 0, 1));
        check(!MediaViewMode.select(1, 1, 1));
        check(!MediaViewMode.select(1, 0, 0));
        check(MediaViewMode.select(1, 0, 1));
        check(!MediaViewMode.showNavigation());
        check(MediaViewMode.select(1, 0, 7));
        MediaViewMode.guidance(true); // new guidance snapshot / rerouting preserves the view
        check(!MediaViewMode.showNavigation());
        check(MediaViewMode.select(2, 0, 1));
        check(MediaViewMode.showNavigation());
        MediaViewMode.select(1, 0, 1);
        MediaViewMode.guidance(false); // end, disconnect, source/session change
        check(MediaViewMode.showNavigation());
        check(!MediaViewMode.select(2, 0, 1));
        MediaViewMode.guidance(true);
        check(MediaViewMode.showNavigation());
        System.out.println("MediaViewModeTest PASS");
    }
}
