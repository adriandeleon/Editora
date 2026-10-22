package com.editora.editor.markdown;

import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.CheckBox;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.text.Text;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.EditorFx;
import com.editora.editor.markdown.PreviewSelection.Pos;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Text selection in the rendered Markdown preview: the model, the mouse, the keys, copy, and what it must not
 *  disturb (block reuse, Find marks, links, task boxes, the cost of scrolling). */
@Tag("fx")
class PreviewSelectionFxTest {

    private static final String DOC = "# Heading one\n\n"
            + "First paragraph with **bold words** and a [link](https://example.com) and `code`.\n\n"
            + "- item one\n- item two\n\n"
            + "| A | B |\n|---|---|\n| a1 | b1 |\n| a2 | b2 |\n\n"
            + "```\nplain code\nline two\n```\n\n"
            + "> [!NOTE]\n> Alert body.\n\n"
            + "- [ ] a task\n\n"
            + "Last paragraph.\n";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private Stage stage;
    private EditorBuffer buffer;
    private MarkdownPreviewPane pane;
    private final List<String> opened = new ArrayList<>();

    private void open(String text) throws Exception {
        EditorFx.onFx(() -> {
            buffer = new EditorBuffer();
            buffer.setLanguageOverride("markdown");
            buffer.getArea().replaceText(text);
            buffer.setOpenUrlHandler(opened::add);
            pane = new MarkdownPreviewPane((b, l) -> opened.add("source:" + l));
            stage = new Stage();
            stage.setScene(new Scene(new HBox(buffer.getNode(), pane.node()), 1100, 600));
            stage.show();
        });
        render(() -> pane.setSource(buffer));
        settle();
    }

    private void render(Runnable action) throws Exception {
        EditorFx.awaitChange(() -> pane.preview().showsProperty(), action);
    }

    /** Lays the scene out and waits for a pulse to have drawn the highlight — twice, so that what the first
     *  pulse's layout moved is drawn too. Never reads a position before it is settled. */
    private void settle() throws Exception {
        for (int i = 0; i < 2; i++) {
            java.util.concurrent.CountDownLatch pulsed = new java.util.concurrent.CountDownLatch(1);
            EditorFx.onFx(() -> {
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                Runnable[] once = new Runnable[1];
                once[0] = () -> {
                    stage.getScene().removePostLayoutPulseListener(once[0]);
                    pulsed.countDown();
                };
                stage.getScene().addPostLayoutPulseListener(once[0]);
                javafx.application.Platform.requestNextPulse();
            });
            assertTrue(pulsed.await(20, java.util.concurrent.TimeUnit.SECONDS), "no pulse ran");
            EditorFx.drain();
        }
    }

    private void close() throws Exception {
        EditorFx.onFx(() -> {
            pane.dispose();
            stage.close();
            buffer.dispose();
        });
    }

    private PreviewSelection sel() {
        return pane.preview().selection();
    }

    /** The text run of the preview that reads {@code text}. */
    private Text run(String text) {
        for (Node n : pane.preview().column().lookupAll(".md-text")) {
            if (n instanceof Text t && text.equals(t.getText())) {
                return t;
            }
        }
        throw new AssertionError("no run reads '" + text + "'");
    }

    private static TextFlow flowOf(Node n) {
        while (!(n instanceof TextFlow)) {
            n = n.getParent();
        }
        return (TextFlow) n;
    }

    /** A position of the document by the text around it: at the start of {@code text} in its leaf, plus
     *  {@code plus} characters. */
    private Pos pos(String text, int plus) {
        for (Node block : pane.preview().column().getChildrenUnmodifiable()) {
            for (Region leaf : PreviewSelection.leaves(block)) {
                int at = PreviewSelection.textOf(leaf).indexOf(text);
                if (at >= 0) {
                    return new Pos(leaf, at + plus);
                }
            }
        }
        throw new AssertionError("no leaf says '" + text + "'");
    }

