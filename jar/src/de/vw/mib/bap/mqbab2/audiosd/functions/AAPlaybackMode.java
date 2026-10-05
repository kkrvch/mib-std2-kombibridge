package de.vw.mib.bap.mqbab2.audiosd.functions;

/**
 * Android Auto shuffle / repeat -> the audiosd SourceState vocabulary. Pure functions with no
 * framework dependency, so the host test can load them (the SourceState shadow itself cannot be
 * loaded outside the HMI). Java 1.4 (cf48).
 */
public final class AAPlaybackMode {
    public static final int SHUFFLE = 1;
    public static final int REPEAT = 2;
    public static final int REPEAT_ONE = 4;

    private AAPlaybackMode() {}

    /** The three phone flags packed into one int (bit0 shuffle, bit1 repeat, bit2 repeat-one). */
    public static int pack(boolean shuffle, boolean repeat, boolean repeatOne) {
        return (shuffle ? SHUFFLE : 0) | (repeat ? REPEAT : 0) | (repeatOne ? REPEAT_ONE : 0);
    }

    /**
     * {stateInfo, stateInfo_Scope} in the stock media vocabulary (SourceState.setStateInfoForMedia /
     * setStateInfoScopeForMedia): stateInfo 2 = mix, 3 = repeat, 4 = repeat + mix, 0 = none;
     * scope 1 = the track, 4 = the list (a phone has no folders, so never 5). Repeat-one is the
     * narrower of the two repeats and wins when the phone reports both.
     */
    public static int[] stateInfo(int mode) {
        boolean shuffle = (mode & SHUFFLE) != 0;
        boolean repeatOne = (mode & REPEAT_ONE) != 0;
        boolean repeat = repeatOne || (mode & REPEAT) != 0;
        if (shuffle && repeat) {
            return new int[]{ 4, repeatOne ? 1 : 4 };
        }
        if (repeat) {
            return new int[]{ 3, repeatOne ? 1 : 4 };
        }
        if (shuffle) {
            return new int[]{ 2, 4 };
        }
        return new int[]{ 0, 0 };
    }
}
