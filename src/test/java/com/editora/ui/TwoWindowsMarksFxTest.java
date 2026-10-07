package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

import com.editora.config.Bookmark;
import com.editora.config.Breakpoint;
import com.editora.config.ConfigManager;
import com.editora.config.NoteScope;
import com.editora.config.PathKeys;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two windows with no project share one bucket of notes, bookmarks and breakpoints, and each holds its own
 * buffer for a file open in both. A buffer used to write its own copy as the file's whole list, so adding a
 * mark in one window deleted the other window's from the store.
 */
@Tag("fx")
class TwoWindowsMarksFxTest {

    private FxWindowFixture fx;
    private MainController a;
    private MainController b;
    private EditorBuffer bufferA;
    private EditorBuffer bufferB;
    private Path file;

    @BeforeEach
    void setUp(@TempDir Path dir) throws Exception {
        FxTestSupport.bootToolkit();
        Path config = Files.createDirectory(dir.resolve("config"));
        file = dir.resolve("shared.txt");
        Files.writeString(file, "line one\nline two\nline three\nline four\n");
        fx = FxWindowFixture.create(config, false, false, false, List.of(), true, c -> {});
        a = fx.controller;
        b = FxTestSupport.callOnFx(() -> {
            fx.windowManager.newWindow();
            List<?> holders = FxTestSupport.field(fx.windowManager, "windows");
            return (MainController)
                    FxTestSupport.call(holders.get(holders.size() - 1), "controller", new Class<?>[] {});
        });
        assertNotSame(a, b, "a genuinely second window");
        fx.shared.getSettings().setNotesSupport(true);
        bufferA = open(a);
        bufferB = open(b);
        assertNotSame(bufferA, bufferB, "each window has its own buffer for the file");
    }

    @AfterEach
    void tearDown() throws Exception {
        if (fx != null) {
            fx.dispose();
        }
    }

    private EditorBuffer open(MainController window) throws Exception {
        FxTestSupport.runOnFx(() -> window.openAndNavigate(file, 0));
        return await("the file to open", () -> {
            EditorBuffer buffer =
                    (EditorBuffer) FxTestSupport.call(window, "openBufferFor", new Class<?>[] {Path.class}, file);
            return buffer != null
                            && buffer.getPath() != null
                            && buffer.getArea().getText().contains("line four")
                    ? buffer
                    : null;
        });
    }

