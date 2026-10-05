package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import javafx.scene.control.Tab;

import com.editora.command.CommandRegistry;
import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A file opened in the background (a workspace edit, a diff apply, an agent write, a run configuration's
 * main class) must come up with the same stored per-file state as any other open.
 *
 * <p>It used to skip the restore block: the tab had no breakpoints or bookmarks although the stores held
 * them, so the next mark change persisted the buffer's near-empty list over the stored one, and a file the
 * user had pinned read-only opened editable — which is the only thing the callers' refusal checks.
 */
@Tag("fx")
class BackgroundOpenRestoreFxTest {

    private static final String SOURCE = "class Helper {\n" // 0
            + "    int compute() {\n" // 1
            + "        int x = 1;\n" // 2
            + "        int y = 2;\n" // 3
            + "        int z = 3;\n" // 4
            + "        int w = 4;\n" // 5
            + "        return x + y + z + w;\n" // 6
            + "    }\n"
            + "}\n";

    @TempDir
    static Path work;

    private static FxWindowFixture fx;
    private static MainController c;
    private static FileWorkflowCoordinator workflows;
    private static CommandRegistry registry;
    private static ConfigManager config;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        fx = FxWindowFixture.create(
                Files.createTempDirectory(work, "cfg"),
                shared -> shared.getSettings().setDebugSupport(true));
        c = fx.controller;
        workflows = FxTestSupport.field(c, "fileWorkflows");
        registry = FxTestSupport.field(c, "registry");
        config = FxTestSupport.field(c, "config");
        settle(300);
    }

    @AfterAll
    static void dispose() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    @Test
    void storedMarksSurviveABackgroundOpenAndTheNextToggle() throws Exception {
        Path helper = Files.writeString(work.resolve("Helper.java"), SOURCE);
        markAndClose(helper);

        EditorBuffer bg = FxTestSupport.callOnFx(() ->
                (EditorBuffer) FxTestSupport.call(c, "openBackgroundBuffer", new Class<?>[] {Path.class}, helper));
        assertNotNull(bg);
        settle(300);
        assertEquals(
                List.of(2, 3),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(bg.getBreakpointManager().lines())));
        assertEquals(
                List.of(4),
                FxTestSupport.callOnFx(() -> List.copyOf(bg.getBookmarkManager().lines())));

        select(helper);
        caret(bg, 5);
        FxTestSupport.runOnFx(() -> registry.run("debug.toggleBreakpoint"));
        caret(bg, 6);
        FxTestSupport.runOnFx(() -> registry.run("bookmarks.toggle"));
        settle(900); // the persist is debounced

        assertEquals(List.of(2, 3, 5), storedBreakpointLines(helper), "one toggle adds to the stored list");
        assertEquals(List.of(4, 6), storedBookmarkLines(helper));
        closeTab(helper);
    }

    @Test
    void asyncBackgroundOpenRestoresMarksAndTheReadOnlyPin() throws Exception {
        Path helper = Files.writeString(work.resolve("Pinned.java"), SOURCE.replace("Helper", "Pinned"));
        EditorBuffer opened = markAndKeepOpen(helper);
        FxTestSupport.runOnFx(() -> registry.run("view.toggleReadOnly"));
        settle(300);
        assertTrue(FxTestSupport.callOnFx(opened::isViewMode), "pinned through the real command");
        closeTab(helper);

        AtomicReference<EditorBuffer> landed = new AtomicReference<>();
        boolean[] editableAtDone = {true};
        Consumer<EditorBuffer> done = b -> {
            // What the workspace-edit / diff callers test, at the moment they test it.
            editableAtDone[0] = b != null && b.isEditable();
            landed.set(b);
        };
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                c, "openBackgroundBufferAsync", new Class<?>[] {Path.class, Consumer.class}, helper, done));
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (landed.get() == null) {
            assertTrue(System.nanoTime() < deadline, "background open never completed");
            Thread.sleep(5);
            FxTestSupport.drainFx();
        }
        EditorBuffer bg = landed.get();

        assertFalse(editableAtDone[0], "a pinned file is not editable when the caller is handed it");
        assertTrue(FxTestSupport.callOnFx(bg::isViewMode));
        assertEquals(
                List.of(2, 3),
                FxTestSupport.callOnFx(
                        () -> List.copyOf(bg.getBreakpointManager().lines())));
        assertEquals(
                List.of(4),
                FxTestSupport.callOnFx(() -> List.copyOf(bg.getBookmarkManager().lines())));
        closeTab(helper);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Breakpoints on lines 2 and 3 and a bookmark on line 4, set through the real commands. */
    private static EditorBuffer markAndKeepOpen(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        long deadline = System.nanoTime() + 15_000_000_000L;
        while (!FxTestSupport.callOnFx(() -> {
            EditorBuffer b = bufferFor(file);
            return b != null && workflows.loadingBuffers.isEmpty() && !b.isLoading();
        })) {
            assertTrue(System.nanoTime() < deadline, "load never finished");
            Thread.sleep(5);
        }
        settle(300);
        EditorBuffer b = FxTestSupport.callOnFx(() -> bufferFor(file));
        caret(b, 2);
        FxTestSupport.runOnFx(() -> registry.run("debug.toggleBreakpoint"));
        caret(b, 3);
        FxTestSupport.runOnFx(() -> registry.run("debug.toggleBreakpoint"));
        caret(b, 4);
        FxTestSupport.runOnFx(() -> registry.run("bookmarks.toggle"));
        settle(900);
        assertEquals(List.of(2, 3), storedBreakpointLines(file), "fixture: breakpoints persisted");
        assertEquals(List.of(4), storedBookmarkLines(file), "fixture: bookmark persisted");
        return b;
    }

    private static void markAndClose(Path file) throws Exception {
        markAndKeepOpen(file);
        closeTab(file);
        assertEquals(List.of(2, 3), storedBreakpointLines(file), "closing keeps the stored breakpoints");
    }

    private static List<Integer> storedBreakpointLines(Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Breakpoint> l = config.getBreakpoints().get(file.toString());
            return l == null
                    ? List.of()
                    : l.stream().map(Breakpoint::line).sorted().toList();
        });
    }

    private static List<Integer> storedBookmarkLines(Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            List<Bookmark> l = config.getBookmarks().get(file.toString());
            return l == null
                    ? List.of()
                    : l.stream().map(Bookmark::line).sorted().toList();
        });
    }

    private static Tab tabFor(Path file) {
        return (Tab) FxTestSupport.call(c, "tabForPath", new Class<?>[] {Path.class}, file);
    }

    private static EditorBuffer bufferFor(Path file) {
        Tab t = tabFor(file);
        return t != null && t.getUserData() instanceof EditorBuffer b ? b : null;
    }

    private static void select(Path file) throws Exception {
        FxTestSupport.runOnFx(
                () -> FxTestSupport.<EditorArea>field(c, "editorArea").select(tabFor(file)));
        settle(100);
    }

    private static void caret(EditorBuffer b, int line) throws Exception {
        FxTestSupport.runOnFx(() -> b.getArea().moveTo(line, 0));
    }

    private static void closeTab(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> {
            Tab t = tabFor(file);
            if (t != null) {
                FxTestSupport.call(c, "closeTab", new Class<?>[] {Tab.class}, t);
            }
        });
        settle(300);
    }

    private static void settle(long millis) throws Exception {
        FxTestSupport.drainFx();
        Thread.sleep(millis);
        FxTestSupport.drainFx();
        FxTestSupport.drainFx();
    }
}
