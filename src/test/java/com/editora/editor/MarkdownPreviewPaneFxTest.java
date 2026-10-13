package com.editora.editor;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A Markdown preview in a tab of its own: what it shows, when it changes source, and what its blocks do. */
@Tag("fx")
class MarkdownPreviewPaneFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer markdown(String text) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("markdown");
        buffer.getArea().replaceText(text);
        return buffer;
    }

    private static String shown(MarkdownPreviewPane pane) {
        return EditorFx.textOf(pane.preview().column());
    }

    /** Runs {@code action} and waits for the render it causes to be shown. */
    private static void awaitRender(MarkdownPreviewPane pane, Runnable action) throws Exception {
        EditorFx.awaitChange(() -> pane.preview().showsProperty(), action);
    }

    @Test
    void itShowsItsSourceFollowsTheActiveMarkdownBufferAndStaysWhenLocked() throws Exception {
        EditorBuffer[] buffers = new EditorBuffer[3];
        MarkdownPreviewPane[] ref = new MarkdownPreviewPane[1];
        Stage[] stage = new Stage[1];
        List<String> revealed = new ArrayList<>();
        EditorFx.onFx(() -> {
            buffers[0] = markdown("# First\n\n- [ ] a task\n\nBody of the first.\n");
            buffers[1] = markdown("# Second\n\nBody of the second.\n");
            buffers[2] = new EditorBuffer();
            buffers[2].getArea().replaceText("plain text, not Markdown");
            ref[0] = new MarkdownPreviewPane((buffer, line) ->
                    revealed.add(buffer.getArea().getParagraph(line).getText()));
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new HBox(buffers[0].getNode(), ref[0].node()), 900, 400));
            stage[0].show();
        });
        MarkdownPreviewPane pane = ref[0];
        assertEquals(tr("markdown.sidePreview.title"), pane.title());
        EditorFx.onFx(() -> assertNull(pane.source(), "nothing to show until it is given a file"));

        awaitRender(pane, () -> pane.setSource(buffers[0]));
        EditorFx.onFx(() -> {
            assertSame(buffers[0], pane.source());
            assertTrue(shown(pane).contains("Body of the first."));
            assertTrue(EditorFx.textOf(pane.node()).contains(buffers[0].getTitle()), "the header names the file");
        });

        // An edit of the source is rendered after the pause, and only the changed block is rebuilt.
        long built = EditorFx.callFx(() -> pane.preview().builtTotal());
        awaitRender(pane, () -> buffers[0].getArea().appendText("\nAdded later.\n"));
        EditorFx.onFx(() -> {
            assertTrue(shown(pane).contains("Added later."));
            assertEquals(built + 1, pane.preview().builtTotal());
        });

        // Its task box ticks the source; a double-clicked block is revealed there.
        EditorFx.onFx(() -> {
            CheckBox box = (CheckBox) EditorFx.styled(pane.node(), "md-task");
            box.fire();
            assertEquals("- [x] a task", buffers[0].getArea().getParagraph(2).getText());
        });

        // Unlocked, it follows the Markdown buffer that becomes active; other tabs leave it alone.
        awaitRender(pane, () -> pane.follow(buffers[1]));
        EditorFx.onFx(() -> {
            assertSame(buffers[1], pane.source());
            assertTrue(shown(pane).contains("Body of the second."));
            assertFalse(shown(pane).contains("Body of the first."));
            pane.follow(buffers[2]);
            pane.follow(null);
            assertSame(buffers[1], pane.source(), "a plain-text tab or a non-editor tab is not a new source");

            // Locked, it stays on its file.
            pane.setLocked(true);
            assertTrue(pane.isLocked());
            pane.follow(buffers[0]);
            assertSame(buffers[1], pane.source());
            assertTrue(EditorFx.textOf(pane.node()).contains(tr("markdown.sidePreview.locked")));
        });

        // A source whose tab is closed is let go, lock or not; unlocked again, the next file is taken up.
        EditorFx.onFx(() -> {
            buffers[1].dispose();
            pane.follow(null);
            assertNull(pane.source());
            assertTrue(EditorFx.textOf(pane.node()).contains(tr("markdown.sidePreview.noSource")));
            pane.setLocked(false);
        });
        awaitRender(pane, () -> pane.follow(buffers[0]));
        EditorFx.onFx(() -> {
            assertSame(buffers[0], pane.source());
            pane.dispose();
            assertNull(pane.source());
            stage[0].close();
            buffers[0].dispose();
            buffers[2].dispose();
        });
    }

    @Test
    void anEmptyDocumentSaysSoInsteadOfShowingABlankPane() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        MarkdownPreviewPane[] paneRef = new MarkdownPreviewPane[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown("<!-- only a comment -->\n");
            paneRef[0] = new MarkdownPreviewPane((b, l) -> {});
            stage[0] = new Stage();
            stage[0].setScene(new Scene(new HBox(ref[0].getNode(), paneRef[0].node()), 700, 300));
            stage[0].show();
        });
        MarkdownPreviewPane pane = paneRef[0];
        awaitRender(pane, () -> pane.setSource(ref[0]));
        EditorFx.onFx(() -> {
            Label note = (Label) EditorFx.styled(
                    ((ScrollPane) EditorFx.styled(pane.node(), "markdown-preview-pane")).getContent(),
                    "md-preview-empty");
            assertTrue(note.isVisible(), "nothing rendered: say so");
            assertEquals(tr("markdown.preview.empty"), note.getText());
        });
        awaitRender(pane, () -> ref[0].getArea().appendText("\nNow there is text.\n"));
        EditorFx.onFx(() -> {
            Label note = (Label) EditorFx.styled(
                    ((ScrollPane) EditorFx.styled(pane.node(), "markdown-preview-pane")).getContent(),
                    "md-preview-empty");
            assertFalse(note.isVisible());
            pane.dispose();
            stage[0].close();
            ref[0].dispose();
        });
    }
}
