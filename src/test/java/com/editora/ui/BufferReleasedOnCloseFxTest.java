package com.editora.ui;

import java.lang.ref.WeakReference;
import java.nio.file.Files;
import java.nio.file.Path;

import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
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
        Thread.sleep(400); // as above: the save is asynchronous, and a dirty close would open a modal prompt
        FxTestSupport.runOnFx(() -> registry.run("buffer.closeAll"));
        return new WeakReference<>(buffer);
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
            Thread.sleep(600); // let the UndoMerge.PAUSE debounce capture a checkpoint
        }

        CommandRegistry registry = FxTestSupport.field(fx.controller, "registry");
        // Save before closing: a dirty buffer raises a modal "save changes?" dialog, which would block the
        // FX thread for the rest of the run in a headless test.
        FxTestSupport.runOnFx(() -> registry.run("file.save"));
        Thread.sleep(400);
        FxTestSupport.runOnFx(() -> registry.run("buffer.closeAll"));
        return new WeakReference<>(buffer);
    }

    /** Waits, with GC pressure, for the referent to be cleared. Generous: this is a liveness assertion. */
    private boolean collected(WeakReference<?> ref) throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            FxTestSupport.runOnFx(() -> {}); // drain the FX queue: a pending event can hold the last edge
            System.gc();
            if (ref.get() == null) {
                return true;
            }
            Thread.sleep(150);
        }
        return ref.get() == null;
    }
}
