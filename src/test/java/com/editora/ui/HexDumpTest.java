package com.editora.ui;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HexDumpTest {

    @Test
    void emptyInputIsEmpty() {
        assertEquals("", HexDump.format(new byte[0], 0));
    }

    @Test
    void singleRowLayout() {
        byte[] data = "Hello".getBytes(StandardCharsets.US_ASCII);
        String row = HexDump.format(data, 0);
        assertTrue(row.startsWith("00000000  48 65 6C 6C 6F "), row); // offset + hex bytes
        assertTrue(row.endsWith(" |Hello|"), row); // ASCII column, padded, fenced
    }

    @Test
    void baseOffsetShowsInTheOffsetColumn() {
        assertTrue(HexDump.format(new byte[] {0x41}, 0x1000).startsWith("00001000  41 "));
    }

    @Test
    void nonPrintableBytesRenderAsDotsInAscii() {
        byte[] data = {0x00, (byte) 0xFF, 0x09, 0x41}; // NUL, 0xFF, tab, 'A'
        String row = HexDump.format(data, 0);
        assertTrue(row.endsWith("|...A|"), row); // only 'A' is printable
        assertTrue(row.contains("00 FF 09 41 "), row);
    }

    @Test
    void multipleRowsSplitAt16Bytes() {
        byte[] data = new byte[20];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) ('a' + (i % 26));
        }
        String[] rows = HexDump.format(data, 0).split("\n");
        assertEquals(2, rows.length);
        assertTrue(rows[0].startsWith("00000000  "));
        assertTrue(rows[1].startsWith("00000010  ")); // second row offset = 16
    }

    @Test
    void rowCountRoundsUp() {
        assertEquals(0, HexDump.rowCount(0));
        assertEquals(1, HexDump.rowCount(1));
        assertEquals(1, HexDump.rowCount(16));
        assertEquals(2, HexDump.rowCount(17));
    }

    @Test
    void outputIsIdenticalToTheFormatterItReplaced() {
        // The table-driven formatter must produce exactly what the String.format one did, for every byte
        // value, a partial last row, and offsets on both sides of the 8-digit column.
        byte[] data = new byte[256 + 7];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        for (long base : new long[] {0L, 0x1000L, 0xFFFFFFF0L, 0x1_2345_6789L}) {
            assertEquals(reference(data, base), HexDump.format(data, base), "base offset " + base);
        }
    }

    /** The previous implementation, kept as the oracle. */
    private static String reference(byte[] data, long baseOffset) {
        StringBuilder sb = new StringBuilder();
        for (int off = 0; off < data.length; off += HexDump.BYTES_PER_ROW) {
            if (off > 0) {
                sb.append('\n');
            }
            int len = Math.min(HexDump.BYTES_PER_ROW, data.length - off);
            sb.append(String.format("%08X  ", baseOffset + off));
            for (int i = 0; i < HexDump.BYTES_PER_ROW; i++) {
                if (i == HexDump.BYTES_PER_ROW / 2) {
                    sb.append(' ');
                }
                sb.append(i < len ? String.format("%02X ", data[off + i] & 0xFF) : "   ");
            }
            sb.append(" |");
            for (int i = 0; i < len; i++) {
                int b = data[off + i] & 0xFF;
                sb.append(b >= 0x20 && b < 0x7F ? (char) b : '.');
            }
            sb.append('|');
        }
        return sb.toString();
    }
}
