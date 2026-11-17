package com.editora.ui;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Closing a tab must let its {@link EditorBuffer} — and therefore its document, style spans and undo
 * checkpoints, which are by far the largest per-file cost — become collectable.
 *
 * <p>This exists because a memory evaluation appeared to show the opposite: an edited buffer looked
 * retained after its tab closed, by tens of MB. Every part of that was measurement error (a forced
 * {@code GC.run} followed by {@code GC.heap_info} reports <em>used</em>, not live bytes; and the first
 * heap dump was taken while the diagnostic's own local variable still referenced the buffer, making it a
 * "Java frame" GC root). A heap dump with no such reference showed zero live buffers. A weak reference is
 * the cheap, honest way to keep that true: no dump, no tooling, and it fails if a listener, a coordinator
 * field or a panel ever starts outliving the tab.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BufferReleasedOnCloseFxTest {

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

    @Test
    void closingAnEditedTabReleasesItsBuffer() throws Exception {
        Path file = Files.createTempFile("editora-release", ".java");
        Files.writeString(file, "class A {\n    void m() {}\n}\n");

        // The buffer is only ever referenced inside this call, so nothing on THIS frame keeps it alive —
        // a live local slot would itself root the buffer and the assertion below would be meaningless.
        WeakReference<EditorBuffer> ref = openEditAndClose(file);

        assertTrue(collected(ref), "the closed buffer is still reachable — something outlived its tab");
    }

    @Test
    void killingYankingAndExpandingInATabDoNotKeepItsBufferAlive() throws Exception {
        Path file = Files.createTempFile("editora-release-kill", ".java");
        Files.writeString(file, "class B {\n    void m() { int value = 1; }\n}\n");

        WeakReference<EditorBuffer> ref = killYankExpandAndClose(file);

        assertTrue(collected(ref), "the closed buffer is still reachable — a kill/yank/selection slot kept it");
    }

    /**
     * Undo Last Removal keeps how to put back what was removed from a tab's line (a breakpoint with a condition, its
     * pinned values, a bookmark with a mnemonic) for the life of the window: it must find the buffer again without
     * keeping it.
     */
    @Test
    void aRemovalThatCanBeUndoneDoesNotKeepItsClosedBufferAlive() throws Exception {
        Path file = Files.createTempFile("editora-release-removal", ".java");
        Files.writeString(file, "class C {\n    int a = 1;\n    int b = 2;\n}\n");

        WeakReference<EditorBuffer> ref = removeFromItsLinesAndClose(file);

        assertEquals(3, FxTestSupport.callOnFx(() -> fx.controller.removalUndo.size()), "the removals are kept");
        assertTrue(collected(ref), "the closed buffer is still reachable — a recorded removal kept it");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(
                () -> registry.run("edit.undoRemoval")); // its tab is gone: it says so, and throws nothing
        assertEquals(2, FxTestSupport.callOnFx(() -> fx.controller.removalUndo.size()));
    }

    /** Opens the file, removes a detailed breakpoint, a line's pins and a mnemonic bookmark from it, and closes it. */
    private WeakReference<EditorBuffer> removeFromItsLinesAndClose(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        EditorBuffer buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
        assertNotNull(buffer, "the file opened into a buffer");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        DebugCoordinator debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        FxTestSupport.runOnFx(() -> {
            fx.controller.removalUndo.clear();
            buffer.getBreakpointManager()
                    .add(com.editora.config.Breakpoint.plain(1, "int a = 1;").withCondition("a > 0"));
            buffer.toggleBreakpoint(1);
            buffer.getPinManager().toggle(2, "b");
            buffer.getFocusedArea().moveTo(2, 0);
            debug.pinnedValues().unpinLine();
            buffer.getBookmarkManager().add(new com.editora.config.Bookmark(2, "", "", "9"));
            registry.run("bookmarks.toggle");
            registry.run("buffer.closeAll");
        });
        return new WeakReference<>(buffer, collectedBuffers);
    }

    /**
     * The "which buffer was the last kill / yank / expand-selection in" slots only ever compare identity, so
     * they must not be what keeps a closed buffer alive. The selection-range slot is filled by a language
     * server's answer, which a headless test does not have: its declared type is pinned instead.
     */
    @Test
    void theLastBufferSlotsAreWeak() throws Exception {
        assertEquals(
                WeakReference.class,
                EditingCoordinator.class.getDeclaredField("lastKillBuffer").getType());
        assertEquals(
                WeakReference.class,
                EditingCoordinator.class.getDeclaredField("lastYankBuffer").getType());
        assertEquals(
                WeakReference.class,
                LspCoordinator.class.getDeclaredField("selectionChainBuffer").getType());
    }

    /** Opens the file, kills a line, yanks it back, expands the selection, saves and closes it. */
    private WeakReference<EditorBuffer> killYankExpandAndClose(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        EditorBuffer buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
        assertNotNull(buffer, "the file opened into a buffer");
        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        FxTestSupport.runOnFx(() -> {
            buffer.getFocusedArea().moveTo(1, 4);
            registry.run("edit.killLine"); // fills the last-kill slot
            registry.run("edit.paste"); // yank it back: fills the last-yank slot
            buffer.getFocusedArea().moveTo(1, 20);
            registry.run("edit.expandSelection");
        });
        EditingCoordinator editing = FxTestSupport.field(fx.controller, "editing");
        assertTrue(
                FxTestSupport.callOnFx(() -> editing.lastYankBuffer.get() == buffer),
                "precondition: the yank slot points at this buffer");
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        awaitSaved(buffer); // as above: the save is asynchronous, and a dirty close would open a modal prompt
        FxTestSupport.runOnFx(() -> registry.run("buffer.closeAll"));
        return new WeakReference<>(buffer, collectedBuffers);
    }

    /** Opens the file, edits it (so undo checkpoints exist), saves and closes it. Returns a weak handle. */
    private WeakReference<EditorBuffer> openEditAndClose(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                FxTestSupport.field(fx.controller, "fileWorkflows"), "openPath", new Class[] {Path.class}, file));
        EditorBuffer buffer = FxTestSupport.callOnFx(
                () -> (EditorBuffer) FxTestSupport.call(fx.controller, "activeBuffer", new Class[] {}));
        assertNotNull(buffer, "the file opened into a buffer");

        for (int i = 0; i < 2; i++) {
            final int n = i;
            FxTestSupport.runOnFx(() -> {
                CodeArea area = buffer.getFocusedArea();
                area.insertText(0, "// edit " + n + "\n");
            });
            com.editora.editor.EditorFx.awaitEditsSettled(buffer); // the UndoMerge.PAUSE debounce captured one
        }

        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        // Save before closing: a dirty buffer raises a modal "save changes?" dialog, which would block the
        // FX thread for the rest of the run in a headless test.
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        awaitSaved(buffer);
        FxTestSupport.runOnFx(() -> registry.run("buffer.closeAll"));
        return new WeakReference<>(buffer, collectedBuffers);
    }

    /** Where the weak handles above are enqueued once their buffer is collected. */
    private final java.lang.ref.ReferenceQueue<EditorBuffer> collectedBuffers = new java.lang.ref.ReferenceQueue<>();

    /**
     * Waits for the save to land: the local writer has run every write queued on it (a buffer whose edits undid
     * themselves is clean before its save is written), its answer is back on the FX thread, and the buffer is clean.
     */
    private void awaitSaved(EditorBuffer buffer) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        for (int round = 0; round < 2; round++) {
            FxTestSupport.drainFx();
            workflows.autoSaveExecutor.submit(() -> {}).get(30, java.util.concurrent.TimeUnit.SECONDS);
        }
        FxTestSupport.drainFx();
        Await.until(
                () -> {
                    try {
                        return !FxTestSupport.callOnFx(buffer::isDirty);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                "the save landed");
    }

    /**
     * Waits, with GC pressure, for the referent to be cleared. Generous: this is a liveness assertion. Each round
     * asks for a collection and waits for a handle to be enqueued, waking as soon as one is.
     */
    private boolean collected(WeakReference<?> ref) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            FxTestSupport.runOnFx(() -> {}); // drain the FX queue: a pending event can hold the last edge
            System.gc();
            if (ref.get() == null) {
                return true;
            }
            collectedBuffers.remove(150);
        }
        return ref.get() == null;
    }
}
