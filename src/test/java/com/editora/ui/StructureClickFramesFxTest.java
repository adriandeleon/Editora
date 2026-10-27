package com.editora.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FrameWatch;
import com.editora.lsp.SymbolNode;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * A click on a Structure row repaints the editor once. It used to take three frames: the caret moved with the
 * viewport left where it was, the next frame scrolled without the sticky-scroll rows for the new place, the one
 * after pinned them and moved the minimap's box — and the row clicked and the row left painted their text
 * unstyled for a frame. Every frame after the click is checked here, not where things settle.
 */
@Tag("fx")
class StructureClickFramesFxTest {

    /** Methods in the fixture class, each twelve lines: {@code m<i>} starts on line {@code 1 + 12 * i}. */
    private static final int METHODS = 40;

    private EditorBuffer buffer;
    private StructurePanel panel;
    private Stage stage;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @BeforeEach
    void show() throws Exception {
        FxTestSupport.runOnFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.setContent(JumpFrameCheck.javaSource(METHODS));
            buffer.setStickyScrollEnabled(true);
            buffer.getFoldManager().recompute();
            panel = new StructurePanel();
            HBox root = new HBox(buffer.getNode(), panel);
            HBox.setHgrow(buffer.getNode(), Priority.ALWAYS);
            stage = new Stage();
            stage.setScene(new Scene(root, 1000, 500));
            stage.show();
            panel.attach(buffer);
            panel.setLspSymbols(buffer, symbols());
        });
        // The minimap's first render (a snapshot) is paced; wait until it has painted its box.
        Object minimap = FxTestSupport.field(buffer, "minimap");
        for (int i = 0;
                i < 100 && FxTestSupport.callOnFx(() -> FxTestSupport.field(minimap, "paintedViewport")) == null;
                i++) {
            FxTestSupport.runOnFx(() -> {});
            Thread.sleep(30);
        }
    }

    @AfterEach
    void close() throws Exception {
        FxTestSupport.runOnFx(() -> {
            stage.close();
            buffer.dispose();
        });
    }

    @Test
    void aClickScrollsPinsAndMovesTheMinimapBoxInOneFrame() throws Exception {
        TreeView<?> tree = FxTestSupport.field(panel, "tree");
        CodeArea area = buffer.getArea();
        Object minimap = FxTestSupport.field(buffer, "minimap");
        assertNotNull(
                FxTestSupport.callOnFx(() -> FxTestSupport.field(minimap, "paintedViewport")), "the minimap painted");
        Supplier<String> check = JumpFrameCheck.of(buffer);

        int row = 1 + 25; // m25, below the first screen
        List<String> wrong = FrameWatch.wrongFrames(
                buffer, minimap, check, () -> tree.getSelectionModel().select(row));

        assertEquals(List.of(), wrong, "every frame after the click shows the row's line, pinned and boxed");
        assertEquals(1 + 12 * 25, (int) FxTestSupport.callOnFx(area::getCurrentParagraph), "the caret went to m25");
        assertFalse(
                ((List<?>) FxTestSupport.callOnFx(() -> FxTestSupport.field(buffer, "stickyLines"))).isEmpty(),
                "the class header is pinned over m25");
    }

    @Test
    void aSelectionChangeKeepsTheRowsItsCellsShow() throws Exception {
        TreeView<?> tree = FxTestSupport.field(panel, "tree");
        // JavaFX re-runs updateItem for the cells whose selection changed from their layout, after the CSS pass:
        // a graphic built there paints black text for a frame. Both looks are taken in one FX turn, so nothing but
        // the selection change runs between them: a pulse in between could carry a late outline rebuild (the fold
        // scan finishing on a slow runner) that replaces every cell for its own reasons.
        List<Map<Integer, Node>> looks = FxTestSupport.callOnFx(() -> {
            tree.applyCss();
            tree.layout();
            Map<Integer, Node> first = graphics(tree);
            tree.getSelectionModel().select(3);
            tree.getSelectionModel().select(5);
            tree.applyCss();
            tree.layout();
            return List.of(first, graphics(tree));
        });
        Map<Integer, Node> before = looks.get(0);
        Map<Integer, Node> after = looks.get(1);
        assertFalse(before.isEmpty(), "the tree shows rows");

        for (var row : before.entrySet()) {
            assertSame(row.getValue(), after.get(row.getKey()), "row " + row.getKey() + " kept its graphic");
        }
    }

    /** The graphic of each filled cell, by row. */
    private static Map<Integer, Node> graphics(TreeView<?> tree) {
        Map<Integer, Node> out = new HashMap<>();
        for (Node n : tree.lookupAll(".tree-cell")) {
            if (n instanceof TreeCell<?> cell && !cell.isEmpty() && cell.getGraphic() != null) {
                out.put(cell.getIndex(), cell.getGraphic());
            }
        }
        return out;
    }

    private static List<SymbolNode> symbols() {
        List<SymbolNode> methods = new ArrayList<>();
        for (int i = 0; i < METHODS; i++) {
            methods.add(new SymbolNode("m" + i + "()", null, "method", 1 + 12 * i, 12 + 12 * i, List.of()));
        }
        return List.of(new SymbolNode("Big", null, "class", 0, 1 + 12 * METHODS, methods));
    }
}
