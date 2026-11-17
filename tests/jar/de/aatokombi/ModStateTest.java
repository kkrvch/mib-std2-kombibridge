package de.aatokombi;

import de.adi961.miblogger.MIBLogger;

/**
 * Host test of the small decisions in de.aatokombi that need no head unit: the cluster video state
 * line (ClusterVideo.parseState), the steering-wheel key handling (WheelKeys) and the IPC end line
 * (IpcFile). In this package to reach the package-private members. Run by tests/jar/run.sh; never part of the shipped jar.
 */
public final class ModStateTest {
    private static int failed;

    private static void eq(String what, String got, String want) {
        if (got == null ? want != null : !got.equals(want)) {
            failed++;
            System.out.println("  FAIL " + what + ": got " + got + ", want " + want);
        }
    }

    private static void ok(String what, boolean cond) {
        if (!cond) {
            failed++;
            System.out.println("  FAIL " + what);
        }
    }

    private static String join(String[] s) {
        return s == null ? null : s[0] + "|" + s[1] + "|" + s[2];
    }

    private static void stateLine() {
        eq("video on, session open", join(ClusterVideo.parseState("1 1 57 1\n")), "1|57|1");
        eq("stream started, DisplayManager not yet", join(ClusterVideo.parseState("1 0 58 1\n")), "0|58|1");
        eq("session open, no video", join(ClusterVideo.parseState("0 0 59 1\n")), "0|59|1");
        eq("session closed", join(ClusterVideo.parseState("0 0 60 0\n")), "0|60|0");
        eq("older libgal without the session field: follows video", join(ClusterVideo.parseState("1 1 61\n")), "1|61|1");
        eq("older libgal, no video", join(ClusterVideo.parseState("0 0 62\n")), "0|62|0");
        // What a read gives while the libgal rewrites the file: nothing, or a cut line. Not a state.
        eq("no file", join(ClusterVideo.parseState(null)), null);
        eq("empty file", join(ClusterVideo.parseState("")), null);
        eq("only whitespace", join(ClusterVideo.parseState("\n")), null);
        eq("cut after the flags", join(ClusterVideo.parseState("1 1")), null);
        eq("cut before the tick", join(ClusterVideo.parseState("1 1 ")), null);
        eq("garbage", join(ClusterVideo.parseState("xxxxxxx")), null);
    }

    private static void viewKey() {
        ok("patch installed and on -> the View code", WheelKeys.viewCodeFor(true, false) == 59);
        ok("patch installed but switched off -> stock key", WheelKeys.viewCodeFor(true, true) == -1);
        ok("no patch -> stock key", WheelKeys.viewCodeFor(false, false) == -1);
        ok("no patch, off file left over -> stock key", WheelKeys.viewCodeFor(false, true) == -1);

        // state 1 = pressed (a release would write /dev/shmem/aa_view_key)
        WheelKeys.viewCode = -1;
        ok("no patch: code 59 on the wheel goes to the stock handling", !WheelKeys.key(WheelKeys.KEYBOARD_MFW, 59, 1, 0, 0));
        WheelKeys.viewCode = 59;
        ok("patch: code 59 on the wheel is ours", WheelKeys.key(WheelKeys.KEYBOARD_MFW, 59, 1, 0, 0));
        ok("patch: also on the 3GP wheel keyboard", WheelKeys.key(WheelKeys.KEYBOARD_MFW_3GP, 59, 1, 0, 0));
        ok("patch: code 59 on another keyboard is not", !WheelKeys.key(2, 59, 1, 0, 0));
        ok("patch: another code on the wheel is not", !WheelKeys.key(WheelKeys.KEYBOARD_MFW, 58, 1, 0, 0));
        WheelKeys.viewCode = -1;
    }

