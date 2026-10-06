package com.editora.diff;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Rebuilding a blob's bytes from an edited view of its text: terminators and charset must survive. */
class BlobRewriteTest {

    private static final byte[] NO_BOM = new byte[0];
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static byte[] rewrite(byte[] original, Charset charset, String after) {
        return BlobRewrite.rewrite(original, charset, NO_BOM, new String(original, charset), after);
    }

    @Test
    void aCrlfBlobKeepsCrlfOnEveryLine() {
        byte[] original = "one\r\ntwo\r\nthree\r\n".getBytes(StandardCharsets.UTF_8);
        // The view composes the desired text with the blob's dominant separator; a view that had lost it
        // (LF only) must give the same bytes, because untouched lines keep their own terminators.
        assertEquals(
                "one\r\nTWO\r\nthree\r\n",
                new String(
                        rewrite(original, StandardCharsets.UTF_8, "one\r\nTWO\r\nthree\r\n"), StandardCharsets.UTF_8));
        assertEquals(
                "one\r\nTWO\r\nthree\r\n",
                new String(rewrite(original, StandardCharsets.UTF_8, "one\nTWO\nthree\n"), StandardCharsets.UTF_8));
    }

    @Test
    void mixedLineEndingsAreKeptPerLine() {
        byte[] original = "a\r\nb\nc\rd\r\n".getBytes(StandardCharsets.UTF_8);

        byte[] staged = rewrite(original, StandardCharsets.UTF_8, "a\r\nb\r\nNEW\r\nc\r\nd\r\n");

        // Untouched lines keep CRLF / LF / CR exactly; the inserted line takes the dominant terminator.
        assertEquals("a\r\nb\nNEW\r\nc\rd\r\n", new String(staged, StandardCharsets.UTF_8));
    }

    @Test
    void aLatin1BlobIsRewrittenInLatin1() {
        byte[] original = "café\nnaïve\n".getBytes(StandardCharsets.ISO_8859_1);

        byte[] staged = rewrite(original, StandardCharsets.ISO_8859_1, "café\nNAÏVE\n");

        assertArrayEquals("café\nNAÏVE\n".getBytes(StandardCharsets.ISO_8859_1), staged);
        // The 0xE9 of the untouched line is still one byte, not the two UTF-8 bytes C3 A9.
        assertEquals((byte) 0xE9, staged[3]);
    }

    @Test
    void aCharacterTheBlobCharsetCannotHoldIsRefusedNotReplaced() {
        byte[] original = "price\n".getBytes(StandardCharsets.ISO_8859_1);

        assertNull(rewrite(original, StandardCharsets.ISO_8859_1, "price €\n"));
    }

    @Test
    void aByteOrderMarkIsKept() {
        byte[] body = "one\ntwo\n".getBytes(StandardCharsets.UTF_8);
        byte[] original = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, original, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, original, UTF8_BOM.length, body.length);

        byte[] staged = BlobRewrite.rewrite(original, StandardCharsets.UTF_8, UTF8_BOM, "one\ntwo\n", "one\n2\n");

        assertEquals((byte) 0xEF, staged[0]);
        assertEquals(
                "one\n2\n",
                new String(staged, UTF8_BOM.length, staged.length - UTF8_BOM.length, StandardCharsets.UTF_8));
        // A BOM charset declared for a file that has no BOM does not gain one.
        assertArrayEquals(
                "one\n2\n".getBytes(StandardCharsets.UTF_8),
                BlobRewrite.rewrite(body, StandardCharsets.UTF_8, UTF8_BOM, "one\ntwo\n", "one\n2\n"));
    }

    @Test
    void endOfFileStateFollowsTheDesiredText() {
        byte[] unterminated = "a\nb".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "a\nb\n", new String(rewrite(unterminated, StandardCharsets.UTF_8, "a\nb\n"), StandardCharsets.UTF_8));
        assertEquals(
                "a\nb\nc",
                new String(rewrite(unterminated, StandardCharsets.UTF_8, "a\nb\nc"), StandardCharsets.UTF_8));
        assertEquals("a", new String(rewrite(unterminated, StandardCharsets.UTF_8, "a"), StandardCharsets.UTF_8));
        byte[] terminated = "a\r\nb\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "a\r\nb", new String(rewrite(terminated, StandardCharsets.UTF_8, "a\r\nb"), StandardCharsets.UTF_8));
        assertEquals("", new String(rewrite(terminated, StandardCharsets.UTF_8, ""), StandardCharsets.UTF_8));
    }

    @Test
    void aNewFileIsEncodedFromNothing() {
        assertArrayEquals(
                "new contents\n".getBytes(StandardCharsets.UTF_8),
                BlobRewrite.rewrite(new byte[0], StandardCharsets.UTF_8, NO_BOM, "", "new contents\n"));
    }

    @Test
    void aViewThatIsNotThisBlobIsRefused() {
        byte[] original = "one\ntwo\n".getBytes(StandardCharsets.UTF_8);

        assertNull(BlobRewrite.rewrite(original, StandardCharsets.UTF_8, NO_BOM, "one\nstale\n", "one\nTWO\n"));
    }

    @Test
    void bytesThatDoNotRoundTripThroughTheCharsetAreRefused() {
        // 0xE9 alone is malformed UTF-8: decoding yields U+FFFD, and re-encoding would rewrite a byte the
        // user never touched.
        byte[] original = {'c', 'a', 'f', (byte) 0xE9, '\n', 'x', '\n'};
        String decoded = new String(original, StandardCharsets.UTF_8);

        assertNull(BlobRewrite.rewrite(original, StandardCharsets.UTF_8, NO_BOM, decoded, decoded.replace("x", "y")));
    }

    @Test
    void theViewsTextIsComparedWithTheBlobLineByLineNotByTerminator() {
        // Diff sides hold the editor's \n-only text; the blob keeps its own terminators.
        byte[] original = "one\r\ntwo\r\n".getBytes(StandardCharsets.UTF_8);
        assertEquals(
                "one\r\nTWO\r\n",
                new String(
                        BlobRewrite.rewrite(original, StandardCharsets.UTF_8, NO_BOM, "one\ntwo\n", "one\nTWO\n"),
                        StandardCharsets.UTF_8));
        assertNull(
                BlobRewrite.rewrite(original, StandardCharsets.UTF_8, NO_BOM, "one\ntwo", "one\nTWO"),
                "a view that differs in its final newline is not this blob");
    }
}
