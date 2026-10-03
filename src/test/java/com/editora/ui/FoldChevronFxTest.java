package com.editora.ui;

import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.editor.FoldManager;
import com.editora.editor.FoldRegions;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The fold chevron in the gutter. A header row's graphic is only rebuilt when the row starts or stops being
 * a fold start, so the chevron of a block that has since <em>grown</em> is the one built for its old extent:
 * it must look the region up when clicked, not remember the one it was built with.
 */
@Tag("fx")
class FoldChevronFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Label chevron(Node gutter) {
        if (gutter instanceof Label label && label.getStyleClass().contains("fold-chevron")) {
            return label;
        }
        if (gutter instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Label found = chevron(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void click(Node node) {
        node.fireEvent(new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                1,
                1,
                1,
                1,
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                true,
                false,
                false,
                false,
                false,
                true,
                null));
    }

    @Test
    void aChevronBuiltBeforeTheBlockGrewFoldsItsCurrentExtent() throws Exception {
        FxTestSupport.runOnFx(() -> {
            CodeArea area = new CodeArea("a {\n1\n2\n}\n5\n6\n7\n");
            FoldManager folds = new FoldManager(area);
            folds.setServerRegions(List.of(new FoldRegions.Region(0, 3)));
            Label chevron = chevron(folds.gutterFactory(true).apply(0));
            assertNotNull(chevron, "line 0 starts a region, so its gutter has a chevron");

            // The block grows (lines typed inside it); line 0 is still a fold start, so its row is not rebuilt.
            folds.setServerRegions(List.of(new FoldRegions.Region(0, 5)));
            click(chevron);

            assertTrue(area.isFolded(1), "the block folded");
            assertTrue(area.isFolded(5), "to its CURRENT last line, not the one it had when the row was built");
            assertFalse(area.isFolded(6));

            click(chevron);
            assertFalse(area.isFolded(1), "and the same chevron unfolds it");
        });
    }
}
