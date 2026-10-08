package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import com.editora.history.HistoryBlobStore;
import com.editora.macro.Macro;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two Editora processes on one config directory: neither may put back data it did not itself change.
 *
 * <p>Each "process" is its own {@link SharedConfig} over the same directory — the stand-in the instance-lock
 * tests use, and an exact one here, since nothing is shared between two configs but the files. Every store
 * used to be written whole from memory, so the second process reverted the first one's preferences, session,
 * notes, projects, dictionary and local-history index with its next incidental save.
 */
class TwoWritersTest {

    @TempDir
    Path dir;

    private SharedConfig sharedA;
    private SharedConfig sharedB;
    private ConfigManager a;
    private ConfigManager b;

    @BeforeEach
    void startBoth() {
        sharedA = new SharedConfig(dir, false);
        assertTrue(sharedA.claimInstance());
        a = new ConfigManager(sharedA);
        a.load();
        assertTrue(a.save());
        sharedB = new SharedConfig(dir, false);
        assertFalse(sharedB.claimInstance(), "the second process on the directory");
        b = new ConfigManager(sharedB);
        b.load();
    }

    @AfterEach
    void stopBoth() {
        sharedB.shutdown();
        sharedA.shutdown();
    }

    private String read(String name) throws Exception {
        return Files.readString(dir.resolve(name));
    }

    @Test
    void anIncidentalSaveInOneProcessDoesNotRevertTheOthersSettings() throws Exception {
        a.getSettings().setKeybindings(new LinkedHashMap<>(Map.of("file.save", "Ctrl+Alt+S")));
        a.getSettings().setAiApiKey("sk-user-key-A");
        a.getSettings().setFontSize(19);
        assertTrue(a.save());

        // What MainController.requestSave does on any tab switch or caret move, and what quit does.
        b.saveAsync();
        assertTrue(sharedB.flushWrites());
        assertTrue(b.save());

        String settings = read("settings.json");
        assertTrue(settings.contains("sk-user-key-A"), settings);
        assertTrue(settings.contains("Ctrl+Alt+S"), settings);
        assertTrue(settings.contains("\"fontSize\" : 19"), settings);
    }

    @Test
    void settingsChangedInBothProcessesAreMergedPerKey() throws Exception {
        a.getSettings().setAiApiKey("sk-user-key-A");
        a.getSettings().setKeybindings(new LinkedHashMap<>(Map.of("file.save", "Ctrl+Alt+S")));
        assertTrue(a.save());

        b.getSettings().setFontSize(21);
        b.getSettings().setKeybindings(new LinkedHashMap<>(Map.of("edit.copy", "Ctrl+Alt+C")));
        assertTrue(b.save());

        // …and the first process saves again, with nothing new of its own.
        assertTrue(a.save());

        ConfigManager fresh = new ConfigManager(dir);
        fresh.load();
        try {
            assertEquals("sk-user-key-A", fresh.getSettings().getAiApiKey());
            assertEquals(21, fresh.getSettings().getFontSize());
            assertEquals(
                    Map.of("file.save", "Ctrl+Alt+S", "edit.copy", "Ctrl+Alt+C"),
                    fresh.getSettings().getKeybindings());
        } finally {
            fresh.shared().shutdown();
        }
    }

    @Test
    void theSameSettingChangedInBothIsLastSavedWins() throws Exception {
        a.getSettings().setFontSize(19);
        assertTrue(a.save());
        b.getSettings().setFontSize(21);
        assertTrue(b.save());

        assertTrue(read("settings.json").contains("\"fontSize\" : 21"));
    }

    @Test
    void anIncidentalSessionSaveDoesNotRevertTheOtherProcesssRunStateInTheSameWindow() throws Exception {
        a.getWorkspaceState().getDebugWatches().add("user.watch.expr");
        a.getWorkspaceState().getProgramArgs().put("/x/Main.java", "--my --args");
        assertTrue(a.save());

        b.saveAsync();
        assertTrue(sharedB.flushWrites());
        // The other process then changes something of its own in that session: a window size.
        b.getWorkspaceState().setWindowWidth(1234);
        assertTrue(b.save());

        String session = read("workspace-state.json");
        assertTrue(session.contains("user.watch.expr"), session);
        assertTrue(session.contains("--my --args"), session);
        assertTrue(session.contains("1234"), session);
    }

