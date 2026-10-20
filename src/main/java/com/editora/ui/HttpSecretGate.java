package com.editora.ui;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;

import javafx.application.Platform;

import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.http.SecretApproval;
import com.editora.http.SecretGate;
import org.jspecify.annotations.Nullable;

/**
 * The HTTP Client's trust gate for secrets, on the window side. A {@code .http} file is content — often one that
 * came with a cloned repository — and one click sends what it resolves to whatever host it names, so a request that
 * reads {@code $processEnv}/{@code $env}/{@code $dotenv} runs only from a trusted folder, or once the user trusts it.
 *
 * <p>What is asked about is not found here: {@link SecretApproval} works it out on the run's worker from a dry
 * resolution of the requests (the same code as a send, so every form the resolver reads is seen) and calls the
 * {@link SecretGate} this class builds. That gate asks the window ({@link
 * HttpClientCoordinator.WindowOps#allowSecretReads}) on the FX thread and waits for the answer, the way a
 * {@code >>!} redirect waits for Local History; it answers a {@link SecretGate.Verdict}, which {@link SecretApproval}
 * words. Requests that read no secret are never asked about.
 */
final class HttpSecretGate {

    private HttpSecretGate() {}

    /**
     * The gate for a run of {@code buffer}'s requests. Built on the FX thread when the run starts: an untitled buffer
     * is trusted as the project it was opened in, if any; without a project there is no folder to trust, so a secret
     * is never read (the safe choice).
     */
    static SecretGate forBuffer(EditorBuffer buffer, HttpClientCoordinator.WindowOps ops) {
        Path project = ops.projectRoot();
        Path asFile = buffer.getPath() != null
                ? buffer.getPath()
                : project == null ? null : project.resolve(buffer.getDisplayName());
        return (reads, hosts, cancelled) -> {
            if (asFile == null) {
                return SecretGate.Verdict.NO_FOLDER;
            }
            boolean[] wasCancelled = new boolean[1];
            boolean allowed = onFx(() -> {
                // Checked again on the FX thread, just before asking: Run pressed twice, or Run then Cancel, cancels
                // this run while its question waits in the queue — it must not put up a prompt (whose "trust" would
                // be remembered for a run that never happens).
                if (cancelled.getAsBoolean()) {
                    wasCancelled[0] = true;
                    return false;
                }
                return ops.allowSecretReads(asFile, hosts, reads);
            });
            if (wasCancelled[0] || cancelled.getAsBoolean()) {
                return SecretGate.Verdict.CANCELLED;
            }
            return allowed ? SecretGate.Verdict.ALLOWED : SecretGate.Verdict.REFUSED;
        };
    }

    /**
     * The folder the trust dialog offers to trust for {@code file}: the open {@code project} when the file is inside
     * it, else the file's own folder. Both are compared canonically — as {@link com.editora.config.TrustStore}
     * checks them — so a project subfolder that is a link to somewhere else is offered as that real folder, and
     * trusting it covers the next run too.
     */
    static Path trustRoot(Path file, @Nullable Path project) {
        Path absolute = file.toAbsolutePath();
        Path parent = absolute.getParent();
        Path dir = PathKeys.canonical(parent != null ? parent : absolute); // a file always has a folder
        if (project != null) {
            Path root = PathKeys.canonical(project.toAbsolutePath());
            if (dir.startsWith(root)) {
                return root;
            }
        }
        return dir;
    }

    /**
     * {@code question} answered on the FX thread (it may show a modal dialog), waited for on this one. No answer —
     * the window is closing, the worker was interrupted — is a refusal.
     */
    static boolean onFx(Supplier<Boolean> question) {
        if (Platform.isFxApplicationThread()) {
            return Boolean.TRUE.equals(question.get());
        }
        CompletableFuture<Boolean> answer = new CompletableFuture<>();
        try {
            Platform.runLater(() -> {
                try {
                    answer.complete(Boolean.TRUE.equals(question.get()));
                } catch (RuntimeException failure) {
                    answer.complete(false);
                }
            });
            return answer.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | IllegalStateException unanswered) {
            return false;
        }
    }
}
