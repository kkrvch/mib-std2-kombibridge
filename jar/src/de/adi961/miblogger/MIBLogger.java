/*
 * MIBLogger — from adi961's mib2-android-auto-vc project:
 *   https://github.com/adi961/mib2-android-auto-vc
 * The de.adi961.miblogger package name is kept as-is to credit the origin.
 */
package de.adi961.miblogger;

import de.aatokombi.Config;

public class MIBLogger {

    static String logPrefix = "MIBLogger";

    public static final int TRACE = 1;
    public static final int DEBUG = 2;
    public static final int INFO = 3;
    public static final int ERROR = 4;
    public static final int SILENT = 5;

    private int logLevel = Config.Advanced.LOG_LEVEL;

    static MIBLogger instance;

    public static MIBLogger getInstance() {
        if (instance != null) {
            return instance;
        }
        instance = new MIBLogger();
        return instance;
    }

    public MIBLogger() {
        this.info("Starting with log level of " + logLevel);
    }

    public void trace(String message) {
        if (logLevel <= TRACE) {
            log("TRACE", message);
        }
    }

    public void debug(String message) {
        if (logLevel <= DEBUG) {
            log("DEBUG", message);
        }
    }

    public void info(String message) {
        if (logLevel <= INFO) {
            log("INFO", message);
        }
    }

    public void error(String message) {
        if (logLevel <= ERROR) {
            log("ERROR", message);
        }
    }

    void log(String level, String message) {
        String line = "[" + logPrefix + "] [" + level + "] [" + getCallingClassName() + "]: " + message;
        System.out.println(line);   // goes to the system j9/hmi log (sloginfo), system-managed
        // Optional diagnostic mirror to a RAM file, so the log can be pulled to SD via the toolbox
        // dump (no direct FS access to the unit). OFF unless DEBUG/TRACE is enabled: /dev/shmem is
        // RAM — at INFO our lines are still in sloginfo anyway.
        if (logLevel <= DEBUG) {
            mirror(line);
        }
    }

    /**
     * The RAM mirror is bounded: at MIRROR_MAX_BYTES the file becomes MIRROR_PATH + ".1" (replacing
     * the previous one) and a new one is started, so the newest lines are always kept and the two
     * files together never take more than twice that. Sizes are counted in chars, close enough.
     */
    static final String MIRROR_PATH = "/dev/shmem/aatokombi.log";
    static final long MIRROR_MAX_BYTES = 1024L * 1024L;
    private static String mirrorPath = MIRROR_PATH;
    private static long mirrorMax = MIRROR_MAX_BYTES;
    private static long mirrorBytes = -1L;

    /** Host tests only: another file and limit (the unit always uses the constants above). */
    static synchronized void mirrorTo(String path, long maxBytes) {
        mirrorPath = path;
        mirrorMax = maxBytes;
        mirrorBytes = -1L;
    }

    static synchronized void mirror(String line) {
        try {
            if (mirrorBytes < 0L) {
                mirrorBytes = new java.io.File(mirrorPath).length();   // 0 when there is no file yet
            }
            if (mirrorBytes >= mirrorMax) {
                java.io.File old = new java.io.File(mirrorPath + ".1");
                old.delete();
                if (!new java.io.File(mirrorPath).renameTo(old)) {
                    new java.io.File(mirrorPath).delete();
                }
                mirrorBytes = 0L;
            }
            java.io.FileWriter fw = new java.io.FileWriter(mirrorPath, true);
            try {
                fw.write(line);
                fw.write('\n');
            } finally {
                fw.close();
            }
            mirrorBytes += line.length() + 1;
        } catch (Throwable t) {
            // never let logging disturb the HMI
        }
    }

    private String getCallingClassName() {
        try {
            throw new Exception();
        } catch (Exception e) {
            StackTraceElement[] stackTrace = e.getStackTrace();
            if (stackTrace.length >= 4) {
                StackTraceElement caller = stackTrace[3];
                return caller.getClassName() + "." + caller.getMethodName();
            }
        }
        return "UnknownClass";
    }

    public void setLogLevel(int level) {
        this.logLevel = level;
    }

    public int getLogLevel() {
        return logLevel;
    }
}
