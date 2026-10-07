package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.LineEndings;
import com.editora.recovery.RecoveryPolicy;
import com.editora.recovery.RecoveryRecord;
import com.editora.recovery.RecoveryService;
import com.editora.recovery.RecoveryStore;

import static com.editora.i18n.Messages.tr;

/**
 * One window's side of crash recovery: keeps a copy of every unsaved buffer's text in the recovery directory
 * while the window is open, drops the copy when the buffer is saved, reverted or closed, and — in the first
 * window of a launch — offers back what a previous run left behind.
 *
 * <p><b>Nothing here runs per keystroke.</b> The buffers are not subscribed to. While the window has an
 * unsaved buffer, {@link RecoveryService} calls {@link #tick} about once a second; a tick compares each
 * unsaved buffer's document version with the one last copied (a few field reads) and, when the buffer is due
 * ({@link RecoveryPolicy#due}), takes its text and hands it to the service, which encodes and writes it on its
 * own thread. A window with no unsaved buffer is not ticked at all.
 *
 * <p><b>When a copy goes away.</b> When the buffer becomes clean (saved, or edited back to its saved text),
 * when its tab is closed, and when the window is closed after the user answered for every unsaved buffer
 * ({@link #closedByUser}). A window torn down any other way ({@link #dispose}) keeps its copies: nobody chose
 * to lose that text, so the next launch offers it.
 *
 * <p><b>Restoring never writes a file.</b> It opens the recovered text as an unsaved tab — bound to the
 * original file, with the disk state the edits were based on, so an on-disk change since then is noticed by
 * the ordinary external-change check and by the save path — and moves the copy into this session.
 */
final class RecoveryCoordinator {

    /** What recovery remembers about one open buffer. */
    private static final class Tracked {
        final String id = UUID.randomUUID().toString();
        ChangeListener<Boolean> dirtyListener;
        /** Document version at the previous tick: differs from the current one while the user is typing. */
        long seenVersion = -1;

        boolean copied;
        long copiedVersion;
        String copiedLineEnding;
        String copiedCharset;
        Path copiedPath;
        long copiedAtNanos;
        boolean toldTooLarge;
    }

    private final CoordinatorHost host;
    /** The window's file operations: tabs, buffers, load completion and the byte-order-mark choice. */
    private final FileWorkflowCoordinator files;
    /** Gives a buffer opened on a file its stored folds, bookmarks, breakpoints, notes and view mode. */
    private final Consumer<EditorBuffer> restorePerFileState;
    /** This window's key ({@code ""} = the no-project window). */
    private final Supplier<String> windowKey;

    private final Map<EditorBuffer, Tracked> tracked = new IdentityHashMap<>();
    private final Runnable ticker = () -> tick(System.nanoTime());
    private final Runnable finalizer = this::snapshotAllNow;
    private final Consumer<java.io.IOException> writeError = e -> Platform.runLater(() -> {
        if (!closed()) {
            hostError(tr("status.recovery.writeFailed", String.valueOf(e.getMessage())));
        }
    });
    private RecoveryService service;
    private RecoveryOfferWindow offer;
    private boolean closed;
    /** Asks before discarding {@code n} records; replaced in tests, where a dialog cannot be answered. */
    IntPredicate confirmDiscard = this::askDiscard;

    RecoveryCoordinator(
            CoordinatorHost host,
            FileWorkflowCoordinator files,
            Consumer<EditorBuffer> restorePerFileState,
            Supplier<String> windowKey) {
        this.host = host;
        this.files = files;
        this.restorePerFileState = restorePerFileState;
        this.windowKey = windowKey;
    }

    void registerCommands(CommandRegistry registry) {
        registry.register(Command.of("file.recoverUnsavedEdits", () -> showOffer(true)));
        registry.register(Command.of("view.toggleCrashRecovery", this::toggle));
    }

    /** Connects this window to the process's recovery service. */
    void attach(RecoveryService service) {
        if (service == null || this.service != null || closed) {
            return;
        }
        this.service = service;
        service.addFinalizer(finalizer);
        service.addWriteErrorListener(writeError);
        if (tracked.keySet().stream().anyMatch(b -> b.dirtyProperty().get())) {
            service.addTicker(ticker);
        }
    }

