package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * For the places where the mod must never throw into the HMI and therefore swallows whatever
 * comes: the exception still leaves a trace. The first FIRST of them at each place are logged in
 * full (ERROR, so also in an everyday build), then every EVERY-th with the count - a place on a
 * 300 ms timer cannot flood the log, and a failure in the field is no longer invisible.
 *
 * `where` names the place: "File#n", n counting the catch blocks of that file from the top.
 * Java 1.3 source; never throws itself.
 */
public final class Quiet {
    static final int FIRST = 3;
    static final int EVERY = 500;
    private static final java.util.Hashtable counts = new java.util.Hashtable();

    private Quiet() {}

    public static void note(String where, Throwable t) {
        try {
            int n = count(where, true);
            if (n <= FIRST || n % EVERY == 0) {
                MIBLogger.getInstance().error("swallowed at " + where + " (#" + n + "): " + t);
            }
        } catch (Throwable x) {
            // logging is the last thing allowed to throw here
        }
    }

    /** How often `where` has been noted; `add` counts this one in. */
    static int count(String where, boolean add) {
        synchronized (counts) {
            int[] c = (int[]) counts.get(where);
            if (c == null) {
                if (!add) {
                    return 0;
                }
                c = new int[1];
                counts.put(where, c);
            }
            if (add) {
                c[0]++;
            }
            return c[0];
        }
    }

    /** Whether note() would write a line for the n-th exception of a place. */
    static boolean logged(int n) {
        return n <= FIRST || n % EVERY == 0;
    }
}