    /** Polls {@code probe} on the FX thread until it returns non-null (and not {@code false}). */
    private static <T> T await(String what, Callable<T> probe) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(20);
        while (true) {
            T value = FxTestSupport.callOnFx(probe);
            if (value != null && !Boolean.FALSE.equals(value)) {
                return value;
            }
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private static PersonalNote note(EditorBuffer buffer, int line, String lineText, String body) {
        return PersonalNote.create(
                buffer.fileIdentity(),
                NoteScope.LINE,
                new TextAnchor(line, 0, line, 0, lineText, "", ""),
                body,
                List.of());
    }

    private List<String> storedNotes() throws Exception {
        return FxTestSupport.callOnFx(
                () -> fx.shared.notesBucket("").getOrDefault(PathKeys.canonicalKey(file), List.of()).stream()
                        .map(PersonalNote::body)
                        .sorted()
                        .toList());
    }

    private List<Integer> storedBookmarks() throws Exception {
        return FxTestSupport.callOnFx(
                () -> fx.shared.bookmarksBucket("").getOrDefault(file.toString(), List.of()).stream()
                        .map(Bookmark::line)
                        .sorted()
                        .toList());
    }

    private List<Integer> storedBreakpoints() throws Exception {
        return FxTestSupport.callOnFx(
                () -> fx.shared.breakpointsBucket("").getOrDefault(file.toString(), List.of()).stream()
                        .map(Breakpoint::line)
                        .sorted()
                        .toList());
    }

    private static List<String> notesIn(EditorBuffer buffer) {
        return buffer.getNoteManager().snapshot().stream()
                .map(PersonalNote::body)
                .sorted()
                .toList();
    }

    private static List<Integer> bookmarksIn(EditorBuffer buffer) {
        return buffer.getBookmarkManager().snapshot().stream()
                .map(Bookmark::line)
                .sorted()
                .toList();
    }

    private static List<Integer> breakpointsIn(EditorBuffer buffer) {
        return buffer.getBreakpointManager().snapshot().stream()
                .map(Breakpoint::line)
                .sorted()
                .toList();
    }

    @Test
    void aNoteAddedInOneWindowDoesNotDeleteTheOtherWindowsNote() throws Exception {
        FxTestSupport.runOnFx(() -> bufferA.getNoteManager().add(note(bufferA, 0, "line one", "from A")));
        await("A's note to be stored", () -> storedNotesNow().equals(List.of("from A")));
        await("window B to show A's note", () -> notesIn(bufferB).equals(List.of("from A")));

        FxTestSupport.runOnFx(() -> bufferB.getNoteManager().add(note(bufferB, 2, "line three", "from B")));
        await("B's note to be stored", () -> storedNotesNow().contains("from B"));

        assertEquals(List.of("from A", "from B"), storedNotes(), "both windows' notes are in the store");
        await("window A to show B's note", () -> notesIn(bufferA).equals(List.of("from A", "from B")));

        // What survives a restart is the file, not the map in memory.
        assertTrue(FxTestSupport.callOnFx(fx.shared::flushWrites));
        ConfigManager reread = new ConfigManager(fx.configDir);
        reread.load();
        try {
            assertEquals(
                    List.of("from A", "from B"),
                    reread.shared().notesBucket("").get(PathKeys.canonicalKey(file)).stream()
                            .map(PersonalNote::body)
                            .sorted()
                            .toList());
        } finally {
            reread.shared().shutdown();
        }
    }

    private List<String> storedNotesNow() {
        return fx.shared.notesBucket("").getOrDefault(PathKeys.canonicalKey(file), List.of()).stream()
                .map(PersonalNote::body)
                .sorted()
                .toList();
    }

    @Test
    void aNoteDeletedInOneWindowGoesFromTheOtherAndStaysGone() throws Exception {
        PersonalNote fromA = FxTestSupport.callOnFx(() -> note(bufferA, 0, "line one", "from A"));
        FxTestSupport.runOnFx(() -> bufferA.getNoteManager().add(fromA));
        await("window B to show A's note", () -> notesIn(bufferB).equals(List.of("from A")));

        FxTestSupport.runOnFx(() -> bufferA.getNoteManager().remove(fromA.id()));
        await("the note to leave the store", () -> storedNotesNow().isEmpty());
        await("window B to drop it too", () -> notesIn(bufferB).isEmpty());

        // B's next write must not bring it back.
        FxTestSupport.runOnFx(() -> bufferB.getNoteManager().add(note(bufferB, 2, "line three", "from B")));
        await("B's note to be stored", () -> storedNotesNow().contains("from B"));
        assertEquals(List.of("from B"), storedNotes());
    }

    @Test
    void bookmarksOfBothWindowsSurvive() throws Exception {
        FxTestSupport.runOnFx(() -> bufferA.toggleBookmark(0));
        await("A's bookmark to be stored", () -> fx.shared.bookmarksBucket("").containsKey(file.toString()));
        await("window B to show A's bookmark", () -> bookmarksIn(bufferB).equals(List.of(0)));

        FxTestSupport.runOnFx(() -> bufferB.toggleBookmark(2));
        await(
                "B's bookmark to be stored",
                () -> fx.shared.bookmarksBucket("").get(file.toString()).stream()
                        .anyMatch(bm -> bm.line() == 2));

        assertEquals(List.of(0, 2), storedBookmarks(), "both windows' bookmarks are in the store");
        await("window A to show B's bookmark", () -> bookmarksIn(bufferA).equals(List.of(0, 2)));

        // A removes its own: only that one goes, in the store and in the other window.
        FxTestSupport.runOnFx(() -> bufferA.toggleBookmark(0));
        await("A's bookmark to leave window B", () -> bookmarksIn(bufferB).equals(List.of(2)));
        assertEquals(List.of(2), storedBookmarks());
    }

    @Test
    void breakpointsOfBothWindowsSurvive() throws Exception {
        FxTestSupport.runOnFx(() -> bufferA.toggleBreakpoint(0));
        await(
                "A's breakpoint to be stored",
                () -> fx.shared.breakpointsBucket("").containsKey(file.toString()));
        await("window B to show A's breakpoint", () -> breakpointsIn(bufferB).equals(List.of(0)));

        FxTestSupport.runOnFx(() -> bufferB.toggleBreakpoint(2));
        await(
                "B's breakpoint to be stored",
                () -> fx.shared.breakpointsBucket("").get(file.toString()).stream()
                        .anyMatch(bp -> bp.line() == 2));

        assertEquals(List.of(0, 2), storedBreakpoints(), "both windows' breakpoints are in the store");
        await("window A to show B's breakpoint", () -> breakpointsIn(bufferA).equals(List.of(0, 2)));

        FxTestSupport.runOnFx(() -> bufferA.toggleBreakpoint(0));
        await("A's breakpoint to leave window B", () -> breakpointsIn(bufferB).equals(List.of(2)));
        assertEquals(List.of(2), storedBreakpoints());
    }
}