    /**
     * Start-up of an ordinary editor window: the first one of the launch looks for what a dead session left
     * and offers it. Not called for the standalone diff window, which is not a place to open documents; the
     * records then wait for the next ordinary launch.
     */
    void offerLeftovers() {
        if (service == null || closed) {
            return;
        }
        if (service.claimOffer()) {
            service.orphans()
                    .thenAccept(found -> Platform.runLater(() -> {
                        service.unresolved().addAll(found.entries());
                        service.unreadable().addAll(found.unreadable());
                        if (closed) {
                            return;
                        }
                        if (!found.unreadable().isEmpty()) {
                            host.setError(tr(
                                    "recovery.unreadable",
                                    found.unreadable().size(),
                                    service.store().root().toString()));
                        }
                        if (!found.entries().isEmpty()) {
                            showOffer(false);
                        }
                    }));
        }
    }

    private boolean closed() {
        return closed;
    }

    private void hostError(String message) {
        host.setError(message);
    }

    private boolean enabled() {
        return service != null && !closed && host.settings().isCrashRecovery();
    }

    // --- tracking open buffers ---

    /** A buffer got a tab in this window. Idempotent (a tab moved between editor groups is added again). */
    void track(EditorBuffer buffer) {
        if (closed || buffer == null || tracked.containsKey(buffer)) {
            return;
        }
        Tracked state = new Tracked();
        state.dirtyListener = (obs, was, dirty) -> {
            if (dirty) {
                state.seenVersion = -1; // first look copies at once: a new unsaved buffer is protected within a tick
                if (service != null && !closed) {
                    service.addTicker(ticker);
                }
            } else {
                forget(state); // saved, or edited back to the saved text: nothing left to recover
            }
        };
        buffer.dirtyProperty().addListener(state.dirtyListener);
        tracked.put(buffer, state);
        if (buffer.dirtyProperty().get() && service != null) {
            service.addTicker(ticker);
        }
    }

    /** The buffer's tab was closed: the user saved it, discarded it, or it had nothing unsaved. */
    void untrack(EditorBuffer buffer) {
        Tracked state = tracked.remove(buffer);
        if (state != null) {
            buffer.dirtyProperty().removeListener(state.dirtyListener);
            forget(state);
        }
    }

    private void forget(Tracked state) {
        state.copied = false;
        if (service != null) {
            service.remove(state.id);
        }
    }

    /**
     * The window is closing and the user has answered for every unsaved buffer (saved or discarded): drop
     * this window's copies. Called at the point the session is persisted for the close.
     */
    void closedByUser() {
        for (Tracked state : tracked.values()) {
            forget(state);
        }
        dispose();
    }

    /** The window is going away without that answer (or after {@link #closedByUser}): stop, delete nothing. */
    void dispose() {
        if (closed) {
            return;
        }
        closed = true;
        tracked.forEach((buffer, state) -> buffer.dirtyProperty().removeListener(state.dirtyListener));
        tracked.clear();
        if (service != null) {
            service.removeTicker(ticker);
            service.removeFinalizer(finalizer);
            service.removeWriteErrorListener(writeError);
        }
        if (offer != null) {
            offer.close();
        }
    }

    // --- taking copies ---

    /** One look at this window's unsaved buffers. {@code nowNanos} is passed in so tests can move time. */
    void tick(long nowNanos) {
        if (closed || service == null) {
            return;
        }
        boolean on = host.settings().isCrashRecovery();
        boolean anyDirty = false;
        for (Map.Entry<EditorBuffer, Tracked> entry : tracked.entrySet()) {
            EditorBuffer buffer = entry.getKey();
            Tracked state = entry.getValue();
            boolean dirty = buffer.dirtyProperty().get();
            anyDirty |= dirty;
            if (!on) {
                forget(state); // turned off (in any window): the copies go, and none is taken
                continue;
            }
            int length = buffer.getArea().getLength();
            RecoveryPolicy.Skip skip = skip(buffer, dirty, length);
            if (skip == RecoveryPolicy.Skip.TOO_LARGE && !state.toldTooLarge) {
                state.toldTooLarge = true;
                host.setStatus(tr("status.recovery.tooLarge", buffer.getTitle()));
            }
            if (skip != RecoveryPolicy.Skip.NONE) {
                continue;
            }
            long version = buffer.docVersion();
            boolean typing = version != state.seenVersion;
            state.seenVersion = version;
            if (upToDate(buffer, state)) {
                continue;
            }
            long sinceCopy = state.copied ? (nowNanos - state.copiedAtNanos) / 1_000_000 : Long.MAX_VALUE;
            if (RecoveryPolicy.due(typing, sinceCopy, length)) {
                copy(buffer, state, nowNanos);
            }
        }
        if (!anyDirty) {
            service.removeTicker(ticker); // the dirty listener starts it again
        }
    }

