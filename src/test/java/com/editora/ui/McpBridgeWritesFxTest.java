package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.mcp.McpBridge;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The MCP write tools against a real window: what they change, and whether what they report is true. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class McpBridgeWritesFxTest {

    @TempDir
    Path work;

    private FxWindowFixture fx;
    private CommandRegistry registry;
    private McpBridge mcp;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create();
        registry = FxTestSupport.field(fx.controller, "registry");
        mcp = fx.controller;
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    // --- targeted edits see the whole file, as read_buffer does, also when the buffer is narrowed ---------

    @Test
    void targetedEditsOnANarrowedBufferMatchAgainstTheWholeFile() throws Exception {
        String doc = "foo = 1\nfoo = 2\nfoo = 3\nfoo = 4\n";
        EditorBuffer b = open("narrow.txt", doc);
        narrowToLine(b, doc, 1);
        String path = b.getPath().toString();
        assertEquals(doc, mcp.readBuffer(path).text(), "read_buffer is the whole file");

        // "foo" is unique in the region and occurs four times in the file the client read.
        String ambiguous = mcp.editBuffer(path, "foo", "bar", false);
        assertNotNull(ambiguous);
        assertTrue(ambiguous.contains("more than once"), ambiguous);
        assertEquals(doc, content(b));

        // Text the client was shown, outside the region, is found.
        assertNull(mcp.editBuffer(path, "foo = 4", "foo = 40", false));
        assertEquals("foo = 1\nfoo = 2\nfoo = 3\nfoo = 40\n", content(b));

        // replace_all reaches every occurrence, not only the ones in the region.
        EditorBuffer all = open("narrow-all.txt", doc);
        narrowToLine(all, doc, 1);
        assertNull(mcp.editBuffer(all.getPath().toString(), "=", ":=", true));
        assertEquals("foo := 1\nfoo := 2\nfoo := 3\nfoo := 4\n", content(all));
        discard(b, all);
    }

    @Test
    void aTargetedEditInsideTheRegionLeavesTheBufferNarrowed() throws Exception {
        String doc = "one\ntwo\nthree\n";
        EditorBuffer b = open("inside.txt", doc);
        narrowToLine(b, doc, 1);

        assertNull(mcp.editBuffer(b.getPath().toString(), "two", "TWO", false));

        assertEquals("one\nTWO\nthree\n", content(b));
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed), "an edit that fits the region does not widen it");
        assertEquals("TWO\n", FxTestSupport.callOnFx(b::getVisibleContent));
        FxTestSupport.runOnFx(b.getArea()::undo);
        assertEquals(doc, content(b), "and is one ordinary undo step");
        discard(b);
    }

    // --- an edit never means "the whole buffer" unless it was asked for by name --------------------------

    @Test
    void aTargetedEditWithoutOldTextChangesNothing() throws Exception {
        String doc = "alpha\nbeta\ngamma\n";
        EditorBuffer b = open("whole.txt", doc);
        assertNotNull(mcp.editBuffer(b.getPath().toString(), "", "BETA", false));
        assertNotNull(mcp.editBuffer(b.getPath().toString(), null, "BETA", false));
        assertEquals(doc, content(b));
        discard(b);
    }

    // --- a whole-buffer replacement is checked against what read_buffer served ---------------------------

    @Test
    void aWholeBufferReplacementComputedFromAnOlderReadIsRefused() throws Exception {
        EditorBuffer b = open("stale.txt", "one\n");
        String path = b.getPath().toString();
        String read = mcp.readBuffer(path).text();
        FxTestSupport.runOnFx(() -> b.getArea().appendText("typed after the read\n"));

        String refused = mcp.replaceBuffer(path, read.toUpperCase(java.util.Locale.ROOT));

        assertNotNull(refused);
        assertTrue(refused.contains("Read it again (read_buffer)"), refused);
        assertEquals("one\ntyped after the read\n", content(b));

        // Re-read, then replace: applied, and still one undo step back to the user's text.
        String current = mcp.readBuffer(path).text();
        assertNull(mcp.replaceBuffer(path, current.toUpperCase(java.util.Locale.ROOT)));
        assertEquals("ONE\nTYPED AFTER THE READ\n", content(b));
        FxTestSupport.runOnFx(b.getArea()::undo);
        assertEquals("one\ntyped after the read\n", content(b));
        discard(b);
    }

    @Test
    void theClientsOwnEditsDoNotMakeItsNextReplacementStale() throws Exception {
        EditorBuffer b = open("own.txt", "one\ntwo\n");
        String path = b.getPath().toString();
        mcp.readBuffer(path);
        assertNull(mcp.editBuffer(path, "two", "TWO", false));
        assertNull(mcp.replaceBuffer(path, "ONE\nTWO\n"));
        assertNull(mcp.replaceBuffer(path, "again\n"));
        assertEquals("again\n", content(b));
        discard(b);
    }

    @Test
    void unsavedTextThatWasNeverReadIsNotReplacedButACleanBufferIs() throws Exception {
        EditorBuffer dirty = open("unread.txt", "saved\n");
        FxTestSupport.runOnFx(() -> dirty.getArea().appendText("only in the editor\n"));
        String refused = mcp.replaceBuffer(dirty.getPath().toString(), "from the disk copy\n");
        assertNotNull(refused);
        assertTrue(refused.contains("unsaved changes"), refused);
        assertEquals("saved\nonly in the editor\n", content(dirty));

        EditorBuffer clean = open("clean.txt", "saved\n");
        assertNull(mcp.replaceBuffer(clean.getPath().toString(), "rewritten\n"));
        assertEquals("rewritten\n", content(clean));
        discard(dirty, clean);
    }

    // --- save_buffer reports what happened to the file ---------------------------------------------------

    @Test
    void saveBufferAnswersOnlyOnceTheBytesAreOnDisk() throws Exception {
        EditorBuffer b = open("save.txt", "v1\n");
        assertNull(mcp.editBuffer(b.getPath().toString(), "v1", "v1 + agent", false));

        assertNull(mcp.saveBuffer(b.getPath().toString()));

        assertEquals("v1 + agent\n", Files.readString(b.getPath()), "no waiting: 'saved' means written");
        assertFalse(FxTestSupport.callOnFx(b::isDirty));
    }

    @Test
    void saveBufferReportsASaveThatDidNotHappen() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer b = open("conflict.txt", "v1\n");
            String path = b.getPath().toString();
            Files.writeString(b.getPath(), "v2 written by another program\n");
            assertNull(mcp.editBuffer(path, "v1", "v1 + agent", false));

            CompletableFuture<String> result = new CompletableFuture<>();
            async.start("mcp-save", () -> result.complete(String.valueOf(mcp.saveBuffer(path))));
            // The save stops at the changed-on-disk question; the user declines to overwrite.
            Button cancel = awaitDialogButton(tr("dialog.cancel"));
            assertFalse(result.isDone(), "no answer while the outcome is undecided");
            FxTestSupport.runOnFx(cancel::fire);

            String answer = async.await(result);
            assertTrue(answer.startsWith("Not saved"), answer);
            assertEquals("v2 written by another program\n", Files.readString(b.getPath()));
            assertTrue(FxTestSupport.callOnFx(b::isDirty));
            discard(b);
        }
    }

    // --- a call that timed out is not applied later ------------------------------------------------------

    @Test
    void anEditThatTimedOutWaitingForTheEditorIsNotAppliedAfterwards() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            EditorBuffer b = open("slow.txt", "keep me\n");
            String path = b.getPath().toString();
            mcp.readBuffer(path);
            CountDownLatch release = new CountDownLatch(1);
            CountDownLatch busy = new CountDownLatch(1);
            async.onClose(release::countDown);
            Platform.runLater(() -> {
                busy.countDown();
                try {
                    release.await(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            async.await(busy, "the FX thread to be busy");

            IllegalStateException timeout =
                    assertThrows(IllegalStateException.class, () -> mcp.replaceBuffer(path, "replaced\n"));

            assertTrue(timeout.getMessage().contains("will not be applied"), timeout.getMessage());
            release.countDown();
            FxTestSupport.drainFx();
            assertEquals("keep me\n", content(b), "the caller was told it failed");
            discard(b);
        }
    }

    // --- helpers -----------------------------------------------------------------------------------------

    private String content(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(b::getContent);
    }

    /** Nothing to prompt about when the window is disposed. */
    private static void discard(EditorBuffer... buffers) throws Exception {
        for (EditorBuffer b : buffers) {
            FxTestSupport.runOnFx(b::markClean);
        }
    }

    private EditorBuffer open(String name, String content) throws Exception {
        Path file = work.resolve(name);
        Files.writeString(file, content);
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
        EditorBuffer[] opened = new EditorBuffer[1];
        assertTrue(
                waitFor(() -> {
                    EditorBuffer b =
                            (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class<?>[] {});
                    if (b == null || b.getPath() == null || b.isLoading() || !content.equals(b.getContent())) {
                        return false;
                    }
                    if (!b.getPath()
                            .toAbsolutePath()
                            .normalize()
                            .equals(file.toAbsolutePath().normalize())) {
                        return false;
                    }
                    opened[0] = b;
                    return true;
                }),
                "the file opened");
        FxTestSupport.drainFx();
        return opened[0];
    }

    /** Narrows {@code b} to the whole 0-based {@code line}. */
    private void narrowToLine(EditorBuffer b, String content, int line) throws Exception {
        assertTrue(FxTestSupport.callOnFx(() -> {
            b.getArea().selectRange(lineStart(content, line), lineStart(content, line + 1));
            return registry.run("edit.narrowToRegion");
        }));
        FxTestSupport.drainFx();
        assertTrue(FxTestSupport.callOnFx(b::isNarrowed), "precondition: the buffer is narrowed");
    }

    private static int lineStart(String text, int line) {
        int off = 0;
        for (int i = 0; i < line; i++) {
            off = text.indexOf('\n', off) + 1;
        }
        return off;
    }

    private static Button awaitDialogButton(String text) throws Exception {
        Button[] found = new Button[1];
        assertTrue(
                waitFor(() -> {
                    for (Window w : List.copyOf(Window.getWindows())) {
                        if (w.isShowing()
                                && w.getScene() != null
                                && w.getScene().getRoot() instanceof DialogPane dp) {
                            for (Node n : dp.lookupAll(".button")) {
                                if (n instanceof Button button && text.equals(button.getText())) {
                                    found[0] = button;
                                    return true;
                                }
                            }
                        }
                    }
                    return false;
                }),
                "the conflict dialog appeared");
        return found[0];
    }

    private static boolean waitFor(Callable<Boolean> onFx) throws Exception {
        long end = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < end) {
            if (Boolean.TRUE.equals(FxTestSupport.callOnFx(onFx))) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }
}
