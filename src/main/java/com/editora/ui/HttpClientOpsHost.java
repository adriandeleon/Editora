package com.editora.ui;

import java.nio.file.Path;
import java.util.List;

import javafx.application.Platform;

import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;

/**
 * What {@link HttpClientCoordinator} needs from a window, resolved from {@link MainController} when called. It
 * lived inside {@code MainController} as an anonymous class; it is a file of its own only because of that
 * file's size (see {@link WindowCommandHost}).
 */
final class HttpClientOpsHost implements HttpClientCoordinator.WindowOps {

    private final MainController mc;

    HttpClientOpsHost(MainController mc) {
        this.mc = mc;
    }

    @Override
    public void openTab(EditorBuffer buffer) {
        mc.addBuffer(buffer, true);
    }

    @Override
    public void updateRunGating() {
        mc.updateRunButton();
    }

    @Override
    public String savedEnvironment() {
        return mc.config.getWorkspaceState().getHttpEnvironment();
    }

    @Override
    public com.editora.config.HttpResponseHistory historyStore() {
        return mc.config.shared().httpHistory();
    }

    @Override
    public void openFile(Path file) {
        mc.fileWorkflows.openPath(file);
    }

    @Override
    public @Nullable Path projectRoot() {
        return mc.windowProjectRoot(); // this window's, not the ProjectManager's active (last-opened) one
    }

    @Override
    public HistoryCoordinator history() {
        return mc.historyCoordinator;
    }

    @Override
    public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
        return mc.confirmHttpSecretReads(file, hosts, reads);
    }

    @Override
    public boolean allowScripts(Path file) {
        return mc.confirmHttpScripts(file);
    }

    @Override
    public boolean isTrusted(Path file) {
        return file != null && mc.config.getTrustStore().isTrusted(file);
    }

    @Override
    public Path scriptCacheDir() {
        return mc.config.getConfigDir().resolve("cache").resolve("http-scripts");
    }

    @Override
    public com.editora.http.NodeResponseScripts.@Nullable Debugger scriptDebugger() {
        if (mc.debugCoordinator == null || !mc.debugCoordinator.debugEffectiveFor("javascript")) {
            return null;
        }
        // Told on a launcher thread once the script waits; the session starts on the FX thread.
        return (port, label, shim, unguarded) ->
                Platform.runLater(() -> mc.debugCoordinator.attachHttpScript(port, label, shim, unguarded));
    }
}
