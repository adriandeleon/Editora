package com.editora.git;

/**
 * The kind of change a line carries relative to {@code HEAD}, for the gutter change bar.
 *
 * <p>Mirrors the IntelliJ/VS Code convention: {@link #ADDED} lines are new, {@link #MODIFIED} lines
 * changed in place, and {@link #DELETED} marks the line where one or more lines were removed.
 */
public enum ChangeType {
    ADDED("git-added"),
    MODIFIED("git-modified"),
    DELETED("git-deleted");

    private final String cssClass;

    ChangeType(String cssClass) {
        this.cssClass = cssClass;
    }

    /** The CSS style class used to color this change's gutter bar (themed in {@code app.css}). */
    public String cssClass() {
        return cssClass;
    }
}
