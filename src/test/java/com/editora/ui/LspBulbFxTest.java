package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;

import com.editora.editor.EditorBuffer;
import com.editora.text.LspDiagnostic;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The gutter lightbulb and the editor's problem counter (IntelliJ's intention bulb and inspection widget), against
 * a scripted server: which actions light the bulb, where it shows, when it goes, and what the counter counts.
 */
@Tag("fx")
class LspBulbFxTest {

    private static final String SOURCE = "class A {\n    void go() {\n        int x = 1;\n    }\n}\n";

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root);
        fx.capabilities.setCodeActionProvider(true);
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    private static Either<Command, CodeAction> action(String title, String kind) {
        CodeAction a = new CodeAction(title);
        a.setKind(kind);
        return Either.forRight(a);
    }

    private EditorBuffer open() throws Exception {
        EditorBuffer b = fx.open("A.java", SOURCE);
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(b));
        FxTestSupport.runOnFx(() -> b.getArea().requestFocus());
        return b;
    }

    private void probe(EditorBuffer b) throws Exception {
        FxTestSupport.runOnFx(() -> b.codeActionBulb().probe());
        fx.settle();
    }

    private static long gutterNodes(EditorBuffer b, String selector) throws Exception {
        return FxTestSupport.callOnFx(() ->
                b.getArea().lookupAll(selector).stream().filter(Node::isVisible).count());
    }

    @Test
    void aQuickFixLightsARedBulbOnTheCaretLineAndAMoveTakesItAway() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Remove 'x'", "quickfix"), action("Extract", "refactor"));
        fx.caret(b, 2, 12);
        probe(b);

        assertEquals(2, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
        assertEquals("fix", FxTestSupport.callOnFx(() -> b.codeActionBulb().kind()));
        fx.layout();
        assertEquals(1, gutterNodes(b, ".bulb-marker.fix"), "drawn in the gutter on that line");

        fx.caret(b, 1, 4); // off the line: gone at once, before any answer
        assertEquals(-1, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
        fx.layout();
        assertEquals(0, gutterNodes(b, ".bulb-marker"));
    }

    @Test
    void onlyRefactoringsOrSourceActionsLightNothingAndAQuickActionIsAnAssist() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Extract", "refactor.extract"), action("Organize", "source"));
        fx.caret(b, 2, 12);
        probe(b);
        assertEquals(
                -1,
                FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue(),
                "offered nearly everywhere: no bulb");

        fx.server().codeActionResponse = List.of(action("Add final modifier", "quickassist"));
        probe(b);
        assertEquals("assist", FxTestSupport.callOnFx(() -> b.codeActionBulb().kind()));
    }

    @Test
    void anAnswerForTextThatHasSinceChangedIsDropped() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Remove 'x'", "quickfix"));
        fx.caret(b, 2, 12);
        FxTestSupport.runOnFx(() -> {
            b.codeActionBulb().probe();
            b.getArea().insertText(b.getArea().getCaretPosition(), "y"); // typed before the answer lands
        });
        fx.settle();
        assertEquals(-1, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
    }

    @Test
    void theSettingTurnsTheBulbOff() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Remove 'x'", "quickfix"));
        fx.caret(b, 2, 12);
        FxTestSupport.runOnFx(() -> b.codeActionBulb().setEnabled(false));
        probe(b);
        assertEquals(-1, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
    }

    @Test
    void theKindOfBulbFollowsTheBestActionOffered() {
        assertEquals(
                "fix",
                LspCoordinator.bulbKind(List.of(
                        item("Extract", "refactor"), item("Add import", "quickfix"), item("Assist", "quickassist"))));
        assertEquals("assist", LspCoordinator.bulbKind(List.of(item("Convert", ""), item("Extract", "refactor"))));
        assertNull(LspCoordinator.bulbKind(List.of(item("Organize imports", "source.organizeImports"))));
        assertNull(LspCoordinator.bulbKind(List.of()));
    }

    private static com.editora.lsp.LspManager.CodeActionItem item(String title, String kind) {
        return new com.editora.lsp.LspManager.CodeActionItem(title, kind, false, title);
    }

    @Test
    void theCounterCountsErrorsAndWarningsAndShowsACheckWhenThereAreNone() throws Exception {
        EditorBuffer b = open();
        var error = new LspDiagnostic(2, 8, 2, 11, LspDiagnostic.Severity.ERROR, "bad", null, "jdtls");
        var warning = new LspDiagnostic(2, 12, 2, 13, LspDiagnostic.Severity.WARNING, "unused", null, "jdtls");
        var info = new LspDiagnostic(1, 4, 1, 8, LspDiagnostic.Severity.INFO, "note", null, "jdtls");
        FxTestSupport.runOnFx(() -> b.setLspDiagnostics(List.of(error, warning, warning, info)));
        fx.layout();

        Node counter = FxTestSupport.callOnFx(() -> b.getArea().getScene().lookup(".problem-counter"));
        assertNotNull(counter, "mounted at the top right while a server checks the file");
        assertEquals(
                com.editora.i18n.Messages.tr("editor.problemCounter.summary", 1, 2),
                FxTestSupport.callOnFx(() -> ((javafx.scene.layout.HBox) counter)
                        .getChildren()
                        .get(0)
                        .getAccessibleText()));
        assertTrue(FxTestSupport.callOnFx(
                () -> counter.lookup(".problem-counter-next").isVisible()));

        FxTestSupport.runOnFx(() -> b.setLspDiagnostics(List.of(info)));
        fx.layout();
        assertNotNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "a check: nothing to fix");
        assertFalse(
                FxTestSupport.callOnFx(
                        () -> counter.lookup(".problem-counter-next").isVisible()),
                "nowhere to go");

        FxTestSupport.runOnFx(() -> b.problemCounter().setEnabled(false));
        fx.layout();
        assertNull(FxTestSupport.callOnFx(() -> b.getArea().getScene().lookup(".problem-counter")), "the setting");
    }

    /**
     * jdtls's "Add Javadoc comment" (offered everywhere in a type) lights nothing, and a warning's fixes (its
     * suppressions) light the amber bulb, not the red one (fifth review AV1, AV6).
     */
    @Test
    void javadocAssistsLightNothingAndAWarningsFixesAreAnAssist() {
        assertNull(LspCoordinator.bulbKind(List.of(item("Add Javadoc comment", "quickassist"))));
        var warning = new org.eclipse.lsp4j.Diagnostic(
                new org.eclipse.lsp4j.Range(new org.eclipse.lsp4j.Position(1, 0), new org.eclipse.lsp4j.Position(1, 3)),
                "deprecated");
        warning.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Warning);
        CodeAction suppress = new CodeAction("Add @SuppressWarnings");
        suppress.setKind("quickfix");
        suppress.setDiagnostics(List.of(warning));
        assertEquals(
                "assist",
                LspCoordinator.bulbKind(List.of(new com.editora.lsp.LspManager.CodeActionItem(
                        "Add @SuppressWarnings", "quickfix", false, suppress))));
        var error = new org.eclipse.lsp4j.Diagnostic(warning.getRange(), "bad");
        error.setSeverity(org.eclipse.lsp4j.DiagnosticSeverity.Error);
        CodeAction fix = new CodeAction("Change type");
        fix.setKind("quickfix");
        fix.setDiagnostics(List.of(error));
        assertEquals(
                "fix",
                LspCoordinator.bulbKind(
                        List.of(new com.editora.lsp.LspManager.CodeActionItem("Change type", "quickfix", false, fix))));
    }

    /** The bulb asks only for quick fixes and quick assists (fifth review AV7). */
    @Test
    void theBulbAsksOnlyForQuickFixesAndAssists() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Remove 'x'", "quickfix"));
        fx.caret(b, 2, 12);
        int before = fx.server().codeActions.size();
        probe(b);
        var params = fx.server().codeActions.get(fx.server().codeActions.size() - 1);
        assertTrue(fx.server().codeActions.size() > before);
        assertEquals(List.of("quickfix", "quickassist"), params.getContext().getOnly());
    }

    /** An edit on the bulb's line takes it away until the next answer: it was for other text (fifth review AV5). */
    @Test
    void editingTheBulbsLineTakesItAway() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Remove 'x'", "quickfix"));
        fx.caret(b, 2, 12);
        probe(b);
        assertEquals(2, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
        FxTestSupport.runOnFx(() -> b.getArea().insertText(b.getArea().getCaretPosition(), "y"));
        assertEquals(-1, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
    }

    /**
     * The counter shows "Analyzing…" until the server has published the file's problems, not a green check, and
     * names a project that did not import (fifth review CV1).
     */
    @Test
    void theCounterSaysAnalyzingUntilTheServerHasLooked() throws Exception {
        EditorBuffer b = FxTestSupport.callOnFx(EditorBuffer::new);
        java.nio.file.Path file = java.nio.file.Files.writeString(root.resolve("B.java"), SOURCE);
        FxTestSupport.runOnFx(() -> {
            b.setPath(file);
            b.setContent(SOURCE);
            fx.show(b);
            b.setLspActive(true);
        });
        fx.layout();
        javafx.scene.Node counter =
                FxTestSupport.callOnFx(() -> b.problemCounter().counter());
        assertNotNull(counter);
        assertEquals(
                com.editora.i18n.Messages.tr("editor.problemCounter.analyzing"),
                FxTestSupport.callOnFx(() -> ((javafx.scene.layout.HBox) counter)
                        .getChildren()
                        .get(0)
                        .getAccessibleText()));
        assertNull(
                FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "no check before it has looked");

        FxTestSupport.runOnFx(
                () -> b.problemCounter().setNote(com.editora.i18n.Messages.tr("editor.problemCounter.notImported")));
        assertEquals(
                com.editora.i18n.Messages.tr("editor.problemCounter.notImported"),
                FxTestSupport.callOnFx(() -> ((javafx.scene.layout.HBox) counter)
                        .getChildren()
                        .get(0)
                        .getAccessibleText()));
        FxTestSupport.runOnFx(() -> {
            b.problemCounter().setNote(null);
            b.setLspDiagnostics(List.of());
        });
        assertNotNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "checked, and clean");
    }

    /** The counter's arrows move the caret in their own file, not the active one (fifth review AV2). */
    @Test
    void theCountersArrowsActOnTheirOwnFile() throws Exception {
        EditorBuffer first = open();
        var error = new LspDiagnostic(2, 8, 2, 11, LspDiagnostic.Severity.ERROR, "bad", null, "jdtls");
        fx.run(() -> fx.coordinator.onDiagnostics(first.getPath(), List.of(error)));
        fx.caret(first, 0, 0);
        EditorBuffer second = fx.open("C.java", "class C {}\n");
        assertTrue(FxTestSupport.callOnFx(() -> fx.host.activeBuffer() == second));
        javafx.scene.Node counter =
                FxTestSupport.callOnFx(() -> first.problemCounter().counter());
        FxTestSupport.runOnFx(() -> ((javafx.scene.control.Button) counter.lookup(".problem-counter-next")).fire());
        assertEquals(
                2,
                FxTestSupport.callOnFx(() -> first.getArea().getCurrentParagraph())
                        .intValue(),
                "its own file");
    }

    /**
     * jdtls publishes an empty list for an open file before its project is built: that is no green check until the
     * server has said it is ready, and a list with problems counts at once (sixth review AS1).
     */
    @Test
    void anEmptyListBeforeTheServerIsReadyIsNoCheck() throws Exception {
        EditorBuffer b = open();
        java.util.concurrent.atomic.AtomicBoolean ready = new java.util.concurrent.atomic.AtomicBoolean();
        FxTestSupport.runOnFx(() -> b.problemCounter().setSettled(ready::get));
        FxTestSupport.runOnFx(() -> b.setLspDiagnostics(List.of()));
        fx.layout();
        Node counter = FxTestSupport.callOnFx(() -> b.problemCounter().counter());
        assertNotNull(counter);
        assertNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "not checked yet");

        ready.set(true);
        FxTestSupport.runOnFx(() -> b.problemCounter().refresh());
        assertNotNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "the server is ready");

        FxTestSupport.runOnFx(() -> b.problemCounter().reset()); // the server restarts
        ready.set(false);
        var error = new LspDiagnostic(2, 8, 2, 11, LspDiagnostic.Severity.ERROR, "bad", null, "jdtls");
        FxTestSupport.runOnFx(() -> b.setLspDiagnostics(List.of(error)));
        assertEquals(
                com.editora.i18n.Messages.tr("editor.problemCounter.summary", 1, 0),
                FxTestSupport.callOnFx(() -> ((javafx.scene.layout.HBox) counter)
                        .getChildren()
                        .get(0)
                        .getAccessibleText()),
                "problems count before ready");
    }

    /** A tab that is not on screen asks nothing, though it keeps its scene (sixth review AV7). */
    @Test
    void aHiddenTabIsNotOnScreen() throws Exception {
        EditorBuffer b = open();
        fx.layout();
        assertTrue(FxTestSupport.callOnFx(() -> com.editora.editor.CodeActionBulbTestHooks.onScreen(b.getArea())));
        FxTestSupport.runOnFx(() -> b.getArea().getParent().setVisible(false));
        assertFalse(FxTestSupport.callOnFx(() -> com.editora.editor.CodeActionBulbTestHooks.onScreen(b.getArea())));
        FxTestSupport.runOnFx(() -> b.getArea().getParent().setVisible(true));
    }

    /** A bulb is kept while its tab is hidden: the tab came back with none on the error under the caret (AR2). */
    @Test
    void aHiddenTabKeepsItsBulb() throws Exception {
        EditorBuffer b = open();
        fx.server().codeActionResponse = List.of(action("Remove 'x'", "quickfix"));
        fx.caret(b, 2, 12);
        probe(b);
        assertEquals(2, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue());
        FxTestSupport.runOnFx(() -> b.getArea().getParent().setVisible(false)); // another tab is shown
        FxTestSupport.runOnFx(() -> b.setLspDiagnostics(List.of())); // problems published meanwhile
        assertEquals(2, FxTestSupport.callOnFx(() -> b.codeActionBulb().line()).intValue(), "kept while hidden");
        FxTestSupport.runOnFx(() -> b.getArea().getParent().setVisible(true));
    }
}
