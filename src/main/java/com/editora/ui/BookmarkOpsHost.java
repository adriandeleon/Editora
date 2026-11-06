package com.editora.ui;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link BookmarkCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class BookmarkOpsHost implements BookmarkCoordinator.Ops {

    private final MainController mc;

    BookmarkOpsHost(MainController mc) {
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
    public @Nullable EditorBuffer bufferForPath(java.nio.file.Path file) {
        return MainController.bufferOf(mc.tabForPath(file));
    }

    @Override
    public void promptText(String title, String label, String initial, java.util.function.Consumer<String> onAccept) {
        mc.promptText(title, label, initial, onAccept);
    }

    @Override
    public java.util.Map<String, java.util.List<com.editora.config.Bookmark>> bookmarks() {
        return mc.config.getBookmarks();
    }

    @Override
    public java.util.Map<String, java.util.Map<String, java.util.List<com.editora.config.Bookmark>>> allBookmarks() {
        return mc.config.getAllBookmarks();
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
    public void saveBookmarks() {
        mc.config.saveBookmarks();
    }

    @Override
    public void bookmarksStored(java.util.Map<String, ?> bucket, @Nullable String fileKey) {
        mc.marksStored(MarkMerge.Kind.BOOKMARKS, bucket, fileKey);
    }
}
