package com.editora.structured;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/**
 * Parses XML text into a neutral {@link XmlNode} DOM tree for the 3-mode preview, using the JDK's own DOM
 * parser (no third-party XML dependency) hardened against XXE exactly like {@code maven/PomParser}: DOCTYPE
 * disallowed outright, external general/parameter entities + external-DTD loading disabled, no XInclude,
 * no entity-reference expansion. Pure; unit-tested. Called off the FX thread (the caller uses
 * {@code PREVIEW_POOL}). A <b>text-only</b> element ({@code <name>Ada</name>}) collapses to an inline
 * {@code value} with no children, so a leaf renders as one tree row.
 */
public final class XmlParser {

    /** Guard so a pathological document can't build millions of tree nodes on the FX thread. */
    static final int MAX_NODES = 50_000;

    /** The parse outcome: a {@code root} element (never null on success) or an {@code error} message. */
    public record Parsed(XmlNode root, String error) {
        public boolean ok() {
            return error == null && root != null;
        }
    }

    private XmlParser() {}

    public static Parsed parse(String text) {
        if (text == null || text.isBlank()) {
            return new Parsed(null, "empty document");
        }
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            // XXE hardening (mirrors PomParser): never resolve a DOCTYPE or an external/parameter entity.
            dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            dbf.setFeature("http://xml.org/sax/features/external-general-entities", false);
            dbf.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            dbf.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            dbf.setXIncludeAware(false);
            dbf.setExpandEntityReferences(false);
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(new InputSource(new StringReader(text)));
            Element root = doc.getDocumentElement();
            if (root == null) {
                return new Parsed(null, "no root element");
            }
            return new Parsed(fromElement(root, new int[] {0}), null);
        } catch (TooLargeException e) {
            return new Parsed(null, "document too large to preview (over " + MAX_NODES + " nodes)");
        } catch (Exception e) {
            return new Parsed(null, firstLine(e.getMessage()));
        }
    }

    static XmlNode fromElement(Element el, int[] count) {
        bump(count);
        List<XmlNode.Attr> attrs = attributesOf(el);

        boolean hasElementOrComment = false;
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            int t = c.getNodeType();
            if (t == Node.ELEMENT_NODE || t == Node.COMMENT_NODE) {
                hasElementOrComment = true;
                break;
            }
        }

        if (!hasElementOrComment) {
            // Text-only (or empty) element → inline the text as the node's value (null when empty).
            StringBuilder sb = new StringBuilder();
            for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
                if (c.getNodeType() == Node.TEXT_NODE || c.getNodeType() == Node.CDATA_SECTION_NODE) {
                    sb.append(c.getNodeValue());
                }
            }
            String v = sb.toString().strip();
            return new XmlNode(XmlNode.Kind.ELEMENT, el.getNodeName(), attrs, v.isEmpty() ? null : v, List.of());
        }

        // Container / mixed content: keep child elements, comments, and significant text in document order.
        List<XmlNode> children = new ArrayList<>();
        for (Node c = el.getFirstChild(); c != null; c = c.getNextSibling()) {
            switch (c.getNodeType()) {
                case Node.ELEMENT_NODE -> children.add(fromElement((Element) c, count));
                case Node.COMMENT_NODE -> {
                    bump(count);
                    children.add(new XmlNode(XmlNode.Kind.COMMENT, null, null, c.getNodeValue(), null));
                }
                case Node.TEXT_NODE, Node.CDATA_SECTION_NODE -> {
                    String v = c.getNodeValue();
                    if (v != null && !v.strip().isEmpty()) {
                        bump(count);
                        children.add(new XmlNode(XmlNode.Kind.TEXT, null, null, v.strip(), null));
                    }
                }
                default -> {
                    // skip processing instructions, etc.
                }
            }
        }
        return new XmlNode(XmlNode.Kind.ELEMENT, el.getNodeName(), attrs, null, children);
    }

    private static List<XmlNode.Attr> attributesOf(Element el) {
        NamedNodeMap am = el.getAttributes();
        if (am == null || am.getLength() == 0) {
            return List.of();
        }
        List<XmlNode.Attr> attrs = new ArrayList<>(am.getLength());
        for (int i = 0; i < am.getLength(); i++) {
            Node a = am.item(i);
            attrs.add(new XmlNode.Attr(a.getNodeName(), a.getNodeValue()));
        }
        return attrs;
    }

    private static void bump(int[] count) {
        if (++count[0] > MAX_NODES) {
            throw new TooLargeException();
        }
    }

    private static String firstLine(String msg) {
        if (msg == null || msg.isBlank()) {
            return "invalid XML";
        }
        int nl = msg.indexOf('\n');
        return (nl >= 0 ? msg.substring(0, nl) : msg).strip();
    }

    private static final class TooLargeException extends RuntimeException {
        TooLargeException() {
            super(null, null, false, false);
        }
    }
}
