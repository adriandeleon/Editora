package com.editora.ui;

import java.nio.file.Path;
import java.util.function.Consumer;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link AgentCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class AgentOpsHost implements AgentCoordinator.Ops {

    private final MainController mc;

    AgentOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public @Nullable Path projectRoot() {
        // This window's project, not the ProjectManager's active one (the window opened last): it is the
        // folder the agent works in and the boundary of what it may read and write.
        return mc.windowProjectRoot();
    }

    @Override
    public @Nullable EditorBuffer bufferForPath(String path) {
        return mc.mcpBridge.openBufferForPath(path);
    }

    @Override
    public @Nullable EditorBuffer bufferInAnotherWindow(Path file) {
        if (mc.windowManager != null) {
            Path key = MainController.canonicalPath(file);
            for (EditorBuffer other : mc.windowManager.buffersAtOrUnder(file)) {
                if (other.getPath() != null
                        && com.editora.config.PathKeys.samePath(MainController.canonicalPath(other.getPath()), key)) {
                    return other;
                }
            }
        }
        return null;
    }

    @Override
    public void recordHistory(Path file, @Nullable String content, Consumer<Boolean> completion) {
        mc.historyCoordinator.recordDurably(
                file, content, com.editora.config.HistoryRevision.REASON_EXTERNAL, completion);
    }

    @Override
    public @Nullable String editorConfigCharset(Path file) {
        return mc.editorConfigCharsetFor(file);
    }

    @Override
    public com.editora.io.DocumentWriteSequencer.Ticket beginDocumentWrite(Path file) {
        return mc.config.shared().documentWrites().begin(file);
    }

    @Override
    public void toggleToolWindow() {
        mc.toolWindows.toggle(mc.agentToolWindow);
    }

    @Override
    public void openToolWindow(boolean focus) {
        if (focus) {
            mc.toolWindows.open(mc.agentToolWindow, true);
        } else {
            mc.toolWindows.open(mc.agentToolWindow);
        }
    }

    @Override
    public void closeToolWindow() {
        mc.toolWindows.close(mc.agentToolWindow);
    }

    @Override
    public void setToolWindowAvailable(boolean available) {
        mc.toolWindows.setAvailable(mc.agentToolWindow, available);
    }

    @Override
    public void refreshProjectTree() {
        mc.refreshProjectTreeIfBuilt();
    }

    @Override
    public void openBackgroundBuffer(Path target) {
        mc.openBackgroundBufferAsync(target, opened -> {});
    }

    @Override
    public void openPath(@Nullable Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public void rememberSession(String sessionId, String cwd, String candidateLabel, long updatedAt, String agentId) {
        mc.config.shared().agentSessions().remember(sessionId, cwd, candidateLabel, updatedAt, agentId);
    }

    @Override
    public javafx.collections.ObservableList<com.editora.config.AgentSessionHistory.Entry> sessionHistory() {
        return mc.config.shared().agentSessions().getList();
    }
}
