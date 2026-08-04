package com.editora.maven;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

/**
 * Parses Maven's {@code archetype-catalog.xml} into {@link MavenArchetype}s.
 *
 * <p>XXE-hardened with the same block as {@link PomParser#parseDocument}. It matters more here: a pom is a
 * file the user already opened, whereas this document is fetched from the network, so a DOCTYPE or external
 * entity would be attacker-controlled.
 *
 * <p>Tolerant by design — a catalog entry missing a version or artifactId is skipped rather than failing the
 * whole fetch, because Maven Central's real catalog has thousands of entries and one bad row should not cost
 * the user the other 4000.
 */
public final class ArchetypeCatalogParser {

    /** Guard against a hostile or accidentally enormous catalog building millions of records. */
    static final int MAX_ENTRIES = 20_000;

    private ArchetypeCatalogParser() {}

    /** @return the archetypes in document order; never null. Throws only on malformed XML. */
    public static List<MavenArchetype> parse(String xml) throws PomParseException {
        if (xml == null || xml.isBlank()) {
            return List.of();
        }
        Document doc = parseDocument(xml);
        NodeList nodes = doc.getElementsByTagName("archetype");
        List<MavenArchetype> out = new ArrayList<>();
        for (int i = 0; i < nodes.getLength() && out.size() < MAX_ENTRIES; i++) {
            if (!(nodes.item(i) instanceof Element e)) {
                continue;
            }
            String groupId = text(e, "groupId");
            String artifactId = text(e, "artifactId");
            String version = text(e, "version");
            if (groupId.isEmpty() || artifactId.isEmpty() || version.isEmpty()) {
                continue; // tolerant: skip the row, keep the catalog
            }
            out.add(new MavenArchetype(
                    groupId, artifactId, version, text(e, "description"), text(e, "repository"), false));
        }
        return List.copyOf(out);
    }

    private static String text(Element parent, String tagName) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (n instanceof Element el && el.getTagName().equals(tagName)) {
                String s = el.getTextContent();
                return s == null ? "" : s.strip();
            }
        }
        return "";
    }

    private static Document parseDocument(String xml) throws PomParseException {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE hardening: never resolve a DOCTYPE or an external/parameter entity.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder builder = dbf.newDocumentBuilder();
            return builder.parse(new InputSource(new StringReader(xml)));
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw new PomParseException("Malformed archetype catalog: " + e.getMessage(), e);
        }
    }
}
