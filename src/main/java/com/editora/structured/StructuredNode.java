package com.editora.structured;

import java.util.List;

/**
 * A neutral, toolkit-free node in a parsed structured-data tree (JSON / YAML / TOML). The editor's
 * {@code StructuredTree} renders this into a JavaFX {@code TreeView}; keeping it Jackson-free means the
 * {@code editor} package never imports Jackson (all parsing lives in {@link StructuredParser}).
 *
 * <p>A container node ({@link Kind#OBJECT}/{@link Kind#ARRAY}) has {@link #children} and a {@code null}
 * {@link #value}; a scalar has a rendered {@link #value} and no children. {@link #key} is the field name
 * for an object member, or {@code null} for an array element / the root.
 */
public final class StructuredNode {

    public enum Kind {
        OBJECT,
        ARRAY,
        STRING,
        NUMBER,
        BOOLEAN,
        NULL
    }

    private final String key;
    private final Kind kind;
    private final String value;
    private final List<StructuredNode> children;

    public StructuredNode(String key, Kind kind, String value, List<StructuredNode> children) {
        this.key = key;
        this.kind = kind;
        this.value = value;
        this.children = children == null ? List.of() : List.copyOf(children);
    }

    /** The field name for an object member, or {@code null} for an array element / the root. */
    public String key() {
        return key;
    }

    public Kind kind() {
        return kind;
    }

    /** The rendered scalar value (for STRING/NUMBER/BOOLEAN/NULL); {@code null} for containers. */
    public String value() {
        return value;
    }

    public List<StructuredNode> children() {
        return children;
    }

    public boolean isContainer() {
        return kind == Kind.OBJECT || kind == Kind.ARRAY;
    }

    /** The number of direct children (for the "{n} items"/"{n} keys" container summary). */
    public int size() {
        return children.size();
    }
}
