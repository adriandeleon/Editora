package com.editora.lsp;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.editora.i18n.Messages;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemLabelDetails;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DiagnosticTag;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
}
