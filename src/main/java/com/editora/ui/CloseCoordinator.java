package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/** Owns tab/window close prompts and exact-state revalidation across nested JavaFX event loops. */
final class CloseCoordinator {

    private record BufferToken(@Nullable Path path, long version, boolean dirty) {}

    static final class ApprovalState {
        private final IdentityHashMap<EditorBuffer, BufferToken> discardedBuffers = new IdentityHashMap<>();
        private final IdentityHashMap<TabContent, Object> discardedContent = new IdentityHashMap<>();
    }

    record Sweep(boolean allowed, boolean prompted) {}

    /** What the close question for an edited buffer came to. */
    enum Answer {
        /** Discarded, or saved where waiting is allowed: close now. */
        CLOSE,
        /** Cancelled: keep it open. */
        CANCEL,
        /** Save picked: the write runs off the FX thread, and the close is asked for again once it has landed. */
        SAVING
    }

    private final Stage stage;
    private final EditorArea editorArea;
    private final Set<Tab> pinned;
    private final Function<Tab, @Nullable EditorBuffer> bufferOf;
    private final FileWorkflowCoordinator files;
    private final Runnable persistSession;

    CloseCoordinator(
            Stage stage,
            EditorArea editorArea,
            Set<Tab> pinned,
            Function<Tab, @Nullable EditorBuffer> bufferOf,
            FileWorkflowCoordinator files,
            Runnable persistSession) {
        this.stage = stage;
        this.editorArea = editorArea;
        this.pinned = pinned;
        this.bufferOf = bufferOf;
        this.files = files;
        this.persistSession = persistSession;
    }

    /** {@link #confirmClose(Tab, Runnable)} where waiting for a save is allowed: tests. */
    boolean confirmClose(Tab tab) {
        return confirmClose(tab, null);
    }

    /**
     * Whether {@code tab} may close now. A Save picked in the question writes off the FX thread (PERF-01, Pauta 6.0.1):
     * the tab stays for now, and {@code afterSave} asks for the close again once the write has landed.
     */
    boolean confirmClose(Tab tab, @Nullable Runnable afterSave) {
        EditorBuffer buffer = bufferOf.apply(tab);
        if (buffer != null && pinned.contains(tab) && !confirmPinned(buffer)) {
            return false;
        }
        if (buffer != null) {
            return askCloseIfDirty(buffer, afterSave) == Answer.CLOSE;
        }
        return tab != null && tab.getUserData() instanceof TabContent content
                ? confirmCloseIfDirty(content, afterSave)
                : true;
    }

    private boolean confirmPinned(EditorBuffer buffer) {
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.pinnedTab.title"));
        alert.setHeaderText(tr("dialog.pinnedTab.header", buffer.getTitle()));
        alert.setContentText(null);
        ButtonType close = new ButtonType(tr("dialog.close"));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(close, cancel);
        Optional<ButtonType> result = Dialogs.showAndWait(alert);
        return result.isPresent() && result.get() == close;
    }

    /**
     * The close question for {@code buffer} where waiting for the write is allowed: the quit, and tests. Everything
     * else asks with {@link #askCloseIfDirty}.
     */
    boolean confirmCloseIfDirty(EditorBuffer buffer) {
        return askCloseIfDirty(buffer, null) == Answer.CLOSE;
    }

    /**
     * Asks whether to save {@code buffer}'s unsaved changes before it closes. Save writes off the FX thread (PERF-01,
     * Pauta 6.0.1) and answers {@link Answer#SAVING}: {@code afterSave} runs once the write has landed, and asks for
     * the close again. With no {@code afterSave} (the quit, which may wait) it waits for the write instead.
     */
    Answer askCloseIfDirty(EditorBuffer buffer, @Nullable Runnable afterSave) {
        return askCloseIfDirty(buffer, afterSave, () -> {});
    }

    /** As above; {@code saveFailed} runs when the Save picked could not write the file. */
    Answer askCloseIfDirty(EditorBuffer buffer, @Nullable Runnable afterSave, Runnable saveFailed) {
        if (!buffer.isDirty() && !files.hasPendingSave(buffer)) {
            return Answer.CLOSE;
        }
        Alert alert = unsavedAlert(buffer.getTitle(), "dialog.save");
        ButtonType save = alert.getButtonTypes().get(0);
        ButtonType discard = alert.getButtonTypes().get(1);
        ButtonType cancel = alert.getButtonTypes().get(2);
        Optional<ButtonType> result = Dialogs.showAndWait(alert);
        if (result.isEmpty() || result.get() == cancel) {
            return Answer.CANCEL;
        }
        if (result.get() == save) {
            if (afterSave == null) {
                return files.saveSynchronously(buffer) && !buffer.isDirty() && !files.hasPendingSave(buffer)
                        ? Answer.CLOSE
                        : Answer.CANCEL;
            }
            files.saveThen(buffer, saved -> {
                if (saved) {
                    afterSave.run(); // asks again: a buffer edited meanwhile is asked about again
                } else {
                    saveFailed.run();
                }
            });
            return Answer.SAVING;
        }
        files.invalidatePendingWrites(buffer);
        return result.get() == discard ? Answer.CLOSE : Answer.CANCEL;
    }

    private boolean confirmCloseIfDirty(TabContent content, @Nullable Runnable afterSave) {
        if (!content.hasUnsavedChanges()) {
            return true;
        }
        Alert alert = unsavedAlert(content.title(), content.closeSaveActionKey());
        ButtonType save = alert.getButtonTypes().get(0);
        ButtonType discard = alert.getButtonTypes().get(1);
        ButtonType cancel = alert.getButtonTypes().get(2);
        Optional<ButtonType> result = Dialogs.showAndWait(alert);
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        return result.get() == discard
                || result.get() == save && content.saveBeforeClose(afterSave) && !content.hasUnsavedChanges();
    }