    /** Copies every unsaved buffer that changed since its last copy, due or not (shutdown, tests). */
    void snapshotAllNow() {
        if (!enabled()) {
            return;
        }
        long now = System.nanoTime();
        tracked.forEach((buffer, state) -> {
            boolean dirty = buffer.dirtyProperty().get();
            if (skip(buffer, dirty, buffer.getArea().getLength()) == RecoveryPolicy.Skip.NONE
                    && !upToDate(buffer, state)) {
                copy(buffer, state, now);
            }
        });
    }

    private static RecoveryPolicy.Skip skip(EditorBuffer buffer, boolean dirty, int length) {
        return RecoveryPolicy.skip(
                dirty,
                buffer.isLoading(),
                buffer.isTruncatedLoad() || buffer.isLogTrimmed(),
                buffer.isLogFollowing(),
                length);
    }

    /** Whether the last copy still says everything a restore needs: text, line ending, charset and file. */
    private static boolean upToDate(EditorBuffer buffer, Tracked state) {
        return state.copied
                && state.copiedVersion == buffer.docVersion()
                && java.util.Objects.equals(state.copiedLineEnding, buffer.getLineEnding())
                && java.util.Objects.equals(state.copiedCharset, buffer.getEffectiveCharset())
                && java.util.Objects.equals(state.copiedPath, buffer.getPath());
    }

    private void copy(EditorBuffer buffer, Tracked state, long nowNanos) {
        Path path = buffer.getPath();
        EditorBuffer.DiskSnapshot disk = buffer.diskSnapshot();
        // The String is the buffer's own cached snapshot of this document version when one exists; encoding
        // and writing happen on the service's thread.
        String text = buffer.getContent();
        service.save(new RecoveryRecord(
                state.id,
                path == null ? null : com.editora.vfs.Vfs.toStorableString(path),
                buffer.getTitle(),
                path == null ? buffer.getDisplayName() : null,
                buffer.getEffectiveCharset(),
                files.writesBom(buffer),
                buffer.getLineEnding(),
                disk.modifiedMillis(),
                disk.size(),
                disk.fingerprint(),
                System.currentTimeMillis(),
                Math.clamp(buffer.getArea().getCaretPosition(), 0, text.length()),
                windowKey.get(),
                text));
        state.copied = true;
        state.copiedVersion = buffer.docVersion();
        state.copiedLineEnding = buffer.getLineEnding();
        state.copiedCharset = buffer.getEffectiveCharset();
        state.copiedPath = path;
        state.copiedAtNanos = nowNanos;
    }

    // --- the setting ---

    private void toggle() {
        boolean next = !host.settings().isCrashRecovery();
        host.settings().setCrashRecovery(next);
        host.requestSave();
        applySupport();
        host.syncSettingsWindow();
        host.setStatus(tr(
                "status.settingToggled",
                tr("command.view.toggleCrashRecovery"),
                tr(next ? "common.on" : "common.off")));
    }

    /** Applies the setting now: off removes this window's copies, on takes them. Other windows follow on their next tick. */
    void applySupport() {
        if (service == null || closed) {
            return;
        }
        if (host.settings().isCrashRecovery()) {
            snapshotAllNow();
        } else {
            tracked.values().forEach(this::forget);
        }
    }

    // --- offering back what a dead session left ---

    /** Shows the offer; {@code asked} (the command) also says so when there is nothing to recover. */
    void showOffer(boolean asked) {
        if (service == null || closed) {
            return;
        }
        if (service.unresolved().isEmpty()) {
            if (asked) {
                host.setStatus(tr("status.recovery.none"));
            }
            return;
        }
        if (offer == null) {
            offer = new RecoveryOfferWindow(
                    service::unresolved,
                    service::unreadable,
                    service.store().root(),
                    this::restore,
                    this::discard,
                    this::decideLater);
        }
        offer.show(host.window());
    }

