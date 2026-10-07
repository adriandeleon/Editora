package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import com.editora.config.migration.ConfigLoadProblem;
import com.editora.config.migration.ConfigLoadProblem.Kind;
import com.editora.history.HistoryBlobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What happens to Editora's own stores when a file is not what the last session wrote: empty or not a store
 * at all (data-loss review C5, C8), set aside by an older build (C6), or unwritable for a while (C7).
 */
class StoreIntegrityTest {

    private static ConfigLoadProblem problemFor(List<ConfigLoadProblem> problems, Path file) {
        return problems.stream().filter(p -> p.file().equals(file)).findFirst().orElse(null);
    }

    private static List<String> backupsOf(Path file) throws IOException {
        try (Stream<Path> siblings = Files.list(file.getParent())) {
            return siblings.map(p -> p.getFileName().toString())
                    .filter(n -> n.startsWith(file.getFileName().toString()) && n.contains("bak"))
                    .sorted()
                    .toList();
        }
    }

    // --- C5 / C8: empty, blank and not-a-store files ----------------------------------------------------

    @ParameterizedTest(name = "{0} holding [{1}]")
    @CsvSource(
            value = {
                "settings.json|",
                "settings.json|'   \n'",
                "settings.json|null",
                "notes.json|",
                "notes.json|'\n'",
                "notes.json|null",
                "notes.json|\"just a string\"",
                "notes.json|42",
                "bookmarks.json|null",
                "macros.json|[]",
                "projects.json|",
                "projects.json|null",
                "workspace-state.json|",
                "workspace-state.json|true",
                "recent-files.json|null",
            },
            delimiter = '|')
    void aStoreFileThatIsNotAStoreIsReportedAndPreservedNotSilentlyReplaced(
            String name, String content, @TempDir Path dir) throws Exception {
        Path file = dir.resolve(name);
        String damaged = content == null ? "" : content;
        Files.writeString(file, damaged);

        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.shared().recentFiles();
        try {
            ConfigLoadProblem problem = problemFor(config.shared().takeLoadProblems(), file);
            assertNotNull(problem, "nothing was reported: the user would never know " + name + " was reset");
            assertEquals(Kind.UNREADABLE, problem.kind());
            assertNotNull(problem.backup(), "the bytes were kept");
            assertEquals(damaged, Files.readString(problem.backup()));
            assertFalse(config.shared().isWriteProtected(file), "a copy exists, so the store works again");
        } finally {
            config.shared().shutdown();
        }
    }

