package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression for #549: the Structure outline rebuilt its tree (an O(n) document split + a full {@code TreeView}
 * rebuild) on every debounced fold-region change — i.e. on every edit — whether or not the tool window was open.
 * This verifies the rebuild is deferred while the panel is hidden (node not in a scene) and coalesced into a
 * single rebuild when the window is (re)opened.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureHiddenFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void hiddenPanelDefersTheTreeRebuildUntilShown() throws Exception {
        Object[] r = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = new StructurePanel(); // never added to a scene → hidden

            EditorBuffer buffer = new EditorBuffer();
            buffer.getArea().replaceText("class A {\n  void m() {}\n}\n");
            panel.attach(buffer); // recompute → rebuild, but hidden → deferred

            List<SymbolNode> syms = List.of(new SymbolNode(
                    "A", "", "class", 0, 2, List.of(new SymbolNode("m", "()", "method", 1, 1, List.of()))));
            panel.setLspSymbols(buffer, syms); // rebuild → deferred while hidden

            TreeView<?> tree = FxTestSupport.field(panel, "tree");
            boolean pendingWhileHidden = FxTestSupport.field(panel, "pendingRebuild");
            int childrenWhileHidden =
                    tree.getRoot() == null ? 0 : tree.getRoot().getChildren().size();

            new Scene(new StackPane(panel), 300, 400); // sceneProperty → rebuild the deferred outline

            boolean pendingAfterShow = FxTestSupport.field(panel, "pendingRebuild");
            int childrenAfterShow =
                    tree.getRoot() == null ? 0 : tree.getRoot().getChildren().size();

            return new Object[] {pendingWhileHidden, childrenWhileHidden, pendingAfterShow, childrenAfterShow};
        });

        assertTrue((Boolean) r[0], "a rebuild requested while hidden is deferred (pending)");
        assertEquals(0, (Integer) r[1], "the tree is not built while the panel is hidden");
        assertFalse((Boolean) r[2], "showing the window clears the pending flag");
        assertTrue((Integer) r[3] > 0, "the tree is built when the window is shown: " + r[3]);
    }
}
