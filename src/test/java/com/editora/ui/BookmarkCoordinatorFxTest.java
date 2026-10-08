package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.ButtonBar;

import com.editora.config.Bookmark;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BookmarkCoordinator} against a recording window: what each entry point leaves in the bookmark
 * store, in an open buffer, in the status line, and what it tells the other windows. The store is the
 * in-memory map {@code bookmarks.json} is written from.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BookmarkCoordinatorFxTest {

    @TempDir
    Path dir;

    private final Map<String, Map<String, List<Bookmark>>> all = new LinkedHashMap<>();
    private final Map<Path, EditorBuffer> open = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private final List<EditorBuffer> created = new ArrayList<>();
    private EditorBuffer active;
    private String promptAnswer;
    private String promptInitial;
    private int changed;
    private BookmarkCoordinator coordinator;

    private final CoordinatorHostStub host = new CoordinatorHostStub() {
        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            created.forEach(action);
        }

        @Override
        public void setStatus(String message) {
            events.add("status: " + message);
        }
    };

    private final BookmarkCoordinator.Ops ops = new BookmarkCoordinator.Ops() {
        @Override
        public void openPath(Path file) {
            events.add("open " + file.getFileName());
        }

        @Override
        public void navigateToLine(int line) {
            events.add("navigate " + line);
        }

        @Override
        public void openInProjectWindow(String projectKey, Path file, int line) {
            events.add("openIn[" + projectKey + "] " + file.getFileName() + ":" + line);
        }

        @Override
        public EditorBuffer bufferForPath(Path file) {
            return open.get(file);
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            events.add("prompt " + title);
            promptInitial = initial;
            if (promptAnswer != null) {
                onAccept.accept(promptAnswer);
            }
        }

        @Override
        public Map<String, List<Bookmark>> bookmarks() {
            return all.get("proj");
        }

        @Override
        public Map<String, Map<String, List<Bookmark>>> allBookmarks() {
            return all;
        }

        @Override
        public String currentProjectKey() {
            return "proj";
        }

        @Override
        public String projectName(String key) {
            return key.isEmpty() ? "General" : key;
        }

        @Override
        public void saveBookmarks() {
            events.add("save");
        }

        @Override
        public void bookmarksStored(Map<String, ?> bucket, String fileKey) {
            String which = bucket == all.get("proj") ? "proj" : bucket == all.get("") ? "general" : "?";
            events.add("stored " + which + " "
                    + (fileKey == null ? "*" : Path.of(fileKey).getFileName()));
        }
    };

    @BeforeAll
    void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void setUp() throws Exception {
        all.clear();
        all.put("", new LinkedHashMap<>());
        all.put("proj", new LinkedHashMap<>());
        open.clear();
        events.clear();
        active = null;
        promptAnswer = null;
        promptInitial = null;
        changed = 0;
        coordinator = FxTestSupport.callOnFx(() -> new BookmarkCoordinator(host, ops));
        FxTestSupport.runOnFx(() -> coordinator.setOnChanged(() -> changed++));
    }

    @AfterEach
    void disposeBuffers() throws Exception {
        FxTestSupport.runOnFx(() -> {
            coordinator.flushPendingPersist();
            created.forEach(EditorBuffer::dispose);
            created.clear();
        });
    }

    private Path file(String name) {
        return dir.resolve(name);
    }

    private String key(String name) {
        return file(name).toString();
    }

    private Map<String, List<Bookmark>> store() {
        return all.get("proj");
    }

    private List<Integer> storedLines(String name) {
        List<Bookmark> marks = store().get(key(name));
        return marks == null ? null : marks.stream().map(Bookmark::line).toList();
    }

    /** An open buffer on {@code name} with {@code text}, known to the window. */
    private EditorBuffer buffer(String name, String text) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            if (name != null) {
                b.setPath(file(name));
                open.put(file(name), b);
            }
            b.setContent(text);
            created.add(b);
            return b;
        });
    }

    private List<Integer> liveLines(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() ->
                b.getBookmarkManager().snapshot().stream().map(Bookmark::line).toList());
    }

    private void fx(Runnable r) throws Exception {
        FxTestSupport.runOnFx(r);
    }

    private BookmarksPanel.Actions panelActions() {
        return FxTestSupport.field(coordinator.panel(), "actions");
    }

    // --- adding from the file manager / a preview --------------------------------------------------------

    @Test
    void addingToAClosedFileStoresOneBookmarkPerLineAndTellsTheOtherWindows() throws Exception {
        fx(() -> {
            coordinator.addBookmark(null);
            coordinator.addBookmark(null, 3);
        });
        assertEquals(List.of(), events, "no file: nothing happens");
        assertFalse(coordinator.hasBookmarks(null));
        assertFalse(coordinator.hasBookmarks(file("a.txt")));

        fx(() -> coordinator.addBookmark(file("a.txt")));
        assertEquals(List.of(0), storedLines("a.txt"));
        assertEquals(List.of("save", "stored proj a.txt"), events);
        assertEquals(1, changed);
        assertTrue(coordinator.hasBookmarks(file("a.txt")));
        assertEquals(List.of(key("a.txt")), List.copyOf(coordinator.storedKeys()));

        fx(() -> {
            coordinator.addBookmark(file("a.txt"), 0); // already there
            coordinator.addBookmark(file("a.txt"), -7); // clamped to the first line: already there
            coordinator.addBookmark(file("a.txt"), 4);
        });
        assertEquals(List.of(0, 4), storedLines("a.txt"));
        assertEquals(4, events.size(), "only the new line was written");
    }

    @Test
    void aStoredKeyThatIsNotNormalisedIsStillFound() throws Exception {
        Path odd = dir.resolve("sub").resolve("..").resolve("a.txt");
        store().put(odd.toString(), new ArrayList<>(List.of(new Bookmark(2, "", ""))));
        assertTrue(coordinator.hasBookmarks(odd));
        fx(() -> coordinator.addBookmark(odd, 2));
        assertEquals(List.of(), events, "the line is already bookmarked under the key as stored");
    }

    @Test
    void aFolderGetsOneFolderBookmark() throws Exception {
        Path folder = Files.createDirectory(file("docs"));
        fx(() -> {
            coordinator.addBookmark(folder);
            coordinator.addBookmark(folder);
        });
        assertEquals(List.of(Bookmark.folder()), store().get(folder.toString()));
        assertEquals(List.of("save", "stored proj docs"), events, "the second request changed nothing");
        assertTrue(coordinator.hasBookmarks(folder));
    }

    @Test
    void addingToAnOpenFileGoesThroughItsBufferAndStaysInsideTheText() throws Exception {
        EditorBuffer b = buffer("a.txt", "one\ntwo\nthree");
        assertFalse(coordinator.hasBookmarks(file("a.txt")), "open, and its buffer has none");
        fx(() -> {
            coordinator.addBookmark(file("a.txt"), 1);
            coordinator.addBookmark(file("a.txt"), 1);
            coordinator.addBookmark(file("a.txt"), 99);
        });
        assertEquals(List.of(1, 2), liveLines(b), "a line past the end lands on the last one");
        assertNull(store().get(key("a.txt")), "the buffer writes the store when it persists, not the request");
        assertTrue(coordinator.hasBookmarks(file("a.txt")));
    }

    // --- the editor's entry points ----------------------------------------------------------------------

    @Test
    void theContextMenuAddsAtOnceButAsksBeforeRemoving() throws Exception {
        EditorBuffer b = buffer("a.txt", "one\ntwo\nthree");
        fx(() -> coordinator.onBookmarkToggleRequest(b, 1));
        assertEquals(List.of(1), liveLines(b));

        String question = tr("dialog.removeBookmark.body", 2);
        assertTrue(FxDialogs.duringContent(
                        () -> coordinator.onBookmarkToggleRequest(b, 1), question, ButtonBar.ButtonData.CANCEL_CLOSE)
                != null);
        assertEquals(List.of(1), liveLines(b), "Cancel keeps it");
        FxDialogs.duringContent(() -> coordinator.onBookmarkToggleRequest(b, 1), question, null);
        assertEquals(List.of(1), liveLines(b), "closing the question keeps it");
        FxDialogs.duringContent(
                () -> coordinator.onBookmarkToggleRequest(b, 1), question, ButtonBar.ButtonData.OK_DONE);
        assertEquals(List.of(), liveLines(b));
    }

    @Test
    void togglingAtTheCaretNeedsASavedFile() throws Exception {
        fx(coordinator::toggleAtCaret);
        assertEquals(List.of(), events, "no buffer at all");

        active = buffer(null, "unsaved");
        fx(coordinator::toggleAtCaret);
        assertEquals(List.of("status: " + tr("status.saveBeforeBookmark")), events);
        assertEquals(List.of(), liveLines(active));

        active = buffer("a.txt", "one\ntwo");
        fx(() -> {
            active.getArea().moveTo(1, 0);
            coordinator.toggleAtCaret();
        });
        assertEquals(List.of(1), liveLines(active));
        fx(coordinator::toggleAtCaret);
        assertEquals(List.of(), liveLines(active), "the keyboard toggle removes without asking");
    }

    @Test
    void editingTheNoteAtTheCaretCreatesTheBookmarkOrRewritesItsNote() throws Exception {
        fx(coordinator::editNoteAtCaret);
        active = buffer(null, "unsaved");
        fx(coordinator::editNoteAtCaret);
        assertEquals(List.of(), events, "nothing to attach a note to");

        active = buffer("a.txt", "one\ntwo");
        promptAnswer = "  first note ";
        fx(() -> {
            active.getArea().moveTo(0, 0);
            coordinator.editNoteAtCaret();
        });
        assertEquals("", promptInitial);
        assertEquals(
                List.of(new Bookmark(0, "first note", "one")),
                FxTestSupport.callOnFx(() -> active.getBookmarkManager().snapshot()));

        promptAnswer = "rewritten";
        fx(coordinator::editNoteAtCaret);
        assertEquals("first note", promptInitial, "the prompt starts from the note it has");
        assertEquals(
                "rewritten",
                FxTestSupport.callOnFx(
                        () -> active.getBookmarkManager().snapshot().getFirst().note()));

        promptAnswer = null; // cancelled
        fx(coordinator::editNoteAtCaret);
        assertEquals(
                "rewritten",
                FxTestSupport.callOnFx(
                        () -> active.getBookmarkManager().snapshot().getFirst().note()));
    }

    @Test
    void jumpingWithinAFileWrapsAndSaysWhenThereIsNothingToJumpTo() throws Exception {
        fx(() -> {
            coordinator.jump(true);
            coordinator.clearInFile();
        });
        assertEquals(List.of(), events, "no buffer");

        active = buffer("a.txt", "one\ntwo\nthree\nfour");
        fx(() -> coordinator.jump(true));
        assertEquals(List.of("status: " + tr("status.noBookmarksInFile")), events);

        events.clear();
        fx(() -> {
            active.toggleBookmark(1);
            active.toggleBookmark(3);
            active.getArea().moveTo(2, 0);
            coordinator.jump(true);
            coordinator.jump(false);
            active.getArea().moveTo(3, 0);
            coordinator.jump(true);
        });
        assertEquals(List.of("navigate 3", "navigate 1", "navigate 1"), events, "forward from the last wraps");

        fx(coordinator::clearInFile);
        assertEquals(List.of(), liveLines(active));
    }

    // --- mnemonics --------------------------------------------------------------------------------------

    @Test
    void aMnemonicNeedsAFileAndASingleLetterOrDigit() throws Exception {
        fx(coordinator::setMnemonicAtCaret);
        active = buffer(null, "unsaved");
        fx(coordinator::setMnemonicAtCaret);
        assertEquals(
                List.of("status: " + tr("status.bookmarks.noFile"), "status: " + tr("status.bookmarks.noFile")),
                events);

        events.clear();
        active = buffer("a.txt", "one\ntwo");
        promptAnswer = "ab";
        fx(coordinator::setMnemonicAtCaret);
        assertEquals(
                List.of(
                        "prompt " + tr("dialog.bookmarkMnemonic.title"),
                        "status: " + tr("status.bookmarks.badMnemonic")),
                events);
        assertEquals(List.of(), liveLines(active), "a refused mnemonic bookmarks nothing");
    }

    @Test
    void settingAMnemonicBookmarksTheLineTakesTheLetterFromItsOldHolderAndKeepsTheFileOrder() throws Exception {
        // Enough files that an order which survives only by luck would not survive.
        List<String> names = List.of("z.txt", "m.txt", "a.txt", "q.txt", "c.txt", "x.txt", "b.txt", "k.txt");
        for (String name : names) {
            store().put(key(name), new ArrayList<>(List.of(new Bookmark(0, "", ""))));
        }
        store().put(key("holder.txt"), new ArrayList<>(List.of(new Bookmark(5, "old holder", "", "q"))));
        List<String> order = List.copyOf(store().keySet());

        active = buffer("m.txt", "one\ntwo\nthree");
        fx(() -> {
            coordinator.restoreBookmarks(active);
            active.getArea().moveTo(2, 0);
        });
        promptAnswer = " Q ";
        events.clear();
        fx(coordinator::setMnemonicAtCaret);

        assertEquals(
                List.of(new Bookmark(0, "", ""), new Bookmark(2, "", "three", "q")),
                FxTestSupport.callOnFx(() -> active.getBookmarkManager().snapshot()),
                "the caret line is bookmarked and carries the mnemonic");
        assertEquals("", store().get(key("holder.txt")).getFirst().mnemonic(), "the previous holder gave it up");
        assertEquals("q", store().get(key("m.txt")).get(1).mnemonic());
        assertEquals(order, List.copyOf(store().keySet()), "the files keep the order the user gave them");
        assertEquals("status: " + tr("status.bookmarks.mnemonicSet", "Q"), events.getLast());
        assertTrue(events.contains("stored proj *"), "another file's bookmark changed too: " + events);

        promptAnswer = "";
        fx(coordinator::setMnemonicAtCaret);
        assertEquals("", store().get(key("m.txt")).get(1).mnemonic());
        assertEquals("status: " + tr("status.bookmarks.mnemonicCleared"), events.getLast());
        assertEquals(order, List.copyOf(store().keySet()));
    }

    @Test
    void aMnemonicIsRefusedOnANarrowedBufferAndTheStoreIsLeftAlone() throws Exception {
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(3, "", "four"))));
        store().put(key("b.txt"), new ArrayList<>(List.of(new Bookmark(1, "", ""))));
        Map<String, List<Bookmark>> before = new LinkedHashMap<>(store());
        active = buffer("a.txt", "one\ntwo\nthree\nfour");
        fx(() -> {
            coordinator.restoreBookmarks(active);
            assertTrue(active.narrowTo(4, 13)); // "two\nthree": its line 0 is the file's line 1
            active.getArea().moveTo(0, 0);
        });
        promptAnswer = "q";
        events.clear();
        fx(coordinator::setMnemonicAtCaret);

        assertEquals(before, store(), "every stored bookmark is still there");
        assertEquals(List.of("status: " + tr("status.bookmarks.mnemonicNarrowed")), events);
    }

    @Test
    void goingToAMnemonicOpensItsHolderOrSaysNobodyHasIt() throws Exception {
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(7, "", "", "3"))));
        fx(() -> {
            coordinator.gotoMnemonic("3");
            coordinator.gotoMnemonic("x");
        });
        assertEquals(List.of("openIn[proj] a.txt:7", "status: " + tr("status.bookmarks.noMnemonic", "X")), events);
    }

    // --- the tool window's actions ----------------------------------------------------------------------

    @Test
    void thePanelOpensABookmarkInItsOwnProjectsWindow() throws Exception {
        fx(() -> {
            panelActions().openAndJump("", file("g.txt"), 4);
            panelActions().openAndJump("proj", file("a.txt"), 1);
        });
        assertEquals(List.of("openIn[] g.txt:4", "openIn[proj] a.txt:1"), events);
    }

    @Test
    void notesAndDeletionsOfAClosedFileRewriteTheStore() throws Exception {
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(1, "", ""), new Bookmark(5, "", ""))));
        fx(() -> panelActions().setNote("proj", file("a.txt"), 5, "noted"));
        assertEquals(List.of(new Bookmark(1, "", ""), new Bookmark(5, "noted", "")), store().get(key("a.txt")));
        assertEquals(List.of("save", "stored proj a.txt"), events);

        fx(() -> panelActions().delete("proj", file("a.txt"), 1));
        assertEquals(List.of(5), storedLines("a.txt"));
        fx(() -> panelActions().delete("proj", file("a.txt"), 5));
        assertFalse(store().containsKey(key("a.txt")), "a file with no bookmarks left is dropped from the store");

        events.clear();
        fx(() -> {
            panelActions().delete("proj", file("a.txt"), 5); // the file is gone from the store
            panelActions().setNote("nowhere", file("a.txt"), 5, "x"); // a project with no bookmarks
            panelActions().deleteAll("proj", file("a.txt"));
            panelActions().deleteAll("nowhere", file("a.txt"));
        });
        assertEquals(List.of(), events, "nothing to change: nothing written");
    }

    @Test
    void anotherProjectsBookmarksAreEditedInTheirOwnBucketEvenWhenTheFileIsOpenHere() throws Exception {
        all.get("").put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(1, "", ""), new Bookmark(2, "", ""))));
        EditorBuffer b = buffer("a.txt", "one\ntwo\nthree");
        fx(() -> b.toggleBookmark(1));

        fx(() -> {
            panelActions().setNote("", file("a.txt"), 1, "general note");
            panelActions().delete(null, file("a.txt"), 2); // a null key means General
        });
        assertEquals(List.of(new Bookmark(1, "general note", "")), all.get("").get(key("a.txt")));
        assertEquals(
                "",
                FxTestSupport.callOnFx(
                        () -> b.getBookmarkManager().snapshot().getFirst().note()));
        assertTrue(events.contains("stored general a.txt"), events.toString());

        fx(() -> panelActions().deleteAll("", file("a.txt")));
        assertFalse(all.get("").containsKey(key("a.txt")));
        assertEquals(List.of(1), liveLines(b), "this window's buffer is in another bucket and keeps its own");
    }

    @Test
    void anOpenFilesBookmarksAreEditedThroughItsBuffer() throws Exception {
        EditorBuffer b = buffer("a.txt", "one\ntwo\nthree");
        fx(() -> {
            b.toggleBookmark(0);
            b.toggleBookmark(2);
            panelActions().setNote("proj", file("a.txt"), 2, "live");
            panelActions().delete("proj", file("a.txt"), 0);
        });
        assertEquals(
                List.of(new Bookmark(2, "live", "three")),
                FxTestSupport.callOnFx(() -> b.getBookmarkManager().snapshot()));
        fx(() -> panelActions().deleteAll("proj", file("a.txt")));
        assertEquals(List.of(), liveLines(b));
        assertFalse(events.contains("save"), "the buffer persists its own changes; the panel wrote nothing itself");
    }

    @Test
    void reorderingRewritesTheStoredOrderAndIgnoresPositionsThatDoNotExist() throws Exception {
        store().put(
                        key("a.txt"),
                        new ArrayList<>(
                                List.of(new Bookmark(1, "", ""), new Bookmark(2, "", ""), new Bookmark(3, "", ""))));
        store().put(key("b.txt"), new ArrayList<>(List.of(new Bookmark(9, "", ""))));

        fx(() -> panelActions().moveBookmark(file("a.txt"), 0, 2));
        assertEquals(List.of(2, 3, 1), storedLines("a.txt"));
        fx(() -> panelActions().moveFile(1, 0));
        assertEquals(List.of(key("b.txt"), key("a.txt")), List.copyOf(store().keySet()));
        assertEquals(List.of("save", "save"), events);
        assertEquals(2, changed);

        events.clear();
        fx(() -> {
            panelActions().moveBookmark(file("a.txt"), 0, 3);
            panelActions().moveBookmark(file("a.txt"), -1, 0);
            panelActions().moveBookmark(file("missing.txt"), 0, 0);
            panelActions().moveFile(0, 2);
            panelActions().moveFile(-1, 0);
        });
        assertEquals(List.of(), events);
        assertEquals(List.of(2, 3, 1), storedLines("a.txt"));
    }

    // --- persistence around a buffer --------------------------------------------------------------------

    @Test
    void aBookmarkThatFollowedItsLineIsWrittenBackOnOpen() throws Exception {
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(0, "kept", "target line"))));
        EditorBuffer b = buffer("a.txt", "inserted above\ntarget line\nlast");
        fx(() -> coordinator.restoreBookmarks(b));
        assertEquals(List.of(1), liveLines(b), "the bookmark moved with its text");
        assertEquals(List.of(1), storedLines("a.txt"), "and the store was corrected");
        assertEquals(List.of("save", "stored proj a.txt"), events);

        events.clear();
        fx(() -> {
            coordinator.restoreBookmarks(b); // now exact: nothing to write
            coordinator.restoreBookmarks(new EditorBufferHolder().untitled());
        });
        assertEquals(List.of(), events);
    }

    /** Builds an untitled buffer on the FX thread and remembers it for disposal. */
    private final class EditorBufferHolder {
        EditorBuffer untitled() {
            EditorBuffer b = new EditorBuffer();
            created.add(b);
            return b;
        }
    }

    @Test
    void aNarrowedOrUntitledBufferIsNotPersisted() throws Exception {
        EditorBuffer untitled = buffer(null, "x");
        EditorBuffer narrowed = buffer("a.txt", "one\ntwo\nthree\nfour");
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(3, "", "four"))));
        fx(() -> {
            coordinator.restoreBookmarks(narrowed);
            assertTrue(narrowed.narrowTo(4, 11)); // "two\nthree"
            coordinator.persistBookmarks(untitled);
            coordinator.persistBookmarks(narrowed);
        });
        assertEquals(List.of(), events);
        assertEquals(List.of(3), storedLines("a.txt"), "region-relative lines never reach the store");
    }

    @Test
    void aDebouncedPersistIsWrittenWhenFlushedOncePerBuffer() throws Exception {
        EditorBuffer a = buffer("a.txt", "one\ntwo");
        EditorBuffer b = buffer("b.txt", "one\ntwo");
        fx(() -> {
            a.toggleBookmark(0);
            b.toggleBookmark(1);
            coordinator.schedulePersistBookmarks(a);
            coordinator.schedulePersistBookmarks(b);
            coordinator.schedulePersistBookmarks(a);
        });
        assertNull(store().get(key("a.txt")), "nothing is written while the edit burst is still going");
        assertEquals(3, changed, "the views are told at once");

        fx(coordinator::flushPendingPersist);
        assertEquals(List.of(0), storedLines("a.txt"));
        assertEquals(List.of(1), storedLines("b.txt"));
        assertEquals(2, events.stream().filter("save"::equals).count());

        events.clear();
        fx(() -> {
            a.clearBookmarks();
            coordinator.persistBookmarks(a);
            coordinator.flushPendingPersist(); // nothing outstanding
        });
        assertFalse(store().containsKey(key("a.txt")), "a file whose last bookmark went is dropped");
        assertEquals(List.of("save", "stored proj a.txt"), events);
    }

    @Test
    void aChangeFromAnotherWindowReachesTheMatchingOpenBuffers() throws Exception {
        EditorBuffer a = buffer("a.txt", "one\ntwo\nthree");
        EditorBuffer b = buffer("b.txt", "one\ntwo\nthree");
        EditorBuffer untitled = buffer(null, "x");
        EditorBuffer narrowed = buffer("n.txt", "one\ntwo\nthree");
        fx(() -> assertTrue(narrowed.narrowTo(0, 3)));
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(2, "", "three"))));
        store().put(key("b.txt"), new ArrayList<>(List.of(new Bookmark(1, "", "two"))));
        store().put(key("n.txt"), new ArrayList<>(List.of(new Bookmark(0, "", "one"))));

        fx(() -> coordinator.storeChangedElsewhere(store(), key("a.txt")));
        assertEquals(List.of(2), liveLines(a));
        assertEquals(List.of(), liveLines(b), "only the named file is re-read");
        assertEquals(1, changed);

        fx(() -> coordinator.storeChangedElsewhere(store(), null));
        assertEquals(List.of(1), liveLines(b), "no file named: every open file is re-read");
        assertEquals(List.of(), liveLines(untitled));
        assertEquals(List.of(), liveLines(narrowed), "a narrowed buffer is left alone until it widens");

        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(0, "", "one"))));
        fx(() -> coordinator.storeChangedElsewhere(all.get(""), key("a.txt")));
        assertEquals(List.of(2), liveLines(a), "another project's bucket is not this window's buffers' business");
        assertEquals(3, changed, "but the panel, which shows every project, is redrawn");
    }

    @Test
    void aRenameMovesTheStoredBookmarksOfTheFileAndOfEverythingBelowAFolder() throws Exception {
        Path folder = dir.resolve("src");
        store().put(folder.resolve("A.java").toString(), new ArrayList<>(List.of(new Bookmark(1, "", ""))));
        store().put(key("other.txt"), new ArrayList<>(List.of(new Bookmark(2, "", ""))));

        fx(() -> coordinator.pathRenamed(folder, dir.resolve("main")));
        assertEquals(
                List.of(dir.resolve("main").resolve("A.java").toString(), key("other.txt")),
                store().keySet().stream().sorted().toList());
        assertEquals(List.of("save", "stored proj *"), events);

        events.clear();
        fx(() -> coordinator.pathRenamed(dir.resolve("unrelated"), dir.resolve("elsewhere")));
        assertEquals(List.of(), events, "nothing stored under the renamed path");
    }

    @Test
    void saveAsCarriesTheBookmarksToTheNewPathAndDropsThoseOfAFileThatIsGone() throws Exception {
        Path kept = Files.writeString(file("kept.txt"), "one\ntwo");
        EditorBuffer b = buffer("kept.txt", "one\ntwo");
        fx(() -> {
            b.toggleBookmark(1);
            coordinator.persistBookmarks(b);
            b.setPath(file("copy.txt"));
            coordinator.bufferPathChanged(b, kept);
        });
        assertEquals(List.of(1), storedLines("copy.txt"));
        assertEquals(List.of(1), storedLines("kept.txt"), "the file it left is still on disk and keeps its own");

        // A path that was never written (a Save As rolled back): its entry goes.
        store().put(key("ghost.txt"), new ArrayList<>(List.of(new Bookmark(0, "", ""))));
        fx(() -> {
            b.setPath(file("copy.txt"));
            coordinator.bufferPathChanged(b, file("ghost.txt"));
        });
        assertFalse(store().containsKey(key("ghost.txt")));

        events.clear();
        fx(() -> coordinator.bufferPathChanged(b, null)); // a first save of an untitled buffer
        assertEquals(List.of(1), storedLines("copy.txt"));
    }

    @Test
    void saveAsOfANarrowedBufferCopiesTheStoredListBecauseItsLiveLinesAreRegionRelative() throws Exception {
        Path original = Files.writeString(file("orig.txt"), "one\ntwo\nthree");
        EditorBuffer b = buffer("orig.txt", "one\ntwo\nthree");
        store().put(key("orig.txt"), new ArrayList<>(List.of(new Bookmark(2, "", "three"))));
        fx(() -> {
            coordinator.restoreBookmarks(b);
            assertTrue(b.narrowTo(4, 13));
            b.setPath(file("copy.txt"));
            coordinator.bufferPathChanged(b, original);
        });
        assertEquals(List.of(2), storedLines("copy.txt"), "the file's own line numbers, not the region's");
        assertEquals(List.of(2), storedLines("orig.txt"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void theJumpPickerListsThisProjectsBookmarksInStoredOrderAndOpensTheChosenOne() throws Exception {
        Path folder = Files.createDirectory(file("docs"));
        store().put(key("b.txt"), new ArrayList<>(List.of(new Bookmark(4, "", "int x;"), new Bookmark(1, "why", "y"))));
        store().put(folder.toString(), new ArrayList<>(List.of(Bookmark.folder())));
        store().put(key("a.txt"), new ArrayList<>(List.of(new Bookmark(0, "", ""))));
        store().put(key("gone.txt"), null); // a damaged entry is skipped, not a crash
        all.get("").put(key("general.txt"), new ArrayList<>(List.of(new Bookmark(0, "not listed", ""))));

        QuickOpen<Object> picker = FxTestSupport.field(coordinator, "jumpPalette");
        java.util.function.Supplier<List<Object>> items = FxTestSupport.field(picker, "itemsSupplier");
        java.util.function.Function<Object, String> label = FxTestSupport.field(picker, "label");
        java.util.function.Function<Object, String> detail = FxTestSupport.field(picker, "detail");
        Consumer<Object> choose = FxTestSupport.field(picker, "onChoose");

        List<Object> rows = FxTestSupport.callOnFx(items::get);
        assertEquals(
                List.of(
                        "int x; | b.txt:5",
                        "why | b.txt:2",
                        tr("bookmarks.folder") + " | " + folder,
                        "line 1 | a.txt:1"),
                rows.stream().map(r -> label.apply(r) + " | " + detail.apply(r)).toList());

        fx(() -> choose.accept(rows.get(1)));
        assertEquals(List.of("openIn[proj] b.txt:1"), events);
    }

    @Test
    void clearingTheChangeListenerIsSafe() throws Exception {
        fx(() -> {
            coordinator.setOnChanged(null);
            coordinator.addBookmark(file("a.txt"), 1);
        });
        assertEquals(0, changed);
        assertEquals(List.of(1), storedLines("a.txt"));
        assertSame(coordinator.panel(), coordinator.panel());
    }
}
