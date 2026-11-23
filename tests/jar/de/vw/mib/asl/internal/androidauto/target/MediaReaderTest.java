package de.vw.mib.asl.internal.androidauto.target;

/**
 * Host test of ShmemMediaReader's reading of the session epoch (field 8 of the aa_media line).
 * Run by tests/jar/run.sh.
 */
public final class MediaReaderTest {
    private static int failed;

    private static void eq(String what, long got, long want) {
        if (got != want) {
            failed++;
            System.out.println("  FAIL " + what + ": got " + got + ", want " + want);
        }
    }

    private static String[] fields(String line) {
        java.util.Vector v = new java.util.Vector();
        int start = 0;
        for (int i = 0; i <= line.length(); i++) {
            if (i == line.length() || line.charAt(i) == '\t') {
                v.addElement(line.substring(start, i));
                start = i + 1;
            }
        }
        String[] out = new String[v.size()];
        v.copyInto(out);
        return out;
    }

    public static void main(String[] args) {
        String full = "Song\tArtist\tAlbum\t12\t200\t3\t4096\t1\t7\t2\t0\t1\t0";
        eq("full line: its own session", ShmemMediaReader.sessionOf(fields(full), 6L), 7L);
        eq("first line ever", ShmemMediaReader.sessionOf(fields(full), -1L), 7L);
        // lines that do not carry a readable session keep the one already known
        eq("cut before the session field", ShmemMediaReader.sessionOf(fields("Song\tArtist\tAlbum\t12\t200\t3\t4096\t1"), 7L), 7L);
        eq("cut inside the line", ShmemMediaReader.sessionOf(fields("Song\tArt"), 7L), 7L);
        eq("empty session field", ShmemMediaReader.sessionOf(fields("Song\tArtist\tAlbum\t12\t200\t3\t4096\t1\t"), 7L), 7L);
        eq("garbage in the session field", ShmemMediaReader.sessionOf(fields("S\tA\tB\t1\t2\t3\t4\t1\tx7"), 7L), 7L);
        eq("older shim, three fields, nothing known yet", ShmemMediaReader.sessionOf(fields("Song\tArtist\tAlbum"), -1L), -1L);
        if (!"12 Song\tArtist".equals(ShmemMediaReader.firstLine("12 Song\tArtist\nrest\n"))) { failed++; System.out.println("  FAIL first line"); }
        if (!"12 Song".equals(ShmemMediaReader.firstLine("12 Song"))) { failed++; System.out.println("  FAIL line without newline"); }
        if (!"12 Song".equals(ShmemMediaReader.firstLine("12 Song\r\n"))) { failed++; System.out.println("  FAIL CR LF"); }
        if (!"".equals(ShmemMediaReader.firstLine("\nx"))) { failed++; System.out.println("  FAIL empty first line"); }
        // where the covers go
        String ram = AndroidAutoTarget.COVER_RAM_DIR, icab = AndroidAutoTarget.ICAB_TMP;
        if (!ram.equals(AndroidAutoTarget.coverDirFor("/var/app/icab/tmp", true, false))) { failed++; System.out.println("  FAIL cache on the flash -> RAM"); }
        if (!icab.equals(AndroidAutoTarget.coverDirFor("/ramdisk/var/app/icab/tmp", true, false))) { failed++; System.out.println("  FAIL cache linked into /ramdisk -> cache"); }
        if (!icab.equals(AndroidAutoTarget.coverDirFor("/dev/shmem/icab", true, false))) { failed++; System.out.println("  FAIL cache in /dev/shmem -> cache"); }
        if (!ram.equals(AndroidAutoTarget.coverDirFor("/ramdiskX/tmp", true, false))) { failed++; System.out.println("  FAIL prefix must be a whole directory"); }
        if (!ram.equals(AndroidAutoTarget.coverDirFor(null, true, false))) { failed++; System.out.println("  FAIL unknown path -> RAM"); }
        if (!ram.equals(AndroidAutoTarget.coverDirFor("/ramdisk/x", false, false))) { failed++; System.out.println("  FAIL not writable -> RAM"); }
        if (!icab.equals(AndroidAutoTarget.coverDirFor("/var/app/icab/tmp", true, true))) { failed++; System.out.println("  FAIL cover-on-flash -> cache"); }
        if (!ram.equals(AndroidAutoTarget.coverDirFor("/var/app/icab/tmp", false, true))) { failed++; System.out.println("  FAIL forced but not writable -> RAM"); }
        System.out.println("media session field     " + (failed == 0 ? "ok" : "FAILED"));
        System.out.println(failed == 0 ? "all tests passed" : "TESTS FAILED");
        if (failed != 0) {
            System.exit(1);
        }
    }
}
