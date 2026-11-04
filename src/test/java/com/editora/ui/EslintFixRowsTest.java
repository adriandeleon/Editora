package com.editora.ui;

import java.util.List;

import com.editora.jstools.EslintReport;
import com.editora.lsp.LspManager;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ESLint's rows in Code Actions: the caret's problems' fixes and suggestions, then Fix All (JS review F). */
class EslintFixRowsTest {

    private static EslintReport.Problem problem(
            int line, String rule, EslintReport.Fix fix, EslintReport.Suggestion... suggestions) {
        var d = new LspDiagnostic(
                line, 0, line, 3, LspDiagnostic.Severity.ERROR, "m", rule, "eslint", null, false, false);
        return new EslintReport.Problem(d, fix, List.of(suggestions));
    }

    @Test
    void theRowsAreTheCaretLinesFixesAndSuggestionsThenFixAll() {
        var fix = new EslintReport.Fix(0, 3, "let");
        var suggestion = new EslintReport.Suggestion("Remove unused variable 'y'.", new EslintReport.Fix(4, 9, ""));
        List<EslintReport.Problem> problems = List.of(
                problem(0, "no-var", fix), problem(0, "no-unused-vars", null, suggestion), problem(5, "no-var", fix));
        List<LspManager.CodeActionItem> rows = EslintCoordinator.fixRows(problems, "var y;", 0, 0);
        assertEquals(
                List.of(tr("eslint.action.fix", "no-var"), "Remove unused variable 'y'.", tr("eslint.action.fixAll")),
                rows.stream().map(LspManager.CodeActionItem::title).toList());
        assertTrue(rows.stream().allMatch(r -> r.kind().equals("quickfix")), "Code Actions keeps them, Generate not");
        assertFalse(rows.get(0).preferred(), "the server's own fixes come first");
        var action = (EslintCoordinator.Action) rows.get(0).raw();
        assertEquals("var y;", action.checkedText());
        assertEquals(fix, action.fix());
        assertEquals(null, ((EslintCoordinator.Action) rows.get(2).raw()).fix(), "Fix All has no single fix");
    }

    @Test
    void noFixAnywhereMeansNoFixAllAndLinesWithoutProblemsGetNothing() {
        List<EslintReport.Problem> problems = List.of(problem(2, "eqeqeq", null));
        assertTrue(EslintCoordinator.fixRows(problems, "", 2, 2).isEmpty());
        var fix = new EslintReport.Fix(0, 1, "x");
        assertEquals(
                List.of(tr("eslint.action.fixAll")),
                EslintCoordinator.fixRows(List.of(problem(9, "semi", fix)), "", 0, 3).stream()
                        .map(LspManager.CodeActionItem::title)
                        .toList(),
                "Fix All is offered anywhere in a file that has a fixable problem");
    }
}
