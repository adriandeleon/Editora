package com.editora.editor;

import org.fxmisc.richtext.CodeArea;

/** The expression a debugger action takes from the editor: the name (with its attribute chain) at the caret. */
public final class DebugExpressions {

    private DebugExpressions() {}

    /**
     * The name at (or just before) the caret of {@code area} with the attribute chain before it ({@code other.depth}),
     * or null when the caret is not on a name in code (a comment, a string, an operator).
     */
    public static String atCaret(CodeArea area) {
        int par = area.getCurrentParagraph();
        int col = area.getCaretColumn();
        String found = DebugHover.expressionAt(area, par, col);
        return found == null && col > 0 ? DebugHover.expressionAt(area, par, col - 1) : found;
    }
}