    /** The scene point where the character at {@code p} starts (the middle of its line's height). */
    private static double[] pointOf(Pos p) {
        TextFlow flow = (TextFlow) p.leaf();
        javafx.scene.shape.Path caret = new javafx.scene.shape.Path(flow.caretShape(p.offset(), true));
        javafx.geometry.Bounds b = flow.localToScene(caret.getBoundsInLocal());
        return new double[] {b.getMinX() + 1, (b.getMinY() + b.getMaxY()) / 2};
    }

    private static MouseEvent mouse(
            javafx.event.EventType<MouseEvent> type,
            Node target,
            double x,
            double y,
            int clicks,
            boolean shift,
            boolean shortcut,
            boolean still) {
        boolean mac = com.editora.command.KeymapManager.isMac();
        return new MouseEvent(
                type,
                x,
                y,
                x,
                y,
                MouseButton.PRIMARY,
                clicks,
                shift,
                shortcut && !mac,
                false,
                shortcut && mac,
                true,
                false,
                false,
                false,
                false,
                still,
                new PickResult(target, x, y));
    }

    private void press(Pos p, int clicks, boolean shift) {
        double[] at = pointOf(p);
        Node target = p.leaf();
        target.fireEvent(mouse(MouseEvent.MOUSE_PRESSED, target, at[0], at[1], clicks, shift, false, true));
    }

    private void dragTo(Pos p) {
        double[] at = pointOf(p);
        Node target = p.leaf();
        target.fireEvent(mouse(MouseEvent.MOUSE_DRAGGED, target, at[0], at[1], 1, false, false, false));
    }

