package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import com.editora.config.Bookmark;
import com.editora.config.FileIdentity;
import com.editora.config.NoteScope;
import com.editora.config.PathKeys;
import com.editora.config.PersonalNote;
import com.editora.config.TextAnchor;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bookmarks and personal notes are stored under the file's path. Whatever renames the path — the Project
 * tree (a file, or a folder with files below it), or Save As — must take them along; only the tab menu's
 * Rename used to. Driven through the same entry point the Project tree calls after it moved the file.
 */
@Tag("fx")
class RenameMigratesFileStateFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            workflows.loadInto(buffer, file);
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static Map<String, List<Bookmark>> bookmarks(FxWindowFixture fx) {
        return FxTestSupport.<com.editora.config.ConfigManager>field(fx.controller, "config")
                .getBookmarks();
    }

    private static Map<String, List<PersonalNote>> notes(FxWindowFixture fx) {
        return FxTestSupport.<com.editora.config.ConfigManager>field(fx.controller, "config")
                .getNotes();
    }

    private static PersonalNote note(Path file, int line, String body) {
        return PersonalNote.create(
                new FileIdentity(file.toString(), PathKeys.canonicalKey(file), 0, 0, ""),
                NoteScope.LINE,
                new TextAnchor(line, 0, line, 0, "", "", "", 0),
                body,
                List.of());
    }

    private static void treeRenamed(FxWindowFixture fx, Path old, Path target) throws Exception {
        Files.move(old, target);
        FxTestSupport.runOnFx(() -> FxTestSupport.call(
                fx.controller, "onProjectFileRenamed", new Class<?>[] {Path.class, Path.class}, old, target));
        FxTestSupport.drainFx();
    }

    private static List<Integer> lines(List<Bookmark> marks) {
        return marks == null ? null : marks.stream().map(Bookmark::line).toList();
    }

    @Test
    void aFileRenamedFromTheProjectTreeKeepsItsBookmarksAndNotes(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setNotesSupport(true));
            Path open = Files.writeString(dir.resolve("open.txt"), "one\ntwo\nthree\n");
            Path closed = Files.writeString(dir.resolve("closed.txt"), "one\ntwo\n");
            EditorBuffer buffer = open(fx, open);
            BookmarkCoordinator marks = FxTestSupport.field(fx.controller, "bookmarkCoordinator");
            FxTestSupport.runOnFx(() -> {
                buffer.toggleBookmark(1);
                marks.persistBookmarks(buffer);
                bookmarks(fx).put(closed.toString(), new ArrayList<>(List.of(new Bookmark(0, "", "one", ""))));
                notes(fx).put(PathKeys.canonicalKey(open), new ArrayList<>(List.of(note(open, 2, "mine"))));
                notes(fx).put(PathKeys.canonicalKey(closed), new ArrayList<>(List.of(note(closed, 1, "theirs"))));
            });
            assertEquals(List.of(1), lines(bookmarks(fx).get(open.toString())), "precondition");

            Path openNow = dir.resolve("open-renamed.txt");
            Path closedNow = dir.resolve("closed-renamed.txt");
            treeRenamed(fx, open, openNow);
            treeRenamed(fx, closed, closedNow);

            assertEquals(openNow, FxTestSupport.callOnFx(buffer::getPath));
            assertEquals(List.of(1), lines(bookmarks(fx).get(openNow.toString())), "the open file's bookmark moved");
            assertNull(bookmarks(fx).get(open.toString()), "and nothing is left under the path that is gone");
            assertEquals(List.of(0), lines(bookmarks(fx).get(closedNow.toString())), "a file with no tab, too");
            assertNull(bookmarks(fx).get(closed.toString()));
            assertEquals(
                    "mine", notes(fx).get(PathKeys.canonicalKey(openNow)).get(0).body());
            assertEquals(
                    "theirs",
                    notes(fx).get(PathKeys.canonicalKey(closedNow)).get(0).body());
            assertEquals(2, notes(fx).size(), "moved, not copied");
        }
    }

    @Test
    void aRenamedFolderTakesTheBookmarksAndNotesOfItsFilesAlong(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setNotesSupport(true));
            Path src =
                    Files.createDirectories(dir.resolve("src").resolve("deep")).getParent();
            Path open = Files.writeString(src.resolve("open.txt"), "one\ntwo\nthree\n");
            Path closed = Files.writeString(src.resolve("deep").resolve("closed.txt"), "one\n");
            Path sibling =
                    Files.writeString(Files.createDirectory(dir.resolve("src2")).resolve("s.txt"), "one\n");
            EditorBuffer buffer = open(fx, open);
            BookmarkCoordinator marks = FxTestSupport.field(fx.controller, "bookmarkCoordinator");
            FxTestSupport.runOnFx(() -> {
                buffer.toggleBookmark(2);
                marks.persistBookmarks(buffer);
                bookmarks(fx).put(closed.toString(), new ArrayList<>(List.of(new Bookmark(0, "", "one", ""))));
                bookmarks(fx).put(sibling.toString(), new ArrayList<>(List.of(new Bookmark(0, "", "one", ""))));
                notes(fx).put(PathKeys.canonicalKey(open), new ArrayList<>(List.of(note(open, 1, "mine"))));
                notes(fx).put(PathKeys.canonicalKey(closed), new ArrayList<>(List.of(note(closed, 0, "theirs"))));
            });

            Path lib = dir.resolve("lib");
            treeRenamed(fx, src, lib);

            Path openNow = lib.resolve("open.txt");
            Path closedNow = lib.resolve("deep").resolve("closed.txt");
            assertEquals(openNow, FxTestSupport.callOnFx(buffer::getPath));
            assertEquals(List.of(2), lines(bookmarks(fx).get(openNow.toString())));
            assertEquals(List.of(0), lines(bookmarks(fx).get(closedNow.toString())));
            assertEquals(List.of(0), lines(bookmarks(fx).get(sibling.toString())), "src2 is not below src");
            assertEquals(3, bookmarks(fx).size(), "nothing left under the old folder");
            assertEquals(
                    "mine", notes(fx).get(PathKeys.canonicalKey(openNow)).get(0).body());
            assertEquals(
                    "theirs",
                    notes(fx).get(PathKeys.canonicalKey(closedNow)).get(0).body());
            assertEquals(2, notes(fx).size());

            // The marks are there for the next session too: what a later toggle writes joins them.
            FxTestSupport.runOnFx(() -> {
                buffer.toggleBookmark(0);
                marks.persistBookmarks(buffer);
            });
            assertEquals(
                    List.of(0, 2),
                    lines(bookmarks(fx).get(openNow.toString())).stream()
                            .sorted()
                            .toList());
        }
    }

    @Test
    void saveAsCopiesBookmarksAndNotesAndTheOriginalKeepsItsOwn(@TempDir Path dir) throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setNotesSupport(true));
            Path a = Files.writeString(dir.resolve("a.txt"), "one\ntwo\nthree\n");
            Path b = dir.resolve("b.txt");
            EditorBuffer buffer = open(fx, a);
            BookmarkCoordinator marks = FxTestSupport.field(fx.controller, "bookmarkCoordinator");
            NotesCoordinator notesCoordinator = FxTestSupport.field(fx.controller, "notesCoordinator");
            FxTestSupport.runOnFx(() -> {
                buffer.toggleBookmark(1);
                marks.persistBookmarks(buffer);
                buffer.getNoteManager().add(note(a, 2, "mine"));
                notesCoordinator.persistNotes(buffer);
            });
            assertNotNull(notes(fx).get(PathKeys.canonicalKey(a)), "precondition");

            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            assertTrue(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, b)));
            ExecutorService worker = FxTestSupport.field(workflows, "autoSaveExecutor");
            async.awaitWorker(worker);
            async.awaitFx();
            assertTrue(Files.exists(b));
            assertFalse(FxTestSupport.callOnFx(buffer::isDirty));

            assertEquals(List.of(1), lines(bookmarks(fx).get(b.toString())), "the copy has the bookmark");
            assertEquals(List.of(1), lines(bookmarks(fx).get(a.toString())), "the original, still on disk, keeps it");
            assertEquals("mine", notes(fx).get(PathKeys.canonicalKey(b)).get(0).body());
            assertEquals("mine", notes(fx).get(PathKeys.canonicalKey(a)).get(0).body());
        }
    }

    /**
     * The copy does not exist when Save As re-points the buffer, so its notes are stored before the file can
     * be resolved. In a folder reached through a link (the temp dir on macOS, {@code /home} on some Linux
     * systems) they were stored under the path as typed and looked up under the real one: the copy had none.
     */
    @Test
    void saveAsIntoAFolderReachedThroughALinkKeepsTheNotes(@TempDir Path dir) throws Exception {
        Path real = Files.createDirectory(dir.resolve("real"));
        Path link;
        try {
            link = Files.createSymbolicLink(dir.resolve("link"), real);
        } catch (java.io.IOException | UnsupportedOperationException e) {
            Assumptions.abort("symbolic links cannot be created here");
            return;
        }
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setNotesSupport(true));
            Path a = Files.writeString(link.resolve("a.txt"), "one\ntwo\nthree\n");
            Path b = link.resolve("b.txt");
            EditorBuffer buffer = open(fx, a);
            NotesCoordinator notesCoordinator = FxTestSupport.field(fx.controller, "notesCoordinator");
            FxTestSupport.runOnFx(() -> {
                buffer.getNoteManager().add(note(a, 2, "mine"));
                notesCoordinator.persistNotes(buffer);
            });

            FileWorkflowCoordinator workflows = FxTestSupport.field(fx.controller, "fileWorkflows");
            assertTrue(FxTestSupport.callOnFx(() -> workflows.applySaveAsTarget(buffer, b)));
            async.awaitWorker(FxTestSupport.<ExecutorService>field(workflows, "autoSaveExecutor"));
            async.awaitFx();
            assertTrue(Files.exists(b));

            String copyKey = PathKeys.canonicalKey(b);
            assertEquals(real.toRealPath().resolve("b.txt").toString(), copyKey);
            assertNotNull(notes(fx).get(copyKey), "stored where the written file is looked up");
            assertEquals("mine", notes(fx).get(copyKey).get(0).body());
            assertEquals(2, notes(fx).size(), "the original and the copy, and nothing under a third spelling");
        }
    }
}
