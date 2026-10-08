package com.editora.recovery;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32C;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A recovery record that is not what this build wrote — a header of the wrong shape, missing fields, text in
 * an encoding it does not know, a UTF-16 body cut in half — is rejected with a reason, never decoded into
 * something offered as the user's document.
 */
class RecoveryCodecDamageTest {

    /** A record file with the given metadata line and body, with whatever length and CRC the test claims. */
    private static byte[] file(String header, byte[] body) {
        byte[] magic = RecoveryCodec.MAGIC.getBytes(StandardCharsets.US_ASCII);
        byte[] meta = header.getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[magic.length + meta.length + 1 + body.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        System.arraycopy(meta, 0, out, magic.length, meta.length);
        out[magic.length + meta.length] = '\n';
        System.arraycopy(body, 0, out, magic.length + meta.length + 1, body.length);
        return out;
    }

    private static long crc(byte[] body) {
        CRC32C crc = new CRC32C();
        crc.update(body, 0, body.length);
        return crc.getValue();
    }

    private static String header(String encoding, byte[] body) {
        return "{\"bufferId\":\"b-1\",\"textEncoding\":" + encoding + ",\"textBytes\":" + body.length + ",\"textCrc\":"
                + crc(body) + "}";
    }

    private static String refusal(byte[] bytes) {
        return assertThrows(IOException.class, () -> RecoveryCodec.decode(bytes, true))
                .getMessage();
    }

    @Test
    void aHeaderThatIsNotAnObjectIsRejected() {
        assertEquals("recovery record header is not an object", refusal(file("[1,2,3]", new byte[0])));
        assertEquals("recovery record header is not an object", refusal(file("\"just a string\"", new byte[0])));
    }

    @Test
    void aHeaderMissingItsIdLengthOrChecksumIsRejected() {
        byte[] body = "text".getBytes(StandardCharsets.UTF_8);
        String incomplete = "recovery record header is incomplete";

        assertEquals(incomplete, refusal(file("{\"textBytes\":4,\"textCrc\":" + crc(body) + "}", body)));
        assertEquals(incomplete, refusal(file("{\"bufferId\":\"b-1\",\"textCrc\":" + crc(body) + "}", body)));
        assertEquals(incomplete, refusal(file("{\"bufferId\":\"b-1\",\"textBytes\":4}", body)));
        assertEquals(
                incomplete, refusal(file("{\"bufferId\":null,\"textBytes\":4,\"textCrc\":" + crc(body) + "}", body)));
    }

    @Test
    void textInAnUnknownOrMissingEncodingIsRejectedEvenWhenLengthAndChecksumMatch() {
        byte[] body = "text".getBytes(StandardCharsets.UTF_8);

        assertEquals(
                "recovery record text encoding is unknown: latin-9", refusal(file(header("\"latin-9\"", body), body)));
        assertEquals("recovery record text encoding is unknown: null", refusal(file(header("null", body), body)));
    }

    @Test
    void utf16TextWithHalfACodeUnitIsRejected() {
        byte[] odd = {0, 'a', 0};

        assertEquals(
                "recovery record text has half a UTF-16 unit", refusal(file(header("\"utf-16-units\"", odd), odd)));
    }

    @Test
    void aListingChecksTheTextButLeavesItOut() throws IOException {
        byte[] utf8 = "héllo".getBytes(StandardCharsets.UTF_8);
        RecoveryRecord listed = RecoveryCodec.decode(file(header("\"utf-8\"", utf8), utf8), false);
        assertEquals("b-1", listed.bufferId());
        assertNull(listed.text());
        assertTrue(listed.untitled(), "no path in the header: an untitled buffer");
        assertEquals(
                "héllo",
                RecoveryCodec.decode(file(header("\"utf-8\"", utf8), utf8), true)
                        .text());

        byte[] units = {0, 'h', 0, 'i'};
        assertNull(RecoveryCodec.decode(file(header("\"utf-16-units\"", units), units), false)
                .text());
        assertEquals(
                "hi",
                RecoveryCodec.decode(file(header("\"utf-16-units\"", units), units), true)
                        .text());
    }

    @Test
    void aRecordWithNoTextEncodesAsAnEmptyDocumentAndDroppingTheTextKeepsEverythingElse() throws IOException {
        RecoveryRecord bare = new RecoveryRecord(
                "b-2", "/tmp/x.txt", "x.txt", null, "UTF-8", true, "LF", 10, 20, "abc", 30, 4, "w1", null);
        assertSame(bare, bare.withoutText(), "nothing to drop");

        RecoveryRecord decoded = RecoveryCodec.decode(RecoveryCodec.encode(bare), true);
        assertEquals("", decoded.text());
        assertEquals("/tmp/x.txt", decoded.path());

        RecoveryRecord full = new RecoveryRecord(
                "b-2", "/tmp/x.txt", "x.txt", "x", "UTF-8", false, "CRLF", 10, 20, "abc", 30, 4, "w1", "the text");
        RecoveryRecord listed = full.withoutText();
        assertNull(listed.text());
        assertEquals(
                new RecoveryRecord(
                        "b-2", "/tmp/x.txt", "x.txt", "x", "UTF-8", false, "CRLF", 10, 20, "abc", 30, 4, "w1", null),
                listed);
    }
}
