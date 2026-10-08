package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Filter, then Enter: the first match opens. Enter opens the highlighted row, and the results used to arrive
 * with none highlighted, so Enter did nothing — and Down first landed on the project's own row, where Enter
 * collapsed the result list.
 */
@Tag("fx")
class ProjectPanelFilterEnterFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static KeyEvent key(KeyCode code) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false);
    }

    @Test
    void enterInTheFilterFieldOpensTheFirstMatch(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.filter.setText("helper"));
            r.await("the search result", () -> r.item(r.helper) != null);

            FxTestSupport.runOnFx(() -> javafx.event.Event.fireEvent(r.filter, key(KeyCode.ENTER)));

            FxTestSupport.runOnFx(() -> {
                assertEquals(List.of(r.helper), r.opened, "filter, Enter: the match opens");
                assertTrue(r.tree.getRoot().isExpanded(), "and the result list is still on show");
            });
        }
    }

    @Test
    void theFirstMatchIsHighlightedAndDownMovesOnToTheNext(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.filter.setText("a.")); // Main.java, Helper.java, alpha.txt, beta.txt
            r.await("the search results", () -> r.item(r.main) != null);

            FxTestSupport.runOnFx(() -> {
                assertTrue(r.rows().size() >= 3, "several matches: " + r.rows());
                assertEquals(r.tree.getTreeItem(1).getValue(), r.selected(), "the first match, not the root row");
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.DOWN));
                assertEquals(r.tree.getTreeItem(2).getValue(), r.selected());
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.ENTER));
                assertEquals(List.of(r.tree.getTreeItem(2).getValue()), r.opened);
            });
        }
    }

    @Test
    void aFilterThatMatchesNothingHighlightsNothingAndEnterOpensNothing(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            FxTestSupport.runOnFx(() -> r.filter.setText("helper"));
            r.await("the search result", () -> r.item(r.helper) != null);
            FxTestSupport.runOnFx(() -> r.filter.setText("no-such-file-anywhere"));
            r.await("the empty result", () -> r.rows().size() == 1 && r.item(r.helper) == null);

            FxTestSupport.runOnFx(() -> {
                assertNull(r.selected(), "the highlight of the previous result is gone with it");
                javafx.event.Event.fireEvent(r.filter, key(KeyCode.ENTER));
                assertTrue(r.opened.isEmpty());
            });
        }
    }
}
