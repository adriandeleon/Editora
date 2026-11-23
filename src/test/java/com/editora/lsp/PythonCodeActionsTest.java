package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.editora.i18n.Messages;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pyright 1.1.411 offers no quick fix at all: Code Actions on {@code "Path" is not defined}, an unused import or a
 * type error were empty (Python review 2 E102). These are the ones Editora adds.
 */
class PythonCodeActionsTest {

    private static final String URI = "file:///p/app.py";

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static Diagnostic diag(int line, int from, int to, String message, DiagnosticSeverity sev, String code) {
        Diagnostic d = new Diagnostic(new Range(new Position(line, from), new Position(line, to)), message);
        d.setSeverity(sev);
        d.setSource("Pyright");
        if (code != null) {
            d.setCode(code);
        }
        return d;
    }

    private static Diagnostic unused(String text, int line, String name) {
        int col = text.lines().toList().get(line).lastIndexOf(name);
        Diagnostic d =
                diag(line, col, col + name.length(), "\"" + name + "\" is not accessed", DiagnosticSeverity.Hint, null);
        d.setTags(List.of(DiagnosticTag.Unnecessary));
        return d;
    }

    /** Applies an action's edits to {@code text} (last first, as a workspace edit lands). */
    private static String apply(String text, CodeAction action) {
        List<TextEdit> edits = new ArrayList<>(action.getEdit().getChanges().get(URI));
        edits.sort(Comparator.comparingInt(
                        (TextEdit e) -> offset(text, e.getRange().getStart()))
                .reversed());
        StringBuilder sb = new StringBuilder(text);
        for (TextEdit e : edits) {
            sb.replace(
                    offset(text, e.getRange().getStart()),
                    offset(text, e.getRange().getEnd()),
                    e.getNewText());
        }
        return sb.toString();
    }

    private static int offset(String text, Position p) {
        int at = 0;
        for (int i = 0; i < p.getLine(); i++) {
            at = text.indexOf('\n', at) + 1;
        }
        return at + p.getCharacter();
    }

    private static String removed(String text, int line, String name) {
        CodeAction a = PythonCodeActions.removeUnusedImport(URI, text, unused(text, line, name));
        assertTrue(a != null, "an action for " + name);
        assertEquals("Remove unused import " + name, a.getTitle());
        return apply(text, a);
    }

    @Test
    void removesAWholeImportLine() {
        assertEquals("import os\nx = os\n", removed("import os\nimport sys\nx = os\n", 1, "sys"));
        assertEquals("import os\n", removed("import os\nfrom typing import Any\n", 1, "Any"));
    }

    @Test
    void removesOneNameOfSeveral() {
        assertEquals("import os\n", removed("import os, sys\n", 0, "sys"));
        assertEquals("import sys\n", removed("import os, sys\n", 0, "os"));
        assertEquals("from a import x, z\n", removed("from a import x, y, z\n", 0, "y"));
        assertEquals("from a import (x)\n", removed("from a import (x, y)\n", 0, "y"));
        assertEquals("from a import x  # keep\n", removed("from a import x, y as w  # keep\n", 0, "w"));
    }

    @Test
    void removesFromAParenthesizedListOverLines() {
        String text = "from a import (\n    x,\n    y,\n)\nprint(x)\n";
        assertEquals("from a import (\n    x,\n)\nprint(x)\n", removed(text, 2, "y"));
        assertEquals("print(x)\n", removed("from a import (\n    y,\n)\nprint(x)\n", 1, "y"));
    }

    @Test
    void leavesAloneWhatItCannotReadSafely() {
        assertNull(PythonCodeActions.removeUnusedImport(
                URI, "import os, \\\n    sys\n", unused("import os, \\\n    sys\n", 0, "os")));
        Diagnostic local = unused("def f():\n    x = 1\n", 1, "x");
        assertNull(PythonCodeActions.removeUnusedImport(URI, "def f():\n    x = 1\n", local), "not an import");
    }

    @Test
    void ignoresARuleAtTheEndOfTheLine() {
        String text = "x: int = \"a\"\n";
        Diagnostic d = diag(0, 9, 12, "Type is not assignable", DiagnosticSeverity.Error, "reportAssignmentType");
        CodeAction a = PythonCodeActions.ignoreAction(URI, text, d);
        assertTrue(a != null);
        assertEquals("Ignore reportAssignmentType on this line", a.getTitle());
        assertEquals("x: int = \"a\"  # pyright: ignore[reportAssignmentType]\n", apply(text, a));
    }

