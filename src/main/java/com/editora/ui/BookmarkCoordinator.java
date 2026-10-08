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
import com.editora.config.BookmarkMnemonics;
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

        /**
         * This window rewrote {@code fileKey}'s bookmarks ({@code null}: several files) in {@code bucket} (one
         * of the per-project maps of {@link #allBookmarks}): the other windows re-read them (see
         * {@link BookmarkCoordinator#storeChangedElsewhere}).
         */
        default void bookmarksStored(Map<String, ?> bucket, String fileKey) {}
    }

    /** A bookmark plus the file it belongs to, for the cross-file jump picker. */
    private record BookmarkEntry(Path file, Bookmark bm) {}

    private final CoordinatorHost host;
    private final Ops ops;
    private final BookmarksPanel panel;
    private final QuickOpen<BookmarkEntry> jumpPalette;
    private Runnable onChanged = () -> {};

    // The per-edit (line-shift) persist is coalesced: a synchronous atomic bookmarks.json write + a full
    // Bookmarks-tree rebuild per newline (holding Enter above a bookmark) would block the FX thread. Explicit
    // user actions (toggle/add/reorder) still persist immediately via persistBookmarks. (#551)
    private final javafx.animation.PauseTransition persistDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    /** Every buffer with a debounced persist outstanding. One slot lost the first buffer's line shifts
     *  whenever a second buffer was edited inside the same debounce window. */
    private final java.util.Set<EditorBuffer> pendingPersist =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

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
                tr("nav.bookmarks.title"),
                tr("nav.bookmarks.prompt"),
                this::allBookmarkEntries,
                e -> bookmarkLabel(e.bm()),
                e -> e.bm().isFolder() ? e.file().toString() : e.file().getFileName() + ":" + (e.bm().line() + 1),
                // The jump picker is scoped to the active project's bookmarks, so they open in this window.
                e -> bookmarkActivate(ops.currentProjectKey(), e.file(), e.bm().line()));
    }

    BookmarksPanel panel() {
        return panel;
    }

    void setOnChanged(Runnable callback) {
        onChanged = callback == null ? () -> {} : callback;
    }

    /** The path keys of this project's stored bookmarks (files and folders). No filesystem access. */
    java.util.Collection<String> storedKeys() {
        return List.copyOf(ops.bookmarks().keySet());
    }

    boolean hasBookmarks(Path file) {
        if (file == null) {
            return false;
        }
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null) {
            return !open.getBookmarkManager().snapshot().isEmpty();
        }
        List<Bookmark> stored = bookmarksFor(file);
        return stored != null && !stored.isEmpty();
    }

    /** Adds a deterministic file-manager bookmark at the file's first line without opening a tab. */
    void addBookmark(Path file) {
        if (file == null) {
            return;
        }
        if (java.nio.file.Files.isDirectory(file)) {
            addFolderBookmark(file);
            return;
        }
        addBookmark(file, 0);
    }

    private void addFolderBookmark(Path folder) {
        String key = normalizedKey(folder);
        List<Bookmark> marks = bookmarksFor(folder);
        List<Bookmark> updated = marks == null ? new ArrayList<>() : new ArrayList<>(marks);
        if (updated.stream().noneMatch(Bookmark::isFolder)) {
            updated.add(Bookmark.folder());
            ops.bookmarks().put(key, updated);
            ops.saveBookmarks();
            refreshViews();
            ops.bookmarksStored(ops.bookmarks(), key);
        }
    }

    /** Adds a bookmark to a specific line from a read-only code preview without opening an editor tab. */
    void addBookmark(Path file, int requestedLine) {
        if (file == null) {
            return;
        }
        int line = Math.max(0, requestedLine);
        EditorBuffer open = ops.bufferForPath(file);
        if (open != null) {
            line = Math.min(line, open.getArea().getParagraphs().size() - 1);
            if (!open.getBookmarkManager().isBookmarked(line)) {
                open.getBookmarkManager().add(line, "");
                open.refreshGutterLine(line);
            }
            return;
        }
        String key = normalizedKey(file);
        List<Bookmark> marks = bookmarksFor(file);
        List<Bookmark> updated = marks == null ? new ArrayList<>() : new ArrayList<>(marks);
        int targetLine = line;
        if (updated.stream().noneMatch(mark -> mark.line() == targetLine)) {
            updated.add(new Bookmark(targetLine, "", ""));
            ops.bookmarks().put(key, updated);
            ops.saveBookmarks();
            refreshViews();
            ops.bookmarksStored(ops.bookmarks(), key); // another window may have the file open
        }
    }

    private List<Bookmark> bookmarksFor(Path file) {
        Map<String, List<Bookmark>> map = ops.bookmarks();
        List<Bookmark> marks = map.get(file.toString());
        return marks != null ? marks : map.get(normalizedKey(file));
    }

    private static String normalizedKey(Path file) {
        try {
            return file.toAbsolutePath().normalize().toString();
        } catch (RuntimeException e) {
            return file.toString();
        }
    }

    private void refreshViews() {
        panel.refresh();
        onChanged.run();
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
        pendingPersist.add(buffer);
        persistDebounce.playFromStart();
        onChanged.run();
    }

    /** Writes every outstanding debounced persist now. Also called when the session is saved and when the
     *  window closes, so a close inside the debounce window does not drop the shifted positions. */
    void flushPendingPersist() {
        persistDebounce.stop();
        java.util.List<EditorBuffer> pending = java.util.List.copyOf(pendingPersist);
        pendingPersist.clear();
        pending.forEach(this::persistBookmarks);
    }

    void persistBookmarks(EditorBuffer buffer) {
        if (buffer.isNarrowed()) {
            // While narrowed the area holds only the region, so every line number is region-relative.
            // Persisting them would rewrite the store with positions that are wrong for the file; the
            // in-memory marks are restored when the buffer is widened.
            return;
        }
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        var map = ops.bookmarks();
        String key = file.toString();
        List<Bookmark> stored = map.get(key);
        // Not the snapshot alone: a second window on this file has its own copy of the bookmarks, and
        // writing this one as the whole list deleted every bookmark the other had added.
        List<Bookmark> marks = MarkMerge.withForeign(
                stored, seenInStore.get(buffer), buffer.getBookmarkManager().snapshot(), Bookmark::line);
        if (marks.isEmpty()) {
            map.remove(key);
        } else {
            // Keep any custom order the user set in the Bookmarks tool window (the snapshot is line-order).
            map.put(key, BookmarkStore.mergePreservingOrder(stored, marks));
        }
        seenInStore.put(buffer, MarkMerge.keys(map.get(key), Bookmark::line));
        ops.saveBookmarks();
        refreshViews();
        ops.bookmarksStored(map, key);
    }

    /**
     * The bookmark lines each buffer last saw in the store for its file (when it loaded or wrote them): what
     * {@link MarkMerge} needs to tell "this buffer removed it" from "another window added it".
     */
    private final Map<EditorBuffer, java.util.Set<Integer>> seenInStore = new java.util.WeakHashMap<>();

    /**
     * Another window rewrote {@code fileKey}'s bookmarks ({@code null}: several files) in {@code bucket}. A
     * buffer of this window on that file shows them now — it would otherwise keep its stale copy and write
     * it back — and the panel, which lists every project's bookmarks, is redrawn.
     */
    void storeChangedElsewhere(Map<String, ?> bucket, String fileKey) {
        if (bucket == ops.bookmarks()) {
            host.forEachBuffer(b -> {
                if (b.getPath() == null || b.isNarrowed()) {
                    return; // a narrowed buffer's lines are region-relative; it merges when it next writes
                }
                String key = b.getPath().toString();
                if (fileKey == null || fileKey.equals(key)) {
                    List<Bookmark> stored = ops.bookmarks().get(key);
                    b.applyBookmarks(stored);
                    seenInStore.put(b, MarkMerge.keys(stored, Bookmark::line));
                }
            });
        }
        refreshViews();
    }

    /** Re-applies a file's saved bookmarks after it is opened (and paints their gutter markers). */
    void restoreBookmarks(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        List<Bookmark> stored = ops.bookmarks().get(file.toString());
        boolean reanchored = buffer.applyBookmarks(stored);
        seenInStore.put(buffer, MarkMerge.keys(stored, Bookmark::line));
        // The file changed outside the editor and a bookmark followed its content to a new line —
        // write the corrected indices back so the session self-heals (once; later opens match exactly).
        if (reanchored) {
            persistBookmarks(buffer);
        }
    }

    /**
     * A rename or move ({@code old → target}, a file or a folder): the bookmarks stored for it, and for every
     * file below it, move to the new path. Only the tab menu's Rename used to do this, for its one file.
     */
    void pathRenamed(Path old, Path target) {
        String sep = old.getFileSystem().getSeparator();
        if (RenamedFileState.rekey(ops.bookmarks(), old.toString(), target.toString(), sep)) {
            ops.saveBookmarks();
            refreshViews();
            ops.bookmarksStored(ops.bookmarks(), null);
        }
    }

    /**
     * Save As re-pointed {@code buffer} from {@code oldPath}: its bookmarks are stored under the new path as
     * well. The file it left keeps its own while it is still on disk; an entry for a path that is not (a
     * Save As rolled back after a failed write) is dropped.
     */
    void bufferPathChanged(EditorBuffer buffer, Path oldPath) {
        Path now = buffer.getPath();
        var map = ops.bookmarks();
        String oldKey = oldPath == null ? null : oldPath.toString();
        if (oldKey != null && now != null && buffer.isNarrowed() && map.get(oldKey) != null) {
            map.put(now.toString(), new ArrayList<>(map.get(oldKey))); // region-relative lines can't be snapshotted
            ops.saveBookmarks();
        }
        if (oldKey != null
                && com.editora.vfs.Vfs.isLocal(oldPath)
                && !java.nio.file.Files.exists(oldPath)
                && map.remove(oldKey) != null) {
            ops.saveBookmarks();
        }
        boolean any = !buffer.getBookmarkManager().snapshot().isEmpty();
        if (now != null && !now.equals(oldPath) && (any || map.containsKey(now.toString()))) {
            pendingPersist.remove(buffer);
            // The buffer now IS this file: bookmarks stored for a file it overwrote are replaced, not merged in.
            seenInStore.put(buffer, MarkMerge.keys(map.get(now.toString()), Bookmark::line));
            persistBookmarks(buffer); // also when it has none: bookmarks of a file it overwrote are gone
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
            b.toggleBookmark(b.getFocusedArea().getCurrentParagraph());
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
        int line = b.getFocusedArea().getCurrentParagraph();
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
        int from = b.getFocusedArea().getCurrentParagraph();
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
    /**
     * {@code bookmarks.setMnemonic}: give the bookmark at the caret a one-character shortcut, creating
     * the bookmark first if there isn't one.
     *
     * <p>Creating it is right here and wrong in {@link BookmarkMnemonics#assign}: at this level the user
     * has pointed at a line and asked for a shortcut to it, which plainly means "bookmark this"; down
     * there it would be a side effect of relabelling.
     */
    void setMnemonicAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.bookmarks.noFile"));
            return;
        }
        if (b.isNarrowed()) {
            // The caret line is region-relative and a narrowed buffer is never persisted, so the bookmark
            // could not be found in the store — and the store must not be rewritten from that lookup.
            host.setStatus(tr("status.bookmarks.mnemonicNarrowed"));
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        Path file = b.getPath();
        ops.promptText(tr("dialog.bookmarkMnemonic.title"), tr("dialog.bookmarkMnemonic.content"), "", typed -> {
            String m = BookmarkMnemonics.normalize(typed);
            if (m.isEmpty() && !typed.strip().isEmpty()) {
                host.setStatus(tr("status.bookmarks.badMnemonic"));
                return;
            }
            var mgr = b.getBookmarkManager();
            if (!mgr.isBookmarked(line)) {
                mgr.add(line, "");
                b.refreshGutterLine(line);
            }
            persistBookmarks(b); // the map must hold this bookmark before assign() can find it
            var updated = BookmarkMnemonics.assign(ops.bookmarks(), file.toString(), line, m);
            // In place, key by key: assign() answers with an unordered copy (and with the very map it was
            // given when the bookmark is not there), so clear-then-putAll shuffled the files' order in the
            // Bookmarks tool window and could empty the store.
            ops.bookmarks().replaceAll((key, marks) -> updated.getOrDefault(key, marks));
            ops.saveBookmarks();
            restoreBookmarks(b); // pull the mnemonic back into the live manager
            refreshViews();
            ops.bookmarksStored(ops.bookmarks(), null); // the mnemonic may have been taken from another file
            host.setStatus(
                    m.isEmpty()
                            ? tr("status.bookmarks.mnemonicCleared")
                            : tr("status.bookmarks.mnemonicSet", m.toUpperCase(java.util.Locale.ROOT)));
        });
    }

    /** {@code bookmarks.gotoMnemonic<N>}: jump to whatever holds {@code mnemonic} in this project. */
    void gotoMnemonic(String mnemonic) {
        var found = BookmarkMnemonics.find(ops.bookmarks(), mnemonic);
        if (found == null) {
            host.setStatus(tr("status.bookmarks.noMnemonic", mnemonic.toUpperCase(java.util.Locale.ROOT)));
            return;
        }
        // In place, through the load-aware open: a bare runLater(navigateToLine) ran against the still-empty
        // loading shell of a file that had no tab and left the caret on line 1.
        ops.openInProjectWindow(
                ops.currentProjectKey(), Path.of(found.file()), found.bookmark().line());
    }

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
        refreshViews();
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
        refreshViews();
    }

    private static boolean inRange(int i, int size) {
        return i >= 0 && i < size;
    }

    private static String bookmarkLabel(Bookmark bm) {
        if (bm.isFolder()) {
            return tr("bookmarks.folder");
        }
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
            refreshViews();
            ops.bookmarksStored(bucket, file.toString());
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
        refreshViews();
        ops.bookmarksStored(map, file.toString()); // another window may have that file open
    }
}
