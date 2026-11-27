package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.mcp.McpBridge;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code propose_edit} against a real window: the proposal tab with the diff, Accept applying one undoable edit,
 * Reject with a reason, closing the tab, an edit typed meanwhile, and no answer in time.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpProposeEditFxTest {

    private static final String TEXT = "int a = 1;\nint b = 2;\nint c = 3;\n";

    @TempDir
    static Path work;

    private FxWindowFixture fx;
    private McpBridge mcp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        mcp = fx.controller;
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @AfterEach
    void shortWaitOff() {
        fx.controller.mcpBridge.setProposalWaitMillisForTest(WindowMcpBridge.PROPOSAL_WAIT_MILLIS);
    }

    private EditorBuffer open(String name) throws Exception {
        Path file = work.toRealPath().resolve(name);
        Files.writeString(file, TEXT);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        Await.until(
                () -> {
                    EditorBuffer b = onFx(
                            () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
                    return b != null && file.equals(b.getPath()) && !b.isLoading() && TEXT.equals(onFx(b::getContent));
                },
                "the file opened");
        return onFx(() -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> c) {
        try {
            return FxTestSupport.callOnFx(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Proposes on another thread (the call waits for the user) and returns the proposal tab and the answer. */
    private record Asked(ProposalPane pane, Tab tab, CompletableFuture<McpBridge.ProposalOutcome> answer) {}

    private Asked propose(String path, String oldText, String newText, String why) throws Exception {
        CompletableFuture<McpBridge.ProposalOutcome> answer = CompletableFuture.supplyAsync(
                () -> mcp.proposeEdit(path, oldText, newText, false, false, why, "Claude Code"));
        Await.until(
                () -> onFx(() -> fx.controller.editorArea.selectedTab() != null
                        && fx.controller.editorArea.selectedTab().getUserData() instanceof ProposalPane),
                "the proposal tab opened");
        Tab tab = onFx(() -> fx.controller.editorArea.selectedTab());
        return new Asked((ProposalPane) tab.getUserData(), tab, answer);
    }

    private boolean gone(Tab tab) {
        return !onFx(() -> fx.controller.editorArea.contains(tab));
    }

    @Test
    void acceptAppliesTheEditAsOneUndoStepAndClosesTheTab() throws Exception {
        EditorBuffer b = open("Accept.java");
        Asked asked = propose(b.getPath().toString(), "int b = 2;", "int b = 20;", "Bigger.");
        assertEquals(TEXT, onFx(b::getContent), "nothing changes before the answer");
        assertTrue(onFx(() -> asked.pane().title().contains("Accept.java")));
        FxTestSupport.runOnFx(asked.pane()::accept);
        McpBridge.ProposalOutcome o = asked.answer().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("accepted", o.outcome());
        assertEquals("int a = 1;\nint b = 20;\nint c = 3;\n", onFx(b::getContent));
        Await.until(() -> gone(asked.tab()), "the tab closed");
        FxTestSupport.runOnFx(() -> b.getArea().undo());
        assertEquals(TEXT, onFx(b::getContent), "one undo takes it back");
    }

    /** Regression: Claude Code writes the file after FILE_SAVED, so an accepted review applying it too wrote it twice. */
    @Test
    void anAcceptedReviewOnlyAnswersAndLeavesTheBufferAlone() throws Exception {
        EditorBuffer b = open("Review.java");
        CompletableFuture<McpBridge.ProposalOutcome> answer = CompletableFuture.supplyAsync(
                () -> mcp.reviewProposal(b.getPath().toString(), "// new\n" + TEXT, "edit A", "Claude Code"));
        Await.until(
                () -> onFx(() -> fx.controller.editorArea.selectedTab() != null
                        && fx.controller.editorArea.selectedTab().getUserData() instanceof ProposalPane),
                "the proposal tab opened");
        Tab tab = onFx(() -> fx.controller.editorArea.selectedTab());
        FxTestSupport.runOnFx(((ProposalPane) tab.getUserData())::accept);
        assertEquals(
                "accepted",
                answer.get(10, java.util.concurrent.TimeUnit.SECONDS).outcome());
        assertEquals(TEXT, onFx(b::getContent), "the agent writes it, not Editora");
        assertTrue(!onFx(b::isDirty));
    }

    @Test
    void rejectSendsTheReasonAndClosingTheTabRejects() throws Exception {
        EditorBuffer b = open("Reject.java");
        String path = b.getPath().toString();
        Asked asked = propose(path, "int c = 3;", "int c = 30;", null);
        FxTestSupport.runOnFx(() -> {
            asked.pane().reasonField().setText("  Keep three.  ");
            asked.pane().reject();
        });
        McpBridge.ProposalOutcome o = asked.answer().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("rejected", o.outcome());
        assertEquals("Keep three.", o.reason());
        assertEquals(TEXT, onFx(b::getContent));
        Await.until(() -> gone(asked.tab()), "the tab closed");

        Asked again = propose(path, "int c = 3;", "int c = 30;", null);
        FxTestSupport.runOnFx(() -> fx.controller.closeTab(again.tab()));
        McpBridge.ProposalOutcome closed = again.answer().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("rejected", closed.outcome(), "closing the tab is a rejection");
        assertEquals("", closed.reason());
    }

    @Test
    void anEditTypedMeanwhileIsNotOverwritten() throws Exception {
        EditorBuffer b = open("Typed.java");
        Asked asked = propose(b.getPath().toString(), "int a = 1;", "int a = 10;", null);
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "// typed\n"));
        FxTestSupport.runOnFx(asked.pane()::accept);
        McpBridge.ProposalOutcome o = asked.answer().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("refused", o.outcome());
        assertTrue(o.reason().contains("changed"), o.reason());
        assertEquals("// typed\n" + TEXT, onFx(b::getContent));
    }

    @Test
    void noAnswerInTimeChangesNothingAndClosesTheTab() throws Exception {
        EditorBuffer b = open("Late.java");
        fx.controller.mcpBridge.setProposalWaitMillisForTest(300);
        Asked asked = propose(b.getPath().toString(), "int a = 1;", "int a = 10;", null);
        McpBridge.ProposalOutcome o = asked.answer().get(10, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals("timeout", o.outcome());
        Await.until(() -> gone(asked.tab()), "the tab closed");
        assertEquals(TEXT, onFx(b::getContent));
    }

    @Test
    void whatCannotBeProposedIsRefusedWithoutATab() throws Exception {
        EditorBuffer b = open("Refused.java");
        String path = b.getPath().toString();
        assertEquals(
                "refused",
                mcp.proposeEdit(path, "nope", "x", false, false, null, "A").outcome());
        assertEquals(
                "refused",
                mcp.proposeEdit(path, null, TEXT, false, true, null, "A").outcome(),
                "the same text: nothing to change");
        assertEquals(
                "refused",
                mcp.proposeEdit("/no/such/file", "a", "b", false, false, null, "A")
                        .outcome());
        assertTrue(onFx(() -> !(fx.controller.editorArea.selectedTab().getUserData() instanceof ProposalPane)));
    }

    @Test
    void theApprovalSettingIsWhatTheBridgeReports() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.config.getSettings().setMcpEditsNeedApproval(true));
        assertTrue(mcp.editsNeedApproval());
        FxTestSupport.runOnFx(() -> fx.controller.config.getSettings().setMcpEditsNeedApproval(false));
        assertEquals(false, mcp.editsNeedApproval());
    }
}