    @Test
    void addsTheRuleToTheLinesIgnoreComment() {
        String text = "y = f(x)  # pyright: ignore[reportCallIssue]\n";
        Diagnostic d = diag(0, 4, 8, "bad", DiagnosticSeverity.Error, "reportArgumentType");
        CodeAction a = PythonCodeActions.ignoreAction(URI, text, d);
        assertTrue(a != null);
        assertEquals("y = f(x)  # pyright: ignore[reportCallIssue, reportArgumentType]\n", apply(text, a));
        Diagnostic same = diag(0, 4, 8, "bad", DiagnosticSeverity.Error, "reportCallIssue");
        assertNull(PythonCodeActions.ignoreAction(URI, text, same), "already ignored");
        assertNull(PythonCodeActions.ignoreAction(URI, "y = f(x)  # pyright: ignore\n", d), "a bare ignore covers it");
    }

    @Test
    void noIgnoreWhereACommentCannotGo() {
        String text = "s = \"\"\"start {x\n\"\"\"\n";
        Diagnostic d = diag(0, 4, 8, "bad", DiagnosticSeverity.Error, "reportUndefinedVariable");
        assertNull(PythonCodeActions.ignoreAction(URI, text, d), "the line ends inside a triple-quoted string");
        Diagnostic hint = diag(0, 0, 1, "\"s\" is not accessed", DiagnosticSeverity.Hint, null);
        assertNull(PythonCodeActions.ignoreAction(URI, "s = 1\n", hint), "a hint names no rule");
    }

    @Test
    void anUndefinedNameIsImportedWithPyrightsOwnEdit() {
        Diagnostic d = diag(2, 4, 8, "\"Path\" is not defined", DiagnosticSeverity.Error, "reportUndefinedVariable");
        assertEquals("Path", PythonCodeActions.undefinedName(d));

        CompletionItem pathlib = new CompletionItem("Path");
        CompletionItemLabelDetails details = new CompletionItemLabelDetails();
        details.setDescription("pathlib");
        pathlib.setLabelDetails(details);
        pathlib.setAdditionalTextEdits(
                List.of(new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "from pathlib import Path\n")));
        CompletionItem other = new CompletionItem("PathLike");
        List<CompletionItem> picked = PythonCodeActions.importCandidates(List.of(other, pathlib), "Path");
        assertEquals(List.of(pathlib), picked);

