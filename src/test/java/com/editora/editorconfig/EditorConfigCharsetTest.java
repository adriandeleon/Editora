package com.editora.editorconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
    void displayNames() {
        assertEquals("UTF-8", EditorConfigCharset.displayName("utf-8"));
        assertEquals("UTF-8 BOM", EditorConfigCharset.displayName("utf-8-bom"));
        assertEquals("ISO-8859-1", EditorConfigCharset.displayName("latin1"));
        assertEquals("UTF-16 LE", EditorConfigCharset.displayName("utf-16le"));
        assertEquals("UTF-8", EditorConfigCharset.displayName(null));
    }
}
