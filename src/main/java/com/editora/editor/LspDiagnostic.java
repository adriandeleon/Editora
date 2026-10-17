package com.editora.editor;

/**
 * A language-server diagnostic as a flat, range-based value the editor surface can render — independent
 * of LSP4J types (the {@code com.editora.lsp} layer maps LSP {@code Diagnostic}s into these). Positions
 * are 0-based line + character (LSP convention; the overlay maps them to paragraphs/columns). Distinct
 * from {@code mermaid.MaidOutput.Diagnostic}, which is a single line + length.
 */
public record LspDiagnostic(
        int startLine,
        int startCol,
        int endLine,
        int endCol,
        Severity severity,
        String message,
        String code,
        String source,
        String href,
        boolean unnecessary,
        boolean deprecated) {

    /**
     * A diagnostic that is not tagged {@code Deprecated}.
     *
     * @see #deprecated() tagged {@code Deprecated} (a call to a deprecated function), drawn struck through
     */
    public LspDiagnostic(
            int startLine,
            int startCol,
            int endLine,
            int endCol,
            Severity severity,
            String message,
            String code,
            String source,
            String href,
            boolean unnecessary) {
        this(startLine, startCol, endLine, endCol, severity, message, code, source, href, unnecessary, false);
    }

    /**
     * A diagnostic without a documentation link or tags.
     *
     * @see #href() the rule's documentation ({@code codeDescription.href}, e.g. ShellCheck's wiki page), or null
     * @see #unnecessary() tagged {@code Unnecessary} (unused code), drawn faded
     */
    public LspDiagnostic(
            int startLine,
            int startCol,
            int endLine,
            int endCol,
            Severity severity,
            String message,
            String code,
            String source) {
        this(startLine, startCol, endLine, endCol, severity, message, code, source, null, false, false);
    }

    /** LSP severity levels (1..4), ordered most→least severe. */
    public enum Severity {
        ERROR,
        WARNING,
        INFO,
        HINT
    }

    /**
     * Whether this is only a tag carried as a hint — Pyright's "x is not accessed" / "f is deprecated", which a
     * client shows by fading or striking the code. Such hints are drawn in the editor but kept out of the
     * Problems list, where an unused import would otherwise bury the real errors (review B3).
     */
    public boolean tagOnlyHint() {
        return severity == Severity.HINT && (unnecessary || deprecated);
    }

    /** A short "source: code" suffix for tooltips/lists, or "" when neither is present. */
    public String origin() {
        String s = source == null ? "" : source.strip();
        String c = code == null ? "" : code.strip();
        if (!s.isEmpty() && !c.isEmpty()) {
            return s + ": " + c;
        }
        return s.isEmpty() ? c : s;
    }
}
