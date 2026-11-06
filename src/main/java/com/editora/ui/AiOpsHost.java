package com.editora.ui;

import java.nio.file.Path;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link AiCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class AiOpsHost implements AiCoordinator.Ops {

    private final MainController mc;

    AiOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public @Nullable Path repoRoot() {
        return mc.git.repoRoot();
    }

    @Override
    public void stagedDiff(Path root, java.util.function.Consumer<String> onResult) {
        mc.git.service().stagedDiff(root, onResult);
    }

    @Override
    public void setCommitMessage(String message) {
        mc.gitPanel().setCommitMessage(message);
    }

    @Override
    public void openCommitWindow() {
        mc.toolWindows.open(mc.commitToolWindow, true);
        mc.gitPanel().focusCommitMessage();
    }

    @Override
    public void openTab(EditorBuffer buffer) {
        mc.addBuffer(buffer, true);
    }

    @Override
    public void setCommitAiAvailable(boolean available) {
        mc.gitAiAvailable = available; // a panel not built yet shows it when it is
        if (mc.gitPanel != null) {
            mc.gitPanel.setAiAvailable(available);
        }
    }
}
