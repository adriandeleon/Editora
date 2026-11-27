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

    @Override
    public AgentChangeSets agentChanges() {
        return mc.agentChanges;
    }

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
        mc.historyCoordinator.recordDurably(file, content, com.editora.config.HistoryRevision.REASON_AGENT, completion);
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
        // The history's first read runs on a thread of its own (PERF-01, Pauta 6.2.0); the entry lands after it.
        mc.config
                .shared()
                .agentSessions(history -> history.remember(sessionId, cwd, candidateLabel, updatedAt, agentId));
    }

    @Override
    public javafx.collections.ObservableList<com.editora.config.AgentSessionHistory.Entry> sessionHistory() {
        com.editora.config.AgentSessionHistory history = mc.config.shared().agentSessionsIfRead();
        if (history != null) {
            return history.getList();
        }
        // Not read yet: empty for now, never a read here. The read starts; withSessionHistory waits for it.
        mc.config.shared().readAgentSessionsAhead();
        return javafx.collections.FXCollections.observableArrayList();
    }

    @Override
    public void withSessionHistory(
            java.util.function.Consumer<javafx.collections.ObservableList<com.editora.config.AgentSessionHistory.Entry>>
                    then) {
        mc.config.shared().agentSessions(history -> then.accept(history.getList()));
    }
}
