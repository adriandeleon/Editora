package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.i18n.Messages;
import com.editora.lsp.LspManager;
import com.editora.text.LspDiagnostic;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The coordinator's pure decisions: labels, the lightbulb's kind, the problem to go to, the word at the caret. */
class LspCoordinatorTest {

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void aProjectValueIsNamedAsSettingsShowsIt() {
        assertEquals("Maven command", LspCoordinator.scopeLabel("build.maven"));
        assertEquals("Gradle command", LspCoordinator.scopeLabel("build.gradle"));
        assertEquals("make command", LspCoordinator.scopeLabel("build.make"), "an unknown tool keeps its id");
        assertEquals("Java debugger", LspCoordinator.scopeLabel("debug.java"));
        assertEquals("Python debugger", LspCoordinator.scopeLabel("debug.python"));
        assertEquals("JavaScript debugger", LspCoordinator.scopeLabel("debug.javascript"));
        assertEquals("go debugger", LspCoordinator.scopeLabel("debug.go"));
        assertEquals("Project JDK", LspCoordinator.scopeLabel("toolchain.jdk"));
        assertEquals("Project Maven", LspCoordinator.scopeLabel("toolchain.maven"));
        assertEquals("Project Gradle", LspCoordinator.scopeLabel("toolchain.gradle"));
        assertEquals("Project node", LspCoordinator.scopeLabel("toolchain.node"));
        assertEquals("Tab size", LspCoordinator.scopeLabel("editor.tabSize"));
        assertEquals("Tab size", LspCoordinator.scopeLabel("tabSize"), "a key without a section");
    }

    @Test
    void aRequestIsNamedByItsLabelElseByItsMethod() {
        assertEquals("textDocument/notARealMethod", LspCoordinator.methodLabel("textDocument/notARealMethod"));
        assertFalse(LspCoordinator.methodLabel("hover").isBlank());
    }

    @Test
    void rootsAreTheSameAfterNormalizing() {
        assertTrue(LspCoordinator.sameLspRoot(Path.of("/p/a/../b"), Path.of("/p/b")));
        assertFalse(LspCoordinator.sameLspRoot(Path.of("/p/a"), Path.of("/p/b")));
        assertFalse(LspCoordinator.sameLspRoot(null, Path.of("/p/b")));
        assertFalse(LspCoordinator.sameLspRoot(Path.of("/p/b"), null));
    }

    private static LspManager.CodeActionItem fix(String title, DiagnosticSeverity... severities) {
        CodeAction action = new CodeAction(title);
        action.setKind("quickfix");
        if (severities.length > 0) {
            List<Diagnostic> ds = new java.util.ArrayList<>();
            for (DiagnosticSeverity s : severities) {
                Diagnostic d = new Diagnostic(new Range(new Position(0, 0), new Position(0, 1)), "x");
                d.setSeverity(s);
                ds.add(d);
            }
            action.setDiagnostics(ds);
        }
        return new LspManager.CodeActionItem(title, "quickfix", false, action);
    }

    @Test
    void theBulbIsAFixOnlyForAnErrorsFix() {
        assertEquals("fix", LspCoordinator.bulbKind(List.of(fix("Import List"))), "a fix naming no diagnostic");
        assertEquals("fix", LspCoordinator.bulbKind(List.of(fix("Add cast", DiagnosticSeverity.Error))));
        assertEquals(
                "fix",
                LspCoordinator.bulbKind(List.of(fix("Add cast", DiagnosticSeverity.Warning, null))),
                "a diagnostic without a severity counts as an error");
        assertEquals(
                "assist",
                LspCoordinator.bulbKind(List.of(fix("Suppress", DiagnosticSeverity.Warning))),
                "a warning's fix is an assist");
        LspManager.CodeActionItem empty =
                new LspManager.CodeActionItem("Fix", "quickfix", false, new CodeAction("Fix"));
        ((CodeAction) empty.raw()).setDiagnostics(List.of());
        assertEquals("fix", LspCoordinator.bulbKind(List.of(empty)), "an empty diagnostics list names none");
        assertEquals(
                "fix",
                LspCoordinator.bulbKind(List.of(new LspManager.CodeActionItem(
                        "Run", "quickfix", false, new org.eclipse.lsp4j.Command("Run", "x")))),
                "a bare command");
        assertNull(LspCoordinator.bulbKind(List.of(new LspManager.CodeActionItem(
                "Add Javadoc comment", "quickassist", false, new CodeAction("Add Javadoc comment")))));
        assertNull(LspCoordinator.bulbKind(List.of(new LspManager.CodeActionItem(
                "Organize imports", "source.organizeImports", false, new CodeAction("Organize imports")))));
        assertEquals(
                "assist",
                LspCoordinator.bulbKind(List.of(new LspManager.CodeActionItem(
                        "Invert if", "quickassist", false, new CodeAction("Invert if")))));
        assertNull(LspCoordinator.bulbKind(List.of()));
        assertEquals("Move 'Strings'", LspCoordinator.bareTitle(" Move 'Strings'… "));
        assertEquals("Move", LspCoordinator.bareTitle("Move..."));
    }

    private static LspDiagnostic at(int line, int col) {
        return new LspDiagnostic(line, col, line, col + 1, LspDiagnostic.Severity.ERROR, "m", null, "s");
    }

    @Test
    void theNextProblemWrapsEitherWay() {
        List<LspDiagnostic> sorted = List.of(at(2, 4), at(2, 9), at(7, 0));
        assertSame(sorted.get(1), LspCoordinator.nextProblem(sorted, 2, 4, true), "later on the same line");
        assertSame(sorted.get(2), LspCoordinator.nextProblem(sorted, 3, 0, true));
        assertSame(sorted.get(0), LspCoordinator.nextProblem(sorted, 9, 0, true), "wraps to the first");
        assertSame(sorted.get(0), LspCoordinator.nextProblem(sorted, 2, 9, false), "earlier on the same line");
        assertSame(sorted.get(1), LspCoordinator.nextProblem(sorted, 5, 0, false));
        assertSame(sorted.get(2), LspCoordinator.nextProblem(sorted, 1, 0, false), "wraps to the last");
    }

    @Test
    void theWordAroundTheCaretIsAnIdentifierRun() {
        assertEquals("count", LspCoordinator.wordAt("int count = 1;", 6));
        assertEquals("count", LspCoordinator.wordAt("int count = 1;", 9), "at its end");
        assertEquals("count", LspCoordinator.wordAt("int count = 1;", 4), "at its start");
        assertEquals("", LspCoordinator.wordAt("a = b", 2), "between words");
        assertEquals("b", LspCoordinator.wordAt("a = b", 99), "past the end: the last word");
        assertEquals("a", LspCoordinator.wordAt("a = b", -3));
        assertEquals("", LspCoordinator.wordAt("", 0));
        assertEquals("", LspCoordinator.wordAt(null, 0));
    }

    @Test
    void anEmptyBufferIsReadFromItsFileForThePackageLine(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("A.java"), "package a.b;\nclass A {}\n");
        assertEquals("package x;", LspCoordinator.packageSource("package x;", file), "loaded text is used as is");
        assertEquals("package a.b;\nclass A {}\n", LspCoordinator.packageSource("  ", file));
        Path empty = Files.writeString(dir.resolve("B.java"), "");
        assertEquals("", LspCoordinator.packageSource("", empty));
        assertEquals("", LspCoordinator.packageSource("", dir.resolve("missing.java")), "unreadable: what it had");
    }
}