    private void key(KeyCode code, boolean shift, boolean shortcut) {
        boolean mac = com.editora.command.KeymapManager.isMac();
        javafx.scene.control.ScrollPane scroll =
                (javafx.scene.control.ScrollPane) EditorFx.styled(pane.node(), "markdown-preview-pane");
        scroll.fireEvent(
                new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, shortcut && !mac, false, shortcut && mac));
    }

    @Test
    void aDragSelectsAcrossBlocksAndTheSelectedTextKeepsTheirShape() throws Exception {
        open(DOC);
        EditorFx.onFx(() -> {
            assertFalse(sel().isActive());
            press(pos("First paragraph", 6), 1, false);
            assertFalse(sel().isActive(), "a press alone selects nothing");
            dragTo(pos("item two", 4));
            assertTrue(sel().isActive());
            assertEquals(
                    "paragraph with bold words and a link and code.\n\nitem one\nitem",
                    sel().text(),
                    "a blank line between blocks, a line break between the items of one list");

            // Backwards is the same selection.
            press(pos("item two", 4), 1, false);
            dragTo(pos("First paragraph", 6));
            assertEquals("paragraph with bold words and a link and code.\n\nitem one\nitem", sel().text());

            // Through a table: a tab between the cells of a row, a line break between rows.
            sel().select(pos("A", 0), pos("b2", 2));
            assertEquals("A\tB\na1\tb1\na2\tb2", sel().text());

            // A heading, a plain code block (a label: all of it), an alert, a task item.
            sel().select(pos("Heading one", 8), pos("First paragraph", 5));
            assertEquals("one\n\nFirst", sel().text());
            sel().select(pos("b2", 0), pos("Alert body.", 5));
            assertTrue(sel().text().startsWith("b2\n\nplain code\nline two\n\n"), sel().text());
            assertTrue(sel().text().endsWith("Alert"), sel().text());
            sel().select(pos("a task", 0), pos("Last paragraph.", 4));
            assertEquals("a task\n\nLast", sel().text());
        });
        close();
    }

    @Test
    void theHighlightIsDrawnOnTheSelectedTextAndGoesWithIt() throws Exception {
        open(DOC);
        EditorFx.onFx(() -> sel().select(pos("First paragraph", 0), pos("First paragraph", 5)));
        settle();
        EditorFx.onFx(() -> {
            assertEquals(1, sel().paintedShapes());
            Node layer = EditorFx.styled(pane.node(), "md-selection-layer");
            Node shape = ((javafx.scene.Parent) layer)
                    .getChildrenUnmodifiable().stream()
                            .filter(Node::isVisible)
                            .findFirst()
                            .orElseThrow();
            javafx.geometry.Bounds drawn = shape.localToScene(shape.getBoundsInLocal());
            Text first = run("First paragraph with ");
            javafx.geometry.Bounds text = first.localToScene(first.getBoundsInLocal());
            assertEquals(text.getMinX(), drawn.getMinX(), 2.0, "it starts where the text starts");
            assertTrue(drawn.getMinY() >= text.getMinY() - 3 && drawn.getMaxY() <= text.getMaxY() + 3, "on its line");
            assertTrue(drawn.getWidth() > 10 && drawn.getWidth() < text.getWidth(), "five characters, not the run");
            assertTrue(layer.isMouseTransparent(), "the layer takes no clicks from the text under it");

            sel().select(pos("Heading one", 0), pos("Last paragraph.", 4));
        });
        settle();
        EditorFx.onFx(() -> {
            int leaves = sel().parts().size();
            assertTrue(leaves >= 12, "every kind of block is in it: " + leaves);
            assertEquals(leaves, sel().paintedShapes(), "one shape per selected leaf on screen");
            sel().clear();
        });
        settle();
        EditorFx.onFx(() -> assertEquals(0, sel().paintedShapes()));
        close();
    }

    @Test
    void aDoubleClickSelectsTheWordATripleClickTheBlockAndShiftClickExtends() throws Exception {
        open(DOC);
        EditorFx.onFx(() -> {
            press(pos("paragraph with", 3), 2, false);
            assertEquals("paragraph", sel().text());
            assertTrue(opened.isEmpty(), "a double click on text is not a jump to the source");

            press(pos("item two", 2), 3, false);
            assertEquals("item one\nitem two", sel().text(), "the whole list is the block");

            press(pos("First paragraph", 0), 1, false);
            press(pos("First paragraph", 15), 1, true);
            assertEquals("First paragraph", sel().text());
            press(pos("Heading one", 0), 1, true);
            assertEquals("Heading one", sel().text(), "Shift-click before the anchor turns it round");

            press(pos("Last paragraph.", 2), 1, false);
            assertFalse(sel().isActive(), "a plain click clears");
            assertEquals("", sel().text());
        });
        close();
    }

    @Test
    void wordBoundaries() {
        assertEquals("[4, 9]", java.util.Arrays.toString(PreviewSelection.wordAt("the quick fox", 6)));
        assertEquals("[4, 9]", java.util.Arrays.toString(PreviewSelection.wordAt("the quick fox", 4)));
        assertEquals("[3, 4]", java.util.Arrays.toString(PreviewSelection.wordAt("the quick fox", 3)), "a space");
        assertEquals("[10, 13]", java.util.Arrays.toString(PreviewSelection.wordAt("the quick fox", 13)), "at the end");
        assertEquals("[0, 5]", java.util.Arrays.toString(PreviewSelection.wordAt("don't.", 2)));
        assertEquals("[0, 7]", java.util.Arrays.toString(PreviewSelection.wordAt("snake_1 x", 0)));
        assertEquals("[0, 0]", java.util.Arrays.toString(PreviewSelection.wordAt("", 0)));
    }

    @Test
    void shiftArrowsExtendEscapeClearsAndTheChordsBelongToThePreviewOnlyWhileSomethingIsSelected() throws Exception {
        open(DOC);
        javafx.scene.control.ScrollPane[] scroll = new javafx.scene.control.ScrollPane[1];
        EditorFx.onFx(() -> {
            scroll[0] = (javafx.scene.control.ScrollPane) EditorFx.styled(pane.node(), "markdown-preview-pane");
            assertNull(scroll[0].getProperties().get("editora.ownsKeys"), "nothing selected: the keys are the app's");
            sel().select(pos("First paragraph", 0), pos("First paragraph", 5));
            assertEquals(Boolean.TRUE, scroll[0].getProperties().get("editora.ownsKeys"));

            key(KeyCode.RIGHT, true, false);
            key(KeyCode.RIGHT, true, false);
            assertEquals("First p", sel().text());
            key(KeyCode.LEFT, true, false);
            assertEquals("First ", sel().text());

            // Down a line: into the list below the paragraph (the paragraph is one line at this width).
            key(KeyCode.DOWN, true, false);
            assertTrue(sel().text().startsWith("First paragraph with bold words"), sel().text());
            assertTrue(sel().text().contains("\n\nitem"), sel().text());
            key(KeyCode.UP, true, false);
            assertEquals("First ", sel().text(), "and back up to where it was");

            // Left from the start of a leaf goes to the end of the one before.
            sel().select(pos("item two", 2), pos("item two", 0));
            key(KeyCode.LEFT, true, false);
            key(KeyCode.LEFT, true, false);
            assertEquals("e\nit", sel().text());

            key(KeyCode.A, false, true);
            assertTrue(sel().isAll());
            assertTrue(sel().text().startsWith("Heading one\n\nFirst paragraph"));
            assertTrue(sel().text().endsWith("Last paragraph."));

            key(KeyCode.ESCAPE, false, false);
            assertFalse(sel().isActive());
            assertNull(scroll[0].getProperties().get("editora.ownsKeys"));
            KeyEvent plain = new KeyEvent(KeyEvent.KEY_PRESSED, "", "", KeyCode.RIGHT, true, false, false, false);
            scroll[0].fireEvent(plain);
            assertFalse(sel().isActive(), "with nothing selected the arrows are not this class's");
        });
        close();
    }

    @Test
    void copyPutsTheSelectedTextAndItsFormattingOnTheClipboard() throws Exception {
        open(DOC);
        EditorFx.onFx(() -> {
            EditorFx.clipboard("before");
            assertFalse(pane.preview().copySelection(), "nothing selected: nothing copied");
            assertEquals("before", Clipboard.getSystemClipboard().getString());

            // Cutting through a block: read off the nodes.
            sel().select(pos("First paragraph", 21), pos("First paragraph", 46));
            assertEquals("bold words and a link and", sel().text());
            assertTrue(pane.preview().copySelection());
            assertEquals(
                    "bold words and a link and", Clipboard.getSystemClipboard().getString());
            assertEquals(
                    "<p><strong>bold words</strong> and a <a href=\"https://example.com\">link</a> and</p>\n",
                    Clipboard.getSystemClipboard().getHtml());

            sel().select(pos("Heading one", 8), pos("item two", 4));
            assertTrue(pane.preview().copySelection());
            String html = Clipboard.getSystemClipboard().getHtml();
            assertTrue(html.startsWith("<h1>one</h1>\n<p>First paragraph with <strong>bold words</strong>"), html);
            assertTrue(html.contains("<code>code</code>."), html);
            assertTrue(html.endsWith("<ul>\n<li>item one</li>\n<li>item</li>\n</ul>\n"), html);

            sel().select(pos("a1", 0), pos("b2", 2));
            assertTrue(pane.preview().copySelection());
            assertEquals(
                    "<table><tr><td>a1</td><td>b1</td></tr><tr><td>a2</td><td>b2</td></tr></table>\n",
                    Clipboard.getSystemClipboard().getHtml());

            // Whole blocks: the Markdown itself is rendered, as Copy of the whole document does.
            press(pos("First paragraph", 3), 3, false);
            assertNotNull(sel().wholeBlocks());
            assertTrue(pane.preview().copySelection());
            String whole = Clipboard.getSystemClipboard().getHtml();
            assertEquals(
                    MarkdownClipboardHtml.toHtml(
                            "First paragraph with **bold words** and a [link](https://example.com) and `code`.", false),
                    whole);
            assertEquals(
                    "First paragraph with bold words and a link and code.",
                    Clipboard.getSystemClipboard().getString());

            // Everything: exactly what Copy of the document gives.
            assertTrue(sel().selectAll());
            assertTrue(pane.preview().copySelection());
            assertEquals(
                    MarkdownRenderer.plainText(DOC),
                    Clipboard.getSystemClipboard().getString());
            assertEquals(
                    MarkdownClipboardHtml.toHtml(DOC, false),
                    Clipboard.getSystemClipboard().getHtml());
        });
        close();
    }

    @Test
    void whatTheDocumentWroteNeverReachesTheClipboardAsMarkup() throws Exception {
        open("A <b>tag</b> & an [evil](javascript:alert(1)) link, [[note]] and \"quotes\" end.\n\nSecond.\n");
        EditorFx.onFx(() -> {
            sel().select(pos("A ", 2), pos("A ", 2 + 60));
            assertTrue(pane.preview().copySelection());
            String html = Clipboard.getSystemClipboard().getHtml();
            assertFalse(html.contains("javascript:"), html);
            assertFalse(html.contains("wikilink:"), html);
            assertFalse(html.contains("<b>"), html);
            assertTrue(html.contains("&amp;"), html);
            assertTrue(html.contains("&quot;quotes&quot;"), html);
        });
        close();
    }

    @Test
    void anEditRebuildsOneBlockAndTheSelectionStaysUnlessItsOwnBlockIsRebuilt() throws Exception {
        open(DOC);
        EditorFx.onFx(() -> sel().select(pos("Heading one", 0), pos("First paragraph", 5)));
        long built = EditorFx.callFx(() -> pane.preview().builtTotal());
        render(() -> buffer.getArea().appendText("\nOne more paragraph.\n"));
        settle();
        EditorFx.onFx(() -> {
            assertEquals(built + 1, pane.preview().builtTotal(), "with a selection, an edit still rebuilds one block");
            assertEquals("Heading one\n\nFirst", sel().text(), "an edit elsewhere leaves the selection alone");
            assertEquals(2, sel().paintedShapes());
        });
        // The block an end of the selection is in is rewritten: what was selected no longer exists.
        long before = EditorFx.callFx(() -> pane.preview().builtTotal());
        render(() -> buffer.getArea().replaceText(0, "# Heading one".length(), "# Heading 1"));
        settle();
        EditorFx.onFx(() -> {
            assertEquals(before + 1, pane.preview().builtTotal());
            assertFalse(sel().isActive());
            assertEquals(0, sel().paintedShapes());
        });
        // Everything selected stays everything, whatever is typed.
        EditorFx.onFx(() -> assertTrue(sel().selectAll()));
        render(() -> buffer.getArea().appendText("\nAnd another.\n"));
        settle();
        EditorFx.onFx(() -> {
            assertTrue(sel().isAll());
            assertTrue(sel().text().endsWith("And another."), sel().text());
        });
        close();
    }

    @Test
    void findMarksLinksAndTaskBoxesAreUndisturbed() throws Exception {
        open(DOC);
        EditorFx.onFx(() -> {
            sel().select(pos("First paragraph", 0), pos("First paragraph", 31));
            assertEquals("First paragraph with bold words", sel().text());
            // Find cuts runs into pieces; the selection is in the flow's text, which is the same text.
            assertEquals(2, pane.preview().find().search("paragraph", false, false, false));
            assertEquals("First paragraph with bold words", sel().text());
            assertEquals(
                    "<p>First paragraph with <strong>bold words</strong></p>\n",
                    PreviewSelectionHtml.of(sel().parts()));
            assertEquals(2, pane.node().lookupAll(".md-find-match").size(), "the marks are all there");
            pane.preview().find().clear();
            assertEquals("First paragraph with bold words", sel().text());

            // A link is followed by a click that does not move, and not by the end of a drag.
            Text link = run("link");
            javafx.geometry.Bounds b = link.localToScene(link.getBoundsInLocal());
            link.fireEvent(
                    mouse(MouseEvent.MOUSE_PRESSED, link, b.getMinX() + 2, b.getMinY() + 4, 1, false, false, true));
            link.fireEvent(
                    mouse(MouseEvent.MOUSE_CLICKED, link, b.getMinX() + 2, b.getMinY() + 4, 1, false, false, true));
            assertEquals(List.of("https://example.com"), opened);
            link.fireEvent(
                    mouse(MouseEvent.MOUSE_PRESSED, link, b.getMinX() + 2, b.getMinY() + 4, 1, false, false, true));
            dragTo(pos("First paragraph", 0));
            link.fireEvent(
                    mouse(MouseEvent.MOUSE_CLICKED, link, b.getMinX() + 2, b.getMinY() + 4, 1, false, false, false));
            assertEquals(
                    List.of("https://example.com"), opened, "a drag that ends on a link selects, it does not follow");
            assertTrue(sel().isActive());

            // A press on a task box is the box's: it ticks the source and selects nothing.
            sel().clear();
            CheckBox box = (CheckBox) EditorFx.styled(pane.node(), "md-task");
            javafx.geometry.Bounds cb = box.localToScene(box.getBoundsInLocal());
            box.fireEvent(
                    mouse(MouseEvent.MOUSE_PRESSED, box, cb.getMinX() + 3, cb.getMinY() + 3, 1, false, false, true));
            assertNull(sel().anchor(), "the press was left to the box");
            box.fire();
            assertTrue(buffer.getContent().contains("- [x] a task"));
        });
        close();
    }

    @Test
    void theHighlightCostsWhatIsOnScreenNotWhatIsInTheDocument() throws Exception {
        int[] shapes = new int[2];
        int[] blocks = new int[2];
        int[] sizes = {200, 2000};
        for (int k = 0; k < 2; k++) {
            StringBuilder md = new StringBuilder();
            for (int i = 0; i < sizes[k]; i++) {
                md.append("Paragraph number ").append(i).append(" of the document.\n\n");
            }
            open(md.toString());
            long end = System.nanoTime() + 30_000_000_000L;
            int want = sizes[k];
            while (EditorFx.callFx(() ->
                            pane.preview().column().getChildrenUnmodifiable().size())
                    < want) {
                assertTrue(System.nanoTime() < end, "the document never finished building");
                Thread.sleep(20);
            }
            EditorFx.onFx(() -> assertTrue(sel().selectAll()));
            settle();
            int at = k;
            EditorFx.onFx(() -> {
                shapes[at] = sel().paintedShapes();
                blocks[at] = pane.preview().column().getChildrenUnmodifiable().size();
                assertEquals(blocks[at], sel().parts().size(), "all of it is selected");
                // Scrolled to the middle, the highlight is drawn there, and is no larger.
                pane.preview().setScrollY(pane.preview().yOfLine(want));
            });
            settle();
            EditorFx.onFx(() -> {
                assertTrue(sel().paintedShapes() > 0, "the highlight follows the scroll");
                assertTrue(
                        sel().paintedShapes() <= 2 * shapes[at] + 4,
                        "and stays the size of the pane (now with a margin above as well as below)");
            });
            close();
        }
        assertTrue(shapes[0] > 0 && shapes[0] < 80, "a pane's worth of shapes: " + shapes[0]);
        assertTrue(
                shapes[1] <= 2 * shapes[0] + 4,
                "ten times the document, still a pane's worth: " + shapes[0] + " then " + shapes[1]);
    }
}
