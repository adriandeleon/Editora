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
}
