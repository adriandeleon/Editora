package com.editora.editor;

import java.nio.file.Path;
import java.util.Collection;
import java.util.function.BiFunction;

import com.editora.editops.Commenter;
import com.editora.snippet.Snippet;
import com.editora.snippet.TabExpansion;
import com.editora.snippet.VariableResolver;
import org.fxmisc.richtext.CodeArea;

/**
 * What {@link EditorBuffer} reads off a view to expand a snippet there: the trigger before the caret, and
 * the variables of the place it expands at. Kept out of the buffer class; both are short, bounded reads.
 */
final class SnippetTyping {

    private SnippetTyping() {}

    /**
     * The trigger Tab would expand at {@code a}'s caret — its {@link TabExpansion.Match#start()} an absolute
     * offset — or null when there is none or Tab may not expand here (see {@link TabExpansion#allowed}).
     * Looks at the caret's line only, and at no more of it than the completion prefix does.
     */
    static TabExpansion.Match triggerAtCaret(
            CodeArea a, String language, boolean prose, BiFunction<String, String, Snippet> provider) {
        int caret = a.getCaretPosition();
        int base =
                Math.max(a.getAbsolutePosition(a.getCurrentParagraph(), 0), caret - BufferCompletion.PREFIX_LOOKBACK);
        TabExpansion.Match m = TabExpansion.find(a.getText(base, caret), prefix -> provider.apply(language, prefix));
        if (m == null) {
            return null;
        }
        // The highlighter's classes say where the trigger sits. A trigger typed a moment ago may not be
        // restyled yet, so the character before it is asked too: both are comment or string inside one.
        int start = base + m.start();
        boolean scoped = inCommentOrString(a, start) || start > base && inCommentOrString(a, start - 1);
        return TabExpansion.allowed(language, prose, scoped) ? new TabExpansion.Match(start, m.snippet()) : null;
    }

    private static boolean inCommentOrString(CodeArea a, int offset) {
        Collection<String> style = a.getStyleOfChar(offset);
        return style.contains("comment") || style.contains("string");
    }

    /** The variables for a snippet replacing {@code [from, to)} of {@code a}, in file {@code path} (null = untitled). */
    static VariableResolver variables(CodeArea a, int from, int to, Path path, String language, Path workspace) {
        Path abs = path == null ? null : path.toAbsolutePath();
        String clip = javafx.scene.input.Clipboard.getSystemClipboard().hasString()
                ? LineEndings.toLf(
                        javafx.scene.input.Clipboard.getSystemClipboard().getString())
                : "";
        int line = lineOf(a, from);
        String currentLine = a.getParagraph(line).getText();
        Commenter.CommentStyle comments = Commenter.styleFor(language);
        return new VariableResolver(
                        path == null ? "" : path.getFileName().toString(),
                        abs == null || abs.getParent() == null
                                ? ""
                                : abs.getParent().toString(),
                        abs == null ? "" : abs.toString(),
                        a.getSelectedText(),
                        clip,
                        line,
                        currentLine)
                .withWorkspace(workspace)
                .withCurrentWord(VariableResolver.wordAt(currentLine, to - a.getAbsolutePosition(line, 0)))
                .withComments(comments.line(), comments.blockStart(), comments.blockEnd());
    }

    static int lineOf(CodeArea a, int offset) {
        return a.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
    }
}
