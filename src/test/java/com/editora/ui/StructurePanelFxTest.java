package com.editora.ui;

import java.util.List;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Headless-FX coverage of {@link StructurePanel}: attaching a buffer and feeding an LSP {@link SymbolNode}
 * tree renders the class → methods hierarchy, and symbols for a non-attached buffer are ignored.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructurePanelFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(StructurePanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static SymbolNode method(String name, int line) {
        return new SymbolNode(name, "()", "method", line, line + 1, List.of());
    }

    @Test
    void lspSymbolsRenderTheClassMethodHierarchy() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanel::new);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class MyClass {\n  void foo() {}\n  void bar() {}\n}\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> p.attach(buffer));

        List<SymbolNode> symbols =
                List.of(new SymbolNode("MyClass", "", "class", 0, 3, List.of(method("foo", 1), method("bar", 2))));
        FxTestSupport.runOnFx(() -> p.setLspSymbols(buffer, symbols));

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        assertEquals(1, root.getChildren().size(), "one top-level class");
        assertEquals(
                2,
                FxTestSupport.callOnFx(
                        () -> root.getChildren().get(0).getChildren().size()),
                "class has two method children");
    }

    @Test
    void symbolsForANonAttachedBufferAreIgnored() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanel::new);
        EditorBuffer attached = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class A {}\n");
            return b;
        });
        EditorBuffer other = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class B {}\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> p.attach(attached));

        int before =
                FxTestSupport.callOnFx(() -> tree(p).getRoot().getChildren().size());
        // Symbols announced for a different buffer must not touch the attached buffer's outline.
        FxTestSupport.runOnFx(() -> p.setLspSymbols(other, List.of(new SymbolNode("Z", "", "class", 0, 1, List.of()))));
        assertEquals(
                before,
                FxTestSupport.callOnFx(() -> tree(p).getRoot().getChildren().size()),
                "outline unchanged for a non-attached buffer");
    }
}
