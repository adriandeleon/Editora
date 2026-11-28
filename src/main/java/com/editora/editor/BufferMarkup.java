package com.editora.editor;

import com.editora.editops.Commenter;
import com.editora.editor.highlight.JsxContext;
import com.editora.editor.markdown.MarkdownEditing;
import com.editora.perf.OffFx;
import com.editora.typst.TypstMarkup;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * Markup and comment commands of one buffer that are not Markdown's own ({@link MarkdownEditing} holds those):
 * which formatting actions apply, Toggle Comment (worked out off the FX thread), and the Typst table and outline
 * inserts.
 *
 * <p>Moved out of {@code EditorBuffer} for size.
 */
final class BufferMarkup {

    private final EditorBuffer b;

    BufferMarkup(EditorBuffer b) {
        this.b = b;
    }

    /** True when this buffer can show Markdown formatting actions (markdown + editable). */
    boolean canFormatMarkdown() {
        return b.isMarkdown() && b.isEditable();
    }
    /** True when this buffer supports the shared markup-formatting actions — Markdown <em>or</em> Typst,
     *  editable. The inline wrap ({@code *}/{@code _}/{@code `}), bullet toggle, heading, and link work for
     *  both (the heading/link cores dispatch on {@link EditorBuffer#isTypst()}); Markdown-only actions (strikethrough,
     *  task list, tables, TOC) keep the narrower {@link #canFormatMarkdown()} gate. */
    boolean canFormatMarkup() {
        return (b.isMarkdown() || b.isTypst()) && b.isEditable();
    }
    /**
     * True when this buffer's language has comment syntax and the buffer is editable — i.e. the
     * "Comment / Uncomment" action is offered (right-click menu) and would do something. Plaintext has
     * no comment syntax, so it's excluded.
     */
    boolean supportsComments() {
        Commenter.CommentStyle s = Commenter.styleFor(b.language);
        return (s.hasLine() || s.hasBlock()) && b.isEditable() && !b.largeFile;
    }
    /**
     * Toggles line/region comments on the selection (or the caret's line when nothing is selected),
     * Emacs comment-dwim style. Returns {@code false} when the language has no comment syntax or the
     * buffer isn't editable. Kept in {@code editor} (mirroring the Markdown format methods) so the editor
     * context menu can invoke it without depending on {@code ui}; {@code MainController.toggleComment}
     * delegates here.
     */
    boolean toggleComment() {
        Boolean[] applied = {null};
        if (!toggleComment(why -> applied[0] = why == null, text -> "")) {
            return false;
        }
        return applied[0] == null || applied[0]; // still out (the app): it was started; in place: whether it applied
    }
    /**
     * As {@link #toggleComment()}, the edit worked out off the FX thread (PERF-01, Pauta 6.2.0): the comment rules read
     * the whole text in some languages (a shell here-doc, an HTML page's script and style blocks, an INI file's
     * style) and a selection can be the whole document. It is applied when it lands if the text is unchanged;
     * {@code done} then hears null, or, when no edit could be made, what {@code whyNot} says of the text (also worked
     * out off the FX thread). False, with nothing started, when the buffer cannot take comments.
     */
    boolean toggleComment(
            java.util.function.Consumer<@Nullable String> done, java.util.function.Function<String, String> whyNot) {
        if (!b.isEditable() || b.largeFile) {
            return false;
        }
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        // A collapsed header is commented together with its (then expanded) body.
        int[] span = b.folds.expandHeaderSpan(
                a.getSelection().getStart(), a.getSelection().getEnd());
        int line = a.offsetToPosition(span[0], org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
        boolean jsx = JsxContext.lineInChildren(
                b.grammar,
                b.highlighter.highlightLines,
                line,
                a.getParagraph(line).getText());
        DocumentText document = DocumentText.of(a); // O(1); the text is built off the FX thread (E11)
        String lang = b.language;
        java.nio.file.Path file = b.getPath();
        long version = b.docVersion;
        OffFx.then(
                EditorBuffer.COMMENT_SITE,
                () -> {
                    String text = document.text();
                    Commenter.Edit edit = jsx
                            ? Commenter.toggle(text, span[0], span[1], JsxContext.COMMENT) // {/* */} in markup (A103)
                            : Commenter.toggle(text, span[0], span[1], lang, file);
                    return new CommentAnswer(edit, edit == null ? whyNot.apply(text) : null);
                },
                answer -> {
                    CommentAnswer got = answer.value();
                    if (got == null || b.disposed || b.docVersion != version) {
                        return; // typed into meanwhile: the command is pressed again on the new text
                    }
                    Commenter.Edit edit = got.edit();
                    if (edit == null) {
                        done.accept(got.whyNot());
                        return;
                    }
                    a.replaceText(edit.from(), edit.to(), edit.replacement());
                    a.selectRange(edit.selStart(), edit.selEnd());
                    a.requestFocus();
                    done.accept(null);
                });
        return true;
    }
    /** Toggle Comment worked out off the FX thread: the edit, or why none could be made. */
    private record CommentAnswer(
            Commenter.@Nullable Edit edit, @Nullable String whyNot) {}
    /** Inserts {@code text} at the focused area's caret (replacing any selection), then focuses it. */
    void insertAtCaret(String text) {
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        a.replaceSelection(text);
        a.requestFocus();
    }
    /** Opens the size picker for a Typst {@code #table} (Typst buffers only); the controller runs the picker. */
    void insertTypstTableInteractive() {
        if (b.isTypst() && b.isEditable() && b.insertTypstTableHandler != null) {
            b.insertTypstTableHandler.run();
        }
    }
    /** Injected by the controller: opens the size picker, then calls {@link #insertTypstTable}. */
    void setInsertTypstTableHandler(Runnable handler) {
        b.insertTypstTableHandler = handler;
    }
    /** Injected by the controller: opens an image file chooser → copies to {@code assets/} → inserts
     *  {@code #image("…")} (the "Insert Image" menu item for Typst buffers). */
    void setTypstImageHandler(Runnable handler) {
        b.typstImagePasteHandler = handler;
    }
    /** Inserts a {@code #table(...)} skeleton at the caret (on its own line), caret in the first cell. */
    void insertTypstTable(int rows, int cols) {
        if (!b.isTypst() || !b.isEditable()) {
            return;
        }
        CodeArea a = b.focusedArea != null ? b.focusedArea : b.area;
        TypstMarkup.Table t = TypstMarkup.table(rows, cols);
        int caret = a.getCaretPosition();
        boolean needLeading = caret > 0 && a.getText(caret - 1, caret).charAt(0) != '\n'; // one char, no copy
        String prefix = needLeading ? "\n" : "";
        a.replaceText(caret, caret, prefix + t.text() + "\n");
        a.moveTo(caret + prefix.length() + t.caretOffset());
        a.requestFollowCaret();
        a.requestFocus();
    }
    /** Inserts a {@code #outline()} table-of-contents call at the caret (Typst buffers only). */
    void insertTypstOutline() {
        if (!b.isTypst() || !b.isEditable()) {
            return;
        }
        insertAtCaret(TypstMarkup.OUTLINE);
    }
}
