package com.editora.config;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The per-project "Run custom…" history a build tool keeps (review B20; schema v23). */
class WorkspaceStateBuildHistoryTest {

    @Test
    void newestFirstDeduplicatedAndCapped() {
        WorkspaceState ws = new WorkspaceState();
        assertEquals(List.of(), ws.buildCustomHistory("maven"));

        ws.rememberBuildCustom("maven", "clean verify");
        ws.rememberBuildCustom("maven", "help:active-profiles");
        ws.rememberBuildCustom("maven", "clean verify"); // again: moves to the front, no duplicate
        assertEquals(List.of("clean verify", "help:active-profiles"), ws.buildCustomHistory("maven"));
        assertEquals(List.of(), ws.buildCustomHistory("gradle"), "per tool");

        for (int i = 0; i < WorkspaceState.BUILD_CUSTOM_HISTORY_SIZE + 5; i++) {
            ws.rememberBuildCustom("maven", "task" + i);
        }
        assertEquals(
                WorkspaceState.BUILD_CUSTOM_HISTORY_SIZE,
                ws.buildCustomHistory("maven").size());
        assertEquals(
                "task" + (WorkspaceState.BUILD_CUSTOM_HISTORY_SIZE + 4),
                ws.buildCustomHistory("maven").get(0));

        ws.rememberBuildCustom("maven", "  ");
        ws.rememberBuildCustom(null, "x");
        assertEquals(
                WorkspaceState.BUILD_CUSTOM_HISTORY_SIZE,
                ws.buildCustomHistory("maven").size(),
                "blanks ignored");
    }
}
