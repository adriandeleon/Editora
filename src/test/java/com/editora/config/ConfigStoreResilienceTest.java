package com.editora.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.config.migration.ConfigLoadProblem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The projects index, the per-window session files and the synchronously written stores get the same care as
 * {@code settings.json}: what could not be read is reported, a file whose content could be neither read nor
 * backed up is not overwritten, and a write that fails is reported instead of thrown into an event handler.
 */
class ConfigStoreResilienceTest {

    /** The number of backup names {@code ConfigMigrations} tries before giving up (its {@code MAX_BACKUPS}). */
    private static final int BACKUP_NAMES = 20;

    private static void occupyEveryBackupName(Path file, String suffix) throws IOException {
        Files.writeString(file.resolveSibling(file.getFileName() + suffix), "older backup");
        for (int i = 2; i <= BACKUP_NAMES; i++) {
            Files.writeString(file.resolveSibling(file.getFileName() + suffix + "." + i), "older backup " + i);
        }
    }

    private static ConfigLoadProblem problemFor(List<ConfigLoadProblem> problems, Path file) {
        return problems.stream().filter(p -> p.file().equals(file)).findFirst().orElse(null);
    }

    /** A config dir that can never be created or written: its parent is a regular file. */
    private static Path unwritableConfigDir(Path tmp) throws IOException {
        Path notADirectory = Files.writeString(tmp.resolve("not-a-directory"), "x");
        return notADirectory.resolve("config");
    }

    // --- projects.json ------------------------------------------------------------------------------

    @Test
    void aTornProjectsIndexIsReported(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("projects.json");
        String torn = "{\"schemaVersion\":2,\"projects\":[{\"id\":\"app-1\",\"name\":\"app\",";
        Files.writeString(file, torn);

        ConfigManager config = new ConfigManager(dir);
        config.load();

        assertTrue(config.projects().list().isEmpty());
        ConfigLoadProblem problem = problemFor(config.shared().takeLoadProblems(), file);
        assertNotNull(problem, "an empty project list with no word of why is what used to happen");
        assertEquals(ConfigLoadProblem.Kind.UNREADABLE, problem.kind());
        assertEquals(torn, Files.readString(problem.backup()));
    }

    @Test
    void aNewerProjectsIndexThatCannotBeBackedUpIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("projects.json");
        String newer = "{\"schemaVersion\": 99, \"projects\": [], \"fromTheFuture\": true}";
        Files.writeString(file, newer);
        occupyEveryBackupName(file, ".v99.bak");

        ConfigManager config = new ConfigManager(dir);
        config.load();

        ConfigLoadProblem problem = problemFor(config.shared().takeLoadProblems(), file);
        assertNotNull(problem);
        assertEquals(ConfigLoadProblem.Kind.NEWER_VERSION, problem.kind());
        assertNull(problem.backup());
        assertTrue(config.shared().isWriteProtected(file));