    @Test
    void notesBookmarksAndBreakpointsFromBothProcessesSurvive() throws Exception {
        a.getNotes()
                .computeIfAbsent("/x/a.txt", k -> new ArrayList<>())
                .add(PersonalNote.create(null, NoteScope.LINE, null, "NOTE-FROM-A", List.of()));
        a.saveNotes();
        b.getNotes()
                .computeIfAbsent("/x/b.txt", k -> new ArrayList<>())
                .add(PersonalNote.create(null, NoteScope.LINE, null, "NOTE-FROM-B", List.of()));
        // The same file in both: a second note on it from the other process.
        b.getNotes()
                .computeIfAbsent("/x/a.txt", k -> new ArrayList<>())
                .add(PersonalNote.create(null, NoteScope.LINE, null, "SECOND-ON-A-FROM-B", List.of()));
        b.saveNotes();

        String notes = read("notes.json");
        assertTrue(notes.contains("NOTE-FROM-A"), notes);
        assertTrue(notes.contains("NOTE-FROM-B"), notes);
        assertTrue(notes.contains("SECOND-ON-A-FROM-B"), notes);

        a.getBookmarks().computeIfAbsent("/x/a.txt", k -> new ArrayList<>()).add(new Bookmark(3, "from A", "line"));
        a.saveBookmarks();
        b.getBookmarks().computeIfAbsent("/x/a.txt", k -> new ArrayList<>()).add(new Bookmark(7, "from B", "line"));
        b.saveBookmarks();
        String bookmarks = read("bookmarks.json");
        assertTrue(bookmarks.contains("from A") && bookmarks.contains("from B"), bookmarks);

        a.getBreakpoints().computeIfAbsent("/x/A.java", k -> new ArrayList<>()).add(Breakpoint.plain(10, "a();"));
        a.saveBreakpoints();
        b.getBreakpoints().computeIfAbsent("/x/B.java", k -> new ArrayList<>()).add(Breakpoint.plain(20, "b();"));
        b.saveBreakpoints();
        String breakpoints = read("breakpoints.json");
        assertTrue(breakpoints.contains("/x/A.java") && breakpoints.contains("/x/B.java"), breakpoints);
    }

    @Test
    void aNoteDeletedInOneProcessIsNotResurrectedByTheOthersSave() throws Exception {
        PersonalNote kept = PersonalNote.create(null, NoteScope.LINE, null, "KEPT", List.of());
        PersonalNote doomed = PersonalNote.create(null, NoteScope.LINE, null, "DOOMED", List.of());
        a.getNotes().computeIfAbsent("/x/a.txt", k -> new ArrayList<>()).addAll(List.of(kept, doomed));
        a.saveNotes();
        sharedB.load(); // the second process starts now, and so knows both notes

        a.getNotes().get("/x/a.txt").remove(doomed);
        a.saveNotes();
        sharedB.notesBucket("")
                .computeIfAbsent("/x/b.txt", k -> new ArrayList<>())
                .add(PersonalNote.create(null, NoteScope.LINE, null, "FROM-B", List.of()));
        sharedB.saveNotes();

        String notes = read("notes.json");
        assertTrue(notes.contains("KEPT") && notes.contains("FROM-B"), notes);
        assertFalse(notes.contains("DOOMED"), "the other process only knew it; it did not re-add it: " + notes);
    }

    @Test
    void aProjectCreatedInOneProcessSurvivesTheOthersWindowFocus() throws Exception {
        Path root = Files.createDirectories(dir.resolve("projA"));
        sharedA.projects().createOrGet("projA", root);
        assertTrue(sharedA.projects().save());

        sharedB.projects().setActive("");
        sharedB.projects().markOpen("untitled:1234abcd");
        assertTrue(sharedB.projects().save());

        String index = read("projects.json");
        assertTrue(index.contains("projA"), index);
        assertTrue(index.contains("untitled:1234abcd"), index);
    }

