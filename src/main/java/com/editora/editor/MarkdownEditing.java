package com.editora.editor;

import java.util.function.BiFunction;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.markdown.BlockInsert;
import com.editora.markdown.MarkdownEdit;
import com.editora.markdown.MarkdownHeading;
import com.editora.markdown.MarkdownInline;
import com.editora.markdown.MarkdownLines;
import com.editora.markdown.MarkdownTable;
import com.editora.markdown.MarkdownToc;
import com.editora.typst.TypstMarkup;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * The Markdown (and shared Typst) editing commands of one {@link EditorBuffer}, applied to its focused
 * view: inline markers, links, headings, lists, tables, the table of contents, and what Enter, Tab and
 * Backspace do inside a list item or a table. The pure cores in {@code com.editora.markdown} decide the
 * edit; this class reads the view, applies the edit as one undo step and places the caret.
 *
 * <p>Split out of {@code EditorBuffer}, which keeps one-line delegates for its callers.
 */
public final class MarkdownEditing {

    private final EditorBuffer buffer;
    private Runnable insertTableHandler;
    private java.util.function.BiConsumer<String, String> tableFileExporter;

    MarkdownEditing(EditorBuffer buffer) {
        this.buffer = buffer;
    }

    private CodeArea view() {
        return buffer.getFocusedArea() != null ? buffer.getFocusedArea() : buffer.getArea();
    }

    private void apply(MarkdownEdit edit) {
        if (edit == null) {
            return;
        }
        CodeArea a = view();
        if (edit.from() != edit.to() || !edit.replacement().isEmpty()) {
            a.replaceText(edit.from(), edit.to(), edit.replacement());
        }
        a.selectRange(edit.selStart(), edit.selEnd());
        a.requestFollowCaret();
        a.requestFocus();
    }

    /**
     * A Typst line edit for a caret-only command: the Typst cores still answer "select the rewritten line",
     * which would let the next key typed replace it, so the caret is put back on its text instead.
     */
    private void applyKeepingCaret(MarkdownEdit edit, int caret, boolean collapsed) {
        if (edit != null && collapsed && edit.replacement().indexOf('\n') < 0) {
            int at = Math.max(edit.from(), caret + edit.replacement().length() - (edit.to() - edit.from()));
            apply(new MarkdownEdit(edit.from(), edit.to(), edit.replacement(), at, at));
        } else {
            apply(edit);
        }
    }

    // ---- inline ----

    /** Toggles an inline marker ({@code **}/{@code *}/{@code ~~}/{@code `}) on the selection or the caret's word. */
    public void formatInline(String marker) {
        if (!buffer.canFormatMarkup()) {
            return;
        }
        CodeArea a = view();
        var sel = a.getSelection();
        apply(
                buffer.isTypst()
                        ? MarkdownInline.toggleSymmetric(a.getText(), sel.getStart(), sel.getEnd(), marker)
                        : MarkdownInline.toggle(a.getText(), sel.getStart(), sel.getEnd(), marker));
    }

    /** Whether the selection (or the caret's word) carries {@code marker} — the format bar's button state. */
    boolean isMarked(String marker) {
        CodeArea a = view();
        var sel = a.getSelection();
        return !buffer.isTypst() && MarkdownInline.isMarked(a.getText(), sel.getStart(), sel.getEnd(), marker);
    }

    /** The heading level (0 = none) of the caret's line — the format bar's Style value. */
    int headingLevel() {
        CodeArea a = view();
        if (!buffer.isTypst()) {
            return MarkdownHeading.levelAt(a.getText(), a.getCaretPosition());
        }
        String line = a.getText(a.getCurrentParagraph()).stripLeading();
        int n = 0;
        while (n < line.length() && line.charAt(n) == '=') {
            n++;
        }
        return n > 0 && (n == line.length() || line.charAt(n) == ' ') ? Math.min(n, 6) : 0;
    }

    /** Wraps the selection as a link with {@code url}; a blank {@code url} leaves the caret in the empty slot. */
    public void formatLink(String url) {
        if (!buffer.canFormatMarkup()) {
            return;
        }
        CodeArea a = view();
        var sel = a.getSelection();
        apply(
                buffer.isTypst()
                        ? TypstMarkup.link(a.getText(), sel.getStart(), sel.getEnd(), url)
                        : MarkdownInline.link(a.getText(), sel.getStart(), sel.getEnd(), url));
    }

