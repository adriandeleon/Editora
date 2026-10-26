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
}
