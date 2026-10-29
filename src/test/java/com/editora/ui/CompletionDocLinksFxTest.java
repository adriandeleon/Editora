package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;

import com.editora.editor.EditorBuffer;
import com.editora.editor.markdown.MarkdownRenderer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A link in a completion's documentation opens on a click through the buffer's (that is, the window's) link
 *  opener, and is no Tab stop. */
@Tag("fx")
class CompletionDocLinksFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Node firstLink(Node node) {
        if (node.getOnMouseClicked() != null
                && node.hasProperties()
                && node.getProperties().containsKey(MarkdownRenderer.LINK_KEY)) {
            return node;
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                Node found = firstLink(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    void aDocumentationLinkIsFollowedByTheOpenerTheWindowGaveTheBuffer() throws Exception {
        List<String> opened = new ArrayList<>();
        EditorBuffer buffer = FxTestSupport.callOnFx(EditorBuffer::new);
        Object completion = FxTestSupport.field(buffer, "completionActions");
        String doc =
                "Returns the size. See [the docs](https://example.org/api#size) and ![x](https://example.org/p.png).";

        // Before the window wires the buffer: rendered, and not followed.
        Node inert = FxTestSupport.callOnFx(
                () -> (Node) FxTestSupport.call(completion, "renderDoc", new Class<?>[] {String.class}, doc));
        assertNotNull(inert);

        FxTestSupport.runOnFx(() -> buffer.setOpenUrlHandler(opened::add));
        Node rendered = FxTestSupport.callOnFx(
                () -> (Node) FxTestSupport.call(completion, "renderDoc", new Class<?>[] {String.class}, doc));
        Node link = FxTestSupport.callOnFx(() -> firstLink(rendered));
        assertNotNull(link, "the link follows a click");
        assertFalse(
                FxTestSupport.callOnFx(link::isFocusTraversable),
                "no Tab stop: the popup would take the Enter and Tab that accept the item (HTML review B1)");
        assertNull(FxTestSupport.callOnFx(link::getOnKeyPressed));
        FxTestSupport.runOnFx(() -> link.getOnMouseClicked()
                .handle(new MouseEvent(
                        MouseEvent.MOUSE_CLICKED,
                        1,
                        1,
                        1,
                        1,
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
                        true,
                        null)));
        assertEquals(List.of("https://example.org/api#size"), opened, "the destination as written");

        assertNull(FxTestSupport.callOnFx(
                () -> FxTestSupport.call(completion, "renderDoc", new Class<?>[] {String.class}, "  ")));
    }
}
