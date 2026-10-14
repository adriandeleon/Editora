package com.editora.editor;

import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.Pane;
import javafx.stage.Stage;

import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Markdown commands and keys as a writer meets them in the editor: where the caret is afterwards, what
 * the next key does, and what the selection bar shows. {@code ‸} marks the caret and {@code «»} a selection
 * in the texts below.
 */
@Tag("fx")
class MarkdownEditingFxTest {

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer markdown(String marked) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride("markdown");
        place(buffer.getArea(), marked);
        return buffer;
    }

    private static void place(CodeArea area, String marked) {
        int caret = marked.indexOf('‸');
        int a = marked.indexOf('«');
        int b = marked.indexOf('»') - 1;
        area.replaceText(marked.replace("‸", "").replace("«", "").replace("»", ""));
        if (caret >= 0) {
            area.moveTo(caret);
        } else {
            area.selectRange(a, b);
        }
    }

    private static String shown(EditorBuffer buffer) {
        CodeArea area = buffer.getArea();
        StringBuilder sb = new StringBuilder(area.getText());
        if (area.getSelection().getLength() == 0) {
            sb.insert(area.getCaretPosition(), '‸');
        } else {
            sb.insert(area.getSelection().getEnd(), '»')
                    .insert(area.getSelection().getStart(), '«');
        }
        return sb.toString();
    }

    private static void type(EditorBuffer buffer, String text) {
        for (char c : text.toCharArray()) {
            buffer.getArea().fireEvent(EditorFx.typed(String.valueOf(c)));
        }
    }

    private static void press(EditorBuffer buffer, KeyCode code, boolean shift) {
        buffer.getArea().fireEvent(EditorFx.pressed(code, shift, false));
    }

    @Test
    void typingAfterALineCommandContinuesTheLineInsteadOfReplacingIt() throws Exception {
        EditorFx.onFx(() -> {
            // Each of these used to leave the whole line selected: the Z replaced "My title".
            EditorBuffer heading = markdown("My ti‸tle\nnext");
            heading.formatHeading(1);
            assertEquals("# My ti‸tle\nnext", shown(heading));
            type(heading, "Z");
            assertEquals("# My tiZ‸tle\nnext", shown(heading));

            EditorBuffer bullet = markdown("My it‸em");
            bullet.formatBulletList();
            type(bullet, "Z");
            assertEquals("- My itZ‸em", shown(bullet));

            EditorBuffer task = markdown("My it‸em");
            task.formatTaskList();
            type(task, "Z");
            assertEquals("- [ ] My itZ‸em", shown(task));

            EditorBuffer style = markdown("a «word» b");
            style.setHeadingLevel(2);
            assertEquals("## a «word» b", shown(style), "the selection stays on its word");

            EditorBuffer typst = new EditorBuffer();
            typst.setLanguageOverride("typst");
            place(typst.getArea(), "My ti‸tle");
            typst.formatHeading(1);
            assertEquals("= My ti‸tle", shown(typst), "the Typst commands keep the caret too");
            for (EditorBuffer b : new EditorBuffer[] {heading, bullet, task, style, typst}) {
                b.dispose();
            }
        });
    }

    @Test
    void aLineSelectionDoesNotReachTheNextLine() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("«one\n»two\n");
            buffer.formatBulletList();
            assertEquals("- one\ntwo\n", buffer.getArea().getText());
            buffer.dispose();
        });
    }

    @Test
    void boldTogglesTheWordUnderTheCaretAndUndoesInOneStep() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("hello wo‸rld");
            buffer.getArea().getUndoManager().forgetHistory();
            buffer.formatInline("**");
            assertEquals("hello **wo‸rld**", shown(buffer));
            buffer.getArea().undo();
            assertEquals("hello world", buffer.getArea().getText());
            buffer.dispose();
        });
    }

    @Test
    void enterLeavesATableFromItsEmptyLastRowAndNeverSplitsARow() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer last = markdown("| a | b |\n|---|---|\n| 1 | 2‸ |");
            press(last, KeyCode.ENTER, false);
            assertEquals("| a   | b   |\n| --- | --- |\n| 1   | 2   |\n| ‸    |     |", shown(last));
            press(last, KeyCode.ENTER, false);
            assertEquals(
                    "| a   | b   |\n| --- | --- |\n| 1   | 2   |\n‸",
                    shown(last),
                    "a second Enter is the way out, also at the end of the file");

            EditorBuffer middle = markdown("| a | b |\n|---|---|\n| 1‸ | 2 |\n| 3 | 4 |\n");
            press(middle, KeyCode.ENTER, false);
            assertEquals("| a   | b   |\n| --- | --- |\n| 1   | 2   |\n| ‸3   | 4   |\n", shown(middle));
            last.dispose();
            middle.dispose();
        });
    }

    @Test
    void tabAndShiftTabMoveAListItemInAndOut() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("- a\n- b‸\n");
            press(buffer, KeyCode.TAB, false);
            assertEquals("- a\n  - b‸\n", shown(buffer), "not four spaces in the middle of the item");
            press(buffer, KeyCode.TAB, false);
            assertEquals("- a\n  - b‸\n", shown(buffer), "already the first child: nothing happens");
            press(buffer, KeyCode.TAB, true);
            assertEquals("- a\n- b‸\n", shown(buffer));

            EditorBuffer numbered = markdown("1. a\n2. b‸\n3. c\n");
            press(numbered, KeyCode.TAB, false);
            assertEquals("1. a\n   1. b‸\n2. c\n", shown(numbered));
            buffer.dispose();
            numbered.dispose();
        });
    }

    @Test
    void enterAndBackspaceOnAnEmptyNestedItemStepOutOneLevel() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer enter = markdown("- a\n  - ‸\n");
            press(enter, KeyCode.ENTER, false);
            assertEquals("- a\n- ‸\n", shown(enter));
            press(enter, KeyCode.ENTER, false);
            assertEquals("- a\n‸\n", shown(enter));

            EditorBuffer back = markdown("- a\n  - ‸");
            press(back, KeyCode.BACK_SPACE, false);
            assertEquals("- a\n- ‸", shown(back));

            EditorBuffer ordered = markdown("1. one‸\n2. two\n");
            press(ordered, KeyCode.ENTER, false);
            assertEquals("1. one\n2. ‸\n3. two\n", shown(ordered), "the items below are renumbered");

            EditorBuffer quoted = markdown("> - a‸");
            press(quoted, KeyCode.ENTER, false);
            assertEquals("> - a\n> - ‸", shown(quoted));
            for (EditorBuffer b : new EditorBuffer[] {enter, back, ordered, quoted}) {
                b.dispose();
            }
        });
    }

    @Test
    void anEmphasisCharacterTypedOverASelectionWrapsIt() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("«word» x");
            type(buffer, "*");
            assertEquals("*«word»* x", shown(buffer));
            type(buffer, "*");
            assertEquals("**«word»** x", shown(buffer));

            EditorBuffer java = new EditorBuffer();
            java.setLanguageOverride("java");
            place(java.getArea(), "«word» x");
            type(java, "*");
            assertEquals("*‸ x", shown(java), "only in Markdown");
            buffer.dispose();
            java.dispose();
        });
    }

    @Test
    void reflowAndTocUpdateLeaveTheCaretWhereItWas() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer table = markdown("| a | b |\n|---|---|\n| long ‸cell | 2 |\n");
            assertTrue(table.reflowTable());
            assertEquals("| a         | b   |\n| --------- | --- |\n| long ‸cell | 2   |\n", shown(table));

            EditorBuffer toc = markdown("# T\n<!-- toc -->\nold\n<!-- /toc -->\n## A‸\n");
            assertTrue(toc.insertOrUpdateToc());
            assertEquals("# T\n<!-- toc -->\n- [T](#t)\n  - [A](#a)\n<!-- /toc -->\n## A‸\n", shown(toc));
            table.dispose();
            toc.dispose();
        });
    }

    @Test
    void pastingAUrlOverALinkOrAUrlDoesNotNestALink() throws Exception {
        EditorFx.onFx(() -> {
            EditorFx.clipboard("https://example.com/new");
            EditorBuffer link = markdown("[«docs»](http://x)");
            assertFalse(link.trySmartLinkPaste(), "already a link's text: an ordinary paste");
            EditorBuffer url = markdown("«http://old.example»");
            assertFalse(url.trySmartLinkPaste(), "a URL is replaced by the pasted one");
            EditorBuffer plain = markdown("see «docs» now");
            assertTrue(plain.trySmartLinkPaste());
            assertEquals(
                    "see [docs](https://example.com/new) now", plain.getArea().getText());
            EditorBuffer csv = markdown("‸");
            EditorFx.clipboard("Just a copied sentence, nothing tabular.");
            assertFalse(csv.tableFromCsv(), "one line of text is not a table");
            for (EditorBuffer b : new EditorBuffer[] {link, url, plain, csv}) {
                b.dispose();
            }
        });
    }

    @Test
    void theFormatBarShowsWhatTheSelectionIs() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("‸## Head\n\nplain **bold** text\n");
            Stage stage = EditorFx.show(buffer, 700, 420);
            CodeArea area = buffer.getArea();
            MarkdownFormatBar bar = new MarkdownFormatBar(buffer);
            Pane node = (Pane) bar.node();
            ComboBox<?> style =
                    (ComboBox<?>) node.getChildren().get(node.getChildren().size() - 1);
            Button bold = (Button) node.getChildren().get(0);
            javafx.css.PseudoClass selected = javafx.css.PseudoClass.getPseudoClass("selected");

            area.selectRange(3, 7); // "Head"
            assertTrue(MarkdownFormatBar.wanted(area));
            bar.showFor(area, EditorFx.field(buffer, "root"));
            assertEquals(2, style.getValue(), "the Style box says what the line is");
            assertFalse(bold.getPseudoClassStates().contains(selected));

            int plain = area.getText().indexOf("plain");
            area.selectRange(plain, plain + 5);
            bar.showFor(area, EditorFx.field(buffer, "root"));
            assertEquals(0, style.getValue(), "it used to keep the last level picked");
            // Picking the level the box showed last time is a change again, so it applies.
            @SuppressWarnings("unchecked")
            ComboBox<Integer> box = (ComboBox<Integer>) style;
            box.setValue(2);
            assertTrue(area.getText().contains("## plain **bold** text"), area.getText());

            int b = area.getText().indexOf("bold");
            area.selectRange(b, b + 4);
            bar.showFor(area, EditorFx.field(buffer, "root"));
            assertTrue(bold.getPseudoClassStates().contains(selected), "the selection is bold: the button is lit");

            assertNotNull(bold.getAccessibleText(), "an icon-only button has a name");
            for (Node n : node.getChildren()) {
                assertFalse(n.isFocusTraversable(), "the bar never takes the keyboard");
            }

            area.moveTo(0);
            assertFalse(MarkdownFormatBar.wanted(area), "no selection");
            int blank = area.getText().indexOf(" text");
            area.selectRange(blank, blank + 1);
            assertFalse(MarkdownFormatBar.wanted(area), "a selected blank is nothing to format");
            stage.hide();
            buffer.dispose();
        });
    }

    @Test
    void aTooltipNamesTheKeyBindingAndTheBarWrapsInANarrowPane() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("some «words» to select\n");
            buffer.markdown().configure(1, 6, id -> id.equals("markdown.bold") ? "C-c C-s b" : null);
            Stage stage = EditorFx.show(buffer, 260, 300);
            javafx.scene.layout.AnchorPane root = EditorFx.field(buffer, "root");
            MarkdownFormatBar bar = new MarkdownFormatBar(buffer);
            Pane node = (Pane) bar.node();
            bar.showFor(buffer.getArea(), root);
            Button bold = (Button) node.getChildren().get(0);
            Button italic = (Button) node.getChildren().get(1);
            assertTrue(
                    bold.getTooltip().getText().endsWith("C-c C-s b"),
                    bold.getTooltip().getText());
            assertFalse(italic.getTooltip().getText().contains("·"), "unbound: just the name");

            // 260 px is narrower than the bar's single row: it used to run out of the pane.
            double rowHeight = bold.prefHeight(-1);
            assertTrue(node.getWidth() <= root.getWidth() + 0.5, node.getWidth() + " in " + root.getWidth());
            assertTrue(node.getHeight() > rowHeight * 1.5, "a second row instead: " + node.getHeight());
            assertTrue(node.getLayoutX() >= 0 && node.getLayoutX() + node.getWidth() <= root.getWidth() + 0.5);
            stage.hide();
            buffer.dispose();
        });
    }

    @Test
    void tooltipHintsAndButtonCommands() {
        assertEquals("Bold (**)  ·  Ctrl+B", MarkdownFormatBar.hint("Bold (**)", "Ctrl+B"));
        assertEquals("Link", MarkdownFormatBar.hint("Link", null));
        assertEquals("markdown.taskList", MarkdownFormatBar.commandFor("tooltip.markdown.taskList", false));
        assertEquals("typst.emph", MarkdownFormatBar.commandFor("tooltip.markdown.italic", true));
        assertEquals("typst.raw", MarkdownFormatBar.commandFor("tooltip.markdown.code", true));
        assertEquals("typst.insertTable", MarkdownFormatBar.commandFor("tooltip.markdown.insertTable", true));
    }

    @Test
    void theTableOfContentsListsTheConfiguredLevels() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("# T\n\n‸\n\n## A\n### B\n#### C\n");
            buffer.markdown().configure(2, 3, id -> null);
            assertTrue(buffer.insertOrUpdateToc());
            String text = buffer.getArea().getText();
            assertTrue(text.contains("<!-- toc -->\n- [A](#a)\n  - [B](#b)\n<!-- /toc -->"), text);
            buffer.markdown().configure(1, 6, id -> null);
            assertTrue(buffer.insertOrUpdateToc(), "the same block, regenerated with the new levels");
            assertTrue(buffer.getArea().getText().contains("- [T](#t)\n  - [A](#a)\n    - [B](#b)\n      - [C](#c)"));
            buffer.dispose();
        });
    }

    @Test
    void anOrderedListIsRenumberedAfterAMoveAsPartOfTheMove() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("1. a\n2. b‸\n3. c\n");
            CodeArea area = buffer.getArea();
            area.getUndoManager().forgetHistory();
            area.replaceText(0, 9, "2. b\n1. a"); // what "move line up" leaves
            area.moveTo(4);
            buffer.markdown().renumberListAtCaret();
            assertEquals("1. b‸\n2. a\n3. c\n", shown(buffer));
            area.undo();
            assertEquals("1. a\n2. b\n3. c\n", area.getText(), "one undo takes back the move and its renumbering");
            buffer.dispose();
        });
    }

    // ---- feature-gap pass ----

    @Test
    void theNewLineCommandsAreOneUndoStepEach() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("«a\nb»\n");
            CodeArea area = buffer.getArea();
            area.getUndoManager().forgetHistory();
            buffer.markdown().formatOrderedList();
            assertEquals("«1. a\n2. b»\n", shown(buffer));
            area.undo();
            assertEquals("a\nb\n", area.getText());

            place(area, "qu‸ote\n");
            buffer.markdown().formatBlockquote();
            assertEquals("> qu‸ote\n", shown(buffer));

            place(area, "«int x;»\n");
            buffer.markdown().formatCodeFence();
            assertEquals("```‸\nint x;\n```\n", shown(buffer), "the caret waits for the language");

            place(area, "- [ ] ta‸sk\n");
            assertTrue(buffer.markdown().toggleTaskDone());
            assertEquals("- [x] ta‸sk\n", shown(buffer));
            place(area, "pla‸in\n");
            assertFalse(buffer.markdown().toggleTaskDone());

            place(area, "A claim‸ here.\n");
            area.getUndoManager().forgetHistory();
            buffer.markdown().insertFootnote();
            assertEquals("A claim[^1] here.\n\n[^1]: ‸", shown(buffer));
            area.undo();
            assertEquals("A claim here.\n", area.getText());

            place(area, "text‸\nmore\n");
            buffer.markdown().insertHorizontalRule();
            assertEquals("text\n\n---\n‸\nmore\n", shown(buffer));

            place(area, "on ‸\n");
            buffer.markdown().insertDate();
            assertTrue(area.getText().matches("on \\d{4}-\\d{2}-\\d{2}\n"), area.getText());

            place(area, "3. a\n1. b‸\n");
            assertTrue(buffer.markdown().renumberList(false));
            assertEquals("1. a\n2. b‸\n", shown(buffer));
            assertFalse(buffer.markdown().renumberList(false), "in order now");
            buffer.dispose();
        });
    }

    @Test
    void theNewTableToolsMoveSortAndDelete() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("| n |\n|---|\n| 10 |\n| ‸9 |\n\nafter\n");
            assertTrue(buffer.markdown().tableMoveRow(true));
            assertEquals("| n   |\n| --- |\n| ‸9   |\n| 10  |\n\nafter\n", shown(buffer));
            assertFalse(buffer.markdown().tableMoveRow(true), "not through the header");
            assertTrue(buffer.markdown().tableSort(false));
            assertEquals("| ‸n   |\n| --- |\n| 10  |\n| 9   |\n\nafter\n", shown(buffer));
            assertTrue(buffer.markdown().tableAddColumnLeft());
            assertTrue(buffer.markdown().tableMoveColumn(false));
            assertFalse(buffer.markdown().tableMoveColumn(false));
            assertTrue(buffer.markdown().tableAddRowAbove());
            assertTrue(buffer.markdown().tableDelete());
            assertEquals("‸\n\nafter\n", shown(buffer));
            assertFalse(buffer.markdown().tableDelete());
            buffer.dispose();
        });
    }

    @Test
    void linkToolsEditRemoveAndConvert() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("see [do‸cs](http://old) now\n");
            buffer.markdown().setLink("the docs", "http://new");
            assertEquals("see [the docs](http://new)‸ now\n", shown(buffer));
            buffer.getArea().moveTo(6);
            assertTrue(buffer.markdown().convertLink(false));
            assertEquals("see [the docs][1]‸ now\n\n[1]: http://new\n", shown(buffer));
            buffer.getArea().moveTo(6);
            assertFalse(buffer.markdown().convertLink(false), "a reference already");
            assertTrue(buffer.markdown().convertLink(true));
            assertEquals("see [the docs](http://new)‸ now\n", shown(buffer));
            buffer.getArea().moveTo(6);
            assertTrue(buffer.markdown().removeLink());
            assertEquals("see «the docs» now\n", shown(buffer));
            assertFalse(buffer.markdown().removeLink());

            place(buffer.getArea(), "make «this» a link\n");
            assertEquals("this", buffer.markdown().linkTextCandidate());
            buffer.markdown().setLink("this", "x.md");
            assertEquals("make [this](x.md)‸ a link\n", shown(buffer));
            buffer.dispose();
        });
    }

    @Test
    void emphasisMarkersArePairedAndSmartTypographyIsOptIn() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("a ‸\n");
            type(buffer, "**b**");
            assertEquals("a **b**‸\n", shown(buffer));
            place(buffer.getArea(), "‸\n");
            type(buffer, "* item");
            assertEquals("* item‸\n", shown(buffer), "a bullet is not emphasis");
            place(buffer.getArea(), "```\n‸\n```\n");
            type(buffer, "a *");
            assertEquals("```\na *‸\n```\n", shown(buffer), "not in a code block");

            place(buffer.getArea(), "‸\n");
            type(buffer, "\"q\" -- ...");
            assertEquals("\"q\" -- ...‸\n", shown(buffer).replace("\"\"", "\""), "typography is off by default");
            buffer.markdown().configureWriting(true, false, false);
            place(buffer.getArea(), "‸\n");
            type(buffer, "\"q\" it's a -- b...");
            assertEquals("“q” it’s a – b…‸\n", shown(buffer));
            place(buffer.getArea(), "`‸`\n");
            type(buffer, "a -- b");
            assertEquals("`a -- b‸`\n", shown(buffer), "never in code");
            buffer.dispose();
        });
    }

    @Test
    void pasteAsMarkdownConvertsStructuredHtmlButNotPlainRichText() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("‸");
            javafx.scene.input.ClipboardContent cells = new javafx.scene.input.ClipboardContent();
            cells.putHtml("<table><tr><td>Name</td><td>Qty</td></tr><tr><td>a</td><td>1</td></tr></table>");
            cells.putString("Name\tQty\na\t1");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(cells);
            assertTrue(buffer.markdown().pasteAsMarkdown(false), "a table: the ordinary paste converts it");
            assertEquals(
                    "| Name | Qty |\n| ---- | --- |\n| a    | 1   |",
                    buffer.getArea().getText());

            javafx.scene.input.ClipboardContent styled = new javafx.scene.input.ClipboardContent();
            styled.putHtml("<span style=\"color:#333\">just <b>text</b></span>");
            styled.putString("just text");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(styled);
            assertFalse(buffer.markdown().pasteAsMarkdown(false), "styled text pastes as the text it is");
            buffer.getArea().replaceText("");
            assertTrue(buffer.markdown().pasteAsMarkdown(true), "the command converts whatever HTML there is");
            assertEquals("just **text**", buffer.getArea().getText());

            javafx.scene.input.ClipboardContent markdown = new javafx.scene.input.ClipboardContent();
            markdown.putHtml("<ul><li>a</li></ul>");
            markdown.putString("- a");
            javafx.scene.input.Clipboard.getSystemClipboard().setContent(markdown);
            assertFalse(buffer.markdown().pasteAsMarkdown(false), "the plain text is Markdown already");

            EditorFx.clipboard("x\ty\n1\t2");
            assertFalse(buffer.markdown().pasteAsMarkdown(false));
            buffer.getArea().replaceText("");
            assertTrue(buffer.markdown().pasteAsMarkdown(true), "tab-separated text becomes a table on request");
            assertTrue(buffer.getArea().getText().startsWith("| x   | y   |"));
            EditorFx.clipboard("plain");
            assertFalse(buffer.markdown().pasteAsMarkdown(true));
            buffer.dispose();
        });
    }

    @Test
    void theTocFollowsItsStyleAndIsRefreshedForASave() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("# Doc\n\n<!-- toc -->\n<!-- /toc -->\n\n## A‸\n## B\n");
            buffer.markdown().configureWriting(false, true, true);
            buffer.getArea().getUndoManager().forgetHistory();
            assertTrue(buffer.markdown().updateTocForSave());
            assertEquals(
                    "# Doc\n\n<!-- toc -->\n1. [A](#a)\n2. [B](#b)\n<!-- /toc -->\n\n## A‸\n## B\n", shown(buffer));
            assertFalse(buffer.markdown().updateTocForSave(), "up to date: the buffer is not touched");
            buffer.getArea().undo();
            assertEquals(
                    "# Doc\n\n<!-- toc -->\n<!-- /toc -->\n\n## A\n## B\n",
                    buffer.getArea().getText());

            EditorBuffer none = markdown("# Doc\n‸");
            assertFalse(none.markdown().updateTocForSave(), "a save never inserts a table of contents");
            EditorBuffer readOnly = markdown("<!-- toc -->\n<!-- /toc -->\n# T‸\n");
            readOnly.setReadOnly(true);
            assertFalse(readOnly.markdown().updateTocForSave());
            for (EditorBuffer b : new EditorBuffer[] {buffer, none, readOnly}) {
                b.dispose();
            }
        });
    }

    @Test
    void deletingSelectedItemsLeavesAnOrderedListInSequence() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("1. a\n«2. b\n3. c\n»4. d\n");
            CodeArea area = buffer.getArea();
            area.getUndoManager().forgetHistory();
            press(buffer, KeyCode.DELETE, false);
            assertEquals("1. a\n‸2. d\n", shown(buffer));
            area.undo();
            assertEquals("1. a\n2. b\n3. c\n4. d\n", area.getText(), "one undo: the deletion and the renumbering");

            place(area, "1. a\n2. b«\n3. c»\n4. d\n");
            press(buffer, KeyCode.BACK_SPACE, false);
            assertEquals("1. a\n2. b‸\n3. d\n", shown(buffer));

            place(area, "1. a\n«5. b\n»9. c\n");
            press(buffer, KeyCode.DELETE, false);
            assertEquals("1. a\n‸9. c\n", shown(buffer), "numbered by hand: left alone");

            place(area, "- a\n«- b\n»- c\n");
            press(buffer, KeyCode.DELETE, false);
            assertEquals("- a\n‸- c\n", shown(buffer), "bullets: the ordinary deletion");
            buffer.dispose();
        });
    }

    @Test
    void theLinkToolsUnderstandAWikiLink() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = markdown("see [[Other No‸te#Heading|label]] now\n");
            assertArrayEquals(
                    new String[] {"label", "Other Note#Heading", "wiki"},
                    buffer.markdown().linkForEditing());
            buffer.markdown().setLink("new label", "Third Note");
            assertEquals("see [[Third Note|new label]]‸ now\n", shown(buffer), "edited, and still a wiki link");

            buffer.getArea().moveTo(8);
            assertEquals(0, buffer.markdown().convertWikiLink());
            assertEquals("see [new label](<Third Note.md>)‸ now\n", shown(buffer));
            buffer.getArea().moveTo(8);
            assertEquals(0, buffer.markdown().convertWikiLink());
            assertEquals("see [[Third Note|new label]]‸ now\n", shown(buffer), "and back");

            buffer.markdown()
                    .setWikiResolver(target -> "notes/" + target.toLowerCase().replace(' ', '-') + ".md");
            buffer.getArea().moveTo(8);
            assertEquals(0, buffer.markdown().convertWikiLink());
            assertEquals("see [new label](notes/third-note.md)‸ now\n", shown(buffer), "through the window's resolver");

            place(buffer.getArea(), "an [ext‸ernal](https://example.com) link and plain\n");
            assertEquals(2, buffer.markdown().convertWikiLink(), "a wiki link cannot name a URL");
            buffer.getArea().moveTo(buffer.getArea().getLength() - 2);
            assertEquals(1, buffer.markdown().convertWikiLink(), "no link here");

            place(buffer.getArea(), "drop [[No‸te|the note]] here\n");
            assertTrue(buffer.markdown().removeLink());
            assertEquals("drop «the note» here\n", shown(buffer));

            // "Link" on a link edits that link instead of nesting a new one in it.
            int[] opened = {0};
            buffer.markdown().setEditLinkHandler(() -> opened[0]++);
            place(buffer.getArea(), "on [[a w‸iki]] link\n");
            buffer.markdown().formatLinkFromClipboard();
            assertEquals(1, opened[0]);
            assertEquals("on [[a wiki]] link\n", buffer.getArea().getText());
            buffer.dispose();
        });
    }

    @Test
    void theBarKeepsOffTheSelectedText() {
        // caret line at y 100–120 in a pane 400 tall, bar 40 tall
        assertEquals(56, MarkdownFormatBar.placeY(100, 120, 40, 400, false), "above the caret line");
        assertEquals(124, MarkdownFormatBar.placeY(100, 120, 40, 400, true), "below it when the selection is above");
        assertEquals(24, MarkdownFormatBar.placeY(0, 20, 40, 400, false), "no room above: below");
        assertEquals(336, MarkdownFormatBar.placeY(380, 400, 40, 400, true), "no room below: above");
    }

    @Test
    void menuLabelsDropTheCommandGroup() {
        assertEquals("Bold", MarkdownMenus.label("Markdown: Bold"));
        assertEquals("Insérer…", MarkdownMenus.label("Tableau : Insérer…"));
        assertEquals("Aus CSV importieren…", MarkdownMenus.label("Tabelle: aus CSV importieren…"));
        assertEquals("No group", MarkdownMenus.label("No group"));
    }
}
