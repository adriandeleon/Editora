package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.config.Bookmark;
import com.editora.config.BookmarkStore;
import com.editora.editor.EditorBuffer;

import static com.editora.i18n.Messages.tr;

/**
 * Bookmarks feature (per-buffer gutter markers + the Bookmarks tool window + the jump picker), extracted
 * from {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link BookmarksPanel} +
 * the {@code bookmarks.jump} picker + the per-buffer persist/restore (keyed by absolute path) + the
 * {@code bookmarks.*} flow; bookmarks are always on (no support gate). {@code MainController} keeps the
 * {@code ToolWindow} (built with {@link #panel()}), the rename-migration call ({@link #migrateKey}), and
 * the command registrations (delegating to the coordinator).
 */
final class BookmarkCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (open/jump, open-buffer lookup, note prompt, store). */
    interface Ops {
        void openPath(Path file);

        void navigateToLine(int line);

        /**
         * Opens {@code file} at 0-based {@code line} in {@code projectKey}'s window ({@code ""} = the
         * general/no-project window), focusing or creating that window — so activating a bookmark from a
         * different project switches to (or opens) that project's window instead of opening it out of context.
         */
        void openInProjectWindow(String projectKey, Path file, int line);

        /** The open buffer for {@code file}, or {@code null} if it isn't open. */
        EditorBuffer bufferForPath(Path file);

        /** Shows the in-scene single-line prompt (used for a bookmark note). */
        void promptText(String title, String label, String initial, Consumer<String> onAccept);

        /** The active project's bookmarks bucket (keyed by absolute path string). */
        Map<String, List<Bookmark>> bookmarks();

        /** Every project's bookmark buckets ({@code projectKey → (path → bookmarks)}) — the cross-project view. */
        Map<String, Map<String, List<Bookmark>>> allBookmarks();

        /** This window's project key ({@code ""} = general/no-project) — the "current" group when grouping. */
        String currentProjectKey();

        /** Display name for a project key: {@code ""} → "General", else the project's name (fallback: the key). */
        String projectName(String key);

        /** Persists {@code bookmarks.json}. */
        void saveBookmarks();
    }

    /** A bookmark plus the file it belongs to, for the cross-file jump picker. */
    private record BookmarkEntry(Path file, Bookmark bm) {}

    private final CoordinatorHost host;
    private final Ops ops;
    private final BookmarksPanel panel;
    private final QuickOpen<BookmarkEntry> jumpPalette;

    // The per-edit (line-shift) persist is coalesced: a synchronous atomic bookmarks.json write + a full
    // Bookmarks-tree rebuild per newline (holding Enter above a bookmark) would block the FX thread. Explicit
    // user actions (toggle/add/reorder) still persist immediately via persistBookmarks. (#551)
    private final javafx.animation.PauseTransition persistDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private EditorBuffer pendingPersist;

    BookmarkCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        persistDebounce.setOnFinished(e -> flushPendingPersist());
        this.panel = new BookmarksPanel(this::scope, new BookmarksPanel.Actions() {
            @Override
            public void openAndJump(String projectKey, Path file, int line) {
                bookmarkActivate(projectKey, file, line);
            }

            @Override
            public void setNote(String projectKey, Path file, int line, String note) {
                bookmarkSetNote(projectKey, file, line, note);
            }

            @Override
            public void delete(String projectKey, Path file, int line) {
                bookmarkDelete(projectKey, file, line);
            }

            @Override
            public void deleteAll(String projectKey, Path file) {
                bookmarkDeleteAll(projectKey, file);
            }

            @Override
            public void moveBookmark(Path file, int from, int to) {
                BookmarkCoordinator.this.moveBookmark(file, from, to);
            }

            @Override
            public void moveFile(int from, int to) {
                moveBookmarkFile(from, to);
            }
        });
        panel.setPrompt(ops::promptText); // in-scene bookmark-note prompt
        this.jumpPalette = new QuickOpen<>(
                "Jump to Bookmark",
                "Type to filter bookmarks…",
                this::allBookmarkEntries,
                e -> bookmarkLabel(e.bm()),
                e -> e.file().getFileName() + ":" + (e.bm().line() + 1),
                // The jump picker is scoped to the active project's bookmarks, so they open in this window.
                e -> bookmarkActivate(ops.currentProjectKey(), e.file(), e.bm().line()));
    }

    BookmarksPanel panel() {
        return panel;
    }

    /** The cross-project view the panel renders on each refresh (every bucket + the current key + a name resolver). */
    private BookmarksPanel.Scope scope() {
        return new BookmarksPanel.Scope(ops.allBookmarks(), ops.currentProjectKey(), ops::projectName);
    }

    /** Binds the jump picker to the shared overlay host (called once from {@code MainController.wireOverlayHost}). */
    void wireOverlayHost() {
        jumpPalette.setOverlayHost(host.overlayHost());
    }

    // --- per-buffer persistence (called from addBuffer / session restore / save) ---------------------

    /**
     * Coalesces the per-edit persist (fired from {@code BookmarkManager.onChanged} when a line shift moves a
     * bookmark) so holding Enter above a bookmark doesn't do a synchronous atomic file write + tree rebuild per
     * newline on the FX thread. The write lands once, ~300 ms after editing settles; a crash before then loses
     * only line-shift indices, which reanchor-on-open recovers. (#551)
     */
    void schedulePersistBookmarks(EditorBuffer buffer) {
        pendingPersist = buffer;
        persistDebounce.playFromStart();
    }

    private void flushPendingPersist() {
        EditorBuffer b = pendingPersist;
        pendingPersist = null;
        if (b != null) {
            persistBookmarks(b);
        }
    }

    void persistBookmarks(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Bookmark> marks = buffer.getBookmarkManager().snapshot();
        var map = ops.bookmarks();
        if (marks.isEmpty()) {
            map.remove(file.toString());
        } else {
            // Keep any custom order the user set in the Bookmarks tool window (the snapshot is line-order).
            map.put(file.toString(), BookmarkStore.mergePreservingOrder(map.get(file.toString()), marks));
        }
        ops.saveBookmarks();
        panel.refresh();
    }

    /** Re-applies a file's saved bookmarks after it is opened (and paints their gutter markers). */
    void restoreBookmarks(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        boolean reanchored = buffer.applyBookmarks(ops.bookmarks().get(file.toString()));
        // The file changed outside the editor and a bookmark followed its content to a new line —
        // write the corrected indices back so the session self-heals (once; later opens match exactly).
        if (reanchored) {
            persistBookmarks(buffer);
        }
    }

    /** Moves a file's bookmarks from {@code oldKey} to {@code newKey} (used by in-app rename). */
    void migrateKey(String oldKey, String newKey) {
        if (oldKey == null || oldKey.equals(newKey)) {
            return;
        }
        var map = ops.bookmarks();
        List<Bookmark> moved = map.remove(oldKey);
        if (moved != null) {
            map.put(newKey, moved);
            ops.saveBookmarks();
            panel.refresh();
        }
    }

    // --- editor + command entry points ---------------------------------------------------------------

    /**
     * Handles the editor right-click menu's bookmark item: adds a bookmark on an unbookmarked line, or asks
     * for confirmation before removing an existing one. (The keyboard toggle {@code C-c m} removes without a
     * prompt.) The gutter marker itself is display-only — clicking it does nothing.
     */
    void onBookmarkToggleRequest(EditorBuffer buffer, int line) {
        if (buffer.getBookmarkManager().isBookmarked(line)) {
            Alert confirm = new Alert(
                    Alert.AlertType.CONFIRMATION,
                    tr("dialog.removeBookmark.body", line + 1),
                    ButtonType.OK,
                    ButtonType.CANCEL);
            confirm.initOwner(host.window());
            confirm.setTitle(tr("dialog.removeBookmark.title"));
            confirm.setHeaderText(null);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                buffer.removeBookmark(line);
            }
        } else {
            buffer.toggleBookmark(line); // add
        }
    }

    /** Toggles a bookmark on the active editor's caret line. */
    void toggleAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.getPath() != null) {
            b.toggleBookmark(b.getArea().getCurrentParagraph());
        } else if (b != null) {
            host.setStatus(tr("status.saveBeforeBookmark"));
        }
    }

    /** Adds (if absent) or edits the note on the bookmark at the active editor's caret line. */
    void editNoteAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            return;
        }
        int line = b.getArea().getCurrentParagraph();
        var mgr = b.getBookmarkManager();
        String current = "";
        for (Bookmark bm : mgr.snapshot()) {
            if (bm.line() == line) {
                current = bm.note();
                break;
            }
        }
        ops.promptText(tr("dialog.bookmarkNote.title"), tr("dialog.bookmarkNote.content"), current, note -> {
            if (!mgr.isBookmarked(line)) {
                mgr.add(line, note.strip());
                b.refreshGutterLine(line);
            } else {
                mgr.setNote(line, note.strip());
            }
        });
    }

    /** Jumps to the next/previous bookmark within the active file (wrapping). */
    void jump(boolean forward) {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        int from = b.getArea().getCurrentParagraph();
        Integer target = forward
                ? b.getBookmarkManager().next(from)
                : b.getBookmarkManager().previous(from);
        if (target != null) {
            ops.navigateToLine(target);
        } else {
            host.setStatus(tr("status.noBookmarksInFile"));
        }
    }

    void clearInFile() {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.clearBookmarks();
        }
    }

    /** Opens the cross-file jump picker ({@code bookmarks.jump}). */
    void openJumpPalette() {
        jumpPalette.show(host.window());
    }

    // --- BookmarksPanel.Actions (open buffer if loaded, else mutate the persisted closed-file list) ---

    /**
     * The active project's bookmarks, flattened for the jump picker in the <em>stored order</em> — files
     * in their map order, bookmarks in their list order — i.e. exactly the order the Bookmarks tool
     * window shows (including any custom drag/move reordering), so the picker and the panel always agree.
     */
    private List<BookmarkEntry> allBookmarkEntries() {
        List<BookmarkEntry> out = new ArrayList<>();
        ops.bookmarks().forEach((path, marks) -> {
            if (marks != null) {
                Path file = Path.of(path);
                marks.forEach(bm -> out.add(new BookmarkEntry(file, bm)));
            }
        });
        return out;
    }

    /** Reorders a bookmark within its file (Bookmarks tool window drag / Alt+Up/Down), then persists. */
    private void moveBookmark(Path file, int fromIndex, int toIndex) {
        var marks = ops.bookmarks().get(file.toString());
        if (marks == null || !inRange(fromIndex, marks.size()) || !inRange(toIndex, marks.size())) {
            return;
        }
        List<Bookmark> list = new ArrayList<>(marks);
        list.add(toIndex, list.remove(fromIndex));
        ops.bookmarks().put(file.toString(), list);
        ops.saveBookmarks();
        panel.refresh();
    }

    /** Reorders a whole file group among the file headers (Bookmarks tool window drag / Alt+Up/Down). */
    private void moveBookmarkFile(int fromIndex, int toIndex) {
        var map = ops.bookmarks();
        List<String> keys = new ArrayList<>(map.keySet());
        if (!inRange(fromIndex, keys.size()) || !inRange(toIndex, keys.size())) {
            return;
        }
        keys.add(toIndex, keys.remove(fromIndex));
        var reordered = new LinkedHashMap<String, List<Bookmark>>();
        keys.forEach(k -> reordered.put(k, map.get(k)));
        map.clear();
        map.putAll(reordered);
        ops.saveBookmarks();
        panel.refresh();
    }

    private static boolean inRange(int i, int size) {
        return i >= 0 && i < size;
    }

    private static String bookmarkLabel(Bookmark bm) {
        if (!bm.note().isEmpty()) {
            return bm.note();
        }
        return bm.lineText().isEmpty() ? "line " + (bm.line() + 1) : bm.lineText();
    }

    /**
     * Opens the bookmark's file and jumps to its line — in {@code projectKey}'s window. When the bookmark
     * belongs to the active window's project this lands in place (same behavior as before); for a General or
     * cross-project bookmark it focuses (or opens) that project's window (see {@link Ops#openInProjectWindow}).
     */
    private void bookmarkActivate(String projectKey, Path file, int line) {
        ops.openInProjectWindow(projectKey, file, line);
    }

    /** Sets a bookmark's note — via the open buffer if loaded (active bucket only), else directly in the map. */
    private void bookmarkSetNote(String projectKey, Path file, int line, String note) {
        if (isActiveBucket(projectKey)) {
            EditorBuffer open = ops.bufferForPath(file);
            if (open != null) {
                open.getBookmarkManager().setNote(line, note);
                return;
            }
        }
        updateBucketBookmarks(
                projectKey, file, marks -> marks.replaceAll(bm -> bm.line() == line ? bm.withNote(note) : bm));
    }

    /** Deletes one bookmark — via the open buffer if loaded (active bucket only), else directly in the map. */
    private void bookmarkDelete(String projectKey, Path file, int line) {
        if (isActiveBucket(projectKey)) {
            EditorBuffer open = ops.bufferForPath(file);
            if (open != null) {
                open.removeBookmark(line);
                return;
            }
        }
        updateBucketBookmarks(projectKey, file, marks -> marks.removeIf(bm -> bm.line() == line));
    }

    /** Deletes all bookmarks in a file — via the open buffer if loaded (active bucket only), else the map. */
    private void bookmarkDeleteAll(String projectKey, Path file) {
        if (isActiveBucket(projectKey)) {
            EditorBuffer open = ops.bufferForPath(file);
            if (open != null) {
                open.clearBookmarks();
                return;
            }
        }
        Map<String, List<Bookmark>> bucket = bucketFor(projectKey);
        if (bucket != null && bucket.remove(file.toString()) != null) {
            ops.saveBookmarks();
            panel.refresh();
        }
    }

    /** Whether {@code projectKey} is this window's active bucket — the only one whose files can be open here. */
    private boolean isActiveBucket(String projectKey) {
        return (projectKey == null ? "" : projectKey).equals(ops.currentProjectKey());
    }

    /** The bookmark bucket (path → bookmarks) for a project key, or {@code null} if that bucket has no bookmarks. */
    private Map<String, List<Bookmark>> bucketFor(String projectKey) {
        return ops.allBookmarks().get(projectKey == null ? "" : projectKey);
    }

    /**
     * Applies a mutation to a file's bookmark list in the given project bucket, then saves + refreshes. Used
     * for closed files, and for any bookmark in a bucket other than this window's active one (a General or
     * cross-project row shown in the panel while a different project is active) — those are never routed
     * through a live buffer, whose manager is tied to the active bucket.
     */
    private void updateBucketBookmarks(String projectKey, Path file, Consumer<List<Bookmark>> mutator) {
        Map<String, List<Bookmark>> map = bucketFor(projectKey);
        if (map == null) {
            return;
        }
        List<Bookmark> marks = map.get(file.toString());
        if (marks == null) {
            return;
        }
        marks = new ArrayList<>(marks);
        mutator.accept(marks);
        if (marks.isEmpty()) {
            map.remove(file.toString());
        } else {
            map.put(file.toString(), marks);
        }
        ops.saveBookmarks();
        panel.refresh();
    }
}
