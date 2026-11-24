package com.editora.editor;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link BufferInlays#labelAt}: the code lens under the pointer is found by descending only into the nodes whose
 * bounds hold the point, not by walking every node of the area on each mouse move (perf review 2026-11-23 L8).
 */
@Tag("fx")
class BufferInlaysLabelAtFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    /** A pane that counts how often its children are listed: a walk into it lists them. */
    private static final class CountingPane extends Pane {
        int listed;

        CountingPane(Node... children) {
            super(children);
        }

        @Override
        public javafx.collections.ObservableList<Node> getChildrenUnmodifiable() {
            listed++;
            return super.getChildrenUnmodifiable();
        }
    }

    @Test
    void findsTheLabelUnderThePointAndSkipsLinesElsewhere() throws Exception {
        EditorFx.onFx(() -> {
            Label lens = new Label("2 references");
            lens.getStyleClass().add("code-lens");
            lens.resizeRelocate(100, 0, 80, 20);
            Pane line1 = new Pane(lens);
            line1.resizeRelocate(0, 0, 400, 20);
            CountingPane line2 = new CountingPane(new Label("other"));
            line2.resizeRelocate(0, 20, 400, 20);
            Group root = new Group(line1, line2);
            new Scene(root, 400, 40);

            assertSame(lens, BufferInlays.labelAt(root, 120, 10, "code-lens"));
            assertEquals(0, line2.listed, "a line the point is not on is not walked");
            assertNull(BufferInlays.labelAt(root, 50, 10, "code-lens"), "beside the lens, on its line");
            assertNull(BufferInlays.labelAt(root, 120, 30, "code-lens"), "on the next line");

            lens.setPadding(new javafx.geometry.Insets(0, 0, 0, 20));
            assertNull(BufferInlays.labelAt(root, 110, 10, "code-lens"), "the gap before the lens is the code's");
            assertSame(lens, BufferInlays.labelAt(root, 125, 10, "code-lens"));
        });
    }
}
