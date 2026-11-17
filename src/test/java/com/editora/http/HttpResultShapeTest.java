package com.editora.http;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link HttpResult}: which media types have no text form, how a declared charset is read, whether a status counts
 * as ok, and what a result built from missing parts holds.
 */
class HttpResultShapeTest {

    private static final byte[] TEXT = "plain ascii, no NUL".getBytes(StandardCharsets.US_ASCII);

    @ParameterizedTest
    @ValueSource(
            strings = {
                "image/png",
                "audio/ogg",
                "video/mp4",
                "font/woff2",
                "application/octet-stream",
                "application/zip",
                "application/gzip",
                "application/x-gzip",
                "application/x-tar",
                "application/x-7z-compressed",
                "application/pdf",
                "application/protobuf",
                "application/x-protobuf",
                "application/grpc+proto",
                "application/msgpack",
                "application/cbor",
                "application/wasm",
                "application/vnd.ms-excel"
            })
    void aMediaTypeThatIsNeverTextIsBinaryWithoutANulByte(String type) {
        assertTrue(HttpResult.looksBinary(TEXT, type), type);
        assertTrue(HttpResult.looksBinary(TEXT, type.toUpperCase(java.util.Locale.ROOT)), "case-blind: " + type);
    }

    @ParameterizedTest
    @ValueSource(strings = {"image/svg", "application/javascript", "application/x-ndjson", "application/x-www-form"})
    void aTextualOrUnknownTypeIsTextUnlessItHasANulByte(String type) {
        assertFalse(HttpResult.looksBinary(TEXT, type), type);
        assertTrue(HttpResult.looksBinary(new byte[] {'a', 0, 'b'}, "application/x-www-form"), "a NUL: binary");
    }

    @Test
    void theDeclaredCharsetIsReadQuotedOrFollowedByMoreParameters() {
        assertEquals(
                StandardCharsets.ISO_8859_1,
                HttpResult.charsetOf("text/plain; charset=ISO-8859-1; format=flowed"),
                "up to the next parameter");
        assertEquals(StandardCharsets.UTF_16LE, HttpResult.charsetOf("text/plain; charset=\"UTF-16LE\""), "quoted");
        assertEquals(StandardCharsets.UTF_8, HttpResult.charsetOf("text/plain; charset="), "empty: the default");
        assertEquals(StandardCharsets.UTF_8, HttpResult.charsetOf("text/plain; charset=no-such-set"), "unknown");
        assertEquals(StandardCharsets.UTF_8, HttpResult.charsetOf(null), "no content type at all");
        assertEquals(StandardCharsets.UTF_8, HttpResult.charsetOf(null, TEXT));
        assertEquals(StandardCharsets.UTF_8, HttpResult.charsetOf("text/html", null), "no bytes to sniff");
        assertFalse(
                HttpResult.looksBinary(new byte[] {'a', 0, 'b'}, "application/octet-stream; charset=utf-16"),
                "a declared charset wins over a never-text type and a NUL");
    }

    @Test
    void okIsATwoOrThreeHundredStatusWithoutAnError() {
        assertTrue(HttpFixtures.bytes(200, List.of(), TEXT, "text/plain", 1, List.of(), false)
                .ok());
        assertTrue(HttpFixtures.bytes(399, List.of(), TEXT, "text/plain", 1, List.of(), false)
                .ok());
        assertFalse(HttpFixtures.bytes(199, List.of(), TEXT, "text/plain", 1, List.of(), false)
                .ok());
        assertFalse(HttpFixtures.bytes(400, List.of(), TEXT, "text/plain", 1, List.of(), false)
                .ok());
        HttpResult failed = HttpResult.failure("refused", List.of());
        assertFalse(failed.ok());
        assertTrue(failed.failed());
        assertEquals("refused", failed.shownError());
    }

    @Test
    void aResultBuiltFromMissingPartsHoldsEmptyOnes() {
        HttpResult none = HttpResult.ofBytes(204, List.of(), null, "text/plain", 1, List.of(), false, List.of(), null);
        assertArrayEquals(new byte[0], none.rawBody(), "no body: empty bytes");
        assertEquals("", none.body());
        assertEquals(0, none.sizeBytes());

        HttpResult bare =
                new HttpResult(200, List.of(), "", "image/png", 0, 0, null, null, null, false, null, null, null);
        assertEquals(List.of(), bare.written(), "no redirects written");
        assertSame(HttpTests.NONE, bare.tests(), "no checks ran");
        assertFalse(bare.binary(), "no bytes: nothing to call binary");

        HttpResult sameWarnings = bare.withTests(HttpTests.NONE, List.of());
        assertEquals(null, sameWarnings.warnings(), "no more warnings: its own kept as they were");
        assertEquals(
                List.of("late"), bare.withTests(HttpTests.NONE, List.of("late")).warnings());
        assertEquals(null, bare.withTests(HttpTests.NONE, null).warnings());
        assertEquals(
                List.of("own", "late"),
                bare.withWarnings(List.of("own"))
                        .withTests(HttpTests.NONE, List.of("late"))
                        .warnings());
    }
}