    /** Link button / make-link command: use a clipboard URL when present, else an empty link. */
    public void formatLinkFromClipboard() {
        String clip = Clipboard.getSystemClipboard().getString();
        formatLink(MarkdownInline.isUrl(clip) ? clip.strip() : "");
    }

    /**
     * Smart paste: a URL on the clipboard over a selection of plain text makes the selection a link. False —
     * the caller pastes normally — when the selection is itself a URL, already a link's text, inside inline
     * code or spans lines.
     */
    public boolean trySmartLinkPaste() {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        var sel = a.getSelection();
        String clip = Clipboard.getSystemClipboard().getString();
        if (sel.getLength() == 0
                || !MarkdownInline.isUrl(clip)
                || !MarkdownInline.wrapsAsLink(a.getText(), sel.getStart(), sel.getEnd())) {
            return false;
        }
        formatLink(clip.strip());
        return true;
    }

    // ---- lines ----

    /** Promote ({@code delta<0}) / demote ({@code delta>0}) the heading level of the selected line(s). */
    public void formatHeading(int delta) {
        if (!buffer.canFormatMarkup()) {
            return;
        }
        CodeArea a = view();
        var sel = a.getSelection();
        if (buffer.isTypst()) {
            applyKeepingCaret(
                    TypstMarkup.heading(a.getText(), sel.getStart(), sel.getEnd(), delta),
                    a.getCaretPosition(),
                    sel.getLength() == 0);
        } else {
            apply(MarkdownHeading.apply(a.getText(), a.getAnchor(), a.getCaretPosition(), delta));
        }
    }

    /** Sets the selected line(s) to an absolute heading level (0 = Normal). */
    public void setHeadingLevel(int level) {
        if (!buffer.canFormatMarkup()) {
            return;
        }
        CodeArea a = view();
        var sel = a.getSelection();
        if (buffer.isTypst()) {
            applyKeepingCaret(
                    TypstMarkup.setHeadingLevel(a.getText(), sel.getStart(), sel.getEnd(), level),
                    a.getCaretPosition(),
                    sel.getLength() == 0);
        } else {
            apply(MarkdownHeading.setLevel(a.getText(), a.getAnchor(), a.getCaretPosition(), level));
        }
    }

    /** Toggles a {@code "- "} bullet on the selected line(s) (valid in both Markdown and Typst). */
    public void formatBulletList() {
        if (buffer.canFormatMarkup()) {
            CodeArea a = view();
            apply(MarkdownLines.toggleBullet(a.getText(), a.getAnchor(), a.getCaretPosition()));
        }
    }

    /** Toggles a GFM task-list checkbox ({@code "- [ ] "}) on the selected line(s). */
    public void formatTaskList() {
        if (buffer.canFormatMarkdown()) {
            CodeArea a = view();
            apply(MarkdownLines.toggleTask(a.getText(), a.getAnchor(), a.getCaretPosition()));
        }
    }

    // ---- keys ----

    /**
     * Enter in a Markdown buffer: inside a table (next row, a new row, or out of the table from its empty
     * last row) or a list/quote item (continue it, or step out of an empty one). False when the caret's line
     * is neither, so the caller inserts its ordinary newline.
     */
    boolean enter(CodeArea a) {
        String line = a.getText(a.getCurrentParagraph());
        int caret = a.getCaretPosition();
        if (MarkdownTable.isRow(line)) {
            String text = a.getText();
            int[] tb = MarkdownTable.blockBounds(text, caret);
            if (tb != null && place(a, tb, MarkdownTable.enter(text.substring(tb[0], tb[1]), caret - tb[0]))) {
                return true;
            }
        }
        if (MarkdownLines.markerLength(line) == 0) {
            return false; // the line alone says "not an item": no need for the document
        }
        MarkdownEdit e = MarkdownLines.enter(a.getText(), caret);
        if (e == null) {
            return false;
        }
        a.replaceText(e.from(), e.to(), e.replacement());
        a.moveTo(e.selStart());
        a.requestFollowCaret();
        return true;
    }

    /** Backspace at the end of an empty list/quote item steps out one level; false anywhere else. */
    boolean backspace(CodeArea a) {
        String line = a.getText(a.getCurrentParagraph());
        if (MarkdownLines.emptyMarkerDeleteLength(line, a.getCaretColumn()) == 0) {
            return false;
        }
        MarkdownEdit e = MarkdownLines.backspace(a.getText(), a.getCaretPosition());
        if (e == null) {
            return false;
        }
        a.replaceText(e.from(), e.to(), e.replacement());
        a.moveTo(e.selStart());
        return true;
    }

