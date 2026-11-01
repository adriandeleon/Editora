package com.editora.editor.completion;

import java.util.ArrayList;
import java.util.List;

import javafx.geometry.BoundingBox;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.editor.EditorFx;
import com.editora.editor.markdown.MarkdownRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The documentation panel beside the completion list is sized to its content at its own width, up to a maximum:
 * beside a two-item list it showed two or three lines and cut the next one in half (HTML review 2 C112).
 */
@Tag("fx")
class CompletionDocPopupFxTest {

    private final List<Stage> stages = new ArrayList<>();
    private final List<CompletionDocPopup> popups = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @AfterEach
    void close() throws Exception {
        EditorFx.onFx(() -> {
            popups.forEach(CompletionDocPopup::hide);
            stages.forEach(Stage::close);
        });
    }

    private static Node doc(String markdown) {
        return MarkdownRenderer.renderPopup(
                org.commonmark.parser.Parser.builder().build().parse(markdown), null);
    }

    /** The panel's card height, and the height its content needs at the panel's width. */
    private double[] showAndMeasure(String signature, String markdown) throws Exception {
        return EditorFx.callFx(() -> {
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(), 800, 600));
            stage.show();
            stages.add(stage);
            CompletionDocPopup popup = new CompletionDocPopup();
            popups.add(popup);
            // a two-item list, 48 px tall
            popup.show(stage, new BoundingBox(stage.getX() + 20, stage.getY() + 40, 360, 48), signature, doc(markdown));
            StackPane card = EditorFx.field(popup, "card");
            card.applyCss();
            card.layout();
            javafx.scene.control.ScrollPane scroll = EditorFx.field(popup, "scroll");
            Node content = scroll.getContent();
            return new double[] {
                card.getHeight(), content.prefHeight(scroll.getViewportBounds().getWidth())
            };
        });
    }

    @Test
    void aShortListGetsADocPanelTallEnoughForItsText() throws Exception {
        String text = "The dialog element represents a part of an application that a user interacts with to perform a"
                + " task, for example a dialog box, inspector, or window.\n\nWidely available across major browsers"
                + " (Baseline since 2022).\n\n[MDN Reference](https://developer.mozilla.org/docs/Web/HTML/Element/dialog)";
        double[] m = showAndMeasure("", text);
        assertTrue(m[0] >= m[1], "the whole text fits: card " + m[0] + " for content " + m[1]);
        assertTrue(m[0] <= CompletionDocPopup.MAX_HEIGHT + 0.5, "capped: " + m[0]);
    }

    @Test
    void aLongDocIsCappedAndAShortOneStaysSmall() throws Exception {
        double[] longDoc = showAndMeasure("void f()", "Lorem ipsum dolor sit amet. ".repeat(200));
        assertEquals(CompletionDocPopup.MAX_HEIGHT, longDoc[0], 0.5, "the cap");
        double[] shortDoc = showAndMeasure("int size()", "");
        assertTrue(shortDoc[0] < 80, "one line stays small: " + shortDoc[0]);
    }
}