    @Test
    void macrosAbbreviationsAndSitesFromBothProcessesSurvive() throws Exception {
        sharedA.getMacroStore().put(new Macro("macro-a", List.of()));
        sharedA.saveMacros();
        sharedB.getMacroStore().put(new Macro("macro-b", List.of()));
        sharedB.saveMacros();
        String macros = read("macros.json");
        assertTrue(macros.contains("macro-a") && macros.contains("macro-b"), macros);

        Abbreviation one = new Abbreviation();
        one.setAbbreviation("btw");
        one.setExpansion("by the way");
        sharedA.setAbbreviations(new ArrayList<>(List.of(one)));
        sharedA.saveAbbreviations();
        Abbreviation two = new Abbreviation();
        two.setAbbreviation("afaik");
        two.setExpansion("as far as I know");
        sharedB.setAbbreviations(new ArrayList<>(List.of(two)));
        sharedB.saveAbbreviations();
        String abbreviations = read("abbreviations.json");
        assertTrue(abbreviations.contains("btw") && abbreviations.contains("afaik"), abbreviations);
    }

    @Test
    void aWordRemovedInOneProcessDoesNotDropTheWordsTheOtherAdded() throws Exception {
        a.addUserWord("alphaword");
        b.addUserWord("betaword");
        b.addUserWord("gammaword");
        b.removeUserWord("betaword"); // used to rewrite the file from this process's set alone

        List<String> words = Files.readAllLines(dir.resolve("dictionary.txt"));
        assertEquals(List.of("alphaword", "gammaword"), words);
        assertTrue(b.getUserDictionary().contains("alphaword"), "and it is known here now");
    }

    @Test
    void recentFilesAddedInBothProcessesAreBothKept() throws Exception {
        sharedB.recentFiles(); // both have loaded the list before either adds to it
        sharedA.recentFiles().add(dir.resolve("a1.txt"));
        assertTrue(sharedA.flushWrites());
        sharedB.recentFiles().add(dir.resolve("b1.txt"));
        assertTrue(sharedB.flushWrites());

        String recent = read("recent-files.json");
        assertTrue(recent.contains("a1.txt") && recent.contains("b1.txt"), recent);
    }

    @Test
    void localHistoryCapturedInOneProcessStaysInTheIndexAndKeepsItsBodies() throws Exception {
        addRevision(sharedA, "/x/a.txt", "content saved in primary\n");
        sharedA.saveHistory();
        assertTrue(sharedA.flushWrites());
        sharedB.load(); // the second process starts with the first one's revision in its index

        String v1 = addRevision(sharedB, "/x/b.txt", "content saved in SECONDARY v1\n");
        String v2 = addRevision(sharedB, "/x/b.txt", "content saved in SECONDARY v2\n");
        sharedB.saveHistory();
        assertTrue(sharedB.flushWrites());
        assertTrue(read("history/index.json").contains("/x/b.txt"));
        sharedB.shutdown(); // the second editor is closed: the first may collect again

        addRevision(sharedA, "/x/a.txt", "another save in primary\n");
        new HistoryBlobStore(dir.resolve("history/blobs")).put("a body no index references\n");
        assertEquals(5, blobCount());
        sharedA.historyService().requestGc();
        sharedA.saveHistory();
        assertTrue(sharedA.flushWrites());
        // The collection runs on the history worker. A body nothing references is the witness that it ran.
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (blobCount() != 4 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        String index = read("history/index.json");
        assertTrue(index.contains("/x/b.txt"), "the other process's revisions are still listed: " + index);
        assertTrue(index.contains(v1) && index.contains(v2), index);
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("history/blobs"));
        assertEquals("content saved in SECONDARY v1\n", blobs.get(v1), "and their bodies were not collected");
        assertEquals("content saved in SECONDARY v2\n", blobs.get(v2));
        assertEquals(4, blobCount());
    }

    private String addRevision(SharedConfig config, String path, String content) {
        String sha = new HistoryBlobStore(dir.resolve("history/blobs")).put(content);
        HistoryRevision revision =
                new HistoryRevision(path, System.nanoTime(), content.length(), sha, HistoryRevision.REASON_SAVE);
        config.historyBucket("").merge(path, List.of(revision), (present, added) -> {
            List<HistoryRevision> out = new ArrayList<>(added); // newest first; a list is replaced, not edited
            out.addAll(present);
            return out;
        });
        return sha;
    }

    private long blobCount() throws Exception {
        try (Stream<Path> files = Files.walk(dir.resolve("history/blobs"))) {
            return files.filter(Files::isRegularFile).count();
        }
    }
}
