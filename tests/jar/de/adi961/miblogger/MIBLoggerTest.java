package de.adi961.miblogger;

import java.io.File;

/**
 * Host test of the bounded RAM mirror of the jar log (MIBLogger.mirror): it must keep the newest
 * lines and never take more than about twice its limit. Run by tests/jar/run.sh.
 */
public final class MIBLoggerTest {
    private static int failed;

    private static void ok(String what, boolean cond) {
        if (!cond) {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static String read(File f) throws Exception {
        java.io.FileReader r = new java.io.FileReader(f);
        try {
            StringBuffer b = new StringBuffer();
            char[] c = new char[4096];
            int n;
            while ((n = r.read(c)) > 0) b.append(c, 0, n);
            return b.toString();
        } finally {
            r.close();
        }
    }

    public static void main(String[] args) throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "aatokombi-logtest-" + System.currentTimeMillis());
        dir.mkdirs();
        File log = new File(dir, "aatokombi.log");
        File old = new File(dir, "aatokombi.log.1");
        final long max = 1000L;
        final int lineBytes = 40;                       // 39 chars + newline
        MIBLogger.mirrorTo(log.getPath(), max);
        long biggestPair = 0L;
        for (int i = 0; i < 500; i++) {
            String n = String.valueOf(1000 + i);
            StringBuffer b = new StringBuffer("line ").append(n).append(' ');
            while (b.length() < lineBytes - 1) b.append('.');
            MIBLogger.mirror(b.toString());
            long pair = log.length() + (old.exists() ? old.length() : 0L);
            if (pair > biggestPair) biggestPair = pair;
        }
        String now = read(log);
        ok("the newest line is in the current file", now.indexOf("line 1499 ") >= 0);
        ok("the current file stays near the limit", log.length() <= max + lineBytes);
        ok("the previous file exists and stays near the limit", old.exists() && old.length() <= max + lineBytes);
        ok("both files together never exceeded twice the limit", biggestPair <= 2 * (max + lineBytes));
        ok("the oldest lines are gone", now.indexOf("line 1000 ") < 0 && read(old).indexOf("line 1000 ") < 0);
        ok("the two files hold consecutive lines", read(old).indexOf("line " + (1499 - (int) (log.length() / lineBytes)) + " ") >= 0);

        // a file already over the limit when the HMI starts (left from before): rolled at once
        MIBLogger.mirrorTo(log.getPath(), 100L);
        MIBLogger.mirror("after restart");
        ok("an oversized file found at start is rolled over", read(log).equals("after restart\n"));

        // an unwritable place must not throw into the caller
        MIBLogger.mirrorTo(new File(dir, "no-such-dir/x.log").getPath(), 100L);
        try {
            MIBLogger.mirror("nowhere");
            ok("unwritable path is swallowed", true);
        } catch (Throwable t) {
            ok("unwritable path is swallowed", false);
        }

        log.delete();
        old.delete();
        dir.delete();
        System.out.println("log mirror              " + (failed == 0 ? "ok" : "FAILED"));
        System.out.println(failed == 0 ? "all tests passed" : "TESTS FAILED");
        if (failed != 0) {
            System.exit(1);
        }
    }
}
