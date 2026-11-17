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
import com.editora.testkit.Await;
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
        settle();
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
        Await.holdsWithin(
                () -> onFx(() -> List.copyOf(bg.getBreakpointManager().lines()).equals(List.of(2, 3))
                        && List.copyOf(bg.getBookmarkManager().lines()).equals(List.of(4))),
                java.time.Duration.ofSeconds(10));
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
        awaitStored(helper, List.of(2, 3, 5), List.of(4, 6)); // the persist is debounced

        assertEquals(List.of(2, 3, 5), storedBreakpointLines(helper), "one toggle adds to the stored list");
        assertEquals(List.of(4, 6), storedBookmarkLines(helper));
        closeTab(helper);
    }

    @Test
    void asyncBackgroundOpenRestoresMarksAndTheReadOnlyPin() throws Exception {
        Path helper = Files.writeString(work.resolve("Pinned.java"), SOURCE.replace("Helper", "Pinned"));
        EditorBuffer opened = markAndKeepOpen(helper);
        FxTestSupport.runOnFx(() -> registry.run("view.toggleReadOnly"));
        Await.holdsWithin(() -> onFx(opened::isViewMode), java.time.Duration.ofSeconds(10));
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
        Await.until(() -> landed.get() != null, "background open never completed", java.time.Duration.ofSeconds(15));
        FxTestSupport.drainFx();
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

    /**
     * A background open resolves {@code .editorconfig} on the read worker, with the load; attaching the tab
     * applies that prepared result rather than walking the file's ancestor directories again on FX.
     */
    @Test
    void asyncBackgroundOpenAppliesThePreparedEditorConfig() throws Exception {
        Path project = Files.createDirectories(work.resolve("ec"));
        Files.writeString(
                project.resolve(".editorconfig"), "root = true\n\n[*.java]\nindent_style = space\nindent_size = 3\n");
        Path file = Files.writeString(project.resolve("Spaced.java"), SOURCE.replace("Helper", "Spaced"));

        AtomicReference<EditorBuffer> landed = new AtomicReference<>();
        Consumer<EditorBuffer> done = landed::set;
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                c, "openBackgroundBufferAsync", new Class<?>[] {Path.class, Consumer.class}, file, done));
        Await.until(() -> landed.get() != null, "background open never completed", java.time.Duration.ofSeconds(15));
        FxTestSupport.drainFx();
        EditorBuffer bg = landed.get();
        assertEquals(
                Integer.valueOf(3),
                FxTestSupport.callOnFx(() -> bg.getEditorConfigProps().indentSize()),
                "the .editorconfig rule the read worker resolved");
        assertEquals(
                Boolean.TRUE,
                FxTestSupport.callOnFx(() -> bg.getEditorConfigProps().insertSpaces()));
        assertEquals(SOURCE.replace("Helper", "Spaced"), FxTestSupport.callOnFx(bg::getContent));
        assertFalse(FxTestSupport.callOnFx(bg::isDirty));
        closeTab(file);
    }

    // ------------------------------------------------------------------------------------------ helpers

    /** Breakpoints on lines 2 and 3 and a bookmark on line 4, set through the real commands. */
    private static EditorBuffer markAndKeepOpen(Path file) throws Exception {
        FxTestSupport.runOnFx(() -> workflows.openPath(file));
        Await.until(
                () -> onFx(() -> {
                    EditorBuffer b = bufferFor(file);
                    return b != null && workflows.loadingBuffers.isEmpty() && !b.isLoading();
                }),
                "load never finished",
                java.time.Duration.ofSeconds(15));
        settle();
        EditorBuffer b = FxTestSupport.callOnFx(() -> bufferFor(file));
        caret(b, 2);
        FxTestSupport.runOnFx(() -> registry.run("debug.toggleBreakpoint"));
        caret(b, 3);
        FxTestSupport.runOnFx(() -> registry.run("debug.toggleBreakpoint"));
        caret(b, 4);
        FxTestSupport.runOnFx(() -> registry.run("bookmarks.toggle"));
        awaitStored(file, List.of(2, 3), List.of(4));
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
        settle();
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
        Await.until(() -> onFx(() -> tabFor(file) == null), "the tab closed");
        settle();
    }

    /** Waits until the stored breakpoints and bookmarks of {@code file} are these (their persist is debounced). */
    private static void awaitStored(Path file, List<Integer> breakpoints, List<Integer> bookmarks) throws Exception {
        Await.holdsWithin(
                () -> {
                    try {
                        return storedBreakpointLines(file).equals(breakpoints)
                                && storedBookmarkLines(file).equals(bookmarks);
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                },
                java.time.Duration.ofSeconds(10));
    }

    /** Lets queued FX work run and every load finish. */
    private static void settle() throws Exception {
        FxTestSupport.drainFx();
        Await.until(() -> onFx(() -> workflows.loadingBuffers.isEmpty()), "every load finished");
        FxTestSupport.drainFx();
    }

    /** {@code task}'s answer, read on the FX thread, for a condition {@link Await} checks. */
    private static <T> T onFx(java.util.concurrent.Callable<T> task) {
        try {
            return FxTestSupport.callOnFx(task);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