        config.projects().markOpen("");
        assertFalse(config.projects().save(), "nothing was written");
        assertEquals(newer, Files.readString(file), "the only copy of the newer index is left untouched");
    }

    @Test
    void aProjectsIndexProblemSurvivesTheLoadThatFollowsConstruction(@TempDir Path dir) throws IOException {
        // The index is read when the shared config is built; load() starts by clearing what was collected.
        Files.writeString(dir.resolve("projects.json"), "{ not json");
        SharedConfig shared = new SharedConfig(dir, false);
        shared.load();
        shared.load();

        assertEquals(1, shared.takeLoadProblems().size(), "reported once, not dropped and not doubled");
    }

    // --- session files ------------------------------------------------------------------------------

    @Test
    void aNewerSessionFileThatCannotBeBackedUpIsNeverOverwritten(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("workspace-state.json");
        String newer = "{\"schemaVersion\": 9999, \"fromTheFuture\": true}";
        Files.writeString(file, newer);
        occupyEveryBackupName(file, ".v9999.bak");

        ConfigManager config = new ConfigManager(dir);
        config.load();

        ConfigLoadProblem problem = problemFor(config.shared().takeLoadProblems(), file);
        assertNotNull(problem, "the session opened empty without a word");
        assertTrue(problem.mustNotOverwrite());
        assertTrue(config.shared().isWriteProtected(file));

        config.save();
        config.saveAsync();
        config.shared().flushWrites();
        assertEquals(newer, Files.readString(file), "the only copy of the newer session is left untouched");
    }

    @Test
    void aNewerProjectSessionIsReportedWhenAWindowSwitchesToIt(@TempDir Path dir) throws IOException {
        Path file = Files.createDirectories(dir.resolve("projects")).resolve("app-1.json");
        Files.writeString(file, "{\"schemaVersion\": 9999}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        assertTrue(config.shared().takeLoadProblems().isEmpty());

        config.setWorkspaceStateFile(file);

        List<ConfigLoadProblem> problems = config.shared().takeLoadProblems();
        assertEquals(1, problems.size(), "moved aside as app-1.json.v9999.bak with no message");
        assertEquals(ConfigLoadProblem.Kind.NEWER_VERSION, problems.get(0).kind());
        assertEquals(
                dir.resolve("projects").resolve("app-1.json.v9999.bak"),
                problems.get(0).backup());
    }

    @Test
    void aSessionFileReadAtBootstrapAndAgainByItsWindowIsReportedOnce(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("workspace-state.json");
        Files.writeString(file, "{ not json");
        ConfigManager bootstrap = new ConfigManager(dir);
        bootstrap.load();
        ConfigManager window = new ConfigManager(bootstrap.shared());
        window.setWorkspaceStateFile(window.getWorkspaceStateFile()); // what WindowManager does per window

        assertEquals(1, bootstrap.shared().takeLoadProblems().size());
    }

    // --- writes that fail ---------------------------------------------------------------------------

    @Test
    void anUnwritableConfigDirDoesNotStopTheConfigFromLoading(@TempDir Path tmp) throws IOException {
        ConfigManager config = new ConfigManager(unwritableConfigDir(tmp));

        // The first load creates bookmarks.json; when that write failed, the app did not start.
        Settings settings = assertDoesNotThrow(config::load);

        assertEquals(14, settings.getFontSize(), "running on defaults");
    }

    @Test
    void aStoreWriteThatFailsIsReportedInsteadOfThrown(@TempDir Path tmp) throws IOException {
        Path dir = unwritableConfigDir(tmp);
        ConfigManager config = new ConfigManager(dir);
        config.load();
        List<Path> failed = new ArrayList<>();
        config.shared().setOnWriteError((file, error) -> failed.add(file));

        // Each of these runs inside an FX event handler: toggle a bookmark or breakpoint, edit a note,
        // record a macro, trust a folder, enable a plugin, edit an abbreviation or a connection.
        assertDoesNotThrow(config::saveBookmarks);
        assertDoesNotThrow(config::saveBreakpoints);
        assertDoesNotThrow(config::saveNotes);
        assertDoesNotThrow(config::saveMacros);
        assertDoesNotThrow(config::saveTrust);
        assertDoesNotThrow(config::savePlugins);
        assertDoesNotThrow(config::saveAbbreviations);
        assertDoesNotThrow(config::saveConnections);
        assertFalse(assertDoesNotThrow(() -> config.projects().save()), "and opening or closing a window");

        assertEquals(
                List.of(
                        dir.resolve("bookmarks.json"),
                        dir.resolve("breakpoints.json"),
                        dir.resolve("notes.json"),
                        dir.resolve("macros.json"),
                        dir.resolve("trusted-folders.json"),
                        dir.resolve("plugins.json"),
                        dir.resolve("abbreviations.json"),
                        dir.resolve("connections.json"),
                        dir.resolve("projects.json")),
                failed,
                "each lost write reaches the handler that shows 'Could not save …'");
    }

    @Test
    void aStandaloneProjectManagerDoesNotThrowEither(@TempDir Path tmp) throws IOException {
        ProjectManager projects = new ProjectManager(unwritableConfigDir(tmp));
        projects.markOpen("");
        assertFalse(assertDoesNotThrow(projects::save));
    }

    // --- a store that stays unreadable across launches ----------------------------------------------

    @Test
    void theSameDamageIsBackedUpOnceHoweverOftenItIsLoaded(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("connections.json");
        String torn = "{\"schemaVersion\":1,\"connections\":[{\"id\":\"prod\",";
        Files.writeString(file, torn);

        for (int launch = 1; launch <= BACKUP_NAMES + 2; launch++) {
            ConfigManager config = new ConfigManager(dir);
            config.load();
            ConfigLoadProblem problem = problemFor(config.shared().takeLoadProblems(), file);
            assertNotNull(problem, "still reported on launch " + launch);
            assertEquals(dir.resolve("connections.json.corrupt.bak"), problem.backup(), "launch " + launch);
            assertFalse(config.shared().isWriteProtected(file), "launch " + launch);
        }
        try (var entries = Files.list(dir)) {
            assertEquals(
                    1,
                    entries.filter(p -> p.getFileName().toString().contains(".corrupt.bak"))
                            .count(),
                    "one copy of the same damage");
        }

        // …and so the store can still be saved: this used to do nothing from the 21st launch on.
        ConfigManager config = new ConfigManager(dir);
        config.load();
        config.setConnections(List.of());
        assertTrue(Files.readString(file).contains("\"connections\" : [ ]"), Files.readString(file));
    }

    @Test
    void differentDamageStillGetsItsOwnBackup(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("macros.json");
        Files.writeString(file, "{ first");
        new ConfigManager(dir).load();
        Files.writeString(file, "{ second");
        new ConfigManager(dir).load();

        assertEquals("{ first", Files.readString(dir.resolve("macros.json.corrupt.bak")));
        assertEquals("{ second", Files.readString(dir.resolve("macros.json.corrupt.bak.2")));
    }
}
