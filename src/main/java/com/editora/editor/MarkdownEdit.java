package com.editora.editor;

/**
 * A pure text edit produced by the Markdown editing cores ({@link MarkdownInline}, {@link MarkdownHeading},
 * {@link MarkdownTable}): replace {@code [from, to)} with {@code replacement}, then select
 * {@code [selStart, selEnd]} (a zero-length selection places the caret). Mirrors {@code Commenter.Edit};
 * applied by {@code EditorBuffer} via {@code area.replaceText(...)} + {@code area.selectRange(...)}.
 */
public record MarkdownEdit(int from, int to, String replacement, int selStart, int selEnd) {}