    private static void modeKey() {
        int key = Config.ClusterMap.MODE_KEY == Config.ClusterMap.ModeKey.BACK ? WheelKeys.KEY_BACK
                : Config.ClusterMap.MODE_KEY == Config.ClusterMap.ModeKey.VOLUME_ROLLER ? WheelKeys.KEY_VOLUME_ROLLER : -1;
        if (key < 0 || !Config.ClusterMap.MODE_CYCLE) {
            ok("no mode key configured: nothing requested", !WheelKeys.takeCycleRequest());
            return;
        }
        WheelKeys.takeCycleRequest();
        ok("the mode key never hides the key from the stock handling", !WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 1, 0, 0));
        ok("pressed: nothing yet", !WheelKeys.takeCycleRequest());
        WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 3, 0, 0);
        ok("held ~0.8 s: nothing yet", !WheelKeys.takeCycleRequest());
        WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 4, 0, 0);
        ok("held ~2 s: next mode requested", WheelKeys.takeCycleRequest());
        ok("the request is taken once", !WheelKeys.takeCycleRequest());
        WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 5, 0, 0);
        ok("still held: not requested again", !WheelKeys.takeCycleRequest());
        WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 0, 0, 0);
        WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 1, 0, 0);
        WheelKeys.key(WheelKeys.KEYBOARD_MFW, key, 4, 0, 0);
        ok("released and held again: requested again", WheelKeys.takeCycleRequest());
        WheelKeys.key(2, key, 4, 0, 0);
        ok("the same code on another keyboard: nothing", !WheelKeys.takeCycleRequest());
    }

    private static void quiet() {
        String where = "ModStateTest#1";
        ok("a place never noted counts 0", Quiet.count(where, false) == 0);
        for (int i = 1; i <= 7; i++) Quiet.note(where, new RuntimeException("x" + i));
        ok("every swallowed exception is counted", Quiet.count(where, false) == 7);
        ok("another place has its own count", Quiet.count("ModStateTest#2", false) == 0);
        ok("the first ones are logged", Quiet.logged(1) && Quiet.logged(Quiet.FIRST));
        ok("then it goes quiet", !Quiet.logged(Quiet.FIRST + 1) && !Quiet.logged(Quiet.EVERY - 1));
        ok("and reports again every EVERY-th", Quiet.logged(Quiet.EVERY) && Quiet.logged(2 * Quiet.EVERY));
        try {
            Quiet.note(null, null);
            Quiet.note("ModStateTest#3", null);
            ok("note itself never throws", true);
        } catch (Throwable t) {
            ok("note itself never throws", false);
        }
    }

    private static int body(String text) {
        byte[] b = text.getBytes();
        return IpcFile.body(b, b.length);
    }

    // The IPC end line: a file counts only when it ends with "end\n" on a line of its own.
    private static void ipcEnd() {
        ok("complete nav file", body("1 1 4 1 0 0 300 20 1 1 5 Main St\n4 1000 60\nend\n") == 43);
        ok("complete media file", body("3 Song\tArtist\nend\n") == 14);
        ok("end line alone", body("end\n") == 0);
        ok("torn: no end line", body("1 1 4 1 0 0 300 20 1 1 5 Main") == -1);
        ok("torn: end line cut", body("3 Song\nen") == -1);
        ok("torn: no final newline", body("3 Song\nend") == -1);
        ok("road merely ends in end", body("1 2 3 Weekend\n") == -1);
        ok("empty", body("") == -1);
        ok("CR LF end line rejected", body("3 Song\r\nend\r\n") == -1);
        ok("null buffer", IpcFile.body(null, 4) == -1);
        ok("n beyond the buffer", IpcFile.body(new byte[2], 4) == -1);
        byte[] big = "xx\nend\nyy".getBytes();
        ok("length counts, not the buffer end", IpcFile.body(big, 7) == 3);
    }

    private static String pad(String s) {
        StringBuffer b = new StringBuffer(s);
        while (b.length() < 24) b.append(' ');
        return b.toString();
    }

    public static void main(String[] args) {
        MIBLogger.getInstance().setLogLevel(MIBLogger.SILENT);   // the key handling logs every key
        String[] names = { "cluster state line", "wheel view key", "wheel mode key", "swallowed exceptions",
                           "ipc end line" };
        for (int i = 0; i < names.length; i++) {
            int before = failed;
            switch (i) {
                case 0: stateLine(); break;
                case 1: viewKey(); break;
                case 2: modeKey(); break;
                case 3: quiet(); break;
                default: ipcEnd(); break;
            }
            System.out.println(pad(names[i]) + (failed == before ? "ok" : "FAILED"));
        }
        System.out.println(failed == 0 ? "all tests passed" : "TESTS FAILED");
        if (failed != 0) {
            System.exit(1);
        }
    }
}
