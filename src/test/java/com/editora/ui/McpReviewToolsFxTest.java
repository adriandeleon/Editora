package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.editor.EditorBuffer;
import com.editora.mcp.McpBridge;
import com.editora.testkit.Await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MCP review tools against a real window and a real Git repository: an agent's comment shown in the editor,
 * read back, answered and resolved, the file rule, and (a regression) the buffer stamps the resource watch reads.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpReviewToolsFxTest {

    private static final String TEXT = "int a = 1;\nint b = 2;\nint c = 3;\nint d = 4;\n";

    /** Static: a per-class {@code @BeforeAll} runs before instance {@code @TempDir} fields are filled. */
    @TempDir
    static Path work;

    private Path repo;
    private FxWindowFixture fx;
    private McpBridge mcp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        repo = Files.createDirectories(work.toRealPath().resolve("repo"));
        assertEquals(
                0,
                new ProcessBuilder("git", "init", "-q", repo.toString())
                        .inheritIO()
                        .start()
                        .waitFor());
        fx = FxWindowFixture.create();
        mcp = fx.controller;
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    /** Opens {@code repo/name} and waits until the window's Git follows it into the repository. */
    private EditorBuffer open(String name) throws Exception {
        Path file = repo.resolve(name);
        Files.writeString(file, TEXT);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        Await.until(
                () -> {
                    EditorBuffer b = active();
                    return b != null && file.equals(b.getPath()) && !b.isLoading() && TEXT.equals(content(b));
                },
                "the file opened");
        Await.until(() -> repo.equals(onFx(() -> fx.controller.git.repoRoot())), "the window's Git is in the repo");
        return active();
    }

    private EditorBuffer active() {
        return onFx(() -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {}));
    }

    private static String content(EditorBuffer b) {
        return onFx(b::getContent);
    }

    private static <T> T onFx(java.util.concurrent.Callable<T> c) {
        try {
            return FxTestSupport.callOnFx(c);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void anAgentsFindingIsShownInTheEditorAndReadBack() throws Exception {
        EditorBuffer b = open("Finding.java");
        String path = b.getPath().toString();
        McpBridge.ReviewAdded added = mcp.addReview(
                path, 2, 3, "Off by one.", "int b = 3;\nint c = 4;", "important", "correctness", "Claude Code");
        assertNull(added.error(), added.error());
        assertNotNull(added.id());
        assertEquals(
                List.of("◆ IMPORTANT · Off by one."),
                onFx(() -> b.reviewLensesOn(2).stream()
                        .map(EditorBuffer.ReviewLens::label)
                        .toList()),
                "after its last line");

        List<McpBridge.ReviewThreadInfo> listed = mcp.listReviews(path, false);
        assertEquals(1, listed.size());
        McpBridge.ReviewThreadInfo t = listed.get(0);
        assertEquals(2, t.startLine());
        assertEquals(3, t.endLine());
        assertEquals("important", t.severity());
        assertEquals("correctness", t.category());
        assertEquals("agent", t.comments().get(0).authorKind());
        assertEquals("Claude Code", t.comments().get(0).author());
        assertEquals("int b = 3;\nint c = 4;", t.comments().get(0).suggestion());

        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "// new first line\n"));
        assertEquals(3, mcp.listReviews(path, false).get(0).startLine(), "where its lines are now, unsaved too");
    }

    @Test
    void repliesAndAResolveWithItsNoteReachTheThread() throws Exception {
        EditorBuffer b = open("Answer.java");
        String path = b.getPath().toString();
        String id = mcp.addReview(path, 1, 1, "Name a?", null, null, null, "Claude Code")
                .id();
        assertNull(mcp.replyReview(id, "It is the count.", null, "Claude Code"));
        assertNull(mcp.resolveReview(id, "Renamed to count", "Claude Code"));
        McpBridge.ReviewThreadInfo t = mcp.listReviews(path, true).stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow();
        assertEquals("resolved", t.state());
        assertTrue(t.stateNote().contains("Claude Code") && t.stateNote().contains("Renamed to count"), t.stateNote());
        assertEquals(2, t.comments().size());
        assertTrue(mcp.listReviews(path, false).stream().noneMatch(x -> x.id().equals(id)), "open ones only");
        assertTrue(mcp.resolveReview(id, "again", "Claude Code").contains("not open"));
        assertTrue(mcp.replyReview("no-such-thread", "x", null, "Claude Code").contains("No review thread"));
    }

    @Test
    void anAgentCommentsOnlyOnLinesOfAFileTheWindowMayShowIt() throws Exception {
        EditorBuffer b = open("Rules.java");
        String path = b.getPath().toString();
        assertTrue(mcp.addReview(path, 9, 9, "x", null, null, null, "A").error().contains("not in the file"));
        assertTrue(
                mcp.addReview(path, 1, 1, "x", null, "huge", null, "A").error().contains("severity"));
        Path elsewhere = Files.writeString(work.resolve("elsewhere.txt"), "x\n");
        assertNotNull(
                mcp.addReview(elsewhere.toString(), 1, 1, "x", null, null, null, "A")
                        .error(),
                "a file the window does not have, outside its project");
    }

    /** Regression: the window never passed bufferStamps on, so the resource watch saw every file at 0. */
    @Test
    void theWindowsBufferStampsMoveWhenAFileIsEdited() throws Exception {
        EditorBuffer b = open("Stamped.java");
        String path = b.getPath().toString();
        Long before = mcp.bufferStamps().get(path);
        assertNotNull(before, "the open file has a stamp");
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "x"));
        assertNotEquals(before, mcp.bufferStamps().get(path), "an edit moves it");
    }
}
