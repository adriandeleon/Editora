package com.editora.editor;

import com.editora.markdown.MarkdownLint;

import static com.editora.i18n.Messages.tr;

/**
 * The words shown for a Markdown-lint diagnostic, in the UI language. The linter is pure and speaks catalog
 * keys ({@code mdlint.msg.*}); the squiggle tooltip, the overview stripe and the Lint tool window all resolve
 * them here, so the three never disagree.
 */
public final class MarkdownLintText {

    private MarkdownLintText() {}

    /** The diagnostic's message: its catalog entry, or the producer's own text when it carries no key. */
    public static String message(MarkdownLint.Diagnostic d) {
        return d.key() == null ? d.message() : tr(d.key());
    }

    /** {@code MD034: Bare URL used} — the one-line form of a tooltip or a copied row. */
    public static String labelled(MarkdownLint.Diagnostic d) {
        return d.code() + ": " + message(d);
    }
}
