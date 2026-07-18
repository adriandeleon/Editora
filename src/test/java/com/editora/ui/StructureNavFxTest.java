package com.editora.ui;

import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.StackPane;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Regression guard for "Structure navigation keeps jumping back": a chatty LSP server (jdtls re-sends
 * {@code language/status} "ServiceReady" repeatedly) re-pushes the same document symbols, which used to
 * rebuild the tree, reset the selection to row 0, and jump the editor to the top while the user navigated.
 * {@link StructurePanel#setLspSymbols} now no-ops when the outline is unchanged, and a genuine rebuild
 * preserves the selected symbol instead of snapping to the first row.
 */
@Tag("fx")
class StructureNavFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static EditorBuffer tenLineBuffer() throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setContent("line0\nline1\nline2\nline3\nline4\nline5\nline6\nline7\nline8\nline9\n");
            return b;
        });
    }

    /** A panel placed in a scene, as it is when its tool window is open (the outline only rebuilds while shown). */
    private static StructurePanel shownPanel() {
        StructurePanel p = new StructurePanel();
        new Scene(new StackPane(p), 300, 400);
        return p;
    }

    private static List<SymbolNode> threeSymbols() {
        return List.of(
                new SymbolNode("Alpha", null, "class", 0, 9, List.of()),
                new SymbolNode("beta", null, "method", 3, 4, List.of()),
                new SymbolNode("gamma", null, "method", 6, 7, List.of()));
    }

    /** Reads the line of the tree's currently-selected StructureNode (package-private) via its accessor. */
    private static int selectedLine(TreeView<?> tree) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            TreeItem<?> sel = tree.getSelectionModel().getSelectedItem();
            return sel == null || sel.getValue() == null
                    ? -1
                    : (int) FxTestSupport.call(sel.getValue(), "line", new Class<?>[] {});
        });
    }

    @Test
    void unchangedSymbolsKeepSelectionAndDoNotRebuild() throws Exception {
        StructurePanel panel = FxTestSupport.callOnFx(StructureNavFxTest::shownPanel);
        EditorBuffer buffer = tenLineBuffer();
        FxTestSupport.runOnFx(() -> {
            panel.attach(buffer);
            panel.setLspSymbols(buffer, threeSymbols());
        });

        TreeView<?> tree = FxTestSupport.field(panel, "tree");
        // Select the third symbol ("gamma"), mimicking the user navigating away from the top.
        TreeItem<?> selectedBefore = FxTestSupport.callOnFx(() -> {
            tree.getSelectionModel().select(2);
            return tree.getSelectionModel().getSelectedItem();
        });
        assertEquals(6, selectedLine(tree));

        // The server re-announces the SAME outline (a fresh but value-equal list).
        FxTestSupport.runOnFx(() -> panel.setLspSymbols(buffer, threeSymbols()));

        // No rebuild happened: the very same TreeItem instance is still selected (not reset to row 0).
        TreeItem<?> selectedAfter =
                FxTestSupport.callOnFx(() -> tree.getSelectionModel().getSelectedItem());
        assertSame(
                selectedBefore, selectedAfter, "an unchanged outline must not rebuild the tree or move the selection");
    }

    @Test
    void changedSymbolsRebuildButPreserveTheSelectedSymbol() throws Exception {
        StructurePanel panel = FxTestSupport.callOnFx(StructureNavFxTest::shownPanel);
        EditorBuffer buffer = tenLineBuffer();
        FxTestSupport.runOnFx(() -> {
            panel.attach(buffer);
            panel.setLspSymbols(buffer, threeSymbols());
        });

        TreeView<?> tree = FxTestSupport.field(panel, "tree");
        FxTestSupport.runOnFx(() -> tree.getSelectionModel().select(2)); // "gamma" at line 6

        // A genuine change (a 4th symbol appended) forces a rebuild — the user's selected symbol must survive.
        List<SymbolNode> grown = List.of(
                new SymbolNode("Alpha", null, "class", 0, 9, List.of()),
                new SymbolNode("beta", null, "method", 3, 4, List.of()),
                new SymbolNode("gamma", null, "method", 6, 7, List.of()),
                new SymbolNode("delta", null, "method", 8, 9, List.of()));
        FxTestSupport.runOnFx(() -> panel.setLspSymbols(buffer, grown));

        // The selection should still be "gamma" (line 6), re-selected by line+label, not snapped to row 0.
        assertEquals(6, selectedLine(tree), "a rebuild must preserve the user's selected symbol");
    }
}
