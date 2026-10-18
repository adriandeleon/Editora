package com.editora.deploy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TarWriterTest {

    @Test
    void aPaxRecordCountsItsOwnLength() {
        assertEquals("12 path=abc\n", TarWriter.paxRecord("path", "abc"));
        String r = TarWriter.paxRecord("path", "x".repeat(90)); // 98 + 2 digits → 100: one more digit
        assertEquals(r.getBytes(StandardCharsets.UTF_8).length, Integer.parseInt(r.substring(0, r.indexOf(' '))));
    }

    @Test
    void blocksAreWholeAndTheHeaderChecksumIsValid() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String sha;
        try (TarWriter tar = new TarWriter(out)) {
            sha = tar.addFile("a.txt", 3, 1_700_000_000L, false, new ByteArrayInputStream("abc".getBytes()));
        }
        byte[] b = out.toByteArray();
        assertEquals(512 * 4, b.length, "header + one data block + two end blocks");
        long sum = 0;
        for (int i = 0; i < 512; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (b[i] & 0xff);
        }
        assertEquals(sum, Long.parseLong(new String(b, 148, 6, StandardCharsets.US_ASCII), 8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", sha);
    }
}
