package com.editora.lsp;

import java.util.List;

import com.editora.editor.SemanticToken;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SemanticTokensDecoderTest {

    /** The standard LSP token-type legend (index = position), as a server reports in its capabilities. */
    private static final List<String> TYPES = List.of(
            "namespace", // 0
            "type", // 1
            "class", // 2
            "enum", // 3
            "interface", // 4
            "struct", // 5
            "typeParameter", // 6
            "parameter", // 7
            "variable", // 8
            "property", // 9
            "enumMember", // 10
            "event", // 11
            "function", // 12
            "method", // 13
            "macro", // 14
            "keyword", // 15
            "modifier", // 16
            "comment", // 17
            "string", // 18
            "number", // 19
            "regexp", // 20
            "operator", // 21
            "decorator"); // 22

    private static final List<String> MODS =
            List.of("declaration", "definition", "readonly", "static", "deprecated", "abstract", "async");

    private static int bit(int index) {
        return 1 << index;
    }

    @Test
    void singleTokenAbsolutePosition() {
        // one parameter at line 2, char 13, length 1
        List<SemanticToken> out = SemanticTokensDecoder.decode(List.of(2, 13, 1, 7, 0), TYPES, MODS);
        assertEquals(1, out.size());
        SemanticToken t = out.get(0);
        assertEquals(2, t.line());
        assertEquals(13, t.startChar());
        assertEquals(1, t.length());
        assertEquals("sem-parameter", t.cssClasses());
    }

    @Test
    void twoTokensOnSameLineUseRelativeStartChar() {
        // property at (3,4) len 5, then parameter at (3,12) len 1 — second deltaStartChar = 12-4 = 8.
        List<SemanticToken> out = SemanticTokensDecoder.decode(List.of(3, 4, 5, 9, 0, 0, 8, 1, 7, 0), TYPES, MODS);
        assertEquals(2, out.size());
        assertEquals(3, out.get(0).line());
        assertEquals(4, out.get(0).startChar());
        assertEquals("sem-property", out.get(0).cssClasses());
        assertEquals(3, out.get(1).line()); // deltaLine 0 → same line
        assertEquals(12, out.get(1).startChar()); // 4 + 8
        assertEquals("sem-parameter", out.get(1).cssClasses());
    }

    @Test
    void newLineResetsStartCharToAbsolute() {
        // token A at (0,10), token B one line down at absolute char 4 (deltaLine 1 ⇒ startChar = 4, not 14).
        List<SemanticToken> out = SemanticTokensDecoder.decode(List.of(0, 10, 2, 8, 0, 1, 4, 3, 13, 0), TYPES, MODS);
        assertEquals(2, out.size());
        assertEquals(0, out.get(0).line());
        assertEquals(10, out.get(0).startChar());
        assertEquals(1, out.get(1).line());
        assertEquals(4, out.get(1).startChar()); // absolute, NOT 10+4
    }

    @Test
    void deprecatedModifierAppendsStrikeClass() {
        List<SemanticToken> out = SemanticTokensDecoder.decode(List.of(5, 2, 3, 13, bit(4)), TYPES, MODS);
        assertEquals("sem-function sem-deprecated", out.get(0).cssClasses());
    }

    @Test
    void readonlyAndStaticGetConstantEmphasis() {
        assertEquals(
                "sem-variable sem-constant",
                SemanticTokensDecoder.decode(List.of(1, 0, 3, 8, bit(2)), TYPES, MODS)
                        .get(0)
                        .cssClasses());
        assertEquals(
                "sem-property sem-constant",
                SemanticTokensDecoder.decode(List.of(1, 0, 3, 9, bit(3)), TYPES, MODS)
                        .get(0)
                        .cssClasses());
    }

    @Test
    void unmappedTypeIsDroppedButCursorStillAdvances() {
        // keyword (type 15) at (0,0) len 5 is dropped; parameter follows on the same line at char 6.
        List<SemanticToken> out = SemanticTokensDecoder.decode(List.of(0, 0, 5, 15, 0, 0, 6, 1, 7, 0), TYPES, MODS);
        assertEquals(1, out.size()); // keyword not emitted
        assertEquals(0, out.get(0).line());
        assertEquals(6, out.get(0).startChar()); // the dropped keyword still advanced startChar
        assertEquals("sem-parameter", out.get(0).cssClasses());
    }

    @Test
    void multiLineSequenceDecodesAllPositions() {
        // Models:  class A { int field; void m(int p) { field = p; } }
        //   field   @ (1,6)  property
        //   m       @ (2,7)  method (declaration)
        //   p       @ (2,13) parameter   (same line as m → relative)
        //   field   @ (3,4)  property
        //   p       @ (3,12) parameter   (same line → relative)
        List<Integer> data = List.of(
                1, 6, 5, 9, 0, // field
                1, 7, 1, 13, bit(0), // m (deltaLine 1 from field's line)
                0, 6, 1, 7, 0, // p  (same line, 13-7=6)
                1, 4, 5, 9, 0, // field (deltaLine 1)
                0, 8, 1, 7, 0); // p  (same line, 12-4=8)
        List<SemanticToken> out = SemanticTokensDecoder.decode(data, TYPES, MODS);
        assertEquals(5, out.size());
        assertEquals(1, out.get(0).line());
        assertEquals(6, out.get(0).startChar());
        assertEquals(2, out.get(1).line());
        assertEquals(7, out.get(1).startChar());
        assertEquals("sem-function", out.get(1).cssClasses());
        assertEquals(2, out.get(2).line());
        assertEquals(13, out.get(2).startChar());
        assertEquals(3, out.get(3).line());
        assertEquals(4, out.get(3).startChar());
        assertEquals(3, out.get(4).line());
        assertEquals(12, out.get(4).startChar());
    }

    @Test
    void malformedInputsAreToleratedNotThrown() {
        assertTrue(SemanticTokensDecoder.decode(null, TYPES, MODS).isEmpty());
        assertTrue(SemanticTokensDecoder.decode(List.of(), TYPES, MODS).isEmpty());
        assertTrue(SemanticTokensDecoder.decode(List.of(1, 2, 3), TYPES, MODS).isEmpty()); // partial group
        assertTrue(
                SemanticTokensDecoder.decode(List.of(0, 0, 1, 7, 0), null, MODS).isEmpty()); // no legend
        // type index past the legend → skipped, no throw
        assertTrue(SemanticTokensDecoder.decode(List.of(0, 0, 1, 99, 0), TYPES, MODS)
                .isEmpty());
        // trailing partial group after a valid one: only the valid token is decoded
        assertEquals(
                1,
                SemanticTokensDecoder.decode(List.of(0, 0, 1, 7, 0, 9, 9), TYPES, MODS)
                        .size());
    }

    @Test
    void zeroLengthTokenSkipped() {
        assertTrue(SemanticTokensDecoder.decode(List.of(0, 0, 0, 7, 0), TYPES, MODS)
                .isEmpty());
    }
}
