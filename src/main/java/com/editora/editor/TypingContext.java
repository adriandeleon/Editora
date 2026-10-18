package com.editora.editor;

import java.util.Collection;

import org.fxmisc.richtext.CodeArea;
import org.fxmisc.richtext.model.TwoDimensional;

/**
 * What the highlighter says about the caret's surroundings, for typing assists that must behave differently
 * inside a literal: a quote typed inside a string, a comment or a regular expression is text, not the start of a
 * new string, so it must not be paired ({@code /['"]/} used to leave {@code "']} behind). FX thread.
 */
final class TypingContext {

    private TypingContext() {}

    /**
     * Whether {@code caret} sits inside a string, comment or regex, by the styles of the characters around it — or,
     * in JavaScript/TypeScript, inside a regex still being typed on its line ({@link RegexLiterals#openAtEnd}).
     */
    static boolean inLiteral(CodeArea a, int caret, String language) {
        if (caret <= 0 || caret > a.getLength()) {
            return false;
        }
        if (RegexLiterals.in(language)) {
            int lineStart = a.getAbsolutePosition(
                    a.offsetToPosition(caret, TwoDimensional.Bias.Backward).getMajor(), 0);
            if (RegexLiterals.openAtEnd(a.getText(lineStart, caret))) {
                return true;
            }
        }
        if (!literal(a.getStyleOfChar(caret - 1))) {
            return false;
        }
        // The character before is part of a literal; the caret is inside it unless that was the literal's last
        // character — then what follows (if anything on this line) is not a literal.
        return caret == a.getLength() || a.getText(caret, caret + 1).equals("\n") || literal(a.getStyleOfChar(caret));
    }

    private static boolean literal(Collection<String> styles) {
        return styles.contains("string") || styles.contains("comment") || styles.contains("regexp");
    }
}
