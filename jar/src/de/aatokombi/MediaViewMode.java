package de.aatokombi;

/** Display choice only. No media command or source change is sent. */
public final class MediaViewMode {
    private static boolean active;
    private static boolean music;

    public static synchronized void guidance(boolean value) {
        if (!value || !active) music = false;
        active = value;
    }

    public static synchronized boolean showNavigation() { return !music; }

    /** Direction selects a view rather than toggling on every repeated wheel event. */
    public static synchronized boolean select(int control, int list, int count) {
        if (!active || list != 0 || count < 1 || (control != 1 && control != 2)) return false;
        music = control == 1;
        return true;
    }

    private MediaViewMode() {}
}
