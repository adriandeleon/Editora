package com.editora.editor.completion;

import java.nio.file.Path;
import java.util.Collection;
import java.util.function.BiFunction;

import com.editora.editops.Commenter;
import com.editora.editops.LineEndings;
import com.editora.editor.EditorBuffer;
import com.editora.snippet.Snippet;
import com.editora.snippet.TabExpansion;
import com.editora.snippet.VariableResolver;
import com.editora.text.ShellScripts;
import org.fxmisc.richtext.CodeArea;
import org.jspecify.annotations.Nullable;

/**
 * What {@link EditorBuffer} reads off a view to expand a snippet there: the trigger before the caret, and
 * the variables of the place it expands at. Kept out of the buffer class; both are short, bounded reads.
 */
public final class SnippetTyping {

    private SnippetTyping() {}

    /**
     * The trigger Tab would expand at {@code a}'s caret — its {@link TabExpansion.Match#start()} an absolute
     * offset — or null when there is none or Tab may not expand here (see {@link TabExpansion#allowed}).
     * Looks at the caret's line only, and at no more of it than the completion prefix does.
     */
    public static TabExpansion.@Nullable Match triggerAtCaret(
            CodeArea a, String language, boolean prose, BiFunction<String, String, Snippet> provider) {
        return triggerAtCaret(a, language, prose, provider, false);
    }

    /**
     * {@code offered}: the trigger is being looked up for the completion popup or its ghost, which show what
     * they would expand — there an inline word trigger is offered in the middle of a sentence too; a bare Tab
     * expands it only when the word is alone on its line.
     */
    public static TabExpansion.@Nullable Match triggerAtCaret(
            CodeArea a, String language, boolean prose, BiFunction<String, String, Snippet> provider, boolean offered) {
        int caret = a.getCaretPosition();
        int base =
                Math.max(a.getAbsolutePosition(a.getCurrentParagraph(), 0), caret - BufferCompletion.PREFIX_LOOKBACK);
        String before = a.getText(base, caret);
        TabExpansion.Match m = TabExpansion.find(before, prefix -> provider.apply(language, prefix));
        if (m == null) {
            return null;
        }
        // base is the line start unless the line is longer than the lookback — then nothing is at its start.
        boolean lineStart = base == a.getAbsolutePosition(a.getCurrentParagraph(), 0);
        boolean block = TabExpansion.blockLevel(m.snippet().body());
        if (prose && block && !(lineStart && TabExpansion.fitsInProse(before, m.start(), m.snippet()))) {
            return null;
        }
        if (prose && !block && !offered) {
            String line = a.getText(a.getCurrentParagraph());
            String after = line.substring(Math.min(a.getCaretColumn(), line.length()));
            if (!(lineStart && TabExpansion.inlineFitsInProse(before, m.start(), after))) {
                return null;
            }
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
    static VariableResolver variables(
            CodeArea a, int from, int to, @Nullable Path path, String language, @Nullable Path workspace) {
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

    /** Parses {@code snippet} for expansion over {@code [from, to)}: its {@link #body} with {@link #variables}. */
    public static com.editora.snippet.ParsedSnippet parse(
            com.editora.snippet.Snippet snippet,
            CodeArea a,
            int from,
            int to,
            @Nullable Path path,
            String language,
            @Nullable Path root) {
        return com.editora.snippet.SnippetParser.parse(
                body(snippet, a, language), variables(a, from, to, path, language, root));
    }

    /**
     * The body to expand for {@code snippet} in this buffer: a shell snippet follows the script's shebang
     * ({@link ShellScripts#snippetBody}), every other one is used as written.
     */
    static String body(com.editora.snippet.Snippet snippet, CodeArea a, String language) {
        if (!"shell".equals(language) || a.getParagraphs().isEmpty()) {
            return snippet.body();
        }
        return ShellScripts.snippetBody(snippet.body(), a.getParagraph(0).getText());
    }

    public static int lineOf(CodeArea a, int offset) {
        return a.offsetToPosition(offset, org.fxmisc.richtext.model.TwoDimensional.Bias.Forward)
                .getMajor();
    }
}
