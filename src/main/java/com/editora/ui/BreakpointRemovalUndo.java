package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.config.Breakpoint;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * Records how to put back a line breakpoint the user removed, when it carried something typed or chosen
 * ({@link Breakpoint#carriesDetails}): a plain one comes back with the key that removed it and records nothing. A
 * breakpoint is removed in its file's tab ({@link EditorBuffer#toggleBreakpoint}: the toggle commands, the gutter and
 * its menu, the Breakpoints list, a Project Map card of an open file) or, for a file with no tab, in the stored list
 * ({@link BreakpointsPicker#rewriteClosed}). It goes back through the one it was removed from, and only while that
 * is still where the file's breakpoints live.
 */
final class BreakpointRemovalUndo {

    private BreakpointRemovalUndo() {}

    /** {@code buffer}'s toggle removed {@code removed} (its line is the buffer's view line). */
    static void removedInTab(CoordinatorHost host, EditorBuffer buffer, Breakpoint removed) {
        if (!removed.carriesDetails()) {
            return;
        }
        RemovedLine at = RemovedLine.of(buffer, removed.line());
        String name = host.bufferBaseName(buffer);
        host.removalUndo()
                .removed(
                        tr("status.debug.breakpointRemoved", at.documentLine() + 1, name),
                        tr("status.debug.breakpointRestored", at.documentLine() + 1, name),
                        () -> restoreInTab(host, at, removed));
    }

    /**
     * Puts {@code removed} back on its line. False, changing nothing, when the tab was closed, the line cannot take
     * it back ({@link RemovedLine#viewLine}), or the line has a breakpoint now.
     */
    static boolean restoreInTab(CoordinatorHost host, RemovedLine at, Breakpoint removed) {
        EditorBuffer buffer = at.buffer(host);
        int line = buffer == null ? -1 : at.viewLine(buffer);
        return buffer != null && line >= 0 && buffer.restoreBreakpoint(removed.withLine(line));
    }

    /** {@code removed} was taken out of the stored breakpoints of {@code file} (stored under {@code key}). */
    static void removedInStore(
            CoordinatorHost host,
            DebugCoordinator.Ops ops,
            BreakpointsPicker store,
            Path file,
            String key,
            Breakpoint removed) {
        if (!removed.carriesDetails()) {
            return;
        }
        Path fileName = file.getFileName();
        String name = fileName == null ? file.toString() : fileName.toString();
        host.removalUndo()
                .removed(
                        tr("status.debug.breakpointRemoved", removed.line() + 1, name),
                        tr("status.debug.breakpointRestored", removed.line() + 1, name),
                        () -> restoreInStore(ops, store, file, key, removed));
    }

    /**
     * Puts {@code removed} back in the stored breakpoints of {@code file}. False, changing nothing, when the file has a
     * tab now (its breakpoints are the tab's) or its line has a breakpoint now.
     */
    static boolean restoreInStore(
            DebugCoordinator.Ops ops, BreakpointsPicker store, Path file, String key, Breakpoint removed) {
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null && !open.isNarrowed()) {
            return false;
        }
        List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key, List.of());
        if (stored.stream().anyMatch(bp -> bp.line() == removed.line())) {
            return false;
        }
        store.rewriteClosed(file, key, removed.line(), removed);
        return true;
    }
}
