package com.editora.ui;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import com.editora.config.FileIdentity;
import com.editora.config.NoteStatus;
import com.editora.config.NoteStore;
import com.editora.config.PathKeys;
import com.editora.config.PersonalNote;
import com.editora.editor.EditorBuffer;
import com.editora.editor.NoteDraft;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import static com.editora.i18n.Messages.tr;

/**
 * Personal Notes feature (file-attached annotations stored outside the file), extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link NotesPanel} + the two
 * cross-file jump pickers, the per-buffer persist/restore (with anchor re-keying), the note editor dialog,
 * and the {@code notes.*} flow. {@code MainController} keeps the {@code ToolWindow} (built with
 * {@link #panel()}), the global {@code promptText}/{@code noteKey}/{@code navigateToLine}/{@code openPath}
 * helpers (reached via {@link Ops}), the rename-migration call ({@link #migrateKey}), and the
 * {@code view.toggleNotes}/{@code tool.notes} command registrations (guarded by {@link #ifEnabled}).
 */
final class NotesCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} (open/jump, the shared note-key, the note store). */
    interface Ops {
        void openPath(Path file);

        void navigateToLine(int line);

        /** Canonical-path store key for a buffer's notes (shared with the rename-migration path). */
        String noteKey(EditorBuffer buffer);

        /** The open buffer for a store key, or {@code null} if that file isn't open. */
        EditorBuffer bufferForKey(String fileKey);

        /** Installs the user's configured caret/edit keybindings on a dialog text control. */
        void installEmacsKeys(javafx.scene.control.TextInputControl control);

        /** Shows/hides the Notes tool window's stripe button (transient availability). */
        void setToolWindowAvailable(boolean available);

        /** The active project's personal-notes bucket (keyed by canonical path). */
        Map<String, List<PersonalNote>> notes();

        /** Persists {@code notes.json}. */
        void saveNotes();
    }

    /** A note plus the store key (file) it belongs to, for the cross-file pickers. */
    private record NoteEntry(String fileKey, PersonalNote note) {}

    private final CoordinatorHost host;
    private final Ops ops;
    private final NotesPanel panel;
    private final QuickOpen<NoteEntry> jumpPalette;
    private final QuickOpen<NoteEntry> searchPalette;

    private boolean supportApplied;

    // Coalesce the per-edit (line-shift) persist off the FX hot path — see schedulePersistNotes. (#551)
    private final javafx.animation.PauseTransition persistDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private EditorBuffer pendingPersist;

    NotesCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        persistDebounce.setOnFinished(e -> flushPendingPersist());
        this.panel = new NotesPanel(ops::notes, new NotesPanel.Actions() {
            @Override
            public void openAndJump(String fileKey, PersonalNote note) {
                noteActivate(fileKey, note);
            }

            @Override
            public void editBody(String fileKey, PersonalNote note) {
                noteEditBody(fileKey, note);
            }

            @Override
            public void setStatus(String fileKey, PersonalNote note, NoteStatus status) {
                noteSetStatus(fileKey, note, status);
            }

            @Override
            public void delete(String fileKey, PersonalNote note) {
                noteDelete(fileKey, note);
            }

            @Override
            public void deleteAll(String fileKey) {
                noteDeleteAll(fileKey);
            }
        });
        this.jumpPalette = new QuickOpen<>(
                tr("notes.jumpTitle"),
                tr("notes.jumpPrompt"),
                this::allNoteEntries,
                e -> noteEntryLabel(e.note()),
                e -> Path.of(e.fileKey()).getFileName() + ":"
                        + (e.note().anchor().line() + 1),
                e -> noteActivate(e.fileKey(), e.note()));
        // Search Notes: same picker, but the query matches the full body + tags + file (not just the
        // first line) so you can find a note by any word in it.
        this.searchPalette = new QuickOpen<>(
                tr("notes.searchTitle"),
                tr("notes.searchPrompt"),
                this::allNoteEntries,
                e -> noteEntryLabel(e.note()),
                e -> Path.of(e.fileKey()).getFileName() + ":"
                        + (e.note().anchor().line() + 1),
                NotesCoordinator::noteSearchText,
                e -> noteActivate(e.fileKey(), e.note()));
    }

    NotesPanel panel() {
        return panel;
    }

    /** Binds the jump pickers to the shared overlay host (called once from {@code MainController.wireOverlayHost}). */
    void wireOverlayHost() {
        jumpPalette.setOverlayHost(host.overlayHost());
        searchPalette.setOverlayHost(host.overlayHost());
    }

    boolean isEnabled() {
        return host.settings().isNotesSupport();
    }

    /** Runs {@code action} only when Personal Notes is enabled; otherwise reports it (no-op command/key). */
    void ifEnabled(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.notesDisabled"));
        }
    }

    /**
     * Reconciles all Personal Notes UI with the "Enable Personal Notes" setting (default off). When off:
     * the Notes tool window is hidden and the editor "Add Note" menu items + commands/keybindings no-op.
     * On the off→on transition each open buffer loads its saved notes from {@code notes.json}. Indicator
     * visibility itself is applied per-buffer in {@code MainController.applyViewSettings} (gated by both
     * flags). Runs at startup and on every settings apply (mirrors {@link GitCoordinator#applySupport}).
     */
    void applySupport() {
        boolean on = isEnabled();
        ops.setToolWindowAvailable(on);
        host.forEachBuffer(b -> b.setNotesEnabled(on));
        if (on && !supportApplied) {
            // off→on: populate open buffers' notes (restoreNotes early-returns while disabled).
            host.forEachBuffer(this::restoreNotes);
            panel.refresh();
        }
        supportApplied = on;
    }

    // --- per-buffer persistence (called from addBuffer / session restore / save) ---------------------

    /**
     * Coalesces the per-edit persist (fired from {@code NoteManager.onChanged} when a line shift moves a note) so
     * holding Enter above a note doesn't do a synchronous atomic notes.json write + tree rebuild per newline on
     * the FX thread. The write lands once, ~300 ms after editing settles; relocate-on-open recovers any indices
     * lost to a crash before then. (#551)
     */
    void schedulePersistNotes(EditorBuffer buffer) {
        pendingPersist = buffer;
        persistDebounce.playFromStart();
    }

    private void flushPendingPersist() {
        EditorBuffer b = pendingPersist;
        pendingPersist = null;
        if (b != null) {
            persistNotes(b);
        }
    }

    /** Persists the active buffer's notes (keyed by canonical path), preserving the panel's order. */
    void persistNotes(EditorBuffer buffer) {
        if (!isEnabled() || buffer.getPath() == null) {
            return;
        }
        String key = ops.noteKey(buffer);
        List<PersonalNote> snap = buffer.getNoteManager().snapshot();
        var map = ops.notes();
        if (snap.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, NoteStore.mergePreservingOrder(map.get(key), snap));
        }
        ops.saveNotes();
        panel.refresh();
    }

    /** Re-applies a file's saved notes after open, re-attaching by content hash if the file was renamed. */
    void restoreNotes(EditorBuffer buffer) {
        if (!isEnabled() || buffer.getPath() == null) {
            return;
        }
        String key = ops.noteKey(buffer);
        var map = ops.notes();
        List<PersonalNote> saved = map.get(key);
        boolean rekeyed = false;
        if (saved == null && !map.isEmpty()) {
            // The file may have been renamed/moved outside Editora — match by content hash and re-key.
            String matchKey = findNoteKeyByIdentity(map, buffer.fileIdentity());
            if (matchKey != null) {
                saved = map.remove(matchKey);
                map.put(key, saved);
                rekeyed = true;
            }
        }
        boolean moved = buffer.applyNotes(saved);
        if (moved || rekeyed) {
            persistNotes(buffer); // self-heal corrected positions / re-key / orphan status
        } else {
            panel.refresh();
        }
    }

    /** Moves a file's personal notes from {@code oldKey} to {@code newKey} (used by in-app rename). */
    void migrateKey(String oldKey, String newKey) {
        if (oldKey == null || oldKey.equals(newKey)) {
            return;
        }
        var map = ops.notes();
        List<PersonalNote> moved = map.remove(oldKey);
        if (moved != null) {
            map.put(newKey, moved);
            ops.saveNotes();
            panel.refresh();
        }
    }

    private static String findNoteKeyByIdentity(Map<String, List<PersonalNote>> map, FileIdentity id) {
        // A content-hash match only means "same bytes" — it is trusted only when the note's own file is gone
        // from disk (a rename). Otherwise opening a copy of an annotated file stole its notes; see PathKeys.
        return PathKeys.findKeyByIdentity(map, id, path -> {
            try {
                return !path.isBlank() && java.nio.file.Files.exists(java.nio.file.Path.of(path));
            } catch (RuntimeException e) {
                return false; // an unparseable stored path can't be an existing file
            }
        });
    }

    // --- add / edit / delete / jump (command + gutter + context-menu entry points) -------------------

    /** "Add Personal Note" (context menu / command): captures the selection/caret anchor, prompts for a body. */
    void addNoteFromContext(EditorBuffer buffer) {
        if (buffer == null || buffer.getPath() == null) {
            host.setStatus(tr("notes.saveFirst"));
            return;
        }
        NoteDraft draft = buffer.captureNoteDraft();
        showNoteDialog(
                "",
                body -> {
                    buffer.getNoteManager()
                            .add(PersonalNote.create(
                                    buffer.fileIdentity(), draft.scope(), draft.anchor(), body, List.of()));
                    buffer.refreshGutter();
                },
                null);
    }

    void addNoteAtCaret() {
        addNoteFromContext(host.activeBuffer());
    }

    /**
     * Multi-line note editor as an in-scene overlay. Saves the (non-blank) body via {@code onAccept}; when
     * {@code onDelete} is non-null an extra Delete button is shown (used when editing an existing note).
     * Enter inserts a newline; Ctrl/Cmd+Enter saves.
     */
    private void showNoteDialog(String initial, Consumer<String> onAccept, Runnable onDelete) {
        TextArea editor = new TextArea(initial == null ? "" : initial);
        editor.setWrapText(true);
        editor.setPrefRowCount(6);
        editor.setPrefColumnCount(42);
        // Honor the user's configured keybindings (Emacs caret movement + basic editing) in the note box.
        ops.installEmacsKeys(editor);
        Label prompt = new Label(tr("dialog.note.content"));
        Label hint = new Label(tr("dialog.note.markdownHint"));
        hint.getStyleClass().add("note-dialog-hint");
        VBox body = new VBox(6, prompt, editor, hint);
        // Editing an existing note: the Delete button confirms first (it's destructive).
        OverlayInput.Extra extra =
                onDelete == null ? null : new OverlayInput.Extra(tr("notes.delete"), () -> confirmDelete(onDelete));
        OverlayInput.show(
                host.overlayHost(),
                tr("dialog.note.title"),
                body,
                editor,
                tr("dialog.save"),
                null,
                () -> {
                    String text = editor.getText().strip();
                    if (!text.isBlank()) {
                        onAccept.accept(text);
                    }
                },
                extra,
                true);
    }

    /** Confirms (native dialog) before running a destructive note delete from the editor popup. */
    private void confirmDelete(Runnable onConfirm) {
        Alert confirm = new Alert(
                Alert.AlertType.CONFIRMATION,
                tr("dialog.note.deleteConfirm.content"),
                ButtonType.OK,
                ButtonType.CANCEL);
        confirm.initOwner(host.window());
        confirm.setTitle(tr("dialog.note.deleteConfirm.title"));
        confirm.setHeaderText(null);
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
            onConfirm.run();
        }
    }

    /** Edit the personal note at the caret — the one whose span contains the caret, else the first on the
     *  caret's line. Backs the {@code notes.editNote} command (the inline marker has no gutter click). */
    void editNoteAtCaret() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        PersonalNote note = buffer.getNoteManager().noteAt(buffer.getArea().getCaretPosition());
        if (note == null) {
            var ns = buffer.getNoteManager().notesOnLine(buffer.getArea().getCurrentParagraph());
            if (!ns.isEmpty()) {
                note = ns.get(0);
            }
        }
        if (note != null) {
            editOpenBufferNote(buffer, note);
        }
    }

    private void editOpenBufferNote(EditorBuffer buffer, PersonalNote note) {
        showNoteDialog(note.body(), body -> buffer.getNoteManager().update(note.withBody(body)), () -> {
            buffer.getNoteManager().remove(note.id());
            buffer.refreshGutter();
        });
    }

    /** Edits the note whose inline start marker was clicked (wired into {@code EditorBuffer}). */
    void onNoteMarkerClick(EditorBuffer buffer, PersonalNote note) {
        if (note != null) {
            editOpenBufferNote(buffer, note);
        }
    }

    /** Toggles the resolved/active status of the note at the caret (the first on the line as a fallback). */
    void toggleResolvedAtCaret() {
        EditorBuffer buffer = host.activeBuffer();
        if (buffer == null) {
            return;
        }
        PersonalNote note = buffer.getNoteManager().noteAt(buffer.getArea().getCaretPosition());
        if (note == null) {
            var ns = buffer.getNoteManager().notesOnLine(buffer.getArea().getCurrentParagraph());
            if (!ns.isEmpty()) {
                note = ns.get(0);
            }
        }
        if (note == null) {
            host.setStatus(tr("status.noNotesInFile"));
            return;
        }
        NoteStatus next = note.status() == NoteStatus.RESOLVED ? NoteStatus.ACTIVE : NoteStatus.RESOLVED;
        buffer.getNoteManager().setStatus(note.id(), next);
        host.setStatus(tr(next == NoteStatus.RESOLVED ? "status.note.resolved" : "status.note.reopened"));
    }

    /** Deletes the (first) personal note on the active buffer's caret line. */
    void deleteNoteAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        var ns = b.getNoteManager().notesOnLine(b.getArea().getCurrentParagraph());
        if (ns.isEmpty()) {
            host.setStatus(tr("status.noNotesInFile"));
            return;
        }
        b.getNoteManager().remove(ns.get(0).id());
        b.refreshGutter();
    }

    void jumpNote(boolean forward) {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return;
        }
        int from = b.getArea().getCurrentParagraph();
        Integer target =
                forward ? b.getNoteManager().next(from) : b.getNoteManager().previous(from);
        if (target != null) {
            ops.navigateToLine(target);
        } else {
            host.setStatus(tr("status.noNotesInFile"));
        }
    }

    /** Opens the cross-file jump picker ({@code notes.jump}). */
    void openJumpPalette() {
        jumpPalette.show(host.window());
    }

    /** Opens a cross-file picker that searches notes by their full body, tags, and file path. */
    void searchNotes() {
        searchPalette.show(host.window());
    }

    /** The text {@code notes.search} matches against: the note's body + tags + file path. */
    private static String noteSearchText(NoteEntry e) {
        PersonalNote n = e.note();
        return n.body() + " " + String.join(" ", n.tags()) + " " + e.fileKey();
    }

    private List<NoteEntry> allNoteEntries() {
        List<NoteEntry> out = new ArrayList<>();
        ops.notes().forEach((key, notes) -> {
            if (notes != null) {
                notes.forEach(n -> out.add(new NoteEntry(key, n)));
            }
        });
        return out;
    }

    private static String noteEntryLabel(PersonalNote n) {
        String body = n.body().strip();
        String first = body.isEmpty() ? "" : body.lines().findFirst().orElse("");
        return first.isEmpty() ? tr("notes.empty") : first;
    }

    // --- NotesPanel.Actions (open buffer if loaded, else mutate the persisted closed-file list) -------

    private void noteActivate(String fileKey, PersonalNote note) {
        String path = note.file() != null && !note.file().path().isBlank()
                ? note.file().path()
                : fileKey;
        ops.openPath(Path.of(path));
        int line = note.anchor().line();
        Platform.runLater(() -> ops.navigateToLine(line));
    }

    private void noteEditBody(String fileKey, PersonalNote note) {
        EditorBuffer open = ops.bufferForKey(fileKey);
        if (open != null) {
            editOpenBufferNote(open, note);
        } else {
            showNoteDialog(
                    note.body(),
                    body -> updateClosedFileNotes(
                            fileKey, list -> list.replaceAll(n -> n.id().equals(note.id()) ? n.withBody(body) : n)),
                    () -> updateClosedFileNotes(fileKey, list -> list.removeIf(n -> n.id().equals(note.id()))));
        }
    }

    private void noteSetStatus(String fileKey, PersonalNote note, NoteStatus status) {
        EditorBuffer open = ops.bufferForKey(fileKey);
        if (open != null) {
            open.getNoteManager().setStatus(note.id(), status);
        } else {
            updateClosedFileNotes(
                    fileKey, list -> list.replaceAll(n -> n.id().equals(note.id()) ? n.withStatus(status) : n));
        }
    }

    private void noteDelete(String fileKey, PersonalNote note) {
        EditorBuffer open = ops.bufferForKey(fileKey);
        if (open != null) {
            open.getNoteManager().remove(note.id());
            open.refreshGutter();
        } else {
            updateClosedFileNotes(fileKey, list -> list.removeIf(n -> n.id().equals(note.id())));
        }
    }

    private void noteDeleteAll(String fileKey) {
        EditorBuffer open = ops.bufferForKey(fileKey);
        if (open != null) {
            open.getNoteManager().clear();
            open.refreshGutter();
        } else {
            ops.notes().remove(fileKey);
            ops.saveNotes();
            panel.refresh();
        }
    }

    private void updateClosedFileNotes(String fileKey, Consumer<List<PersonalNote>> mutator) {
        var map = ops.notes();
        List<PersonalNote> list = map.get(fileKey);
        if (list == null) {
            return;
        }
        list = new ArrayList<>(list);
        mutator.accept(list);
        if (list.isEmpty()) {
            map.remove(fileKey);
        } else {
            map.put(fileKey, list);
        }
        ops.saveNotes();
        panel.refresh();
    }

    void exportNotes() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(tr("dialog.exportNotes.title"));
        chooser.setInitialFileName("notes.json");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("JSON", "*.json"));
        File f = chooser.showSaveDialog(host.window());
        if (f == null) {
            return;
        }
        try {
            new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(f, ops.notes());
            host.setStatus(tr("status.notesExported", f.toString()));
        } catch (IOException e) {
            host.setStatus(tr("status.notesExportFailed", e.getMessage()));
        }
    }
}
