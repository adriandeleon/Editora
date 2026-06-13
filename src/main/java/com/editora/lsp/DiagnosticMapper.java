package com.editora.lsp;

import java.util.ArrayList;
import java.util.List;

import com.editora.editor.LspDiagnostic;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Range;

/** Pure mapping from LSP {@link Diagnostic}s to the editor's flat {@link LspDiagnostic} value type. */
public final class DiagnosticMapper {

    private DiagnosticMapper() {}

    /** Maps a server's diagnostic list (may be null) to editor diagnostics, skipping malformed entries. */
    public static List<LspDiagnostic> map(List<Diagnostic> diagnostics) {
        List<LspDiagnostic> out = new ArrayList<>();
        if (diagnostics == null) {
            return out;
        }
        for (Diagnostic d : diagnostics) {
            Range r = d.getRange();
            if (r == null || r.getStart() == null || r.getEnd() == null) {
                continue;
            }
            out.add(new LspDiagnostic(
                    r.getStart().getLine(),
                    r.getStart().getCharacter(),
                    r.getEnd().getLine(),
                    r.getEnd().getCharacter(),
                    severity(d.getSeverity()),
                    d.getMessage() == null ? "" : d.getMessage(),
                    code(d),
                    d.getSource()));
        }
        return out;
    }

    /** Maps LSP severity (null ⇒ ERROR per the spec's "client decides", we treat as ERROR). */
    public static LspDiagnostic.Severity severity(DiagnosticSeverity s) {
        if (s == null) {
            return LspDiagnostic.Severity.ERROR;
        }
        return switch (s) {
            case Error -> LspDiagnostic.Severity.ERROR;
            case Warning -> LspDiagnostic.Severity.WARNING;
            case Information -> LspDiagnostic.Severity.INFO;
            case Hint -> LspDiagnostic.Severity.HINT;
        };
    }

    /** The diagnostic code as a string (LSP code is an Either&lt;String,Integer&gt;), or null. */
    private static String code(Diagnostic d) {
        if (d.getCode() == null) {
            return null;
        }
        if (d.getCode().isLeft()) {
            return d.getCode().getLeft();
        }
        Integer n = d.getCode().getRight();
        return n == null ? null : n.toString();
    }
}
