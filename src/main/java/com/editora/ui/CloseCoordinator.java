package com.editora.ui;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import javafx.scene.control.Alert;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.stage.Stage;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;

import static com.editora.i18n.Messages.tr;

/** Owns tab/window close prompts and exact-state revalidation across nested JavaFX event loops. */
final class CloseCoordinator {

    private record BufferToken(Path path, long version, boolean dirty) {}

    static final class ApprovalState {
        private final IdentityHashMap<EditorBuffer, BufferToken> discardedBuffers = new IdentityHashMap<>();
        private final IdentityHashMap<TabContent, Object> discardedContent = new IdentityHashMap<>();
    }

    record Sweep(boolean allowed, boolean prompted) {}

    private final Stage stage;
    private final EditorArea editorArea;
    private final Set<Tab> pinned;
    private final Function<Tab, EditorBuffer> bufferOf;
    private final FileWorkflowCoordinator files;
    private final Runnable persistSession;

    CloseCoordinator(
            Stage stage,
            EditorArea editorArea,
            Set<Tab> pinned,
            Function<Tab, EditorBuffer> bufferOf,
            FileWorkflowCoordinator files,
            Runnable persistSession) {
        this.stage = stage;
        this.editorArea = editorArea;
        this.pinned = pinned;
        this.bufferOf = bufferOf;
        this.files = files;
        this.persistSession = persistSession;
    }

    boolean confirmClose(Tab tab) {
        EditorBuffer buffer = bufferOf.apply(tab);
        if (buffer != null && pinned.contains(tab) && !confirmPinned(buffer)) {
            return false;
        }
        if (buffer != null) {
            return confirmCloseIfDirty(buffer);
        }
        return tab != null && tab.getUserData() instanceof TabContent content ? confirmCloseIfDirty(content) : true;
    }

    private boolean confirmPinned(EditorBuffer buffer) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.pinnedTab.title"));
        alert.setHeaderText(tr("dialog.pinnedTab.header", buffer.getTitle()));
        alert.setContentText(null);
        ButtonType close = new ButtonType(tr("dialog.close"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(close, cancel);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == close;
    }

    boolean confirmCloseIfDirty(EditorBuffer buffer) {
        if (!buffer.isDirty() && !files.hasPendingSave(buffer)) {
            return true;
        }
        Alert alert = unsavedAlert(buffer.getTitle(), "dialog.save");
        ButtonType save = alert.getButtonTypes().get(0);
        ButtonType discard = alert.getButtonTypes().get(1);
        ButtonType cancel = alert.getButtonTypes().get(2);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        if (result.get() == save) {
            return files.saveSynchronously(buffer) && !buffer.isDirty() && !files.hasPendingSave(buffer);
        }
        files.invalidatePendingWrites(buffer);
        return result.get() == discard;
    }

    private boolean confirmCloseIfDirty(TabContent content) {
        if (!content.hasUnsavedChanges()) {
            return true;
        }
        Alert alert = unsavedAlert(content.title(), content.closeSaveActionKey());
        ButtonType save = alert.getButtonTypes().get(0);
        ButtonType discard = alert.getButtonTypes().get(1);
        ButtonType cancel = alert.getButtonTypes().get(2);
        Optional<ButtonType> result = alert.showAndWait();
        if (result.isEmpty() || result.get() == cancel) {
            return false;
        }
        return result.get() == discard
                || result.get() == save && content.saveBeforeClose() && !content.hasUnsavedChanges();
    }

    private Alert unsavedAlert(String title, String saveKey) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.unsaved.title"));
        alert.setHeaderText(tr("dialog.unsaved.header", title));
        alert.setContentText(null);
        ButtonType save = new ButtonType(tr(saveKey));
        ButtonType discard = new ButtonType(tr("dialog.discard"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(save, discard, cancel);
        MainController.styleUnsavedChangesButtons(alert, save, discard);
        return alert;
    }

    boolean confirmQuit() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.initOwner(stage);
        alert.setTitle(tr("dialog.quit.title"));
        alert.setHeaderText(tr("dialog.quit.header"));
        alert.setContentText(null);
        ButtonType quit = new ButtonType(tr("dialog.quit.button"));
        ButtonType cancel = new ButtonType(tr("dialog.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(quit, cancel);
        MainController.styleQuitButtonAsDanger(alert, quit);
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == quit;
    }

    /** One validation pass. Discard approvals remain valid only for the exact state the user saw. */
    Sweep confirmSweep(ApprovalState approvals) {
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
                    if (!confirmCloseIfDirty(content)) {
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
            if (!confirmCloseIfDirty(buffer)) {
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

    boolean confirmCloseAll() {
        ApprovalState approvals = new ApprovalState();
        while (true) {
            Sweep sweep = confirmSweep(approvals);
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