    RecoveryOfferWindow offerForTest() {
        return offer;
    }

    private void decideLater() {
        if (!closed && service != null && !service.unresolved().isEmpty()) {
            host.setStatus(tr("status.recovery.kept", service.unresolved().size()));
        }
    }

    private void refreshOffer() {
        if (offer != null) {
            offer.refresh();
        }
    }

    /** Opens each record's text as an unsaved tab. Nothing is written to the user's files. */
    void restore(List<RecoveryStore.Entry> entries) {
        for (RecoveryStore.Entry entry : new ArrayList<>(entries)) {
            service.read(entry)
                    .whenComplete((record, error) -> Platform.runLater(() -> {
                        if (closed || !service.unresolved().contains(entry)) {
                            return; // the window went away, or a second click already restored this one
                        }
                        if (error != null || record == null || record.text() == null) {
                            host.setError(tr(
                                    "status.recovery.readFailed", entry.record().title()));
                            return; // the record stays where it is, and stays listed
                        }
                        restoreInto(entry, record);
                    }));
        }
    }

    private void restoreInto(RecoveryStore.Entry entry, RecoveryRecord record) {
        Path path = null;
        if (record.path() != null) {
            try {
                path = com.editora.vfs.Vfs.parseStorable(record.path());
            } catch (RuntimeException notAPathHere) {
                path = null;
            }
            if (path == null) {
                // A remote file whose connection is not open (or a path this system cannot express): the
                // text still comes back, as an untitled buffer the user can save where they want.
                restoredAsNew(entry, record, null, tr("status.recovery.restoredAsUntitled", record.title()));
                return;
            }
        }
        Tab openTab = path == null ? null : files.host().tabForPath(path);
        if (openTab == null) {
            restoredAsNew(entry, record, path, tr("status.recovery.restored", record.title()));
            return;
        }
        EditorBuffer open = files.host().bufferOf(openTab);
        if (open == null) {
            // The file is showing in a viewer tab (image, hex): there is no editor buffer to put text in.
            restoredAsNew(entry, record, null, tr("status.recovery.restoredAsUntitled", record.title()));
            return;
        }
        // The file is already open — typically reopened by the session restore. Wait for its text, then put
        // the recovered text on top of it as one undoable edit. If the load fails the tab goes away and the
        // record stays listed.
        files.afterBufferLoad(open, () -> {
            if (closed || open.isDisposed() || !service.unresolved().contains(entry)) {
                return;
            }
            if (open.isDirty() && open.getContent().equals(record.text())) {
                finish(entry, open, tr("status.recovery.restored", record.title()));
            } else if (open.isDirty() || !open.isEditable() || open.isTruncatedLoad() || open.isLogFollowing()) {
                // It has unsaved changes of its own (or cannot take text): never overwrite those.
                restoredAsNew(entry, record, null, tr("status.recovery.restoredBeside", record.title()));
            } else {
                open.replaceWholeDocument(record.text());
                applyLineEnding(open, record.lineEnding());
                Tab tab = files.host().tabForBuffer(open);
                if (tab != null) {
                    files.host().editorArea().select(tab);
                    files.host().updateTabMeta(tab, open);
                }
                moveCaret(open, record.caret());
                finish(entry, open, tr("status.recovery.restored", record.title()));
            }
        });
    }

