package com.editora.structured;

import java.util.List;

import com.editora.structured.DtdDeclarations.Decl;
import com.editora.structured.DtdDeclarations.Kind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A DTD's declarations for its outline (XML review 2 C11). */
class DtdDeclarationsTest {

    private static final String CATALOG = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- <!ELEMENT commented (x)> -->
            <!ENTITY % common "id ID #IMPLIED">
            <!ELEMENT catalog (item*)>
            <!ATTLIST catalog
                version CDATA #FIXED "1.0"
                %common;>
            <!ELEMENT item (#PCDATA)>
            <!ATTLIST item
                sku ID #REQUIRED
                kind (book|cd) "book"
                cover NOTATION (gif|png) #IMPLIED
                note CDATA "a > b">
            <!ENTITY copy "&#169;">
            <!NOTATION gif SYSTEM "image/gif">
            <![IGNORE[ <!ELEMENT ignored (x)> ]]>
            <![INCLUDE[ <!ELEMENT included EMPTY> ]]>
            <!ATTLIST orphan lang CDATA "en">
            """;

    private static String row(Decl d) {
        return d.kind() + " " + d.name() + "@" + d.line() + "-" + d.endLine()
                + (d.attributes().isEmpty()
                        ? ""
                        : " "
                                + d.attributes().stream()
                                        .map(a -> a.name() + "@" + a.line())
                                        .toList());
    }

    @Test
    void elementsCarryTheirAttributesAndEntitiesAndNotationsAreListed() {
        List<String> rows = DtdDeclarations.parse(CATALOG).stream()
                .map(DtdDeclarationsTest::row)
                .toList();
        assertEquals(
                List.of(
                        "PARAMETER_ENTITY common@2-2",
                        "ELEMENT catalog@3-3 [version@5]",
                        "ELEMENT item@7-7 [sku@9, kind@10, cover@11, note@12]",
                        "ENTITY copy@13-13",
                        "NOTATION gif@14-14",
                        "ELEMENT included@16-16",
                        "ATTLIST orphan@17-17 [lang@17]"),
                rows);
    }

    @Test
    void anAttributeListBeforeItsElementIsFoldedIntoIt() {
        List<Decl> decls = DtdDeclarations.parse("<!ATTLIST a x CDATA #IMPLIED>\n<!ELEMENT a EMPTY>\n");
        assertEquals(1, decls.size());
        assertEquals(Kind.ELEMENT, decls.get(0).kind());
        assertEquals(1, decls.get(0).line(), "the element's own line");
        assertEquals("x", decls.get(0).attributes().get(0).name());
    }

    @Test
    void anXmlDocumentHasNoDeclarationsAndTruncatedTextIsSafe() {
        assertEquals(List.of(), DtdDeclarations.parse("<note><to>x</to></note>"));
        assertEquals(List.of(), DtdDeclarations.parse("<!ELEMENT"));
        assertEquals(1, DtdDeclarations.parse("<!ATTLIST a x (b|c").size());
        assertEquals(List.of(), DtdDeclarations.parse("<!ENTITY %"));
    }

    @Test
    void dtdFilesAreToldByTheirExtension() {
        assertTrue(DtdDeclarations.isDtdFileName("catalog.DTD"));
        assertTrue(DtdDeclarations.isDtdFileName("xhtml-lat1.ent"));
        assertTrue(DtdDeclarations.isDtdFileName("xhtml-base-1.mod"));
        assertFalse(DtdDeclarations.isDtdFileName("note.xml"));
        assertFalse(DtdDeclarations.isDtdFileName(null));
    }
}