        CodeAction a = PythonCodeActions.importAction(URI, "Path", pathlib, d, true);
        assertTrue(a != null);
        assertEquals("Import Path from pathlib", a.getTitle());
        assertEquals("quickfix", a.getKind());
        assertTrue(a.getIsPreferred());
        assertEquals(
                "from pathlib import Path\nimport os\np = Path(\"a\")\n", apply("import os\np = Path(\"a\")\n", a));
        assertNull(
                PythonCodeActions.importAction(URI, "Path", new CompletionItem("Path"), d, false), "no edit, no row");
    }

    @Test
    void theLightbulbAsksOnlyForItsKinds() {
        assertTrue(PythonCodeActions.wanted("quickfix", null));
        assertTrue(PythonCodeActions.wanted(BashCodeActions.SUPPRESS_KIND, List.of("quickfix", "quickassist")));
        assertFalse(PythonCodeActions.wanted("quickfix", List.of("source.organizeImports")));
    }

    private static String stdImport(String text, String name) {
        Diagnostic d = diag(
                0,
                0,
                name.length(),
                "\"" + name + "\" is not defined",
                DiagnosticSeverity.Error,
                "reportUndefinedVariable");
        CodeAction a = PythonCodeActions.stdlibImport(URI, text, name, d);
        assertTrue(a != null, "an import for " + name);
        return apply(text, a);
    }

    /** Pyright imports only from modules already in the import graph: Path in a fresh file had no fix (E102). */
    @Test
    void standardNamesPyrightCannotImportAreImportedFromTheirModule() {
        assertEquals(
                "import os\nfrom pathlib import Path\n\np = Path(\"a\")\n",
                stdImport("import os\n\np = Path(\"a\")\n", "Path"));
        assertEquals(
                "from pathlib import PurePath, Path\np = Path()\n",
                stdImport("from pathlib import PurePath\np = Path()\n", "Path"));
        assertEquals(
                "\"\"\"Doc.\"\"\"\nimport os\n\nx = os.getcwd()\n",
                stdImport("\"\"\"Doc.\"\"\"\nx = os.getcwd()\n", "os"));
        assertEquals(
                "from __future__ import annotations\nfrom collections import OrderedDict\n\nd = OrderedDict()\n",
                stdImport("from __future__ import annotations\n\nd = OrderedDict()\n", "OrderedDict"));
        assertEquals("import sys\n", stdImport("", "sys"));
        assertNull(PythonCodeActions.stdlibImport(
                URI, "x = frobnicate\n", "frobnicate", diag(0, 4, 14, "x", DiagnosticSeverity.Error, null)));
    }

    // --- the quick fixes asked of a running server (an in-process fake) --------------------------------------

    private static final String SOURCE = "p = Path('a')\nimport os, sys\nx = os.getcwd() + 1\n";

    private record Served(LanguageServerSession session, FakeLanguageServer fake) {}

    private static Served served(String text) {
        var caps = new ServerCapabilities();
        caps.setCompletionProvider(new CompletionOptions(true, List.of()));
        var spec =
                new LspServerRegistry.ServerSpec(PythonCodeActions.SERVER_ID, List.of("pyright-langserver"), List.of());
        var session = new LanguageServerSession(spec, java.nio.file.Path.of("/p"), d -> {}, (type, message) -> {});
        var fake = new FakeLanguageServer();
        session.attachForTest(fake, caps);
        session.didOpen(URI, "python", text);
        return new Served(session, fake);
    }

    private static Diagnostic undefined(int line, int from, String name) {
        return diag(
                line,
                from,
                from + name.length(),
                "\"" + name + "\" is not defined",
                DiagnosticSeverity.Error,
                "reportUndefinedVariable");
    }

    /** As {@link #undefined}, without a rule: no ignore action is offered for it. */
    private static Diagnostic bare(int line, int from, String name) {
        Diagnostic d = undefined(line, from, name);
        d.setCode((String) null);
        return d;
    }

    private static CompletionItem candidate(String label, String module, boolean withEdit) {
        CompletionItem c = new CompletionItem(label);
        if (module != null) {
            CompletionItemLabelDetails details = new CompletionItemLabelDetails();
            details.setDescription(module);
            c.setLabelDetails(details);
        }
        if (withEdit) {
            c.setAdditionalTextEdits(List.of(new TextEdit(
                    new Range(new Position(0, 0), new Position(0, 0)),
                    "from " + (module == null ? "x" : module) + " import " + label + "\n")));
        }
        return c;
    }

    private static List<LspManager.CodeActionItem> quickFixes(
            Served s, List<Diagnostic> context, List<String> only, List<LspManager.CodeActionItem> items)
            throws Exception {
        return PythonCodeActions.withQuickFixes(s.session(), URI, items, context, only, Map.of(), 42L)
                .get(30, java.util.concurrent.TimeUnit.SECONDS);
    }

    private static List<String> titles(List<LspManager.CodeActionItem> items) {
        return items.stream().map(LspManager.CodeActionItem::title).toList();
    }

    @Test
    void theServersImportsComeAfterItsOwnActionsThenTheLocalFixes() throws Exception {
        Served s = served(SOURCE);
        s.fake().completionFuture = CompletableFuture.completedFuture(Either.forLeft(List.of(
                candidate("Path", "pathlib", true),
                candidate("PathLike", "os", true),
                candidate("Path", "mylib.paths", false))));
        s.fake().completionResolver = c -> {
            c.setAdditionalTextEdits(List.of(
                    new TextEdit(new Range(new Position(0, 0), new Position(0, 0)), "from mylib.paths import Path\n")));
            return c;
        };
        Diagnostic unusedSys = unused(SOURCE, 1, "sys");
        Diagnostic operator =
                diag(2, 4, 19, "Operator \"+\" not supported", DiagnosticSeverity.Error, "reportOperatorIssue");
        var own = new LspManager.CodeActionItem("Server's own", "quickfix", false, new CodeAction("Server's own"));

        List<LspManager.CodeActionItem> out =
                quickFixes(s, List.of(undefined(0, 4, "Path"), unusedSys, operator), null, List.of(own));

        assertEquals(
                List.of(
                        "Server's own",
                        "Import Path from pathlib",
                        "Import Path from mylib.paths",
                        "Remove unused import sys",
                        "Ignore reportUndefinedVariable on this line",
                        "Ignore reportOperatorIssue on this line"),
                titles(out));
        assertTrue(out.get(1).preferred(), "the first import is the preferred fix");
        assertFalse(out.get(2).preferred());
        assertEquals(42L, out.get(1).requestedAtMillis());
        assertEquals(1, s.fake().completions.size(), "one completion round trip for the name");
        assertEquals(1, s.fake().resolvedCompletions.size(), "only the candidate without an edit is resolved");
    }

    @Test
    void onlyTheRequestedKindsAreAdded() throws Exception {
        Served s = served(SOURCE);
        List<Diagnostic> context = List.of(undefined(0, 4, "Path"), unused(SOURCE, 1, "sys"));

        assertEquals(List.of(), quickFixes(s, context, List.of("source.organizeImports"), List.of()));
        assertTrue(s.fake().completions.isEmpty(), "no import is asked for when no quick fix is wanted");

        List<LspManager.CodeActionItem> suppress =
                quickFixes(s, context, List.of(BashCodeActions.SUPPRESS_KIND), List.of());
        assertEquals(List.of("Ignore reportUndefinedVariable on this line"), titles(suppress));
    }

    @Test
    void aStandardNameIsImportedWhenTheServerOffersNone() throws Exception {
        Served s = served(SOURCE); // the fake's completion answers an empty list
        List<LspManager.CodeActionItem> out =
                quickFixes(s, List.of(bare(0, 4, "Path")), List.of("quickfix"), List.of());
        assertEquals("Import Path from pathlib", out.get(0).title());
        CodeAction a = (CodeAction) out.get(0).raw();
        assertEquals(
                "from pathlib import Path\n\n",
                a.getEdit().getChanges().get(URI).get(0).getNewText());

        List<LspManager.CodeActionItem> none =
                quickFixes(s, List.of(bare(0, 4, "frobnicate")), List.of("quickfix"), List.of());
        assertEquals(List.of(), titles(none), "a name that is no standard one gets no import");
    }

    @Test
    void aCompletionListAnswerIsReadAsAListIs() throws Exception {
        Served s = served(SOURCE);
        s.fake().completionFuture = CompletableFuture.completedFuture(
                Either.forRight(new CompletionList(false, List.of(candidate("Path", "pathlib", true)))));
        assertEquals(
                List.of("Import Path from pathlib"),
                titles(quickFixes(s, List.of(bare(0, 4, "Path")), List.of("quickfix"), List.of())));

        s.fake().completionFuture = CompletableFuture.completedFuture(Either.forRight(new CompletionList()));
        assertEquals(
                List.of("Import Path from pathlib"),
                titles(quickFixes(s, List.of(bare(0, 4, "Path")), List.of("quickfix"), List.of())),
                "an empty list falls back to the standard module");
    }

    @Test
    void aFailedCompletionLeavesTheOtherFixes() throws Exception {
        Served s = served(SOURCE);
        s.fake().completionFuture = CompletableFuture.failedFuture(new IllegalStateException("server error"));
        List<LspManager.CodeActionItem> out =
                quickFixes(s, List.of(undefined(0, 4, "Path"), unused(SOURCE, 1, "sys")), null, List.of());
        assertEquals(List.of("Remove unused import sys", "Ignore reportUndefinedVariable on this line"), titles(out));
    }

    @Test
    void atMostThreeNamesAreAskedAboutEachOnce() throws Exception {
        String text = "a = Alpha + Beta + Gamma + Delta + Alpha\n";
        Served s = served(text);
        List<Diagnostic> context = new java.util.ArrayList<>();
        for (String name : List.of("Alpha", "Beta", "Alpha", "Gamma", "Delta")) {
            context.add(undefined(0, text.indexOf(name), name));
        }
        quickFixes(s, context, List.of("quickfix"), List.of());
        assertEquals(PythonCodeActions.MAX_NAMES, s.fake().completions.size());
    }

    // --- the pure parts' edge cases ------------------------------------------------------------------------

    @Test
    void noTextNoLocalFixesAndBrokenDiagnosticsAreSkipped() {
        assertEquals(List.of(), PythonCodeActions.localFixes(URI, null, List.of(unused("import os\n", 0, "os"))));
        Diagnostic noRange = new Diagnostic();
        noRange.setMessage("\"os\" is not accessed");
        List<Diagnostic> context = new java.util.ArrayList<>();
        context.add(null);
        context.add(noRange);
        context.add(unused("import os\n", 0, "os"));
        context.add(unused("import os\n", 0, "os"));
        List<CodeAction> out = PythonCodeActions.localFixes(URI, "import os\n", context);
        assertEquals(1, out.size(), "the same removal is offered once");
        assertEquals("Remove unused import os", out.get(0).getTitle());
    }

    @Test
    void anUndefinedNameIsReadOnlyFromAnErrorsMessage() {
        assertNull(PythonCodeActions.undefinedName(null));
        Diagnostic hint = undefined(0, 0, "Path");
        hint.setSeverity(DiagnosticSeverity.Hint);
        assertNull(PythonCodeActions.undefinedName(hint), "a hint");
        assertNull(PythonCodeActions.undefinedName(
                diag(0, 0, 1, "Import \"x\" could not be resolved", DiagnosticSeverity.Error, null)));
        Diagnostic markup = new Diagnostic(new Range(new Position(0, 0), new Position(0, 4)), "");
        markup.setMessage(new MarkupContent("plaintext", "\"Path\" is not defined"));
        assertEquals("Path", PythonCodeActions.undefinedName(markup), "a markup message is read too");
        Diagnostic none = new Diagnostic();
        none.setRange(new Range(new Position(0, 0), new Position(0, 1)));
        assertNull(PythonCodeActions.undefinedName(none), "no message");
    }

    @Test
    void importCandidatesSkipWhatCannotImportAndStopAtTheLimit() {
        List<CompletionItem> items = new java.util.ArrayList<>();
        items.add(null);
        items.add(candidate("Path", null, false)); // no edit, no module: nothing to import from
        items.add(candidate("Path", "  ", false)); // a blank module is none
        for (int i = 0; i < PythonCodeActions.MAX_IMPORTS + 2; i++) {
            items.add(candidate("Path", "m" + i, i % 2 == 0));
        }
        List<CompletionItem> picked = PythonCodeActions.importCandidates(items, "Path");
        assertEquals(PythonCodeActions.MAX_IMPORTS, picked.size());
        assertEquals("m0", picked.get(0).getLabelDetails().getDescription());
    }

    @Test
    void anImportWithoutAModuleIsABareImport() {
        Diagnostic d = undefined(0, 0, "os");
        CodeAction bare = PythonCodeActions.importAction(URI, "os", candidate("os", null, true), d, false);
        assertNotNull(bare);
        assertEquals("Import os", bare.getTitle());
        assertFalse(bare.getIsPreferred());
        assertNull(PythonCodeActions.importAction(URI, "os", null, d, true));
        CompletionItem empty = candidate("os", "x", false);
        empty.setAdditionalTextEdits(List.of());
        assertNull(PythonCodeActions.importAction(URI, "os", empty, d, true), "an empty edit list");
        assertNull(PythonCodeActions.stdlibImport(URI, null, "os", d), "no document text");
    }

    @Test
    void aStandardImportGoesAboveCodeAndPastIndentedAndContinuedLines() {
        assertEquals("import os\n\nx = 1\n", stdImport("x = 1\n", "os"));
        assertEquals(
                "import sys\nimport os\nif x:\n    import re\n", stdImport("import sys\nif x:\n    import re\n", "os"));
        assertEquals("from a import (\n    b,\n)\nimport os\n", stdImport("from a import (\n    b,\n)\n", "os"));
        assertEquals(
                "from pathlib import (PurePath)\nfrom pathlib import Path\n",
                stdImport("from pathlib import (PurePath)\n", "Path"),
                "a parenthesized import is not extended");
        assertEquals("import sys\nimport os\n", stdImport("import sys", "os"), "a last line without its break");
    }

    @Test
    void removalNeedsAnUnnecessaryImportHintOnOneLine() {
        String text = "import os, sys\n";
        Diagnostic noTags = unused(text, 0, "sys");
        noTags.setTags(null);
        assertNull(PythonCodeActions.removeUnusedImport(URI, text, noTags));
        Diagnostic deprecated = unused(text, 0, "sys");
        deprecated.setTags(List.of(DiagnosticTag.Deprecated));
        assertNull(PythonCodeActions.removeUnusedImport(URI, text, deprecated));
        Diagnostic other = unused(text, 0, "sys");
        other.setMessage("\"sys\" is possibly unbound");
        assertNull(PythonCodeActions.removeUnusedImport(URI, text, other));
        Diagnostic twoLines = unused(text, 0, "sys");
        twoLines.getRange().setEnd(new Position(1, 0));
        assertNull(PythonCodeActions.removeUnusedImport(URI, text, twoLines));
        Diagnostic past = unused(text, 0, "sys");
        past.setRange(new Range(new Position(5, 0), new Position(5, 3)));
        assertNull(PythonCodeActions.removeUnusedImport(URI, text, past), "a line past the end");
        Diagnostic module = unused(text, 0, "sys");
        module.setRange(new Range(new Position(0, 0), new Position(0, 3)));
        assertNull(PythonCodeActions.removeUnusedImport(URI, text, module), "on the keyword, not a name");
    }

    @Test
    void anImportItCannotReadWholeIsLeftAlone() {
        String open = "from a import (x,\n    y)\n";
        assertNull(PythonCodeActions.removeUnusedImport(URI, open, unused(open, 0, "x")), "a list going on below");
        String call = "x = f(\n    y,\n)\n";
        assertNull(PythonCodeActions.removeUnusedImport(URI, call, unused(call, 1, "y")), "not a from-import");
        String closed = "from a import (\n    b)\n    y,\n)\n";
        assertNull(PythonCodeActions.removeUnusedImport(URI, closed, unused(closed, 2, "y")), "a list closed above");
        String unclosed = "from a import (\n    y,\n";
        assertNull(PythonCodeActions.removeUnusedImport(URI, unclosed, unused(unclosed, 1, "y")), "never closed");
        String expr = "from a import (\n    y + 1,\n)\n";
        Diagnostic d = unused(expr, 1, "y");
        assertNull(PythonCodeActions.removeUnusedImport(URI, expr, d), "no plain name on the line");
        String noOpen = "\n    y,\n)\n";
        assertNull(PythonCodeActions.removeUnusedImport(URI, noOpen, unused(noOpen, 1, "y")), "no opening line");
    }

    @Test
    void theLastLinesImportGoesWithTheBreakBeforeIt() {
        assertEquals("import os", removed("import os\nimport sys", 1, "sys"));
        assertEquals(
                "x = 1\nfrom a import (\n    x,\n    y,\n)",
                removed("x = 1\nfrom a import (\n    x,\n    y,\n    z,\n)", 4, "z"));
    }

    @Test
    void anIgnoreNeedsARuleOnOneLineOfCode() {
        String text = "x: int = \"a\"\r\ny = 2\n";
        Diagnostic numeric = diag(0, 0, 1, "bad", DiagnosticSeverity.Error, null);
        numeric.setCode(Either.forRight(7));
        assertNull(PythonCodeActions.rule(numeric), "a number is no rule");
        assertNull(PythonCodeActions.rule(diag(0, 0, 1, "bad", DiagnosticSeverity.Error, "E501")), "not Pyright's");
        assertNull(PythonCodeActions.ignoreAction(URI, text, numeric));

        Diagnostic twoLines = diag(0, 0, 1, "bad", DiagnosticSeverity.Error, "reportGeneralTypeIssues");
        twoLines.getRange().setEnd(new Position(1, 0));
        assertNull(PythonCodeActions.ignoreAction(URI, text, twoLines));
        Diagnostic past = diag(9, 0, 1, "bad", DiagnosticSeverity.Error, "reportGeneralTypeIssues");
        assertNull(PythonCodeActions.ignoreAction(URI, text, past), "a line past the end");

        CodeAction crlf = PythonCodeActions.ignoreAction(
                URI, text, diag(0, 9, 12, "bad", DiagnosticSeverity.Warning, "reportAssignmentType"));
        assertNotNull(crlf);
        TextEdit edit = crlf.getEdit().getChanges().get(URI).get(0);
        assertEquals(new Range(new Position(0, 12), new Position(0, 12)), edit.getRange(), "before the \\r");

        String continued = "x = 1 + \\\n    2\n";
        assertNull(PythonCodeActions.ignoreAction(
                URI, continued, diag(0, 0, 1, "bad", DiagnosticSeverity.Error, "reportGeneralTypeIssues")));
    }

    @Test
    void anEmptyRuleListGetsTheRuleWithoutAComma() {
        String text = "y = f(x)  # pyright: ignore[]\n";
        CodeAction a = PythonCodeActions.ignoreAction(
                URI, text, diag(0, 4, 8, "bad", DiagnosticSeverity.Information, "reportCallIssue"));
        assertNotNull(a);
        assertEquals("y = f(x)  # pyright: ignore[reportCallIssue]\n", apply(text, a));
    }
}
