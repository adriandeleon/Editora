package com.editora.structured;

import java.util.List;

/**
 * A neutral, toolkit-free node in a parsed XML DOM tree, for the structured-data preview — a <b>faithful</b>
 * DOM model (elements / attributes / text / comments), not a lossy XML→JSON shoehorn. The editor's
 * {@code XmlTree} renders this into a JavaFX {@code TreeView}; keeping it DOM-free means the {@code editor}
 * package never touches {@code javax.xml} (all parsing lives in {@link XmlParser}).
 *
 * <p>An {@link Kind#ELEMENT} has a tag {@link #name}, its {@link #attributes}, and either {@link #children}
 * (child elements/comments/mixed text) or — for a text-only leaf like {@code <name>Ada</name>} — an inline
 * {@link #value} with no children. {@link Kind#TEXT}/{@link Kind#COMMENT} carry a {@link #value}.
 */
public final class XmlNode {

    public enum Kind {
        ELEMENT,
        TEXT,
        COMMENT
    }

    /** An element attribute (including namespace declarations, which the DOM surfaces as attributes). */
    public record Attr(String name, String value) {}

    private final Kind kind;
    private final String name;
    private final List<Attr> attributes;
    private final String value;
    private final List<XmlNode> children;

    public XmlNode(Kind kind, String name, List<Attr> attributes, String value, List<XmlNode> children) {
        this.kind = kind;
        this.name = name;
        this.attributes = attributes == null ? List.of() : List.copyOf(attributes);
        this.value = value;
        this.children = children == null ? List.of() : List.copyOf(children);
    }

    public Kind kind() {
        return kind;
    }

    /** The tag name for an element (as written, prefix included); {@code null} for text/comment. */
    public String name() {
        return name;
    }

    public List<Attr> attributes() {
        return attributes;
    }

    /** The inline text of a text-only element, or a text/comment node's content; {@code null} otherwise. */
    public String value() {
        return value;
    }

    public List<XmlNode> children() {
        return children;
    }

    public boolean isElement() {
        return kind == Kind.ELEMENT;
    }

    public int size() {
        return children.size();
    }
}
