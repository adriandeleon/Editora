package com.editora.editorconfig;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorConfigParserTest {

    @Test
    void parsesRootAndSections() {
        EditorConfigParser.Parsed p = EditorConfigParser.parse("""
                # a comment
                root = true

                [*]
                indent_style = space
                indent_size = 2

                [*.md]
                trim_trailing_whitespace = false
                """);
        assertTrue(p.root());
        assertEquals(2, p.sections().size());
        assertEquals("*", p.sections().get(0).glob());
        assertEquals("space", p.sections().get(0).properties().get("indent_style"));
        assertEquals("*.md", p.sections().get(1).glob());
    }

    @Test
    void lastValueWinsWithinSection() {
        EditorConfigParser.Parsed p = EditorConfigParser.parse("[*]\nindent_size = 2\nindent_size = 4\n");
        assertEquals("4", p.sections().get(0).properties().get("indent_size"));
    }

    @Test
    void keysAndValuesAreLowercasedButGlobIsNot() {
        EditorConfigParser.Parsed p = EditorConfigParser.parse("[*.Java]\nIndent_Style = SPACE\n");
        assertEquals("*.Java", p.sections().get(0).glob());
        assertEquals("space", p.sections().get(0).properties().get("indent_style"));
    }

    @Test
    void commentsAndBlankLinesIgnored() {
        EditorConfigParser.Parsed p = EditorConfigParser.parse("; semi\n#hash\n\n[*]\nindent_size=3\n");
        assertFalse(p.root());
        assertEquals(1, p.sections().size());
    }

    @Test
    void toPropertiesMapsTypes() {
        EditorConfigParser.Parsed p = EditorConfigParser.parse("""
                [*]
                indent_style = tab
                tab_width = 8
                end_of_line = crlf
                charset = utf-8-bom
                trim_trailing_whitespace = true
                insert_final_newline = false
                max_line_length = 100
                """);
        EditorConfigProperties props =
                EditorConfigParser.toProperties(p.sections().get(0).properties());
        assertEquals(Boolean.FALSE, props.insertSpaces());
        assertEquals(8, props.tabWidth());
        assertEquals("crlf", props.endOfLine());
        assertEquals("utf-8-bom", props.charset());
        assertEquals(Boolean.TRUE, props.trimTrailingWhitespace());
        assertEquals(Boolean.FALSE, props.insertFinalNewline());
        assertEquals(100, props.maxLineLength());
    }

    @Test
    void indentSizeTabDefersToTabWidth() {
        EditorConfigProperties props =
                EditorConfigParser.toProperties(EditorConfigParser.parse("[*]\nindent_size = tab\ntab_width = 4\n")
                        .sections()
                        .get(0)
                        .properties());
        assertNull(props.indentSize());
        assertEquals(4, props.effectiveIndentSize(2));
    }

    @Test
    void maxLineLengthOffSentinelAndInvalidIgnored() {
        EditorConfigProperties off =
                EditorConfigParser.toProperties(EditorConfigParser.parse("[*]\nmax_line_length = off\n")
                        .sections()
                        .get(0)
                        .properties());
        assertEquals(EditorConfigProperties.OFF, off.maxLineLength());

        EditorConfigProperties bad =
                EditorConfigParser.toProperties(EditorConfigParser.parse("[*]\nindent_size = huge\ncharset = klingon\n")
                        .sections()
                        .get(0)
                        .properties());
        assertNull(bad.indentSize());
        assertNull(bad.charset());
    }
}
