package com.editora.lsp;

import java.util.List;

import com.editora.text.LspDiagnostic;
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

    /**
     * ShellCheck links each code to its wiki page and tags an unused variable Unnecessary; both reach the editor
     * now (shell review C9). Only a web address is kept as a link.
     */
    @org.junit.jupiter.api.Test
    void theRulesDocumentationLinkAndTheUnnecessaryTagAreKept() {
        var range =
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 0), new org.eclipse.lsp4j.Position(1, 6));
        var unused = new org.eclipse.lsp4j.Diagnostic(
                range, "unused appears unused.", org.eclipse.lsp4j.DiagnosticSeverity.Warning, "shellcheck", "SC2034");
        unused.setCodeDescription(
                new org.eclipse.lsp4j.DiagnosticCodeDescription("https://www.shellcheck.net/wiki/SC2034"));
        unused.setTags(java.util.List.of(org.eclipse.lsp4j.DiagnosticTag.Unnecessary));
        var local =
                new org.eclipse.lsp4j.Diagnostic(range, "x", org.eclipse.lsp4j.DiagnosticSeverity.Hint, "lint", "L1");
        local.setCodeDescription(new org.eclipse.lsp4j.DiagnosticCodeDescription("file:///etc/passwd"));

        var mapped = DiagnosticMapper.map(java.util.List.of(unused, local));

        org.junit.jupiter.api.Assertions.assertEquals(
                "https://www.shellcheck.net/wiki/SC2034", mapped.get(0).href());
        org.junit.jupiter.api.Assertions.assertTrue(mapped.get(0).unnecessary());
        org.junit.jupiter.api.Assertions.assertNull(mapped.get(1).href(), "only a web page is a link");
        org.junit.jupiter.api.Assertions.assertFalse(mapped.get(1).unnecessary());
    }

    /** A call to a {@code @deprecated} function is tagged Deprecated, drawn struck through (Python review B3). */
    @org.junit.jupiter.api.Test
    void theDeprecatedTagIsKept() {
        var range = new org.eclipse.lsp4j.Range(
                new org.eclipse.lsp4j.Position(3, 4), new org.eclipse.lsp4j.Position(3, 12));
        var old = new org.eclipse.lsp4j.Diagnostic(
                range, "The function \"old_func\" is deprecated", org.eclipse.lsp4j.DiagnosticSeverity.Hint, "Pyright");
        old.setTags(java.util.List.of(org.eclipse.lsp4j.DiagnosticTag.Deprecated));

        var mapped = DiagnosticMapper.map(java.util.List.of(old)).get(0);

        org.junit.jupiter.api.Assertions.assertTrue(mapped.deprecated());
        org.junit.jupiter.api.Assertions.assertFalse(mapped.unnecessary());
        org.junit.jupiter.api.Assertions.assertTrue(mapped.tagOnlyHint(), "a tag carried as a hint is not a problem");
    }

    /** lemminx's messages end on a "Code:" that VS Code completes with the code; here it dangled (XML review B16). */
    @Test
    void lemminxsDanglingCodeTailIsCut() {
        String enumeration = "Value 'urgent' is not in the enumeration list.\n\nIt must be one of the following:\n"
                + " - low\n - normal\n - high\n\nCode:";
        org.junit.jupiter.api.Assertions.assertEquals(
                "Value 'urgent' is not in the enumeration list.\n\nIt must be one of the following:\n"
                        + " - low\n - normal\n - high",
                DiagnosticMapper.xmlMessage("xml", enumeration));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Invalid element name:\n - from\n\nOne of the following is expected:\n - to\n\n"
                        + "Error indicated by:\n {the schema}",
                DiagnosticMapper.xmlMessage(
                        "xml",
                        "Invalid element name:\n - from\n\nOne of the following is expected:\n - to\n\n"
                                + "Error indicated by:\n {the schema}\nwith code:"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "Ends in error code:",
                DiagnosticMapper.xmlMessage("xml", "Ends in error code:"),
                "not on its own line");
        org.junit.jupiter.api.Assertions.assertEquals(
                enumeration, DiagnosticMapper.xmlMessage("other", enumeration), "only lemminx's messages");

        var d = new org.eclipse.lsp4j.Diagnostic(
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 0), new org.eclipse.lsp4j.Position(1, 5)),
                "Attribute 'id' is missing from element 'note'.\n\nCode:");
        d.setSource("xml");
        d.setCode("cvc-complex-type.4");
        var mapped = DiagnosticMapper.map(java.util.List.of(d)).get(0);
        org.junit.jupiter.api.Assertions.assertEquals(
                "Attribute 'id' is missing from element 'note'.", mapped.message());
        org.junit.jupiter.api.Assertions.assertEquals("cvc-complex-type.4", mapped.code());
    }
}
