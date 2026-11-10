package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link DebugCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class DebugOpsHost implements DebugCoordinator.Ops {

    private final MainController mc;

    DebugOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openToolWindow() {
        mc.toolWindows.open(mc.debugToolWindow, false); // never takes focus (review C2)
    }

    @Override
    public void debugDeployed(com.editora.config.RunConfiguration cfg) {
        mc.runCoordinator.debugDeployedConfig(cfg);
    }

    @Override
    public @Nullable String chordFor(String commandId) {
        return mc.keymap.displayChord(commandId);
    }

    @Override
    public void debugHttpRequest() {
        mc.httpClient.debugRequestAtCaret();
    }

    @Override
    public void editConfiguration(String name) {
        mc.runConfigurations.runConfigEditor.accept(name);
    }

    @Override
    public @Nullable List<String> buildExecutable(BuildTool tool, Path root) {
        return mc.buildExecutableFor(tool, root);
    }

    @Override
    public boolean trustBuild(Path root, List<String> items) {
        if (mc.config.getTrustStore().isTrusted(root)) {
            return true;
        }
        TrustPrompts.Prompt prompt = TrustPrompts.buildProject(
                mc.stage, root, new com.editora.build.BuildTrust.Summary(root, null, "", items));
        if (prompt.alert().showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != prompt.trust()) {
            return false;
        }
        mc.config.getTrustStore().trust(root);
        mc.saveTrustDecision();
        return true;
    }

    @Override
    public void toggleToolWindow() {
        mc.toolWindows.toggle(mc.debugToolWindow);
    }

    @Override
    public void setToolWindowAvailable(boolean available) {
        mc.toolWindows.setAvailable(mc.debugToolWindow, available);
        // Called on every DapManager state change (including SUSPENDED), which is what the
        // step/evaluate menu items are gated on.
        mc.refreshMenuEnablement();
    }

    @Override
    public void setStatusDebug(@Nullable String text) {
        mc.statusBar.setDebug(text);
    }

    @Override
    public void setStatusDebugLoading(boolean loading) {
        mc.statusBar.setDebugLoading(loading);
    }

    @Override
    public boolean saveBuffer(EditorBuffer buffer) {
        return mc.fileWorkflows.saveSynchronously(buffer);
    }

    @Override
    public String programArgs(@Nullable Path path) {
        return mc.programArgsFor(path);
    }

    @Override
    public @Nullable Path projectRoot() {
        return mc.windowProjectRoot();
    }

    @Override
    public List<com.editora.config.RunConfiguration> runConfigurations() {
        return mc.runConfigurations.savedConfigs();
    }

    @Override
    public List<com.editora.config.RunCompound> runCompounds() {
        return List.copyOf(mc.config.getWorkspaceState().getRunCompounds());
    }

    /** The compounds' editor, made on first use. */
    private @Nullable DebugCompoundsWindow compoundsWindow;

    @Override
    public void editCompounds(@Nullable String name) {
        DebugCompoundsWindow window = compoundsWindow;
        if (window == null) {
            window = new DebugCompoundsWindow(mc.config, compound -> mc.debugCoordinator.debugRunCompound(compound));
            compoundsWindow = window;
        }
        window.show(name, mc.stage);
    }

    @Override
    public String pythonInterpreterChoice() {
        return mc.config.getWorkspaceState().getPythonInterpreter();
    }

    @Override
    public void openLink(com.editora.run.StackTraceLinks.Link link) {
        mc.testNavigation.openRunLink(link);
    }

    @Override
    public void openPath(@Nullable Path file) {
        mc.fileWorkflows.openPath(file, true); // no "Already open" echo on every stop and step
    }

    @Override
    public boolean isToolWindowOpen() {
        return mc.toolWindows.isOpen(mc.debugToolWindow);
    }

    @Override
    public @Nullable EditorBuffer bufferForPath(@Nullable Path file) {
        return MainController.bufferOf(mc.tabForPath(file));
    }

    @Override
    public void afterBufferLoad(EditorBuffer buffer, Runnable action) {
        mc.fileWorkflows.afterBufferLoad(buffer, action);
    }

    @Override
    public List<String> debugWatches() {
        return mc.config.getWorkspaceState().getDebugWatches();
    }

    @Override
    public void persistDebugWatches(List<String> watches) {
        mc.config.getWorkspaceState().setDebugWatches(new java.util.ArrayList<>(watches));
        mc.config.save();
    }

    @Override
    public List<String> debugPausedWatches() {
        return mc.config.getWorkspaceState().getDebugPausedWatches();
    }

    @Override
    public void persistDebugPausedWatches(List<String> paused) {
        mc.config.getWorkspaceState().setDebugPausedWatches(new java.util.ArrayList<>(paused));
    }

    @Override
    public List<String> debugEvaluateHistory() {
        return mc.config.getWorkspaceState().getDebugEvaluateHistory();
    }

    @Override
    public void persistDebugEvaluateHistory(List<String> history) {
        mc.config.getWorkspaceState().setDebugEvaluateHistory(new java.util.ArrayList<>(history));
        mc.config.save();
    }

    @Override
    public void openLibraryFrame(Path anchor, String uri, int line0, java.util.function.Consumer<EditorBuffer> opened) {
        mc.lspCoordinator.openLibraryFrame(anchor, uri, line0, opened);
    }

    @Override
    public java.util.Map<String, List<String>> debugExceptionFilters() {
        return mc.config.getWorkspaceState().getDebugExceptionFilters();
    }

    @Override
    public void persistDebugExceptionFilters(java.util.Map<String, List<String>> filters) {
        mc.config.getWorkspaceState().setDebugExceptionFilters(filters);
        mc.config.save();
    }

    @Override
    public List<String> debugAttachTargets() {
        return mc.config.getWorkspaceState().getDebugAttachTargets();
    }

    @Override
    public void persistDebugAttachTargets(List<String> targets) {
        mc.config.getWorkspaceState().setDebugAttachTargets(targets);
        mc.config.save();
    }

    @Override
    public boolean debugBreakpointsMuted() {
        return mc.config.getWorkspaceState().isDebugBreakpointsMuted();
    }

    @Override
    public void persistDebugBreakpointsMuted(boolean muted) {
        mc.config.getWorkspaceState().setDebugBreakpointsMuted(muted);
        mc.config.save();
    }

    @Override
    public java.util.Map<String, List<com.editora.config.Breakpoint>> breakpointMap() {
        return mc.config.getBreakpoints();
    }

    @Override
    public void saveBreakpoints() {
        mc.config.saveBreakpoints();
    }

    @Override
    public List<com.editora.config.PinnedLine> pins(String fileKey) {
        return List.copyOf(mc.config.getPins().getOrDefault(fileKey, List.of()));
    }

    @Override
    public void persistPins(String fileKey, List<com.editora.config.PinnedLine> pins) {
        if (pins.isEmpty()) {
            if (mc.config.getPins().remove(fileKey) == null) {
                return;
            }
        } else {
            mc.config.getPins().put(fileKey, List.copyOf(pins));
        }
        mc.config.saveBreakpoints();
    }

    @Override
    public List<com.editora.config.MemberBreakpoint> memberBreakpoints() {
        return List.copyOf(mc.config.getMemberBreakpoints());
    }

    @Override
    public void persistMemberBreakpoints(List<com.editora.config.MemberBreakpoint> breakpoints) {
        List<com.editora.config.MemberBreakpoint> stored = mc.config.getMemberBreakpoints();
        stored.clear();
        stored.addAll(breakpoints);
        mc.config.saveBreakpoints();
    }

    @Override
    public void breakpointsStored(java.util.Map<String, ?> bucket, @Nullable String fileKey) {
        mc.marksStored(MarkMerge.Kind.BREAKPOINTS, bucket, fileKey);
    }
}
