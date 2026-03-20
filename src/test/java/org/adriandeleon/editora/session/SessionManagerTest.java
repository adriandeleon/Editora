package org.adriandeleon.editora.session;

import org.adriandeleon.editora.persistence.EditoraPersistence;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionManagerTest {

    @TempDir
    Path tempDir;

    private String previousHomeOverride;

    @BeforeEach
    void clearBefore() {
        previousHomeOverride = System.getProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, tempDir.resolve("home").toString());
    }

    @AfterEach
    void clearAfter() {
        if (previousHomeOverride == null) {
            System.clearProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY);
        } else {
            System.setProperty(EditoraPersistence.HOME_OVERRIDE_PROPERTY, previousHomeOverride);
        }
    }

    @Test
    void savesAndLoadsWorkspaceSessionShellState() throws IOException {
        Path workspaceRoot = tempDir.resolve("editora-workspace").toAbsolutePath().normalize();
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

        WorkspaceSession loaded = SessionManager.loadWorkspaceSession(tempDir.resolve("fallback").toAbsolutePath().normalize());

        assertEquals(session, loaded);
        assertEquals(SessionManager.workspaceSessionFile(), EditoraPersistence.workspaceSessionFile());
        String persistedSession = Files.readString(SessionManager.workspaceSessionFile());
        assertTrue(persistedSession.contains("\"toolDockVisible\""));
        assertTrue(persistedSession.contains("\"toolDockDividerPosition\""));
        assertTrue(persistedSession.contains("\"toolDockWidth\""));
        assertFalse(persistedSession.contains("\"projectExplorerVisible\""));
        assertFalse(persistedSession.contains("\"projectExplorerDividerPosition\""));
        assertFalse(persistedSession.contains("\"projectExplorerWidth\""));
    }

    @Test
    void savesRecentFilesAsDistinctNormalizedPaths() {
        Path first = tempDir.resolve("editora-workspace/README.md").toAbsolutePath().normalize();
        Path second = tempDir.resolve("editora-workspace/src/Main.java").toAbsolutePath().normalize();

        SessionManager.saveRecentFiles(List.of(first, second, first));

        assertEquals(List.of(first, second), SessionManager.loadRecentFiles());
        assertEquals(SessionManager.recentFilesFile(), EditoraPersistence.recentFilesFile());
    }

    @Test
    void loadWorkspaceSessionDefaultsSearchAndExplorerToHidden() {
        Path fallbackWorkspaceRoot = tempDir.resolve("fallback").toAbsolutePath().normalize();

        WorkspaceSession loaded = SessionManager.loadWorkspaceSession(fallbackWorkspaceRoot);

        assertEquals(fallbackWorkspaceRoot, loaded.workspaceRoot());
        assertEquals(false, loaded.searchBarVisible());
        assertEquals(false, loaded.toolDockVisible());
        assertEquals(true, loaded.statusBarVisible());
        assertEquals(0.22d, loaded.toolDockDividerPosition());
        assertEquals(260d, loaded.toolDockWidth());
    }

    @Test
    void loadWorkspaceSessionIgnoresLegacyProjectExplorerKeys() throws IOException {
        Path fallbackWorkspaceRoot = tempDir.resolve("fallback").toAbsolutePath().normalize();
        Path legacyWorkspaceRoot = tempDir.resolve("legacy-workspace").toAbsolutePath().normalize();
        Files.createDirectories(SessionManager.workspaceSessionFile().getParent());
        Files.writeString(SessionManager.workspaceSessionFile(), """
                {
                  "workspaceRoot": "%s",
                  "projectExplorerVisible": true,
                  "projectExplorerDividerPosition": 0.31,
                  "projectExplorerWidth": 420
                }
                """.formatted(legacyWorkspaceRoot.toString().replace("\\", "\\\\")));

        WorkspaceSession loaded = SessionManager.loadWorkspaceSession(fallbackWorkspaceRoot);

        assertEquals(legacyWorkspaceRoot, loaded.workspaceRoot());
        assertFalse(loaded.toolDockVisible());
        assertEquals(0.22d, loaded.toolDockDividerPosition());
        assertEquals(260d, loaded.toolDockWidth());
    }

    @Test
    void loadWorkspaceSessionFallsBackWhenJsonIsMalformed() throws IOException {
        Path fallbackWorkspaceRoot = tempDir.resolve("fallback").toAbsolutePath().normalize();
        Files.createDirectories(SessionManager.workspaceSessionFile().getParent());
        Files.writeString(SessionManager.workspaceSessionFile(), "{ broken }");

        WorkspaceSession loaded = SessionManager.loadWorkspaceSession(fallbackWorkspaceRoot);

        assertEquals(fallbackWorkspaceRoot, loaded.workspaceRoot());
        assertEquals(List.of(), loaded.openFiles());
        assertEquals(Optional.empty(), loaded.selectedFile());
        assertEquals(false, loaded.searchBarVisible());
        assertEquals(false, loaded.toolDockVisible());
        assertEquals(true, loaded.statusBarVisible());
        assertEquals(0.22d, loaded.toolDockDividerPosition());
        assertEquals(260d, loaded.toolDockWidth());
    }
}

