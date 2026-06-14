package com.editora.lsp;

import java.util.List;

import com.editora.editor.LspDiagnostic;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticMapperTest {

    private static Diagnostic diag(int sl, int sc, int el, int ec, DiagnosticSeverity sev, String msg) {
        Diagnostic d = new Diagnostic(new Range(new Position(sl, sc), new Position(el, ec)), msg);
        d.setSeverity(sev);
        return d;
    }

    @Test
    void mapsRangeSeverityAndMessage() {
        Diagnostic d = diag(2, 4, 2, 9, DiagnosticSeverity.Error, "cannot find symbol");
        d.setSource("java");
        d.setCode(Either.forLeft("compiler.err"));
        List<LspDiagnostic> out = DiagnosticMapper.map(List.of(d));
        assertEquals(1, out.size());
        LspDiagnostic m = out.get(0);
        assertEquals(2, m.startLine());
        assertEquals(4, m.startCol());
        assertEquals(9, m.endCol());
        assertEquals(LspDiagnostic.Severity.ERROR, m.severity());
        assertEquals("cannot find symbol", m.message());
        assertEquals("java: compiler.err", m.origin());
    }

    @Test
    void severityMapping() {
        assertEquals(LspDiagnostic.Severity.WARNING, DiagnosticMapper.severity(DiagnosticSeverity.Warning));
        assertEquals(LspDiagnostic.Severity.INFO, DiagnosticMapper.severity(DiagnosticSeverity.Information));
        assertEquals(LspDiagnostic.Severity.HINT, DiagnosticMapper.severity(DiagnosticSeverity.Hint));
        assertEquals(LspDiagnostic.Severity.ERROR, DiagnosticMapper.severity(null));
    }

    @Test
    void integerCodeAndNullSeverityHandled() {
        Diagnostic d = diag(0, 0, 0, 1, null, "oops"); // null severity → ERROR
        d.setCode(Either.forRight(42));
        List<LspDiagnostic> out = DiagnosticMapper.map(List.of(d));
        assertEquals("oops", out.get(0).message());
        assertEquals(LspDiagnostic.Severity.ERROR, out.get(0).severity());
        assertEquals("42", out.get(0).origin());
        assertTrue(DiagnosticMapper.map(null).isEmpty());
    }

    @Test
    void skipsEntriesWithNoRange() {
        Diagnostic noRange = new Diagnostic();
        noRange.setMessage("x");
        assertTrue(DiagnosticMapper.map(List.of(noRange)).isEmpty());
    }

    @Test
    void mapReportFullReportMapsItems() {
        Diagnostic d = diag(1, 0, 1, 3, DiagnosticSeverity.Warning, "duplicate attribute");
        var full = new org.eclipse.lsp4j.RelatedFullDocumentDiagnosticReport(List.of(d));
        List<LspDiagnostic> out = DiagnosticMapper.mapReport(new org.eclipse.lsp4j.DocumentDiagnosticReport(full));
        assertEquals(1, out.size());
        assertEquals("duplicate attribute", out.get(0).message());
        assertEquals(LspDiagnostic.Severity.WARNING, out.get(0).severity());
    }

    @Test
    void mapReportUnchangedReportReturnsNull() {
        var unchanged = new org.eclipse.lsp4j.RelatedUnchangedDocumentDiagnosticReport("result-id");
        org.junit.jupiter.api.Assertions.assertNull(
                DiagnosticMapper.mapReport(new org.eclipse.lsp4j.DocumentDiagnosticReport(unchanged)));
        org.junit.jupiter.api.Assertions.assertTrue(
                DiagnosticMapper.mapReport(null).isEmpty()); // null report → empty (clears)
    }
}
