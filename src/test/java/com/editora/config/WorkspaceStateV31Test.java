package com.editora.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.migration.ConfigSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Workspace schema v31 (Map code browser gap 9): a kept Project Map card's caret, top line and the answers asked from
 * it, with their edges. A v30 card has none and keeps the rest; all of it survives a restart, in the restart state
 * and in a working set.
 */
class WorkspaceStateV31Test {

    @Test
    void aV30CardHasNoCaretOrAnswersAndKeepsWhatItHad(@TempDir Path dir) throws Exception {
        Files.writeString(
                dir.resolve("workspace-state.json"),
                "{\"schemaVersion\":30,\"projectMapState\":{\"root\":\"/work/p\","
                        + "\"cards\":[{\"path\":\"a.go\",\"x\":10,\"pinned\":true}]}}");
        ConfigManager config = new ConfigManager(dir);
        config.load();
        ProjectMapState.Card card =
                config.getWorkspaceState().getProjectMapState().getCards().getFirst();

        assertEquals(-1, card.getCaretLine(), "no caret: the card opens at its top");
        assertEquals(-1, card.getTopLine());
        assertTrue(card.getAnswers().isEmpty());
        assertTrue(card.isPinned());
        assertEquals(10, card.getX());
        assertEquals(WorkspaceState.SCHEMA_VERSION, config.getWorkspaceState().getSchemaVersion());
        assertEquals(WorkspaceState.SCHEMA_VERSION, ConfigSchema.WORKSPACE.currentVersion());
    }

    @Test
    void aCardsCaretAndAnswersSurviveARestartAndAWorkingSet(@TempDir Path dir) {
        ConfigManager first = new ConfigManager(dir);
        first.load();
        ProjectMapState.Card card = new ProjectMapState.Card("src/App.java", 300, 80, 520, 360, 1.1, false);
        card.setCaretLine(12);
        card.setCaretColumn(8);
        card.setTopLine(9);
        ProjectMapState.Group group = new ProjectMapState.Group();
        group.setPath("src/Use.java/…mrun~0");
        group.setFile("src/Use.java");
        group.setLabel("run(App)");
        group.setLine(4);
        group.setCount(2);
        group.setMark("add");
        group.setEdge(true);
        group.setOwned(true);
        ProjectMapState.Place place = new ProjectMapState.Place();
        place.setFile("src/Use.java");
        place.setLine(4);
        place.setColumn(12);
        place.setCount(1);
        place.setName("run");
        ProjectMapState.Answer answer = new ProjectMapState.Answer();
        answer.setKind("CALLERS");
        answer.setVia("SERVER");
        answer.setName("add");
        answer.setGroups(List.of(group));
        answer.setPlaces(List.of(place));
        card.setAnswers(List.of(answer));
        ProjectMapState shown = new ProjectMapState();
        shown.setRoot("/work/project");
        shown.setCards(List.of(card));
        first.getWorkspaceState().setProjectMapState(shown);
        first.getWorkspaceState().setProjectMapWorkingSets(List.of(new ProjectMapWorkingSet("Callers", shown)));
        first.save();

        ConfigManager reopened = new ConfigManager(dir);
        reopened.load();
        for (ProjectMapState state : List.of(
                reopened.getWorkspaceState().getProjectMapState(),
                reopened.getWorkspaceState()
                        .getProjectMapWorkingSets()
                        .getFirst()
                        .getState())) {
            ProjectMapState.Card back = state.getCards().getFirst();
            assertEquals(List.of(12, 8, 9), List.of(back.getCaretLine(), back.getCaretColumn(), back.getTopLine()));
            ProjectMapState.Answer kept = back.getAnswers().getFirst();
            assertEquals(List.of("CALLERS", "SERVER", "add"), List.of(kept.getKind(), kept.getVia(), kept.getName()));
            ProjectMapState.Group g = kept.getGroups().getFirst();
            assertEquals("src/Use.java/…mrun~0", g.getPath());
            assertEquals("src/Use.java", g.getFile());
            assertEquals("run(App)", g.getLabel());
            assertEquals(List.of(4, 2), List.of(g.getLine(), g.getCount()));
            assertEquals("add", g.getMark());
            assertTrue(g.isEdge() && g.isOwned());
            ProjectMapState.Place p = kept.getPlaces().getFirst();
            assertEquals("src/Use.java", p.getFile());
            assertEquals(List.of(4, 12, 1), List.of(p.getLine(), p.getColumn(), p.getCount()));
            assertEquals("run", p.getName());
        }

        ProjectMapState.Card copy = card.copy();
        assertNotSame(card.getAnswers(), copy.getAnswers());
        assertEquals(12, copy.getCaretLine());
        ProjectMapState.Answer blank = new ProjectMapState.Answer();
        blank.setKind(null);
        blank.setVia(null);
        blank.setGroups(null);
        blank.setPlaces(null);
        assertEquals("", blank.getKind());
        assertEquals("", blank.getVia());
        assertNull(blank.getName());
        assertTrue(blank.getGroups().isEmpty() && blank.getPlaces().isEmpty());
        ProjectMapState.Group empty = new ProjectMapState.Group();
        empty.setPath(null);
        empty.setFile(null);
        empty.setLabel(null);
        assertEquals("", empty.getPath() + empty.getFile() + empty.getLabel());
        ProjectMapState.Place nowhere = new ProjectMapState.Place();
        nowhere.setFile(null);
        assertEquals("", nowhere.getFile());
        card.setAnswers(null);
        assertTrue(card.getAnswers().isEmpty());
    }
}
