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
        AgentChangeSets.Turn turn = fx.controller.agentChanges.latest();
        assertTrue(turn.fileCount() >= 1, "this file, and another test's within the same quiet time");
        assertEquals(TEXT, turn.before().get(b.getPath()), "before the turn's first edit");

        FxTestSupport.runOnFx(() -> fx.controller.registry.run("agent.reviewChanges"));
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
        FxTestSupport.runOnFx(() -> fx.controller.registry.run("agent.revertTurn"));
        assertEquals(1, asked.size(), "asked first");
        assertEquals(TEXT, text(b), "as before the turn");
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals("int a = 10;\nint b = 20;\n", text(b), "one undo takes the revert back");
    }

    @Test
    void sayingNoChangesNothing() throws Exception {
        EditorBuffer b = open("Keep.java");
        assertNull(fx.controller.editBuffer(b.getPath().toString(), "int b = 2;", "int b = 3;", false));
        Confirm.answerWith(q -> false);
        FxTestSupport.runOnFx(() -> fx.controller.registry.run("agent.revertTurn"));
        assertEquals("int a = 1;\nint b = 3;\n", text(b));
    }
}
