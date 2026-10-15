package com.editora.http;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPOutputStream;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Response bodies decoded by their Content-Encoding. */
class ContentDecodingTest {

    private static final byte[] TEXT = "hello, compressed world".getBytes(StandardCharsets.UTF_8);

    private static byte[] gzip(byte[] b) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
            gz.write(b);
        }
        return bo.toByteArray();
    }

    private static byte[] deflate(byte[] b, boolean raw) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        try (DeflaterOutputStream d = new DeflaterOutputStream(bo, new Deflater(Deflater.DEFAULT_COMPRESSION, raw))) {
            d.write(b);
        }
        return bo.toByteArray();
    }

    private static byte[] read(byte[] body, String encoding) throws IOException {
        return ContentDecoding.decode(new ByteArrayInputStream(body), encoding).stream()
                .readAllBytes();
    }

    @Test
    void gzipDeflateAndStackedEncodingsAreUndone() throws IOException {
        assertArrayEquals(TEXT, read(gzip(TEXT), "gzip"));
        assertArrayEquals(TEXT, read(gzip(TEXT), "X-GZIP"));
        assertArrayEquals(TEXT, read(deflate(TEXT, false), "deflate"));
        assertArrayEquals(TEXT, read(deflate(TEXT, true), "deflate"), "a raw deflate stream too");
        assertArrayEquals(
                TEXT, read(gzip(deflate(TEXT, false)), "deflate, gzip"), "applied in order, undone in reverse");
        assertArrayEquals(TEXT, read(TEXT, "identity"));
        assertArrayEquals(TEXT, read(TEXT, null));
    }

    @Test
    void anEmptyBodyDecodesToNothing() throws IOException {
        assertArrayEquals(new byte[0], read(new byte[0], "gzip"));
        assertArrayEquals(new byte[0], read(new byte[0], "deflate"));
    }

    @Test
    void anUnsupportedEncodingIsPassedThroughAndNamed() {
        InputStream in = new ByteArrayInputStream(TEXT);
        ContentDecoding.Decoded d = ContentDecoding.decode(in, "gzip, br");
        assertSame(in, d.stream(), "nothing is half-decoded");
        assertEquals("gzip, br", d.undecoded());
        assertNull(ContentDecoding.decode(in, "gzip").undecoded());
    }

    @Test
    void aCorruptBodyFailsNamingTheEncoding() {
        ContentDecoding.DecodeException e =
                assertThrows(ContentDecoding.DecodeException.class, () -> read("not gzip at all".getBytes(), "gzip"));
        assertEquals("gzip", e.encoding());
    }

    @Test
    void decodingDoesNotReadBeforeTheFirstRead() throws IOException {
        int[] reads = {0};
        InputStream counting = new InputStream() {
            @Override
            public int read() {
                reads[0]++;
                return -1;
            }
        };
        ContentDecoding.Decoded d = ContentDecoding.decode(counting, "deflate");
        assertEquals(0, reads[0], "a stalled body must not block before the deadline is armed");
        d.stream().read();
        assertEquals(1, reads[0]);
    }
}
