package com.editora.editor;

import java.util.function.BiFunction;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.markdown.BlockInsert;
import com.editora.markdown.HtmlToMarkdown;
import com.editora.markdown.MarkdownEdit;
import com.editora.markdown.MarkdownFootnote;
import com.editora.markdown.MarkdownHeading;
import com.editora.markdown.MarkdownInline;
import com.editora.markdown.MarkdownLines;
import com.editora.markdown.MarkdownLinks;
import com.editora.markdown.MarkdownTable;
import com.editora.markdown.MarkdownToc;
import com.editora.markdown.MarkdownTyping;
import com.editora.markdown.MarkdownWiki;
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
    private Runnable editLinkHandler;
    private java.util.function.UnaryOperator<String> wikiResolver;
    /** Image files a paste or drop into this buffer created in this session (see {@link #sessionImages()}). */
    private final java.util.Set<java.nio.file.Path> sessionImages = new java.util.LinkedHashSet<>();

    private java.util.function.BiConsumer<String, String> tableFileExporter;
    private int tocMinLevel = 1;
    private int tocMaxLevel = 6;
    private java.util.function.Function<String, String> chords = id -> null;
    private boolean smartTypography;
    private boolean tocSkipTitle;
    private boolean tocOrdered;

    MarkdownEditing(EditorBuffer buffer) {
        this.buffer = buffer;
    }

    /**
     * Pushes what the commands take from the settings and the keymap: the heading levels a table of contents
     * lists, and the lookup from a command id to the chord the active keymap binds it to (null when unbound).
     */
    public void configure(int tocMinLevel, int tocMaxLevel, java.util.function.Function<String, String> chords) {
        this.tocMinLevel = Math.max(1, Math.min(6, tocMinLevel));
        this.tocMaxLevel = Math.max(this.tocMinLevel, Math.min(6, tocMaxLevel));
        this.chords = chords == null ? id -> null : chords;
    }

    /** Pushes the typing and table-of-contents choices of the settings. */
    public void configureWriting(boolean smartTypography, boolean tocSkipTitle, boolean tocOrdered) {
        this.smartTypography = smartTypography;
        this.tocSkipTitle = tocSkipTitle;
        this.tocOrdered = tocOrdered;
    }

    private MarkdownToc.Style tocStyle() {
        return new MarkdownToc.Style(tocMinLevel, tocMaxLevel, tocSkipTitle, tocOrdered);
    }

    /**
     * The image files this session's pastes and drops into this buffer created — the only files the
     * "trash unused pasted images" clean-up may ever touch. Live: the window adds to it and takes from it.
     */
    public java.util.Set<java.nio.file.Path> sessionImages() {
        return sessionImages;
    }

    /** The chord bound to {@code commandId} in the active keymap, as it is displayed; null when unbound. */
    String chord(String commandId) {
        String chord = chords.apply(commandId);
        return chord == null || chord.isBlank() ? null : chord;
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
        // On a link already — a Markdown or a wiki link — "Link" means that link: edit it, do not nest one in it.
        if (view().getSelection().getLength() == 0 && editLinkHandler != null && linkForEditing()[2] != null) {
            editLinkInteractive();
            return;
        }
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
     * A character typed in a Markdown buffer that does more than insert itself: an emphasis character over a
     * selection wraps it; {@code *} and {@code _} come in pairs; with smart typography on, quotes, double
     * hyphens and three dots become their typographic forms. False for ordinary typing.
     *
     * <p>On the keystroke path: the line is read only for the few characters that can matter, and the
     * document (to rule out a code block) only once the line itself says there is something to do.
     */
    boolean typed(CodeArea a, char c) {
        if (a.getSelection().getLength() > 0) {
            return wrapSelection(a, c);
        }
        if (!MarkdownTyping.interesting(c, smartTypography)) {
            return false;
        }
        int par = a.getCurrentParagraph();
        String line = a.getText(par);
        int col = Math.min(a.getCaretColumn(), line.length());
        MarkdownEdit e = MarkdownTyping.typed(line, col, c, smartTypography);
        int lineStart = a.getAbsolutePosition(par, 0);
        if (e == null || !MarkdownLines.isProse(a.getText(), lineStart)) {
            return false;
        }
        a.replaceText(lineStart + e.from(), lineStart + e.to(), e.replacement());
        a.moveTo(lineStart + e.selStart());
        return true;
    }

    /**
     * An emphasis character ({@code *}, {@code _}, {@code ~}) typed over a single-line selection wraps the
     * selection instead of replacing it; false (ordinary typing) for anything else.
     */
    private boolean wrapSelection(CodeArea a, char c) {
        String sel = a.getSelectedText();
        if ((c != '*' && c != '_' && c != '~') || sel.isBlank() || sel.indexOf('\n') >= 0) {
            return false;
        }
        int s = a.getSelection().getStart();
        a.replaceText(s, a.getSelection().getEnd(), c + sel + c);
        a.selectRange(s + 1, s + 1 + sel.length());
        return true;
    }

    /**
     * After a line was moved up or down: when it is an item of an ordered list, numbers that list in sequence
     * again, as part of the move's own undo step. A no-op anywhere else.
     */
    public void renumberListAtCaret() {
        renumberList(true);
    }

    /**
     * Hooks a view of the buffer up: the format bar's mouse tracking ({@code onMouseRelease} asks for its
     * update), and Delete / Backspace over selected lines of an ordered list, which leaves the list numbered
     * in sequence.
     */
    void attach(CodeArea a, Runnable onMouseRelease) {
        MarkdownFormatBar.trackMouse(a, onMouseRelease);
        a.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (deleteSelectedItems(a, e)) {
                e.consume();
            }
        });
    }

    /**
     * Delete or Backspace with a selection that spans lines of an ordered Markdown list which was numbered in
     * sequence: deletes the selection and numbers the list again, as one undo step. False — the key does what
     * it always does — for anything else, including a list numbered out of sequence by hand.
     */
    private boolean deleteSelectedItems(CodeArea a, javafx.scene.input.KeyEvent e) {
        boolean key = e.getCode() == javafx.scene.input.KeyCode.DELETE
                || e.getCode() == javafx.scene.input.KeyCode.BACK_SPACE;
        if (!key
                || a.getSelection().getLength() == 0
                || e.isConsumed()
                || e.isControlDown()
                || e.isAltDown()
                || e.isMetaDown()
                || !buffer.isMarkdown()
                || !buffer.canFormatMarkdown()
                || buffer.hasMultipleCarets()
                || a.getSelectedText().indexOf('\n') < 0) {
            return false;
        }
        String text = a.getText();
        int start = a.getSelection().getStart();
        int end = a.getSelection().getEnd();
        if (!orderedItemAt(text, start) && !orderedItemAt(text, end)) {
            return false;
        }
        if (MarkdownLines.renumber(text, start) != null || MarkdownLines.renumber(text, end) != null) {
            return false; // numbered by hand: left as its author numbered it
        }
        a.replaceSelection("");
        renumberList(true);
        a.requestFollowCaret();
        return true;
    }

    private static boolean orderedItemAt(String text, int offset) {
        int ls = text.lastIndexOf('\n', offset - 1) + 1;
        int le = text.indexOf('\n', offset);
        return com.editora.markdown.MarkdownLine.parse(text.substring(ls, le < 0 ? text.length() : le))
                .isOrdered();
    }

    /** Whether the ordered list at the caret is numbered in sequence (true as well when there is no such list). */
    public boolean listInOrder() {
        CodeArea a = view();
        return MarkdownLines.markerLength(a.getText(a.getCurrentParagraph())) == 0
                || MarkdownLines.renumber(a.getText(), a.getSelection().getStart()) == null;
    }

    /**
     * Numbers the ordered list at the caret (or where the selection starts) in sequence again; false when
     * there is no such list or it is in order already. {@code joinLast}: as part of the previous edit's undo
     * step (the move or delete that left the list out of order).
     */
    public boolean renumberList(boolean joinLast) {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        int start = a.getSelection().getStart();
        if (MarkdownLines.markerLength(
                        a.getText(a.offsetToPosition(start, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                                .getMajor()))
                == 0) {
            return false; // the line alone says "not a list": no need for the document
        }
        MarkdownEdit e = MarkdownLines.renumber(a.getText(), a.getAnchor(), a.getCaretPosition());
        if (e == null) {
            return false;
        }
        Runnable joined = joinLast ? CompletionUndoManager.joinLast(a) : () -> {};
        a.replaceText(e.from(), e.to(), e.replacement());
        a.selectRange(e.selStart(), e.selEnd());
        joined.run();
        return true;
    }

    // ---- more line and block commands ----

    /** Toggles a numbered list on the selected line(s). */
    public void formatOrderedList() {
        if (buffer.canFormatMarkdown()) {
            CodeArea a = view();
            apply(MarkdownLines.toggleOrdered(a.getText(), a.getAnchor(), a.getCaretPosition()));
        }
    }

    /** Toggles a blockquote on the selected line(s). */
    public void formatBlockquote() {
        if (buffer.canFormatMarkdown()) {
            CodeArea a = view();
            apply(MarkdownLines.toggleQuote(a.getText(), a.getAnchor(), a.getCaretPosition()));
        }
    }

    /** Wraps the selected line(s) in a fenced code block, or unwraps the body of one. */
    public void formatCodeFence() {
        if (buffer.canFormatMarkdown()) {
            CodeArea a = view();
            apply(MarkdownLines.toggleFence(a.getText(), a.getAnchor(), a.getCaretPosition()));
        }
    }

    /** Ticks / unticks the task items on the selected line(s); false when none of them is a task item. */
    public boolean toggleTaskDone() {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        MarkdownEdit e = MarkdownLines.toggleDone(a.getText(), a.getAnchor(), a.getCaretPosition());
        apply(e);
        return e != null;
    }

    /** Inserts a footnote reference at the caret and its definition at the end of the document. */
    public void insertFootnote() {
        if (buffer.canFormatMarkdown()) {
            CodeArea a = view();
            apply(MarkdownFootnote.insert(a.getText(), a.getCaretPosition()));
        }
    }

    /** Inserts today's date (ISO, {@code 2026-10-12}) at the caret, over any selection. */
    public void insertDate() {
        if (buffer.canFormatMarkdown()) {
            buffer.insertAtCaret(java.time.LocalDate.now().toString());
        }
    }

    /** Inserts a horizontal rule on a line of its own, with the blank line a rule needs above it. */
    public void insertHorizontalRule() {
        if (buffer.canFormatMarkdown()) {
            CodeArea a = view();
            a.moveTo(insertBlock(a, "---") + 4);
            a.requestFollowCaret();
            a.requestFocus();
        }
    }

    // ---- links ----

    /** Opens the Edit Link card (wired by the window); a no-op until injected. */
    public void editLinkInteractive() {
        if (buffer.canFormatMarkdown() && editLinkHandler != null) {
            editLinkHandler.run();
        }
    }

    public void setEditLinkHandler(Runnable handler) {
        this.editLinkHandler = handler;
    }

    /** The link under the caret, or null. */
    public MarkdownLinks.Link linkAtCaret() {
        CodeArea a = view();
        return MarkdownLinks.at(a.getText(), a.getCaretPosition());
    }

    /** The selected text when it is on one line — what a new link's text starts as; "" otherwise. */
    public String linkTextCandidate() {
        String sel = view().getSelectedText();
        return sel.indexOf('\n') >= 0 ? "" : sel;
    }

    /**
     * Makes the link under the caret — or, without one, the selection (or the caret position) — a link with
     * {@code text} pointing at {@code destination}.
     */
    public void setLink(String text, String destination) {
        if (!buffer.canFormatMarkdown()) {
            return;
        }
        CodeArea a = view();
        MarkdownWiki.Link wiki = MarkdownWiki.at(a.getText(), a.getCaretPosition());
        if (wiki != null) { // a wiki link stays one: its address between the brackets, its label after the bar
            String made = MarkdownWiki.write(destination, text, wiki.embed(), wiki.inTable());
            int end = wiki.start() + made.length();
            apply(new MarkdownEdit(wiki.start(), wiki.end(), made, end, end));
            return;
        }
        MarkdownLinks.Link link = linkAtCaret();
        var sel = a.getSelection();
        boolean oneLine = a.getSelectedText().indexOf('\n') < 0;
        int from = oneLine ? sel.getStart() : a.getCaretPosition();
        int to = oneLine ? sel.getEnd() : a.getCaretPosition();
        apply(MarkdownLinks.set(a.getText(), link, from, to, text, destination));
    }

    /** Replaces the link under the caret by its text; false when the caret is not on a link. */
    public boolean removeLink() {
        MarkdownWiki.Link wiki =
                buffer.canFormatMarkdown() ? MarkdownWiki.at(view().getText(), view().getCaretPosition()) : null;
        if (wiki != null) {
            String shown = wiki.shown();
            apply(new MarkdownEdit(wiki.start(), wiki.end(), shown, wiki.start(), wiki.start() + shown.length()));
            return true;
        }
        MarkdownLinks.Link link = buffer.canFormatMarkdown() ? linkAtCaret() : null;
        if (link != null) {
            apply(MarkdownLinks.remove(link));
        }
        return link != null;
    }

    /** Inline link → reference link (or, {@code toInline}, back); false when the caret's link is not of that kind. */
    public boolean convertLink(boolean toInline) {
        MarkdownLinks.Link link = buffer.canFormatMarkdown() ? linkAtCaret() : null;
        if (link == null) {
            return false;
        }
        String text = view().getText();
        MarkdownEdit e = toInline ? MarkdownLinks.toInline(text, link) : MarkdownLinks.toReference(text, link);
        apply(e);
        return e != null;
    }

    /**
     * What Edit Link starts from: {@code {text, address, kind}} of the link under the caret — a Markdown
     * link ({@code "link"}) or a wiki link ({@code "wiki"}: its label and what is between its brackets) —
     * else the selection as the text of a new link (kind null).
     */
    public String[] linkForEditing() {
        CodeArea a = view();
        MarkdownWiki.Link wiki = MarkdownWiki.at(a.getText(), a.getCaretPosition());
        if (wiki != null) {
            return new String[] {wiki.label() == null ? "" : wiki.label(), wiki.address(), "wiki"};
        }
        MarkdownLinks.Link link = linkAtCaret();
        return link == null
                ? new String[] {linkTextCandidate(), "", null}
                : new String[] {link.text(), link.destination(), "link"};
    }

    /** Maps a wiki link's target to the path a Markdown link to it should carry; null when it cannot tell. */
    public void setWikiResolver(java.util.function.UnaryOperator<String> resolver) {
        this.wikiResolver = resolver;
    }

    /**
     * Wiki link ⇄ Markdown link for the link under the caret. Returns 0 when it was converted, 1 when the
     * caret is on no link, 2 when the Markdown link leaves the project (a wiki link cannot name a URL).
     */
    public int convertWikiLink() {
        if (!buffer.canFormatMarkdown()) {
            return 1;
        }
        CodeArea a = view();
        String text = a.getText();
        MarkdownWiki.Link wiki = MarkdownWiki.at(text, a.getCaretPosition());
        if (wiki != null) {
            apply(MarkdownWiki.toMarkdown(wiki, wikiResolver));
            return 0;
        }
        MarkdownLinks.Link link = linkAtCaret();
        if (link == null) {
            return 1;
        }
        MarkdownEdit e = MarkdownWiki.toWiki(
                link, MarkdownTable.blockBounds(text, a.getCaretPosition()) != null); // in a table the bar is \|
        apply(e);
        return e == null ? 2 : 0;
    }

    // ---- paste ----

    /**
     * Pastes the clipboard as Markdown: its HTML converted (a table, links, emphasis, lists, headings, code),
     * else — for the explicit command — tab-separated text as a table. False when there is nothing to convert.
     *
     * <p>{@code explicit} false is the ordinary paste asking whether to step in, and it does so only for HTML
     * that carries structure ({@link HtmlToMarkdown#hasStructure}), when the plain text beside it is not
     * Markdown already and the conversion says something the plain text does not.
     */
    public boolean pasteAsMarkdown(boolean explicit) {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        String html = clipboard.hasHtml() ? clipboard.getHtml() : null;
        String plain = clipboard.hasString() ? LineEndings.toLf(clipboard.getString()) : null;
        String markdown = null;
        if (html != null
                && (explicit || (HtmlToMarkdown.hasStructure(html) && !HtmlToMarkdown.looksLikeMarkdown(plain)))) {
            markdown = HtmlToMarkdown.convert(html);
        }
        if ((markdown == null || markdown.isBlank()) && explicit && plain != null && plain.indexOf('\t') >= 0) {
            markdown = MarkdownTable.fromCsv(plain);
        }
        if (markdown == null || markdown.isBlank() || (!explicit && plain != null && markdown.equals(plain.strip()))) {
            return false;
        }
        buffer.insertAtCaret(markdown);
        return true;
    }

    // ---- tables ----

    public boolean tableAddRowAbove() {
        return onTable(MarkdownTable::addRowAbove);
    }

    public boolean tableAddColumnLeft() {
        return onTable(MarkdownTable::addColumnLeft);
    }

    /** False when not on a table, on the header or delimiter row, or with no body row on that side. */
    public boolean tableMoveRow(boolean up) {
        return onTable((block, caret) -> MarkdownTable.moveRow(block, caret, up));
    }

    /** False when not on a table or with no column on that side. */
    public boolean tableMoveColumn(boolean left) {
        return onTable((block, caret) -> MarkdownTable.moveColumn(block, caret, left));
    }

    public boolean tableSort(boolean ascending) {
        return onTable((block, caret) -> MarkdownTable.sort(block, caret, ascending));
    }

    /** Deletes the caret's table, leaving an empty line; false when the caret is not on one. */
    public boolean tableDelete() {
        if (!buffer.canFormatMarkdown()) {
            return false;
        }
        CodeArea a = view();
        int[] b = MarkdownTable.blockBounds(a.getText(), a.getCaretPosition());
        if (b == null) {
            return false;
        }
        a.replaceText(b[0], b[1], "");
        a.requestFocus();
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
     * Before a save: regenerates the managed {@code <!-- toc -->} block when the document has one and it is
     * out of date — never inserts one, and never on a read-only or preview-only buffer. One undo step; the
     * caret stays. Returns whether the text changed.
     */
    public boolean updateTocForSave() {
        if (!buffer.canFormatMarkdown() || buffer.isPreviewOnly()) {
            return false;
        }
        CodeArea a = buffer.getArea();
        String doc = a.getText();
        MarkdownEdit e = MarkdownToc.update(doc, tocStyle());
        if (e == null || e.replacement().equals(doc.substring(e.from(), e.to()))) {
            return false;
        }
        int caret = a.getCaretPosition();
        buffer.preventUndoMerge();
        a.replaceText(e.from(), e.to(), e.replacement());
        buffer.preventUndoMerge();
        int delta = e.replacement().length() - (e.to() - e.from());
        a.moveTo(caret <= e.from() ? caret : caret >= e.to() ? caret + delta : e.from());
        return true;
    }

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
        MarkdownEdit e = MarkdownToc.update(doc, tocStyle());
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
        String block = MarkdownToc.insertion(doc, caret, tocStyle());
        if (block.isEmpty()) {
            return false; // no headings to list
        }
        a.moveTo(insertBlock(a, block) + block.length() + 1);
        a.requestFollowCaret();
        a.requestFocus();
        return true;
    }
}
