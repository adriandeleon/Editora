package com.editora.editor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.VBox;
import javafx.scene.text.Text;
import javafx.scene.text.TextAlignment;
import javafx.scene.text.TextFlow;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer.MarkdownViewMode;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The live Markdown preview of a real {@link EditorBuffer}: which nodes a re-render keeps, where it scrolls,
 * and what the renderer builds for math, the HTML subset, wide blocks, images and links.
 */
@Tag("fx")
class MarkdownPreviewFxTest {

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

    private static ScrollPane pane(EditorBuffer buffer) {
        return EditorFx.call(buffer, "previewPane");
    }

    private static MarkdownPreview preview(EditorBuffer buffer) {
        return EditorFx.field(buffer, "markdownPreview");
    }

    private static VBox column(EditorBuffer buffer) {
        return EditorFx.field(preview(buffer), "column");
    }

    /** Switches {@code buffer} to {@code mode} and waits for the first render to be on screen and laid out. */
    private static void open(EditorBuffer buffer, MarkdownViewMode mode) throws Exception {
        EditorFx.awaitChange(() -> pane(buffer).contentProperty(), () -> buffer.setMarkdownViewMode(mode));
        layout(buffer);
    }

    /** Runs {@code edit}, asks for a re-render and waits until it has been shown. */
    private static void rerender(EditorBuffer buffer, Runnable edit) throws Exception {
        EditorFx.awaitChange(() -> preview(buffer).showsProperty(), () -> {
            edit.run();
            buffer.refreshPreview();
        });
        layout(buffer);
    }

