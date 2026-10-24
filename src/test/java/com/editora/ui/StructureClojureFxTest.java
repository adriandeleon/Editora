package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.scene.text.Text;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A Clojure file's Structure comes from its top-level definition forms: the grammar names none of them, so the
 * TextMate path showed nothing at all. A function shows its parameter vector instead of {@code ()}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureClojureFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void topLevelFormsAreTheOutline() throws Exception {
        String rendered = FxTestSupport.callOnFx(() -> {
            StructurePanel p = new StructurePanel();
            new Scene(new StackPane(p), 400, 400);
            EditorBuffer b = new EditorBuffer();
            b.setDisplayName("core.clj");
            b.setContent(
                    "(ns demo.core)\n\n(def limit 3)\n\n(defn greet\n  \"Says hi.\"\n  [name]\n  (str \"hi \" name))\n\n"
                            + "(defprotocol Shape (area [this]))\n(defn helper [a {:keys [b]}] b)\n");
            p.attach(b);
            @SuppressWarnings("unchecked")
            TreeView<Object> tree = (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
            List<String> rows = new ArrayList<>();
            for (var item : tree.getRoot().getChildren()) {
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
                rows.add(sb.toString());
            }
            return String.join("|", rows);
        });
        assertEquals("demo.core|limit|greet [name]|Shape|helper [a {:keys [b]}]", rendered);
    }

    @Test
    void theParameterVectorIsFoundPastADocstringAndAnAttributeMap() {
        String text = "(defn f\n  \"Doc with ] and [.\"\n  {:added \"1.0\"}\n  [x\n   y]\n  x)";
        assertEquals("[x y]", StructureOutline.clojureParams(text, "(defn f".length()));
        assertEquals("", StructureOutline.clojureParams("(defn g\n  ([x] x)\n  ([x y] y))", "(defn g".length()));
    }
}
