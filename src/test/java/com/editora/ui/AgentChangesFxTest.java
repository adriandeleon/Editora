package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An agent's changes against a real window: an MCP edit is kept in a turn, Agent: Review Changes opens the turn as a
 * review tab, and Revert Turn puts the files back as one undoable edit each, after asking.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentChangesFxTest {

    private static final String TEXT = "int a = 1;\nint b = 2;\n";

    @TempDir
    static Path work;

    private FxWindowFixture fx;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @AfterEach
    void dialogsAgain() {
        Confirm.answerWith(null);
    }

    private EditorBuffer open(String name) throws Exception {
        Path file = work.toRealPath().resolve(name);
        Files.writeString(file, TEXT);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        Await.until(
                () -> {
                    EditorBuffer b = active();
                    return b != null && file.equals(b.getPath()) && !b.isLoading() && TEXT.equals(text(b));
                },
                "the file opened");
        return active();
    }

    /** The newest turn that changed {@code file} (the tests share one window, so not always the newest turn). */
    private AgentChangeSets.Turn turnWith(Path file) {
        return fx.controller.agentChanges.turns().stream()
                .filter(t -> t.before().containsKey(file))
                .findFirst()
                .orElseThrow();
    }

    private EditorBuffer active() {
        try {
            return FxTestSupport.callOnFx(
                    () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String text(EditorBuffer b) {
        try {
            return FxTestSupport.callOnFx(b::getContent);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anMcpEditIsReviewedAndRevertedAsATurn() throws Exception {
        EditorBuffer b = open("Turn.java");
        assertNull(fx.controller.editBuffer(b.getPath().toString(), "int b = 2;", "int b = 20;", false));
        assertNull(fx.controller.editBuffer(b.getPath().toString(), "int a = 1;", "int a = 10;", false));
        AgentChangeSets.Turn turn = turnWith(b.getPath());
        assertTrue(turn.fileCount() >= 1, "this file, and another test's within the same quiet time");
        assertEquals(TEXT, turn.before().get(b.getPath()), "before the turn's first edit");

        FxTestSupport.runOnFx(() -> fx.controller.diffCoordinator.openAgentChanges(turn));
        Await.until(
                () -> {
                    try {
                        return FxTestSupport.callOnFx(
                                () -> fx.controller.editorArea.selectedTab().getUserData() instanceof PatchReviewPane);
                    } catch (Exception e) {
                        return false;
                    }
                },
                "the review tab opened");
        PatchReviewPane review = FxTestSupport.callOnFx(
                () -> (PatchReviewPane) fx.controller.editorArea.selectedTab().getUserData());
        assertEquals(turn.fileCount(), review.panes().size());

        List<Confirm.Question> asked = new ArrayList<>();
        Confirm.answerWith(q -> {
            asked.add(q);
            return true;
        });
        FxTestSupport.runOnFx(() -> fx.controller.diffCoordinator.revertTurn(turnWith(b.getPath())));
        assertEquals(1, asked.size(), "asked first");
        assertEquals(TEXT, text(b), "as before the turn");
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals("int a = 10;\nint b = 20;\n", text(b), "one undo takes the revert back");
    }

    @Test
    void anAcceptedProposalIsPartOfTheTurn() throws Exception {
        EditorBuffer b = open("Proposed.java");
        String path = b.getPath().toString();
        var answer = java.util.concurrent.CompletableFuture.supplyAsync(
                () -> fx.controller.proposeEdit(path, "int a = 1;", "int a = 5;", false, false, null, "Claude Code"));
        Await.until(
                () -> {
                    try {
                        return FxTestSupport.callOnFx(
                                () -> fx.controller.editorArea.selectedTab().getUserData() instanceof ProposalPane);
                    } catch (Exception e) {
                        return false;
                    }
                },
                "the proposal opened");
        FxTestSupport.runOnFx(
                () -> ((ProposalPane) fx.controller.editorArea.selectedTab().getUserData()).accept());
        assertEquals(
                "accepted",
                answer.get(10, java.util.concurrent.TimeUnit.SECONDS).outcome());
        assertEquals(TEXT, turnWith(b.getPath()).before().get(b.getPath()), "before the accepted edit");
    }

    @Test
    void eachTurnLeavesALabelledRevisionAndSeveralTurnsAskWhichOne() throws Exception {
        EditorBuffer b = open("Labelled.java");
        String path = b.getPath().toString();
        assertNull(fx.controller.editBuffer(path, "int b = 2;", "int b = 7;", false));
        AgentChangeSets.Turn turn = turnWith(b.getPath());
        String label = "Before " + AgentChangeSets.name(turn);
        Await.until(
                () -> fx.controller.localHistory(path).stream().anyMatch(h -> label.equals(h.label())),
                "File History has the turn's restore point");
        int at = fx.controller.localHistory(path).stream()
                .filter(h -> label.equals(h.label()))
                .findFirst()
                .orElseThrow()
                .index();
        assertEquals(TEXT, fx.controller.localHistoryText(path, at), "the file before the turn");

        FxTestSupport.runOnFx(() -> fx.controller.agentChanges.beginTurn("Claude Code"));
        FxTestSupport.runOnFx(() -> fx.controller.agentChanges.beforeAgentWrite(b.getPath(), TEXT, false));
        FxTestSupport.runOnFx(() -> fx.controller.agentChanges.endTurn());
        assertTrue(fx.controller.agentChanges.turns().size() >= 2);
        FxTestSupport.runOnFx(() -> fx.controller.registry.run("agent.reviewChanges"));
        assertTrue(FxTestSupport.callOnFx(fx.controller.overlayHost::isShowing), "a picker asks which turn");
        FxTestSupport.runOnFx(fx.controller.overlayHost::hide);
    }

    @Test
    void sayingNoChangesNothing() throws Exception {
        EditorBuffer b = open("Keep.java");
        assertNull(fx.controller.editBuffer(b.getPath().toString(), "int b = 2;", "int b = 3;", false));
        Confirm.answerWith(q -> false);
        FxTestSupport.runOnFx(() -> fx.controller.diffCoordinator.revertTurn(turnWith(b.getPath())));
        assertEquals("int a = 1;\nint b = 3;\n", text(b));
    }
}
