package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import com.editora.config.HttpResponseHistory;
import com.editora.editor.EditorBuffer;
import com.editora.http.NodeResponseScripts;

/**
 * The window's {@link HttpClientCoordinator.WindowOps} for one run's trust gates ({@link HttpSecretGate}, {@link
 * HttpScriptGate}), telling {@code asking} {@code true} before a trust question may put up its dialog and {@code
 * false} once it is answered — so the response view says "Waiting for your answer…" instead of a clock that runs
 * while nothing is sent, and starts the clock when the request goes out. Both calls happen on the FX thread; a
 * folder already trusted answers at once, between two frames. Everything else is the window's own.
 */
final class HttpAskingOps implements HttpClientCoordinator.WindowOps {

    private final HttpClientCoordinator.WindowOps ops;
    private final Consumer<Boolean> asking;

    HttpAskingOps(HttpClientCoordinator.WindowOps ops, Consumer<Boolean> asking) {
        this.ops = ops;
        this.asking = asking;
    }

    @Override
    public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
        asking.accept(true);
        try {
            return ops.allowSecretReads(file, hosts, reads);
        } finally {
            asking.accept(false);
        }
    }

    @Override
    public boolean allowScripts(Path file) {
        asking.accept(true);
        try {
            return ops.allowScripts(file);
        } finally {
            asking.accept(false);
        }
    }

    @Override
    public void openTab(EditorBuffer buffer) {
        ops.openTab(buffer);
    }

    @Override
    public void updateRunGating() {
        ops.updateRunGating();
    }

    @Override
    public String savedEnvironment() {
        return ops.savedEnvironment();
    }

    @Override
    public HttpResponseHistory historyStore() {
        return ops.historyStore();
    }

    @Override
    public Path projectRoot() {
        return ops.projectRoot();
    }

    @Override
    public HistoryCoordinator history() {
        return ops.history();
    }

    @Override
    public boolean isTrusted(Path file) {
        return ops.isTrusted(file);
    }

    @Override
    public Path scriptCacheDir() {
        return ops.scriptCacheDir();
    }

    @Override
    public NodeResponseScripts.Debugger scriptDebugger() {
        return ops.scriptDebugger();
    }

    @Override
    public void openFile(Path file) {
        ops.openFile(file);
    }
}
