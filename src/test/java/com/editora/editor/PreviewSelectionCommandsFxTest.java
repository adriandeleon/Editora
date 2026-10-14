package com.editora.editor;

import java.util.List;

import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.PickResult;
import javafx.scene.text.Text;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The in-tab preview's selection through the buffer: Preview: Copy, the editor's Copy / Select All when the
 *  preview is where the user is, and the context menu. */
@Tag("fx")
class PreviewSelectionCommandsFxTest {

    private static final String DOC = "# Title\n\nFirst **bold** paragraph.\n\nSecond paragraph.\n";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private EditorBuffer buffer;
    private Stage stage;

    private MarkdownPreview open(MarkdownViewMode mode) throws Exception {
        EditorFx.onFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("markdown");
            buffer.getArea().replaceText(DOC);
            stage = EditorFx.show(buffer, 1000, 500);
            buffer.setMarkdownViewMode(mode);
        });
        long end = System.nanoTime() + 20_000_000_000L;
        while (!EditorFx.callFx(() -> {
            MarkdownPreview p = EditorFx.field(buffer, "markdownPreview");
            ScrollPane pane = EditorFx.field(buffer, "previewPane");
            return p != null
                    && p.isShowing()
                    && p.column().getChildrenUnmodifiable().size() == 3
                    && pane != null
                    && pane.getScene() != null
                    && pane.getScene().getWindow() != null
                    && pane.getScene().getWindow().isShowing();
        })) {
            assertTrue(System.nanoTime() < end, "the preview never rendered");
            Thread.sleep(20);
        }
        EditorFx.drain();
        return EditorFx.callFx(() -> EditorFx.<MarkdownPreview>field(buffer, "markdownPreview"));
    }

    /** Waits (off the FX thread) until {@code done}, checked on it, holds. */
    private static void await(java.util.concurrent.Callable<Boolean> done, String otherwise) throws Exception {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!EditorFx.callFx(done)) {
            assertTrue(System.nanoTime() < end, otherwise);
            Thread.sleep(20);
        }
        EditorFx.drain();
    }

    private void close() throws Exception {
        EditorFx.onFx(() -> {
            stage.close();
            buffer.dispose();
        });
    }

    private static PreviewSelection.Pos at(MarkdownPreview preview, int block, int offset) {
        return new PreviewSelection.Pos(
                PreviewSelection.leaves(
                                preview.column().getChildrenUnmodifiable().get(block))
                        .get(0),
                offset);
    }

    @Test
    void previewCopyCopiesTheSelectionWhenThereIsOneAndTheDocumentOtherwise() throws Exception {
        MarkdownPreview preview = open(MarkdownViewMode.SPLIT);
        EditorFx.onFx(() -> {
            buffer.copyPreviewToClipboard();
            assertEquals(
                    MarkdownRenderer.plainText(DOC),
                    Clipboard.getSystemClipboard().getString());

            preview.selection().select(at(preview, 1, 6), at(preview, 1, 10));
            buffer.copyPreviewToClipboard();
            assertEquals("bold", Clipboard.getSystemClipboard().getString());
            assertEquals(
                    "<p><strong>bold</strong></p>\n",
                    Clipboard.getSystemClipboard().getHtml());

            preview.selection().clear();
            buffer.copyPreviewToClipboard();
            assertEquals(
                    MarkdownRenderer.plainText(DOC),
                    Clipboard.getSystemClipboard().getString());
        });
        close();
    }

    @Test
    void theEditorsCopyAndSelectAllAreThePreviewsOnlyWhereThePreviewIsWhereTheUserIs() throws Exception {
        MarkdownPreview preview = open(MarkdownViewMode.SPLIT);
        EditorFx.onFx(() -> {
            // Split, the editor has the focus: the commands are the editor's.
            buffer.getArea().requestFocus();
            preview.selection().select(at(preview, 1, 0), at(preview, 1, 5));
            assertFalse(buffer.previewEditCommand(false), "Copy stays the editor's while the editor is focused");
            assertFalse(buffer.previewEditCommand(true));

            // The preview pane has the focus: they are the preview's.
            ScrollPane pane = EditorFx.field(buffer, "previewPane");
            stage.requestFocus(); // (another test's window may have been the focused one)
            pane.requestFocus();
        });
        await(
                () -> EditorFx.<ScrollPane>field(buffer, "previewPane").isFocused(),
                "the preview pane never got the focus");
        EditorFx.onFx(() -> {
            EditorFx.clipboard("before");
            assertTrue(buffer.previewEditCommand(false));
            assertEquals("First", Clipboard.getSystemClipboard().getString());
            assertTrue(buffer.previewEditCommand(true));
            assertTrue(preview.selection().isAll());

            // Nothing selected there: Copy is not the preview's (the editor's rule for an empty selection applies).
            preview.selection().clear();
            assertFalse(buffer.previewEditCommand(false));
        });
        close();

        MarkdownPreview only = open(MarkdownViewMode.PREVIEW);
        EditorFx.onFx(() -> {
            assertTrue(buffer.isPreviewOnly());
            assertTrue(buffer.previewEditCommand(true), "only the preview is showing: Select All selects it");
            assertTrue(only.selection().isAll());
            assertTrue(buffer.previewEditCommand(false));
            assertEquals(
                    MarkdownRenderer.plainText(DOC),
                    Clipboard.getSystemClipboard().getString());
        });
        close();
    }

    @Test
    void theContextMenuOffersCopyOfTheSelectionSelectAllAndTheWayToTheSource() throws Exception {
        MarkdownPreview preview = open(MarkdownViewMode.SPLIT);
        EditorFx.onFx(() -> {
            ScrollPane pane = EditorFx.field(buffer, "previewPane");
            Text second = (Text) preview.column().lookupAll(".md-text").stream()
                    .filter(n -> "Second paragraph.".equals(((Text) n).getText()))
                    .findFirst()
                    .orElseThrow();
            javafx.geometry.Bounds b = second.localToScene(second.getBoundsInLocal());
            Runnable ask = () -> pane.fireEvent(new ContextMenuEvent(
                    ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                    b.getMinX() + 2,
                    b.getMinY() + 2,
                    200,
                    200,
                    false,
                    new PickResult(second, b.getMinX() + 2, b.getMinY() + 2)));
            ask.run();
            ContextMenu menu = EditorFx.field(buffer, "previewContextMenu");
            List<String> titles = EditorFx.titles(menu.getItems());
            assertTrue(
                    titles.containsAll(List.of(
                            tr("previewmenu.copy"),
                            tr("previewmenu.copyAll"),
                            tr("previewmenu.selectAll"),
                            tr("previewmenu.goToSource"))),
                    titles.toString());
            MenuItem copy = EditorFx.menuItem(menu.getItems(), tr("previewmenu.copy"));
            assertFalse(copy.isVisible(), "nothing selected: only Copy All is offered");

            EditorFx.menuItem(menu.getItems(), tr("previewmenu.selectAll")).fire();
            assertTrue(preview.selection().isAll());
            menu.hide();
            ask.run();
            assertTrue(copy.isVisible());
            EditorFx.clipboard("before");
            copy.fire();
            assertEquals(
                    MarkdownRenderer.plainText(DOC),
                    Clipboard.getSystemClipboard().getString());

            MenuItem toSource = EditorFx.menuItem(menu.getItems(), tr("previewmenu.goToSource"));
            assertTrue(toSource.isVisible());
            buffer.getArea().moveTo(0);
            toSource.fire(); // the caret moves once the menu's event is done
            menu.hide();
        });
        EditorFx.drain();
        EditorFx.onFx(() ->
                assertEquals(4, buffer.getArea().getCurrentParagraph(), "the caret is on the block's source line"));
        close();
    }
}
