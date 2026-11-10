package com.editora.ui;

import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

import com.editora.git.GitService;

import static com.editora.i18n.Messages.tr;

/**
 * A permalink copied ({@link GitService#permalink}): the link goes to the clipboard and the status line says what was
 * copied, or why nothing was — from the editor's Git: Copy Permalink and from the Project Map's cards and rows.
 */
final class GitPermalinks {

    private GitPermalinks() {}

    /** Copies {@code link}'s address, when it has one, and returns what the status line says. FX thread. */
    static String copy(GitService.Permalink link) {
        if (!link.url().isEmpty()) {
            ClipboardContent content = new ClipboardContent();
            content.putString(link.url());
            Clipboard.getSystemClipboard().setContent(content);
        }
        return message(link);
    }

    /**
     * The 0-based lines a selection in {@code area} covers, {@code {first, last}}: a selection that ends at the start
     * of a line leaves that line out (a whole-line selection); without a selection, the caret's line.
     */
    static int[] selectedLines(org.fxmisc.richtext.GenericStyledArea<?, ?, ?> area) {
        var selection = area.getSelection();
        if (selection.getLength() == 0) {
            int caret = area.getCurrentParagraph();
            return new int[] {caret, caret};
        }
        var bias = org.fxmisc.richtext.model.TwoDimensional.Bias.Forward;
        var start = area.offsetToPosition(selection.getStart(), bias);
        var end = area.offsetToPosition(selection.getEnd(), bias);
        int last = end.getMinor() == 0 && end.getMajor() > start.getMajor() ? end.getMajor() - 1 : end.getMajor();
        return new int[] {start.getMajor(), last};
    }

    /** What the status line says of {@code link}. Pure but for the language. */
    static String message(GitService.Permalink link) {
        return switch (link.status()) {
            case OK -> tr(link.pushed() ? "status.git.permalink.copied" : "status.git.permalink.unpushed", link.url());
            case NEW_LINES -> tr("status.git.permalink.newLines", link.url());
            case NO_REPOSITORY -> tr("status.git.permalink.noRepository");
            case NO_COMMIT -> tr("status.git.permalink.noCommit");
            case NO_REMOTE -> tr("status.git.permalink.noRemote");
            case UNTRACKED -> tr("status.git.permalink.untracked");
        };
    }
}
