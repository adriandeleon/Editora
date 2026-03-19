package org.adriandeleon.editora.session;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SessionManagerTest {

    private final Preferences preferences = Preferences.userNodeForPackage(SessionManager.class);

    @BeforeEach
    void clearBefore() throws BackingStoreException {
        preferences.clear();
    }

    @AfterEach
    void clearAfter() throws BackingStoreException {
        preferences.clear();
    }

    @Test
    void savesAndLoadsWorkspaceSessionShellState() {
        Path workspaceRoot = Path.of("/tmp/editora-workspace").toAbsolutePath().normalize();
        Path firstOpenFile = workspaceRoot.resolve("src/Main.java").normalize();
        Path secondOpenFile = workspaceRoot.resolve("README.md").normalize();

        WorkspaceSession session = new WorkspaceSession(
                List.of(firstOpenFile, secondOpenFile),
                Optional.of(secondOpenFile),
                workspaceRoot,
                false,
                false,
                true,
                0.31d,
                420d,
                "needle",
                "replacement",
                true,
                true,
                true,
                "settings",
                "src/uti",
                List.of("src/uti", "README.md"),
                1660d,
                980d,
                120d,
                160d,
                true
        );

        SessionManager.saveWorkspaceSession(session);

        WorkspaceSession loaded = SessionManager.loadWorkspaceSession(Path.of("/fallback").toAbsolutePath().normalize());

        assertEquals(session, loaded);
    }

    @Test
    void savesRecentFilesAsDistinctNormalizedPaths() {
        Path first = Path.of("/tmp/editora-workspace/README.md").toAbsolutePath().normalize();
        Path second = Path.of("/tmp/editora-workspace/src/Main.java").toAbsolutePath().normalize();

        SessionManager.saveRecentFiles(List.of(first, second, first));

        assertEquals(List.of(first, second), SessionManager.loadRecentFiles());
    }

    @Test
    void loadWorkspaceSessionDefaultsSearchAndExplorerToHidden() {
        Path fallbackWorkspaceRoot = Path.of("/fallback").toAbsolutePath().normalize();

        WorkspaceSession loaded = SessionManager.loadWorkspaceSession(fallbackWorkspaceRoot);

        assertEquals(fallbackWorkspaceRoot, loaded.workspaceRoot());
        assertEquals(false, loaded.searchBarVisible());
        assertEquals(false, loaded.projectExplorerVisible());
        assertEquals(true, loaded.statusBarVisible());
    }
}