    /**
     * Tab / Shift-Tab in a Markdown buffer: between the cells of a table (a selection inside the table does
     * not turn it into "indent the line"), or — with nothing selected — a list item in or out one level.
     */
    boolean tab(CodeArea a, boolean forward) {
        String line = a.getText(a.getCurrentParagraph());
        boolean row = MarkdownTable.isRow(line);
        boolean item = !row && a.getSelection().getLength() == 0 && MarkdownLines.markerLength(line) > 0;
        if (!row && !item) {
            return false;
        }
        String text = a.getText();
        int caret = a.getCaretPosition();
        if (row) {
            int[] tb = MarkdownTable.blockBounds(text, caret);
            if (tb == null
                    || a.getSelection().getStart() < tb[0]
                    || a.getSelection().getEnd() > tb[1]) {
                return false;
            }
            return place(a, tb, MarkdownTable.tab(text.substring(tb[0], tb[1]), caret - tb[0], forward));
        }
        MarkdownEdit e = MarkdownLines.indentItem(text, caret, !forward);
        if (e == null) {
            return false;
        }
        if (!e.replacement().isEmpty() || e.from() != e.to()) {
            a.replaceText(e.from(), e.to(), e.replacement());
            a.moveTo(e.selStart());
        }
        return true;
    }

    /**
     * An emphasis character ({@code *}, {@code _}, {@code ~}) typed over a single-line selection wraps the
     * selection instead of replacing it; false (ordinary typing) for anything else.
     */
    boolean wrapSelection(CodeArea a, char c) {
        String sel = a.getSelectedText();
        if ((c != '*' && c != '_' && c != '~') || sel.isBlank() || sel.indexOf('\n') >= 0) {
            return false;
        }
        int s = a.getSelection().getStart();
        a.replaceText(s, a.getSelection().getEnd(), c + sel + c);
        a.selectRange(s + 1, s + 1 + sel.length());
        return true;
    }

    // ---- tables ----

    /** Replaces the table at {@code bounds} with {@code nav}'s block and moves the caret; false for null. */
    private boolean place(CodeArea a, int[] bounds, MarkdownTable.Nav nav) {
        if (nav == null) {
            return false;
        }
        if (!nav.block().equals(a.getText(bounds[0], bounds[1]))) {
            a.replaceText(bounds[0], bounds[1], nav.block());
        }
        a.moveTo(bounds[0] + Math.max(0, Math.min(nav.caret(), nav.block().length())));
        a.requestFollowCaret();
        return true;
    }

    /** Whether the caret is on a GFM table (header, delimiter or body row). */
    public boolean caretInTable() {
        CodeArea a = view();
        return MarkdownTable.blockBounds(a.getText(), a.getCaretPosition()) != null;
    }

    private boolean onTable(BiFunction<String, Integer, MarkdownTable.Nav> op) {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        String text = a.getText();
        int caret = a.getCaretPosition();
        int[] b = MarkdownTable.blockBounds(text, caret);
        if (b == null || !place(a, b, op.apply(text.substring(b[0], b[1]), caret - b[0]))) {
            return false;
        }
        a.requestFocus();
        return true;
    }

    /** Reflows the GFM table around the caret (which stays in its cell); false when not on a table. */
    public boolean reflowTable() {
        return onTable(MarkdownTable::reflowAt);
    }

    public boolean tableAddRow() {
        return onTable(MarkdownTable::addRow);
    }

    /** False when not on a table, and on the header or delimiter row (those cannot be deleted). */
    public boolean tableDeleteRow() {
        return onTable(MarkdownTable::deleteRow);
    }

    public boolean tableAddColumn() {
        return onTable(MarkdownTable::addColumn);
    }

    /** False when not on a table, and when only one column is left. */
    public boolean tableDeleteColumn() {
        return onTable(MarkdownTable::deleteColumn);
    }

    public boolean tableSetAlignment(MarkdownTable.Align align) {
        return onTable((block, caret) -> MarkdownTable.setAlignment(block, caret, align));
    }

    /** Inserts {@code block} at the caret on lines of its own; returns the offset it starts at. */
    private int insertBlock(CodeArea a, String block) {
        int caret = a.getCaretPosition();
        BlockInsert.Framed f = BlockInsert.at(a.getText(), caret, block);
        a.replaceText(caret, caret, f.text());
        return caret + f.blockStart();
    }

