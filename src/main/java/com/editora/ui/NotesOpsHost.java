package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link NotesCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class NotesOpsHost implements NotesCoordinator.Ops {

    private final MainController mc;

    NotesOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openPath(java.nio.file.Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public void navigateToLine(int line) {
        mc.navigateToLine(line);
    }

    @Override
    public void openInProjectWindow(String projectKey, java.nio.file.Path file, int line) {
        mc.openInProjectWindow(projectKey, file, line);
    }

    @Override
    public String noteKey(EditorBuffer buffer) {
        return MainController.noteKey(buffer);
    }

    @Override
    public @Nullable EditorBuffer bufferForKey(String fileKey) {
        return MainController.bufferOf(mc.tabForKey(fileKey));
    }

    @Override
    public @Nullable EditorBuffer bufferForPath(java.nio.file.Path file) {
        return MainController.bufferOf(mc.tabForPath(file));
    }

    @Override
    public void installEmacsKeys(javafx.scene.control.TextInputControl control) {
        com.editora.command.TextInputKeymap.install(control, mc.keymap);
    }

    @Override
    public void setToolWindowAvailable(boolean available) {
        mc.toolWindows.setAvailable(mc.notesToolWindow, available);
    }

    @Override
    public java.util.Map<String, java.util.List<com.editora.config.PersonalNote>> notes() {
        return mc.config.getNotes();
    }

    @Override
    public java.util.Map<String, java.util.Map<String, java.util.List<com.editora.config.PersonalNote>>> allNotes() {
        return mc.config.getAllNotes();
    }

    @Override
    public String currentProjectKey() {
        return mc.config.currentProjectKey();
    }

    @Override
    public String projectName(String key) {
        return mc.projectDisplayName(key);
    }

    @Override
    public void saveNotes() {
        mc.config.saveNotes();
    }

    @Override
    public void notesStored(java.util.Map<String, ?> bucket, @Nullable String fileKey) {
        mc.marksStored(MarkMerge.Kind.NOTES, bucket, fileKey);
    }
}
