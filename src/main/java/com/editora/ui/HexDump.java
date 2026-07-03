package com.editora.ui;

/**
 * Pure formatter for the classic hex-dump view — {@code OFFSET  HH HH … HH  |ASCII|}, 16 bytes per row —
 * shown by the read-only {@link HexViewerPane}. The offset is an 8-digit uppercase hex column, the middle
 * is the bytes in hex (a gap after the 8th so it reads in two groups of eight, with missing trailing bytes
 * padded so the ASCII column stays aligned), and the right is the printable ASCII (bytes 0x20–0x7E, else
 * {@code .}) fenced by {@code |}. No JavaFX / no IO, so it is unit-tested.
 */
public final class HexDump {

    /** Bytes shown per row. */
    public static final int BYTES_PER_ROW = 16;

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
        sb.append(String.format("%08X  ", offset));
        for (int i = 0; i < BYTES_PER_ROW; i++) {
            if (i == BYTES_PER_ROW / 2) {
                sb.append(' '); // extra gap between the two 8-byte groups
            }
            if (i < len) {
                sb.append(String.format("%02X ", data[start + i] & 0xFF));
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
}
