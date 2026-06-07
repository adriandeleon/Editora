package com.editora.editor;

/**
 * A language-server diagnostic as a flat, range-based value the editor surface can render — independent
 * of LSP4J types (the {@code com.editora.lsp} layer maps LSP {@code Diagnostic}s into these). Positions
 * are 0-based line + character (LSP convention; the overlay maps them to paragraphs/columns). Distinct
 * from {@code mermaid.MaidOutput.Diagnostic}, which is a single line + length.
 */
public record LspDiagnostic(
        int startLine, int startCol, int endLine, int endCol,
        Severity severity, String message, String code, String source) {

    /** LSP severity levels (1..4), ordered most→least severe. */
    public enum Severity { ERROR, WARNING, INFO, HINT }

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
