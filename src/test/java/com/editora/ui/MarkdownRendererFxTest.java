package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Label;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;

import com.editora.editor.MarkdownRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FX-thread coverage of {@link MarkdownRenderer}'s fenced-code-block syntax highlighting: a block with a
 * known language renders as a {@code TextFlow} of tokenized {@code Text} runs, while an unknown-language
 * (or plain) block falls back to a plain {@code Label}.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MarkdownRendererFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private Node render(String md) throws Exception {
        return FxTestSupport.callOnFx(
                () -> MarkdownRenderer.renderDocument(MarkdownRenderer.parseToDocument(md), null));
    }

    private static <T> List<T> collect(Node root, Class<T> type) {
        List<T> out = new ArrayList<>();
        walk(root, type, out);
        return out;
    }

    private static <T> void walk(Node node, Class<T> type, List<T> out) {
        if (type.isInstance(node)) {
            out.add(type.cast(node));
        }
        if (node instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                walk(c, type, out);
            }
        }
    }

    @Test
    void fencedBlockWithLanguageRendersAsHighlightableTextFlow() throws Exception {
        // A known language renders as a TextFlow (the tokenized runs fill in off-thread; the token-class
        // assertion lives in the pure MarkdownRendererTest, which isn't subject to async timing).
        Node root = render("```java\npublic class X { void main() {} }\n```\n");
        boolean flow = collect(root, TextFlow.class).stream()
                .anyMatch(f -> f.getStyleClass().contains("md-code-block"));
        boolean label = collect(root, Label.class).stream()
                .anyMatch(l -> l.getStyleClass().contains("md-code-block"));
        assertTrue(flow, "known language ⇒ a TextFlow code block");
        assertFalse(label, "known language ⇒ not the plain Label code block");
    }

    @Test
    void fencedBlockWithoutLanguageStaysPlain() throws Exception {
        Node root = render("```\njust plain text\n```\n");
        boolean plainLabel = collect(root, Label.class).stream()
                .anyMatch(l -> l.getStyleClass().contains("md-code-block"));
        boolean highlightedFlow = collect(root, TextFlow.class).stream()
                .anyMatch(f -> f.getStyleClass().contains("md-code-block"));
        assertTrue(plainLabel, "no language ⇒ plain Label code block");
        assertFalse(highlightedFlow, "no language ⇒ not a highlighted TextFlow");
    }

    @Test
    void linkGetsClickHandlerAndHandCursorWhenWired() throws Exception {
        List<String> clicked = new ArrayList<>();
        Node root = FxTestSupport.callOnFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseToDocument("[example](https://example.com)"), null, clicked::add));
        Text link = collect(root, Text.class).stream()
                .filter(t -> t.getStyleClass().contains("md-link"))
                .findFirst()
                .orElseThrow();
        assertEquals(Cursor.HAND, link.getCursor(), "a clickable link shows the hand cursor");
        link.getOnMouseClicked().handle(null); // the handler ignores its event arg
        assertEquals(List.of("https://example.com"), clicked, "click fires with the link's raw destination");
    }

    @Test
    void linkHasNoClickHandlerWithoutOneWired() throws Exception {
        // Non-interactive renders (print/PDF/popups) pass no handler — links must stay inert.
        Node root = render("[example](https://example.com)");
        Text link = collect(root, Text.class).stream()
                .filter(t -> t.getStyleClass().contains("md-link"))
                .findFirst()
                .orElseThrow();
        assertNull(link.getOnMouseClicked(), "no handler wired ⇒ link isn't clickable");
    }
}
