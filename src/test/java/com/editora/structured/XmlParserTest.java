package com.editora.structured;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for XML → {@link XmlNode} DOM-tree parsing, including XXE hardening. */
class XmlParserTest {

    private static final String DOC = """
            <config version="1.0">
              <name>editora</name>
              <features enabled="true">
                <format>json</format>
                <format>xml</format>
              </features>
              <!-- a comment -->
            </config>
            """;

    @Test
    void parsesElementsAttributesAndInlineText() {
        XmlParser.Parsed p = XmlParser.parse(DOC);
        assertTrue(p.ok());
        XmlNode root = p.root();
        assertSame(XmlNode.Kind.ELEMENT, root.kind());
        assertEquals("config", root.name());
        assertEquals(1, root.attributes().size());
        assertEquals("version", root.attributes().get(0).name());
        assertEquals("1.0", root.attributes().get(0).value());

        // Children (in document order): name, features, comment (whitespace text is skipped).
        assertEquals(3, root.size());
        XmlNode name = root.children().get(0);
        assertEquals("name", name.name());
        assertEquals("editora", name.value()); // text-only element → inline value
        assertTrue(name.children().isEmpty());

        XmlNode features = root.children().get(1);
        assertEquals("features", features.name());
        assertEquals("enabled", features.attributes().get(0).name());
        assertEquals(2, features.size());
        assertEquals("json", features.children().get(0).value());
        assertEquals("xml", features.children().get(1).value());

        assertSame(XmlNode.Kind.COMMENT, root.children().get(2).kind());
    }

    @Test
    void mixedContentKeepsTextInOrder() {
        XmlParser.Parsed p = XmlParser.parse("<p>before <b>bold</b> after</p>");
        assertTrue(p.ok());
        XmlNode root = p.root();
        assertEquals(3, root.size());
        assertSame(XmlNode.Kind.TEXT, root.children().get(0).kind());
        assertEquals("before", root.children().get(0).value());
        assertEquals("b", root.children().get(1).name());
        assertSame(XmlNode.Kind.TEXT, root.children().get(2).kind());
    }

    @Test
    void blankAndInvalid() {
        assertFalse(XmlParser.parse("   ").ok());
        XmlParser.Parsed bad = XmlParser.parse("<open>no close");
        assertFalse(bad.ok());
        assertNull(bad.root());
    }

    @Test
    void xxeIsBlocked() {
        // A DOCTYPE with an external entity must be rejected (disallow-doctype-decl), never fetched.
        String xxe = "<?xml version=\"1.0\"?>\n"
                + "<!DOCTYPE foo [<!ENTITY xxe SYSTEM \"file:///etc/passwd\">]>\n"
                + "<foo>&xxe;</foo>";
        XmlParser.Parsed p = XmlParser.parse(xxe);
        assertFalse(p.ok()); // parse fails on the DOCTYPE; the entity is never resolved
        assertNull(p.root());
    }
}