    @Test
    void aLegacyBareArrayRecentFilesListIsStillAStore(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("recent-files.json"), "[\"/a/b.txt\"]");
        SharedConfig shared = new SharedConfig(dir, false);
        try {
            shared.load();
            assertEquals(1, shared.recentFiles().getList().size());
            assertTrue(shared.takeLoadProblems().isEmpty());
        } finally {
            shared.shutdown();
        }
    }

    @ParameterizedTest(name = "index holding [{0}]")
    @ValueSource(strings = {"", "\n", "null", "{}", "\"x\""})
    void aHistoryIndexThatListsNothingBesideStoredBodiesNeverLetsThemBeCollected(String damaged, @TempDir Path dir)
            throws Exception {
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("history/blobs"));
        SharedConfig first = new SharedConfig(dir, false);
        first.claimInstance();
        first.load();
        List<String> hashes = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String content = "old revision " + i + "\n";
            String sha = blobs.put(content);
            hashes.add(sha);
            first.historyBucket("")
                    .computeIfAbsent("/x/f.txt", k -> new ArrayList<>())
                    .add(new HistoryRevision("/x/f.txt", i, content.length(), sha, "SAVE"));
        }
        first.saveHistory();
        assertTrue(first.flushWrites());
        first.shutdown();
        Files.writeString(dir.resolve("history/index.json"), damaged);

        SharedConfig second = new SharedConfig(dir, false);
        second.claimInstance();
        second.load();
        try {
            assertFalse(
                    second.mayCollectHistoryBlobs(),
                    "collecting against this index deletes every stored revision body");
            // …and it stays that way after the session writes an index of its own.
            String content = "new save\n";
            second.historyBucket("")
                    .computeIfAbsent("/x/g.txt", k -> new ArrayList<>())
                    .add(new HistoryRevision("/x/g.txt", 9, content.length(), blobs.put(content), "SAVE"));
            second.historyService().requestGc();
            second.saveHistory();
            assertTrue(second.flushWrites());
            assertFalse(second.mayCollectHistoryBlobs());
            for (String sha : hashes) {
                assertNotNull(blobs.get(sha), "body " + sha + " is gone");
            }
        } finally {
            second.shutdown();
        }
    }

    // --- C6: files an older build set aside -------------------------------------------------------------

    /** What an older build does to a store it cannot read: moves it aside and starts from defaults. */
    private static void downgradeOnce(Path dir, String name, String newerContent, String leftInPlace)
            throws IOException {
        int version = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(newerContent)
                .get("schemaVersion")
                .asInt();
        Files.createDirectories(dir.resolve(name).getParent());
        Files.writeString(dir.resolve(name + ".v" + version + ".bak"), newerContent);
        if (leftInPlace != null) {
            Files.writeString(dir.resolve(name), leftInPlace);
        }
    }

    @Test
    void settingsAnOlderBuildSetAsideComeBackWhenTheFileInTheirPlaceIsStillDefaults(@TempDir Path dir)
            throws Exception {
        String mine = "{\"schemaVersion\":" + Settings.SCHEMA_VERSION
                + ",\"fontSize\":19,\"aiApiKey\":\"sk-ant-USER\",\"keybindings\":{\"file.save\":\"Ctrl+Alt+S\"}}";
        // The older build wrote its defaults, and its own update check stamped the bookkeeping keys.
        downgradeOnce(
                dir,
                "settings.json",
                mine,
                "{\"schemaVersion\":" + (Settings.SCHEMA_VERSION - 1)
                        + ",\"fontSize\":" + new Settings().getFontSize()
                        + ",\"lastUpdateCheckEpoch\":1790000000,\"dismissedUpdateVersion\":\"0.1\"}");

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        try {
            assertEquals(19, settings.getFontSize());
            assertEquals("sk-ant-USER", settings.getAiApiKey());
            assertEquals("Ctrl+Alt+S", settings.getKeybindings().get("file.save"));
            ConfigLoadProblem told = problemFor(config.shared().takeLoadProblems(), dir.resolve("settings.json"));
            assertNotNull(told, "the user is told their settings came back");
            assertEquals(Kind.NEWER_COPY_RESTORED, told.kind());
            assertEquals(List.of(), backupsOf(dir.resolve("settings.json")), "the copy was moved back, not duplicated");
        } finally {
            config.shared().shutdown();
        }
    }

    @Test
    void storesAnOlderBuildSetAsideAndNeverRewroteComeBackToo(@TempDir Path dir) throws Exception {
        // macros.json is only written when a macro changes, so nothing was left in its place at all.
        downgradeOnce(
                dir,
                "notes.json",
                "{\"schemaVersion\":" + NoteStore.SCHEMA_VERSION
                        + ",\"byProject\":{\"\":{\"/x/a.txt\":[{\"id\":\"11111111-1111-1111-1111-111111111111\","
                        + "\"body\":\"MY NOTE\"}]}}}",
                "{\"schemaVersion\":1,\"byProject\":{}}");
        downgradeOnce(
                dir,
                "macros.json",
                "{\"schemaVersion\":" + MacroStore.SCHEMA_VERSION + ",\"macros\":[{\"name\":\"mine\",\"steps\":[]}]}",
                null);
        downgradeOnce(
                dir,
                "projects.json",
                "{\"schemaVersion\":2,\"projects\":[{\"id\":\"p-1\",\"name\":\"p\",\"root\":\"/p\"}],"
                        + "\"activeProjectId\":\"p-1\",\"openProjectIds\":[\"p-1\"]}",
                // An older build from before this fix rewrote the index with its own (empty) window set.
                "{\"schemaVersion\":1,\"projects\":[],\"activeProjectId\":\"\",\"openProjectIds\":[\"\"]}");

        ConfigManager config = new ConfigManager(dir);
        config.load();
        try {
            assertEquals("MY NOTE", config.getNotes().get("/x/a.txt").get(0).body());
            assertNotNull(config.getMacroStore().find("mine"));
            assertEquals(1, config.projects().list().size());
            assertTrue(config.projects().isOpen("p-1"));
            assertTrue(config.projects().loadedIntact());
        } finally {
            config.shared().shutdown();
        }
    }

    @Test
    void aSetAsideCopyIsNotRestoredOverAFileTheUserHasChangedSinceButTheyAreToldOnceWhereItIs(@TempDir Path dir)
            throws Exception {
        String mine =
                "{\"schemaVersion\":" + Settings.SCHEMA_VERSION + ",\"fontSize\":19,\"aiApiKey\":\"sk-ant-USER\"}";
        downgradeOnce(
                dir,
                "settings.json",
                mine,
                "{\"schemaVersion\":" + (Settings.SCHEMA_VERSION - 1) + ",\"fontSize\":23}"); // set in the older build
        Path copy = dir.resolve("settings.json.v" + Settings.SCHEMA_VERSION + ".bak");

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        try {
            assertEquals(23, settings.getFontSize(), "two sets of data: the one in use is not replaced");
            ConfigLoadProblem told = problemFor(config.shared().takeLoadProblems(), dir.resolve("settings.json"));
            assertNotNull(told);
            assertEquals(Kind.NEWER_COPY_KEPT, told.kind());
            assertEquals(copy, told.backup());
            assertEquals(mine, Files.readString(copy), "and the copy is untouched");
        } finally {
            config.shared().shutdown();
        }

        ConfigManager next = new ConfigManager(dir);
        next.load();
        try {
            assertNull(
                    problemFor(next.shared().takeLoadProblems(), dir.resolve("settings.json")),
                    "said once, not on every launch for as long as the copy exists");
        } finally {
            next.shared().shutdown();
        }
    }

    @Test
    void aCopyNewerThanThisBuildIsLeftWhereItIs(@TempDir Path dir) throws Exception {
        String newer = "{\"schemaVersion\":" + (Settings.SCHEMA_VERSION + 5) + ",\"fontSize\":19}";
        downgradeOnce(dir, "settings.json", newer, null);

        ConfigManager config = new ConfigManager(dir);
        config.load();
        try {
            assertEquals(new Settings().getFontSize(), config.getSettings().getFontSize());
            assertEquals(
                    newer, Files.readString(dir.resolve("settings.json.v" + (Settings.SCHEMA_VERSION + 5) + ".bak")));
        } finally {
            config.shared().shutdown();
        }
    }

    @Test
    void aRestoredHistoryIndexIsTrustedAgain(@TempDir Path dir) throws Exception {
        HistoryBlobStore blobs = new HistoryBlobStore(dir.resolve("history/blobs"));
        String sha = blobs.put("kept\n");
        downgradeOnce(
                dir,
                "history/index.json",
                "{\"schemaVersion\":" + HistoryStore.SCHEMA_VERSION
                        + ",\"byProject\":{\"\":{\"/x/f.txt\":[{\"path\":\"/x/f.txt\","
                        + "\"timestamp\":1,\"sizeBytes\":5,\"sha256\":\"" + sha
                        + "\",\"reason\":\"SAVE\",\"label\":\"\"}]}}}",
                "{\"schemaVersion\":1,\"byProject\":{}}");

        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        try {
            assertEquals(1, shared.historyBucket("").get("/x/f.txt").size());
            assertTrue(shared.mayCollectHistoryBlobs(), "no backup is left beside it, and it lists its bodies");
        } finally {
            shared.shutdown();
        }
    }

    // --- C10: a store whose record gained a field has a version an older build refuses ------------------

    @Test
    void aBookmarksFileFromBeforeMnemonicsLoadsAndIsStampedWithTheVersionThatHasThem(@TempDir Path dir)
            throws Exception {
        Files.writeString(
                dir.resolve("bookmarks.json"),
                "{\"schemaVersion\":1,\"byProject\":{\"\":{\"/x/a.txt\":[{\"line\":3,\"note\":\"n\",\"lineText\":\"t\"}]}}}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        try {
            assertEquals(2, BookmarkStore.SCHEMA_VERSION);
            Bookmark loaded = config.getBookmarks().get("/x/a.txt").get(0);
            assertEquals("", loaded.mnemonic());
            config.getBookmarks().get("/x/a.txt").set(0, loaded.withMnemonic("a"));
            config.saveBookmarks();
            String written = Files.readString(dir.resolve("bookmarks.json"));
            assertTrue(written.contains("\"schemaVersion\" : 2"), written);
            assertTrue(written.contains("\"mnemonic\" : \"a\""), written);
        } finally {
            config.shared().shutdown();
        }
    }

    // --- C7: a store write that failed is retried -------------------------------------------------------

    /** Puts something in {@code file}'s place that no write can replace; returns how to remove it again. */
    private static Runnable block(Path file) throws IOException {
        Files.deleteIfExists(file);
        Files.createDirectories(file);
        Path inside = Files.writeString(file.resolve("in-the-way"), "x");
        return () -> {
            try {
                Files.delete(inside);
                Files.delete(file);
            } catch (IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        };
    }

    @Test
    void aNoteWrittenWhileTheConfigFolderWasUnwritableIsSavedByTheQuitTimeSave(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.save());
        List<String> reported = new CopyOnWriteArrayList<>();
        config.shared()
                .setOnWriteError((file, e) -> reported.add(file.getFileName().toString()));
        Runnable recover = block(dir.resolve("notes.json"));

        config.getNotes()
                .computeIfAbsent("/x/a.txt", k -> new ArrayList<>())
                .add(PersonalNote.create(null, NoteScope.LINE, null, "NOTE-WRITTEN-WHILE-DISK-FAILED", List.of()));
        config.saveNotes();
        assertEquals(List.of("notes.json"), reported);
        assertTrue(config.shared().hasUnsavedStores());
        assertFalse(config.save(), "a durable save while the store still cannot be written says so");

        recover.run(); // the disk is back; the user keeps working and quits normally

        assertTrue(config.save(), "what persistSession does at quit");
        assertTrue(Files.readString(dir.resolve("notes.json")).contains("NOTE-WRITTEN-WHILE-DISK-FAILED"));
        assertFalse(config.shared().hasUnsavedStores());
        assertTrue(config.shared().shutdown());
    }

    @Test
    void aFailedStoreIsRetriedAsSoonAsAnotherStoreIsWritten(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.shared().setOnWriteError((file, e) -> {});
        Runnable recover = block(dir.resolve("bookmarks.json"));
        config.getBookmarks()
                .computeIfAbsent("/x/a.txt", k -> new ArrayList<>())
                .add(new Bookmark(3, "kept", "line"));
        config.saveBookmarks();
        recover.run();

        config.getBreakpoints()
                .computeIfAbsent("/x/A.java", k -> new ArrayList<>())
                .add(Breakpoint.plain(1, "a();"));
        config.saveBreakpoints();

        try {
            assertTrue(Files.readString(dir.resolve("bookmarks.json")).contains("kept"));
        } finally {
            config.shared().shutdown();
        }
    }

    @Test
    void aDictionaryWordThatCouldNotBeAppendedIsReportedAndSavedLater(@TempDir Path dir) throws Exception {
        ConfigManager config = new ConfigManager(dir);
        config.load();
        Files.writeString(dir.resolve("dictionary.txt"), "existing" + System.lineSeparator());
        config.shared().load();
        List<String> reported = new CopyOnWriteArrayList<>();
        config.shared()
                .setOnWriteError((file, e) -> reported.add(file.getFileName().toString()));
        Runnable recover = block(dir.resolve("dictionary.txt"));

        config.addUserWord("lostword");
        assertEquals(List.of("dictionary.txt"), reported, "this failure used to be swallowed");
        recover.run();
        Files.writeString(dir.resolve("dictionary.txt"), "existing" + System.lineSeparator());

        assertTrue(config.shared().shutdown());
        assertEquals(List.of("existing", "lostword"), Files.readAllLines(dir.resolve("dictionary.txt")));
    }

    // --- C11: the legacy TOML file is kept --------------------------------------------------------------

    @Test
    void theHandMaintainedTomlFileSurvivesItsConversionToJson(@TempDir Path dir) throws Exception {
        String toml = "# my editor settings — tuned over years\nfontSize = 20\nmyOwnKey = \"not modelled\"\n";
        Files.writeString(dir.resolve("settings.toml"), toml);

        ConfigManager config = new ConfigManager(dir);
        Settings settings = config.load();
        try {
            assertEquals(20, settings.getFontSize());
            assertTrue(Files.exists(dir.resolve("settings.json")));
            assertFalse(Files.exists(dir.resolve("settings.toml")), "JSON wins from now on");
            assertEquals(toml, Files.readString(dir.resolve("settings.toml.migrated")), "comments and all");
        } finally {
            config.shared().shutdown();
        }
    }
}
