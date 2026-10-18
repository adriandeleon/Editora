package com.editora.dap;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Follow-up 4: what java-debug sends for "a😀b" (measured against java-debug 0.53.2 in jdtls 1.61) is
 * "aߘ\u0080b" — Eclipse JDI's JdwpString.read takes the 4-byte UTF-8 of 😀 as a 3-byte sequence plus a
 * character. The pair rebuilds exactly; a real text that looks like one is told apart by the JVM's own bytes.
 */
class JdwpStringRepairTest {

    /** Eclipse JDI's decoding of {@code text}'s UTF-8 (JdwpString.read, which knows 1-, 2- and 3-byte forms). */
    static String asEclipseJdiReadsIt(String text) {
        byte[] utf8 = text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < utf8.length; ) {
            int a = utf8[i] & 0xFF;
            if ((a >> 4) < 12) {
                out.append((char) a);
                i++;
            } else if ((a >> 4) < 14) {
                out.append((char) (((a & 0x1F) << 6) | (utf8[i + 1] & 0x3F)));
                i += 2;
            } else {
                out.append((char) (((a & 0x0F) << 12) | ((utf8[i + 1] & 0x3F) << 6) | (utf8[i + 2] & 0x3F)));
                i += 3;
            }
        }
        return out.toString();
    }

    @Test
    void theModelOfTheBugMatchesWhatJavaDebugSent() {
        assertEquals("aߘ\u0080b", asEclipseJdiReadsIt("a😀b"));
        assertEquals("xߘ\u0080yߎ\u0089z", asEclipseJdiReadsIt("x😀y🎉z"));
    }

    @Test
    void garbledEmojiAreRebuiltExactly() {
        for (String text : List.of("a😀b", "x😀y🎉z", "𝄞 music", "😀😀", "end 🏳️‍🌈", "􏿿")) {
            String garbled = "\"" + asEclipseJdiReadsIt(text) + "\"";
            assertTrue(JdwpStringRepair.suspect(garbled), text);
            assertEquals("\"" + text + "\"", JdwpStringRepair.rebuild(garbled), text);
        }
    }

    @Test
    void plainTextIsNotSuspectButSomeRealTextLooksLikeAPair() {
        assertFalse(JdwpStringRepair.suspect("\"hello ünï € 日本\""));
        assertFalse(JdwpStringRepair.suspect(null));
        // Cyrillic letter + closing guillemet rebuilds into a valid code point: only the JVM's bytes can tell
        assertTrue(JdwpStringRepair.suspect("\"«слово»\""));
    }

    private static List<DapModels.VariableInfo> bytes(String text, boolean littleEndian) {
        List<DapModels.VariableInfo> out = new ArrayList<>();
        int i = 0;
        for (char ch : text.toCharArray()) {
            byte hi = (byte) (ch >> 8);
            byte lo = (byte) ch;
            out.add(new DapModels.VariableInfo(String.valueOf(i++), String.valueOf(littleEndian ? lo : hi), "byte", 0));
            out.add(new DapModels.VariableInfo(String.valueOf(i++), String.valueOf(littleEndian ? hi : lo), "byte", 0));
        }
        return out;
    }

    @Test
    void theJvmsUtf16BytesAreReadInEitherOrder() {
        assertEquals("a😀b", JdwpStringRepair.utf16(bytes("a😀b", true), true));
        assertEquals("a😀b", JdwpStringRepair.utf16(bytes("a😀b", false), false));
        assertNull(JdwpStringRepair.utf16(List.of(new DapModels.VariableInfo("0", "x", "byte", 0)), true));
    }
}
