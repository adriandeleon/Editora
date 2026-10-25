package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import com.editora.editor.highlight.GrammarRegistry;
import com.editora.editor.highlight.TextMateHighlighter;
import com.editora.text.FoldRegions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A JavaScript file's Structure without a language server: one-line methods and arrow functions, which head no
 * fold region, are listed under the block that holds them, and a regex holding a backtick no longer hides
 * everything below it (Node.js review findings A4, A5).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class JavaScriptStructureFxTest {

    private static final String SOURCE = String.join(
            "\n",
            "/**",
            " * @param {string} file",
            " */",
            "export async function load(file) {",
            "  return file;",
            "}",
            "",
            "class Cache extends Map {",
            "  get(key) { return super.get(key); }",
            "  static create() { return new Cache(); }",
            "}",
            "",
            "const twice = (x) => x * 2;",
            "const escapeTicks = (s) => s.split(/`/).join(\"'\");",
            "",
            "function Banner() {",
            "  return <p className=\"note\">hi</p>;",
            "}",
            "",
            "export default function main() {",
            "  if (process.argv.length < 3) {",
            "    throw new Error('usage');",
            "  }",
            "}",
            "");

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void oneLineDeclarationsAreListedAndNothingBelowARegexIsLost() throws Exception {
        List<String> rows = FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 600, 400);
            EditorBuffer b = new EditorBuffer();
            b.setLanguageOverride("javascript");
            b.setContent(SOURCE);
            var field = EditorBuffer.class.getDeclaredField("symbols");
            field.setAccessible(true);
            field.set(
                    b,
                    TextMateHighlighter.analyze(SOURCE, GrammarRegistry.shared().forLanguageName("javascript"))
                            .symbols());
            b.getFoldManager().setServerRegions(FoldRegions.detect(SOURCE, "javascript"));
            p.attach(b);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
            List<String> out = new ArrayList<>();
            collect(tree, tree.getRoot(), "", out);
            return out;
        });
        assertEquals(
                List.of("load()", "Cache", "  get()", "  create()", "twice()", "escapeTicks()", "Banner()", "main()"),
                rows);
    }

    private static void collect(TreeView<Object> tree, TreeItem<Object> parent, String indent, List<String> out) {
        for (TreeItem<Object> item : parent.getChildren()) {
            @SuppressWarnings("unchecked")
            TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
            Object value = item.getValue();
            FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
            StringBuilder sb = new StringBuilder();
            for (var n : ((HBox) cell.getGraphic()).getChildren()) {
                if (n instanceof Text t && !t.getStyleClass().contains("structure-line-number")) {
                    sb.append(t.getText());
                }
            }
            out.add(indent + sb.toString().strip());
            collect(tree, item, indent + "  ", out);
        }
    }
}
