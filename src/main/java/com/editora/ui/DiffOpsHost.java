package com.editora.ui;

import java.nio.file.Path;

import javafx.scene.control.Tab;

import com.editora.editor.EditorBuffer;
import com.editora.editor.TabContent;
import org.jspecify.annotations.Nullable;

/**
 * What {@link DiffCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class DiffOpsHost implements DiffCoordinator.Ops {

    private final MainController mc;

    DiffOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void addDiffTab(TabContent pane) {
        if (pane instanceof DiffViewerPane diffPane) {
            diffPane.setExitDiffUiAction(mc.chrome.diffUiActive() ? mc.chrome::exitDiffUiMode : null);
        } else if (pane instanceof DirectoryReviewPane reviewPane) {
            reviewPane.setExitDiffUiAction(mc.chrome.diffUiActive() ? mc.chrome::exitDiffUiMode : null);
        }
        mc.addContentTab(pane, true);
    }

    @Override
    public void prepareDiffPane(DiffViewerPane pane) {
        pane.setExitDiffUiAction(mc.chrome.diffUiActive() ? mc.chrome::exitDiffUiMode : null);
    }

    @Override
    public @Nullable EditorBuffer openBufferFor(Path target) {
        return mc.openBufferFor(target);
    }

    @Override
    public @Nullable EditorBuffer openBackgroundBuffer(Path target) {
        return mc.openBackgroundBuffer(target);
    }

    @Override
    public void openBackgroundBufferAsync(Path target, java.util.function.Consumer<@Nullable EditorBuffer> done) {
        mc.openBackgroundBufferAsync(target, done);
    }

    @Override
    public void discardBackgroundBuffer(@Nullable EditorBuffer buffer) {
        Tab tab = buffer == null ? null : mc.tabFor(buffer);
        if (tab != null && buffer != null && !buffer.isDirty()) {
            mc.editorArea.remove(tab);
        }
    }

    @Override
    public boolean saveBuffer(EditorBuffer buffer) {
        return mc.fileWorkflows.saveSynchronously(buffer);
    }

    @Override
    public java.util.List<DiffViewerPane> openDiffPanes() {
        java.util.List<DiffViewerPane> out = new java.util.ArrayList<>();
        for (Tab tab : mc.editorArea.tabs()) {
            if (tab.getUserData() instanceof DiffViewerPane dp) {
                out.add(dp);
            } else if (tab.getUserData() instanceof PatchReviewPane review) {
                out.addAll(review.panes());
            } else if (tab.getUserData() instanceof DirectoryReviewPane review) {
                out.addAll(review.panes());
            }
        }
        return out;
    }

    @Override
    public @Nullable DiffViewerPane activeDiffPane() {
        Tab t = mc.editorArea.selectedTab();
        if (t == null) {
            return null;
        }
        if (t.getUserData() instanceof DiffViewerPane dp) {
            return dp;
        }
        if (t.getUserData() instanceof PatchReviewPane review) {
            return review.activePane();
        }
        return t.getUserData() instanceof DirectoryReviewPane review ? review.activePane() : null;
    }

    @Override
    public @Nullable Path finderStartDir() {
        return mc.navigation.finderStartDir();
    }

    @Override
    public @Nullable String editorConfigCharset(@Nullable Path file) {
        return mc.editorConfigCharsetFor(file);
    }

    @Override
    public void openAt(Path file, int line) {
        mc.openAndNavigate(file, Math.max(0, line - 1));
    }
}
