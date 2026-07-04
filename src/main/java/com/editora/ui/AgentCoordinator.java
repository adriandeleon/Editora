package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.agent.AcpClient;
import com.editora.agent.AcpJson;
import com.editora.editor.EditorBuffer;
import com.editora.run.ProgramArgs;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the embedded AI-agent feature (an <a href="https://agentclientprotocol.com">ACP</a> agent such as
 * Claude Code driven over stdio — the {@code CoordinatorHost} feature-coordinator pattern): the
 * {@link AcpClient} lifecycle (spawn on first prompt, one session per window), the {@link AgentPanel}
 * chat tool window, the {@code session/update} → transcript routing, the agent's fs bridge (reads serve
 * an open buffer's <em>live</em> text; writes to an open buffer go through an <em>undoable</em>
 * {@code replaceText}, disk otherwise), and the {@code session/request_permission} dialog.
 * {@code MainController} keeps the {@code ToolWindow} registration + the {@code agent.*} command
 * registrations and delegates the logic here.
 */
final class AgentCoordinator implements AcpClient.Host {

    /** The default agent command — Claude Code's ACP adapter (npm: {@code @zed-industries/claude-code-acp}). */
    static final String DEFAULT_COMMAND = "claude-code-acp";

    /** The agent-specific window services beyond {@link CoordinatorHost}. */
    interface Ops {
        /** This window's project root, or null (no project). */
        Path projectRoot();

        /** The open buffer whose file matches {@code path} (canonical), or null. */
        EditorBuffer bufferForPath(String path);

        /** Toggles the AI Agent tool window. */
        void toggleToolWindow();

        /** Opens the AI Agent tool window (optionally focusing the prompt input). */
        void openToolWindow(boolean focus);

        /** Refreshes the Project tree after the agent wrote a file the editor doesn't have open. */
        void refreshProjectTree();
    }

