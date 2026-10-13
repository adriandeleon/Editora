package com.editora.editor;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.stage.Stage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What the reader sees and chooses in the live preview: front matter as a table (or hidden), a code block's
 * language, the column width and font size, a picture enlarged on a click, and scroll sync switched off.
 */
@Tag("fx")
class PreviewReadingOptionsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    @AfterEach
    void reset() throws Exception {
        EditorFx.onFx(() -> {
            PreviewPrefs.set(0, 0, false);
            PreviewScrollSync.setEnabled(true);
        });
    }

    private Stage stage;
    private EditorBuffer buffer;
    private MarkdownPreviewPane pane;

    private void open(String text, Path file) throws Exception {
        EditorFx.onFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("markdown");
            buffer.getArea().replaceText(text);
            if (file != null) {
                buffer.setPath(file);
            }
            pane = new MarkdownPreviewPane((b, l) -> {});
            stage = new Stage();
            stage.setScene(new Scene(new HBox(buffer.getNode(), pane.node()), 1000, 500));
            stage.show();
        });
        render(() -> pane.setSource(buffer));
    }

    private void render(Runnable action) throws Exception {
        EditorFx.awaitChange(() -> pane.preview().showsProperty(), action);
    }

    private void close() throws Exception {
        EditorFx.onFx(() -> {
            pane.dispose();
            stage.close();
            buffer.dispose();
        });
    }

    /** A still click (pressed and released in place) of {@code button}. */
    private static javafx.scene.input.MouseEvent click(MouseButton button) {
        return new javafx.scene.input.MouseEvent(
                javafx.scene.input.MouseEvent.MOUSE_CLICKED,
                5,
                5,
                5,
                5,
                button,
                1,
                false,
                false,
                false,
                false,
                button == MouseButton.PRIMARY,
                false,
                button == MouseButton.SECONDARY,
                false,
                false,
                true,
                null);
    }

    /** Waits (off the FX thread) until {@code done}, checked on it, holds. */
    private static void poll(java.util.concurrent.Callable<Boolean> done) throws Exception {
        long end = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while (!EditorFx.callFx(done)) {
            if (System.nanoTime() > end) {
                throw new IllegalStateException("the awaited state was never reached");
            }
            Thread.sleep(20);
        }
        EditorFx.drain();
    }

    private String shown() {
        return EditorFx.textOf(pane.preview().column());
    }

    @Test
    void frontMatterIsAKeyValueTableAndCanBeHidden() throws Exception {
        open("---\ntitle: Notes\ntags:\n  - one\n  - two\n---\n\n# Heading\n\nBody.\n", null);
        EditorFx.onFx(() -> {
            GridPane table = (GridPane) EditorFx.styled(pane.node(), "md-frontmatter");
            assertNotNull(table, "front matter is shown as a table");
            assertEquals(4, table.getChildren().size(), "two rows of key and value");
            assertEquals(
                    "title", ((javafx.scene.control.Label) table.getChildren().get(0)).getText());
            assertEquals(
                    "Notes", ((javafx.scene.control.Label) table.getChildren().get(1)).getText());
            assertEquals(
                    "tags", ((javafx.scene.control.Label) table.getChildren().get(2)).getText());
            assertEquals(
                    "one\ntwo",
                    ((javafx.scene.control.Label) table.getChildren().get(3)).getText());
            assertEquals(0, GridPane.getColumnIndex(table.getChildren().get(2)));
            assertEquals(1, GridPane.getRowIndex(table.getChildren().get(3)));
            PreviewPrefs.set(0, 0, true);
        });
        render(pane::render);
        EditorFx.onFx(() -> {
            assertNull(EditorFx.styled(pane.node(), "md-frontmatter"), "hidden when the reader says so");
            assertFalse(shown().contains("Notes"));
            assertTrue(shown().contains("Heading"), "the document itself is all there");
            assertTrue(shown().contains("Body."));
            assertEquals(pane.preview().yOfLine(7), pane.preview().yOfLine(0), 0.5, "its lines map to the top");
            PreviewPrefs.set(0, 0, false);
        });
        render(pane::render);
        EditorFx.onFx(() -> assertNotNull(EditorFx.styled(pane.node(), "md-frontmatter")));
        close();
    }

    @Test
    void aFencedBlockNamesItsLanguage() throws Exception {
        open(
                "```java\nint x = 1;\n```\n\n```\nno language\n```\n\n    indented\n\n```{.python}\nprint(1)\n```\n\n"
                        + "```js title=\"a.js\"\nlet a;\n```\n",
                null);
        EditorFx.onFx(() -> {
            java.util.List<String> labels = pane.preview().column().lookupAll(".md-code-lang").stream()
                    .map(n -> ((javafx.scene.control.Label) n).getText())
                    .toList();
            assertEquals(java.util.List.of("java", "python", "js"), labels);
            Node wrap = EditorFx.styled(pane.node(), "md-code-wrap");
            assertEquals("java", wrap.getProperties().get(MarkdownRenderer.LANGUAGE_KEY));
            assertEquals(
                    "int x = 1;",
                    wrap.getProperties().get(MarkdownRenderer.CODE_KEY),
                    "Copy Code Block still finds the code");
            assertTrue(
                    EditorFx.styled(wrap, "md-code-lang").isMouseTransparent(), "the code under it stays selectable");
        });
        close();
    }

    @Test
    void theColumnWidthAndTheFontSizeAreTheReadersChoice() throws Exception {
        open("# Heading\n\nBody.\n", null);
        EditorFx.onFx(() -> {
            Region column = (Region) pane.preview().column();
            assertEquals(PreviewPrefs.BASE_WIDTH, column.getMaxWidth());
            assertEquals("-fx-font-size: 15.0px;", pane.preview().root().getStyle());
            PreviewPrefs.set(19, 520, false);
        });
        render(pane::render);
        EditorFx.onFx(() -> {
            Region column = (Region) pane.preview().column();
            assertEquals(520, column.getMaxWidth());
            assertEquals("-fx-font-size: 19.0px;", pane.preview().root().getStyle());
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
            assertTrue(column.getWidth() <= 520.5, "the column is no wider than asked: " + column.getWidth());
        });
        close();
    }

    @Test
    void theInTabPreviewTakesTheFontSizeToo() throws Exception {
        Stage[] ref = new Stage[1];
        EditorBuffer[] b = new EditorBuffer[1];
        javafx.scene.control.ScrollPane[] preview = new javafx.scene.control.ScrollPane[1];
        EditorFx.onFx(() -> {
            b[0] = new EditorBuffer();
            b[0].setLanguageOverride("markdown");
            b[0].getArea().replaceText("# Heading\n\nBody.\n");
            ref[0] = EditorFx.show(b[0], 900, 400);
            PreviewPrefs.set(21, 0, false);
            b[0].setMarkdownViewMode(EditorBuffer.MarkdownViewMode.SPLIT);
            preview[0] = EditorFx.field(b[0], "previewPane");
        });
        poll(() -> preview[0].getContent() != null
                && "-fx-font-size: 21.0px;".equals(preview[0].getContent().getStyle()));
        EditorFx.onFx(() -> {
            PreviewPrefs.set(12, 0, false);
            b[0].refreshPreview(); // what a change of the setting does
        });
        poll(() -> "-fx-font-size: 12.0px;".equals(preview[0].getContent().getStyle()));
        EditorFx.onFx(() -> {
            b[0].zoomPreviewIn();
            String zoomed = preview[0].getContent().getStyle();
            assertFalse(zoomed.equals("-fx-font-size: 12.0px;"), "zoom multiplies the choice: " + zoomed);
            assertTrue(zoomed.startsWith("-fx-font-size: 1"), zoomed);
            ref[0].close();
            b[0].dispose();
        });
    }

    @Test
    void aClickOnAPictureOpensItEnlargedAndEscClosesIt(@TempDir Path dir) throws Exception {
        BufferedImage img = new BufferedImage(40, 20, BufferedImage.TYPE_INT_RGB);
        javax.imageio.ImageIO.write(img, "png", dir.resolve("pic.png").toFile());
        Path file = dir.resolve("doc.md");
        Files.writeString(file, "x");
        open("Text.\n\n![a picture](pic.png)\n\n[![linked](pic.png)](https://example.com)\n", file);
        poll(() -> pane.preview().column().lookupAll(".md-image").size() == 2
                && pane.preview().column().lookupAll(".md-image").stream()
                        .allMatch(n -> ((ImageView) n).getImage() != null));
        EditorFx.onFx(() -> {
            java.util.List<Node> slots =
                    java.util.List.copyOf(pane.preview().column().lookupAll(".md-image-slot"));
            assertEquals(2, slots.size());
            Node plain = slots.get(0);
            assertTrue(plain.getProperties().containsKey(MarkdownRenderer.ENLARGE_KEY));
            assertNull(pane.preview().lightbox());
            plain.getOnMouseClicked().handle(click(MouseButton.SECONDARY));
            assertNull(pane.preview().lightbox(), "the context menu's button does not enlarge");
            plain.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
            PreviewLightbox box = pane.preview().lightbox();
            assertNotNull(box, "a click opens the picture enlarged");
            assertEquals(40 * PreviewLightbox.MAX_MAGNIFICATION, box.view().getFitWidth(), 1e-6);
            assertTrue(box.popup().isShowing());
            Node backdrop = box.popup().getContent().get(0);
            javafx.event.Event.fireEvent(
                    backdrop, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.ESCAPE, false, false, false, false));
            assertFalse(box.isShowing(), "Esc closes it");
            assertNull(pane.preview().lightbox());

            // A picture that is a link keeps the link's click.
            Node linked = slots.get(1);
            assertTrue(linked.getProperties().containsKey(MarkdownRenderer.LINK_KEY));
            linked.getOnMouseClicked().handle(click(MouseButton.PRIMARY));
            assertNull(pane.preview().lightbox(), "a linked picture follows its link instead");
        });
        close();
    }

    @Test
    void scrollSyncOffLeavesThePreviewWhereItIs() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            text.append("Paragraph ").append(i).append(".\n\n");
        }
        open(text.toString(), null);
        EditorFx.onFx(() -> {
            PreviewScrollSync.setEnabled(false);
            buffer.getArea().showParagraphAtTop(300);
        });
        EditorFx.drain();
        EditorFx.drain();
        EditorFx.onFx(() -> assertEquals(0, pane.preview().scrollY(), 0.5, "switched off: the preview stays"));
        EditorFx.onFx(() -> PreviewScrollSync.setEnabled(true));
        // Scroll again until the preview follows: on a loaded machine the first scroll can land before the
        // area has laid out, and then no scroll event reaches the sync at all.
        int[] target = {200};
        poll(() -> {
            buffer.getArea().showParagraphAtTop(target[0]);
            target[0] = target[0] == 200 ? 160 : 200;
            return pane.preview().scrollY() > 0;
        });
        close();
    }
}
