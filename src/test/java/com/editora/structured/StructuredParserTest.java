package com.editora.structured;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure tests for JSON/YAML/TOML parsing into the neutral {@link StructuredNode} tree. */
class StructuredParserTest {

    @Test
    void formatForLanguage() {
        assertSame(StructuredParser.Format.JSON, StructuredParser.Format.forLanguage("json"));
        assertSame(StructuredParser.Format.YAML, StructuredParser.Format.forLanguage("yaml"));
        assertSame(StructuredParser.Format.TOML, StructuredParser.Format.forLanguage("toml"));
        assertNull(StructuredParser.Format.forLanguage("markdown"));
        assertNull(StructuredParser.Format.forLanguage(null));
    }

    @Test
    void parsesJsonObjectWithTypedScalars() {
        String json = "{\"name\":\"Ada\",\"age\":36,\"admin\":true,\"note\":null,\"tags\":[\"a\",\"b\"]}";
        StructuredParser.Parsed p = StructuredParser.parse(json, StructuredParser.Format.JSON);
        assertTrue(p.ok());
        assertFalse(p.isOpenApi());
        StructuredNode root = p.root();
        assertSame(StructuredNode.Kind.OBJECT, root.kind());
        assertEquals(5, root.size());
        StructuredNode name = root.children().get(0);
        assertEquals("name", name.key());
        assertSame(StructuredNode.Kind.STRING, name.kind());
        assertEquals("Ada", name.value());
        assertSame(StructuredNode.Kind.NUMBER, root.children().get(1).kind());
        assertEquals("36", root.children().get(1).value());
        assertSame(StructuredNode.Kind.BOOLEAN, root.children().get(2).kind());
        assertSame(StructuredNode.Kind.NULL, root.children().get(3).kind());
        StructuredNode tags = root.children().get(4);
        assertSame(StructuredNode.Kind.ARRAY, tags.kind());
        assertEquals(2, tags.size());
        assertNull(tags.children().get(0).key()); // array elements have no key
    }

    @Test
    void parsesYamlToTheSameShape() {
        String yaml = "name: Ada\nnested:\n  x: 1\n  y: 2\nlist:\n  - one\n  - two\n";
        StructuredParser.Parsed p = StructuredParser.parse(yaml, StructuredParser.Format.YAML);
        assertTrue(p.ok());
        StructuredNode root = p.root();
        assertSame(StructuredNode.Kind.OBJECT, root.kind());
        assertEquals(3, root.size());
        StructuredNode nested = root.children().get(1);
        assertEquals("nested", nested.key());
        assertSame(StructuredNode.Kind.OBJECT, nested.kind());
        assertEquals(2, nested.size());
        assertSame(StructuredNode.Kind.ARRAY, root.children().get(2).kind());
    }

    @Test
    void parsesTomlTable() {
        String toml = "title = \"cfg\"\nport = 8080\n[server]\nhost = \"localhost\"\n";
        StructuredParser.Parsed p = StructuredParser.parse(toml, StructuredParser.Format.TOML);
        assertTrue(p.ok());
        assertFalse(p.isOpenApi()); // TOML is never treated as OpenAPI
        StructuredNode root = p.root();
        assertSame(StructuredNode.Kind.OBJECT, root.kind());
        assertEquals(3, root.size());
        StructuredNode server = root.children().get(2);
        assertEquals("server", server.key());
        assertSame(StructuredNode.Kind.OBJECT, server.kind());
    }

    @Test
    void blankAndInvalid() {
        assertTrue(StructuredParser.parse("   ", StructuredParser.Format.JSON).ok());
        StructuredParser.Parsed bad = StructuredParser.parse("{ not valid json ", StructuredParser.Format.JSON);
        assertFalse(bad.ok());
        assertNull(bad.root());
        assertNull(StructuredParser.parse("x", null).root());
    }
}