    private final CoordinatorHost host;
    private final Ops ops;
    /** Spawning the agent runs a login-shell PATH probe + process start — keep it off the FX thread. */
    private final ExecutorService lifecycleExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "acp-agent-lifecycle");
        t.setDaemon(true);
        return t;
    });

    private AgentPanel panel;
    private volatile AcpClient client;
    private volatile String sessionId;

    AgentCoordinator(CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
    }

    /** Whether the AI Agent is enabled (the setting, suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isAgentSupport() && !host.simpleModeActive();
    }

    /** The chat tool window's content (built lazily; {@code MainController} wraps it in a {@code ToolWindow}). */
    AgentPanel panel() {
        if (panel == null) {
            panel = new AgentPanel(this::stopTurn, this::newSession);
            panel.setOnSend(this::sendPrompt);
            applyPanelFont();
        }
        return panel;
    }

    /** Reconciles the feature with its setting (startup + every settings apply): font + teardown when off. */
    void applySupport() {
        applyPanelFont();
        if (!isEnabled()) {
            disposeClient();
        }
    }

    private void applyPanelFont() {
        if (panel != null) {
            var s = host.settings();
            panel.setPanelFont(s.getFontFamily(), Math.max(1, (int) Math.round(s.getFontSize() * s.getFontZoom())));
        }
    }

    // --- commands -----------------------------------------------------------------------------------

    /** {@code tool.agent}: toggle the chat tool window. */
    void toggleToolWindow() {
        ifAgent(ops::toggleToolWindow);
    }

    /** {@code agent.newSession}: drop the current conversation and start fresh on the next prompt. */
    void newSession() {
        ifAgent(() -> {
            AcpClient c = client;
            String sid = sessionId;
            if (c != null && sid != null) {
                c.cancel(sid);
            }
            sessionId = null;
            panel().clearTranscript();
            panel().setBusy(false);
            host.setStatus(tr("status.agent.newSession"));
        });
    }

    /** {@code agent.stop}: cancel the in-flight prompt turn (the session survives). */
    void stopTurn() {
        ifAgent(() -> {
            AcpClient c = client;
            String sid = sessionId;
            if (c != null && sid != null) {
                c.cancel(sid);
            }
        });
    }

    /** Sends one prompt turn (the panel blocks re-entry while a turn is running). */
    void sendPrompt(String text) {
        if (!isEnabled()) {
            host.setStatus(tr("status.agent.disabled"));
            return;
        }
        panel().appendLine("❯ " + text);
        panel().setBusy(true);
        ensureSession()
                .thenCompose(sid -> client.prompt(sid, text))
                .whenComplete((stopReason, error) -> Platform.runLater(() -> {
                    panel().setBusy(false);
                    if (error != null) {
                        String message = String.valueOf(rootCause(error).getMessage());
                        panel().appendLine("✗ " + message);
                        host.setStatus(tr("status.agent.failed", message));
                    } else if ("cancelled".equals(stopReason)) {
                        panel().appendLine(tr("agent.turnCancelled"));
                    }
                }));
    }

    /** The running client's session, starting the agent + a session on first use (off the FX thread). */
    private CompletableFuture<String> ensureSession() {
        AcpClient c = client;
        String sid = sessionId;
        if (c != null && c.isAlive() && sid != null) {
            return CompletableFuture.completedFuture(sid);
        }
        List<String> command = commandTokens();
        Path cwd = sessionCwd();
        return CompletableFuture.supplyAsync(
                        () -> {
                            AcpClient fresh = new AcpClient(command, cwd, this);
                            if (!fresh.start()) {
                                throw new CompletionException(
                                        new IOException(tr("status.agent.startFailed", command.get(0))));
                            }
                            return fresh;
                        },
                        lifecycleExec)
                .thenCompose(fresh -> fresh.initialize()
                        .thenCompose(init -> fresh.newSession(cwd))
                        .thenApply(newSid -> {
                            if (newSid == null) {
                                fresh.dispose();
                                throw new CompletionException(new IOException(tr("status.agent.noSession")));
                            }
                            client = fresh;
                            sessionId = newSid;
                            return newSid;
                        }));
    }

    /** The configured agent command (quote-aware tokens; blank ⇒ {@link #DEFAULT_COMMAND}). */
    private List<String> commandTokens() {
        String configured = host.settings().getAgentCommand();
        String cmd = configured == null || configured.isBlank() ? DEFAULT_COMMAND : configured.trim();
        List<String> tokens = ProgramArgs.tokenize(cmd);
        return tokens.isEmpty() ? List.of(DEFAULT_COMMAND) : tokens;
    }

    /** The session's working directory: project root, else the active file's folder, else the home dir. */
    private Path sessionCwd() {
        Path root = ops.projectRoot();
        if (root != null) {
            return root;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null
                && b.getPath() != null
                && host.isLocalBuffer(b)
                && b.getPath().getParent() != null) {
            return b.getPath().toAbsolutePath().getParent();
        }
        return Path.of(System.getProperty("user.home"));
    }

    // --- AcpClient.Host (reader/request threads — marshal to FX here) --------------------------------

    @Override
    public void onUpdate(AcpJson.Update update) {
        Platform.runLater(() -> {
            switch (update.kind()) {
                case AGENT_MESSAGE -> panel().appendChunk(update.text());
                case TOOL_CALL -> panel().appendLine("⚙ " + update.text());
                case TOOL_CALL_UPDATE -> {
                    if ("failed".equals(update.text())) {
                        panel().appendLine("⚙ ✗ " + tr("agent.toolFailed"));
                    }
                }
                case PLAN -> {
                    if (!update.text().isEmpty()) {
                        panel().appendLine("· " + update.text().replace("\n", "\n· "));
                    }
                }
                case AGENT_THOUGHT, OTHER -> {
                    // thoughts are noisy in a plain transcript; unknown updates are ignored (forward-compatible)
                }
            }
        });
    }

    @Override
    public void onExit(int code) {
        Platform.runLater(() -> {
            client = null;
            sessionId = null;
            if (panel != null) {
                panel.setBusy(false);
                panel.appendLine(tr("agent.exited", code));
            }
        });
    }

    @Override
    public String readTextFile(String path, Integer line, Integer limit) throws Exception {
        EditorBuffer open = fxCall(() -> ops.bufferForPath(path));
        String text = open != null ? fxCall(open::getContent) : Files.readString(Path.of(path));
        return slice(text, line, limit);
    }

    @Override
    public void writeTextFile(String path, String content) throws Exception {
        String body = content == null ? "" : content;
        EditorBuffer open = fxCall(() -> ops.bufferForPath(path));
        if (open != null) {
            // Undoable, review-first: the buffer goes dirty and the user saves (one C-z reverts the edit).
            fxCall(() -> {
                open.getArea().replaceText(body);
                host.setStatus(tr("status.agent.editedBuffer", open.getTitle()));
                return null;
            });
            return;
        }
        Path file = Path.of(path);
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, body);
        Platform.runLater(ops::refreshProjectTree);
    }

    @Override
    public CompletableFuture<String> requestPermission(String title, List<AcpJson.PermissionOption> options) {
        CompletableFuture<String> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            ops.openToolWindow(false); // make sure the user sees what the request belongs to
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.initOwner(host.window());
            alert.setTitle(tr("agent.permissionTitle"));
            alert.setHeaderText(tr("agent.permissionHeader"));
            alert.setContentText(title == null || title.isEmpty() ? tr("agent.permissionBody") : title);
            ButtonType[] buttons = new ButtonType[options.size() + 1];
            for (int i = 0; i < options.size(); i++) {
                buttons[i] = new ButtonType(options.get(i).name());
            }
            buttons[options.size()] = ButtonType.CANCEL;
            alert.getButtonTypes().setAll(buttons);
            var result = alert.showAndWait();
            String optionId = null;
            if (result.isPresent()) {
                for (int i = 0; i < options.size(); i++) {
                    if (result.get() == buttons[i]) {
                        optionId = options.get(i).optionId();
                        break;
                    }
                }
            }
            f.complete(optionId);
        });
        return f;
    }

    // --- helpers --------------------------------------------------------------------------------------

    /** The 1-based {@code line}/{@code limit} slice of {@code text} an ACP fs read may ask for (pure). */
    static String slice(String text, Integer line, Integer limit) {
        if (text == null) {
            return "";
        }
        if ((line == null || line <= 1) && limit == null) {
            return text;
        }
        String[] lines = text.split("\n", -1);
        int from = line == null ? 0 : Math.max(0, Math.min(lines.length, line - 1));
        int to = limit == null ? lines.length : Math.min(lines.length, from + Math.max(0, limit));
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, to));
    }

    /** Runs {@code task} on the FX thread and blocks (with a timeout) for its result — for the fs bridge,
     *  which the agent calls on its own request threads. */
    private static <T> T fxCall(java.util.function.Supplier<T> task) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return task.get();
        }
        CompletableFuture<T> f = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                f.complete(task.get());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(10, TimeUnit.SECONDS);
    }

    private static Throwable rootCause(Throwable t) {
        while (t.getCause() != null && t != t.getCause()) {
            t = t.getCause();
        }
        return t;
    }

    private void ifAgent(Runnable action) {
        if (isEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("status.agent.disabled"));
        }
    }

    private void disposeClient() {
        AcpClient c = client;
        client = null;
        sessionId = null;
        if (c != null) {
            c.dispose();
        }
        if (panel != null) {
            panel.setBusy(false);
        }
    }

    /** Window close / feature off: kill the agent process tree. */
    void shutdown() {
        disposeClient();
        lifecycleExec.shutdownNow();
    }
}
