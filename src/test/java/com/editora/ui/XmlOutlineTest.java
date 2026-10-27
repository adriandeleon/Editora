package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The Structure window's XML outline, with and without lemminx (XML review C4, B13/C11). */
class XmlOutlineTest {

    private static final String XSD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!-- a schema -->
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="note">
                <xs:complexType>
                  <xs:sequence>
                    <xs:element name="to" type="xs:string"/>
                    <xs:element ref="from"/>
                  </xs:sequence>
                  <xs:attribute
                      name="id"
                      type="xs:ID"/>
                </xs:complexType>
              </xs:element>
            </xs:schema>
            """;

    /** Each node as "label@line" in document order, depth by indentation. */
    private static List<String> flat(List<StructureNode> nodes) {
        List<String> out = new ArrayList<>();
        flat(nodes, "", out);
        return out;
    }

    private static void flat(List<StructureNode> nodes, String indent, List<String> out) {
        for (StructureNode n : nodes) {
            out.add(indent + n.label() + "@" + n.line());
            flat(n.children(), indent + "  ", out);
        }
    }

    @Test
    void aSchemaListsEveryElementByQualifiedNameAndNamingAttribute() {
        assertEquals(
                List.of(
                        "xs:schema@2",
                        "  xs:element name=\"note\"@3",
                        "    xs:complexType@4",
                        "      xs:sequence@5",
                        "        xs:element name=\"to\"@6",
                        "        xs:element ref=\"from\"@7",
                        "      xs:attribute name=\"id\"@9"),
                flat(XmlOutline.build(XSD)));
        StructureNode schema = XmlOutline.build(XSD).get(0);
        assertEquals(14, schema.region().endLine(), "the schema spans to its end tag");
        StructureNode attribute =
                schema.children().get(0).children().get(0).children().get(1);
        assertEquals(9, attribute.region().startLine());
        assertEquals(11, attribute.region().endLine(), "a start tag over three lines");
    }

    @Test
    void anXsltTemplateIsNamedByItsMatchAndALongValueIsCut() {
        String xsl = "<xsl:stylesheet version=\"1.0\" xmlns:xsl=\"http://www.w3.org/1999/XSL/Transform\">\n"
                + "  <xsl:template match=\"/\"><out/></xsl:template>\n"
                + "  <xsl:template match=\"" + "a/".repeat(40) + "b\"/>\n"
                + "</xsl:stylesheet>\n";
        List<String> rows = flat(XmlOutline.build(xsl));
        assertEquals("  xsl:template match=\"/\"@1", rows.get(1));
        assertEquals("    out@1", rows.get(2), "a one-line leaf is listed");
        assertTrue(rows.get(3).contains("…\"@2"), rows.get(3));
    }

    @Test
    void commentsCdataAndADoctypeAreSkippedAndBrokenMarkupStillOutlines() {
        String text = "<!DOCTYPE config [\n<!ELEMENT config ANY>\n<!ENTITY x \"<y>\">\n]>\n"
                + "<config>\n"
                + "  <!-- <ignored/> -->\n"
                + "  <![CDATA[ <alsoIgnored/> ]]>\n"
                + "  <features id=\"f\">\n"
                + "    <feature>one</feature>\n"
                + "  <authors>\n" // features never closed
                + "    <author>Ada</author>\n"
                + "  </authors>\n"
                + "</config>\n";
        assertEquals(
                List.of("config@4", "  features id=\"f\"@7", "    feature@8", "    authors@9", "      author@10"),
                flat(XmlOutline.build(text)));
        assertEquals(List.of(), XmlOutline.build("no markup at all < here"));
    }

    @Test
    void theListIsCapped() {
        String many = "<r>\n" + "<e/>\n".repeat(XmlOutline.MAX_NODES + 50) + "</r>\n";
        List<StructureNode> roots = XmlOutline.build(many);
        assertEquals(XmlOutline.MAX_NODES - 1, roots.get(0).children().size());
    }

    @Test
    void attributesAreReadWhateverTheQuotingAndSpacing() {
        assertEquals("a", XmlOutline.attribute("<e  name = 'a' />", "name"));
        assertEquals("b", XmlOutline.attribute("<e checked name=b>", "name"));
        assertEquals("x y", XmlOutline.attribute("<e title=\"name='no'\" name=\"x y\">", "name"));
        assertEquals(null, XmlOutline.attribute("<e title=\"name='no'\">", "name"));
        assertEquals("e id=\"k\"", XmlOutline.label("e", "<e id=\"k\" name=\"\">"), "a blank name gives way");
    }

    private static SymbolNode sym(String name, String kind, int line, int end, SymbolNode... children) {
        return new SymbolNode(name, "", kind, line, end, List.of(children));
    }

    @Test
    void theServersOutlineLosesTheGrammarsRowsAndNamesItsElements() {
        List<SymbolNode> server = List.of(
                sym(
                        "Grammars",
                        "module",
                        0,
                        0,
                        sym(
                                "file:///tmp/x/note.xsd",
                                "file",
                                0,
                                0,
                                sym("Cache: false", "property", 0, 0),
                                sym("Binding: xsi:noNamespaceSchemaLocation", "property", 0, 0))),
                sym(
                        "xs:schema",
                        "field",
                        2,
                        14,
                        sym("xs:element", "field", 3, 13, sym("xs:complexType", "field", 4, 12))));
        List<String> lines = XSD.lines().toList();
        List<StructureNode> tidy = XmlOutline.tidyLsp(server, lines::get, lines.size());
        assertEquals(List.of("xs:schema@2", "  xs:element name=\"note\"@3", "    xs:complexType@4"), flat(tidy));
        assertEquals("field", tidy.get(0).kind(), "the server's kind is kept");
    }

    @Test
    void anElementNamedGrammarsIsKept() {
        assertFalse(XmlOutline.isGrammarsNode(sym("Grammars", "field", 0, 3, sym("grammar", "field", 1, 2))));
        assertFalse(XmlOutline.isGrammarsNode(sym("Grammars", "field", 0, 0)));
    }

    @Test
    void structureOutlineUsesTheXmlOutlineForXmlButNotForAPom() {
        List<StructureNode> xsd = StructureOutline.build(
                StructureOutline.Source.file("note.xsd", XSD).analyzed());
        assertEquals("xs:schema", xsd.get(0).label());
        String pom = "<project>\n  <dependencies>\n    <dependency>\n      <groupId>g</groupId>\n"
                + "      <artifactId>a</artifactId>\n    </dependency>\n  </dependencies>\n</project>\n";
        List<String> rows = flat(StructureOutline.build(
                StructureOutline.Source.file("pom.xml", pom).analyzed()));
        assertTrue(rows.stream().noneMatch(r -> r.contains("groupId")), "a pom keeps its own outline: " + rows);
    }
}
