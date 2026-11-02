package com.editora.ui;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilderFactory;

import javafx.scene.paint.Color;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Map's SVG export as a document ({@link ProjectMapSvg}, Map explore position 16): each kind of row gets its own
 * mark (none for a stub), dimmed rows are faded, the selected row is outlined, Structure columns take their own fill,
 * a column with no count has no count text, connectors curve along the flow, and coordinates and text are always
 * valid XML.
 */
class ProjectMapSvgTest {

    private static final ProjectMapSvg.Palette PALETTE = new ProjectMapSvg.Palette(
            "#ffffff", "#f0f0f0", "#cccccc", "#e0e0ff", "#fafafa", "#dddddd", "#111111", "#777777", "#0066cc",
            "#aa8800", "#448844", "#8844aa");

    private static ProjectMapSvg.Row row(
            String name, boolean folder, boolean symbol, boolean stub, boolean selected, boolean dimmed, double y) {
        return new ProjectMapSvg.Row(name, name, folder, symbol, stub, selected, dimmed, 10, y, 180, 24, 40, y + 16);
    }

    private static Document parse(String svg) throws Exception {
        return DocumentBuilderFactory.newInstance()
                .newDocumentBuilder()
                .parse(new ByteArrayInputStream(svg.getBytes(StandardCharsets.UTF_8)));
    }

    private static List<Element> elements(Element parent, String tag) {
        NodeList nodes = parent.getElementsByTagName(tag);
        List<Element> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            out.add((Element) nodes.item(i));
        }
        return out;
    }

    private static Element group(Document doc, String className) {
        return elements(doc.getDocumentElement(), "g").stream()
                .filter(g -> className.equals(g.getAttribute("class")))
                .findFirst()
                .orElseThrow();
    }

    @Test
    void eachKindOfRowHasItsOwnMarkAndItsStates() throws Exception {
        List<ProjectMapSvg.Row> rows = List.of(
                row("src", true, false, false, false, false, 30),
                row("App.java", false, false, false, true, false, 60),
                row("main()", false, true, false, false, false, 90),
                row("+3 more", false, false, true, false, false, 120),
                row("build", true, false, false, false, true, 150));
        Document doc = parse(ProjectMapSvg.write(
                new double[] {0, 0, 400, 300},
                List.of(
                        new ProjectMapSvg.Column("project", "5", false, 0, 0, 200, 200),
                        new ProjectMapSvg.Column("App.java", "", true, 220, 0, 160, 120)),
                rows,
                List.of(),
                PALETTE,
                "Map of project"));

        List<Element> rowGroups = new ArrayList<>();
        for (Element g : elements(group(doc, "rows"), "g")) {
            rowGroups.add(g);
        }
        assertEquals(5, rowGroups.size());
        // folder: an opaque, rounder mark in the folder colour
        List<Element> folderRects = elements(rowGroups.get(0), "rect");
        assertEquals(2, folderRects.size(), "the row and its mark");
        assertEquals("#aa8800", folderRects.get(1).getAttribute("fill"));
        assertEquals("2", folderRects.get(1).getAttribute("rx"));
        assertEquals("", folderRects.get(1).getAttribute("fill-opacity"));
        // the selected file: outlined in the accent, its label bold, a translucent file mark
        Element selected = elements(rowGroups.get(1), "rect").getFirst();
        assertEquals("#0066cc", selected.getAttribute("stroke"));
        assertEquals("1.8", selected.getAttribute("stroke-width"));
        assertEquals("#448844", elements(rowGroups.get(1), "rect").get(1).getAttribute("fill"));
        assertEquals("0.55", elements(rowGroups.get(1), "rect").get(1).getAttribute("fill-opacity"));
        assertEquals("600", elements(rowGroups.get(1), "text").getFirst().getAttribute("font-weight"));
        // a declaration takes the symbol colour
        assertEquals("#8844aa", elements(rowGroups.get(2), "rect").get(1).getAttribute("fill"));
        // a stub has no mark; a dimmed row is faded; the others are not
        assertEquals(1, elements(rowGroups.get(3), "rect").size());
        assertEquals("#dddddd", elements(rowGroups.get(3), "rect").getFirst().getAttribute("stroke"));
        assertEquals("0.45", rowGroups.get(4).getAttribute("opacity"));
        assertEquals("", rowGroups.get(0).getAttribute("opacity"));
        assertEquals("build", elements(rowGroups.get(4), "title").getFirst().getTextContent());

        List<Element> columns = elements(group(doc, "columns"), "g");
        assertEquals("#f0f0f0", elements(columns.get(0), "rect").getFirst().getAttribute("fill"));
        assertEquals("#e0e0ff", elements(columns.get(1), "rect").getFirst().getAttribute("fill"), "a Structure column");
        assertEquals(2, elements(columns.get(0), "text").size(), "title and count");
        assertEquals("5", elements(columns.get(0), "text").get(1).getTextContent());
        assertEquals(1, elements(columns.get(1), "text").size(), "no count, no count text");
        assertEquals(
                "Map of project",
                elements(doc.getDocumentElement(), "title").getFirst().getTextContent());
    }

    @Test
    void connectorsCurveAlongTheFlowInEitherDirection() throws Exception {
        Document doc = parse(ProjectMapSvg.write(
                new double[] {0, 0, 100, 100},
                List.of(),
                List.of(),
                List.of(new ProjectMapSvg.Link(0, 10, 40, 50, false), new ProjectMapSvg.Link(10, 0, 50, 40, true)),
                PALETTE,
                "t"));
        List<Element> paths = elements(group(doc, "links"), "path");
        assertEquals("M0 10 C20 10 20 50 40 50", paths.get(0).getAttribute("d"), "left to right: handles mid-x");
        assertEquals("M10 0 C10 20 50 20 50 40", paths.get(1).getAttribute("d"), "top down: handles mid-y");
    }

    @Test
    void coordinatesAreFiniteAndPlainAndTextIsValidXml() throws Exception {
        assertEquals("0", ProjectMapSvg.n(Double.NaN));
        assertEquals("0", ProjectMapSvg.n(Double.POSITIVE_INFINITY));
        assertEquals("1.50", ProjectMapSvg.n(1.5));
        assertEquals("10000000000000000.00", ProjectMapSvg.n(1e16), "never an exponent");
        assertEquals("a\tb\nc\rd", ProjectMapSvg.escape("a\tb\nc\rd"), "the whitespace XML allows is kept");
        assertEquals("ab", ProjectMapSvg.escape("a￾b￿"), "the two non-characters are dropped");
        assertEquals("é€", ProjectMapSvg.escape("é€"));
        assertEquals("#ff8000", ProjectMapSvg.hex(Color.color(1, 0.5, 0, 0.3)));

        String svg = ProjectMapSvg.write(
                new double[] {Double.NaN, 0, 10, 10},
                List.of(new ProjectMapSvg.Column("<a & 'b'>\u0007", "\"1\"", false, 0, 0, 10, 10)),
                List.of(row("x\u0000y", false, false, false, false, false, 0)),
                List.of(),
                PALETTE,
                "t & u");
        Document doc = parse(svg);
        assertTrue(svg.contains("viewBox=\"0 0 10 10\""), svg);
        assertEquals(
                "<a & 'b'>", elements(group(doc, "columns"), "text").getFirst().getTextContent());
        assertEquals("xy", elements(group(doc, "rows"), "title").getFirst().getTextContent());
    }
}
