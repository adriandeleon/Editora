package com.editora.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.editora.vfs.RemoteConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One window's view of the shared configuration: which project's notes, bookmarks, breakpoints and history
 * it sees — decided by where its session file lives — and what a project's removal takes with it.
 */
class ConfigManagerWindowTest {

    @TempDir
    Path dir;

    private SharedConfig shared;

    @BeforeEach
    void setUp() {
        shared = new SharedConfig(dir, false);
        shared.load();
    }

    @AfterEach
    void tearDown() {
        shared.shutdown();
    }

    private ConfigManager window(Path stateFile) {
        return new ConfigManager(shared, stateFile);
    }

    @Test
    void onlyASessionFileUnderProjectsHasAProjectKey() {
        assertEquals("", new ConfigManager(shared).currentProjectKey(), "the no-project window");
        assertEquals(
                "",
                window(dir.resolve("windows").resolve("1a2b3c4d.json")).currentProjectKey(),
                "an untitled New Window shares the no-project bucket");
        assertEquals(
                "alpha-1234",
                window(dir.resolve(ProjectManager.PROJECTS_DIR).resolve("alpha-1234.json"))
                        .currentProjectKey());
        assertEquals(
                "no-extension",
                window(dir.resolve(ProjectManager.PROJECTS_DIR).resolve("no-extension"))
                        .currentProjectKey());
        assertEquals("", window(Path.of("workspace-state.json")).currentProjectKey(), "a bare file name");
        assertEquals("", window(dir.getRoot()).currentProjectKey(), "a path with no file name at all");
    }

    @Test
    void eachWindowSeesItsOwnProjectsBucketsAndDeletingAProjectsBucketsLeavesTheOthers() {
        ConfigManager global = new ConfigManager(shared);
        ConfigManager alpha = window(dir.resolve(ProjectManager.PROJECTS_DIR).resolve("alpha.json"));
        ConfigManager beta = window(dir.resolve(ProjectManager.PROJECTS_DIR).resolve("beta.json"));

        for (ConfigManager w : List.of(global, alpha, beta)) {
            String key = w.currentProjectKey();
            w.getBookmarks().put("/f-" + key, new ArrayList<>(List.of(new Bookmark(1, "", "x"))));
            w.getBreakpoints().put("/f-" + key, new ArrayList<>(List.of(new Breakpoint(3, "", "", true, "x"))));
            w.getHistory()
                    .put(
                            "/f-" + key,
                            new ArrayList<>(List.of(new HistoryRevision(
                                    "/f-" + key, 1L, 1L, "aa", HistoryRevision.REASON_SAVE, "", "", false, ""))));
            w.getNotes().put("/f-" + key, new ArrayList<>());
        }
        assertEquals(List.of("/f-alpha"), List.copyOf(alpha.getBookmarks().keySet()));
        assertEquals(List.of("/f-"), List.copyOf(global.getBookmarks().keySet()));
        assertEquals(3, global.getAllBookmarks().size(), "every project's bucket is visible to the panels");
        assertEquals(3, global.getAllNotes().size());
        assertEquals(3, global.getHistoryByProject().size());

        global.deleteBookmarksForProject("alpha");
        global.deleteNotesForProject("alpha");
        global.deleteBreakpointsForProject("alpha");
        global.deleteHistoryForProject("alpha");

        assertTrue(alpha.getBookmarks().isEmpty());
        assertTrue(alpha.getNotes().isEmpty());
        assertTrue(alpha.getBreakpoints().isEmpty());
        assertTrue(alpha.getHistory().isEmpty());
        assertEquals(List.of("/f-beta"), List.copyOf(beta.getBookmarks().keySet()), "another project is untouched");
        assertEquals(List.of("/f-beta"), List.copyOf(beta.getBreakpoints().keySet()));
        assertEquals(List.of("/f-beta"), List.copyOf(beta.getHistory().keySet()));
        assertEquals(List.of("/f-"), List.copyOf(global.getBreakpoints().keySet()));

        // The no-project bucket goes by null as well as by "".
        global.deleteBreakpointsForProject(null);
        global.deleteNotesForProject(null);
        global.deleteHistoryForProject(null);
        assertTrue(global.getBreakpoints().isEmpty()
                && global.getNotes().isEmpty()
                && global.getHistory().isEmpty());
        global.deleteBreakpointsForProject("a-project-with-no-bucket"); // nothing there: nothing to do
        assertEquals(List.of("/f-beta"), List.copyOf(beta.getBreakpoints().keySet()));
    }

    @Test
    void aRemoteSiteIsRemovedByItsConnectionKey() {
        ConfigManager config = new ConfigManager(shared);
        RemoteConnection first = new RemoteConnection(
                "one.example.org", 22, "ada", RemoteConnection.AuthMethod.DEFAULT_KEYS, "", "", "");
        RemoteConnection second =
                new RemoteConnection("two.example.org", 0, "", RemoteConnection.AuthMethod.PASSWORD, "", "", "");
        config.putConnection(first);
        config.putConnection(second);
        assertEquals(2, config.getConnections().size());

        config.removeConnection(first.id());
        assertEquals(
                List.of(second.id()),
                config.getConnections().stream().map(RemoteConnection::id).toList());
        config.removeConnection("nobody@nowhere:22");
        assertEquals(1, config.getConnections().size());
    }

    @Test
    void everyStoreFileLivesInTheConfigFolderUnderItsOwnName() {
        ConfigManager config = new ConfigManager(shared);
        List<Path> files = List.of(
                config.getSettingsFile(),
                config.getBookmarksFile(),
                config.getNotesFile(),
                config.getBreakpointsFile(),
                config.getConnectionsFile(),
                config.getUserDictionaryFile(),
                config.getDictionaryFile());
        files.forEach(f -> assertEquals(dir, f.getParent(), f.toString()));
        assertEquals(files.size() - 1, files.stream().distinct().count(), "only the two dictionary names coincide");
        assertEquals(config.getUserDictionaryFile(), config.getDictionaryFile());
        assertTrue(config.getHistoryBlobsDir().startsWith(dir));
        assertFalse(config.isDev());
        assertEquals(dir, config.getConfigDir());
    }

    @Test
    void theCommandLineFolderWinsOverTheEnvironmentAndTheHomeFolder() {
        assertEquals(
                Path.of("custom", "cfg"), ConfigManager.configDirFor("  " + Path.of("custom", "cfg") + " ", false));
        assertEquals(
                Path.of("custom", "cfg"),
                ConfigManager.configDirFor(Path.of("custom", "cfg").toString(), true));
        assertEquals(ConfigManager.defaultConfigDir(false), ConfigManager.configDirFor(null, false));
        assertEquals(ConfigManager.defaultConfigDir(true), ConfigManager.configDirFor("   ", true));
        assertEquals(ConfigManager.defaultConfigDir(false), ConfigManager.defaultConfigDir());

        assertEquals(Path.of(".", ".editora"), ConfigManager.resolveConfigDir(null, null, false));
        assertEquals(Path.of(".", ".editora-dev"), ConfigManager.resolveConfigDir("  ", " ", true));
        assertEquals(Path.of("home", ".editora"), ConfigManager.resolveConfigDir(null, "home"));
    }
}
