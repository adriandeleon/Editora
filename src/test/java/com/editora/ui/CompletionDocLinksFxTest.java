package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** A link in a completion's documentation opens through the buffer's (that is, the window's) link opener. */
@Tag("fx")
class CompletionDocLinksFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static Node firstLink(Node node) {
        if (node.isFocusTraversable() && node.getOnKeyPressed() != null) {
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
        assertNotNull(link, "the link is a Tab stop with a key handler");
        FxTestSupport.runOnFx(() -> link.getOnKeyPressed()
                .handle(new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ENTER, false, false, false, false)));
        assertEquals(List.of("https://example.org/api#size"), opened, "the destination as written");

        assertNull(FxTestSupport.callOnFx(
                () -> FxTestSupport.call(completion, "renderDoc", new Class<?>[] {String.class}, "  ")));
    }
}
