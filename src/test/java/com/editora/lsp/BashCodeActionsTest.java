package com.editora.lsp;

import java.util.List;
import java.util.Map;

import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** ShellCheck's quick fixes titled by what they do, suppressions apart and made where 5.6 has none (C10, B13). */
class BashCodeActionsTest {

    @org.junit.jupiter.api.BeforeAll
    static void catalog() {
        com.editora.i18n.Messages.init("en"); // real text, so a wrong argument shows
    }

    private static final String URI = "file:///p/main.sh";
    private static final String TEXT = "#!/bin/bash\nfor f in $(ls); do\n    echo $a $b\ndone\n";

    private static Diagnostic shellcheck(String code, String message, int line, int col) {
        Diagnostic d = new Diagnostic(new Range(new Position(line, col), new Position(line, col + 2)), message);
        d.setSource("shellcheck");
        d.setCode(code);
        return d;
    }

    private static LspManager.CodeActionItem fix(Diagnostic d) {
        CodeAction a = new CodeAction("Apply fix for " + d.getCode().getLeft());
        a.setKind("quickfix");
        a.setDiagnostics(List.of(d));
        return new LspManager.CodeActionItem(a.getTitle(), "quickfix", false, a);
    }

    private static LspManager.CodeActionItem disable(String code, String scope) {
        CodeAction a = new CodeAction("Disable ShellCheck rule " + code + " for " + scope);
        a.setKind("quickfix");
        return new LspManager.CodeActionItem(a.getTitle(), "quickfix", false, a);
    }

    private static List<LspManager.CodeActionItem> shape(List<LspManager.CodeActionItem> items, List<Diagnostic> ctx) {
        return BashCodeActions.shape(items, ctx, URI, TEXT, Map.of(), 0);
    }

    @Test
    void aFixIsTitledByItsDiagnosticAndTwoAlikeByTheirColumns() {
        Diagnostic a = shellcheck("SC2086", "Double quote to prevent globbing and word splitting.", 2, 9);
        Diagnostic b = shellcheck("SC2086", "Double quote to prevent globbing and word splitting.", 2, 12);
        List<LspManager.CodeActionItem> out = shape(List.of(fix(a), fix(b)), List.of());
        String message = "Double quote to prevent globbing and word splitting";
        assertEquals(
                tr("lsp.codeAction.bashFixAt", "SC2086", message, 10),
                out.get(0).title());
        assertEquals(
                tr("lsp.codeAction.bashFixAt", "SC2086", message, 13),
                out.get(1).title());
        Diagnostic c = shellcheck("SC2006", "Use $(...) notation instead of legacy backticks `...`.", 1, 9);
        assertEquals(
                tr("lsp.codeAction.bashFix", "SC2006", "Use $(...) notation instead of legacy backticks `...`"),
                shape(List.of(fix(c)), List.of()).get(0).title());
    }

    @Test
    void theServersSuppressionsAreMarkedAndNoneIsMadeForTheirRule() {
        Diagnostic d = shellcheck("SC2086", "Double quote.", 2, 9);
        List<LspManager.CodeActionItem> out = shape(
                List.of(fix(d), disable("SC2086", "this command"), disable("SC2086", "the entire file")), List.of(d));
        assertEquals(3, out.size(), "5.8 offers its own: nothing is added");
        assertEquals("quickfix", out.get(0).kind());
        assertEquals(BashCodeActions.SUPPRESS_KIND, out.get(1).kind());
        assertEquals(
                CodeActionKinds.Group.SUPPRESS, CodeActionKinds.group(out.get(2).kind()));
        List<LspManager.CodeActionItem> arranged = CodeActionKinds.arrange(List.of(out.get(1), out.get(0)), i -> true);
        assertEquals("quickfix", arranged.get(0).kind(), "the fix before its suppressions");
    }

    @Test
    void withoutTheServersSuppressionOneIsMadeAboveTheLineAtItsIndent() {
        Diagnostic d = shellcheck("SC2086", "Double quote.", 2, 9);
        Diagnostic same = shellcheck("SC2086", "Double quote.", 2, 12);
        Diagnostic other = shellcheck("SC2045", "Iterating over ls output is fragile.", 1, 9);
        Diagnostic notOurs = new Diagnostic(new Range(new Position(2, 0), new Position(2, 1)), "x");
        notOurs.setSource("bash-language-server");
        List<LspManager.CodeActionItem> out = shape(List.of(), List.of(d, same, other, notOurs));
        assertEquals(
                List.of(tr("lsp.codeAction.bashDisable", "SC2086"), tr("lsp.codeAction.bashDisable", "SC2045")),
                out.stream().map(LspManager.CodeActionItem::title).toList());
        TextEdit edit =
                ((CodeAction) out.get(0).raw()).getEdit().getChanges().get(URI).get(0);
        assertEquals(new Position(2, 0), edit.getRange().getStart());
        assertEquals("    # shellcheck disable=SC2086\n", edit.getNewText());
        assertEquals(BashCodeActions.SUPPRESS_KIND, out.get(1).kind());
        assertNull(BashCodeActions.disableAction(URI, TEXT, 40, "SC2086", d), "a line not in the text");
        assertNull(BashCodeActions.shellcheckCode(notOurs));
    }
}
