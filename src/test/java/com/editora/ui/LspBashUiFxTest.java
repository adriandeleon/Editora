package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.Node;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.LspTestHooks;
import com.editora.text.LspDiagnostic;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.RenameOptions;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.WorkspaceEdit;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.jsonrpc.messages.Either3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the editor makes of bash-language-server's answers (Bash review, 2026-10-27), against a scripted server:
 * rename on a command word, a format shfmt could not parse, ShellCheck's quick fixes and suppressions, the problem
 * counter over notes and over a zsh script, and Next Problem's echo.
 */
@Tag("fx")
class LspBashUiFxTest {

    private static final String SCRIPT = "#!/usr/bin/env bash\ngreet() {\n  echo \"$1\"\n}\ngreet $name\n";

    @TempDir
    Path root;

    private LspCoordinatorFixture fx;

    @BeforeEach
    void setUp() throws Exception {
        fx = new LspCoordinatorFixture(root, LspTestHooks.caps());
        FxTestSupport.runOnFx(() -> fx.coordinator.setServerAvailableForTest("bash", true));
    }

    @AfterEach
    void tearDown() throws Exception {
        fx.close();
    }

    private static Range range(int line, int from, int to) {
        return new Range(new Position(line, from), new Position(line, to));
    }

    private EditorBuffer script() throws Exception {
        EditorBuffer b = fx.open("deploy.sh", SCRIPT);
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(b));
        return b;
    }

    // --- C1, B5, B11: rename -----------------------------------------------------------------------------------

    @Test
    void renameOnACommandWordIsRefusedWhenNothingDeclaresIt() throws Exception {
        fx.capabilities.setRenameProvider(Either.forRight(new RenameOptions(true)));
        EditorBuffer b = script();
        fx.caret(b, 2, 3); // on `echo`
        fx.server().prepareRenameResponse = Either3.forFirst(range(2, 2, 6));
        fx.server().definitionResponse = List.of(); // a builtin: no declaration anywhere

        fx.run(() -> fx.coordinator.rename());

        assertTrue(fx.host.prompts.isEmpty(), "no rename prompt for a builtin");
        assertEquals(tr("status.lsp.bashCannotRenameCommand", "echo"), fx.host.lastStatus());
    }

    @Test
    void renameOnAFunctionIsPromptedAndANameTheShellCannotTakeIsRefused() throws Exception {
        fx.capabilities.setRenameProvider(Either.forRight(new RenameOptions(true)));
        EditorBuffer b = script();
        fx.caret(b, 4, 1); // on the call of `greet`
        fx.server().prepareRenameResponse = Either3.forFirst(range(4, 0, 5));
        String uri = b.getPath().toUri().toString();
        fx.server().definitionResponse = List.of(new Location(uri, range(1, 0, 5)));

        fx.run(() -> fx.coordinator.rename());

        assertEquals("greet", fx.lastPrompt().initial());
        fx.run(() -> fx.lastPrompt().onAccept().accept("1bad name"));
        assertEquals(tr("status.lsp.renameInvalidShellName", "1bad name"), fx.host.lastStatus());
        assertTrue(fx.server().renames.isEmpty(), "never sent to the server");

        fx.server().renameResponse = new WorkspaceEdit(java.util.Map.of(
                uri, List.of(new TextEdit(range(1, 0, 5), "say-hi"), new TextEdit(range(4, 0, 5), "say-hi"))));
        fx.run(() -> fx.lastPrompt().onAccept().accept("say-hi"));
        fx.settle();
        assertEquals(1, fx.server().renames.size(), "a function may have a dash in its name");
    }

    // --- B3, C3, B14: Format Document --------------------------------------------------------------------------

    @Test
    void aFormatShfmtCouldNotParseSaysWhyNotAlreadyFormatted() throws Exception {
        EditorBuffer b = script();
        Path file = b.getPath();
        fx.server().formattingResponse = List.of(); // bash-language-server's null
        fx.server().beforeFormattingReply = () -> LspTestHooks.logError(
                fx.manager,
                file,
                "02:41:38.518 ERROR ⛔️ Error while formatting: Error: Shfmt: exited with status 1: " + file
                        + ":5:1: `for` statement must end with `done`");

        fx.run(() -> fx.coordinator.formatDocument());

        assertEquals(
                tr("status.lsp.bashCannotFormatAt", 5, "`for` statement must end with `done`"), fx.host.lastStatus());
        assertFalse(fx.host.lastStatus().equals(tr("status.lsp.formatNoChange")));
    }

    @Test
    void aWholeFileEditThatChangesNothingIsAlreadyFormattedAndLeavesTheFileClean() throws Exception {
        EditorBuffer b = script();
        fx.server().formattingResponse = List.of( // shfmt's one whole-file edit
                new TextEdit(new Range(new Position(0, 0), new Position(5, 0)), SCRIPT));

        fx.run(() -> fx.coordinator.formatDocument());

        assertEquals(tr("status.lsp.formatNoChange"), fx.host.lastStatus());
        assertFalse(FxTestSupport.callOnFx(b::isDirty), "nothing applied");
    }

    @Test
    void formattingASelectionOnAServerWithoutRangeFormattingSaysTheWholeFileWasFormatted() throws Exception {
        fx.capabilities.setDocumentRangeFormattingProvider(false);
        EditorBuffer b = script();
        FxTestSupport.runOnFx(() -> b.getArea().selectRange(0, 10));
        fx.server().formattingResponse = List.of(new TextEdit(range(2, 0, 0), "  "));

        fx.run(() -> fx.coordinator.formatDocument());

        assertEquals(tr("status.lsp.formattedWholeFile"), fx.host.lastStatus());
    }

    // --- C10, B13: quick fixes and suppressions ----------------------------------------------------------------

    private static Diagnostic shellcheck(String code, String message, int line, int from, int to) {
        Diagnostic d = new Diagnostic(range(line, from, to), message);
        d.setSource("shellcheck");
        d.setCode(code);
        d.setSeverity(DiagnosticSeverity.Information);
        return d;
    }

    @Test
    void shellCheckFixesSayWhatTheyDoAndSuppressionsComeAfterThem() throws Exception {
        LspCoordinatorFixture.PickingBuffer b = fx.openPicking("tool.sh", SCRIPT);
        Diagnostic quote = shellcheck("SC2086", "Double quote to prevent globbing and word splitting.", 4, 6, 11);
        LspTestHooks.publishDiagnostics(
                fx.manager,
                b.getPath(),
                new org.eclipse.lsp4j.PublishDiagnosticsParams(
                        b.getPath().toUri().toString(), List.of(quote)));
        fx.settle();
        CodeAction fix = new CodeAction("Apply fix for SC2086");
        fix.setKind("quickfix");
        fix.setDiagnostics(List.of(quote));
        fix.setEdit(new WorkspaceEdit(java.util.Map.of()));
        fx.server().codeActionResponse = List.of(Either.forRight(fix));
        fx.caret(b, 4, 8);

        fx.run(() -> fx.coordinator.codeActions());

        List<String> titles = FxTestSupport.callOnFx(() ->
                b.offered.stream().map(com.editora.editor.lsp.CodeAction::title).toList());
        assertEquals(
                List.of(
                        tr("lsp.codeAction.bashFix", "SC2086", "Double quote to prevent globbing and word splitting"),
                        tr("lsp.codeAction.bashDisable", "SC2086")),
                titles,
                "5.6 offers no suppression: one is made from the diagnostic");
        assertEquals(
                tr("lsp.codeAction.group.suppress"),
                FxTestSupport.callOnFx(() -> b.offered.get(1).kind()));

        fx.run(() -> b.pick(tr("lsp.codeAction.bashDisable", "SC2086")));
        fx.settle();
        assertEquals(
                "# shellcheck disable=SC2086",
                FxTestSupport.callOnFx(() -> b.getArea().getParagraph(4).getText()),
                "above the command, at its indent");
        assertEquals(
                "greet $name",
                FxTestSupport.callOnFx(() -> b.getArea().getParagraph(5).getText()));
    }

    // --- C5, C14, C13: the problem counter and Next Problem -----------------------------------------------------

    private static String summaryOf(Node counter) throws Exception {
        return FxTestSupport.callOnFx(
                () -> ((javafx.scene.layout.HBox) counter).getChildren().get(0).getAccessibleText());
    }

    @Test
    void notesCountAndTheArrowsReachThemWhereACheckSaidNoProblems() throws Exception {
        EditorBuffer b = script();
        var note = new LspDiagnostic(4, 6, 4, 11, LspDiagnostic.Severity.INFO, "Double quote", "SC2086", "shellcheck");
        var hint = new LspDiagnostic(2, 2, 2, 6, LspDiagnostic.Severity.HINT, "style", "SC2006", "shellcheck");
        var error = new LspDiagnostic(1, 0, 1, 5, LspDiagnostic.Severity.ERROR, "bad", "SC1009", "shellcheck");
        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of(note, hint)));
        fx.layout();
        Node counter = FxTestSupport.callOnFx(() -> b.problemCounter().counter());
        assertNotNull(counter);
        assertNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "no check over two notes");
        assertEquals(tr("editor.problemCounter.summary", 0, 0, 2), summaryOf(counter));
        assertTrue(FxTestSupport.callOnFx(
                () -> counter.lookup(".problem-counter-next").isVisible()));

        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of(note, error)));
        fx.caret(b, 1, 0);
        FxTestSupport.runOnFx(() -> ((javafx.scene.control.Button) counter.lookup(".problem-counter-next")).fire());
        assertEquals(
                4,
                FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()).intValue(),
                "the arrows go through every problem, not only the errors");
        fx.run(() -> fx.coordinator.gotoProblem(true));
        assertEquals(
                1,
                FxTestSupport.callOnFx(() -> b.getArea().getCurrentParagraph()).intValue(),
                "Next Problem still goes to the errors first");
        assertTrue(fx.host.errors.isEmpty(), "a script's error is no Editora error (C13): " + fx.host.errors);
        assertEquals(tr("status.lsp.problem", 2, "bad"), fx.host.lastStatus());

        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of()));
        assertNotNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")), "nothing at all: a check");
    }

    @Test
    void aZshScriptOnTheBashServerIsNotCheckedRatherThanClean() throws Exception {
        EditorBuffer b = fx.open("prompt.zsh", "#!/usr/bin/env zsh\nsetopt prompt_subst\n");
        FxTestSupport.runOnFx(() -> fx.coordinator.wireBuffer(b));
        fx.run(() -> fx.coordinator.onDiagnostics(b.getPath(), List.of()));
        fx.layout();
        Node counter = FxTestSupport.callOnFx(() -> b.problemCounter().counter());
        assertNotNull(counter);
        assertNull(FxTestSupport.callOnFx(() -> counter.lookup(".problem-counter-ok")));
        assertEquals(tr("editor.problemCounter.zshNotChecked"), summaryOf(counter));

        EditorBuffer bash = script();
        fx.run(() -> fx.coordinator.onDiagnostics(bash.getPath(), List.of()));
        Node other = FxTestSupport.callOnFx(() -> bash.problemCounter().counter());
        assertNotNull(FxTestSupport.callOnFx(() -> other.lookup(".problem-counter-ok")), "a bash script is checked");
    }
}
