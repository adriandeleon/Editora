package com.editora.ui;

/**
 * Pure formatter for the classic hex-dump view — {@code OFFSET  HH HH … HH  |ASCII|}, 16 bytes per row —
 * shown by the read-only {@link HexViewerPane}. The offset is an 8-digit uppercase hex column, the middle
 * is the bytes in hex (a gap after the 8th so it reads in two groups of eight, with missing trailing bytes
 * padded so the ASCII column stays aligned), and the right is the printable ASCII (bytes 0x20–0x7E, else
 * {@code .}) fenced by {@code |}. No JavaFX / no IO, so it is unit-tested.
 *
 * <p>Digits come from a lookup table: a 1 MiB dump is 65 536 rows and over a million bytes, and one
 * {@code String.format} call per byte made building it the slowest part of opening a binary file.
 */
public final class HexDump {

    /** Bytes shown per row. */
    public static final int BYTES_PER_ROW = 16;

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private HexDump() {}

    /** Formats all of {@code data} (whose first byte is at {@code baseOffset} in the file) as hex-dump rows. */
    public static String format(byte[] data, long baseOffset) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(rowCount(data.length) * 78);
        for (int off = 0; off < data.length; off += BYTES_PER_ROW) {
            if (off > 0) {
                sb.append('\n');
            }
            appendRow(sb, data, off, Math.min(BYTES_PER_ROW, data.length - off), baseOffset + off);
        }
        return sb.toString();
    }

    /** Number of rows {@code byteCount} bytes render to. */
    public static int rowCount(long byteCount) {
        return (int) ((byteCount + BYTES_PER_ROW - 1) / BYTES_PER_ROW);
    }

    private static void appendRow(StringBuilder sb, byte[] data, int start, int len, long offset) {
        appendOffset(sb, offset);
        sb.append(' ').append(' ');
        for (int i = 0; i < BYTES_PER_ROW; i++) {
            if (i == BYTES_PER_ROW / 2) {
                sb.append(' '); // extra gap between the two 8-byte groups
            }
            if (i < len) {
                int b = data[start + i] & 0xFF;
                sb.append(HEX[b >>> 4]).append(HEX[b & 0xF]).append(' ');
            } else {
                sb.append("   "); // pad a missing trailing byte so the ASCII column stays aligned
            }
        }
        sb.append(" |");
        for (int i = 0; i < len; i++) {
            int b = data[start + i] & 0xFF;
            sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
        }
        sb.append('|');
    }

    /** The offset as uppercase hex, zero-padded to at least 8 digits (the {@code %08X} layout). */
    private static void appendOffset(StringBuilder sb, long offset) {
        int digits = 8;
        while (digits < 16 && (offset >>> (digits * 4)) != 0) {
            digits++;
        }
        for (int shift = (digits - 1) * 4; shift >= 0; shift -= 4) {
            sb.append(HEX[(int) ((offset >>> shift) & 0xF)]);
        }
    }
}
