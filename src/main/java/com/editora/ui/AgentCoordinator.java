package com.editora.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.collections.ObservableList;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.layout.BorderPane;
import javafx.stage.Stage;
import javafx.stage.Window;

import com.editora.agent.AcpAgentRegistry;
import com.editora.agent.AcpClient;
import com.editora.agent.AcpJson;
import com.editora.config.AgentSessionHistory;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.git.RelativeTime;
import com.editora.io.AtomicFileWrite;
import com.editora.process.ProcessRunner;
import org.fxmisc.richtext.CodeArea;

import static com.editora.i18n.Messages.tr;

/**
 * Owns the embedded AI-agent feature (an <a href="https://agentclientprotocol.com">ACP</a> agent such as
 * Claude Code driven over stdio — the {@code CoordinatorHost} feature-coordinator pattern): the
 * {@link AcpClient} lifecycle (spawn on first prompt, one session per window), the {@link AgentPanel}
 * chat tool window, the {@code session/update} → transcript routing, the agent's fs bridge (reads serve
 * an open buffer's <em>live</em> text; writes to a buffer open in any window are an <em>undoable</em>
 * whole-document edit that is refused when the text changed since the agent read it; a file with no buffer
 * is snapshotted to Local History and rewritten in its own encoding), and the
 * {@code session/request_permission} dialog.
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

        /** The open buffer in <em>this window</em> whose file matches {@code path} (canonical), or null. */
        EditorBuffer bufferForPath(String path);

        /**
         * The buffer another window has open for {@code file}, or null. An agent write must go through it: a
         * write to disk underneath a buffer — dirty or not — leaves that window holding text the file no
         * longer has. FX-thread only.
         */
        default EditorBuffer bufferInAnotherWindow(Path file) {
            return null;
        }

        /**
         * Records {@code content} — the text of {@code file} about to be replaced — in Local File History and
         * reports once it is durable ({@code true} also when the feature is off: there is then nothing to
         * wait for). FX-thread only.
         */
        default void recordHistory(Path file, String content, Consumer<Boolean> completion) {
            completion.accept(true);
        }

        /** The {@code .editorconfig} charset the editor would read {@code file} with, or null. Any thread. */
        default String editorConfigCharset(Path file) {
            return null;
        }

        /** Takes this file's place in the app-wide document-write order; null = no shared sequencer (tests). */
        default com.editora.io.DocumentWriteSequencer.Ticket beginDocumentWrite(Path file) {
            return null;
        }

        /** Toggles the AI Agent tool window. */
        void toggleToolWindow();

        /** Opens the AI Agent tool window (optionally focusing the prompt input). */
        void openToolWindow(boolean focus);

        /** Closes (undocks) the AI Agent tool window — used when popping the panel out into its own window. */
        void closeToolWindow();

        /** Shows/hides the AI Agent stripe button (hidden while the panel is detached into its own window). */
        void setToolWindowAvailable(boolean available);

        /** Refreshes the Project tree after the agent wrote a file the editor doesn't have open. */
        void refreshProjectTree();

        /** Opens {@code target} as a new, unfocused background tab (creating the {@link EditorBuffer} and
         *  loading its just-written content) — so a brand-new file the agent created is immediately
         *  visible, without stealing focus from the chat. FX-thread only; the file is read off it, so the
         *  tab appears once the read lands. */
        void openBackgroundBuffer(Path target);

        /** Opens (and focuses) {@code file} as a normal tab — already-open switches to it. FX-thread only. */
        void openPath(Path file);

        /** Records a prompt in {@code sessionId} in the persisted resume history (title + agentId set once
         *  from {@code candidateLabel}/{@code agentId}; position/timestamp bumped on every call). FX-thread only. */
        void rememberSession(String sessionId, String cwd, String candidateLabel, long updatedAt, String agentId);

        /** The persisted resume history, most-recently-used first (backs the resume picker). */
        ObservableList<AgentSessionHistory.Entry> sessionHistory();
    }

    private final CoordinatorHost host;
    private final Ops ops;
    /** Where agent file writes are always refused — the editor's config directory ({@link #protectDirectory}). */
    private volatile Path writeProtectedDir;

    private final AtomicFileWrite.FileOperations documentFiles;
    /** Spawning the agent runs a login-shell PATH probe + process start — keep it off the FX thread. */
    private final ExecutorService lifecycleExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "acp-agent-lifecycle");
        t.setDaemon(true);
        return t;
    });

    private AgentPanel panel;
    /** Non-null while the chat panel is popped out into its own window; the {@link AgentPanel} node moves
     *  between the docked tool window and this stage's scene (a node lives in one scene only). */
    private Stage detachedStage;

    private volatile AcpClient client;
    private volatile String sessionId;
    private volatile List<AcpJson.ModelInfo> models = List.of();
    private volatile List<AcpJson.ModeInfo> modes = List.of();
    private volatile String currentModelId;
    private volatile String currentModeId;
    /** The runtime-current agent client (distinct from the persisted default in Settings.agentClient):
     *  changed only by an explicit pick (switchAgentClient) or a cross-agent resume — NOT reset by a mere
     *  session teardown (disposeClient). Lazily initialized from Settings on first use (activeAgent()). */
    private volatile AcpAgentRegistry.AgentDef activeAgent;
    /** agentId -> last PATH-availability probe (Settings status rows). Mirrors LspManager.availableCache;
     *  invalidated when a client's command override changes (see detect / invalidateDetection). */
    private final Map<String, Boolean> agentAvailableCache = new ConcurrentHashMap<>();

    AgentCoordinator(CoordinatorHost host, Ops ops) {
        this(host, ops, AtomicFileWrite.systemFileOperations());
    }

    /** Filesystem boundary for deterministic document-write failures; production uses the JDK-backed implementation. */
    AgentCoordinator(CoordinatorHost host, Ops ops, AtomicFileWrite.FileOperations documentFiles) {
        this.host = host;
        this.ops = ops;
        this.documentFiles = java.util.Objects.requireNonNull(documentFiles, "documentFiles");
    }

    /** Whether the AI Agent is enabled (the master AI kill switch + the feature's own setting,
     *  suppressed in Simple UI mode). */
    boolean isEnabled() {
        return host.settings().isAiEnabled() && host.settings().isAgentSupport() && !host.simpleModeActive();
    }

    /** The chat tool window's content (built lazily; {@code MainController} wraps it in a {@code ToolWindow}). */
    AgentPanel panel() {
        if (panel == null) {
            panel = new AgentPanel(
                    this::stopTurn,
                    this::newSession,
                    this::pickModel,
                    this::pickMode,
                    this::pickAgentClient,
                    this::resumeSessionPicker,
                    this::openPathCandidate);
            panel.setOnSend(this::sendPrompt);
            panel.setOnDetach(this::toggleDetach);
            applyPanelFont();
        }
        return panel;
    }

    /** Whether the chat panel is currently popped out into its own window. */
    boolean isDetached() {
        return detachedStage != null;
    }

    /** The detach button toggles: dock ⇢ pop out; floating ⇢ dock (closing the window redocks via onHidden). */
    private void toggleDetach() {
        if (detachedStage != null) {
            detachedStage.hide(); // → setOnHidden → redock
        } else {
            detach();
        }
    }

    /**
     * Pops the chat panel out of the tool-window stripe into its own resizable window. The <b>same</b>
     * {@link AgentPanel} node is moved (so the live transcript survives) and the agent session — which lives
     * in this coordinator, not the node — is untouched. The stripe is hidden while floating so the panel
     * can't be pulled back into the dock behind the window; closing the window redocks it.
     */
    private void detach() {
        if (detachedStage != null) {
            detachedStage.toFront();
            return;
        }
        AgentPanel p = panel();
        ops.closeToolWindow(); // free the panel node from the docked slot
        ops.setToolWindowAvailable(false); // hide the stripe while floating
        BorderPane holder = new BorderPane(p);
        Scene scene = new Scene(holder, 480, 640);
        addAppStylesheets(scene);
        Stage stage = new Stage();
        stage.setTitle(tr("toolwindow.agent"));
        Window owner = host.window();
        if (owner != null) {
            stage.initOwner(owner);
        }
        stage.setScene(scene);
        stage.setOnHidden(e -> redockFromHolder(holder));
        detachedStage = stage;
        p.setDetached(true);
        stage.show();
    }

    /** Moves the panel back into the dock when the floating window closes (or is hidden for a redock). */
    private void redockFromHolder(BorderPane holder) {
        if (detachedStage == null) {
            return; // already redocked (reentrancy guard)
        }
        detachedStage = null;
        holder.setCenter(null); // detach the panel node from the closing scene
        if (panel != null) {
            panel.setDetached(false);
        }
        // Don't re-dock into a tearing-down scene when the whole window is closing (the owned floating stage
        // hides as part of that shutdown) — only redock while the owner window is still alive.
        Window owner = host.window();
        boolean redock = isEnabled() && owner != null && owner.isShowing();
        ops.setToolWindowAvailable(redock);
        if (redock) {
            ops.openToolWindow(true); // re-dock (ToolWindowPanel re-parents the panel) + focus
        }
    }

    private void addAppStylesheets(Scene scene) {
        for (String css : new String[] {"/com/editora/styles/app.css", "/com/editora/styles/syntax.css"}) {
            var url = getClass().getResource(css);
            if (url != null) {
                scene.getStylesheets().add(url.toExternalForm());
            }
        }
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

    /** {@code tool.agent}: toggle the chat tool window (or bring the floating window forward while detached). */
    void toggleToolWindow() {
        ifAgent(() -> {
            if (detachedStage != null) {
                detachedStage.toFront();
                detachedStage.requestFocus();
            } else {
                ops.toggleToolWindow();
            }
        });
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
                    Platform.runLater(() -> ops.rememberSession(
                            sid, cwd, sessionLabel(text), now, activeAgent().id()));
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
        Map<String, String> environment;
        try {
            var settings = host.settings();
            environment = activeAgent() == AcpAgentRegistry.AgentDef.LMSTUDIO
                    ? com.editora.agent.LmStudioAgent.environment(
                            settings.getAiLmStudioEndpoint(),
                            settings.getAiLmStudioModel(),
                            settings.getAiApiKeyLmstudio())
                    : Map.of();
        } catch (IllegalArgumentException e) {
            return CompletableFuture.failedFuture(e);
        }
        return CompletableFuture.supplyAsync(
                () -> {
                    AcpClient fresh = new AcpClient(command, cwd, this, environment);
                    if (!fresh.start()) {
                        throw new CompletionException(new IOException(
                                activeAgent() == AcpAgentRegistry.AgentDef.CODEX
                                        ? tr("status.ai.codexSetup")
                                        : tr("status.agent.startFailed", command.get(0))));
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
        // Resume must relaunch the SAME agent that created the session. If that differs from the current
        // runtime client, switch the runtime selection (and header) — but do NOT persist it: resuming an old
        // conversation is a one-off, the user's chosen default (Settings.agentClient) is unchanged.
        AcpAgentRegistry.AgentDef entryAgent = AcpAgentRegistry.from(entry.agentId());
        if (entryAgent != activeAgent()) {
            activeAgent = entryAgent;
            panel().setAgentLabel(entryAgent.displayName());
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
                                Instant.now().getEpochSecond(),
                                entryAgent.id());
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
        return com.editora.config.PathDisplay.collapseHome(full);
    }

    /** Pushes the current agent/model/mode display state to the panel header (after a fresh session starts). */
    private void refreshPanelHeader() {
        panel().setAgentLabel(activeAgent().displayName());
        panel().setModelLabel(modelDisplayName(models, currentModelId));
        panel().setModeLabel(modeDisplayName(modes, currentModeId));
    }

    /** The runtime-current agent client, lazily initialized from the persisted default on first use. */
    private AcpAgentRegistry.AgentDef activeAgent() {
        AcpAgentRegistry.AgentDef a = activeAgent;
        if (a == null) {
            a = AcpAgentRegistry.from(host.settings().getAgentClient());
            activeAgent = a;
        }
        return a;
    }

    /** The active agent client's command tokens: its per-client Settings override if set, else its
     *  registry default (quote-aware; a fully-blank resolution falls back to DEFAULT_COMMAND — never
     *  actually reached in practice, since activeAgent() always resolves to a real, defaulted AgentDef). */
    private List<String> commandTokens() {
        AcpAgentRegistry.AgentDef agent = activeAgent();
        List<String> tokens = AcpAgentRegistry.commandFor(agent.id(), agentCommandOverrides());
        return tokens.isEmpty() ? List.of(DEFAULT_COMMAND) : tokens;
    }

    /** agentId -> the user's per-client command override (blank where unset). Read live from Settings. */
    private Map<String, String> agentCommandOverrides() {
        var s = host.settings();
        return Map.of(
                "claude", s.getAgentCommand(),
                "gemini", s.getGeminiAgentCommand(),
                "copilot", s.getCopilotAgentCommand(),
                "codex", s.getCodexAgentCommand(),
                "qwen", s.getQwenAgentCommand(),
                "opencode", s.getOpencodeAgentCommand(),
                "lmstudio", s.getLmstudioAgentCommand());
    }

    /** Probes whether {@code agentId}'s resolved command is on PATH; cached, off-thread, result on the FX
     *  thread. Mirrors LspManager.detect — backs the Settings page's per-client status rows. */
    void detect(String agentId, Consumer<Boolean> onResult) {
        Boolean hit = agentAvailableCache.get(agentId);
        if (hit != null) {
            Platform.runLater(() -> onResult.accept(hit));
            return;
        }
        List<String> command = AcpAgentRegistry.commandFor(agentId, agentCommandOverrides());
        lifecycleExec.submit(() -> {
            boolean ok = agentAvailable(command);
            agentAvailableCache.put(agentId, ok);
            Platform.runLater(() -> onResult.accept(ok));
        });
    }

    /** Clears the cached availability probes so the next detect re-probes (a command override changed). */
    void invalidateDetection() {
        agentAvailableCache.clear();
    }

    /** True if the command's executable resolves (absolute path exists, or bare name found on PATH).
     *  Mirrors LspManager.available. Package-private + static so it's directly unit-tested. */
    static boolean agentAvailable(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String exe = command.get(0);
        if (exe.indexOf('/') >= 0 || exe.indexOf('\\') >= 0) {
            return Files.isExecutable(Path.of(exe));
        }
        return !ProcessRunner.resolveExecutable(command).get(0).equals(exe);
    }

    /** {@code agent.selectClient}: opens a picker over the known ACP agents (shared by the header label
     *  click and the palette command). Detail line = the resolved command for each agent. */
    void pickAgentClient() {
        ifAgent(() -> {
            QuickOpen<AcpAgentRegistry.AgentDef> picker = new QuickOpen<>(
                    tr("command.agent.selectClient"),
                    tr("palette.agent.selectClientPrompt"),
                    AcpAgentRegistry::all,
                    AcpAgentRegistry.AgentDef::displayName,
                    def -> String.join(" ", AcpAgentRegistry.commandFor(def.id(), agentCommandOverrides())),
                    this::switchAgentClient);
            picker.setOverlayHost(host.overlayHost());
            picker.show(host.window());
        });
    }

    /** Switches the active ACP agent: persists it as the new default, tears down the current session (a
     *  running agent process can't be handed off), clears the transcript, updates the header, and reports.
     *  The SINGLE switch code path — the Settings combo and the header picker both call here (so runtime
     *  activeAgent and Settings.agentClient can never drift apart). Idempotent for the current agent.
     *  Deliberately not gated by ifAgent/isEnabled(): the Settings page lets you pre-configure the active
     *  client even while the feature is off, same as other Settings rows. */
    void switchAgentClient(AcpAgentRegistry.AgentDef def) {
        if (def == null || def == activeAgent()) {
            return;
        }
        host.settings().setAgentClient(def.id());
        host.requestSave(); // coalesced off-thread save — the frequent-path convention (mirrors other coordinators)
        activeAgent = def;
        disposeClient();
        panel().clearTranscript();
        panel().setAgentLabel(def.displayName());
        host.setStatus(tr("status.agent.switched", def.displayName()));
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

    /**
     * What the agent has sent that the FX thread has not applied yet, in arrival order: session updates
     * ({@link AcpJson.Update}) and config changes ({@link Runnable}). A reply streams as hundreds of small
     * chunks; each used to be its own task on the FX queue. They are now collected and applied by one task
     * per batch, which also joins neighbouring text chunks into a single append.
     */
    private final java.util.Queue<Object> inbox = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final java.util.concurrent.atomic.AtomicBoolean inboxScheduled =
            new java.util.concurrent.atomic.AtomicBoolean();

    private void post(Object event) {
        inbox.add(event);
        if (inboxScheduled.compareAndSet(false, true)) {
            Platform.runLater(this::drainInbox);
        }
    }

    private int inboxDrains;

    /** FX tasks that have applied agent events — lets a test show a burst of chunks shares one. */
    int inboxDrains() {
        return inboxDrains;
    }

    /** FX thread: applies everything received so far, in order. */
    private void drainInbox() {
        inboxDrains++;
        inboxScheduled.set(false); // before polling: an event added from here on schedules its own drain
        StringBuilder text = null;
        for (Object event = inbox.poll(); event != null; event = inbox.poll()) {
            if (event instanceof AcpJson.Update update && update.kind() == AcpJson.UpdateKind.AGENT_MESSAGE) {
                text = (text == null ? new StringBuilder() : text).append(update.text());
                continue;
            }
            if (text != null) {
                panel().appendChunk(text.toString());
                text = null;
            }
            if (event instanceof Runnable action) {
                action.run();
            } else {
                applyUpdate((AcpJson.Update) event);
            }
        }
        if (text != null) {
            panel().appendChunk(text.toString());
        }
    }

    @Override
    public void onSessionConfig(String updatedSessionId, AcpJson.SessionInfo info) {
        post((Runnable) () -> {
            if (!java.util.Objects.equals(sessionId, updatedSessionId)) {
                return;
            }
            models = info.models();
            modes = info.modes();
            currentModelId = info.currentModelId();
            currentModeId = info.currentModeId();
            refreshPanelHeader();
        });
    }

    @Override
    public void onUpdate(AcpJson.Update update) {
        post(update);
    }

    private void applyUpdate(AcpJson.Update update) {
        switch (update.kind()) {
            case AGENT_MESSAGE -> panel().appendChunk(update.text());
            case TOOL_CALL -> panel().appendToolLine(update.text());
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

    /** How long an fs request waits for the FX thread; a request that never started by then is cancelled. */
    static final long FX_TIMEOUT_MILLIS = 10_000;

    /** What this session's agent was last shown of each file (by {@link PathKeys#key}); see {@link ServedText}. */
    private final ServedText served = new ServedText();

    /** Orders closed-file writes when the window supplies no app-wide sequencer (tests). */
    private final com.editora.io.DocumentWriteSequencer localWrites = new com.editora.io.DocumentWriteSequencer();

    /**
     * The file an fs request names. {@link AcpClient} hands over the absolute path {@link
     * com.editora.agent.AcpFsGuard} resolved inside the session folder; anything else is refused here rather
     * than resolved against the editor's own working directory, which is not the project.
     */
    static Path sessionPath(String path) throws IOException {
        Path file;
        try {
            file = path == null || path.isBlank() ? null : Path.of(path);
        } catch (RuntimeException e) {
            file = null;
        }
        if (file == null || !file.isAbsolute()) {
            throw new IOException("Refused: not an absolute path inside the session folder: " + path);
        }
        return file.normalize();
    }

    /** FX thread: the buffer holding {@code file} — in this window, else in any other — or null. */
    private EditorBuffer openBuffer(Path file) {
        EditorBuffer here = ops.bufferForPath(file.toString());
        return here != null ? here : ops.bufferInAnotherWindow(file);
    }

    @Override
    public String readTextFile(String path, Integer line, Integer limit) throws Exception {
        Path file = sessionPath(path);
        String key = PathKeys.key(file);
        String text = fxCall(() -> {
            EditorBuffer open = openBuffer(file);
            if (open == null) {
                return null;
            }
            String live = open.getContent();
            served.served(key, live); // in the same FX turn as the read: nothing can be typed in between
            return live;
        });
        if (text == null) {
            // Decoded the way the editor opens the file, so a write can put the same encoding back.
            text = AgentFileWrites.decode(Files.readAllBytes(file), ops.editorConfigCharset(file));
            served.served(key, text);
        }
        return slice(text, line, limit);
    }

    @Override
    public void writeTextFile(String path, String content) throws Exception {
        Path file = sessionPath(path);
        String key = PathKeys.key(file);
        String body = content == null ? "" : content;
        // null = no buffer holds the file; "" = applied to its buffer; anything else = why it was refused.
        String refusal = fxCall(() -> {
            EditorBuffer open = openBuffer(file);
            return open == null ? null : applyToOpenBuffer(open, key, body, path);
        });
        if (refusal == null) {
            writeClosedFile(file, key, body);
        } else if (!refusal.isEmpty()) {
            throw new IOException(refusal);
        }
    }

    /**
     * FX thread: replaces the whole document of {@code open} with the agent's text — in whichever window the
     * buffer lives. Undoable and review-first: the buffer goes dirty and the user saves (one undo reverts the
     * edit); nothing is written to disk. Returns {@code ""} when applied, else the reason it was refused.
     *
     * <p>This is the one place an ACP write replaces a buffer's text.
     */
    private String applyToOpenBuffer(EditorBuffer open, String key, String body, String path) {
        if (!open.isEditable()) {
            return "Cannot apply an agent edit to read-only buffer " + path;
        }
        // The agent sends the whole file as it believes it to be. Text typed since it last read the buffer —
        // or unsaved text it never read at all — would be replaced without notice.
        String stale = served.check(key, open.getContent(), open.isDirty()).refusal(path, "fs/read_text_file");
        if (stale != null) {
            return stale;
        }
        // The agent read getContent() (the whole file), so widen a narrowed buffer rather than nest the file in it.
        open.replaceWholeDocument(body);
        served.served(key, open.getContent()); // its own write is text it knows
        host.setStatus(tr("status.agent.editedBuffer", open.getTitle()));
        return "";
    }

    /**
     * Agent request thread: replaces (or creates) a file no window has open. There is no undo and no review
     * step on this path, so the previous text goes to Local File History before anything is written, the
     * bytes keep the file's own encoding, byte-order mark and line endings, and the replacement is
     * conditional on the file still being what was snapshotted and on no buffer having opened it meanwhile.
     */
    private void writeClosedFile(Path file, String key, String body) throws Exception {
        AgentFileWrites.Plan plan = AgentFileWrites.plan(file, body, ops.editorConfigCharset(file));
        if (plan.existing() != null) {
            String stale = served.check(key, plan.currentText(), false).refusal(file.toString(), "fs/read_text_file");
            if (stale != null) {
                throw new IOException(stale);
            }
            recordBeforeWrite(file, plan.previousText());
        } else if (file.getParent() != null) {
            documentFiles.createDirectories(file.getParent());
        }
        java.util.function.BooleanSupplier stillClosed = () -> {
            try {
                return fxCall(() -> openBuffer(file) == null);
            } catch (Exception e) {
                return false;
            }
        };
        com.editora.io.DocumentWriteSequencer.Ticket shared = ops.beginDocumentWrite(file);
        boolean written;
        try (com.editora.io.DocumentWriteSequencer.Ticket ticket = shared != null ? shared : localWrites.begin(file)) {
            var outcome = ticket.runIfCurrent(() -> plan.existing() != null
                    ? AtomicFileWrite.replaceIfUnchanged(
                            file,
                            plan.existing(),
                            plan.replacement(),
                            () -> ticket.isCurrent() && stillClosed.getAsBoolean(),
                            documentFiles)
                    : AtomicFileWrite.writeIf(
                            file,
                            plan.replacement(),
                            () -> ticket.isCurrent()
                                    && !documentFiles.exists(file, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                                    && stillClosed.getAsBoolean(),
                            documentFiles));
            written = outcome.executed() && Boolean.TRUE.equals(outcome.value());
        }
        if (!written) {
            throw new IOException("Refused: " + file + " changed, or was opened in the editor, while this write"
                    + " was being applied. Read it again and retry.");
        }
        served.served(key, plan.replacementText());
        // No buffer held this path (a brand-new file, or one with no tab) — open it as a background tab so
        // the user actually sees what the agent wrote, instead of it only landing on disk with no visible tab.
        Platform.runLater(() -> {
            ops.refreshProjectTree();
            ops.openBackgroundBuffer(file);
        });
    }

    /** Agent request thread: the previous text is in Local File History, durably, or the write does not happen. */
    private void recordBeforeWrite(Path file, String previousText) throws IOException {
        CompletableFuture<Boolean> durable = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                ops.recordHistory(file, previousText, durable::complete);
            } catch (RuntimeException failure) {
                durable.complete(false);
            }
        });
        boolean kept;
        try {
            kept = Boolean.TRUE.equals(durable.get(FX_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            kept = false;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            kept = false;
        }
        if (!kept) {
            throw new IOException("Refused: could not keep a Local History copy of " + file
                    + " before replacing it, so it was left untouched.");
        }
    }

    /** The agent may edit the project, never the editor's own settings/keymaps/plugins (which can run code). */
    @Override
    public Path writeProtectedDirectory() {
        return writeProtectedDir;
    }

    /** Sets the directory agent file writes are refused under (the editor's configuration directory). */
    void protectDirectory(Path dir) {
        this.writeProtectedDir = dir;
    }

    /**
     * How long after the permission dialog appears its input is ignored. The dialog opens from an agent
     * request, at no moment the user chose — usually while they are typing in the editor — and takes the
     * keyboard focus: the Enter or Space already on its way would otherwise press whatever button has it.
     */
    static final long PERMISSION_GRACE_NANOS = TimeUnit.MILLISECONDS.toNanos(700);

    /** The clock the grace period is measured on (a test seam). */
    java.util.function.LongSupplier permissionClock = System::nanoTime;

    /**
     * The index of the button that has the focus when the permission dialog opens: the first option that
     * rejects just this once, else Cancel ({@code options.size()}), which decides nothing. Never an approving
     * option — agents list "Always Allow" first — and never a standing "reject always" either: whatever a
     * stray key can reach must be harmless and must not outlive the request. Pure.
     */
    static int safePermissionChoice(List<AcpJson.PermissionOption> options) {
        for (int i = 0; i < options.size(); i++) {
            if ("reject_once".equals(options.get(i).kind())) {
                return i;
            }
        }
        return options.size();
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
            guardPermissionDialog(alert, buttons, safePermissionChoice(options));
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

    /**
     * Makes the dialog safe to appear under the user's hands: no button is the default (Enter from anywhere),
     * the focus starts on {@code buttons[safe]}, and until {@link #PERMISSION_GRACE_NANOS} after it is shown
     * every key and every button press is swallowed — so input that was aimed at the editor answers nothing.
     */
    private void guardPermissionDialog(Alert alert, ButtonType[] buttons, int safe) {
        javafx.scene.control.DialogPane pane = alert.getDialogPane();
        long[] shownAt = {Long.MIN_VALUE};
        java.util.function.BooleanSupplier armed =
                () -> shownAt[0] != Long.MIN_VALUE && permissionClock.getAsLong() - shownAt[0] > PERMISSION_GRACE_NANOS;
        pane.addEventFilter(javafx.scene.input.KeyEvent.ANY, e -> {
            if (!armed.getAsBoolean()) {
                e.consume();
            }
        });
        for (ButtonType type : buttons) {
            if (pane.lookupButton(type) instanceof javafx.scene.control.Button button) {
                button.setDefaultButton(false);
                button.addEventFilter(javafx.event.ActionEvent.ACTION, e -> {
                    if (!armed.getAsBoolean()) {
                        e.consume(); // DialogPane closes on an unconsumed ACTION only
                    }
                });
            }
        }
        javafx.scene.Node safeButton = pane.lookupButton(buttons[safe]);
        alert.setOnShown(e -> {
            shownAt[0] = permissionClock.getAsLong();
            if (safeButton != null) {
                safeButton.requestFocus();
                Platform.runLater(safeButton::requestFocus); // after the dialog's own initial focus pass
            }
        });
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

    /**
     * Runs {@code task} on the FX thread and blocks (with a timeout) for its result — for the fs bridge, which
     * the agent calls on its own request threads. A task the FX thread has not started when the wait ends is
     * cancelled, so a request answered with a timeout is not applied afterwards (see {@link FxCall}).
     */
    private static <T> T fxCall(java.util.function.Supplier<T> task) throws Exception {
        return FxCall.call(task, FX_TIMEOUT_MILLIS);
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
        served.clear(); // the next agent process has read nothing yet
        models = List.of();
        modes = List.of();
        currentModelId = null;
        currentModeId = null;
        // activeAgent intentionally NOT reset here — it's a longer-lived runtime selection that outlives a
        // session teardown; only switchAgentClient / a cross-agent resume changes it.
        if (c != null) {
            c.dispose();
        }
        if (panel != null) {
            panel.setBusy(false);
            panel.setModelLabel(null);
            panel.setModeLabel(null);
            panel.setAgentLabel(activeAgent().displayName()); // keep showing which agent is selected
            panel.clearPlan();
        }
    }

    /** Window close / feature off: kill the agent process tree. */
    void shutdown() {
        disposeClient();
        lifecycleExec.shutdownNow();
    }
}
