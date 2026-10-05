package com.editora.editorconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorConfigCharsetTest {

    @Test
    void detectByBom() {
        assertEquals("utf-8-bom", EditorConfigCharset.detectByBom(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}));
        assertEquals("utf-16le", EditorConfigCharset.detectByBom(new byte[] {(byte) 0xFF, (byte) 0xFE}));
        assertEquals("utf-16be", EditorConfigCharset.detectByBom(new byte[] {(byte) 0xFE, (byte) 0xFF}));
        assertNull(EditorConfigCharset.detectByBom(new byte[] {'h', 'i'}));
        assertNull(EditorConfigCharset.detectByBom(new byte[0]));
    }

    @Test
    void roundTripUtf8Bom() {
        byte[] bytes = EditorConfigCharset.encode("héllo", "utf-8-bom");
        // Starts with the UTF-8 BOM…
        assertArrayEquals(
                new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF}, new byte[] {bytes[0], bytes[1], bytes[2]});
        assertEquals("utf-8-bom", EditorConfigCharset.detectByBom(bytes));
        // …and decodes back without the BOM character.
        assertEquals("héllo", EditorConfigCharset.decode(bytes, "utf-8-bom"));
    }

    @Test
    void roundTripUtf16AndLatin1() {
        for (String cs : new String[] {"utf-16le", "utf-16be", "latin1", "utf-8"}) {
            byte[] bytes = EditorConfigCharset.encode("café", cs);
            assertEquals("café", EditorConfigCharset.decode(bytes, cs), cs);
        }
    }

    @Test
    void utf16EncodeHasBomAndDecodeStripsIt() {
        byte[] le = EditorConfigCharset.encode("A", "utf-16le");
        assertEquals("utf-16le", EditorConfigCharset.detectByBom(le));
        assertEquals("A", EditorConfigCharset.decode(le, "utf-16le")); // no leading U+FEFF
    }

    @Test
    void decodeKeepsContentWhenBomCharsetButNoBomOnDisk() {
        // .editorconfig may declare charset=utf-8-bom for a file that doesn't actually have a BOM yet.
        // decode must NOT strip the first 3 bytes in that case (that silently truncated real content).
        byte[] noBom = "héllo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals("héllo", EditorConfigCharset.decode(noBom, "utf-8-bom"));
        // utf-16 declared but bytes are bare (no FF FE / FE FF) — first char must survive.
        byte[] le = "A".getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        assertEquals("A", EditorConfigCharset.decode(le, "utf-16le"));
    }

    @Test
    void resolveNameBomWinsOverEditorConfig() {
        // A leading BOM overrides whatever .editorconfig says (the file is self-describing).
        byte[] bom16le = {(byte) 0xFF, (byte) 0xFE, 'A', 0};
        assertEquals("utf-16le", EditorConfigCharset.resolveName(bom16le, "latin1"));
        byte[] bom8 = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'x'};
        assertEquals("utf-8-bom", EditorConfigCharset.resolveName(bom8, "latin1"));
    }

    @Test
    void resolveNameFallsBackToEditorConfigThenUtf8() {
        byte[] plain = {(byte) 0xE9}; // a bare latin1 'é' — no BOM, invalid UTF-8 start
        assertEquals("latin1", EditorConfigCharset.resolveName(plain, "latin1"));
        assertEquals("utf-8", EditorConfigCharset.resolveName(plain, null)); // EditorConfig off / no rule
    }

    @Test
    void resolveNameThenDecodeRecoversLatin1WhereUtf8Mojibakes() {
        // The #435 core: a latin1-committed blob decoded as UTF-8 is mojibake; via resolveName+decode it's real.
        byte[] bytes = "café".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String correct = EditorConfigCharset.decode(bytes, EditorConfigCharset.resolveName(bytes, "latin1"));
        assertEquals("café", correct);
        String forcedUtf8 = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(forcedUtf8.contains("café"), "UTF-8 force-decode should mojibake: " + forcedUtf8);
    }

    @Test
    void displayNames() {
        assertEquals("UTF-8", EditorConfigCharset.displayName("utf-8"));
        assertEquals("UTF-8 BOM", EditorConfigCharset.displayName("utf-8-bom"));
        assertEquals("ISO-8859-1", EditorConfigCharset.displayName("latin1"));
        assertEquals("UTF-16 LE", EditorConfigCharset.displayName("utf-16le"));
        assertEquals("UTF-8", EditorConfigCharset.displayName(null));
    }

    @Test
    void latin1CannotEncodeWhatTheUserActuallyTypes() {
        // String.getBytes(Charset) replaces these with '?' — silently, and the editor keeps showing the real
        // character until the file is reopened. The save path checks this and falls back to UTF-8 instead.
        assertFalse(EditorConfigCharset.canEncode("an em dash — here", "latin1"));
        assertFalse(EditorConfigCharset.canEncode("curly \u201cquotes\u201d", "latin1"));
        assertFalse(EditorConfigCharset.canEncode("\u20ac 100", "latin1"), "the euro sign is not in ISO-8859-1");
        assertFalse(EditorConfigCharset.canEncode("emoji \ud83d\ude80", "latin1"));
        assertFalse(EditorConfigCharset.canEncode("\u65e5\u672c\u8a9e", "latin1"));
    }

    @Test
    void latin1EncodesWhatItCan() {
        assertTrue(EditorConfigCharset.canEncode("plain ascii", "latin1"));
        assertTrue(EditorConfigCharset.canEncode("caf\u00e9 na\u00efve", "latin1"), "accented Latin-1 is fine");
    }

    @Test
    void utf8EncodesEverything() {
        assertTrue(EditorConfigCharset.canEncode("— \u201c\u201d \u20ac \ud83d\ude80 \u65e5\u672c\u8a9e", "utf-8"));
    }

    // --- lossless decode -----------------------------------------------------------------------------

    private static byte[] hex(String hex) {
        return java.util.HexFormat.of().parseHex(hex);
    }

    private static void assertRoundTrips(byte[] bytes, String editorConfigCharset) {
        EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(bytes, editorConfigCharset);
        assertArrayEquals(
                bytes,
                EditorConfigCharset.encode(decoded.text(), decoded.charset()),
                "encoding the decoded text with the reported charset must reproduce the file");
    }

    @Test
    void validUtf8IsDecodedAsDeclared() {
        byte[] bytes = "año café — 日本語\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(bytes, null);
        assertEquals("año café — 日本語\n", decoded.text());
        assertEquals("utf-8", decoded.charset());
        assertFalse(decoded.assumed());
        assertRoundTrips(bytes, null);
    }

    @Test
    void bytesThatAreNotUtf8FallBackToALosslessSingleByteDecode() {
        byte[] latin1 = hex("61f16f20636166e90a"); // "año café\n" in ISO-8859-1
        EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(latin1, null);
        assertEquals("año café\n", decoded.text());
        assertEquals("windows-1252", decoded.charset(), "no byte rules windows-1252 out, so it is preferred");
        assertEquals("utf-8", decoded.declared());
        assertTrue(decoded.assumed());
        assertFalse(decoded.text().contains("\uFFFD"), "nothing may be replaced: a replacement is saved back");
        assertRoundTrips(latin1, null);
    }

    @Test
    void everyByteValueSurvivesTheFallback() {
        // Shift-JIS lead bytes include 0x81 and 0x8F, which windows-1252 leaves undefined — the reason the
        // fallback is ISO-8859-1.
        byte[] all = new byte[256];
        for (int i = 0; i < all.length; i++) {
            all[i] = (byte) i;
        }
        assertRoundTrips(all, null);
        assertEquals("latin1", EditorConfigCharset.decodeLossless(all, null).charset());
        byte[] shiftJis = hex("93fa967b8cea81408f430d0a");
        assertRoundTrips(shiftJis, null);
        assertEquals(
                "latin1", EditorConfigCharset.decodeLossless(shiftJis, null).charset());
    }

    @Test
    void windows1252PunctuationIsShownAsPunctuationNotControlCharacters() {
        byte[] bytes = hex("93689420800a"); // “h” €\n in windows-1252
        EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(bytes, null);
        assertEquals("\u201Ch\u201D \u20AC\n", decoded.text());
        assertEquals("windows-1252", decoded.charset());
        assertTrue(decoded.assumed());
        assertEquals("Windows-1252", EditorConfigCharset.displayName(decoded.charset()));
        assertRoundTrips(bytes, null);
    }

    @Test
    void everyByteWindows1252DefinesRoundTripsThroughIt() {
        byte[] defined = new byte[251];
        int n = 0;
        for (int i = 0; i < 256; i++) {
            if (i != 0x81 && i != 0x8D && i != 0x8F && i != 0x90 && i != 0x9D) {
                defined[n++] = (byte) i;
            }
        }
        assertEquals(251, n);
        assertTrue(EditorConfigCharset.definedInWindows1252(defined));
        assertEquals(
                "windows-1252",
                EditorConfigCharset.decodeLossless(defined, null).charset());
        assertRoundTrips(defined, null);
        for (int undefined : new int[] {0x81, 0x8D, 0x8F, 0x90, 0x9D}) {
            byte[] one = {'a', (byte) undefined, (byte) 0x93};
            assertFalse(EditorConfigCharset.definedInWindows1252(one));
            assertEquals("latin1", EditorConfigCharset.decodeLossless(one, null).charset());
            assertRoundTrips(one, null);
        }
    }

    @Test
    void aLiteralReplacementCharacterInValidUtf8IsNotMistakenForDamage() {
        byte[] bytes = hex("61efbfbd620a");
        EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(bytes, null);
        assertEquals("a\uFFFDb\n", decoded.text());
        assertFalse(decoded.assumed());
        assertRoundTrips(bytes, null);
    }

    @Test
    void anEditorConfigCharsetThatCannotDecodeTheFileIsNotTrusted() {
        byte[] latin1 = hex("61f16f0a");
        EditorConfigCharset.Decoded declaredUtf8 = EditorConfigCharset.decodeLossless(latin1, "utf-8");
        assertTrue(declaredUtf8.assumed());
        assertEquals("latin1", declaredUtf8.charset(), "the windows-1252 guess is only for an undeclared charset");
        assertRoundTrips(latin1, "utf-8");

        EditorConfigCharset.Decoded declaredLatin1 = EditorConfigCharset.decodeLossless(latin1, "latin1");
        assertFalse(declaredLatin1.assumed(), "the declared charset decoded it, so nothing was assumed");
        assertEquals("año\n", declaredLatin1.text());

        byte[] oddUtf16 = hex("6100f1"); // a dangling byte: not UTF-16
        assertTrue(EditorConfigCharset.decodeLossless(oddUtf16, "utf-16le").assumed());
        assertRoundTrips(oddUtf16, "utf-16le");
    }

    @Test
    void aBomFollowedByInvalidBytesKeepsTheBomBytesToo() {
        byte[] bytes = hex("efbbbf61f16f0a"); // UTF-8 BOM, then Latin-1
        EditorConfigCharset.Decoded decoded = EditorConfigCharset.decodeLossless(bytes, null);
        assertTrue(decoded.assumed());
        assertEquals("utf-8-bom", decoded.declared());
        assertRoundTrips(bytes, null);
    }

    @Test
    void strictDecodeReportsMalformedInputInsteadOfReplacingIt() {
        org.junit.jupiter.api.Assertions.assertThrows(
                java.nio.charset.CharacterCodingException.class,
                () -> EditorConfigCharset.decodeStrict(hex("61f16f"), "utf-8"));
    }

    @Test
    void bomlessUtf16IsRecognisedOnlyWhenDeclaredAndWellFormed() {
        byte[] hi = {'h', 0, 'i', 0, '\n', 0};
        assertTrue(EditorConfigCharset.isBomlessUtf16(hi, EditorConfigCharset.UTF_16LE));
        assertFalse(EditorConfigCharset.isBomlessUtf16(hi, EditorConfigCharset.UTF_8), "not declared UTF-16");
        assertFalse(EditorConfigCharset.isBomlessUtf16(hi, null));
        assertFalse(
                EditorConfigCharset.isBomlessUtf16(new byte[] {'h', 0, 'i'}, EditorConfigCharset.UTF_16LE),
                "an odd length is not UTF-16");
        assertFalse(
                EditorConfigCharset.isBomlessUtf16(new byte[] {'h', 0, 0, 0}, EditorConfigCharset.UTF_16LE),
                "a NUL character is binary data in any charset");
        assertFalse(
                EditorConfigCharset.isBomlessUtf16(new byte[] {0, (byte) 0xD8, 'h', 0}, EditorConfigCharset.UTF_16LE),
                "an unpaired surrogate is not text");
        assertFalse(
                EditorConfigCharset.isBomlessUtf16(
                        new byte[] {(byte) 0xFF, (byte) 0xFE, 'h', 0}, EditorConfigCharset.UTF_16LE),
                "a file with a BOM is not BOM-less");
    }

    @Test
    void aBomlessFileIsEncodedBackWithoutABom() {
        byte[] hi = {'h', 0, 'i', 0, '\n', 0};
        String text = EditorConfigCharset.decode(hi, EditorConfigCharset.UTF_16LE);
        assertArrayEquals(hi, EditorConfigCharset.encode(text, EditorConfigCharset.UTF_16LE, false));
        assertArrayEquals(
                new byte[] {(byte) 0xFF, (byte) 0xFE, 'h', 0, 'i', 0, '\n', 0},
                EditorConfigCharset.encode(text, EditorConfigCharset.UTF_16LE, true));
    }
}
