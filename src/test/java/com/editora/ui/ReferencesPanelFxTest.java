package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.scene.control.ContentDisplay;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.text.Text;

import com.editora.ui.ReferencesPanel.Reference;
import com.editora.ui.ReferencesPanel.Run;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Headless-FX coverage of the References rows: a styled preview renders as syntax-classed text runs. */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReferencesPanelFxTest {

    private static final Path FILE = Path.of("/proj/App.java");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @SuppressWarnings("unchecked")
    private static TreeCell<Object> renderedCell(Reference ref) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            ReferencesPanel panel = new ReferencesPanel((file, line, col) -> {});
            panel.setReferences(List.of(ref));
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(panel, "tree");
            TreeItem<Object> item =
                    tree.getRoot().getChildren().get(0).getChildren().get(0);
            Object value = item.getValue();
            TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
            return cell;
        });
    }

    @Test
    void styledPreviewRendersAsSyntaxClassedRunsAfterThePosition() throws Exception {
        TreeCell<Object> cell = renderedCell(new Reference(
                FILE,
                186,
                24,
                "private static void run(",
                List.of(new Run("private", List.of("keyword")), new Run(" static void run(", List.of()))));

        assertEquals("187:25  ", cell.getText());
        assertEquals(ContentDisplay.RIGHT, cell.getContentDisplay(), "the code follows the position");
        List<Text> texts = ((HBox) cell.getGraphic())
                .getChildren().stream().map(Text.class::cast).toList();
        assertEquals(2, texts.size());
        assertEquals("private", texts.get(0).getText());
        assertTrue(
                texts.get(0).getStyleClass().containsAll(List.of("text", "keyword", "reference-code")),
                "the classes the editor's token rules target");
        assertEquals(List.of("reference-code", "text"), texts.get(1).getStyleClass());
    }

    @Test
    void previewWithoutRunsStaysPlainText() throws Exception {
        TreeCell<Object> cell = renderedCell(new Reference(FILE, 4, 0, "  foo();  "));
        assertEquals("5:1  foo();", cell.getText());
        assertNull(cell.getGraphic());
    }
}
