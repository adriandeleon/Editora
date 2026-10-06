package com.editora.ai;

import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

import com.editora.agent.AcpClient;
import com.editora.agent.AcpJson;

import static com.editora.i18n.Messages.tr;

/** One-shot text generation through Codex's ACP adapter, using its existing login. Each request owns
 * a separate process/session so actions never disturb the chat. Runs on AiService's worker. */
final class CodexAiClient {
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    /** A null prompt checks startup, authentication, model and read-only mode without generating text. */
    void run(
            List<String> command,
            String model,
            String prompt,
            Duration timeout,
            BooleanSupplier cancelled,
            AiClient.Listener listener) {
        Path cwd = null;
        AcpClient agent = null;
        try {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            // Supplied text is the entire task context; don't expose an editor project as the working dir.
            cwd = Files.createTempDirectory("editora-ai-");
            agent = new AcpClient(command, cwd, new TextHost(cancelled, listener));
            if (!agent.start()) {
                throw new IOException(tr("status.ai.codexSetup"));
            }
            long deadline = System.nanoTime() + timeout.toNanos();
            await(agent.initialize(), deadline, cancelled);
            AcpJson.SessionInfo session = await(agent.newSession(cwd), deadline, cancelled);
            String sid = session.sessionId();
            if (sid == null || sid.isBlank()) {
                throw new IOException(tr("status.agent.noSession"));
            }
            // Fail closed if the adapter cannot honor this mode. Client-side writes and permission
            // requests are also refused: only the coordinator may apply the returned replacement.
            await(agent.setMode(sid, "read-only"), deadline, cancelled);
            String usedModel = session.currentModelId();
            if (model != null && !model.isBlank()) {
                usedModel = model.trim();
                await(agent.setModel(sid, usedModel), deadline, cancelled);
            }
            if (usedModel != null && !usedModel.isBlank()) {
                listener.onModel(usedModel);
            }
            String stop = prompt == null ? "end_turn" : await(agent.prompt(sid, prompt), deadline, cancelled);
            listener.onDone(stop);
        } catch (CancellationException e) {
            listener.onDone("cancelled");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            listener.onDone("cancelled");
        } catch (Exception e) {
            listener.onError(AiErrors.describe(e));
        } finally {
            if (agent != null) {
                agent.dispose();
            }
            if (cwd != null) {
                deleteWorkingDirectory(cwd);
            }
        }
    }

    /**
     * Removes our working directory if it is empty; agent output is never deleted recursively. The agent is
     * stopped asynchronously, and on Windows a directory cannot be removed while a process still has it as
     * its working directory, so a refusal is retried for a moment — otherwise every request left its
     * {@code editora-ai-} folder behind in the temp directory there.
     */
    private static void deleteWorkingDirectory(Path cwd) {
        for (int attempt = 0; attempt < 20; attempt++) {
            try {
                Files.deleteIfExists(cwd);
                return;
            } catch (DirectoryNotEmptyException e) {
                return; // an adapter wrote session metadata here; leave it
            } catch (IOException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }

    private static <T> T await(CompletableFuture<T> future, long deadline, BooleanSupplier cancelled) throws Exception {
        while (true) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException();
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                throw new TimeoutException();
            }
            try {
                return future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                // Keep polling cancellation even while startup or a silent turn is waiting for a reply.
            }
        }
    }

    private record TextHost(BooleanSupplier cancelled, AiClient.Listener listener) implements AcpClient.Host {
        @Override
        public void onUpdate(AcpJson.Update update) {
            if (!cancelled.getAsBoolean() && update.kind() == AcpJson.UpdateKind.AGENT_MESSAGE) {
                listener.onText(update.text());
            }
        }

        @Override
        public void onExit(int code) {
            // AcpClient completes pending requests exceptionally; run() delivers the single result.
        }

        @Override
        public String readTextFile(String path, Integer line, Integer limit) throws IOException {
            throw new IOException("AI actions use only the supplied text");
        }

        @Override
        public void writeTextFile(String path, String content) throws IOException {
            throw new IOException("AI actions return text; the editor applies edits");
        }

        @Override
        public CompletableFuture<String> requestPermission(String title, List<AcpJson.PermissionOption> options) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
