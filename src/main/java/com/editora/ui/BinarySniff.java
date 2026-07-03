package com.editora.ui;

/**
 * Pure heuristic deciding whether a byte sample looks like a <b>binary</b> file (so Editora opens it in the
 * read-only {@link HexViewerPane} instead of dumping its bytes as garbage text). Mirrors git's rule — a NUL
 * byte in the sample ⇒ binary — plus a control-character density fallback for binaries that have no early
 * NUL, and a **BOM guard** so BOM-marked UTF-16/UTF-8 text (which legitimately contains NUL bytes) is treated
 * as text. Bytes ≥ 0x80 are <b>not</b> counted as control chars, so UTF-8 / Latin-1 prose isn't misflagged.
 * No JavaFX / no IO, so it is unit-tested.
 */
public final class BinarySniff {

    /** How much of a file to sample when deciding (git uses the first 8000 bytes). */
    public static final int SAMPLE_BYTES = 8000;

    /** Fraction of control characters above which a BOM-less, NUL-less sample is judged binary. */
    private static final int CONTROL_PERCENT_THRESHOLD = 30;

    private BinarySniff() {}

    /** True when {@code sample} (a file's first bytes) looks binary. Empty/null ⇒ text (open normally). */
    public static boolean looksBinary(byte[] sample) {
        if (sample == null || sample.length == 0) {
            return false;
        }
        if (hasBom(sample)) {
            return false; // a Unicode BOM ⇒ text (UTF-16's NUL bytes must not read as binary)
        }
        int control = 0;
        for (byte value : sample) {
            int b = value & 0xFF;
            if (b == 0x00) {
                return true; // NUL ⇒ binary (git's rule)
            }
            if (b < 0x09 || (b > 0x0D && b < 0x20) || b == 0x7F) {
                control++; // a control char other than tab / LF / VT / FF / CR
            }
        }
        return control * 100L / sample.length > CONTROL_PERCENT_THRESHOLD;
    }

    /** True when {@code sample} starts with a UTF-8 / UTF-16 / UTF-32 byte-order mark. */
    private static boolean hasBom(byte[] s) {
        int n = s.length;
        if (n >= 3 && (s[0] & 0xFF) == 0xEF && (s[1] & 0xFF) == 0xBB && (s[2] & 0xFF) == 0xBF) {
            return true; // UTF-8
        }
        if (n >= 2 && (s[0] & 0xFF) == 0xFE && (s[1] & 0xFF) == 0xFF) {
            return true; // UTF-16 BE
        }
        // UTF-16 LE (FF FE …) — also the prefix of a UTF-32 LE BOM (FF FE 00 00); either way it's text.
        return n >= 2 && (s[0] & 0xFF) == 0xFF && (s[1] & 0xFF) == 0xFE;
    }
}
