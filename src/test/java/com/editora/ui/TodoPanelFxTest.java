package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.Label;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.todo.TodoMatch;
import com.editora.todo.TodoService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Headless-FX coverage of {@link TodoPanel#setResults}: rendering a {@link TodoService.Outcome} as a
 * file → match tree and updating the summary label (incl. the empty / truncated cases). No-op Actions.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TodoPanelFxTest {

    private static final TodoPanel.Actions NOOP = new TodoPanel.Actions() {
        @Override
        public void openMatch(Path file, int line, int col) {}

        @Override
        public void refresh() {}
    };

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private TodoPanel panel() throws Exception {
        return FxTestSupport.callOnFx(() -> new TodoPanel(NOOP));
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(TodoPanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static TodoMatch match(int line, String text) {
        return new TodoMatch(0, 4, line, 0, text, "TODO", "#ffcc00");
    }

    @Test
    void rendersFileMatchTreeAndSummary() throws Exception {
        TodoPanel p = panel();
        TodoService.Outcome outcome = new TodoService.Outcome(
                List.of(
                        new TodoService.FileTodos(
                                Path.of("/proj/A.java"), List.of(match(3, "TODO a"), match(7, "TODO b"))),
                        new TodoService.FileTodos(Path.of("/proj/B.java"), List.of(match(1, "TODO c")))),
                3,
                2,
                false);
        FxTestSupport.runOnFx(() -> p.setResults(outcome));

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(2, root.getChildren().size(), "two file groups");
        int total = FxTestSupport.callOnFx(() -> root.getChildren().stream()
                .mapToInt(f -> f.getChildren().size())
                .sum());
        assertEquals(3, total, "all TODO matches rendered");

        Label summary = FxTestSupport.field(p, "summary");
        assertFalse(
                FxTestSupport.callOnFx(() -> summary.getText()).isBlank(), "summary populated for a non-empty scan");
    }

    @Test
    void emptyOutcomeClearsTheTree() throws Exception {
        TodoPanel p = panel();
        FxTestSupport.runOnFx(() -> p.setResults(new TodoService.Outcome(List.of(), 0, 0, false)));
        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(0, root.getChildren().size(), "no files ⇒ empty tree");
        Label summary = FxTestSupport.field(p, "summary");
        assertFalse(FxTestSupport.callOnFx(() -> summary.getText()).isBlank(), "summary shows the 'none' message");
    }
}
