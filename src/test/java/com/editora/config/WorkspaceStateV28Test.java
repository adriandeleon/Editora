package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v28 (Project Map review 3): what the Project Map keeps of an exploration across restarts (B6),
 * and three ⋯ options of the Map — Compact rows, Hide ignored and generated, and whether the Overview card was
 * shown. A v27 file gains the defaults and keeps the rest.
 */
class WorkspaceStateV28Test {

    @Test
    void aV27FileGainsTheDefaultsAndKeepsWhatItHad(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":27,\"projectViewMode\":\"MAP\",\"projectMapFollowActiveFile\":true,"
                        + "\"debugWatches\":[\"kept\"]}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        WorkspaceState state = config.getWorkspaceState();

        assertNull(state.getProjectMapState(), "a Map that starts afresh");
        assertFalse(state.isProjectMapCompactRows());
        assertFalse(state.isProjectMapHideIgnored());
        assertFalse(state.isProjectMapOverviewSeen());
        assertTrue(state.isProjectMapFollowActiveFile());
        assertEquals(WorkspaceState.PROJECT_VIEW_MAP, state.getProjectViewMode());
        assertEquals(List.of("kept"), state.getDebugWatches());
        assertEquals(28, state.getSchemaVersion());
    }

    @Test
    void theMapsStateAndTheThreeOptionsSurviveARestart(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        WorkspaceState written = first.getWorkspaceState();
        written.setProjectMapCompactRows(true);
        written.setProjectMapHideIgnored(true);
        written.setProjectMapOverviewSeen(true);
        ProjectMapState map = new ProjectMapState();
        map.setRoot("/work/project");
        map.setExpanded(List.of("src", "src/main"));
        map.setSelected("src/main/App.java");
        map.setHistory(List.of("", "src/main/App.java", "README.md"));
        map.setHistoryIndex(1);
        map.setZoom(0.8);
        map.setOffsetX(-120.5);
        map.setOffsetY(40);
        map.setCards(List.of(
                new ProjectMapState.Card("src/main/App.java", 300, 80, 520, 360, 1.21, true),
                new ProjectMapState.Card("src/main/App.java/…smain~0", 40, 400, 480, 240, 1.0, false)));
        written.setProjectMapState(map);
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        WorkspaceState state = reopened.getWorkspaceState();

        assertTrue(state.isProjectMapCompactRows());
        assertTrue(state.isProjectMapHideIgnored());
        assertTrue(state.isProjectMapOverviewSeen());
        ProjectMapState read = state.getProjectMapState();
        assertNotNull(read);
        assertEquals("/work/project", read.getRoot());
        assertEquals(List.of("src", "src/main"), read.getExpanded());
        assertEquals("src/main/App.java", read.getSelected());
        assertEquals(List.of("", "src/main/App.java", "README.md"), read.getHistory());
        assertEquals(1, read.getHistoryIndex());
        assertEquals(0.8, read.getZoom());
        assertEquals(-120.5, read.getOffsetX());
        assertEquals(40, read.getOffsetY());
        assertEquals(2, read.getCards().size());
        ProjectMapState.Card card = read.getCards().getFirst();
        assertEquals("src/main/App.java", card.getPath());
        assertEquals(300, card.getX());
        assertEquals(520, card.getWidth());
        assertEquals(1.21, card.getTextZoom());
        assertTrue(card.isStructure());
        assertEquals("src/main/App.java/…smain~0", read.getCards().get(1).getPath());
        assertEquals(WorkspaceState.SCHEMA_VERSION, state.getSchemaVersion());
    }
}
