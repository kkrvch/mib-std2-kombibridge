package de.aatokombi;

/**
 * The end marker of the IPC files the shim (patched libgal) writes for the jar: /dev/shmem/aa_nav
 * and /dev/shmem/aa_media.
 *
 * The shim rewrites each file whole (O_TRUNC, then one write), which is not atomic: a poll that
 * lands in between reads an empty or a cut file, and a cut line can still parse as valid data (a
 * shorter road name, a smaller distance). So the shim ends every file with the line "end\n", and a
 * reader takes a file only when it ends with that line.
 *
 * A shim built before the marker existed never writes it, so this jar shows no nav or media data
 * with it: the jar and libgal must be installed together.
 *
 * Java 1.3: no generics, no varargs. Must never throw into the HMI.
 */
public final class IpcFile {

    private IpcFile() {
    }

    /** The last line of every complete IPC file. */
    private static final byte[] END = { 'e', 'n', 'd', '\n' };

    /**
     * The length of the data in {@code buf[0..n)} without the end line, or -1 when the bytes do not
     * end with it (a torn read, or a file from an older shim). The end line must start a line: it
     * is either the whole file or preceded by '\n'.
     */
    public static int body(byte[] buf, int n) {
        int m = END.length;
        if (buf == null || n < m || n > buf.length) {
            return -1;
        }
        for (int i = 0; i < m; i++) {
            if (buf[n - m + i] != END[i]) {
                return -1;
            }
        }
        if (n > m && buf[n - m - 1] != '\n') {
            return -1;                     // "...xend\n": a line that merely ends in "end"
        }
        return n - m;
    }
}
