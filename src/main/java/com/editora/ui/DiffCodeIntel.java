package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Code intelligence on the file side of a diff (a working-tree change, a file against HEAD or a revision): while the
 * keyboard is on that side, Show Hover answers there, over the diff, and Go to Definition, Find References and Reveal
 * in Map open the file at the same place and act from it. The other side is a revision with no buffer of its own,
 * so the commands do there what they do anywhere else. FX thread.
 */
final class DiffCodeIntel {

    private final MainController mc;

    DiffCodeIntel(MainController mc) {
        this.mc = mc;
    }

    /** Where the keyboard is in a diff's file side: the pane, the file and its 0-based line and column. */
    record At(DiffViewerPane pane, Path file, CodeArea area, int line, int column) {}

    /** The caret on the active diff's file side, or null when the keyboard is not there. */
    @org.jspecify.annotations.Nullable
    At at() {
        DiffViewerPane pane = new DiffOpsHost(mc).activeDiffPane();
        Path file = pane == null ? null : pane.localFile();
        CodeArea area = pane == null ? null : pane.localArea();
        int[] caret = pane == null ? null : pane.localCaret();
        return pane == null || file == null || area == null || caret == null
                ? null
                : new At(pane, file, area, caret[0], caret[1]);
    }

    /** {@code lsp.hover} in a diff: the file's hover at the caret, over the diff. False when not in one. */
    boolean hover() {
        At at = at();
        if (at == null) {
            return false;
        }
        EditorBuffer open = mc.openBufferFor(at.file());
        if (open != null) {
            mc.lspCoordinator.popups.showHoverIn(at.area(), open, at.line(), at.column());
        } else {
            // The server answers for a file it has open: a background tab opens it, as a search result does.
            mc.openBackgroundBufferAsync(at.file(), b -> {
                if (b != null) {
                    mc.lspCoordinator.popups.showHoverIn(at.area(), b, at.line(), at.column());
                }
            });
        }
        return true;
    }

    /**
     * {@code lsp.gotoDefinition}, {@code lsp.findReferences} and {@code projectMap.revealActiveFile} in a diff: opens
     * the file at the caret's place and runs {@code then} from there. False when not in a diff's file side.
     */
    boolean fromFile(Runnable then) {
        At at = at();
        if (at == null) {
            return false;
        }
        mc.setStatus(tr("status.diff.fromFile", at.file().getFileName()));
        mc.fileWorkflows.openThen(at.file(), () -> {
            mc.sessions.gotoInFile(at.file(), at.line() + 1, at.column() + 1);
            then.run();
        });
        return true;
    }
}
