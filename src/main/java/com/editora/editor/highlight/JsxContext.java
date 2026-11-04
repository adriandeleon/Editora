package com.editora.editor.highlight;

import java.time.Duration;
import java.util.List;

import com.editora.editops.Commenter;
import com.editora.editops.Indenter;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.jspecify.annotations.Nullable;

/**
 * Whether a place in a JavaScript/TypeScript file is JSX children — markup between {@code <div>} and
 * {@code </div>}, where {@code //} is page text and a comment is written {@code {/* *}{@code /}}. Read from the TSX
 * grammar (the one JavaScript, JSX and TSX share), resumed from the state the line starts in, so it costs one
 * line's tokenizing. Toggle Comment (JS/TS review A103) and Enter after an opening tag (A104) ask it.
 */
public final class JsxContext {

    /** The grammar every JSX-capable file uses; plain TypeScript ({@code source.ts}) has no JSX. */
    static final String TSX_SCOPE = "source.tsx";

    /** The JSX comment: a block comment inside an expression container. No line form exists in markup. */
    public static final Commenter.CommentStyle COMMENT = new Commenter.CommentStyle(null, "{/*", "*/}");

    private static final String CHILDREN = "meta.jsx.children";
    private static final String EXPRESSION = "meta.embedded.expression";
    private static final Duration TIMEOUT = Duration.ofMillis(50);

    private JsxContext() {}

    /**
     * Whether text typed right after {@code before} (the start of a line, up to a column) would be JSX children.
     * {@code lineStart} is the grammar state the line starts in ({@code null} for the first line). {@code false}
     * for a grammar without JSX, or when the line is too long to ask about cheaply.
     */
    public static boolean childrenAfter(@Nullable IGrammar grammar, @Nullable IStateStack lineStart, String before) {
        if (grammar == null || !TSX_SCOPE.equals(grammar.getScopeName()) || before.length() > 2_000) {
            return false;
        }
        // A probe letter at the column: its scopes are the context of whatever is typed there.
        String probe = before + "x\n";
        IToken[] tokens;
        try {
            tokens = grammar.tokenizeLine(probe, lineStart, TIMEOUT).getTokens();
        } catch (RuntimeException | LinkageError e) {
            return false;
        }
        int at = before.length();
        for (int k = tokens.length - 1; k >= 0; k--) {
            if (tokens[k].getStartIndex() <= at) {
                return innermostIsChildren(tokens[k].getScopes());
            }
        }
        return false;
    }

    /**
     * Whether line {@code line} (its whole text, {@code lineText}) starts in JSX children, at its first non-blank;
     * {@code lines} is the buffer's last highlight pass, which has the state every line starts in.
     */
    public static boolean lineInChildren(
            @Nullable IGrammar grammar, HighlightPass.@Nullable Lines lines, int line, String lineText) {
        return childrenAfter(grammar, stateBefore(lines, line), lineText.substring(0, indentEnd(lineText)));
    }

    /** The state line {@code line} starts in; {@code null} for the first line, or when the pass has not got there. */
    static @Nullable IStateStack stateBefore(HighlightPass.@Nullable Lines lines, int line) {
        return line <= 0 || lines == null || line > lines.states().length ? null : lines.states()[line - 1];
    }

    private static int indentEnd(String line) {
        int i = 0;
        while (i < line.length() && (line.charAt(i) == ' ' || line.charAt(i) == '\t')) {
            i++;
        }
        return i;
    }

    /** Scopes from outermost to innermost: is the nearest JSX-or-expression container the children? */
    static boolean innermostIsChildren(List<String> scopes) {
        for (int i = scopes.size() - 1; i >= 0; i--) {
            String s = scopes.get(i);
            if (s.startsWith(CHILDREN)) {
                return true;
            }
            if (s.startsWith(EXPRESSION)) {
                return false;
            }
        }
        return false;
    }

    /**
     * Enter between JSX tags, as VS Code's JSX rules do: right after an opening tag ({@code <ul>|}, {@code <div
     * className="x">|}) the new line is one level deeper, and when that tag's closer follows the caret ({@code
     * <ul>|</ul>}) the closer goes to a line of its own at the tag's indent. {@code null} when the caret is not
     * after an opening tag, so the ordinary rules apply.
     *
     * @param lines the buffer's last highlight pass (the state each line starts in)
     * @param unit one indent level
     */
    public static Indenter.@Nullable EnterEdit enter(
            @Nullable IGrammar grammar,
            HighlightPass.@Nullable Lines lines,
            int line,
            String lineText,
            int column,
            String unit) {
        String before = lineText.substring(0, Math.min(column, lineText.length()));
        String after = lineText.substring(before.length());
        String tag = openingTagAtEnd(before);
        if (tag == null || !childrenAfter(grammar, stateBefore(lines, line), before)) {
            return null;
        }
        String indent = lineText.substring(0, indentEnd(lineText));
        String inner = "\n" + indent + unit;
        String rest = after.stripLeading();
        if (rest.startsWith("</" + tag + ">") || (tag.isEmpty() && rest.startsWith("</>"))) {
            return new Indenter.EnterEdit(inner + "\n" + indent, inner.length());
        }
        return new Indenter.EnterEdit(inner, inner.length());
    }

    /**
     * The name of the opening tag {@code before} ends with ({@code ""} for a fragment {@code <>}), or {@code null}
     * when it ends with something else: a closing or self-closing tag, an arrow {@code =>}, a comparison.
     */
    static @Nullable String openingTagAtEnd(String before) {
        String s = before.stripTrailing();
        if (!s.endsWith(">") || s.endsWith("/>") || s.endsWith("=>")) {
            return null;
        }
        if (s.endsWith("<>")) {
            return "";
        }
        // Back to the '<' that opens this tag, skipping attribute strings and {expressions}.
        int depth = 0;
        char quote = 0;
        for (int i = s.length() - 2; i >= 0; i--) {
            char c = s.charAt(i);
            if (quote != 0) {
                quote = c == quote ? 0 : quote;
            } else if (c == '"' || c == '\'') {
                quote = c;
            } else if (c == '}') {
                depth++;
            } else if (c == '{') {
                depth--;
            } else if (c == '<' && depth == 0) {
                int j = i + 1;
                while (j < s.length()
                        && (Character.isLetterOrDigit(s.charAt(j)) || ".-_:$".indexOf(s.charAt(j)) >= 0)) {
                    j++;
                }
                return j > i + 1 && Character.isLetter(s.charAt(i + 1)) ? s.substring(i + 1, j) : null;
            } else if (c == '>' && depth == 0) {
                return null; // another tag ended first: this '>' is not an opening tag's
            }
        }
        return null;
    }
}
