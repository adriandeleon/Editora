package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;
import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * A window's ways of opening a file other than the plain open: in the background for an edit that touches an
 * unopened file, in the reusable preview tab, as text regardless of its type, the end of an asynchronous load, and
 * the two dictionaries. The preview tab and the loading state stay with {@link MainController} and {@link
 * FileWorkflowCoordinator}. It lived inside {@code MainController}; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class WindowFileOpens {

    private final MainController mc;

    WindowFileOpens(MainController mc) {
        this.mc = mc;
    }

    /**
     * Opens {@code target} (assumed not already open — callers check {@link MainController#openBufferFor} first) as a new,
     * unfocused background tab, reading the file on the calling (FX) thread. Only for the two callers that must
     * answer synchronously (a merge pane's save-before-close, the workspace-edit transaction's fallback for a
     * tab closed mid-transaction); everything else goes through {@link #openBackgroundBufferAsync}.
     */
    @Nullable
    EditorBuffer openBackgroundBuffer(Path target) {
        Tab placeholder = mc.tabForPath(target);
        if (RestorePlaceholder.of(placeholder) != null) {
            // The file is open, as a restored tab that has not been built yet: build it (read on the caller's
            // thread, as below) rather than open a second tab on the same file.
            return mc.sessions.materializeLoaded(placeholder);
        }
        try {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(target);
            FileWorkflowCoordinator.PreparedLoad load =
                    mc.fileWorkflows.prepareLoad(target, false).preparedFor(buffer);
            mc.fileWorkflows.applyPreparedLoad(buffer, load);
            mc.fileWorkflows.notePerfContentLoaded(buffer);
            return attachBackground(buffer, load.editorConfig());
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Adds a loaded {@code buffer} as an unfocused tab with its stored per-file state, like any other open:
     * without the marks the next mark change would persist over them, and callers gate on the read-only pin.
     * {@code editorConfig} is the load's resolved {@code .editorconfig} result: no ancestor walk on FX again.
     */
    private EditorBuffer attachBackground(
            EditorBuffer buffer, com.editora.editorconfig.EditorConfigProperties editorConfig) {
        Tab tab = mc.addBuffer(buffer, false, editorConfig); // background: keep the caller's current tab focused
        mc.fileState.restorePerFileState(buffer);
        mc.previews.restoreMarkdownMode(buffer);
        mc.updateTabMeta(tab, buffer);
        return buffer;
    }

    /**
     * Workspace edits, diff applies and agent writes may touch unopened files. The buffer is created here (on
     * FX) so the load worker can read, decode and split the file into its paragraphs, as {@code openPath} does;
     * the FX thread is left with the document swap and the tab. The shell is disposed when the read fails, the
     * file is binary, or the file was opened by other means while the read ran.
     */
    void openBackgroundBufferAsync(Path target, java.util.function.Consumer<@Nullable EditorBuffer> done) {
        EditorBuffer existing = mc.openBufferFor(target);
        if (existing != null) {
            done.accept(existing);
            return;
        }
        Tab placeholder = mc.tabForPath(target);
        if (RestorePlaceholder.of(placeholder) != null) {
            mc.sessions.materialize(placeholder, false, done); // its own read, on the same executor
            return;
        }
        EditorBuffer buffer = new EditorBuffer();
        buffer.setPath(target);
        mc.fileWorkflows.fileLoadExecutor.execute(() -> {
            try {
                FileWorkflowCoordinator.PreparedLoad load =
                        mc.fileWorkflows.prepareLoad(target, false).preparedFor(buffer);
                Platform.runLater(() -> {
                    if (mc.sessionClosed || load.binary()) { // never attach a buffer to a disposed window
                        buffer.dispose();
                        done.accept(null);
                        return;
                    }
                    EditorBuffer openedWhileLoading = mc.openBufferFor(target);
                    if (openedWhileLoading != null) {
                        buffer.dispose();
                        done.accept(openedWhileLoading);
                        return;
                    }
                    mc.fileWorkflows.applyPreparedLoad(buffer, load);
                    done.accept(attachBackground(buffer, load.editorConfig()));
                });
            } catch (IOException | RuntimeException e) {
                Platform.runLater(() -> {
                    buffer.dispose();
                    done.accept(null);
                });
            }
        });
    }

    /** Opens the personal dictionary ({@code dictionary.txt}) in the editor, creating it empty if it doesn't
     *  exist yet (so the link works even before any word is added). Backs the Settings → Spell Check link. */
    void openPersonalDictionary() {
        Path file = mc.config.getDictionaryFile();
        // made when missing, off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.run(
                "dictionary-open",
                () -> {
                    if (!Files.exists(file)) {
                        Files.writeString(file, "");
                    }
                },
                failure -> {
                    if (failure != null) {
                        mc.setStatus(tr("status.dict.openFailed"));
                    } else {
                        mc.fileWorkflows.openPath(file);
                    }
                });
    }

    /** Opens the bundled technical-terms dictionary in a read-only tab so the user can browse what it covers.
     *  It's a classpath resource (inside the jar), so there's no on-disk path to open. */
    void openTechnicalDictionary() {
        // the bundled list is read off the FX thread (PERF-01, Pauta 6.2.0)
        com.editora.perf.OffFx.then(
                "dictionary-open",
                () -> {
                    try (var in = MainController.class.getResourceAsStream("/com/editora/dictionaries/technical.txt")) {
                        return in == null
                                ? null
                                : new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    }
                },
                read -> {
                    String text = read.value();
                    if (text == null) {
                        mc.setStatus(tr("status.dict.openFailed"));
                    } else {
                        openTechnicalDictionary(text);
                    }
                });
    }

    void openTechnicalDictionary(String text) {
        EditorBuffer buffer = new EditorBuffer();
        buffer.setDisplayName("technical.txt");
        buffer.setContentLater(text, () -> {}); // line endings resolved off the FX thread (PERF-01, Pauta 6.2.0)
        mc.addBuffer(buffer, true);
        buffer.setViewMode(true); // read-only: it's the bundled list, not user-editable
    }

    /**
     * Opens {@code file} in the preview slot: the previous preview tab is replaced rather than added to.
     *
     * <p>An already-open file is simply selected and is <em>not</em> demoted into the slot — it earned its
     * place by some earlier deliberate open, and quietly making it disposable would lose it on the next
     * glance at something else.
     */
    void openPathPreview(Path file) {
        Tab existing = mc.tabForPath(file);
        if (existing != null) {
            TabFocus.select(mc.editorArea, existing);
            return;
        }
        Tab previous = mc.previewTab;
        mc.previewTab = null; // cleared first: closing it runs listeners that would otherwise see a stale slot
        if (previous != null && mc.editorArea.tabs().contains(previous)) {
            EditorBuffer b = MainController.bufferOf(previous);
            // A dirty preview tab is not disposable — the edit already promoted it, but guard anyway
            // rather than risk closing unsaved work on a keystroke.
            if (b == null || !b.isDirty()) {
                mc.editorArea.remove(previous); // tabs() is unmodifiable by design; remove() is the seam
            }
        }
        mc.fileWorkflows.openPath(file, true);
        Tab opened = mc.tabForPath(file);
        if (opened != null) {
            mc.previewTab = opened;
            EditorBuffer b = MainController.bufferOf(opened);
            if (b != null) {
                mc.updateTabMeta(opened, b); // re-render the header so it shows as italic
            }
        }
    }

    /** Makes {@code tab} permanent — it was chosen, not glanced at. No-op for any other tab. */
    void promoteTab(@Nullable Tab tab) {
        if (tab == null || tab != mc.previewTab) {
            return;
        }
        mc.previewTab = null;
        EditorBuffer b = MainController.bufferOf(tab);
        if (b != null) {
            mc.updateTabMeta(tab, b); // drops the italic
        }
    }

    /** Loads {@code file} into a new text {@link EditorBuffer} tab (the normal text path, bypassing the
     *  image/binary routing in {@link #openPath}). Used by {@code openPath}'s fall-through and by the
     *  {@code view.openAsText} command (which forces a text open even for a binary / already-hex file). */
    private void openTextBuffer(Path file) {
        mc.fileWorkflows.openTextBufferAsync(file, false); // explicit Open as Text bypasses binary routing
    }

    void finishAsyncOpen(Tab tab, EditorBuffer buffer, FileWorkflowCoordinator.PreparedLoad load) {
        if (mc.tabForBuffer(buffer) != tab) {
            discardLoading(buffer); // the user closed the shell while its disk read was running
            return;
        }
        if (load.binary()) {
            boolean selected = mc.editorArea.selectedTab() == tab;
            discardLoading(buffer);
            mc.editorArea.remove(tab);
            mc.fileWorkflows.openHexTab(load.file(), selected);
            if (mc.recentFiles != null) {
                mc.recentFiles.add(load.file());
            }
            mc.setStatus(tr("status.opened", com.editora.config.PathDisplay.of(load.file())));
            return;
        }
        String note = mc.fileWorkflows.applyPreparedLoad(buffer, load);
        mc.fileWorkflows.notePerfContentLoaded(buffer);
        mc.fileState.restorePerFileState(buffer);
        buffer.setLoading(false);
        mc.previews.restoreMarkdownMode(buffer);
        mc.updateTabMeta(tab, buffer);
        // The loading shell is deliberately read-only until its document lands. Selection attached the
        // status bar while that temporary state was active; refresh after restoring the file's real view
        // mode or the segment can keep saying Read-Only even though the CodeArea is now editable. This is
        // especially visible for `--expert FILE`, where the status bar remains but the tab header is hidden.
        mc.statusBar.refresh();
        mc.lspCoordinator.syncBufferWhenShown(buffer); // the temporary heavy-file shell suppressed this
        // Set the default caret before releasing queued navigation. A later runLater(goToStart) would erase
        // a file:line request that waited on this loading shell.
        buffer.goToStart();
        if (mc.recentFiles != null) {
            mc.recentFiles.add(load.file());
        }
        mc.setStatus(note.isEmpty() ? tr("status.opened", com.editora.config.PathDisplay.of(load.file())) : note);
        clearLoading(buffer);
        mc.fileWorkflows.encryption().opened(buffer); // an encrypted file decrypts now; a public key offers Import
    }

    void failAsyncOpen(Tab tab, EditorBuffer buffer, Path file, Exception error) {
        discardLoading(buffer);
        if (mc.tabForBuffer(buffer) == tab) {
            mc.editorArea.remove(tab);
        }
        mc.setStatus(tr("status.failedOpen", RemoteReadFailure.reason(file, error)));
        if (mc.recentFiles != null) {
            mc.recentFiles.remove(file);
        }
    }

    void clearLoading(EditorBuffer buffer) {
        mc.fileWorkflows.loadingBuffers.remove(buffer);
        List<Runnable> queued = mc.fileWorkflows.afterBufferLoad.remove(buffer);
        if (queued != null) {
            queued.forEach(Runnable::run);
        }
    }

    void discardLoading(EditorBuffer buffer) {
        mc.fileWorkflows.loadingBuffers.remove(buffer);
        mc.fileWorkflows.afterBufferLoad.remove(buffer);
    }

    /** Command {@code view.openAsText}: opens the active file's bytes in a normal text buffer, bypassing the
     *  image/binary auto-routing (for a file mis-detected as binary, or to read a binary's raw text). No-op
     *  with a status when the active tab has no file. */
    void openActiveAsText() {
        Path p = MainController.tabPath(mc.editorArea.selectedTab());
        if (p == null) {
            mc.setStatus(tr("status.hex.noFile"));
            return;
        }
        openTextBuffer(p);
    }

    /** The file's resolved {@code .editorconfig} charset, or null (EditorConfig off / remote / no rule). The
     *  BOM check that overrides it lives in {@link com.editora.editorconfig.EditorConfigCharset#resolveName}. */
    @Nullable
    String editorConfigCharsetFor(@Nullable Path file) {
        if (file != null && mc.editorSettings.editorConfigEnabled() && com.editora.vfs.Vfs.isLocal(file)) {
            return com.editora.editorconfig.EditorConfig.resolveFor(file).charset();
        }
        return null;
    }
}