    /**
     * The words of the unsaved-changes question about the tab titled {@code title}. Its answers: Save (0), worded by
     * {@code saveKey}, and Discard (1).
     */
    static Confirm.Choice unsavedQuestion(String title, String saveKey) {
        return Confirm.choice(
                tr("dialog.unsaved.title"),
                tr("dialog.unsaved.header", title),
                null,
                tr(saveKey),
                tr("dialog.discard"));
    }

    /**
     * The words of "Quit?", naming the terminals ({@code busy}, "title (program)") that quitting ends. Its answer:
     * Quit (0).
     */
    static Confirm.Choice quitQuestion(java.util.List<String> busy) {
        return Confirm.choice(
                tr("dialog.quit.title"),
                tr("dialog.quit.header"),
                busy.isEmpty() ? null : tr("terminal.closeWindow.running", "• " + String.join("\n• ", busy)),
                tr("dialog.quit.button"));
    }

    private Alert unsavedAlert(String title, String saveKey) {
        Confirm.Choice words = unsavedQuestion(title, saveKey);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(stage);
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType save = new ButtonType(words.answer(0));
        ButtonType discard = new ButtonType(words.answer(1));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(save, discard, cancel);
        MainController.styleUnsavedChangesButtons(alert, save, discard);
        // Saving is an option, so Save is the one default button: Enter keeps the work, and the red Discard
        // runs only from its own button.
        Dialogs.defaultTo(alert, save);
        return alert;
    }

    boolean confirmQuit() {
        return confirmQuit(java.util.List.of());
    }

    /** "Quit?", saying which terminals ({@code busy}, "title (program)") quitting ends. */
    boolean confirmQuit(java.util.List<String> busy) {
        Confirm.Choice words = quitQuestion(busy);
        Alert alert = Dialogs.styled(new Alert(Alert.AlertType.CONFIRMATION));
        alert.initOwner(stage);
        alert.setTitle(words.title());
        alert.setHeaderText(words.header());
        alert.setContentText(words.message());
        ButtonType quit = new ButtonType(words.answer(0));
        ButtonType cancel = Dialogs.cancel();
        alert.getButtonTypes().setAll(quit, cancel);
        MainController.styleQuitButtonAsDanger(alert, quit);
        Dialogs.destructive(alert, quit); // red, never the default: Cancel is the default button
        Optional<ButtonType> result = Dialogs.showAndWait(alert);
        return result.isPresent() && result.get() == quit;
    }

    /** {@link #confirmSweep(ApprovalState, Runnable)} where waiting for a save is allowed: tests. */
    Sweep confirmSweep(ApprovalState approvals) {
        return confirmSweep(approvals, null);
    }

    /**
     * One validation pass over the window's tabs, asking about each unsaved one; discard approvals remain valid only
     * for the exact state the user saw. {@code afterSave}: where a Save picked goes on
     * once its write has landed (the pass stops, as at a Cancel); null only at the quit, which may wait for the write.
     */
    Sweep confirmSweep(ApprovalState approvals, @Nullable Runnable afterSave) {
        boolean prompted = false;
        for (Tab tab : new ArrayList<>(editorArea.tabs())) {
            EditorBuffer buffer = bufferOf.apply(tab);
            if (buffer == null) {
                if (tab.getUserData() instanceof TabContent content && content.hasUnsavedChanges()) {
                    Object token = content.unsavedStateToken();
                    if (Objects.equals(approvals.discardedContent.get(content), token)) {
                        continue;
                    }
                    editorArea.select(tab);
                    prompted = true;
                    if (!confirmCloseIfDirty(content, afterSave)) {
                        return new Sweep(false, true);
                    }
                    if (content.hasUnsavedChanges()) {
                        approvals.discardedContent.put(content, content.unsavedStateToken());
                    } else {
                        approvals.discardedContent.remove(content);
                    }
                }
                continue;
            }
            if (!buffer.isDirty() && !files.hasPendingSave(buffer)) {
                approvals.discardedBuffers.remove(buffer);
                continue;
            }
            BufferToken token = token(buffer);
            if (Objects.equals(approvals.discardedBuffers.get(buffer), token)) {
                continue;
            }
            editorArea.select(tab);
            prompted = true;
            if (askCloseIfDirty(buffer, afterSave) != Answer.CLOSE) {
                return new Sweep(false, true);
            }
            if (buffer.isDirty() || files.hasPendingSave(buffer)) {
                approvals.discardedBuffers.put(buffer, token(buffer));
            } else {
                approvals.discardedBuffers.remove(buffer);
            }
        }
        return new Sweep(true, prompted);
    }

    /** {@link #confirmCloseAll(Runnable)} where waiting for a save is allowed: tests. */
    boolean confirmCloseAll() {
        return confirmCloseAll(null);
    }

    /** Asks about every unsaved tab of the window, then saves its session; {@code afterSave} as {@link #confirmSweep}. */
    boolean confirmCloseAll(@Nullable Runnable afterSave) {
        ApprovalState approvals = new ApprovalState();
        while (true) {
            Sweep sweep = confirmSweep(approvals, afterSave);
            if (!sweep.allowed()) {
                return false;
            }
            if (!sweep.prompted()) {
                persistSession.run();
                return true;
            }
        }
    }

    private static BufferToken token(EditorBuffer buffer) {
        return new BufferToken(buffer.getPath(), buffer.docVersion(), buffer.isDirty());
    }
}
