package com.editora.editor;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;

import com.editora.markdown.MarkdownTable;
import com.editora.typst.TypstMarkup;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Markdown / Typst editing actions as the buffer applies them: where the text lands, where the caret is
 * left, and that each action refuses the buffers it does not belong to (another language, read-only).
 */
@Tag("fx")
class MarkupEditingFxTest {

    private static final String TABLE = "| a | b |\n| --- | --- |\n| 1 | 2 |";

    @BeforeAll
    static void boot() throws Exception {
        EditorFx.boot();
    }

    private static EditorBuffer buffer(String language, String text) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setLanguageOverride(language);
        buffer.getArea().replaceText(text);
        buffer.getArea().moveTo(0);
        return buffer;
    }

    @Test
    void insertTablePutsTheSkeletonOnItsOwnLineWithTheCaretInTheFirstHeaderCell() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "intro");
            CodeArea area = buffer.getArea();
            area.moveTo(5);
            buffer.insertTable(3, 2);

            MarkdownTable.Nav skeleton = MarkdownTable.generate(3, 2);
            assertEquals("intro\n" + skeleton.block() + "\n", area.getText(), "a mid-line caret gets a line break");
            assertEquals(6 + skeleton.caret(), area.getCaretPosition());
            assertTrue(area.getText().contains("Column 1"), "the header names its columns");
            assertEquals(
                    4,
                    area.getText().lines().filter(l -> l.startsWith("|")).count(),
                    "header, delimiter and two body rows");

            // At a line start there is nothing to break away from.
            EditorBuffer atLineStart = buffer("markdown", "");
            atLineStart.insertTable(1, 1);
            assertEquals(
                    MarkdownTable.generate(1, 1).block() + "\n",
                    atLineStart.getArea().getText());
            buffer.dispose();
            atLineStart.dispose();
        });
    }

    @Test
    void tableActionsAreRefusedOutsideAnEditableMarkdownBuffer() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer java = buffer("java", TABLE);
            java.insertTable(2, 2);
            assertEquals(TABLE, java.getArea().getText(), "a Java buffer gets no Markdown table");
            assertFalse(java.reflowTable());
            assertFalse(java.tableAddRow());
            assertFalse(java.tableFromCsv());
            assertFalse(java.tableToCsv());
            assertFalse(java.exportTableFile("csv"));
            assertFalse(java.insertOrUpdateToc());
            assertFalse(java.trySmartLinkPaste());
            java.formatTaskList();
            java.formatBulletList();
            java.formatHeading(1);
            java.setHeadingLevel(2);
            java.formatInline("**");
            java.formatLink("https://example.org");
            assertEquals(TABLE, java.getArea().getText(), "none of the markup actions touched the Java source");

            EditorBuffer readOnly = buffer("markdown", TABLE);
            readOnly.setReadOnly(true);
            assertFalse(readOnly.canFormatMarkdown());
            assertFalse(readOnly.tableDeleteRow());
            readOnly.insertTable(2, 2);
            readOnly.setHeadingLevel(1);
            assertEquals(TABLE, readOnly.getArea().getText(), "a read-only document is left alone");
            assertFalse(readOnly.toggleComment(), "and cannot be commented either");
            java.dispose();
            readOnly.dispose();
        });
    }

    @Test
    void rowAndColumnEditsApplyToTheTableUnderTheCaretOnly() throws Exception {
        EditorFx.onFx(() -> {
            String before = "prose\n\n";
            EditorBuffer buffer = buffer("markdown", before + TABLE + "\n\nafter");
            CodeArea area = buffer.getArea();

            area.moveTo(2); // in "prose": no table there
            assertFalse(buffer.tableAddRow(), "the caret is not in a table");
            assertFalse(buffer.reflowTable());
            assertFalse(buffer.tableToCsv());
            assertEquals(before + TABLE + "\n\nafter", area.getText());

            int inLastRow = before.length() + TABLE.lastIndexOf("1");
            area.moveTo(inLastRow);
            MarkdownTable.Nav added = MarkdownTable.addRow(TABLE, inLastRow - before.length());
            assertTrue(buffer.tableAddRow());
            assertEquals(before + added.block() + "\n\nafter", area.getText(), "only the table block is rewritten");
            assertEquals(before.length() + added.caret(), area.getCaretPosition());
            assertEquals(4, added.block().lines().count(), "one more row than before");

            assertTrue(buffer.tableDeleteRow());
            assertEquals(3, tableOf(area, before).lines().count(), "the added row is gone again");

            area.moveTo(before.length() + 2);
            assertTrue(buffer.tableAddColumn());
            assertEquals(3, cells(tableOf(area, before).lines().findFirst().orElseThrow()), "a third column");
            assertTrue(buffer.tableDeleteColumn());
            assertEquals(2, cells(tableOf(area, before).lines().findFirst().orElseThrow()));

            area.moveTo(before.length() + 2);
            assertTrue(buffer.tableSetAlignment(MarkdownTable.Align.CENTER));
            String delimiter = tableOf(area, before).lines().skip(1).findFirst().orElseThrow();
            assertTrue(delimiter.matches("\\|\\s*:-+:\\s*\\|.*"), "the caret's column is centred, was: " + delimiter);
            assertTrue(area.getText().endsWith("\n\nafter"), "the text after the table is intact");
            buffer.dispose();
        });
    }

    private static String tableOf(CodeArea area, String before) {
        String text = area.getText();
        return text.substring(before.length(), text.indexOf("\n\nafter"));
    }

    private static int cells(String row) {
        return row.split("\\|", -1).length - 2;
    }

    @Test
    void reflowAlignsARaggedTableAndReportsWhenThereWasNothingToDo() throws Exception {
        EditorFx.onFx(() -> {
            String ragged = "| name | n |\n|---|---|\n| a long cell | 1 |";
            EditorBuffer buffer = buffer("markdown", ragged);
            buffer.getArea().moveTo(3);
            assertTrue(buffer.reflowTable());
            String reflowed = buffer.getArea().getText();
            assertEquals(MarkdownTable.reflow(ragged), reflowed);
            assertEquals(
                    1,
                    reflowed.lines().map(String::length).distinct().count(),
                    "every row is the same width once reflowed");

            assertTrue(buffer.reflowTable(), "an already aligned table still counts as handled");
            assertEquals(reflowed, buffer.getArea().getText());
            buffer.dispose();
        });
    }

    @Test
    void tabAndEnterInsideATableMoveBetweenCellsInsteadOfIndenting() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", TABLE);
            CodeArea area = buffer.getArea();
            area.moveTo(2); // in header cell "a"
            MarkdownTable.Nav next = MarkdownTable.tab(TABLE, 2, true);
            area.fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));
            assertEquals(next.block(), area.getText());
            assertEquals(next.caret(), area.getCaretPosition(), "Tab went to the next cell");
            assertTrue(area.getCaretPosition() > 2);

            int from = area.getCaretPosition();
            MarkdownTable.Nav back = MarkdownTable.tab(area.getText(), from, false);
            area.fireEvent(EditorFx.pressed(KeyCode.TAB, true, false));
            assertEquals(back.caret(), area.getCaretPosition(), "Shift-Tab went back");
            assertTrue(area.getCaretPosition() < from);

            // Enter on the last row appends a row rather than splitting the line.
            String text = area.getText();
            int lastRow = text.lastIndexOf("1");
            area.moveTo(lastRow);
            MarkdownTable.Nav entered = MarkdownTable.enter(text, lastRow);
            area.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals(entered.block(), area.getText());
            assertEquals(entered.caret(), area.getCaretPosition());
            assertEquals(4, area.getText().lines().count(), "a new body row");

            // The same Tab outside a table is an ordinary indent.
            EditorBuffer prose = buffer("markdown", "plain");
            prose.getArea().fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));
            assertTrue(prose.getArea().getText().endsWith("plain"));
            assertTrue(prose.getArea().getText().length() > 5, "the line was indented");

            // A selection inside a table is indented too: cell navigation needs a bare caret.
            EditorBuffer selected = buffer("markdown", TABLE);
            selected.getArea().selectRange(2, 3);
            selected.getArea().fireEvent(EditorFx.pressed(KeyCode.TAB, false, false));
            assertFalse(selected.getArea()
                    .getText()
                    .equals(MarkdownTable.tab(TABLE, 2, true).block()));
            buffer.dispose();
            prose.dispose();
            selected.dispose();
        });
    }

    @Test
    void aTableRoundTripsThroughTheClipboardAsCsv() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", TABLE);
            buffer.getArea().moveTo(3);
            assertTrue(buffer.tableToCsv());
            assertEquals(
                    "a,b\n1,2",
                    Clipboard.getSystemClipboard().getString().strip().replace("\r\n", "\n"));

            // Paste it back as a table after a line of prose: the clipboard CSV is inserted at the caret.
            EditorBuffer target = buffer("markdown", "see");
            target.getArea().moveTo(3);
            assertTrue(target.tableFromCsv());
            String table = MarkdownTable.fromCsv(
                    LineEndings.toLf(Clipboard.getSystemClipboard().getString()));
            assertEquals("see\n" + table + "\n", target.getArea().getText());
            assertEquals(target.getArea().getLength(), target.getArea().getCaretPosition(), "caret after the table");

            // A selection wins over the clipboard and is replaced in place.
            EditorBuffer inPlace = buffer("markdown", "x,y\n3,4");
            inPlace.getArea().selectAll();
            assertTrue(inPlace.tableFromCsv());
            assertEquals(MarkdownTable.fromCsv("x,y\n3,4"), inPlace.getArea().getText());
            assertTrue(inPlace.getArea().getText().contains("| x"), "the selected CSV became a pipe table");

            EditorFx.clipboard("   ");
            EditorBuffer empty = buffer("markdown", "");
            assertFalse(empty.tableFromCsv(), "a blank clipboard is nothing to convert");
            assertEquals("", empty.getArea().getText());
            buffer.dispose();
            target.dispose();
            inPlace.dispose();
            empty.dispose();
        });
    }

    @Test
    void exportingATableHandsItsCsvToTheInjectedExporter() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "text\n\n" + TABLE);
            List<String> exported = new ArrayList<>();
            buffer.getArea().moveTo(buffer.getArea().getLength() - 2);
            assertFalse(buffer.exportTableFile("csv"), "nothing is wired yet");

            buffer.setTableFileExporter((csv, format) -> exported.add(format + ":" + csv.strip()));
            assertTrue(buffer.exportTableFile("xlsx"));
            assertEquals(List.of("xlsx:" + MarkdownTable.toCsv(TABLE).strip()), exported);

            buffer.getArea().moveTo(1);
            assertFalse(buffer.exportTableFile("csv"), "the caret left the table");
            assertEquals(1, exported.size());
            buffer.dispose();
        });
    }

    @Test
    void theTableOfContentsIsInsertedOnceAndThenRegeneratedInPlace() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer none = buffer("markdown", "no headings here");
            assertFalse(none.insertOrUpdateToc(), "nothing to list");
            assertEquals("no headings here", none.getArea().getText());

            EditorBuffer buffer = buffer("markdown", "intro\n# One\n\n## Two\n");
            buffer.getArea().moveTo(5); // end of "intro"
            assertTrue(buffer.insertOrUpdateToc());
            String withToc = buffer.getArea().getText();
            assertTrue(withToc.startsWith("intro\n<!-- toc -->"), "inserted on its own line: " + withToc);
            assertTrue(withToc.contains("[One](#one)"));
            assertTrue(withToc.contains("[Two](#two)"));

            buffer.getArea().appendText("\n# Three\n");
            assertTrue(buffer.insertOrUpdateToc());
            String updated = buffer.getArea().getText();
            assertTrue(updated.contains("[Three](#three)"), "the existing block picked up the new heading");
            assertEquals(1, updated.split("<!-- toc -->", -1).length - 1, "still exactly one TOC block");
            none.dispose();
            buffer.dispose();
        });
    }

    @Test
    void headingAndLinkActionsFollowTheBuffersMarkupLanguage() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer md = buffer("markdown", "Title\nbody");
            md.setHeadingLevel(3);
            assertEquals("### Title\nbody", md.getArea().getText());
            md.setHeadingLevel(1);
            assertEquals("# Title\nbody", md.getArea().getText());

            EditorBuffer typst = buffer("typst", "Title\nbody");
            assertTrue(typst.canFormatMarkup());
            assertFalse(typst.canFormatMarkdown(), "Typst has no Markdown-only actions");
            typst.setHeadingLevel(2);
            assertEquals("== Title\nbody", typst.getArea().getText());
            typst.formatHeading(1);
            assertEquals("=== Title\nbody", typst.getArea().getText(), "demoted one level");

            typst.getArea()
                    .selectRange(
                            typst.getArea().getLength() - 4, typst.getArea().getLength());
            typst.formatLink("https://example.org");
            assertTrue(
                    typst.getArea().getText().endsWith("#link(\"https://example.org\")[body]"),
                    typst.getArea().getText());

            typst.formatTaskList(); // Markdown-only
            assertFalse(typst.getArea().getText().contains("[ ]"));
            md.dispose();
            typst.dispose();
        });
    }

    @Test
    void typstInsertionsLandAtTheCaretAndOnlyInAnEditableTypstBuffer() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer typst = buffer("typst", "= Doc");
            typst.getArea().moveTo(5);
            typst.insertTypstTable(2, 3);
            TypstMarkup.Table table = TypstMarkup.table(2, 3);
            assertEquals("= Doc\n" + table.text() + "\n", typst.getArea().getText());
            assertEquals(6 + table.caretOffset(), typst.getArea().getCaretPosition(), "caret in the first cell");
            assertEquals('[', typst.getArea().getText().charAt(typst.getArea().getCaretPosition() - 1));

            typst.getArea().moveTo(0);
            typst.insertTypstOutline();
            assertTrue(typst.getArea().getText().startsWith("#outline()= Doc"));

            AtomicInteger asked = new AtomicInteger();
            typst.insertTypstTableInteractive(); // no handler yet: nothing happens
            typst.setInsertTypstTableHandler(asked::incrementAndGet);
            typst.insertTypstTableInteractive();
            assertEquals(1, asked.get());
            typst.setReadOnly(true);
            typst.insertTypstTableInteractive();
            String frozen = typst.getArea().getText();
            typst.insertTypstTable(1, 1);
            typst.insertTypstOutline();
            assertEquals(1, asked.get(), "a read-only document does not ask for a table size");
            assertEquals(frozen, typst.getArea().getText());

            EditorBuffer md = buffer("markdown", "x");
            md.insertTypstTable(1, 1);
            md.insertTypstOutline();
            md.setInsertTypstTableHandler(asked::incrementAndGet);
            md.insertTypstTableInteractive();
            assertEquals("x", md.getArea().getText(), "Markdown gets no Typst markup");
            assertEquals(1, asked.get());

            AtomicInteger mdAsked = new AtomicInteger();
            md.insertTableInteractive(); // no handler
            md.setInsertTableHandler(mdAsked::incrementAndGet);
            md.insertTableInteractive();
            assertEquals(1, mdAsked.get());
            typst.setInsertTableHandler(mdAsked::incrementAndGet);
            typst.insertTableInteractive();
            assertEquals(1, mdAsked.get(), "and Typst gets no Markdown table prompt");
            typst.dispose();
            md.dispose();
        });
    }

    @Test
    void pastingAUrlOverASelectionMakesALink() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "see docs\nnext");
            CodeArea area = buffer.getArea();
            EditorFx.clipboard("  https://example.org/a  ");

            assertFalse(buffer.trySmartLinkPaste(), "no selection: an ordinary paste");
            area.selectRange(4, 13); // "docs\nnext" spans two lines
            assertFalse(buffer.trySmartLinkPaste(), "a multi-line selection is not link text");

            area.selectRange(4, 8);
            assertTrue(buffer.trySmartLinkPaste());
            assertEquals("see [docs](https://example.org/a)\nnext", area.getText());

            EditorFx.clipboard("not a url");
            area.selectRange(0, 3);
            assertFalse(buffer.trySmartLinkPaste(), "plain text on the clipboard is pasted as text");

            // The link button with no URL to offer leaves an empty destination to type into.
            buffer.formatLinkFromClipboard();
            assertTrue(area.getText().startsWith("[see]()"), area.getText());
            buffer.dispose();
        });
    }

    @Test
    void openLinkUnderCaretHandsTheUrlToTheInjectedOpener() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer buffer = buffer("markdown", "go [here](https://example.org/x) now");
            List<String> opened = new ArrayList<>();
            buffer.getArea().moveTo(6);
            assertTrue(buffer.openLinkUnderCaret(), "with no opener wired the link is still recognised");

            buffer.setOpenUrlHandler(opened::add);
            assertTrue(buffer.openLinkUnderCaret());
            assertEquals(List.of("https://example.org/x"), opened);
            assertEquals("https://example.org/x", buffer.linkUnderCaret());

            buffer.getArea().moveTo(buffer.getArea().getLength());
            assertFalse(buffer.openLinkUnderCaret(), "the caret is past the link");
            assertEquals(1, opened.size());

            buffer.setOpenUrlHandler(null); // back to the no-op opener
            buffer.getArea().moveTo(6);
            assertTrue(buffer.openLinkUnderCaret());
            assertEquals(1, opened.size());
            buffer.dispose();
        });
    }

    @Test
    void enterAndBackspaceManageListMarkers() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer md = buffer("markdown", "- one");
            CodeArea area = md.getArea();
            area.moveTo(5);
            area.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals("- one\n- ", area.getText(), "Enter continues the list");
            assertEquals(8, area.getCaretPosition());

            area.fireEvent(EditorFx.pressed(KeyCode.BACK_SPACE, false, false));
            assertEquals("- one\n", area.getText(), "Backspace on the empty item clears the whole marker");

            area.appendText("- ");
            area.moveTo(area.getLength());
            area.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals("- one\n", area.getText(), "Enter on an empty item ends the list");

            // Typst lists use their own markers and the same two keys.
            EditorBuffer typst = buffer("typst", "+ first");
            CodeArea t = typst.getArea();
            t.moveTo(7);
            t.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals("+ first\n+ ", t.getText());
            t.fireEvent(EditorFx.pressed(KeyCode.BACK_SPACE, false, false));
            assertEquals("+ first\n", t.getText());
            t.appendText("+ ");
            t.moveTo(t.getLength());
            t.fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals("+ first\n", t.getText());

            // Modified Enter and a read-only buffer are left to the default handling.
            EditorBuffer readOnly = buffer("markdown", "- one");
            readOnly.setReadOnly(true);
            readOnly.getArea().moveTo(5);
            readOnly.getArea().fireEvent(EditorFx.pressed(KeyCode.ENTER, false, false));
            assertEquals("- one", readOnly.getArea().getText());
            md.dispose();
            typst.dispose();
            readOnly.dispose();
        });
    }

    @Test
    void macroReplayTypesThroughTheSameAssists() throws Exception {
        EditorFx.onFx(() -> {
            EditorBuffer md = buffer("markdown", "");
            md.typeString("- a\nb");
            assertEquals("- a\n- b", md.getArea().getText(), "a replayed newline continues the list");
            md.typeString(null);
            md.typeString("");
            assertEquals("- a\n- b", md.getArea().getText());

            md.pressKey("BACK_SPACE");
            assertEquals("- a\n- ", md.getArea().getText());
            md.pressKey("NOT_A_KEY"); // a hand-edited macro step
            md.pressKey(null);
            assertEquals("- a\n- ", md.getArea().getText());

            md.getArea().replaceText("x");
            md.getArea().moveTo(0);
            md.typeChar('\t');
            assertTrue(md.getArea().getText().endsWith("x") && md.getArea().getLength() > 1, "Tab indents");

            EditorBuffer java = buffer("java", "");
            java.typeString("f(");
            assertEquals("f()", java.getArea().getText(), "the bracket is paired, as when typed by hand");
            assertEquals(2, java.getArea().getCaretPosition());
            java.typeChar(')');
            assertEquals("f()", java.getArea().getText(), "and the closer is typed over");
            assertEquals(3, java.getArea().getCaretPosition());

            java.setReadOnly(true);
            java.typeString("more");
            java.pressKey("BACK_SPACE");
            assertEquals("f()", java.getArea().getText(), "a read-only buffer ignores the replay");

            assertTrue(java.ownsKeyTarget(java.getArea()));
            assertFalse(java.ownsKeyTarget(md.getArea()), "another buffer's area is not this buffer's key target");
            assertFalse(java.ownsKeyTarget(null));
            md.dispose();
            java.dispose();
        });
    }
}
