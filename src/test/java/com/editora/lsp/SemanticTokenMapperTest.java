package com.editora.lsp;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class SemanticTokenMapperTest {

    private static final List<String> MODS =
            List.of("declaration", "definition", "readonly", "static", "deprecated", "abstract", "async");

    private static int bit(int index) {
        return 1 << index;
    }

    @Test
    void typesMapToBaseClasses() {
        assertEquals("sem-parameter", SemanticTokenMapper.cssClass("parameter", 0, MODS));
        assertEquals("sem-variable", SemanticTokenMapper.cssClass("variable", 0, MODS));
        assertEquals("sem-property", SemanticTokenMapper.cssClass("property", 0, MODS));
        assertEquals("sem-property", SemanticTokenMapper.cssClass("enumMember", 0, MODS));
        assertEquals("sem-function", SemanticTokenMapper.cssClass("method", 0, MODS));
        assertEquals("sem-function", SemanticTokenMapper.cssClass("function", 0, MODS));
        assertEquals("sem-type", SemanticTokenMapper.cssClass("class", 0, MODS));
        assertEquals("sem-type", SemanticTokenMapper.cssClass("interface", 0, MODS));
        assertEquals("sem-macro", SemanticTokenMapper.cssClass("decorator", 0, MODS));
    }

    @Test
    void jdtlsSpecificTypesMap() {
        // jdtls publishes these beyond the standard set
        assertEquals("sem-type", SemanticTokenMapper.cssClass("record", 0, MODS));
        assertEquals("sem-property", SemanticTokenMapper.cssClass("recordComponent", 0, MODS));
        assertEquals("sem-macro", SemanticTokenMapper.cssClass("annotation", 0, MODS));
    }

    @Test
    void lexicalTypesDeferToTextMate() {
        for (String t : List.of("keyword", "comment", "string", "number", "regexp", "operator", "modifier")) {
            assertNull(SemanticTokenMapper.cssClass(t, 0, MODS), t + " should fall through to TextMate");
        }
        assertNull(SemanticTokenMapper.cssClass("somethingNew", 0, MODS));
        assertNull(SemanticTokenMapper.cssClass(null, 0, MODS));
    }

    @Test
    void deprecatedTakesPrecedenceOverReadonly() {
        int both = bit(2) | bit(4); // readonly + deprecated
        assertEquals("sem-function sem-deprecated", SemanticTokenMapper.cssClass("method", both, MODS));
    }

    @Test
    void readonlyOrStaticEmphasis() {
        assertEquals("sem-variable sem-constant", SemanticTokenMapper.cssClass("variable", bit(2), MODS));
        assertEquals("sem-type sem-constant", SemanticTokenMapper.cssClass("class", bit(3), MODS));
    }

    @Test
    void unrelatedModifiersDoNotEmphasize() {
        assertEquals("sem-parameter", SemanticTokenMapper.cssClass("parameter", bit(0) | bit(1), MODS));
    }

    @Test
    void modifierLegendIsNullSafe() {
        // No legend → modifiers can't be resolved, so just the base class (never throws even with all bits set).
        assertEquals("sem-function", SemanticTokenMapper.cssClass("method", 0xFFFF, null));
        // A modifier name absent from this server's legend is simply not set (deprecated bit, but no such entry).
        assertEquals("sem-variable", SemanticTokenMapper.cssClass("variable", bit(4), List.of("declaration")));
    }
}
