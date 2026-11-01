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

    /**
     * The filter searches the panel's project index (Map refactor stage 2): the list the Map and Search Everywhere
     * search, ranked their way — a file deeper than the old walk's 25 levels is found, and an in-app change reaches
     * the next filter without a walk.
     */
    @Test
    void theFilterSearchesTheProjectIndexDeepFilesIncluded(@TempDir Path dir) throws Exception {
        try (ProjectPanelRig r = new ProjectPanelRig(dir)) {
            Path deep = r.root;
            for (int level = 0; level < 30; level++) {
                deep = deep.resolve("d" + level);
            }
            java.nio.file.Files.createDirectories(deep);
            Path bottom = java.nio.file.Files.writeString(deep.resolve("Bottom.java"), "class Bottom {}");
            FxTestSupport.runOnFx(() -> r.filter.setText("Bottom"));
            r.await("the deep file", () -> r.item(bottom) != null);
            ProjectIndex index = FxTestSupport.callOnFx(r.panel::projectIndex);
            assertTrue(
                    index.searchListed(r.root, "Bottom", ProjectIndex.Kinds.FILES, 10)
                            .paths()
                            .contains(bottom),
                    "the filter listed the index's list");

            Path added = java.nio.file.Files.writeString(r.root.resolve("Added.java"), "class Added {}");
            FxTestSupport.runOnFx(() -> r.panel.noteLocalWrite(added)); // what a first save tells the panel
            FxTestSupport.runOnFx(() -> r.filter.setText("Added"));
            r.await("the saved file", () -> r.item(added) != null);
        }
    }
}
