package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * Switch for the shim's trip dump (shim/src/navshim_fs.cpp, "trip dump"): every message the phone
 * sends on the navigation and media channels, written by the patched GAL library to
 * aa_dump_nav.log / aa_dump_media.log on the SD card (else /dev/shmem).
 *
 * The shim is built separately and has no Config of its own, so the one switch stays here:
 * Config.LOG_LEVEL at DEBUG (or TRACE) creates the flag file FLAG at HMI start, anything quieter
 * removes it. The shim looks for the flag once per Android Auto session.
 *
 * Java 1.4 (cf48). Must never throw into the HMI.
 */
public final class TripDump {
    public static final String FLAG = "/dev/shmem/aa_debug";

    private TripDump() {}

    /** Create or remove the flag to match Config.LOG_LEVEL. */
    public static void apply() {
        try {
            java.io.File f = new java.io.File(FLAG);
            if (Config.LOG_LEVEL <= MIBLogger.DEBUG) {
                if (!f.exists()) {
                    java.io.FileWriter w = new java.io.FileWriter(FLAG);
                    try {
                        w.write("1\n");
                    } finally {
                        w.close();
                    }
                }
                MIBLogger.getInstance().info("trip dump enabled (" + FLAG + ")");
            } else if (f.exists()) {
                f.delete();
            }
        } catch (Throwable t) {
            // diagnostics only
        }
    }
}