    private static void layout(EditorBuffer buffer) throws Exception {
        EditorFx.onFx(() -> {
            buffer.getNode().applyCss();
            buffer.getNode().layout();
        });
        EditorFx.drain();
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

    private static Node render(String md, Path baseDir, List<String> opened) throws Exception {
        return EditorFx.callFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseForRender(md, true), baseDir, opened == null ? null : opened::add));
    }

    private static Text textNode(Node root, String text) {
        return collect(root, Text.class).stream()
                .filter(t -> t.getText().equals(text))
                .findFirst()
                .orElse(null);
    }

    private static MouseEvent click(MouseButton button, boolean still) {
        return new MouseEvent(
                MouseEvent.MOUSE_CLICKED,
                0,
                0,
                0,
                0,
                button,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                still,
                null);
    }

    /** 60 sections after a 150-line code block: a document whose scroll fraction says little about its lines. */
    private static String sections() {
        StringBuilder sb = new StringBuilder("# Top\n\n[Jump](#marker-40) and [out](https://example.org/x)\n\n```\n");
        for (int i = 0; i < 150; i++) {
            sb.append("code line ").append(i).append('\n');
        }
        sb.append("```\n\n");
        for (int i = 1; i <= 60; i++) {
            sb.append("## Marker ").append(i).append("\n\nParagraph ").append(i).append(" of prose.\n\n");
        }
        return sb.toString();
    }

    private static int lineOf(EditorBuffer buffer, String line) {
        String[] lines = buffer.getArea().getText().split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].equals(line)) {
                return i;
            }
        }
        throw new AssertionError("no line: " + line);
    }

    // --- A1: block reuse ---------------------------------------------------------------------------

    @Test
    void anEditRebuildsOnlyTheBlockItTouched() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown(sections());
            stage[0] = EditorFx.show(ref[0], 700, 400);
        });
        EditorBuffer buffer = ref[0];
        open(buffer, MarkdownViewMode.PREVIEW);
        Node[] root = new Node[1];
        List<Node> before = new ArrayList<>();
        EditorFx.onFx(() -> {
            root[0] = pane(buffer).getContent();
            before.addAll(column(buffer).getChildren());
            assertEquals(123, before.size(), "heading, links, code, 60 × (heading + paragraph)");
            assertEquals(123, preview(buffer).lastBuilt());
        });

        rerender(buffer, () -> {
            int at = buffer.getArea().getText().indexOf("Paragraph 30 of prose.");
            buffer.getArea().insertText(at, "Edited ");
        });
        EditorFx.onFx(() -> {
            assertSame(root[0], pane(buffer).getContent(), "the document node is not replaced");
            List<Node> after = column(buffer).getChildren();
            assertEquals(1, preview(buffer).lastBuilt(), "one block changed, one block is built");
            assertEquals(122, preview(buffer).lastReused());
            int changed = 0;
            for (int i = 0; i < after.size(); i++) {
                if (after.get(i) != before.get(i)) {
                    changed++;
                    assertTrue(EditorFx.textOf(after.get(i)).contains("Edited Paragraph 30"));
                }
            }
            assertEquals(1, changed, "every other node is the very node that was on screen");
        });

        // A new block in the middle: nothing around it is rebuilt, and the blocks below know their new lines.
        rerender(buffer, () -> {
            int at = buffer.getArea().getText().indexOf("## Marker 10\n");
            buffer.getArea().insertText(at, "Inserted paragraph.\n\n");
        });
        EditorFx.onFx(() -> {
            assertEquals(1, preview(buffer).lastBuilt());
            assertEquals(124, column(buffer).getChildren().size());
            MarkdownPreview p = preview(buffer);
            int line = lineOf(buffer, "## Marker 40");
            assertTrue(Math.abs(p.lineAtY(p.yOfLine(line)) - line) < 1e-6);
        });

        // Leaving the preview gives the nodes back; coming back builds them again.
        EditorFx.onFx(() -> {
            buffer.setMarkdownViewMode(MarkdownViewMode.EDITOR);
            assertTrue(column(buffer).getChildren().isEmpty());
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void aLongDocumentComesUpASliceAtATimeAndIsCompleteInTheEnd() throws Exception {
        StringBuilder md = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            md.append("Paragraph number ").append(i).append(".\n\n");
        }
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown(md.toString());
            stage[0] = EditorFx.show(ref[0], 700, 400);
        });
        EditorBuffer buffer = ref[0];
        int[] first = new int[1];
        // The first slice is on screen as soon as the render lands; the FX thread is free between slices.
        EditorFx.awaitChange(
                () -> pane(buffer).contentProperty(), () -> buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW));
        EditorFx.onFx(() -> {
            first[0] = column(buffer).getChildren().size();
            assertTrue(
                    first[0] >= MarkdownPreview.SLICE && first[0] < 2000,
                    "not built in one go, and not empty either: " + first[0]);
            assertTrue(EditorFx.textOf(column(buffer)).contains("Paragraph number 0."));
        });
        EditorFx.awaitUntil(
                () -> preview(buffer).showsProperty(),
                () -> preview(buffer).showsProperty().get() > 0);
        EditorFx.onFx(() -> {
            assertEquals(2000, column(buffer).getChildren().size());
            assertEquals(2000, preview(buffer).lastBuilt());
            assertTrue(EditorFx.textOf(column(buffer)).contains("Paragraph number 1999."));
        });
        // From then on an edit is one block again.
        rerender(buffer, () -> buffer.getArea().insertText(0, "X"));
        EditorFx.onFx(() -> {
            assertEquals(1, preview(buffer).lastBuilt());
            assertEquals(2000, column(buffer).getChildren().size());
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void theColumnMeasuresOnlyTheBlocksThatAskedForLayout() throws Exception {
        EditorFx.onFx(() -> {
            BlockColumn column = new BlockColumn();
            for (int i = 0; i < 200; i++) {
                column.getChildren()
                        .add(new TextFlow(new Text("Paragraph " + i + " with enough words to wrap around.")));
            }
            Stage stage = new Stage();
            stage.setScene(new Scene(column, 300, 400));
            stage.show();
            column.applyCss();
            column.layout();
            long afterFirst = column.measured();
            assertTrue(afterFirst >= 200, "every block is measured once");

            // The way a scroll pane asks: the height at two widths, every pass. Nothing is measured again.
            for (int pass = 0; pass < 5; pass++) {
                column.prefHeight(300);
                column.prefHeight(286);
            }
            long steady = column.measured();
            assertTrue(steady <= afterFirst + 200, "a second width costs one more measure per block, once");
            column.prefHeight(300);
            column.prefHeight(286);
            column.layout();
            assertEquals(steady, column.measured(), "unchanged blocks at known widths are not measured");

            // One block changes: it is measured, its neighbours only move.
            double below = column.getChildren().get(11).getLayoutY();
            ((TextFlow) column.getChildren().get(10))
                    .getChildren()
                    .add(new Text(" And a good deal more text so that it needs more lines than it did."));
            column.layout();
            long delta = column.measured() - steady;
            assertTrue(delta >= 1 && delta <= 3, "only the block that asked for layout: " + delta);
            assertTrue(column.getChildren().get(11).getLayoutY() > below, "the blocks below it moved down");

            // A block replaced by another one: one measure, and the height adds up.
            column.getChildren().set(20, new TextFlow(new Text("New.")));
            long beforeLayout = column.measured();
            double height = column.prefHeight(300);
            column.layout();
            assertTrue(column.measured() - beforeLayout <= 3);
            Node last = column.getChildren().get(199);
            assertEquals(height, last.getLayoutY() + last.getLayoutBounds().getHeight(), 1.0);
            stage.close();
        });
    }

    // --- A7 / A8: lines, not fractions -------------------------------------------------------------

    @Test
    void theSplitPreviewShowsTheEditorsTopLineWhateverSitsAboveIt() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown(sections());
            ref[0].getArea().moveTo(0);
            stage[0] = EditorFx.show(ref[0], 900, 400);
        });
        EditorBuffer buffer = ref[0];
        open(buffer, MarkdownViewMode.SPLIT);
        for (int marker : new int[] {5, 20, 45}) {
            EditorFx.onFx(() -> {
                buffer.getArea().showParagraphAtTop(lineOf(buffer, "## Marker " + marker));
                buffer.getArea().layout();
            });
            layout(buffer);
            EditorFx.onFx(() -> {
                ScrollPane pane = pane(buffer);
                Text heading = textNode(pane.getContent(), "Marker " + marker);
                double top = pane.localToScene(pane.getBoundsInLocal()).getMinY();
                double y = heading.localToScene(heading.getBoundsInLocal()).getMinY() - top;
                assertTrue(
                        y >= -2 && y < 60,
                        "Marker " + marker + " is at the top of the preview too, not a fraction away: y=" + y);
            });
        }
        // The other way: the preview scrolled (mouse over it) to a heading brings the editor to that line.
        EditorFx.onFx(() -> {
            ScrollPane pane = pane(buffer);
            Object sync = EditorFx.field(buffer, "scrollSync");
            EditorFx.setField(sync, "layoutChangedAt", System.nanoTime() - 3_600_000_000_000L);
            EditorFx.setField(
                    sync, "contentHeight", pane.getContent().getLayoutBounds().getHeight());
            EditorFx.hover(pane, true);
            assertTrue(preview(buffer).scrollToAnchor("marker-30"));
            buffer.getArea().layout();
            assertEquals(lineOf(buffer, "## Marker 30"), ScrollAnchor.firstVisibleLine(buffer.getArea()), 1.0);
            EditorFx.hover(pane, false);
            stage[0].close();
            buffer.dispose();
        });
    }

    @Test
    void aModeSwitchAndARerenderKeepTheReadersPlace() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown(sections());
            stage[0] = EditorFx.show(ref[0], 700, 400);
        });
        EditorBuffer buffer = ref[0];
        int[] line = new int[1];
        EditorFx.onFx(() -> {
            line[0] = lineOf(buffer, "## Marker 40");
            buffer.getArea().showParagraphAtTop(line[0]);
            buffer.getArea().layout();
            assertEquals(line[0], ScrollAnchor.firstVisibleLine(buffer.getArea()));
        });
        // Editor → Preview: the preview comes up at the line the editor was showing.
        open(buffer, MarkdownViewMode.PREVIEW);
        EditorFx.onFx(() -> assertEquals(line[0], preview(buffer).topLine(), 0.5, "not wherever it was before"));

        // Text inserted far above: the same heading is still at the top afterwards.
        rerender(buffer, () -> buffer.getArea().insertText(0, "Intro one.\n\nIntro two.\n\nIntro three.\n\n"));
        EditorFx.onFx(() -> {
            assertEquals(line[0] + 6, lineOf(buffer, "## Marker 40"));
            assertEquals(line[0] + 6, preview(buffer).topLine(), 0.5, "the reader's line, six lines further down");
        });

        // Preview → Editor: the editor opens where the preview was read.
        int[] target = new int[1];
        EditorFx.onFx(() -> {
            target[0] = lineOf(buffer, "## Marker 12");
            assertTrue(preview(buffer).scrollToAnchor("#Marker-12"));
            buffer.setMarkdownViewMode(MarkdownViewMode.EDITOR);
        });
        layout(buffer);
        EditorFx.onFx(() -> {
            buffer.getArea().layout();
            assertEquals(target[0], ScrollAnchor.firstVisibleLine(buffer.getArea()), 1.0);
            stage[0].close();
            buffer.dispose();
        });
    }

    // --- anchors and links (A4's preview half, A16, A20) -------------------------------------------

    @Test
    void anAnchorLinkScrollsThePreviewAndOnlyAPlainPrimaryClickFollowsALink() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        List<String> opened = new ArrayList<>();
        EditorFx.onFx(() -> {
            ref[0] = markdown(sections());
            ref[0].setOpenUrlHandler(opened::add);
            stage[0] = EditorFx.show(ref[0], 700, 400);
        });
        EditorBuffer buffer = ref[0];
        open(buffer, MarkdownViewMode.PREVIEW);
        EditorFx.onFx(() -> {
            MarkdownPreview p = preview(buffer);
            assertEquals("top", p.anchors().get(0));
            assertTrue(p.anchors().contains("marker-40"));
            assertFalse(p.scrollToAnchor("no-such-heading"));
            // The public way in, for whoever follows a link from outside the preview.
            assertTrue(buffer.scrollPreviewToAnchor("#Marker-7"));
            assertEquals(lineOf(buffer, "## Marker 7"), p.topLine(), 0.5);
            assertFalse(buffer.scrollPreviewToAnchor("no-such-heading"));
            assertFalse(markdown("# A\n").scrollPreviewToAnchor("a"), "no preview showing: nothing to scroll");

            Text jump = textNode(pane(buffer).getContent(), "Jump");
            jump.fireEvent(click(MouseButton.PRIMARY, true));
            assertTrue(opened.isEmpty(), "an in-document anchor is not handed to the link opener");
            assertEquals(lineOf(buffer, "## Marker 40"), p.topLine(), 0.5, "the preview scrolled to the heading");

            Text out = textNode(pane(buffer).getContent(), "out");
            out.fireEvent(click(MouseButton.SECONDARY, true));
            out.fireEvent(click(MouseButton.PRIMARY, false));
            assertTrue(opened.isEmpty(), "neither the menu button nor the end of a drag opens a link");
            out.fireEvent(click(MouseButton.PRIMARY, true));
            assertEquals(List.of("https://example.org/x"), opened);

            // Keyboard: a link is a Tab stop and Enter follows it.
            assertTrue(out.isFocusTraversable());
            assertEquals(javafx.scene.AccessibleRole.HYPERLINK, out.getAccessibleRole());
            out.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals(2, opened.size());
        });

        // The menu knows what the pointer is on.
        EditorFx.onFx(() -> {
            Text out = textNode(pane(buffer).getContent(), "out");
            pane(buffer)
                    .fireEvent(new ContextMenuEvent(
                            ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 220, 220, false, new PickResult(out, 5, 5)));
            ContextMenu menu = EditorFx.field(buffer, "previewContextMenu");
            List<String> titles = EditorFx.titles(menu.getItems());
            assertFalse(titles.contains(tr("editmenu.selectAll")), "nothing can be selected: no Select All");
            MenuItem copyLink = EditorFx.menuItem(menu.getItems(), tr("previewmenu.copyLink"));
            assertTrue(copyLink.isVisible());
            assertFalse(EditorFx.menuItem(menu.getItems(), tr("previewmenu.copyCode"))
                    .isVisible());
            EditorFx.clipboard("stale");
            copyLink.fire();
            assertEquals("https://example.org/x", Clipboard.getSystemClipboard().getString());
            menu.hide();

            Node code = EditorFx.styled(pane(buffer).getContent(), "md-code-block");
            pane(buffer)
                    .fireEvent(new ContextMenuEvent(
                            ContextMenuEvent.CONTEXT_MENU_REQUESTED,
                            5,
                            5,
                            220,
                            220,
                            false,
                            new PickResult(code, 5, 5)));
            MenuItem copyCode = EditorFx.menuItem(menu.getItems(), tr("previewmenu.copyCode"));
            assertTrue(copyCode.isVisible());
            assertFalse(copyLink.isVisible());
            copyCode.fire();
            assertTrue(Clipboard.getSystemClipboard().getString().startsWith("code line 0\ncode line 1\n"));
            menu.hide();
            stage[0].close();
            buffer.dispose();
        });
    }

    // --- A2: math drawn for the preview's theme ----------------------------------------------------

    /** Mean brightness of the opaque pixels of the first formula under {@code root}. */
    private static double glyphBrightness(Node root) {
        ImageView view = collect(root, ImageView.class).stream()
                .filter(v -> v.getStyleClass().contains("md-math-inline"))
                .findFirst()
                .orElseThrow();
        var reader = view.getImage().getPixelReader();
        double sum = 0;
        int n = 0;
        for (int y = 0; y < view.getImage().getHeight(); y++) {
            for (int x = 0; x < view.getImage().getWidth(); x++) {
                javafx.scene.paint.Color c = reader.getColor(x, y);
                if (c.getOpacity() > 0.9) {
                    sum += c.getBrightness();
                    n++;
                }
            }
        }
        return n == 0 ? -1 : sum / n;
    }

    @Test
    void formulasAreDrawnForThePreviewsOwnThemeAndRedrawnWhenItIsToggled() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            MathImages.configure(true, false); // a light app theme
            ref[0] = markdown("Euler $e^{i\\pi} + 1 = 0$ here.\n");
            ref[0].applyPreviewTheme("dark", false); // the preview toggled to dark
            stage[0] = EditorFx.show(ref[0], 600, 300);
        });
        EditorBuffer buffer = ref[0];
        try {
            open(buffer, MarkdownViewMode.PREVIEW);
            Node[] first = new Node[1];
            EditorFx.onFx(() -> {
                first[0] = column(buffer).getChildren().get(0);
                assertTrue(
                        glyphBrightness(pane(buffer).getContent()) > 0.7,
                        "light glyphs on the dark preview (they were dark on dark)");
            });
            rerender(buffer, () -> buffer.applyPreviewTheme("light", false));
            EditorFx.onFx(() -> {
                assertNotSame(first[0], column(buffer).getChildren().get(0), "the theme changed: rebuilt");
                assertTrue(glyphBrightness(pane(buffer).getContent()) < 0.3, "dark glyphs on the light preview");
            });
            // Following the app theme again uses the app's.
            rerender(buffer, () -> buffer.applyPreviewTheme("", false));
            EditorFx.onFx(() -> assertTrue(glyphBrightness(pane(buffer).getContent()) < 0.3));
        } finally {
            EditorFx.onFx(() -> {
                MathImages.configure(false, false);
                stage[0].close();
                buffer.dispose();
            });
        }
    }

    @Test
    void mathKeepsItsLatexAndAnUnclosedBlockIsShownAsText() throws Exception {
        EditorFx.onFx(() -> MathImages.configure(true, false));
        try {
            Node root = render("Row $a*b*c$ and `$code$` and \\$5.\n\n$$\nx \\\\ y\n$$\n\n$$\nopen\n", null, null);
            EditorFx.onFx(() -> {
                assertEquals(
                        1,
                        collect(root, ImageView.class).stream()
                                .filter(v -> v.getStyleClass().contains("md-math-inline"))
                                .count());
                assertNotNull(EditorFx.styled(root, "md-math-block-wrap"));
                String text = EditorFx.textOf(root);
                assertTrue(text.contains("$code$"), "code is never math: " + text);
                assertTrue(text.contains("$5"), "an escaped dollar is text");
                assertFalse(text.contains("a*b*c") || text.contains("$a"), "the formula is an image, not text");
                assertTrue(text.contains("$$open"), "a block that was never closed shows what was typed");
            });
        } finally {
            EditorFx.onFx(() -> MathImages.configure(false, false));
        }
    }

    // --- A13: the safe HTML subset -------------------------------------------------------------------

    @Test
    void inlineHtmlOfTheSafeSubsetIsRenderedAndTheRestDropped() throws Exception {
        Node root = render(
                "Press <kbd>Ctrl</kbd>+<kbd>C</kbd>, line<br>break, H<sub>2</sub>O, <b>bold</b> <i>it</i>, "
                        + "<span style=\"color:red\">kept</span><!-- gone -->, <a href=\"https://e.org\">site</a>.\n",
                null,
                new ArrayList<>());
        EditorFx.onFx(() -> {
            String text = EditorFx.textOf(root);
            assertFalse(text.contains("<"), "no tag is shown as source: " + text);
            assertFalse(text.contains("gone"));
            List<Label> keys = collect(root, Label.class).stream()
                    .filter(l -> l.getStyleClass().contains("md-kbd"))
                    .toList();
            assertEquals(List.of("Ctrl", "C"), keys.stream().map(Label::getText).toList());
            assertNotNull(textNode(root, "\n"), "<br> is a line break");
            assertTrue(textNode(root, "2").getStyleClass().contains("md-sub"));
            assertTrue(textNode(root, "bold").getStyleClass().contains("md-bold"));
            assertTrue(textNode(root, "it").getStyleClass().contains("md-italic"));
            assertNotNull(textNode(root, "kept"), "an unknown tag is dropped, its text kept");
            Text site = textNode(root, "site");
            assertTrue(site.getStyleClass().contains("md-link"));
            assertEquals("https://e.org", site.getProperties().get(MarkdownRenderer.LINK_KEY));
        });
    }

    @Test
    void htmlBlocksBecomeParagraphsHeadingsAndImagesAndNeverRunAnything() throws Exception {
        Node root = render(
                "<p align=\"center\">Centered <b>title</b><br>second line</p>\n\n"
                        + "<h2 align=\"center\">Heading</h2>\n\n"
                        + "<!-- note --> Visible text after a comment\n\n"
                        + "<script>alert(1)</script>\n\n"
                        + "<iframe src=\"https://evil.example\">inner text</iframe>\n\n"
                        + "<pre>\nkept   as\n  written\n</pre>\n\n"
                        + "<img src=\"https://example.org/a.png\" alt=\"remote\" width=\"60\">\n",
                null,
                null);
        // (renderDocument's default policy would load the remote image; this render never reaches a pulse.)
        EditorFx.onFx(() -> {
            String text = EditorFx.textOf(root);
            assertFalse(text.contains("<p") || text.contains("<h2") || text.contains("iframe"), text);
            assertFalse(text.contains("alert"), "script content is dropped");
            assertFalse(text.contains("note"), "the comment is dropped");
            assertTrue(text.contains("Visible text after a comment"));
            assertTrue(text.contains("inner text"));
            assertTrue(text.contains("kept   as\n  written"), "<pre> keeps its whitespace: " + text);
            TextFlow centered = (TextFlow) textNode(root, "Centered ").getParent();
            assertEquals(TextAlignment.CENTER, centered.getTextAlignment());
            assertTrue(centered.getStyleClass().contains("md-paragraph"));
            TextFlow heading = (TextFlow) textNode(root, "Heading").getParent();
            assertTrue(heading.getStyleClass().contains("md-h2"));
            assertEquals(TextAlignment.CENTER, heading.getTextAlignment());
        });
        // An untrusted surface shows an HTML image as a placeholder too: the same policy as ![](…).
        Node untrusted = EditorFx.callFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseForRender("<img src=\"https://example.org/a.png\" alt=\"remote\">\n", false),
                null,
                null,
                MarkdownRenderer.ImagePolicy.DATA_ONLY));
        EditorFx.onFx(() -> {
            assertTrue(collect(untrusted, ImageView.class).isEmpty(), "nothing is fetched");
            assertTrue(EditorFx.textOf(untrusted).contains("[image: remote] https://example.org/a.png"));
        });
    }

    @Test
    void detailsFoldsTheMarkdownBetweenItsTags() throws Exception {
        Node root = render(
                "before\n\n<details>\n<summary>Click <b>me</b></summary>\n\nHidden **content**.\n\n- item\n\n</details>\n\n"
                        + "<details open>\n<summary>Open one</summary>\n\nShown.\n\n</details>\n\nafter\n",
                null,
                null);
        EditorFx.onFx(() -> {
            List<Node> details = new ArrayList<>();
            for (VBox box : collect(root, VBox.class)) {
                if (box.getStyleClass().contains("md-details")) {
                    details.add(box);
                }
            }
            assertEquals(2, details.size());
            assertFalse(EditorFx.textOf(root).contains("<details"), "the tags are not shown");
            Node header = EditorFx.styled(details.get(0), "md-details-summary");
            Node body = EditorFx.styled(details.get(0), "md-details-body");
            assertTrue(EditorFx.textOf(header).contains("Click"));
            assertFalse(body.isVisible(), "closed until asked");
            assertFalse(body.isManaged());
            assertNotNull(textNode(body, "content"), "the Markdown inside is rendered as Markdown");
            header.fireEvent(click(MouseButton.PRIMARY, true));
            assertTrue(body.isVisible());
            assertTrue(header.isFocusTraversable());
            header.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertFalse(body.isVisible(), "Enter toggles it too");
            assertTrue(EditorFx.styled(details.get(1), "md-details-body").isVisible(), "<details open>");
            assertNotNull(textNode(root, "after"));
        });
    }

    // --- A6 / A14: nothing is cut off or squeezed ----------------------------------------------------

    @Test
    void longInlineCodeBreaksAndWideTablesAndCodeScrollSideways() throws Exception {
        String command = "./mvnw -q -o -DskipTests test-compile dependency:build-classpath -Dmdep.outputFile=cp.txt";
        StringBuilder table = new StringBuilder("|");
        for (int c = 1; c <= 16; c++) {
            table.append(" c").append(c).append(" |");
        }
        table.append("\n|");
        table.append("----|".repeat(16)).append("\n|");
        for (int c = 1; c <= 16; c++) {
            table.append(" v").append(c).append(" |");
        }
        String longLine = "x".repeat(200);
        Node root = render(
                "Run `" + command + "` first.\n\n" + table + "\n\n| a | b |\n|---|---|\n| 1 | 2 |\n\n```\n" + longLine
                        + "\nshort\n```\n",
                null,
                null);
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            stage[0] = new Stage();
            ScrollPane scroll = new ScrollPane(root);
            scroll.setFitToWidth(true);
            stage[0].setScene(new Scene(scroll, 420, 600));
            stage[0].show();
            scroll.applyCss();
            scroll.layout();
        });
        EditorFx.drain();
        EditorFx.onFx(() -> {
            root.getParent().getParent().getParent().layout();
            // Inline code: pieces the line can break between, none wider than the column.
            List<Label> pieces = collect(root, Label.class).stream()
                    .filter(l -> l.getStyleClass().contains("md-inline-code"))
                    .toList();
            assertTrue(pieces.size() > 3, "an 80-character command is not one unbreakable box");
            StringBuilder joined = new StringBuilder();
            pieces.forEach(l -> joined.append(l.getText()));
            assertEquals(command, joined.toString(), "nothing is lost");
            TextFlow flow = (TextFlow) pieces.get(0).getParent();
            double right = flow.localToScene(flow.getBoundsInLocal()).getMaxX();
            for (Label piece : pieces) {
                assertTrue(
                        piece.localToScene(piece.getBoundsInLocal()).getMaxX() <= right + 1,
                        "every piece is inside the paragraph: " + piece.getText());
            }

            List<SideScroll> wide = collect(root, SideScroll.class);
            assertEquals(3, wide.size(), "two tables and a code block");
            SideScroll sixteen = wide.get(0);
            assertTrue(sixteen.overflows(), "a 16-column table scrolls in a 420px pane");
            assertTrue(sixteen.content().getWidth() > sixteen.getWidth());
            Text cell = textNode(sixteen, "v16");
            assertTrue(
                    cell.getLayoutBounds().getHeight() < 30,
                    "\"v16\" is on one line, not broken into v1/6: "
                            + cell.getLayoutBounds().getHeight());
            assertFalse(wide.get(1).overflows(), "a small table just fills the column");
            assertEquals(wide.get(1).getWidth(), wide.get(1).content().getWidth(), 1.0);

            SideScroll code = wide.get(2);
            assertTrue(code.overflows(), "a 200-character line scrolls instead of wrapping");
            assertTrue(
                    code.content().getHeight() < 90,
                    "two lines of code are two lines high: " + code.content().getHeight());
            assertTrue(code.getWidth() <= 420, "and the block itself is no wider than the pane");
            assertEquals(longLine + "\nshort", code.getProperties().get(MarkdownRenderer.CODE_KEY));
            stage[0].close();
        });
    }

    // --- A10 / A11 / A12: images -------------------------------------------------------------------

    private static void png(Path file, int width, int rgb) throws Exception {
        java.awt.image.BufferedImage img =
                new java.awt.image.BufferedImage(width, 10, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int x = 0; x < width; x++) {
            for (int y = 0; y < 10; y++) {
                img.setRGB(x, y, rgb);
            }
        }
        javax.imageio.ImageIO.write(img, "png", file.toFile());
    }

    private static void awaitImage(ImageView view) throws Exception {
        EditorFx.awaitUntil(view::imageProperty, () -> view.getImage() != null);
    }

    @Test
    void aMissingImageSaysSoAndAnEncodedPathAndAReplacedFileAreLoaded(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("img"));
        png(dir.resolve("img/my image.png"), 40, 0xCC3333);
        String md = "![spaced](img/my%20image.png)\n\n![the alt text](img/does-not-exist.png)\n";
        Node root = render(md, dir, null);
        List<javafx.scene.layout.StackPane> slots =
                EditorFx.callFx(() -> collect(root, javafx.scene.layout.StackPane.class).stream()
                        .filter(p -> p.getStyleClass().contains("md-image-slot"))
                        .toList());
        assertEquals(2, slots.size());
        ImageView spaced =
                (ImageView) EditorFx.callFx(() -> slots.get(0).getChildren().get(0));
        awaitImage(spaced);
        EditorFx.onFx(() -> assertEquals(40, spaced.getImage().getWidth(), "the %20 path is the file with a space"));

        // The missing one: its slot shows the alt text instead of staying a 0×0 nothing.
        EditorFx.awaitUntil(
                () -> slots.get(1).getChildren(),
                () -> slots.get(1).getChildren().get(0) instanceof Label);
        EditorFx.onFx(() -> {
            Label missing = (Label) slots.get(1).getChildren().get(0);
            assertEquals("[image: the alt text]", missing.getText());
            assertTrue(missing.getStyleClass().contains("md-image-missing"));
        });

        // The file is exported again, wider: the next render shows the new picture, not the cached one.
        png(dir.resolve("img/my image.png"), 90, 0x33CC33);
        Node again = render(md, dir, null);
        ImageView reloaded =
                EditorFx.callFx(() -> collect(again, ImageView.class).get(0));
        EditorFx.awaitUntil(
                reloaded::imageProperty,
                () -> reloaded.getImage() != null && reloaded.getImage().getWidth() == 90);
        EditorFx.onFx(() -> assertEquals(90, reloaded.getFitWidth(), 0.5));
    }

    @Test
    void anImageAltIsItsWholeTextAndALinkTitleIsShown() throws Exception {
        Node root = EditorFx.callFx(() -> MarkdownRenderer.renderDocument(
                MarkdownRenderer.parseForRender("![a *nested* `alt`](https://h.example/x.png)\n", false),
                null,
                null,
                MarkdownRenderer.ImagePolicy.DATA_ONLY));
        EditorFx.onFx(() -> assertTrue(
                EditorFx.textOf(root).contains("[image: a nested alt]"),
                "not only the direct text: " + EditorFx.textOf(root)));
    }

    // --- A9: remote images can be switched off, and open previews follow --------------------------------

    @Test
    void switchingRemoteImagesOffTurnsThemIntoPlaceholdersInAnOpenPreview() throws Exception {
        // (An address the loader refuses by itself, so the test never reaches the network either way.)
        String md = "Text.\n\n![pic](http://127.0.0.1:9/x.png)\n";
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown(md);
            stage[0] = EditorFx.show(ref[0], 600, 300);
        });
        EditorBuffer buffer = ref[0];
        try {
            open(buffer, MarkdownViewMode.PREVIEW);
            Node[] image = new Node[1];
            EditorFx.onFx(() -> {
                image[0] = column(buffer).getChildren().get(1);
                assertNotNull(EditorFx.styled(image[0], "md-image-slot"), "on by default: an image to load");
            });
            rerender(buffer, () -> PreviewImageLoader.setRemoteImages(false));
            EditorFx.onFx(() -> {
                Node now = column(buffer).getChildren().get(1);
                assertNotSame(image[0], now, "the setting changed: the block is rebuilt");
                assertNull(EditorFx.styled(now, "md-image-slot"), "nothing is fetched");
                assertTrue(
                        EditorFx.textOf(now).contains("[image: pic] http://127.0.0.1:9/x.png"), EditorFx.textOf(now));
            });
        } finally {
            EditorFx.onFx(() -> {
                PreviewImageLoader.setRemoteImages(true);
                stage[0].close();
                buffer.dispose();
            });
        }
    }

    // --- A18: footnotes are numbered and linked both ways -----------------------------------------------

    @Test
    void footnotesAreNumberedInOrderOfUseAndLinkedToTheirDefinitionsAndBack() throws Exception {
        StringBuilder md = new StringBuilder(
                "# Notes\n\nFirst claim[^zeta] and a second[^alpha], and the first again[^zeta].\n\n");
        for (int i = 0; i < 60; i++) {
            md.append("Filler paragraph ").append(i).append(".\n\n");
        }
        md.append("[^alpha]: The alpha note.\n\n[^zeta]: The zeta note.\n\n[^unused]: Never referred to.\n");
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        List<String> opened = new ArrayList<>();
        EditorFx.onFx(() -> {
            ref[0] = markdown(md.toString());
            ref[0].setOpenUrlHandler(opened::add);
            stage[0] = EditorFx.show(ref[0], 700, 400);
        });
        EditorBuffer buffer = ref[0];
        open(buffer, MarkdownViewMode.PREVIEW);
        EditorFx.onFx(() -> {
            Node root = pane(buffer).getContent();
            List<String> refs = collect(root, Text.class).stream()
                    .filter(t -> t.getStyleClass().contains("md-footnote-ref"))
                    .map(Text::getText)
                    .toList();
            assertEquals(List.of("[1]", "[2]", "[1]"), refs, "numbers by first use, not the author's labels");
            List<String> markers = collect(root, Label.class).stream()
                    .filter(l -> l.getStyleClass().contains("md-footnote-def-marker"))
                    .map(Label::getText)
                    .toList();
            assertEquals(List.of("[2]", "[1]", "[unused]"), markers, "a definition carries its reference's number");

            MarkdownPreview p = preview(buffer);
            Text first = textNode(root, "[1]");
            first.fireEvent(click(MouseButton.PRIMARY, true));
            assertTrue(opened.isEmpty(), "a footnote link stays in the preview");
            Node definition = textNode(root, "The zeta note.");
            double top =
                    pane(buffer).localToScene(pane(buffer).getBoundsInLocal()).getMinY();
            double y = definition.localToScene(definition.getBoundsInLocal()).getMinY() - top;
            assertTrue(
                    y >= 0 && y < pane(buffer).getViewportBounds().getHeight(),
                    "scrolled to the definition (as far as the document goes): y=" + y);

            List<Text> back = collect(root, Text.class).stream()
                    .filter(t -> t.getStyleClass().contains("md-footnote-back"))
                    .toList();
            assertEquals(2, back.size(), "one way back per referenced note; none for the unused one");
            back.get(0).fireEvent(click(MouseButton.PRIMARY, true));
            assertTrue(p.topLine() < 5, "and back to the paragraph that referred to it: " + p.topLine());
            assertTrue(opened.isEmpty());
        });
        // A new reference ahead of the others renumbers them: the blocks that show numbers are rebuilt.
        rerender(
                buffer,
                () -> buffer.getArea()
                        .insertText(buffer.getArea().getText().indexOf("First claim"), "Before[^alpha]. "));
        EditorFx.onFx(() -> {
            List<String> refs = collect(pane(buffer).getContent(), Text.class).stream()
                    .filter(t -> t.getStyleClass().contains("md-footnote-ref"))
                    .map(Text::getText)
                    .toList();
            assertEquals(List.of("[1]", "[2]", "[1]", "[2]"), refs);
            List<String> markers = collect(pane(buffer).getContent(), Label.class).stream()
                    .filter(l -> l.getStyleClass().contains("md-footnote-def-marker"))
                    .map(Label::getText)
                    .toList();
            assertEquals(List.of("[1]", "[2]", "[unused]"), markers);
            stage[0].close();
            buffer.dispose();
        });
    }

    // --- A17: zoom reaches formulas and images; zoom and divider are kept ----------------------------

    @Test
    void zoomSizesFormulasAndTheSplitDividerStaysWhereItWasPut() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        List<PreviewLayout> stored = new ArrayList<>();
        EditorFx.onFx(() -> {
            MathImages.configure(true, false);
            ref[0] = markdown("Euler $e^{i\\pi} + 1 = 0$ here.\n");
            // What the window does on open: the layout remembered for the file, and a listener for changes.
            ref[0].restorePreviewLayout(new PreviewLayout(1.0, 0.3), () -> stored.add(ref[0].getPreviewLayout()));
            stage[0] = EditorFx.show(ref[0], 900, 300);
        });
        EditorBuffer buffer = ref[0];
        try {
            open(buffer, MarkdownViewMode.SPLIT);
            double[] base = new double[1];
            EditorFx.onFx(() -> {
                javafx.scene.control.SplitPane split = (javafx.scene.control.SplitPane)
                        pane(buffer).getParent().getParent().getParent();
                assertEquals(0.3, split.getDividerPositions()[0], 0.02, "the remembered divider, not the middle");
                ImageView formula =
                        collect(pane(buffer).getContent(), ImageView.class).get(0);
                base[0] = formula.getFitWidth();
                buffer.zoomPreviewIn();
                buffer.zoomPreviewIn();
                assertEquals(base[0] * 1.21, formula.getFitWidth(), 0.5, "a formula grows with the text");
                assertEquals(
                        1.21, stored.get(stored.size() - 1).zoom(), 1e-6, "and the zoom is reported, to be stored");
            });
            // A block built while zoomed is zoomed too.
            rerender(buffer, () -> buffer.getArea().appendText("\nMore $e^{i\\pi} + 1 = 0$ later.\n"));
            EditorFx.onFx(() -> {
                List<ImageView> formulas = collect(pane(buffer).getContent(), ImageView.class);
                assertEquals(2, formulas.size());
                assertEquals(base[0] * 1.21, formulas.get(1).getFitWidth(), 0.5);
            });
            // Dragging the divider is remembered, and survives a trip through Preview.
            EditorFx.onFx(() -> {
                javafx.scene.control.SplitPane split = (javafx.scene.control.SplitPane)
                        pane(buffer).getParent().getParent().getParent();
                split.setDividerPositions(0.6);
                assertEquals(0.3, buffer.getPreviewLayout().divider(), 1e-6, "a programmatic move is not the user's");
                EditorFx.pressed(split, true);
                split.setDividerPositions(0.7);
                EditorFx.pressed(split, false);
                assertEquals(0.7, buffer.getPreviewLayout().divider(), 0.01);
                assertEquals(0.7, stored.get(stored.size() - 1).divider(), 0.01);
                buffer.setMarkdownViewMode(MarkdownViewMode.PREVIEW);
                buffer.setMarkdownViewMode(MarkdownViewMode.SPLIT);
            });
            layout(buffer);
            EditorFx.onFx(() -> {
                javafx.scene.control.SplitPane split = (javafx.scene.control.SplitPane)
                        pane(buffer).getParent().getParent().getParent();
                assertEquals(0.7, split.getDividerPositions()[0], 0.02);
                buffer.resetPreviewZoom();
                assertEquals(1.0, buffer.getPreviewLayout().zoom(), 1e-9);
            });
        } finally {
            EditorFx.onFx(() -> {
                MathImages.configure(false, false);
                stage[0].close();
                buffer.dispose();
            });
        }
    }

    // --- A15: a long line does not stop the live preview --------------------------------------------

    @Test
    void aMarkdownFileThatIsLargeOnlyForOneLongLineKeepsItsLivePreview() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer md = markdown("# Title\n\n![d](data:image/png;base64," + "A".repeat(70_000) + ")\n");
            md.setLargeFile(true); // what the loader does for a 64K-character line
            assertTrue(EditorFx.<Boolean>call(md, "previewLive"), "its preview follows edits");
            EditorBuffer code = new EditorBuffer();
            code.getArea().replaceText("x".repeat(70_000));
            code.setLargeFile(true);
            assertFalse(EditorFx.<Boolean>call(code, "previewLive"), "large-file mode still holds for the rest");
            EditorBuffer small = markdown("# small\n");
            assertTrue(EditorFx.<Boolean>call(small, "previewLive"));
            assertTrue(
                    EditorBuffer.PREVIEW_LIVE_MAX_CHARS < EditorBuffer.LARGE_FILE_BYTES, "a truly large file is not");
            // A document past that limit says its preview is paused and offers a refresh; a live one does not.
            EditorBuffer huge = markdown("x".repeat(EditorBuffer.PREVIEW_LIVE_MAX_CHARS + 1));
            huge.setLargeFile(true);
            assertFalse(EditorFx.<Boolean>call(huge, "previewLive"));
            Node strip = EditorFx.styled(EditorFx.<Node>call(huge, "previewHost"), "md-preview-paused");
            assertNotNull(strip, "the reader is told the preview no longer follows edits");
            assertTrue(EditorFx.textOf(strip).contains(tr("markdown.preview.paused")));
            assertTrue(EditorFx.textOf(strip).contains(tr("markdown.preview.refresh")));
            assertNull(EditorFx.styled(EditorFx.<Node>call(md, "previewHost"), "md-preview-paused"));
            huge.dispose();
            md.dispose();
            code.dispose();
            small.dispose();
        });
    }

    // --- A22: the floating controls do not cover the first lines ------------------------------------

    @Test
    void theDocumentStartsBelowTheFloatingControlsAndTaskBoxesAreNotFaded() throws Exception {
        EditorBuffer[] ref = new EditorBuffer[1];
        Stage[] stage = new Stage[1];
        EditorFx.onFx(() -> {
            ref[0] = markdown("---\ntitle: x\n---\n\n- [ ] open\n- [x] done\n");
            stage[0] = EditorFx.show(ref[0], 500, 300);
            stage[0].getScene()
                    .getStylesheets()
                    .add(EditorBuffer.class
                            .getResource("/com/editora/styles/app.css")
                            .toExternalForm());
        });
        EditorBuffer buffer = ref[0];
        open(buffer, MarkdownViewMode.PREVIEW);
        EditorFx.onFx(() -> {
            ScrollPane pane = pane(buffer);
            Node zoom = EditorFx.call(buffer, "zoomControl");
            Node first = column(buffer).getChildren().get(0);
            double controlsBottom = zoom.localToScene(zoom.getBoundsInLocal()).getMaxY();
            double firstTop = first.localToScene(first.getBoundsInLocal()).getMinY();
            assertTrue(
                    firstTop >= controlsBottom, "front matter at " + firstTop + ", controls end at " + controlsBottom);
            for (javafx.scene.control.CheckBox box : collect(pane.getContent(), javafx.scene.control.CheckBox.class)) {
                assertEquals(1.0, box.getOpacity(), 1e-9, "a task box is content, drawn at full strength");
                assertFalse(box.isFocusTraversable());
            }
            stage[0].close();
            buffer.dispose();
        });
    }

    // --- A23: a fence seen before is coloured at once ------------------------------------------------

    @Test
    void aFenceHighlightedOnceIsColouredImmediatelyTheNextTime() throws Exception {
        String md = "```java\npublic class TokenCacheProbe { int unique" + System.nanoTime() + "; }\n```\n";
        Node first = render(md, null, null);
        TextFlow flow = (TextFlow) EditorFx.callFx(() -> EditorFx.styled(first, "md-code-block"));
        EditorFx.awaitUntil(flow::getChildren, () -> flow.getChildren().size() > 1);
        Node second = render(md, null, null);
        EditorFx.onFx(() -> {
            TextFlow again = (TextFlow) EditorFx.styled(second, "md-code-block");
            assertTrue(again.getChildren().size() > 1, "tokens come from the cache: no flash of plain text");
            assertNull(textNode(again, md), "not the single uncoloured run");
        });
    }
}
