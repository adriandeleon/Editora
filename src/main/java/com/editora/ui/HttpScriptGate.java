package com.editora.ui;

import java.nio.file.Path;
import java.time.Duration;
import java.util.function.BooleanSupplier;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.NodeResponseScripts;
import com.editora.http.ResponseScripts;
import com.editora.http.SecretGate;

/**
 * The HTTP Client's trust gate for response handlers, on the window side. A {@code > {% … %}} handler is code that
 * came with the {@code .http} file — often with a cloned repository — so it runs only from a trusted folder, or once
 * the user trusts it: the same trust store as build scripts and secrets ({@link
 * HttpClientCoordinator.WindowOps#allowScripts}), asked on the FX thread while the run's worker waits, once per run.
 * Built on the FX thread when a run starts, with the settings of that moment.
 */
final class HttpScriptGate {

    private HttpScriptGate() {}

    /**
     * What runs {@code buffer}'s response handlers in one run. An untitled buffer is trusted as the project it was
     * opened in, if any; without a project there is no folder to trust, and no handler runs.
     */
    static ResponseScripts forBuffer(EditorBuffer buffer, HttpClientCoordinator.WindowOps ops, Settings settings) {
        Path project = ops.projectRoot();
        Path file = buffer.getPath();
        Path asFile = file != null ? file : project == null ? null : project.resolve(buffer.getDisplayName());
        NodeResponseScripts.Trust trust = new NodeResponseScripts.Trust() {
            @Override
            public SecretGate.Verdict allowRun(BooleanSupplier cancelled) {
                if (asFile == null) {
                    return SecretGate.Verdict.NO_FOLDER;
                }
                boolean[] wasCancelled = new boolean[1];
                boolean allowed = HttpSecretGate.onFx(() -> {
                    // Checked again on the FX thread: a run cancelled while this question waited never prompts.
                    if (cancelled.getAsBoolean()) {
                        wasCancelled[0] = true;
                        return false;
                    }
                    return ops.allowScripts(asFile);
                });
                if (wasCancelled[0] || cancelled.getAsBoolean()) {
                    return SecretGate.Verdict.CANCELLED;
                }
                return allowed ? SecretGate.Verdict.ALLOWED : SecretGate.Verdict.REFUSED;
            }

            @Override
            public boolean isTrusted(Path script) {
                return HttpSecretGate.onFx(() -> ops.isTrusted(script)); // the trust store lives on the FX thread
            }
        };
        Path folder = file == null ? null : file.toAbsolutePath().getParent();
        Path cache = ops.scriptCacheDir();
        return new NodeResponseScripts(
                new NodeResponseScripts.Settings(
                        settings.isHttpScriptsEnabled(),
                        settings.getHttpNodePath(),
                        Duration.ofSeconds(settings.getHttpScriptTimeoutSeconds()),
                        cache),
                trust,
                file,
                folder);
    }
}
