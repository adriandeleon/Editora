package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.github.PrViewParser.PrDetail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Markdown rendered outside the preview — an AI answer, a pull request's description — goes through the same
 * renderer, and its links have to do something: they were drawn as links and wired to nothing.
 */
@Tag("fx")
class RenderedMarkdownLinksFxTest {

    private static final String MD = "See [the docs](https://example.com/docs) for details.\n";

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    /** The first rendered piece of a link (style class {@code md-link}) under {@code root}. */
    private static Node link(Node root) {
        if (root.getStyleClass().contains("md-link")) {
            return root;
        }
        if (root instanceof javafx.scene.control.ScrollPane scroll) {
            return scroll.getContent() == null ? null : link(scroll.getContent());
        }
        if (root instanceof Parent p) {
            for (Node c : p.getChildrenUnmodifiable()) {
                Node found = link(c);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static void click(Node node) {
        node.getOnMouseClicked()
                .handle(new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        0,
                        0,
                        0,
                        0,
                        MouseButton.PRIMARY,
                        1,
                        false,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        true,
                        false,
                        false,
                        null));
    }

    @Test
    void aLinkInAPullRequestDescriptionOpensThroughThePanesOpener() throws Exception {
        List<String> urls = FxTestSupport.callOnFx(() -> {
            List<String> opened = new ArrayList<>();
            PrReviewPane pane = new PrReviewPane(7, f -> {}, () -> {}, opened::add, () -> {}, () -> {});
            pane.update(
                    new PrDetail(7, "Title", MD, "alice", "main", "fix", "OPEN", "https://example.invalid/pr/7", 1, 1),
                    List.of(),
                    "");
            click(link(pane));
            return opened;
        });
        assertEquals(List.of("https://example.com/docs"), urls);
    }

    @Test
    void aLinkInAnAiAnswerOpensThroughThePanelsOpener() throws Exception {
        List<String> urls = FxTestSupport.callOnFx(() -> {
            List<String> opened = new ArrayList<>();
            AgentPanel panel = new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {});
            panel.setOnOpenLink(opened::add);
            panel.appendChunk(MD);
            panel.appendLine("next entry"); // finalizes the message: one whole render
            click(link(panel));
            return opened;
        });
        assertEquals(List.of("https://example.com/docs"), urls);
    }

    @Test
    void withoutAnOpenerALinkIsInertAsBefore() throws Exception {
        Object handler = FxTestSupport.callOnFx(() -> {
            AgentPanel panel = new AgentPanel(() -> {}, () -> {}, () -> {}, () -> {}, () -> {}, () -> {}, p -> {});
            panel.appendChunk(MD);
            panel.appendLine("next entry");
            return link(panel).getOnMouseClicked();
        });
        assertNull(handler);
    }
}
