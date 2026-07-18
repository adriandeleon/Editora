package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;
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

        /** The open buffer for {@code file}, or {@code null} if it isn't open. */
        EditorBuffer bufferForPath(Path file);

        /** Shows the in-scene single-line prompt (used for a bookmark note). */
        void promptText(String title, String label, String initial, Consumer<String> onAccept);

        /** The active project's bookmarks bucket (keyed by absolute path string). */
        Map<String, List<Bookmark>> bookmarks();

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
        this.panel = new BookmarksPanel(ops::bookmarks, new BookmarksPanel.Actions() {
            @Override
            public void openAndJump(Path file, int line) {
                bookmarkActivate(file, line);
            }

            @Override
            public void setNote(Path file, int line, String note) {
                bookmarkSetNote(file, line, note);
            }

            @Override
            public void delete(Path file, int line) {
                bookmarkDelete(file, line);
            }

            @Override
            public void deleteAll(Path file) {
                bookmarkDeleteAll(file);
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
                e -> bookmarkActivate(e.file(), e.bm().line()));
    }

    BookmarksPanel panel() {
        return panel;
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
     * Handles a click in the gutter: adds a bookmark on an unbookmarked line, or asks for confirmation
     * before removing an existing one. (The keyboard toggle {@code C-c m} removes without a prompt.)
     */
    void onGutterBookmarkClick(EditorBuffer buffer, int line) {
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

    /** Opens the file (if needed) and jumps to the bookmarked line. */
    private void bookmarkActivate(Path file, int line) {
        ops.openPath(file);
        Platform.runLater(() -> ops.navigateToLine(line));
    }

    /** Sets a bookmark's note — via the open buffer if loaded, else directly in the persisted map. */
    private void bookmarkSetNote(Path file, int line, String note) {
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null) {
            open.getBookmarkManager().setNote(line, note);
        } else {
            updateClosedFileBookmarks(
                    file, marks -> marks.replaceAll(bm -> bm.line() == line ? bm.withNote(note) : bm));
        }
    }

    /** Deletes one bookmark — via the open buffer if loaded, else directly in the persisted map. */
    private void bookmarkDelete(Path file, int line) {
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null) {
            open.removeBookmark(line);
        } else {
            updateClosedFileBookmarks(file, marks -> marks.removeIf(bm -> bm.line() == line));
        }
    }

    /** Deletes all bookmarks in a file — via the open buffer if loaded, else the persisted map. */
    private void bookmarkDeleteAll(Path file) {
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null) {
            open.clearBookmarks();
        } else {
            ops.bookmarks().remove(file.toString());
            ops.saveBookmarks();
            panel.refresh();
        }
    }

    /** Applies a mutation to a closed file's bookmark list in the persisted map, then saves + refreshes. */
    private void updateClosedFileBookmarks(Path file, Consumer<List<Bookmark>> mutator) {
        var map = ops.bookmarks();
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
