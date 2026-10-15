package com.editora.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** One-line XML responses are indented for reading; nothing but whitespace between tags changes (C17). */
class HttpXmlIndentTest {

    @Test
    void elementsGoOnTheirOwnLinesIndentedByDepth() {
        assertEquals(
                "<?xml version=\"1.0\"?>\n<catalog>\n  <book id=\"1\">\n    <title>Editora</title>\n"
                        + "    <price>9.99</price>\n  </book>\n  <empty/>\n</catalog>",
                HttpXmlIndent.indent("<?xml version=\"1.0\"?><catalog><book id=\"1\"><title>Editora</title>"
                        + "<price>9.99</price></book><empty/></catalog>"));
    }

    @Test
    void commentsCdataAndQuotedAnglesAreKeptWhole() {
        assertEquals(
                "<a>\n  <!-- a <b> -->\n  <c x=\"1>2\">t</c>\n  <![CDATA[<raw>]]>\n</a>",
                HttpXmlIndent.indent("<a><!-- a <b> --><c x=\"1>2\">t</c><![CDATA[<raw>]]></a>"));
    }

    @Test
    void anAlreadyFormattedOrNonXmlBodyIsLeftAlone() {
        String formatted = "<a>\n  <b/>\n</a>";
        assertEquals(formatted, HttpXmlIndent.indent(formatted));
        assertEquals("plain text", HttpXmlIndent.indent("plain text"));
        assertEquals("<a><b", HttpXmlIndent.indent("<a><b"), "an unterminated tag: unchanged");
        assertEquals("", HttpXmlIndent.indent(null));
    }
}
