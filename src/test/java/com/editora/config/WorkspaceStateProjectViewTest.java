package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v13: the Map's open state and navigation options; v24: where the Map's window was; v25: the
 * Map's "Hide unmatched"; v26: a stored right-to-left flow becomes left-to-right.
 */
class WorkspaceStateProjectViewTest {

    @Test
    void aV12FileKeepsAVerticalFlowAndGainsTheOldBehaviourAsDefaults(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":12,\"projectMapFlow\":\"TOP_TO_BOTTOM\",\"debugWatches\":[\"kept\"]}");

        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState state = config.getWorkspaceState();

        assertEquals("TOP_TO_BOTTOM", state.getProjectMapFlow(), "a vertical flow was chosen and is kept");
        assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
        assertTrue(state.isProjectMapKeepZoom());
        assertTrue(state.isProjectMapFocusNewColumn());
        assertEquals(java.util.List.of("kept"), state.getDebugWatches());
        assertEquals(WorkspaceState.SCHEMA_VERSION, state.getSchemaVersion());
    }

    @Test
    void aFileFromBeforeTheMapHadAFlowGetsTheNewDefault(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":10}");

        ConfigManager config = new ConfigManager(dir);
        config.load();

        assertEquals("LEFT_TO_RIGHT", config.getWorkspaceState().getProjectMapFlow());
    }

    @Test
    void aStoredRightToLeftFlowBecomesLeftToRightOnce(@TempDir Path dir) throws Exception {
        // Right-to-left was the default written into v11–v25 files, so it is reset; a later choice of it sticks.
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":25,\"projectMapFlow\":\"RIGHT_TO_LEFT\",\"debugWatches\":[\"kept\"]}");
        ConfigManager first = new ConfigManager(dir);
        first.load();
        assertEquals("LEFT_TO_RIGHT", first.getWorkspaceState().getProjectMapFlow());
        assertEquals(java.util.List.of("kept"), first.getWorkspaceState().getDebugWatches());
        first.getWorkspaceState().setProjectMapFlow("RIGHT_TO_LEFT");
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();

        assertEquals("RIGHT_TO_LEFT", reopened.getWorkspaceState().getProjectMapFlow());
        assertEquals(WorkspaceState.SCHEMA_VERSION, reopened.getWorkspaceState().getSchemaVersion());
    }

    @Test
    void theViewModeAndNavigationOptionsSurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        first.getWorkspaceState().setProjectViewMode(WorkspaceState.PROJECT_VIEW_MAP);
        first.getWorkspaceState().setProjectMapKeepZoom(false);
        first.getWorkspaceState().setProjectMapFocusNewColumn(false);
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        WorkspaceState state = reopened.getWorkspaceState();

        assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());
        assertFalse(state.isProjectMapKeepZoom());
        assertFalse(state.isProjectMapFocusNewColumn());
    }

    @Test
    void anUnknownViewModeFallsBackToTheTree() {
        WorkspaceState state = new WorkspaceState();
        state.setProjectViewMode("GRID");
        assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
        state.setProjectViewMode(null);
        assertEquals(WorkspaceState.PROJECT_VIEW_TREE, state.getProjectViewMode());
    }

    /** Schema v14: the branch dropdown's folded sections. */
    @Test
    void theFoldedBranchSectionsSurviveARestartAndAnOlderFileHasNone(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":13,\"debugWatches\":[\"kept\"]}");
        ConfigManager first = new ConfigManager(dir);
        first.load();
        assertEquals(java.util.List.of(), first.getWorkspaceState().getCollapsedBranchSections());
        assertEquals(java.util.List.of("kept"), first.getWorkspaceState().getDebugWatches());
        first.getWorkspaceState().setCollapsedBranchSections(new java.util.ArrayList<>(java.util.List.of("remote")));
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();

        assertEquals(java.util.List.of("remote"), reopened.getWorkspaceState().getCollapsedBranchSections());
        assertEquals(WorkspaceState.SCHEMA_VERSION, reopened.getWorkspaceState().getSchemaVersion());
    }

    /** Schema v24: the Project Map window's bounds. */
    @Test
    void theMapWindowsBoundsSurviveARestartAndAnOlderFileHasNone(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":23,\"projectViewMode\":\"MAP\",\"debugWatches\":[\"kept\"]}");
        ConfigManager first = new ConfigManager(dir);
        first.load();
        WorkspaceState state = first.getWorkspaceState();
        assertEquals(java.util.List.of(), state.getProjectMapWindowBounds());
        assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode(), "the Map reopens, in its window");
        assertEquals(java.util.List.of("kept"), state.getDebugWatches());
        state.setProjectMapWindowBounds(java.util.List.of(10.0, 20.0, 900.0, 600.0));
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();

        assertEquals(
                java.util.List.of(10.0, 20.0, 900.0, 600.0),
                reopened.getWorkspaceState().getProjectMapWindowBounds());
        assertEquals(WorkspaceState.SCHEMA_VERSION, reopened.getWorkspaceState().getSchemaVersion());
    }

    /** Schema v25: the Map's "Hide unmatched". */
    @Test
    void hideUnmatchedSurvivesARestartAndAnOlderFileKeepsFading(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("workspace-state.json"), "{\"schemaVersion\":24,\"debugWatches\":[\"kept\"]}");
        ConfigManager first = new ConfigManager(dir);
        first.load();
        assertFalse(first.getWorkspaceState().isProjectMapHideUnmatched());
        assertEquals(java.util.List.of("kept"), first.getWorkspaceState().getDebugWatches());
        first.getWorkspaceState().setProjectMapHideUnmatched(true);
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();

        assertTrue(reopened.getWorkspaceState().isProjectMapHideUnmatched());
        assertEquals(WorkspaceState.SCHEMA_VERSION, reopened.getWorkspaceState().getSchemaVersion());
    }
}