    /** Opens the text in a new tab: bound to {@code path} when given, otherwise untitled. */
    private void restoredAsNew(RecoveryStore.Entry entry, RecoveryRecord record, Path path, String status) {
        EditorBuffer buffer = new EditorBuffer();
        if (path != null) {
            buffer.setPath(path);
            // The disk state the edits were based on, not today's: a file changed since then is then caught
            // by the same checks as if the editor had never stopped.
            buffer.setDiskSnapshot(record.baseModifiedMillis(), record.baseSize(), record.baseFingerprint());
        } else {
            String name =
                    record.displayName() != null ? record.displayName() : record.untitled() ? null : record.title();
            buffer.setDisplayName(name);
        }
        if (record.charset() != null) {
            buffer.setDetectedCharset(record.charset());
        }
        String text = record.text();
        boolean longLine =
                FileWorkflowCoordinator.textStats(text).maxLineLength() >= FileWorkflowCoordinator.LONG_LINE_FILE_CHARS;
        if (longLine || text.length() >= EditorBuffer.LARGE_FILE_BYTES) {
            buffer.setLargeFile(true); // before the text goes in, as a load does
        }
        if (longLine) {
            buffer.setWrapSuppressed(true);
            buffer.setWordWrap(false);
        }
        buffer.setInitialContent(LineEndings.apply(text, record.lineEnding()), longLine);
        applyLineEnding(buffer, record.lineEnding());
        Tab tab = files.host().addBuffer(buffer, true);
        files.setWritesBom(buffer, record.bom());
        if (path != null) {
            // As every open of a file does: a tab without its marks would write empty lists over the stored ones.
            restorePerFileState.accept(buffer);
            host.restoreMarkdownMode(buffer);
        }
        buffer.markUnsaved(); // recovered text is unsaved by definition, whatever it is compared with
        moveCaret(buffer, record.caret());
        if (tab != null) {
            files.host().updateTabMeta(tab, buffer);
        }
        finish(entry, buffer, status);
    }

    private static void applyLineEnding(EditorBuffer buffer, String lineEnding) {
        if (lineEnding != null
                && !lineEnding.equals(buffer.getLineEnding())
                && (LineEndings.LF.equals(lineEnding) || LineEndings.CRLF.equals(lineEnding))) {
            buffer.convertLineEndings(LineEndings.CRLF.equals(lineEnding));
        }
    }

    private static void moveCaret(EditorBuffer buffer, int caret) {
        buffer.getArea().moveTo(Math.clamp(caret, 0, buffer.getArea().getLength()));
        buffer.getArea().requestFollowCaret();
    }

    /**
     * The text is in {@code buffer}. Take this session's own copy of it at once and delete the old record
     * only when that copy is on disk, so there is no moment at which a second crash would lose the text.
     */
    private void finish(RecoveryStore.Entry entry, EditorBuffer buffer, String status) {
        service.unresolved().remove(entry);
        Tracked state = tracked.get(buffer);
        boolean dirty = buffer.dirtyProperty().get();
        if (enabled()
                && state != null
                && skip(buffer, dirty, buffer.getArea().getLength()) == RecoveryPolicy.Skip.NONE) {
            copy(buffer, state, System.nanoTime());
            service.discardOnceSaved(entry, state.id);
        } else {
            // Recovery is off, or the buffer turned out to equal its file: there is nothing to keep a copy of.
            service.discard(entry);
        }
        host.setStatus(status);
        refreshOffer();
    }

    /** Deletes the records — after the user confirmed it. */
    void discard(List<RecoveryStore.Entry> entries) {
        List<RecoveryStore.Entry> chosen = new ArrayList<>(entries);
        if (chosen.isEmpty() || !confirmDiscard.test(chosen.size())) {
            return;
        }
        for (RecoveryStore.Entry entry : chosen) {
            if (!service.unresolved().remove(entry)) {
                continue;
            }
            service.discard(entry)
                    .thenAccept(deleted -> Platform.runLater(() -> {
                        if (!deleted && !closed) {
                            host.setError(tr(
                                    "status.recovery.discardFailed",
                                    entry.record().title()));
                        }
                    }));
        }
        host.setStatus(tr("status.recovery.discarded", chosen.size()));
        refreshOffer();
    }

    private boolean askDiscard(int count) {
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(host.window());
        alert.setTitle(tr("recovery.discard.confirmTitle"));
        alert.setHeaderText(null);
        alert.setContentText(tr("recovery.discard.confirm", count));
        ButtonType discard = new ButtonType(tr("recovery.discard.button"), ButtonBar.ButtonData.OTHER);
        ButtonType keep = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(discard, keep);
        // Enter and Escape both keep the records: only a click on Discard deletes them.
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(discard)).setDefaultButton(false);
        ((javafx.scene.control.Button) alert.getDialogPane().lookupButton(keep)).setDefaultButton(true);
        return alert.showAndWait().filter(discard::equals).isPresent();
    }
}
