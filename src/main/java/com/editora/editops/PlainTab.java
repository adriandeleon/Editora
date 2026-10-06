package com.editora.editops;

/**
 * Tab / Shift-Tab for the buffers {@link Indenter#smartTab} declines: prose, plain text and every language
 * without an indentation style of its own ({@link Indenter.Style#PLAIN}). Those have no context to
 * re-indent a line from, but a selection is still a block to indent, Shift-Tab still dedents, and a Tab
 * still inserts the file's indent unit — the stock handler instead replaced the selection with one tab
 * character, ignored Shift-Tab, and typed a tab whatever the indent style said.
 *
 * <p>Pure: no toolkit dependency.
 */
public final class PlainTab {

    /** Any language with a style of its own: block indent and dedent do not depend on which. */
    private static final String STYLED_LANGUAGE = "java";

    /**
     * Formats in which a tab character is syntax, not indentation: a Makefile recipe must start with one
     * and a TSV file separates its columns with them. Tab stays a real tab there whatever the indent style
     * says, as it always was.
     */
    private static final java.util.Set<String> TAB_IS_SYNTAX = java.util.Set.of("makefile", "csv");

    private PlainTab() {}

    /**
     * The edit for Tab ({@code shift == false}) or Shift-Tab over {@code [selStart, selEnd)}:
     * <ul>
     *   <li>a selection → every touched line indented (or dedented) by one unit, staying selected;</li>
     *   <li>Shift-Tab with a bare caret → the caret's line dedented;</li>
     *   <li>Tab with a bare caret → one unit inserted at the caret. There is no "snap to the indent the
     *       code above implies" here: in prose the line above implies nothing.</li>
     * </ul>
     * The unit is {@link Indenter#unitFor}: an EditorConfig / indent-style override when
     * {@code insertSpaces != null}, else what the document already uses — except in a
     * {@linkplain #TAB_IS_SYNTAX tab-significant} {@code language}, where it is always a tab.
     */
    public static Indenter.TabEdit edit(
            String text,
            int selStart,
            int selEnd,
            String language,
            int tabSize,
            boolean shift,
            Boolean insertSpaces,
            Integer indentSize) {
        if (language != null && TAB_IS_SYNTAX.contains(language)) {
            insertSpaces = Boolean.FALSE;
        }
        if (selStart != selEnd || shift) {
            return Indenter.smartTab(text, selStart, selEnd, STYLED_LANGUAGE, tabSize, shift, insertSpaces, indentSize);
        }
        String unit = Indenter.unitFor(text, tabSize, insertSpaces, indentSize);
        int caret = selStart + unit.length();
        return new Indenter.TabEdit(selStart, selStart, unit, caret, caret);
    }
}