    /** Inserts a fresh, aligned GFM table ({@code rowsTotal} rows incl. header × {@code cols}) at the caret. */
    public void insertTable(int rowsTotal, int cols) {
        if (!buffer.canFormatMarkdown()) {
            return;
        }
        CodeArea a = view();
        MarkdownTable.Nav g = MarkdownTable.generate(rowsTotal, cols, n -> tr("table.header.column", n));
        int start = insertBlock(a, g.block());
        a.moveTo(start + Math.max(0, Math.min(g.caret(), g.block().length())));
        a.requestFollowCaret();
        a.requestFocus();
    }

    /** Shows the interactive table-size picker (wired by the controller); a no-op until injected. */
    public void insertTableInteractive() {
        if (buffer.canFormatMarkdown() && insertTableHandler != null) {
            insertTableHandler.run();
        }
    }

    public void setInsertTableHandler(Runnable handler) {
        this.insertTableHandler = handler;
    }

    /**
     * Converts CSV into a Markdown table: a non-blank selection is treated as CSV and replaced in place,
     * else the clipboard's CSV is inserted as a table at the caret. False when there is nothing tabular: no
     * selection and a clipboard that is empty or holds a single line (a copied sentence is not a table).
     */
    public boolean tableFromCsv() {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        String sel = a.getSelectedText();
        boolean fromSelection = sel != null && !sel.isBlank();
        String csv = fromSelection
                ? sel
                : LineEndings.toLf(Clipboard.getSystemClipboard().getString());
        String table = csv == null || csv.isBlank() ? null : MarkdownTable.fromCsv(csv);
        if (table == null
                || (!fromSelection && table.chars().filter(c -> c == '\n').count() < 2)) {
            return false;
        }
        if (fromSelection) {
            var s = a.getSelection();
            a.replaceText(s.getStart(), s.getEnd(), table);
            a.moveTo(s.getStart() + table.length());
        } else {
            a.moveTo(insertBlock(a, table) + table.length() + 1);
        }
        a.requestFollowCaret();
        a.requestFocus();
        return true;
    }

    /** The caret's table as CSV, or null when the caret is not on one. */
    private String tableCsv() {
        if (!buffer.canFormatMarkdown()) {
            return null;
        }
        CodeArea a = view();
        int[] b = MarkdownTable.blockBounds(a.getText(), a.getCaretPosition());
        return b == null ? null : MarkdownTable.toCsv(a.getText().substring(b[0], b[1]));
    }

    /** Copies the caret's Markdown table to the system clipboard as CSV; false when not on a table. */
    public boolean tableToCsv() {
        String csv = tableCsv();
        if (csv == null) {
            return false;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(csv);
        Clipboard.getSystemClipboard().setContent(content);
        return true;
    }

    /** Injected file-export for the caret's table: {@code (csvText, format)}, format ∈ csv/xlsx/ods. */
    public void setTableFileExporter(java.util.function.BiConsumer<String, String> exporter) {
        this.tableFileExporter = exporter;
    }

    /** Exports the caret's table through the injected exporter; false when not on a table or none is wired. */
    public boolean exportTableFile(String format) {
        String csv = tableFileExporter == null ? null : tableCsv();
        if (csv == null) {
            return false;
        }
        tableFileExporter.accept(csv, format);
        return true;
    }

    // ---- table of contents ----

    /**
     * Regenerates the document's {@code <!-- toc -->} block in place (the caret stays where it was), or
     * inserts a new one at the caret. False when not an editable Markdown buffer, or there is no heading to list.
     */
    public boolean insertOrUpdateToc() {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        String doc = a.getText();
        int caret = a.getCaretPosition();
        MarkdownEdit e = MarkdownToc.update(doc, 1, 6);
        if (e != null) {
            if (!e.replacement().equals(doc.substring(e.from(), e.to()))) {
                buffer.preventUndoMerge();
                a.replaceText(e.from(), e.to(), e.replacement());
                buffer.preventUndoMerge();
                int delta = e.replacement().length() - (e.to() - e.from());
                a.moveTo(caret <= e.from() ? caret : caret >= e.to() ? caret + delta : e.from());
            }
            a.requestFocus();
            return true;
        }
        String block = MarkdownToc.insertion(doc, caret, 1, 6);
        if (block.isEmpty()) {
            return false; // no headings to list
        }
        a.moveTo(insertBlock(a, block) + block.length() + 1);
        a.requestFollowCaret();
        a.requestFocus();
        return true;
    }
}
