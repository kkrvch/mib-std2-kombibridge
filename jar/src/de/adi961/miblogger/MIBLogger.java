/*
 * MIBLogger — from adi961's mib2-android-auto-vc project:
 *   https://github.com/adi961/mib2-android-auto-vc
 * The de.adi961.miblogger package name is kept as-is to credit the origin.
 */
package de.adi961.miblogger;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class MIBLogger {

    static String logPrefix = "MIBLogger";

    public static final int TRACE = 1;
    public static final int DEBUG = 2;
    public static final int INFO = 3;
    public static final int ERROR = 4;
    public static final int SILENT = 5;

    // Default INFO: quiet in normal use (a few startup lines). Set DEBUG/TRACE via the config
    // file (/media/mp000/MIBLogger) for a debugging session.
    private int logLevel = INFO;

    static MIBLogger instance;

    public static MIBLogger getInstance() {
        if (instance != null) {
            return instance;
        }
        // MST2 SD card mounts at /media/mp000.
        instance = new MIBLogger("/media/mp000/MIBLogger");
        return instance;
    }

    public MIBLogger(String configFilePath) {
        try {
            BufferedReader br = new BufferedReader(new FileReader(configFilePath));
            String line = br.readLine();
            if (line != null) {
                if (line.equalsIgnoreCase("TRACE")) {
                    logLevel = TRACE;
                } else if (line.equalsIgnoreCase("DEBUG")) {
                    logLevel = DEBUG;
                } else if (line.equalsIgnoreCase("INFO")) {
                    logLevel = INFO;
                } else if (line.equalsIgnoreCase("ERROR")) {
                    logLevel = ERROR;
                } else if (line.equalsIgnoreCase("SILENT")) {
                    logLevel = SILENT;
                }
            }
            br.close();
        } catch (IOException e) {
            this.info("No config file found at " + configFilePath);
            System.out.println("Error reading the log configuration file.");
        }

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
        // dump (no direct FS access to the unit). OFF unless DEBUG/TRACE is enabled, because this
        // appends unbounded and /dev/shmem is RAM — at INFO our lines are still in sloginfo anyway.
        if (logLevel <= DEBUG) {
            try {
                java.io.FileWriter fw = new java.io.FileWriter("/dev/shmem/aatokombi.log", true);
                fw.write(line);
                fw.write('\n');
                fw.close();
            } catch (Throwable t) {
                // never let logging disturb the HMI
            }
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