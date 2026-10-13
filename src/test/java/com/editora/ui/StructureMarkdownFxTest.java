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
import com.editora.i18n.Messages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Structure panel's outline of a Markdown file: a heading is listed by the text a reader sees (not its
 * source with the {@code **}, backticks and link targets in it), a setext heading by its whole paragraph and
 * in one colour, and the row's "line N" in the interface language.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class StructureMarkdownFxTest {

    private static final String DOC = "# **Bold** and `code` and [link](http://x.y) title\n\n"
            + "Multi line\nsetext heading\n==============\n\n## Plain\n";

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void english() throws Exception {
        FxTestSupport.runOnFx(() -> Messages.init("en"));
    }

    @SuppressWarnings("unchecked")
    private static TreeView<Object> tree(StructurePanel p) {
        return (TreeView<Object>) FxTestSupport.<TreeView<?>>field(p, "tree");
    }

    private static StructurePanel panelFor(String markdown) {
        StructurePanel p = new StructurePanel();
        new Scene(new StackPane(p), 300, 400);
        EditorBuffer b = new EditorBuffer();
        b.setLanguageOverride("markdown");
        b.setContent(markdown);
        p.attach(b);
        return p;
    }

    /** The Text nodes of the rendered row of {@code item}. */
    private static List<Text> row(StructurePanel p, TreeItem<Object> item) {
        TreeView<Object> tree = tree(p);
        @SuppressWarnings("unchecked")
        TreeCell<Object> cell = (TreeCell<Object>) tree.getCellFactory().call(tree);
        Object value = item.getValue();
        FxTestSupport.call(cell, "updateItem", new Class<?>[] {value.getClass(), boolean.class}, value, false);
        List<Text> out = new ArrayList<>();
        for (var n : ((HBox) cell.getGraphic()).getChildren()) {
            if (n instanceof Text t) {
                out.add(t);
            }
        }
        return out;
    }

    @Test
    void headingsAreListedByTheTextAReaderSees() throws Exception {
        List<String> labels = FxTestSupport.callOnFx(() -> panelFor(DOC).outline().stream()
                .map(o -> o.label() + "@" + o.line())
                .toList());
        assertEquals(List.of("Bold and code and link title@0", "Multi line setext heading@2", "Plain@6"), labels);
    }

    @Test
    void aSetextHeadingIsOneColourAndTheRowNamesItsLineInTheInterfaceLanguage() throws Exception {
        for (String lang : List.of("en", "de")) {
            List<Text> setext = FxTestSupport.callOnFx(() -> {
                Messages.init(lang);
                StructurePanel p = panelFor(DOC);
                return row(p, tree(p).getRoot().getChildren().get(1));
            });
            List<Text> name = setext.stream()
                    .filter(t -> t.getStyleClass().contains("structure-name"))
                    .toList();
            assertEquals(1, name.size(), "one run, not a coloured first word and a plain rest: " + setext);
            assertEquals("Multi line setext heading", name.getFirst().getText());
            assertTrue(
                    name.getFirst().getStyleClass().contains("heading"),
                    name.getFirst().getStyleClass().toString());
            String line = setext.stream()
                    .filter(t -> t.getStyleClass().contains("structure-line-number"))
                    .map(Text::getText)
                    .findFirst()
                    .orElseThrow();
            assertEquals(lang.equals("de") ? "  Zeile 3" : "  line 3", line);
        }
    }
}
