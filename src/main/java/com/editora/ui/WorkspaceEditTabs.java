package com.editora.ui;

import java.nio.file.Path;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

import com.editora.editor.EditorBuffer;
import com.editora.lsp.WorkspaceEditMapper;
import org.jspecify.annotations.Nullable;

/**
 * The tabs a workspace edit opened for itself. An edit that touches a file with no tab opens one in the
 * background; when the edit is then refused and rolled back, that tab showed a file the edit never changed — or,
 * for a file the edit was to create, one that no longer exists (XML review C1: each failed "Generate … and bind"
 * left an empty "nogrammar.dtd" tab behind).
 */
final class WorkspaceEditTabs {

    private WorkspaceEditTabs() {}

    /**
     * Closes every clean tab at one of {@code mapped}'s targets (edited or created files) that was not open
     * in {@code before}. A tab the user has typed into since is kept.
     */
    static void discardOpened(
            WorkspaceEditMapper.Mapped mapped,
            Set<EditorBuffer> before,
            Function<Path, @Nullable EditorBuffer> bufferAt,
            Consumer<EditorBuffer> close) {
        Set<EditorBuffer> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        java.util.List<Path> targets = new java.util.ArrayList<>();
        mapped.edits().forEach(edit -> targets.add(edit.file()));
        mapped.creates().forEach(create -> targets.add(create.file()));
        for (Path target : targets) {
            EditorBuffer buffer = bufferAt.apply(target);
            if (buffer != null
                    && seen.add(buffer)
                    && !before.contains(buffer)
                    && !buffer.isDisposed()
                    && !buffer.isDirty()) {
                close.accept(buffer);
            }
        }
    }
}
