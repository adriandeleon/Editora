package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;

import com.editora.agent.AcpClient;
import com.editora.agent.AcpJson;
import com.editora.config.AgentSessionHistory;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.git.RelativeTime;
import com.editora.run.ProgramArgs;
import org.fxmisc.richtext.CodeArea;

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

        /** Opens {@code target} as a new, unfocused background tab (creating the {@link EditorBuffer} and
         *  loading its just-written content) — so a brand-new file the agent created is immediately
         *  visible, without stealing focus from the chat. FX-thread only. */
        EditorBuffer openBackgroundBuffer(Path target);

        /** Opens (and focuses) {@code file} as a normal tab — already-open switches to it. FX-thread only. */
        void openPath(Path file);

        /** Records a prompt in {@code sessionId} in the persisted resume history (title set once from
         *  {@code candidateLabel}; position/timestamp bumped on every call). FX-thread only. */
        void rememberSession(String sessionId, String cwd, String candidateLabel, long updatedAt);

        /** The persisted resume history, most-recently-used first (backs the resume picker). */
        ObservableList<AgentSessionHistory.Entry> sessionHistory();
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
    private volatile List<AcpJson.ModelInfo> models = List.of();
    private volatile List<AcpJson.ModeInfo> modes = List.of();
    private volatile String currentModelId;
    private volatile String currentModeId;

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
            panel = new AgentPanel(
                    this::stopTurn,
                    this::newSession,
                    this::pickModel,
                    this::pickMode,
                    this::resumeSessionPicker,
                    this::openPathCandidate);
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

    /** {@code agent.newSession}: dispose the current conversation (killing its process tree) and start
     *  fresh on the next prompt. Reuses {@link #disposeClient()} for a clean teardown — this both fixes
     *  a process leak (New Session used to only fire {@code session/cancel} and never dispose the old
     *  {@code AcpClient}/OS process, so it was silently orphaned) and shares one teardown path with resume. */
    void newSession() {
        ifAgent(() -> {
            disposeClient();
            panel().clearTranscript();
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

    /** {@code agent.selectModel}: opens a picker over the session's available models (shared by the
     *  header label click and the palette command). */
    void pickModel() {
        ifAgent(() -> {
            if (models.isEmpty()) {
                host.setStatus(tr("status.agent.noModels"));
                return;
            }
            QuickOpen<AcpJson.ModelInfo> picker = new QuickOpen<>(
                    tr("command.agent.selectModel"),
                    tr("palette.agent.selectModelPrompt"),
                    () -> models,
                    AcpJson.ModelInfo::name,
                    AcpJson.ModelInfo::description,
                    this::setModel);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** {@code agent.selectMode}: opens a picker over the session's available modes (shared by the header
     *  label click and the palette command). */
    void pickMode() {
        ifAgent(() -> {
            if (modes.isEmpty()) {
                host.setStatus(tr("status.agent.noModes"));
                return;
            }
            QuickOpen<AcpJson.ModeInfo> picker = new QuickOpen<>(
                    tr("command.agent.selectMode"),
                    tr("palette.agent.selectModePrompt"),
                    () -> modes,
                    AcpJson.ModeInfo::name,
                    AcpJson.ModeInfo::description,
                    this::setMode);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Switches the running session's active model. */
    private void setModel(AcpJson.ModelInfo model) {
        AcpClient c = client;
        String sid = sessionId;
        if (c == null || sid == null) {
            return;
        }
        c.setModel(sid, model.modelId())
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err == null) {
                        currentModelId = model.modelId();
                        panel().setModelLabel(model.name());
                    } else {
                        host.setStatus(tr(
                                "status.agent.failed",
                                String.valueOf(rootCause(err).getMessage())));
                    }
                }));
    }

    /** Switches the running session's active mode. */
    private void setMode(AcpJson.ModeInfo mode) {
        AcpClient c = client;
        String sid = sessionId;
        if (c == null || sid == null) {
            return;
        }
        c.setMode(sid, mode.id())
                .whenComplete((v, err) -> Platform.runLater(() -> {
                    if (err == null) {
                        currentModeId = mode.id();
                        panel().setModeLabel(mode.name());
                    } else {
                        host.setStatus(tr(
                                "status.agent.failed",
                                String.valueOf(rootCause(err).getMessage())));
                    }
                }));
    }

    /** Max chars of quoted selection text included in the context header (bounds token cost). */
    private static final int SELECTION_PREVIEW_LIMIT = 200;

    /** Max chars of the first prompt kept as a session's title in the resume history. */
    private static final int SESSION_LABEL_LIMIT = 80;

    /**
     * Sends one prompt turn (the panel blocks re-entry while a turn is running). When
     * {@code Settings.agentIncludeContext} is on, the active buffer's path/cursor/selection is prefixed
     * to what's actually sent to the agent (never merged into the echoed transcript line) so the common
     * "explain this line" case works without the agent having to ask which file.
     */
    void sendPrompt(String text) {
        if (!isEnabled()) {
            host.setStatus(tr("status.agent.disabled"));
            return;
        }
        panel().appendLine("❯ " + text);
        String context = host.settings().isAgentIncludeContext() ? activeBufferContext() : null;
        if (context != null) {
            panel().appendLine(context);
        }
        String composed = context == null ? text : context + "\n\n" + text;
        // sessionCwd() reads the active buffer/tabs (FX-thread-only), so it's captured here — before the
        // async chain, which may resolve on a background thread when a fresh process is spawned.
        String cwd = sessionCwd().toString();
        long now = Instant.now().getEpochSecond();
        panel().setBusy(true);
        ensureSession()
                .thenCompose(sid -> {
                    // Persist for resume as soon as the session exists — before the turn completes, so a
                    // crash mid-turn still leaves the session resumable. Title (from the first prompt) is
                    // set once by the store; later prompts only bump ordering/timestamp. rememberSession
                    // mutates an ObservableList, so it must run on the FX thread regardless of which
                    // thread this stage completes on.
                    Platform.runLater(() -> ops.rememberSession(sid, cwd, sessionLabel(text), now));
                    return client.prompt(sid, composed);
                })
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

    /** Pure: a session's one-line resume-history title from its first prompt — trimmed, newlines
     *  flattened to spaces, truncated to {@link #SESSION_LABEL_LIMIT} with an ellipsis. */
    static String sessionLabel(String firstPrompt) {
        if (firstPrompt == null) {
            return "";
        }
        String flat = firstPrompt.strip().replaceAll("\\s+", " ");
        return flat.length() > SESSION_LABEL_LIMIT ? flat.substring(0, SESSION_LABEL_LIMIT) + "…" : flat;
    }

    /** The active buffer's context header, or null if there is no active editor buffer (e.g. the Welcome
     *  tab). Recomputed on every call — the active buffer/caret can change between turns in one session. */
    private String activeBufferContext() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return null;
        }
        CodeArea area = b.getFocusedArea() != null ? b.getFocusedArea() : b.getArea();
        return formatContext(bufferLabel(b), area.getCurrentParagraph() + 1, area.getSelectedText());
    }

    /** The path shown to the agent: relativized against {@link #sessionCwd()} (the same frame of
     *  reference the agent's own tools use) when possible, else the absolute path, else the buffer's
     *  title (an untitled or remote buffer, where a cwd-relative path would be meaningless). */
    private String bufferLabel(EditorBuffer b) {
        Path path = b.getPath();
        if (path == null || !host.isLocalBuffer(b)) {
            return b.getTitle();
        }
        try {
            Path rel = sessionCwd().toAbsolutePath().relativize(path.toAbsolutePath());
            if (!rel.startsWith("..")) {
                return rel.toString();
            }
        } catch (IllegalArgumentException ignored) {
            // different filesystem roots (e.g. Windows drive letters) — fall through to the absolute path
        }
        return path.toString();
    }

    /** Pure: builds the one-line context header. Package-private + static so it's directly unit-tested
     *  (the {@link #slice} idiom), even though it resolves through {@code tr(...)}. */
    static String formatContext(String label, int line, String selectedText) {
        StringBuilder sb = new StringBuilder(tr("agent.context.header", label, line));
        if (selectedText != null && !selectedText.isEmpty()) {
            String preview = selectedText.length() > SELECTION_PREVIEW_LIMIT
                    ? selectedText.substring(0, SELECTION_PREVIEW_LIMIT) + "…"
                    : selectedText;
            sb.append(tr("agent.context.selected", preview.replace("\n", "\\n")));
        }
        return sb.toString();
    }

    /** The running client's session, starting the agent + a session on first use (off the FX thread). */
    private CompletableFuture<String> ensureSession() {
        AcpClient c = client;
        String sid = sessionId;
        if (c != null && c.isAlive() && sid != null) {
            return CompletableFuture.completedFuture(sid);
        }
        Path cwd = sessionCwd();
        return spawnClient(cwd)
                .thenCompose(fresh -> fresh.initialize()
                        .thenCompose(init -> fresh.newSession(cwd))
                        .thenApply(info -> adoptSession(fresh, info)));
    }

    /** Spawns + starts a fresh ACP process (off the FX thread, on {@link #lifecycleExec}); completes
     *  exceptionally with {@code status.agent.startFailed} if the process won't start. Shared by the
     *  fresh-session ({@link #ensureSession}) and resume ({@link #resumeSession}) paths. */
    private CompletableFuture<AcpClient> spawnClient(Path cwd) {
        List<String> command = commandTokens();
        return CompletableFuture.supplyAsync(
                () -> {
                    AcpClient fresh = new AcpClient(command, cwd, this);
                    if (!fresh.start()) {
                        throw new CompletionException(new IOException(tr("status.agent.startFailed", command.get(0))));
                    }
                    return fresh;
                },
                lifecycleExec);
    }

    /** Adopts a freshly-initialized session: validates the id, promotes {@code fresh} to the live
     *  {@link #client}, stores the model/mode catalogs, and refreshes the header. Disposes {@code fresh}
     *  and throws {@code status.agent.noSession} if the info carries no session id. Returns the session id.
     *  Shared by {@link #ensureSession} and {@link #resumeSession}. */
    private String adoptSession(AcpClient fresh, AcpJson.SessionInfo info) {
        if (info.sessionId() == null) {
            fresh.dispose();
            throw new CompletionException(new IOException(tr("status.agent.noSession")));
        }
        client = fresh;
        sessionId = info.sessionId();
        models = info.models();
        modes = info.modes();
        currentModelId = info.currentModelId();
        currentModeId = info.currentModeId();
        Platform.runLater(this::refreshPanelHeader);
        return info.sessionId();
    }

    /** {@code agent.resumeSession}: opens a picker over the persisted session history to reopen a past
     *  chat. Palette-gated like {@link #pickModel}/{@link #pickMode}. */
    void resumeSessionPicker() {
        ifAgent(() -> {
            if (ops.sessionHistory().isEmpty()) {
                host.setStatus(tr("status.agent.noHistory"));
                return;
            }
            QuickOpen<AgentSessionHistory.Entry> picker = new QuickOpen<>(
                    tr("command.agent.resumeSession"),
                    tr("palette.agent.resumeSessionPrompt"),
                    () -> ops.sessionHistory(),
                    AgentCoordinator::displayLabel,
                    e -> sessionDetail(e, Instant.now().getEpochSecond()),
                    this::resumeSession);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Reopens a past chat: tears down the current session, spawns a fresh process, and drives
     *  {@code session/resume}. The transcript starts empty (ACP resume does not replay history — a known
     *  v1 limitation); the agent retains full context internally, so the next prompt works normally. */
    private void resumeSession(AgentSessionHistory.Entry entry) {
        if (entry == null) {
            return;
        }
        if (entry.sessionId().equals(sessionId)) {
            host.setStatus(tr("status.agent.resumed", displayLabel(entry)));
            return;
        }
        disposeClient();
        panel().clearTranscript();
        panel().setBusy(true);
        Path cwd = Path.of(entry.cwd());
        spawnClient(cwd)
                .thenCompose(fresh -> fresh.initialize()
                        .thenCompose(init -> fresh.resumeSession(entry.sessionId(), cwd))
                        .thenApply(info -> adoptSession(fresh, info)))
                .whenComplete((sid, error) -> Platform.runLater(() -> {
                    panel().setBusy(false);
                    if (error != null) {
                        disposeClient();
                        host.setStatus(tr(
                                "status.agent.failed",
                                String.valueOf(rootCause(error).getMessage())));
                    } else {
                        ops.rememberSession(
                                entry.sessionId(),
                                entry.cwd(),
                                entry.label(),
                                Instant.now().getEpochSecond());
                        host.setStatus(tr("status.agent.resumed", displayLabel(entry)));
                    }
                }));
    }

    /** A non-blank display title for an entry (falls back to the "Untitled session" placeholder). Pure. */
    static String displayLabel(AgentSessionHistory.Entry entry) {
        return entry.label() == null || entry.label().isBlank() ? tr("agent.untitledSession") : entry.label();
    }

    /** Pure: the picker's secondary line for a session — "<relative time> · <home-collapsed cwd>". */
    static String sessionDetail(AgentSessionHistory.Entry entry, long nowSeconds) {
        return relativeTimeLabel(entry.updatedAt(), nowSeconds) + " · " + homeCollapsed(entry.cwd());
    }

    /** Pure: localizes a {@link RelativeTime} bucket to an {@code agent.time.*} string. */
    static String relativeTimeLabel(long epochSeconds, long nowSeconds) {
        RelativeTime.Span span = RelativeTime.of(epochSeconds, nowSeconds);
        long v = span.value();
        return switch (span.unit()) {
            case NOW -> tr("agent.time.now");
            case MINUTES -> tr("agent.time.minutesAgo", v);
            case HOURS -> tr("agent.time.hoursAgo", v);
            case DAYS -> tr("agent.time.daysAgo", v);
            case WEEKS -> tr("agent.time.weeksAgo", v);
            case MONTHS -> tr("agent.time.monthsAgo", v);
            case YEARS -> tr("agent.time.yearsAgo", v);
        };
    }

    /** Pure: home-collapses an absolute path for compact display (mirrors the identical private helper
     *  already duplicated in {@code MainController}/{@code SearchCoordinator}). */
    static String homeCollapsed(String full) {
        if (full == null) {
            return "";
        }
        String home = System.getProperty("user.home", "");
        return !home.isEmpty() && (full.equals(home) || full.startsWith(home + java.io.File.separator))
                ? "~" + full.substring(home.length())
                : full;
    }

    /** Pushes the current model/mode display state to the panel header (after a fresh session starts). */
    private void refreshPanelHeader() {
        panel().setModelLabel(modelDisplayName(models, currentModelId));
        panel().setModeLabel(modeDisplayName(modes, currentModeId));
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

    /** {@link AgentPanel}'s inline-code-path click handler: resolves a clicked span's raw text to a file
     *  (relative paths against {@link #sessionCwd()}, {@code ~} expands to the user's home — reusing
     *  {@link PathKeys#resolveUserInput}, the same resolver the Save-As prompt uses) and opens it if it
     *  exists; reports a clear status instead of silently doing nothing when it doesn't resolve to a real
     *  file (the syntactic pre-filter in {@code AgentPanel.looksLikePath} can't know that without disk I/O). */
    private void openPathCandidate(String text) {
        Path resolved = PathKeys.resolveUserInput(text, sessionCwd(), System.getProperty("user.home"));
        if (resolved != null && Files.exists(resolved)) {
            ops.openPath(resolved);
        } else {
            host.setStatus(tr("status.agent.pathNotFound", text));
        }
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
                case PLAN -> panel().setPlan(update.planEntries());
                case MODE_CHANGED -> {
                    currentModeId = update.text();
                    panel().setModeLabel(modeDisplayName(modes, update.text()));
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
        // No open buffer matched this path (a brand-new file, or an unsaved/untitled buffer the agent
        // couldn't have targeted since it has no path yet) — open it as a background tab so the user
        // actually sees what the agent wrote, instead of it only landing on disk with no visible tab.
        Platform.runLater(() -> {
            ops.refreshProjectTree();
            ops.openBackgroundBuffer(file);
        });
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

    /** The display name for {@code modelId} (falls back to the bare id if not found, empty if null). Pure. */
    static String modelDisplayName(List<AcpJson.ModelInfo> models, String modelId) {
        if (modelId == null) {
            return "";
        }
        for (AcpJson.ModelInfo m : models) {
            if (m.modelId().equals(modelId)) {
                return m.name();
            }
        }
        return modelId;
    }

    /** The display name for {@code modeId} (falls back to the bare id if not found, empty if null). Pure. */
    static String modeDisplayName(List<AcpJson.ModeInfo> modes, String modeId) {
        if (modeId == null) {
            return "";
        }
        for (AcpJson.ModeInfo mo : modes) {
            if (mo.id().equals(modeId)) {
                return mo.name();
            }
        }
        return modeId;
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
        models = List.of();
        modes = List.of();
        currentModelId = null;
        currentModeId = null;
        if (c != null) {
            c.dispose();
        }
        if (panel != null) {
            panel.setBusy(false);
            panel.setModelLabel(null);
            panel.setModeLabel(null);
            panel.clearPlan();
        }
    }

    /** Window close / feature off: kill the agent process tree. */
    void shutdown() {
        disposeClient();
        lifecycleExec.shutdownNow();
    }
}
