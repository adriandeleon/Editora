package com.editora.ui;

import java.lang.reflect.Field;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.editor.FoldRegions.Region;
import com.editora.editor.TextMateHighlighter.Symbol;
import com.editora.lsp.SymbolNode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    /** A panel placed in a scene, as it is when its tool window is open (the outline only rebuilds while shown). */
    private static StructurePanel shownPanel() {
        StructurePanel p = new StructurePanel();
        new Scene(new StackPane(p), 300, 400);
        return p;
    }

    private static SymbolNode method(String name, int line) {
        return new SymbolNode(name, "()", "method", line, line + 1, List.of());
    }

    @Test
    void lspSymbolsRenderTheClassMethodHierarchy() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanelFxTest::shownPanel);
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

        List<String> classStyles = FxTestSupport.callOnFx(() ->
                renderedNameStyles(tree(p), tree(p).getRoot().getChildren().getFirst()));
        List<String> methodStyles = FxTestSupport.callOnFx(() -> renderedNameStyles(
                tree(p),
                tree(p).getRoot().getChildren().getFirst().getChildren().getFirst()));
        assertTrue(classStyles.containsAll(List.of("text", "type")), "class names use the editor's type token");
        assertTrue(
                methodStyles.containsAll(List.of("text", "function")), "method names use the editor's function token");
    }

    @Test
    void rowsPreserveAppliedSemanticNameStyles() throws Exception {
        List<String> styles = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = shownPanel();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("class OldType {}\n");
            int start = buffer.getArea().getText().indexOf("OldType");
            buffer.getArea().setStyle(start, start + "OldType".length(), List.of("sem-type", "sem-deprecated"));
            panel.attach(buffer);
            panel.setLspSymbols(buffer, List.of(new SymbolNode("OldType", "", "class", 0, 0, List.of())));
            return renderedNameStyles(
                    tree(panel), tree(panel).getRoot().getChildren().getFirst());
        });

        assertTrue(styles.containsAll(List.of("text", "sem-type", "sem-deprecated")));
    }

    @Test
    void methodSignaturesKeepPerTokenStylesAndShowTheReturnType() throws Exception {
        List<Text> runs = FxTestSupport.callOnFx(() -> {
            StructurePanel panel = shownPanel();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setContent("static boolean zenFlag(java.util.List<String> args) { return true; }\n");
            style(buffer, "boolean", "keyword");
            style(buffer, "zenFlag", "function");
            style(buffer, "List", "type");
            style(buffer, "String", "type");
            panel.attach(buffer);
            panel.setLspSymbols(
                    buffer, List.of(new SymbolNode("zenFlag(List<String>)", "", "method", 0, 0, List.of())));
            return renderedTexts(
                    tree(panel), tree(panel).getRoot().getChildren().getFirst());
        });

        assertEquals(
                "zenFlag(List<String>) : boolean  1",
                runs.stream().map(Text::getText).reduce("", String::concat));
        assertTextHasStyle(runs, "zenFlag", "function");
        assertTextHasStyle(runs, "List", "type");
        assertTextHasStyle(runs, "String", "type");
        assertTextHasStyle(runs, "boolean", "keyword");
    }

    private static void style(EditorBuffer buffer, String token, String style) {
        int start = buffer.getArea().getText().indexOf(token);
        buffer.getArea().setStyle(start, start + token.length(), List.of(style));
    }

    private static void assertTextHasStyle(List<Text> runs, String text, String style) {
        assertTrue(
                runs.stream()
                        .anyMatch(run -> text.equals(run.getText())
                                && run.getStyleClass().contains(style)),
                text + " should use the editor's " + style + " token style");
    }

    @Test
    void rowsShowOneBasedLineNumbers() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanelFxTest::shownPanel);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("java");
            b.setContent("class MyClass {\n  void foo() {}\n}\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> p.attach(buffer));
        FxTestSupport.runOnFx(() -> p.setLspSymbols(buffer, List.of(method("foo", 1))));

        String renderedLine = FxTestSupport.callOnFx(() -> {
            TreeView<Object> tree = tree(p);
            Object value = tree.getRoot().getChildren().get(0).getValue();
            @SuppressWarnings("unchecked")
            TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
            HBox graphic = (HBox) cell.getGraphic();
            return graphic.getChildren().stream()
                    .filter(Text.class::isInstance)
                    .map(Text.class::cast)
                    .filter(t -> t.getStyleClass().contains("structure-line-number"))
                    .map(Text::getText)
                    .findFirst()
                    .orElse("");
        });

        assertEquals("  2", renderedLine, "the stored zero-based line is displayed to users as line 2");
    }

    private static List<String> renderedNameStyles(TreeView<Object> tree, TreeItem<Object> item) {
        return renderedTexts(tree, item).stream()
                .filter(text -> text.getStyleClass().contains("structure-name"))
                .findFirst()
                .orElseThrow()
                .getStyleClass();
    }

    private static List<Text> renderedTexts(TreeView<Object> tree, TreeItem<Object> item) {
        Object value = item.getValue();
        @SuppressWarnings("unchecked")
        TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
        FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
        HBox graphic = (HBox) cell.getGraphic();
        return graphic.getChildren().stream()
                .filter(Text.class::isInstance)
                .map(Text.class::cast)
                .toList();
    }

    @Test
    void fallbackOutlineDoesNotTurnInnerControlFlowOrCallsIntoMethods() throws Exception {
        List<String> labels = FxTestSupport.callOnFx(() -> {
            StructurePanel p = shownPanel();
            EditorBuffer buffer = new EditorBuffer();
            buffer.setLanguageOverride("java");
            buffer.setContent("private void ifLsp(Runnable action) {\n"
                    + "    if (lspEnabled()) {\n"
                    + "        action.run();\n"
                    + "    } else {\n"
                    + "        setStatus();\n"
                    + "    }\n"
                    + "}\n");
            // Pin the exact symbols/regions from the reported failure. The fallback used to associate the
            // preceding ifLsp definition with the inner if fold, then the run call with the else fold.
            setSymbols(buffer, List.of(new Symbol(0, "ifLsp", "function"), new Symbol(2, "run", "function")));
            buffer.getFoldManager().setServerRegions(List.of(new Region(0, 6), new Region(1, 3), new Region(3, 5)));
            p.attach(buffer);
            TreeItem<Object> root = tree(p).getRoot();
            assertEquals(1, root.getChildren().size(), "only the declaration-backed method is a root");
            assertTrue(root.getChildren().get(0).getChildren().isEmpty(), "control-flow folds are not members");
            return root.getChildren().stream().map(StructurePanelFxTest::label).toList();
        });

        assertEquals(List.of("ifLsp"), labels);
    }

    private static void setSymbols(EditorBuffer buffer, List<Symbol> symbols) {
        try {
            Field field = EditorBuffer.class.getDeclaredField("symbols");
            field.setAccessible(true);
            field.set(buffer, symbols);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void symbolsForANonAttachedBufferAreIgnored() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanelFxTest::shownPanel);
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

    /**
     * A Typst buffer outlines by its heading sections, nested by level.
     *
     * <p>It used to fall through to the generic fold-region path, which for Typst meant its brace pairs
     * labelled by whatever TextMate symbol sat on the header line: a three-section report showed a single
     * entry reading {@code #align()}. Asserted through the panel rather than through
     * {@code TypstOutline} so it pins the dispatch, which is where the gap actually was.
     */
    @Test
    void aTypstBufferOutlinesByItsHeadings() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanelFxTest::shownPanel);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("typst");
            b.setContent(
                    "#align(center)[\n  Title\n]\n\n= Introduction\nprose\n\n== A list\n- one\n\n= Second\ntail\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> p.attach(buffer));

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        List<String> top = FxTestSupport.callOnFx(
                () -> root.getChildren().stream().map(i -> label(i)).toList());
        assertEquals(List.of("Introduction", "Second"), top, "the two top-level sections");

        List<String> nested = FxTestSupport.callOnFx(() -> root.getChildren().get(0).getChildren().stream()
                .map(i -> label(i))
                .toList());
        assertEquals(List.of("A list"), nested, "== nests under the = above it");
    }

    /** The rendered text of an outline row, whatever node type the panel puts in the tree. */
    private static String label(TreeItem<Object> item) {
        Object v = item.getValue();
        return String.valueOf(FxTestSupport.<String>field(v, "label"));
    }

    /**
     * {@code #let} / {@code #show} bindings join the outline, nested under the section they are written in.
     *
     * <p>A Typst document's definitions are as navigable as its sections, and a template file can be almost
     * entirely bindings — outlining only headings left those files with an empty Structure window.
     */
    @Test
    void typstBindingsAppearUnderTheirSection() throws Exception {
        StructurePanel p = FxTestSupport.callOnFx(StructurePanelFxTest::shownPanel);
        EditorBuffer buffer = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("typst");
            b.setContent("#let brand = \"x\"\n\n= Setup\n#let accent = red\n#show heading: it => it\n");
            return b;
        });
        FxTestSupport.runOnFx(() -> p.attach(buffer));

        TreeItem<Object> root = FxTestSupport.callOnFx(() -> tree(p).getRoot());
        List<String> top = FxTestSupport.callOnFx(
                () -> root.getChildren().stream().map(i -> label(i)).toList());
        assertEquals(
                List.of("brand", "Setup"),
                top,
                "a binding above the first heading stays at the root, in document order");

        List<String> under = FxTestSupport.callOnFx(() -> root.getChildren().get(1).getChildren().stream()
                .map(i -> label(i))
                .toList());
        assertEquals(List.of("accent", "heading"), under, "bindings written inside a section nest under it");
    }
}
