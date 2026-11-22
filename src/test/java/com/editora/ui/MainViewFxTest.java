package com.editora.ui;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Every window after the first is built by {@link MainView} rather than by parsing {@code main.fxml} on the FX thread
 * (PERF-01, Pauta 6.2.0): the two frames must stay the same — the same nodes in the same order with the same ids, style
 * classes and layout, every {@code @FXML} field of the controller filled and every button wired.
 */
@Tag("fx")
class MainViewFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void theFrameBuiltInCodeIsTheFrameMainFxmlDeclares() throws Exception {
        List<String> fromFxml = FxTestSupport.callOnFx(() -> {
            FXMLLoader loader = new FXMLLoader(MainController.class.getResource("main.fxml"));
            loader.setClassLoader(MainController.class.getClassLoader());
            BorderPane root = loader.load();
            return describe(root, loader.getController());
        });
        List<String> fromCode = FxTestSupport.callOnFx(() -> {
            MainController controller = new MainController();
            return describe(MainView.build(controller), controller);
        });
        assertEquals(fromFxml, fromCode);
    }

    /** The tree as lines, then each {@code @FXML} field's node: what must agree between the two builds. */
    private static List<String> describe(BorderPane root, MainController controller) throws Exception {
        List<String> out = new ArrayList<>();
        walk(root, 0, out);
        for (Field f : MainController.class.getDeclaredFields()) {
            if (f.isAnnotationPresent(FXML.class)) {
                f.setAccessible(true);
                Object value = f.get(controller);
                assertNotNull(value, "@FXML " + f.getName() + " is filled");
                out.add("field " + f.getName() + " = " + ((Node) value).getId());
            }
        }
        return out;
    }

    private static void walk(Node node, int depth, List<String> out) {
        StringBuilder line = new StringBuilder("  ".repeat(depth))
                .append(node.getClass().getSimpleName())
                .append(" #")
                .append(node.getId())
                .append(' ')
                .append(node.getStyleClass());
        if (node instanceof HBox box) {
            line.append(" align=").append(box.getAlignment());
        }
        if (HBox.getHgrow(node) != null) {
            line.append(" hgrow=").append(HBox.getHgrow(node));
        }
        if (node instanceof javafx.scene.control.Separator sep) {
            line.append(" ").append(sep.getOrientation());
        }
        if (node instanceof ButtonBase button) {
            line.append(" action=").append(button.getOnAction() != null);
        }
        out.add(line.toString());
        List<Node> children = new ArrayList<>();
        if (node instanceof BorderPane pane) {
            for (Node part :
                    new Node[] {pane.getTop(), pane.getLeft(), pane.getCenter(), pane.getRight(), pane.getBottom()}) {
                if (part != null) {
                    children.add(part);
                }
            }
        } else if (node instanceof ToolBar bar) {
            children.addAll(bar.getItems());
        } else if (node instanceof javafx.scene.control.TabPane) {
            // no children of its own in the frame
        } else if (node instanceof Parent parent && !(node instanceof javafx.scene.control.Control)) {
            children.addAll(parent.getChildrenUnmodifiable());
        }
        for (Node child : children) {
            walk(child, depth + 1, out);
        }
    }
}
