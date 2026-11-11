package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import com.editora.config.Breakpoint;
import com.editora.config.BreakpointStore;
import com.editora.config.PathKeys;
import com.editora.config.RunConfiguration;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DapServerRegistry;
import com.editora.editor.EditorBuffer;
import com.editora.editor.debug.BreakpointManager;
import com.editora.lsp.LspManager;
import com.editora.run.BuildBeforeLaunch;
import com.editora.run.ProgramArgs;
import com.editora.run.StackTraceLinks;
import com.editora.vfs.Vfs;
import org.jspecify.annotations.Nullable;

import static com.editora.i18n.Messages.tr;

/**
 * Multi-language debugging (Debug Adapter Protocol) for Java / Python / JavaScript, extracted from
 * {@link MainController} via the {@link CoordinatorHost} pattern. Owns the {@link DebugPanel}, the
 * breakpoint persistence + gutter gating, the DAP event sink + panel actions, the inline-values / hover /
 * execution-line editor surfaces, the start/attach/step/run-to-cursor/jump flows, and the
 * {@code debug.toggleAdapter}/{@code debug.setAdapterPath} pickers.
 *
 * <p>The {@link DapManager} is <em>not</em> owned here: it stays a {@code MainController} field (it's built
 * on the LSP {@code lspManager}, and {@code SettingsWindow} + window-dispose reach it) and is passed in,
 * mirroring how {@link LspCoordinator} takes the {@code lspManager}. Java debugging layers on the jdtls LSP
 * session, so the coordinator also takes the {@code lspManager} + {@link LspCoordinator} to push the
 * java-debug bundle and re-gate the java buffers. {@code MainController} keeps the Debug {@code ToolWindow}
 * (built with {@link #panel()}), the shared {@code programArgs}/{@code openRunLink}/{@code save} helpers, and
 * the {@code debug.*} command registrations (which delegate here).
 */
final class DebugCoordinator {

    /** Window hooks beyond {@link CoordinatorHost} that the debug flows need. */
    interface Ops {
        /** Shows the Debug window without moving keyboard focus into it (review C2). */
        void openToolWindow();

        /** Debugs a configuration that runs on a deployment: deployed, started on its host, attached here. */
        default void debugDeployed(RunConfiguration cfg) {}

        /** The active keymap's chord for {@code commandId}, formatted, or null when it is unbound. */
        default @Nullable String chordFor(String commandId) {
            return null;
        }

        /** Debug Request on the active {@code .http} file: its scripts under the debugger (HTTP Client). */
        default void debugHttpRequest() {}

        /** Opens the Run Configurations page on {@code name} — see {@code RunCoordinator.Ops}. */
        void editConfiguration(String name);

        /**
         * The argv prefix a launch may start {@code tool} with from {@code root} (the tasks tree's choice after
         * its trust gate), or null when the user declined to trust the project's wrapper — see
         * {@code RunCoordinator.Ops#buildExecutable}.
         */
        default @Nullable List<String> buildExecutable(com.editora.build.BuildTool tool, Path root) {
            return List.of(tool.commandExample());
        }

        /**
         * Whether a build may run the code of {@code root} — a CMake or Make project, which has no build tool of its
         * own here — after asking, as a build tool's first build does ({@code items}: what will run, one line
         * each); false when the user declined.
         */
        default boolean trustBuild(Path root, List<String> items) {
            return true;
        }

        void toggleToolWindow();

        void setToolWindowAvailable(boolean available);

        /** Status-bar debug-state segment ({@code null} clears it). */
        void setStatusDebug(@Nullable String text);

        /** Status-bar indeterminate loading bar (a session is starting). */
        void setStatusDebugLoading(boolean loading);

        /** Saves {@code buffer} (dirty → write, untitled → Save-As); {@code false} if cancelled/failed. */
        boolean saveBuffer(EditorBuffer buffer);

        /** The remembered program-arguments string for {@code path} (shared with the Run feature). */
        String programArgs(@Nullable Path path);

        /** This window's saved run configurations (none outside a project window). */
        default List<RunConfiguration> runConfigurations() {
            return List.of();
        }

        /** This window's project root (null: none): a Python program's venv and {@code PYTHONPATH}. */
        default @Nullable Path projectRoot() {
            return null;
        }

        /** The Python interpreter picked for this window's project ("" when none was). */
        default String pythonInterpreterChoice() {
            return "";
        }

        /** A stack-trace location double-clicked in the Debug console: resolve + jump (shared resolver). */
        void openLink(StackTraceLinks.Link link);

        /**
         * Opens (or focuses) the tab for {@code file} (the frame's file, for the execution highlight) —
         * quietly: a step that stays in the same file is not news for the status bar.
         */
        void openPath(@Nullable Path file);

        /** Whether the Debug tool window is showing (it is then never closed just because the tab changed). */
        default boolean isToolWindowOpen() {
            return false;
        }

        /** The open buffer for {@code file} (canonical-tab match), or {@code null} when no tab holds it. */
        @Nullable
        EditorBuffer bufferForPath(@Nullable Path file);

        /** Runs {@code action} once an asynchronously opened buffer has received its initial document. */
        default void afterBufferLoad(EditorBuffer buffer, Runnable action) {
            action.run();
        }

        /** The persisted debug-watch expressions (workspace state). */
        List<String> debugWatches();

        /** Persists the debug-watch expressions (workspace state + durable save). */
        void persistDebugWatches(List<String> watches);

        /** The persisted paused watches (a subset of {@link #debugWatches}). */
        default List<String> debugPausedWatches() {
            return List.of();
        }

        /** Persists the paused watches. */
        default void persistDebugPausedWatches(List<String> paused) {}

        /** The persisted expressions evaluated in the evaluate fields, oldest first. */
        default List<String> debugEvaluateHistory() {
            return List.of();
        }

        /** Persists the evaluate fields' history. */
        default void persistDebugEvaluateHistory(List<String> history) {}

        /**
         * Opens the read-only source jdtls has for {@code uri} (a {@code jdt://} class file) at {@code line0},
         * routed through {@code anchor}'s language server, and hands the tab to {@code opened}.
         */
        default void openLibraryFrame(Path anchor, String uri, int line0, Consumer<EditorBuffer> opened) {}

        /** The persisted exception-breakpoint choices, per debug-adapter language (workspace state). */
        default Map<String, List<String>> debugExceptionFilters() {
            return Map.of();
        }

        /** Persists the exception-breakpoint choices (workspace state + save). */
        default void persistDebugExceptionFilters(Map<String, List<String>> filters) {}

        /** This project's compounds: run configurations debugged together (workspace state). */
        default List<com.editora.config.RunCompound> runCompounds() {
            return List.of();
        }

        /** Opens the compounds' editor on compound {@code name} (null: a new one). */
        default void editCompounds(@Nullable String name) {}

        /** The addresses Attach reached in this project, most recent first (workspace state). */
        default List<String> debugAttachTargets() {
            return List.of();
        }

        /** Persists the attach addresses (workspace state + save). */
        default void persistDebugAttachTargets(List<String> targets) {}

        /** Whether Mute Breakpoints was left on for this project (workspace state). */
        default boolean debugBreakpointsMuted() {
            return false;
        }

        /** Persists Mute Breakpoints (workspace state + save). */
        default void persistDebugBreakpointsMuted(boolean muted) {}

        /** The per-file breakpoint map ({@code breakpoints.json}); path-string → that file's breakpoints. */
        Map<String, List<Breakpoint>> breakpointMap();

        /** The values pinned to {@code fileKey}'s lines ({@code breakpoints.json}). */
        default List<com.editora.config.PinnedLine> pins(String fileKey) {
            return List.of();
        }

        /** Replaces the values pinned to {@code fileKey}'s lines (none: removed) and writes {@code breakpoints.json}. */
        default void persistPins(String fileKey, List<com.editora.config.PinnedLine> pins) {}

        /** This project's method breakpoints and field watchpoints ({@code breakpoints.json}). */
        default List<com.editora.config.MemberBreakpoint> memberBreakpoints() {
            return List.of();
        }

        /** Replaces this project's method breakpoints and field watchpoints, and writes {@code breakpoints.json}. */
        default void persistMemberBreakpoints(List<com.editora.config.MemberBreakpoint> breakpoints) {}

        /** Writes {@code breakpoints.json}. */
        void saveBreakpoints();

        /**
         * This window rewrote {@code fileKey}'s breakpoints ({@code null}: several files) in its bucket: the
         * other windows re-read them (see {@link DebugCoordinator#breakpointsChangedElsewhere}).
         */
        default void breakpointsStored(Map<String, ?> bucket, @Nullable String fileKey) {}
    }

    /** Inline-value fetch cap — frames can hold hundreds of locals; the overlay needs a name→value map. */
    private static final int MAX_INLINE_VALUES = 100;

    private final CoordinatorHost host;
    /** The window's debug sessions; the focused one is shown and acted on ({@link #s()}). */
    private final DebugSessions sessions;

    private final LspManager lspManager;
    private final LspCoordinator lsp;
    private final Ops ops;
    /** The console font to apply when the panel is built (set by every settings apply, panel or not). */
    private @Nullable String consoleFontFamily;

    private int consoleFontSize;

    /**
     * The before-launch steps of the session being handled ({@link #s()}), made on first use. Each session has its
     * own, on its own service rather than the Run console's: debugging is not refused because a program runs there,
     * and the sessions of a compound build at the same time. What a step hands back runs as its session's.
     */
    private BeforeLaunchStep beforeLaunch() {
        DebugSession session = s();
        BeforeLaunchStep step = session.beforeLaunch;
        if (step == null) {
            com.editora.run.RunService service = new com.editora.run.RunService();
            session.beforeLaunchService = service;
            step = new BeforeLaunchStep(service);
            step.setContext(() -> r -> () -> inSession(session, r));
            session.beforeLaunch = step;
        }
        return step;
    }

    /** A before-launch step of the session being handled is running: its Stop stops that step. */
    private boolean beforeLaunchActive() {
        BeforeLaunchStep step = s().beforeLaunch;
        return step != null && step.isActive();
    }

    /** The java-debug bundle jars last pushed to the LSP layer — restart jdtls only when this changes. */
    private List<String> appliedDebugBundles = List.of();

    /** The buffer currently carrying the execution-line highlight (cleared on resume/terminate). */
    private @Nullable EditorBuffer execHighlightBuffer;

    /** The buffer currently carrying inline values + an active hover (cleared on resume/terminate). */
    private @Nullable EditorBuffer debugValuesBuffer;

    /**
     * Re-anchored breakpoints of files with <em>no</em> open tab, computed off the FX thread at each session
     * start (see {@link #withClosedBreakpoints}). Merged into {@link #collectBreakpoints()} so a breakpoint
     * in a closed file is armed like VS Code / IntelliJ, not silently inert. {@code volatile}: written on the
     * FX thread, read by the DAP supplier (also the FX thread, but keep it safe).
     */
    private volatile List<DapModels.FileBreakpoints> closedBreakpoints = List.of();

    // Coalesce the per-edit (line-shift) breakpoint persist off the FX hot path — see schedulePersistBreakpoints.
    // Only the synchronous breakpoints.json write is debounced; the adapter update stays immediate. (#551)
    private final javafx.animation.PauseTransition persistDebounce =
            new javafx.animation.PauseTransition(javafx.util.Duration.millis(300));
    private final Set<EditorBuffer> pendingPersist = new LinkedHashSet<>();

    /** A breakpoint condition or log message the adapter could not evaluate, and what it said about it. */
    record EvaluationFailure(String expression, String message) {}

    /** {@link #relaunch} of a session attached to a JVM a build or test run started: it cannot be redone. */
    private static final Runnable ONE_SHOT_ATTACH = () -> {};

    /**
     * {@link #relaunch} of a session attached to an HTTP Client script: redoing it would send the request again,
     * which is Debug Request's to do, not Restart's.
     */
    private static final Runnable HTTP_SCRIPT_ATTACH = () -> {};

    /** {@link #relaunch} of a subprocess's session: its program starts it again, not Restart. */
    private static final Runnable SUBPROCESS = () -> {};

    /**
     * The session whose event, reply, panel action or launch step is being handled ({@link #inSession}); else the
     * focused one, which the Debug window shows and the debug commands act on.
     */
    DebugSession s() {
        DebugSession d = dispatching;
        return d != null ? d : sessions.focused();
    }

    /** The debug manager of {@link #s()}. */
    DapManager dap() {
        return s().dap;
    }

    /** The session being handled while one is ({@link #inSession}); null: the focused one. FX thread. */
    private @Nullable DebugSession dispatching;

    /** Runs {@code r} as {@code session}'s: {@link #s()} and {@link #dap()} are that session's meanwhile. */
    void inSession(DebugSession session, Runnable r) {
        DebugSession before = dispatching;
        dispatching = session;
        try {
            r.run();
        } finally {
            dispatching = before;
        }
    }

    /** As {@link #inSession(DebugSession, Runnable)}, for an answer. */
    private <T> T inSession(DebugSession session, java.util.function.Supplier<T> r) {
        DebugSession before = dispatching;
        dispatching = session;
        try {
            return r.get();
        } finally {
            dispatching = before;
        }
    }

    /** Runs {@code r} later on the FX thread, as the session being handled now ({@link #s()}). */
    private void later(Runnable r) {
        DebugSession session = s();
        Platform.runLater(() -> inSession(session, r));
    }

    /**
     * Starts {@code work} on a virtual thread called {@code name}, given the session being handled now (read here, on
     * the FX thread) so that what it hands back runs as that session's ({@link #laterIn}).
     */
    private void startOffFx(String name, Function<DebugSession, Runnable> work) {
        Runnable job = work.apply(s());
        Thread.ofVirtual().name(name).start(job);
    }

    /** Runs {@code r} later on the FX thread as {@code session}'s; callable from any thread. */
    private void laterIn(DebugSession session, Runnable r) {
        Platform.runLater(() -> inSession(session, r));
    }

    /** {@code c}, run as the session being handled now ({@link #s()}) whenever it is called. */
    private <T> Consumer<T> asThisSession(Consumer<T> c) {
        DebugSession session = s();
        return value -> inSession(session, () -> c.accept(value));
    }

    /** Runs {@code r} as each session's in turn, oldest first. */
    private void forEachSession(Runnable r) {
        for (DebugSession session : sessions.all()) {
            inSession(session, r);
        }
    }

    /**
     * Whether the session being handled is the one the window shows: only its stop marks the editor (execution line,
     * inline values, the exception card, breakpoint states) and the status bar's debug state.
     */
    private boolean shown() {
        return s() == sessions.focused();
    }

    /**
     * Ties {@code session}'s manager to this window: its events, replies, code-replace notices and its panel's actions
     * are each handled as that session's ({@link #inSession}), whichever one is focused.
     */
    private void setUp(DebugSession session) {
        DapManager.Listener events = dapListener();
        session.dap.setListener(inSessionProxy(DapManager.Listener.class, events, session));
        session.dap.setCallbackWrapper(r -> () -> inSession(session, r));
        session.dap.setCodeReplaceNotices(
                (type, message) -> inSession(session, () -> codeReplaceNotice(type, message)));
        session.dap.setBreakpointsSupplier(this::collectBreakpoints);
    }

    /** {@code target} behind {@code type}, each of whose calls runs as {@code session}'s. */
    private <T> T inSessionProxy(Class<T> type, T target, DebugSession session) {
        return type.cast(java.lang.reflect.Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> type.getSimpleName() + " of " + session;
                        };
                    }
                    return inSession(session, () -> {
                        try {
                            return method.invoke(target, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            if (e.getCause() instanceof RuntimeException re) {
                                throw re;
                            }
                            if (e.getCause() instanceof Error err) {
                                throw err;
                            }
                            throw new IllegalStateException(e.getCause());
                        } catch (IllegalAccessException e) {
                            throw new IllegalStateException(e);
                        }
                    });
                }));
    }

    /** The window's debug sessions. */
    DebugSessions sessions() {
        return sessions;
    }

    DebugCoordinator(CoordinatorHost host, DapManager dapManager, LspManager lspManager, LspCoordinator lsp, Ops ops) {
        this.host = host;
        this.sessions = new DebugSessions(dapManager);
        this.lspManager = lspManager;
        this.lsp = lsp;
        this.ops = ops;
        persistDebounce.setOnFinished(e -> flushPendingPersist());
        setUp(sessions.focused());
        memberBreakpoints = new MemberBreakpointCommands(
                host, ops, this::dap, this::allManagers, lspManager, () -> breakpointsMuted);
        memberMarks = new MemberBreakpointMarks(host, ops, lspManager);
        memberBreakpoints.setOnChanged(memberMarks::refreshAll);
        lambdaBreakpoints = new LambdaBreakpoints(host);
        triggered = new TriggeredBreakpoints(host, ops);
        pinnedValues = new PinnedValues(host, ops, this::dap);
        // Each stop's pinned values are kept with it in Debug History.
        pinnedValues.setOnPainted((b, shown) -> {
            if (s().panel != null && b.getPath() != null) {
                s().panel.recordPins(b.getPath(), shown);
            }
        });
    }

    /** Values pinned to lines. */
    private final PinnedValues pinnedValues;

    PinnedValues pinnedValues() {
        return pinnedValues;
    }

    /** Debug: Pin Value to Line — the selection or the expression at the caret, on the caret's line. */
    void togglePinAtCaret() {
        if (debugSupportEnabled()) {
            pinnedValues.togglePinAtCaret();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /** Debug: Unpin Values on Line. */
    void unpinLine() {
        pinnedValues.unpinLine();
    }

    /** Method breakpoints and field watchpoints. */
    private final MemberBreakpointCommands memberBreakpoints;

    /** Their marks on the declaration lines of the open files. */
    private final MemberBreakpointMarks memberMarks;

    /** Places the method breakpoints' and field watchpoints' marks again in every open file. */
    void memberMarksRefreshAll() {
        memberMarks.refreshAll();
    }

    /** Places them again in {@code buffer} (its text settled after an edit). */
    void memberMarksRefresh(EditorBuffer buffer) {
        memberMarks.refresh(buffer);
    }

    private final LambdaBreakpoints lambdaBreakpoints;

    /** Breakpoints that wait for another to be hit (see {@link TriggeredBreakpoints}). */
    private final TriggeredBreakpoints triggered;

    MemberBreakpointCommands memberBreakpoints() {
        return memberBreakpoints;
    }

    /** Debug: Toggle Method Breakpoint — on the method the caret is in (else asks which). */
    void toggleMethodBreakpoint() {
        if (debugSupportEnabled()) {
            memberBreakpoints.toggleMethodAtCaret();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /** Debug: Toggle Field Watchpoint — on the field declared on the caret line (else asks which). */
    void toggleFieldWatchpoint() {
        if (debugSupportEnabled()) {
            memberBreakpoints.toggleFieldAtCaret();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /**
     * The Debug tool-window content (the {@code ToolWindow} itself stays in {@code MainController}), built on
     * its first use: the window's first open, or the first session event that has something to show.
     */
    DebugPanel panel() {
        if (s().panel == null) {
            DebugSession session = s();
            DebugPanel built = new DebugPanel(
                    inSessionProxy(DebugPanel.Actions.class, debugActions(), session), evaluateHistory());
            built.setPrompt(host::promptText);
            built.setExpressionPrompt(this::expressionPrompt); // Add / Edit Watch: the field completes (follow-up 5)
            built.setOnLink(ops::openLink); // double-clicked stack-trace line → jump
            built.setWatches(ops.debugWatches());
            built.setPausedWatches(ops.debugPausedWatches());
            built.setOnHistoryChanged(
                    () -> ops.persistDebugEvaluateHistory(evaluateHistory().entries()));
            built.setChordLookup(id -> java.util.Objects.requireNonNullElse(ops.chordFor(id), ""));
            built.setSourceCheck(this::hasSource);
            built.history().setCapacity(host.settings().getDebugHistoryStops());
            built.setOnWatchesChanged(() -> {
                List<String> watches = new ArrayList<>(built.getWatches());
                List<String> paused = built.getPausedWatches();
                ops.persistDebugPausedWatches(paused); // first: persisting the watches saves the workspace
                ops.persistDebugWatches(watches);
                forEachPanel(
                        other -> { // the watches are the window's: every session evaluates the same ones
                            if (other != built) {
                                other.setWatches(watches);
                                other.setPausedWatches(paused);
                            }
                        });
            });
            if (consoleFontFamily != null) {
                built.setConsoleFont(consoleFontFamily, consoleFontSize);
            }
            built.setPlainValueColours(plainValueColours()); // values legible on mixed themes (C111)
            built.setState(dap().state()); // a session may already be running (the window opened mid-session)
            built.setOnAreaShown(area -> refreshLowLevelViews());
            built.disassemblyView().setOnOpenSource(this::openSourceLine);
            built.memoryView().setOnGo(this::memoryGo);
            built.memoryView().setOnPage(direction -> {
                if (s().memoryReference != null) {
                    s().memoryOffset += (long) direction * DebugMemoryView.PAGE;
                    readMemory();
                }
            });
            s().panel = built;
            refreshExceptionToggles();
            built.setBreakpointsMuted(breakpointsMuted); // the state line says so from the start
            if (shown() && sessionsView != null) {
                sessionsView.setContent(built);
            }
        }
        return s().panel;
    }

    // --- several sessions at once ------------------------------------------------------------------

    /** The Debug tool window's content; null until {@link #view()} builds it. */
    private @Nullable DebugSessionsView sessionsView;

    /**
     * The Debug tool-window content (the {@code ToolWindow} itself stays in {@code MainController}): the focused
     * session's panel, under a strip of the sessions' tabs when there is more than one.
     */
    DebugSessionsView view() {
        if (sessionsView == null) {
            DebugSessionsView built = new DebugSessionsView(new DebugSessionsView.Actions() {
                @Override
                public void show(DebugSession session) {
                    focusSession(session);
                }

                @Override
                public void close(DebugSession session) {
                    closeSession(session);
                }

                @Override
                public void stopAll() {
                    DebugCoordinator.this.stopAll();
                }
            });
            sessionsView = built;
            refreshSessionTabs();
        }
        return sessionsView;
    }

    /**
     * Shows {@code target} and makes it the one the debug commands act on: the editor drops the last one's execution
     * line, inline values and exception card and marks {@code target}'s stop, its breakpoints' states and the status
     * bar's debug state instead.
     */
    void focusSession(DebugSession target) {
        DebugSession before = sessions.focused();
        if (target == before || !sessions.all().contains(target)) {
            return;
        }
        inSession(before, () -> {
            closeMainClassPicker(); // it chose for that session's launch
            closeSmartStepPicker(DapManager.State.INACTIVE);
            clearExecHighlight();
            clearDebugEditorSurfaces();
        });
        sessions.focus(target);
        inSession(target, () -> {
            host.forEachBuffer(this::showBreakpointStates);
            updateDebugStatus(dap().state());
            DapModels.StackFrameInfo frame = s().shownFrame;
            if (dap().state() == DapManager.State.SUSPENDED && frame != null && s().quietStop != s().stopSerial) {
                s().debugFrameId = frame.id(); // the hover evaluator's frame again
                highlightFrame(frame);
                dap().scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
                pinnedValues.refresh(frame.id());
            }
        });
        refreshSessionTabs();
        updateDebugAvailability();
    }

    /**
     * A session for a launch while the focused one is live ("Debug Both"), focused from now on: the launch that asked
     * for it is its own. Past {@link DebugSessions#MAX_SESSIONS}, the oldest session that has ended makes room.
     */
    private DebugSession openSession() {
        makeRoom();
        DebugSession session = new DebugSession(sessions.focused().dap.newSession());
        setUp(session);
        sessions.add(session);
        focusSession(session);
        return session;
    }

    /** Past {@link DebugSessions#MAX_SESSIONS}, forgets the oldest session that has ended (and is not shown). */
    private void makeRoom() {
        if (sessions.size() >= DebugSessions.MAX_SESSIONS) {
            for (DebugSession old : sessions.all()) {
                if (old != sessions.focused() && !inSession(old, this::sessionLive)) {
                    shutdownBeforeLaunch(old);
                    sessions.remove(old);
                    break;
                }
            }
        }
    }

    /**
     * The program of the session being handled started a subprocess the adapter can debug ({@link
     * DapManager.Listener#onChildSession}): a session of its own attaches to it, beside its parent, named as the
     * adapter names it ("Subprocess 1234"). It is shown when it stops; its tab goes when it ends unless it is shown;
     * stopping or ending the parent stops it.
     */
    private void openChildSession(java.util.Map<String, Object> configuration) {
        DebugSession parent = s();
        makeRoom();
        DebugSession child = new DebugSession(parent.dap.newSession());
        child.parent = parent;
        setUp(child);
        sessions.add(child);
        Path anchor = parent.dap.debugFile();
        String name =
                configuration.get("name") instanceof String n && !n.isBlank() ? n : tr("debug.sessions.subprocess");
        inSession(child, () -> {
            nameSession(name);
            s().relaunch = SUBPROCESS;
            dap().startPythonChild(anchor, configuration);
        });
        refreshSessionTabs();
    }

    /** Stops the sessions of the subprocesses of the session being handled that are still live. */
    private void stopChildren() {
        DebugSession self = s();
        for (DebugSession child : sessions.all()) {
            if (child.parent == self && child != self) {
                inSession(child, () -> {
                    if (sessionLive()) {
                        stopOne();
                    }
                });
            }
        }
    }

    /** Closes the tab of {@code session}, which has ended; a live one, or the window's last, stays. */
    void closeSession(DebugSession session) {
        if (sessions.size() < 2 || inSession(session, this::sessionLive)) {
            return;
        }
        if (session == sessions.focused()) {
            List<DebugSession> others = new ArrayList<>(sessions.all());
            others.remove(session);
            focusSession(others.getLast());
        }
        shutdownBeforeLaunch(session);
        sessions.remove(session);
        refreshSessionTabs();
    }

    /** Debug: Stop All Sessions — Stop, in every session that is live. */
    void stopAll() {
        forEachSession(() -> {
            if (sessionLive()) {
                stopOne();
            }
        });
    }

    /** Every session's debug manager, oldest first. */
    List<DapManager> allManagers() {
        return sessions.all().stream().map(session -> session.dap).toList();
    }

    /** Whether any session is starting, running, paused or being built for. */
    boolean anySessionLive() {
        for (DebugSession session : sessions.all()) {
            if (inSession(session, this::sessionLive)) {
                return true;
            }
        }
        return false;
    }

    /** Draws the sessions' tabs again (each one's name and state) and shows the focused one's panel. */
    private void refreshSessionTabs() {
        DebugSessionsView view = sessionsView;
        if (view == null) {
            return;
        }
        List<DebugSessionsView.Tab> tabs = new ArrayList<>();
        List<DebugSession> all = sessions.all();
        for (int i = 0; i < all.size(); i++) {
            DebugSession session = all.get(i);
            int number = i + 1;
            tabs.add(inSession(
                    session,
                    () -> new DebugSessionsView.Tab(
                            session,
                            s().sessionLabel.isEmpty() ? tr("debug.sessions.untitled", number) : s().sessionLabel,
                            sessionStateWords(),
                            sessionLive(),
                            session == sessions.focused())));
        }
        view.setTabs(tabs);
        view.setContent(inSession(sessions.focused(), this::panel));
    }

    /** What a session's tab says of its state. */
    private String sessionStateWords() {
        return switch (dap().state()) {
            case STARTING -> tr("debug.state.starting");
            case RUNNING -> tr("debug.state.running");
            case SUSPENDED -> tr("debug.state.suspended");
            case INACTIVE, TERMINATED -> sessionLive() ? tr("debug.state.starting") : tr("debug.sessions.ended");
        };
    }

    /** Runs {@code action} on each session's Debug panel that has been built. */
    private void forEachPanel(Consumer<DebugPanel> action) {
        for (DebugSession session : sessions.all()) {
            DebugPanel panel = session.panel;
            if (panel != null) {
                action.accept(panel);
            }
        }
    }

    /**
     * Re-reads the UI and editor themes (the panel and the popup are built, and each settings apply). When one is light
     * and the other dark, the Variables tree and the value popup draw values in the UI's colours: the editor theme's
     * token colours vanished on the panel's background (review 2 C111).
     */
    void refreshValueColours() {
        boolean plain = plainValueColours();
        forEachPanel(panel -> panel.setPlainValueColours(plain));
        if (valuePopup != null && valuePopup.popup().getSkin() != null) {
            DebugPanel.setPlainValueColours(valuePopup.popup().getSkin().getNode(), plain);
        }
    }

    private boolean plainValueColours() {
        var s = host.settings();
        return DebugPanel.themesDisagree(
                Themes.backgroundFor(s.getTheme()), EditorThemes.editorBackgroundFor(s.getEditorTheme()));
    }

    /** Matches the console font to the editor font; remembered for a panel not built yet. */
    void setConsoleFont(String family, int size) {
        consoleFontFamily = family;
        consoleFontSize = size;
        forEachPanel(panel -> panel.setConsoleFont(family, size));
    }

    // --- gating / effectiveness --------------------------------------------------------------------

    boolean debugSupportEnabled() {
        // Simple UI mode disables debugging (+ the breakpoint gutter) entirely; saved setting unchanged.
        return host.settings().isDebugSupport() && !host.simpleModeActive();
    }

    /** LSP-on-and-not-simple — the precondition Java debugging layers on (mirrors {@code MainController.lspEnabled}). */
    private boolean lspEnabled() {
        return host.settings().isLspSupport() && !host.simpleModeActive();
    }

    /** Debugging is <em>effective</em> for Java only when LSP + the java server are on and detected, and
     *  the java-debug plugin jar was located. */
    boolean debugEffective() {
        return debugEffectiveFor("java");
    }

    /**
     * Whether debugging is available for {@code language} (the editor/LSP language id): Java layers on the
     * jdtls LSP server + the java-debug plugin; Python needs {@code pythonDebugEnabled} + debugpy detected;
     * JavaScript needs {@code jsDebugEnabled} + the js-debug server + node detected.
     */
    boolean debugEffectiveFor(@Nullable String language) {
        if (!debugSupportEnabled() || language == null) {
            return false;
        }
        com.editora.dap.DebugLanguage profile =
                com.editora.dap.DebugLanguages.forLanguage(language); // TS: JavaScript's
        if (profile == null) {
            profile = com.editora.dap.DebugLanguages.byId(language); // an adapter's id (a user's own: not a language)
        }
        if (profile == null) {
            return false;
        }
        if (profile.availability() == com.editora.dap.DebugLanguage.Availability.JAVA_DEBUG_PLUGIN) {
            return lspEnabled()
                    && lsp.serverEnabled("java")
                    && lsp.isServerAvailable("java")
                    && dap().isAdapterAvailable();
        }
        // Its own switch, when it has one (Python, JavaScript), and its adapter found. Editora's own JVM adapter and
        // Metals' have nothing to install: the tool (groovy, kotlinc, clojure or lein), or Metals for a build's Scala
        // file, is checked when the session starts.
        DebugAdapterSettings.Binding settings = DebugAdapterSettings.of(profile.id());
        if (profile == com.editora.dap.DebugLanguages.CPP && gdbForCpp()) {
            // C and C++ on GDB: its switch is C/C++'s, its adapter GDB's own DAP mode.
            return host.settings().isCppDebugEnabled() && dap().isExternalLocated(com.editora.dap.DebugLanguages.GDB);
        }
        return (settings == null || settings.isEnabled(host.settings())) && dap().isLanguageAvailable(profile.id());
    }

    /** Whether C and C++ are debugged on GDB (Settings → Debugging), not CodeLLDB. */
    private boolean gdbForCpp() {
        return "gdb".equals(host.settings().getCppDebugger());
    }

    /**
     * As {@link #debugEffectiveFor(String)}, for {@code file}: a remote file is debugged on its host, which is
     * asked for its adapter when the session starts (this machine's detection says nothing about it).
     */
    boolean debugEffectiveFor(@Nullable String language, @Nullable Path file) {
        if (file == null || com.editora.process.ExecTargets.runsHere(file)) {
            return debugEffectiveFor(language);
        }
        if (!debugSupportEnabled() || language == null || !DapManager.debuggableOnHost(language)) {
            return false;
        }
        DebugAdapterSettings.Binding settings = DebugAdapterSettings.of(DapServerRegistry.adapterLanguage(language));
        boolean enabled = settings != null && settings.toggleable()
                ? settings.isEnabled(host.settings())
                // Java: jdtls runs on the host and is handed the java-debug jar there (copied from this machine,
                // or the host's own), so the adapter is the host's jdtls's.
                : lspEnabled()
                        && lsp.serverEnabled("java")
                        && (dap().isAdapterAvailable()
                                || com.editora.process.ExecTargets.remoteTool(file, "dap.java") != null);
        return enabled && RemoteExec.runsFor(file);
    }

    /**
     * Debug ▸ Stop: {@link #stopOne()} — and, for a session of a compound that stops all, every session of it still
     * running that compound's launches (VS Code's {@code stopAll}).
     */
    void stop() {
        DebugSession current = s();
        DebugCompound group = current.compound;
        if (group == null || !group.stopAll || group.stopping || !group.holds(current)) {
            stopOne();
            return;
        }
        group.stopping = true;
        try {
            for (DebugSession member : group.sessions()) {
                if (sessions.all().contains(member)) {
                    inSession(member, () -> {
                        if (member == current || sessionLive()) {
                            stopOne();
                        }
                    });
                }
            }
        } finally {
            group.stopping = false;
        }
    }

    /** Stops the session being handled: a before-launch build still running is what gets stopped; else the session. */
    private void stopOne() {
        stopChildren(); // a program's subprocesses go with it
        s().closedBreakpointsRead = null; // a start still reading closed files' breakpoints is not made (review D104)
        if (beforeLaunchActive()) {
            beforeLaunch().stop();
        } else if (s().pendingBuild != null) {
            // The jdtls build before a launch: it finishes on its own, but nothing is launched after it.
            dropPendingBuild();
            host.setStatus(tr("status.debug.launchCancelled"));
        } else if (s().pendingAttachStop != null && !dap().isActive()) {
            // Debug Test waiting for its suspended JVM: there is no session yet, so Stop ends the build that
            // would fork it (review D1) — as it ends the run of an attached session below.
            Runnable cancel = s().pendingAttachStop;
            setPendingAttach(null);
            cancel.run();
        } else {
            Runnable owner = s().relaunch == ONE_SHOT_ATTACH ? s().ownerStop : null;
            s().ownerStop = null;
            queuedAttaches.clear(); // Stop ends the whole run, its other JVMs included
            dap().stop();
            if (owner != null) {
                owner.run();
            }
        }
    }

    /**
     * A Debug Test run is waiting for its suspended JVM ({@code cancel} ends that build), or no longer is
     * ({@code null}): the window opens with Stop usable and its spinner on, like a before-launch build.
     */
    void setPendingAttach(@Nullable Runnable cancel) {
        boolean was = s().pendingAttachStop != null;
        s().pendingAttachStop = cancel;
        if (cancel != null && !was) {
            EditorBuffer active = host.activeBuffer(); // Debug Test runs the active file's test
            s().pendingAttachFile = active == null ? null : active.getPath();
            ops.openToolWindow();
        } else if (cancel == null) {
            s().pendingAttachFile = null;
        }
        // A session being debugged keeps its own buttons (Continue, Stop through ownerStop): the waiting state
        // shows once it ends, when the run's next fork is what Stop would end (see the state listener).
        if (!beforeLaunchActive() && s().pendingBuild == null && !dap().isActive()) {
            panel().setPreparing(cancel != null, tr("debugpanel.state.waitingTest"));
        }
        updateDebugAvailability();
    }

    /**
     * Debug ▸ Restart: stops the session and starts it again <em>the way it was started</em> — saving the
     * edited file, running the configuration's before-launch build, re-anchoring closed-file breakpoints.
     * Re-running only the adapter launch debugged the previous code against the edited buffer's breakpoint
     * lines. An attach has nothing to redo and is simply re-attached — except one a build or test run
     * opened ({@link #attachToPort}): that JVM stops listening once it is resumed, so re-attaching would
     * only let it run to its end and leave a session with no debuggee showing "Running".
     */
    void restart() {
        Runnable again = s().relaunch;
        if (again == ONE_SHOT_ATTACH) {
            host.setStatus(tr("status.debug.cannotRestartAttached"));
            return;
        }
        if (again == HTTP_SCRIPT_ATTACH) {
            host.setStatus(tr("status.debug.cannotRestartHttpScript"));
            return;
        }
        if (again == SUBPROCESS) {
            host.setStatus(tr("status.debug.cannotRestartSubprocess"));
            return;
        }
        if (again == null) {
            dap().restart();
            return;
        }
        dap().stop();
        again.run();
    }

    /** Whether Restart can do anything for the session there is: not for one attached to a build or test run. */
    boolean restartAvailable() {
        return s().relaunch != ONE_SHOT_ATTACH && s().relaunch != HTTP_SCRIPT_ATTACH && s().relaunch != SUBPROCESS;
    }

    /**
     * Attaches the JavaScript debugger to an HTTP Client script waiting on its inspector {@code port} (Debug Request):
     * a session called {@code label}, stepping over the runner {@code shim}, with every breakpoint — the {@code .http}
     * file's script lines and script files alike. {@code unguardedNode} names a Node that runs it without its permission
     * model (one that cannot let a debugger in under it), which the console and status bar then say. FX thread.
     */
    void attachHttpScript(int port, String label, @Nullable Path shim, @Nullable String unguardedNode) {
        if (!debugEffectiveFor("javascript")) {
            host.setStatus(tr("status.http.debugUnavailable"));
            return;
        }
        ops.openToolWindow();
        nameSession(label);
        s().relaunch = HTTP_SCRIPT_ATTACH;
        Path dir = shim == null ? null : shim.getParent();
        List<String> skip = new java.util.ArrayList<>(List.of("<node_internals>/**"));
        if (shim != null) {
            skip.add(shim.toString());
        }
        withClosedBreakpoints(() -> dap().startJsAttach("127.0.0.1", port, label, dir, skip, true));
        if (unguardedNode != null) {
            // Said after the session starts (which clears the console), where the user is looking.
            String why = tr("status.http.debugUnguarded", unguardedNode);
            panel().appendOutput(why + System.lineSeparator(), "stderr");
            host.setStatus(why);
        }
    }

    /** Window close: the before-launch build must not outlive the window that started it. */
    void shutdown() {
        persistDebounce.stop();
        flushPendingPersist(); // a breakpoint moved by an edit in the last 300 ms is not lost with the window
        for (DebugSession session : sessions.all()) {
            shutdownBeforeLaunch(session);
        }
    }

    /** Ends {@code session}'s before-launch service, if it has one (the session or the window is going). */
    private static void shutdownBeforeLaunch(DebugSession session) {
        com.editora.run.RunService service = session.beforeLaunchService;
        if (service != null) {
            service.shutdown();
        }
    }

    /** The session's program is running (not paused, starting or being built for): what Pause acts on (C206). */
    boolean programRunning() {
        return dap().isActive() && dap().state() == DapManager.State.RUNNING;
    }

    /** A session is starting, running, paused or being built for — the states Stop / Restart act on. */
    boolean sessionLive() {
        return dap().state() != DapManager.State.INACTIVE
                || beforeLaunchActive()
                || s().pendingBuild != null
                || s().pendingAttachStop != null;
    }

    /** Names the session being launched in the panel; survives the end of the session it replaces. */
    private void nameSession(@Nullable String label) {
        s().sessionLabel = label == null ? "" : label;
        panel().setSessionFile(s().sessionLabel);
        refreshSessionTabs();
    }

    /** Streams a before-launch build into the Debug console and keeps Stop usable while it runs. */
    private BeforeLaunchStep.Console beforeLaunchConsole() {
        return beforeLaunchConsole(true);
    }

    /** As {@link #beforeLaunchConsole()}; not {@code fresh}: a later step of the same build, which keeps the output. */
    private BeforeLaunchStep.Console beforeLaunchConsole(boolean fresh) {
        s().announceStart = true; // a step's status would outlive it (review 2, A105)
        return new BeforeLaunchStep.Console() {
            @Override
            public void started(String commandLine) {
                ops.openToolWindow();
                panel().setPreparing(true);
                if (fresh) {
                    panel().clearConsole(); // a new launch: the previous session's output is not this one's
                }
                s().consoleStarted = true;
                panel().appendOutput("$ " + commandLine + "\n", "console");
            }

            @Override
            public void output(String line, boolean stderr) {
                panel().appendOutput(line + "\n", stderr ? "stderr" : "stdout");
            }

            @Override
            public void ended(int code, @Nullable String launchError) {
                panel().setPreparing(false);
                s().consoleStarted = code == 0 && launchError == null; // a failed build is not followed by a session
                if (launchError != null) {
                    panel().appendOutput(launchError + "\n", "stderr");
                }
            }
        };
    }

    /** Runs {@code action} only when the Debug feature is enabled; otherwise reports it. */
    void ifDebug(Runnable action) {
        if (debugSupportEnabled()) {
            action.run();
        } else {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
        }
    }

    /** Runs a debugged program in a Terminal tab (Settings → Debug → in the Terminal); set by the window. */
    private com.editora.dap.@Nullable ProgramTerminal programTerminal;

    void setProgramTerminal(com.editora.dap.ProgramTerminal programTerminal) {
        this.programTerminal = programTerminal;
    }

    /**
     * Reconciles the Debug feature with its setting (mirrors {@link LspCoordinator#applySupport}). The plugin
     * jar is located synchronously and pushed into the LSP layer BEFORE any jdtls session can start — doing it
     * in an async callback raced the session-restore, so jdtls could come up without the debug bundle. When the
     * bundle set changes, a running jdtls is restarted so it reloads with (or without) the plugin. Gates the
     * breakpoint gutter + Debug window. Runs at init + every settings apply.
     */
    void applySupport() {
        restoreExceptionFilters();
        var s = host.settings();
        boolean on = debugSupportEnabled(); // effective: off in Simple UI mode
        ProjectScope scope = host.projectScope(); // a trusted project's debugger paths, else the user's
        dap().configure(
                        on,
                        scope.debugTool("java", s.getJavaDebugPluginPath()),
                        s.isPythonDebugEnabled(),
                        scope.debugTool("python", s.getPythonDebugCommand()),
                        s.isJsDebugEnabled(),
                        scope.debugTool("javascript", s.getJsDebugPath()));
        // Delve: as configured, else `dlv` on the PATH (where `go install` puts it is on Editora's PATH).
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.GO,
                        on && s.isGoDebugEnabled(),
                        scope.debugTool("go", s.getGoDebugPath()),
                        found -> applyGating());
        // CodeLLDB: as configured, else Editora's install, VS Code's extension, or the PATH.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.RUST,
                        on && s.isRustDebugEnabled(),
                        scope.debugTool("rust", s.getRustDebugPath()),
                        found -> applyGating());
        // GDB's DAP mode: C and C++'s other debugger, and launch.json's gdb / cppdbg.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.GDB,
                        on,
                        scope.debugTool("gdb", s.getGdbPath()),
                        found -> applyGating());
        loadCustomAdapters(on);
        // netcoredbg: as configured, else Editora's install, a VS Code extension's, or the PATH.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.CSHARP,
                        on && s.isCsharpDebugEnabled(),
                        scope.debugTool("csharp", s.getCsharpDebugPath()),
                        found -> applyGating());
        // rdbg: as configured, else on the PATH or where `gem install --user-install` put it.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.RUBY,
                        on && s.isRubyDebugEnabled(),
                        scope.debugTool("ruby", s.getRubyDebugPath()),
                        found -> applyGating());
        // vscode-php-debug: as configured, else Editora's install or VS Code's extension.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.PHP,
                        on && s.isPhpDebugEnabled(),
                        scope.debugTool("php", s.getPhpDebugPath()),
                        found -> applyGating());
        // C and C++: the same CodeLLDB as Rust's.
        dap().configureExternal(
                        com.editora.dap.DebugLanguages.CPP,
                        on && s.isCppDebugEnabled(),
                        scope.debugTool("rust", s.getRustDebugPath()),
                        found -> applyGating());
        int historyStops = s.getDebugHistoryStops();
        forEachPanel(panel -> panel.history().setCapacity(historyStops)); // fewer forgets the oldest now
        dap().setProgramConsole(s.isDebugProgramConsole()); // takes effect at the next launch
        dap().setPythonSubprocesses(s.isDebugPythonSubprocesses()); // shared by every session, from its next launch
        dap().setNodePath(s.getNodePath()); // the Node.js a JavaScript session runs on (and js-debug)
        dap().setProgramTerminal(s.isDebugProgramConsole() && s.isDebugProgramInTerminal() ? programTerminal : null);
        dap().setJavaDebugSettings(new com.editora.dap.JavaDebugSettings(
                s.isDebugStepFilters(),
                s.isDebugShowStatics(),
                com.editora.dap.JavaDebugSettings.parseExceptionTypes(s.getDebugExceptionTypes()),
                s.isDebugShowToString(),
                s.isDebugSuspendAllThreads()));
        List<String> bundles = on ? dap().bundlePaths() : List.of();
        boolean changed = !bundles.equals(appliedDebugBundles);
        lspManager.setDebugBundles(bundles); // set before sessions start — jdtls always gets the bundle
        appliedDebugBundles = bundles;
        if (!on) {
            dap().stop();
        }
        if (changed) {
            lspManager.restartServer("java"); // reload a running jdtls with/without the bundle (no-op if none)
            lsp.applyGating(); // re-open the java buffers on the fresh session
        }
        applyGating();
        if (on) {
            // Probe debugpy / node off-thread; re-gate when each result lands (enables python/js gutters).
            dap().detectPython(ok -> applyGating());
            dap().detectJs(ok -> applyGating());
        }
    }

    /**
     * Re-reads whether the running jdtls itself advertises the java-debug commands and re-gates. Some
     * distributions (e.g. Homebrew's jdtls) ship and auto-load the plugin, so {@code locate()} finds no jar to
     * inject yet debugging works — capabilities are only known once a server finishes {@code initialize}, so
     * the controller calls this from the server-ready event (#711). Light: no bundle push, no restart.
     */
    void refreshJavaDebugAvailability() {
        boolean before = dap().isLanguageAvailable("java");
        dap().setServerProvidesJavaDebug(lspManager.javaDebugCommandsAvailable());
        if (dap().isLanguageAvailable("java") != before) {
            applyGating(); // the breakpoint gutter + Debug window just became (un)available
        }
    }

    /** Per-buffer breakpoint-gutter gate (only for debuggable languages) + Debug tool-window availability. */
    void applyGating() {
        boolean on = debugSupportEnabled();
        host.forEachBuffer(b -> b.setBreakpointsEnabled(on && isDebuggableBuffer(b)));
        updateDebugAvailability();
        if (!on) {
            ops.setStatusDebug(null);
            debugLoading(false);
        }
    }

    /**
     * The Debug tool-window stripe button is shown only when the active file is debuggable (Java/Python/JS) —
     * or whenever a debug session is live, so it never disappears mid-session if you peek at another file.
     * "Live" includes the seconds a session takes to start and its before-launch build, when there is no
     * adapter connection yet; and a window that is open stays open — making it unavailable closes it, which
     * hid the build output, the only Stop button and a finished session's last output behind a tab switch.
     */
    void updateDebugAvailability() {
        boolean available = debugSupportEnabled()
                && (isDebuggableBuffer(host.activeBuffer()) || anySessionLive() || ops.isToolWindowOpen());
        ops.setToolWindowAvailable(available);
    }

    /** Whether a buffer's language has a registered debug adapter (java/python/javascript). */
    boolean isDebuggableBuffer(@Nullable EditorBuffer b) {
        if (b != null && b.isHttpFile()) {
            // Its scripts are debugged with Debug Request, on this machine only (remote .http files do not run).
            return host.settings().isJsDebugEnabled() && host.isLocalBuffer(b);
        }
        if (b == null || !DapServerRegistry.isDebuggable(b.getLanguage())) {
            return false;
        }
        if (host.isLocalBuffer(b) && (b.getPath() == null || com.editora.process.ExecTargets.runsHere(b.getPath()))) {
            return true;
        }
        // A file whose tools run elsewhere (a remote host, a container, WSL) is debugged there, while that may
        // run commands.
        return b.getPath() != null && DapManager.debuggableOnHost(b.getLanguage()) && RemoteExec.runsFor(b.getPath());
    }

    // --- breakpoints -------------------------------------------------------------------------------

    /** Persists a buffer's breakpoints + (if a session is live) re-sends that file's set to the adapter. */
    void onBreakpointsChanged(EditorBuffer buffer) {
        if (buffer.getBreakpointManager().isEditDriven()) {
            schedulePersistBreakpoints(buffer); // debounced FS write (off the per-newline hot path)
        } else {
            pendingPersist.remove(buffer); // the user's own toggle / edit: written now, never left to a timer
            persistBreakpoints(buffer);
        }
        forEachSession(
                () -> { // every live session is told
                    if (buffer.getPath() != null && dap().isActive()) {
                        // Adapter stays current immediately — but only when what it is told actually changed: editing
                        // the text of a breakpoint's line is a change to persist, not one to put on the wire.
                        DapModels.FileBreakpoints now = fileBreakpoints(buffer, buffer.getPath());
                        if (!now.equals(s().sentBreakpoints.put(buffer.getPath(), now))) {
                            dap().updateBreakpoints(now);
                        }
                        showBreakpointStates(buffer); // a new breakpoint is unverified until the adapter answers for it
                    }
                });
        triggersChanged(buffer);
    }

    /**
     * A breakpoint of {@code changed} was set, changed or removed: one that others wait for may have come or gone, so
     * the other tabs' triggered breakpoints are drawn again and re-sent to each live session where that changes them
     * (one whose trigger is gone waits for none).
     */
    private void triggersChanged(EditorBuffer changed) {
        host.forEachBuffer(b -> {
            var mgr = b.getBreakpointManager();
            if (b == changed || mgr.documentSnapshot().stream().noneMatch(Breakpoint::isTriggered)) {
                return;
            }
            mgr.repaintTriggered();
            forEachSession(() -> {
                if (b.getPath() != null && dap().isActive()) {
                    DapModels.FileBreakpoints now = fileBreakpoints(b, b.getPath());
                    if (!now.equals(s().sentBreakpoints.put(b.getPath(), now))) {
                        dap().updateBreakpoints(now);
                    }
                    showBreakpointStates(b);
                }
            });
        });
    }

    /**
     * The program stopped on a breakpoint: when others wait for it, they are sent to this session from now on, and
     * stop like any other. Waits for the stopped file's buffer to load: the stop may have just opened it.
     */
    private void armTriggered(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty()) {
            return;
        }
        DapModels.StackFrameInfo top = frames.get(0);
        Path file = top.file();
        if (file == null) {
            return;
        }
        DebugSession session = s();
        EditorBuffer b = ops.bufferForPath(file);
        if (b == null) {
            List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key(file), List.of());
            stored.stream().filter(bp -> bp.line() == top.line()).findFirst().ifPresent(bp -> armBy(bp, top));
            return;
        }
        ops.afterBufferLoad(
                b,
                () -> inSession(session, () -> {
                    var mgr = b.getBreakpointManager();
                    Breakpoint bp = mgr.get(top.line() - mgr.regionFirstLine());
                    if (bp != null) {
                        armBy(bp, top);
                    }
                }));
    }

    /** {@code trigger} was hit in this session (at {@code where}): the breakpoints that wait for it are sent. */
    private void armBy(Breakpoint trigger, DapModels.StackFrameInfo where) {
        String id = trigger.id();
        if (id.isEmpty() || s().hitTriggers.contains(id) || !dap().isActive()) {
            return;
        }
        List<BreakpointsPicker.Entry> waiting = triggered.entries().stream()
                .filter(e -> id.equals(e.breakpoint().triggeredBy())
                        && e.breakpoint().enabled())
                .toList();
        s().hitTriggers.add(id);
        if (waiting.isEmpty()) {
            return;
        }
        Set<EditorBuffer> tabs = new java.util.LinkedHashSet<>();
        Map<String, List<Breakpoint>> closed = new LinkedHashMap<>();
        for (BreakpointsPicker.Entry e : waiting) {
            if (e.buffer() != null) {
                tabs.add(e.buffer());
            } else {
                closed.put(e.key(), ops.breakpointMap().getOrDefault(e.key(), List.of()));
            }
        }
        for (EditorBuffer tab : tabs) {
            Path file = tab.getPath();
            if (file != null) {
                DapModels.FileBreakpoints now = fileBreakpoints(tab, file);
                if (!now.equals(s().sentBreakpoints.put(file, now))) {
                    dap().updateBreakpoints(now);
                }
                showBreakpointStates(tab);
            }
        }
        if (!closed.isEmpty()) {
            Predicate<Breakpoint> sent = sentPredicate(s());
            startOffFx("debug-triggered", session -> () -> {
                List<DapModels.FileBreakpoints> computed = ClosedFileBreakpoints.of(
                        closed, Set.of(), RemoteExec::runsFor, ClosedFileBreakpoints::readLinesOrNull, sent);
                laterIn(session, () -> {
                    if (!dap().isActive()) {
                        return;
                    }
                    for (DapModels.FileBreakpoints fb : computed) {
                        if (ops.bufferForPath(fb.file()) == null) { // opened meanwhile: its tab sent them
                            s().sentBreakpoints.put(fb.file(), fb);
                            dap().updateBreakpoints(fb);
                        }
                    }
                });
            });
        }
        String place = where.file() == null || where.file().getFileName() == null
                ? String.valueOf(where.line() + 1)
                : where.file().getFileName() + ":" + (where.line() + 1);
        host.setStatus(tr("status.debug.triggerHit", place, waiting.size()));
        panel().appendNotice(tr("status.debug.triggerHit", place, waiting.size()));
    }

    // --- what the live session says about the breakpoints ------------------------------------------

    /**
     * Paints {@code buffer}'s breakpoints as the live session sees them — hollow while the adapter has not
     * bound one, flagged when it refused one — or as plain breakpoints when there is no session.
     */
    private void showBreakpointStates(EditorBuffer buffer) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        Path file = buffer.getPath();
        if (file == null || dap().state() == DapManager.State.INACTIVE) {
            buffer.getBreakpointManager().setLive(null);
            return;
        }
        Map<Integer, DapModels.BreakpointStatus> known = s().breakpointStatus.getOrDefault(file, Map.of());
        Map<Integer, EvaluationFailure> failures = s().evaluationFailures.get(file);
        Map<Integer, BreakpointManager.Live> live = new java.util.HashMap<>();
        String pending = tr(DebugFeedback.pendingKey(known.values(), buffer.getLanguage()));
        LaunchedLines.Mapping map = lineMap(buffer);
        Set<String> ids = null;
        for (Breakpoint bp : buffer.getBreakpointManager().documentSnapshot()) {
            int launchedLine = map.toLaunched(bp.line());
            if (bp.isTriggered()) {
                ids = ids == null ? triggered.ids() : ids;
                if (TriggeredBreakpoints.waits(bp, ids, s().hitTriggers)) {
                    String trigger = triggered.labelOf(bp.triggeredBy());
                    live.put(
                            bp.line(),
                            new BreakpointManager.Live(
                                    BreakpointManager.LiveState.PENDING,
                                    tr("debug.breakpoint.waiting", trigger == null ? "" : trigger)));
                    continue;
                }
            }
            if (launchedLine < 0) {
                live.put(
                        bp.line(),
                        new BreakpointManager.Live(
                                BreakpointManager.LiveState.PENDING, tr("debug.breakpoint.addedSinceLaunch")));
                continue;
            }
            EvaluationFailure failure = failures == null ? null : failures.get(bp.line());
            if (failures != null
                    && failure != null
                    && !failure.expression().equals(bp.condition())
                    && !failure.expression().equals(bp.logMessage())) {
                failures.remove(bp.line()); // the expression it was about has been edited
                failure = null;
            }
            live.put(
                    bp.line(),
                    DebugFeedback.live(
                            known.get(launchedLine),
                            failure == null ? null : failure.message(),
                            pending,
                            tr("debug.breakpoint.rejected")));
        }
        buffer.getBreakpointManager().setLive(live);
    }

    /** The adapter answered for {@code file}'s breakpoints, or changed its mind about some of them. */
    private void breakpointStatusChanged(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
        Map<Integer, DapModels.BreakpointStatus> known =
                s().breakpointStatus.computeIfAbsent(file, f -> new java.util.HashMap<>());
        if (whole) {
            known.clear();
        }
        EditorBuffer buffer = ops.bufferForPath(file);
        DapModels.FileBreakpoints sent = s().sentBreakpoints.get(file);
        LaunchedLines.Mapping map = lineMap(buffer); // statuses name launched lines; the markers are the document's
        for (DapModels.BreakpointStatus status : statuses) {
            known.put(status.line(), status);
            boolean armed = sent != null && sent.breakpoints().stream().anyMatch(lb -> lb.line() == status.line());
            if (!armed) {
                continue; // a run-to-cursor stop, or an answer about a set that has since been replaced
            }
            if (status.failed() && s().reportedRejections.add(file + ":" + status.line() + ":" + status.message())) {
                host.setStatus(tr(
                        "status.debug.breakpointInvalid",
                        file.getFileName() + ":" + (status.line() + 1),
                        status.message().isEmpty() ? tr("debug.breakpoint.rejected") : status.message()));
            }
            // The adapter bound it to another line (the next one with code): the breakpoint follows, so the
            // dot sits where the program will actually stop. Moving it re-sends the file, now at that line.
            if (status.verified()
                    && status.actualLine() >= 0
                    && status.actualLine() != status.line()
                    && buffer != null
                    && buffer.getBreakpointManager()
                            .moveDocumentLine(map.toCurrent(status.line()), map.toCurrent(status.actualLine()))) {
                known.remove(status.line());
                known.put(
                        status.actualLine(),
                        new DapModels.BreakpointStatus(status.actualLine(), true, false, "", status.actualLine()));
                host.setStatus(tr(
                        "status.debug.breakpointMoved",
                        map.toCurrent(status.line()) + 1,
                        map.toCurrent(status.actualLine()) + 1));
            } else if (status.verified()
                    && status.actualLine() >= 0
                    && status.actualLine() != status.line()
                    && buffer != null
                    && buffer.getBreakpointManager()
                            .mergeDocumentLine(map.toCurrent(status.line()), map.toCurrent(status.actualLine()))) {
                // Bound onto a line that has a breakpoint already: one breakpoint, the one there (the re-send
                // the removal causes leaves the adapter just that one, with its condition).
                known.remove(status.line());
                host.setStatus(tr("status.debug.breakpointMerged", status.line() + 1, status.actualLine() + 1));
            }
        }
        if (buffer != null) {
            showBreakpointStates(buffer);
        }
    }

    /**
     * A notice from the adapter. java-debug reports a breakpoint condition or logpoint message it could not
     * evaluate this way — and then stops on every hit — so the breakpoints carrying that expression are
     * flagged, and the text is shown in the console and the status bar either way.
     */
    private void adapterNotice(String message, boolean error) {
        panel().appendOutput(message + System.lineSeparator(), error ? "stderr" : "console");
        host.setStatus(tr("status.debug.error", message));
        host.forEachBuffer(b -> {
            if (b.getPath() == null) {
                return;
            }
            boolean flagged = false;
            for (Breakpoint bp : b.getBreakpointManager().documentSnapshot()) {
                String expression = DebugFeedback.noticeNames(message, bp.condition())
                        ? bp.condition()
                        : DebugFeedback.noticeNames(message, bp.logMessage()) ? bp.logMessage() : null;
                if (bp.enabled() && expression != null) {
                    s().evaluationFailures
                            .computeIfAbsent(b.getPath(), f -> new java.util.HashMap<>())
                            .put(bp.line(), new EvaluationFailure(expression, message));
                    flagged = true;
                }
            }
            if (flagged) {
                showBreakpointStates(b);
            }
        });
    }

    /**
     * Coalesces the synchronous breakpoints.json write fired per line-shifting edit (holding Enter above a
     * breakpoint) so it lands once, ~300 ms after editing settles, instead of blocking the FX thread per newline.
     * reanchor-on-open recovers indices lost to a crash before the write. (#551)
     */
    private void schedulePersistBreakpoints(EditorBuffer buffer) {
        // A set, not one buffer: an edit that shifts lines in several files (a rename refactoring, format on
        // save-all) reports each of them inside one debounce window, and every one of them must be written.
        pendingPersist.add(buffer);
        persistDebounce.playFromStart();
    }

    private void flushPendingPersist() {
        List<EditorBuffer> pending = List.copyOf(pendingPersist);
        pendingPersist.clear();
        pending.forEach(this::persistBreakpoints);
    }

    /**
     * {@code buffer} now belongs to another file (renamed, moved with its folder, or saved under a new name):
     * its breakpoints follow it. They are keyed by path, so without this they stayed under the old one — gone
     * from the file on its next open, and still sent to the adapter for a path that may no longer exist.
     */
    void bufferPathChanged(EditorBuffer buffer, @Nullable Path oldPath) {
        Path newPath = buffer.getPath();
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer)); // the language may differ
        if (newPath == null || newPath.equals(oldPath)) {
            return;
        }
        boolean oldGone = false;
        if (oldPath != null) {
            try {
                oldGone = !Files.exists(oldPath);
            } catch (RuntimeException closedConnection) {
                oldPath = null; // a remote file whose connection is gone: nothing can be asked about it
            }
        }
        var map = ops.breakpointMap();
        List<Breakpoint> stored = null;
        if (oldPath != null && oldGone) {
            stored = map.remove(key(oldPath)); // renamed away; a Save As copy leaves the original its own
        } else if (oldPath != null) {
            stored = map.get(key(oldPath));
        }
        pendingPersist.remove(buffer);
        if (buffer.isNarrowed()) {
            if (stored != null) {
                map.put(key(newPath), new ArrayList<>(stored)); // region-relative lines cannot be snapshotted
            }
            ops.saveBreakpoints();
        } else {
            // The buffer now IS this file: breakpoints stored for a file it overwrote are replaced, not merged.
            seenInStore.put(buffer, MarkMerge.keys(map.get(key(newPath)), Breakpoint::line));
            persistBreakpoints(buffer); // writes the file, also when the buffer has none (the old key is gone)
        }
        Path renamedFrom = oldPath;
        boolean renamedAway = oldGone;
        forEachSession(() -> {
            if (dap().isActive()) {
                if (renamedFrom != null && s().sentBreakpoints.remove(renamedFrom) != null && renamedAway) {
                    dap().updateBreakpoints(new DapModels.FileBreakpoints(renamedFrom, List.of()));
                }
                DapModels.FileBreakpoints now = fileBreakpoints(buffer, newPath);
                s().sentBreakpoints.put(newPath, now);
                dap().updateBreakpoints(now);
            }
        });
    }

    /**
     * Breakpoints stored for files that are not open — a closed file renamed from the Project tree, the
     * files below a renamed folder — follow the rename too. Open buffers moved theirs in
     * {@link #bufferPathChanged}, so there is nothing left under the old path for those.
     */
    void pathRenamed(Path old, Path target) {
        String sep = old.getFileSystem().getSeparator();
        if (RenamedFileState.rekey(ops.breakpointMap(), key(old), key(target), sep)) {
            ops.saveBreakpoints();
            ops.breakpointsStored(ops.breakpointMap(), null);
        }
    }

    /**
     * The breakpoint store's key for {@code file}: its path for a local file, as it always was, and its
     * {@code sftp://} URI for a remote one — which names the host (the bare path would be a local file's key)
     * and survives a reconnect, whose new connection gives the same file a new {@link Path}.
     */
    static String key(Path file) {
        return Vfs.toStorableString(file);
    }

    private void persistBreakpoints(EditorBuffer buffer) {
        if (buffer.isNarrowed()) {
            return; // region-relative line numbers while narrowed — see BookmarkCoordinator.persistBookmarks
        }
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        var map = ops.breakpointMap();
        String key = key(file);
        List<Breakpoint> stored = map.get(key);
        // Not the snapshot alone: a second window on this file has its own copy of the breakpoints, and
        // writing this one as the whole list deleted every breakpoint the other had set.
        List<Breakpoint> snapshot = buffer.getBreakpointManager().snapshot();
        if (buffer.encryption().isEncrypted()) {
            snapshot = snapshot.stream().map(b -> b.withLineText("")).toList(); // see persistBookmarks
        }
        List<Breakpoint> bps = MarkMerge.withForeign(stored, seenInStore.get(buffer), snapshot, Breakpoint::line);
        if (bps.isEmpty()) {
            map.remove(key);
        } else {
            map.put(key, BreakpointStore.mergePreservingOrder(stored == null ? List.of() : stored, bps));
        }
        seenInStore.put(buffer, MarkMerge.keys(map.get(key), Breakpoint::line));
        ops.saveBreakpoints();
        ops.breakpointsStored(map, key);
    }

    /**
     * The breakpoint lines each buffer last saw in the store for its file (when it loaded or wrote them):
     * what {@link MarkMerge} needs to tell "this buffer removed it" from "another window set it".
     */
    private final Map<EditorBuffer, Set<Integer>> seenInStore = new java.util.WeakHashMap<>();

    /**
     * Another window rewrote {@code fileKey}'s breakpoints ({@code null}: several files) in {@code bucket}.
     * A buffer of this window on that file shows them now — it would otherwise keep its stale copy and
     * write it back — and a live session of this window is told the file's new set.
     */
    void breakpointsChangedElsewhere(Map<String, ?> bucket, @Nullable String fileKey) {
        if (bucket != ops.breakpointMap()) {
            return;
        }
        host.forEachBuffer(b -> {
            Path file = b.getPath();
            if (file == null || b.isNarrowed()) {
                return; // a narrowed buffer's lines are region-relative; it merges when it next writes
            }
            String key = key(file);
            if (fileKey != null && !fileKey.equals(key)) {
                return;
            }
            List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key, List.of());
            b.applyBreakpoints(stored);
            seenInStore.put(b, MarkMerge.keys(stored, Breakpoint::line));
            forEachSession(() -> {
                if (dap().isActive()) {
                    DapModels.FileBreakpoints now = fileBreakpoints(b, file);
                    if (!now.equals(s().sentBreakpoints.put(file, now))) {
                        dap().updateBreakpoints(now);
                    }
                }
            });
            showBreakpointStates(b);
        });
    }

    void restoreBreakpoints(EditorBuffer buffer) {
        Path file = buffer.getPath();
        if (file == null) {
            return;
        }
        if (dap().isActive()) {
            snapshotLaunched(buffer); // opened during a session: as on disk, which is what the program was built from
        }
        List<Breakpoint> stored = ops.breakpointMap().getOrDefault(key(file), List.of());
        seenInStore.put(buffer, MarkMerge.keys(stored, Breakpoint::line));
        buffer.getBreakpointManager().setTriggerLabels(triggered::labelOf);
        if (buffer.applyBreakpoints(stored)) {
            persistBreakpoints(buffer); // self-heal re-anchored indices once
        }
        showBreakpointStates(buffer); // opened during a session: it shows what the adapter already said
        pinnedValues.restore(buffer);
        memberMarks.refresh(buffer);
        reshowFrameIn(buffer);
    }

    /**
     * {@code buffer} was just (re)opened: if it is the file of the frame on show, it gets the execution line
     * and inline values back — the buffer that carried them was disposed with its tab.
     */
    private void reshowFrameIn(EditorBuffer buffer) {
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (frame == null
                || frame.file() == null
                || dap().state() != DapManager.State.SUSPENDED
                || buffer == execHighlightBuffer
                || !samePath(frame.file(), buffer.getPath())) {
            return;
        }
        clearExecHighlight();
        paintExecutionLine(buffer, frame);
        dap().scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
    }

    /**
     * The enabled breakpoints of {@code buffer} as a DAP {@code FileBreakpoints} (empty list if none), in
     * whole-file lines even while the buffer is narrowed.
     */
    private DapModels.FileBreakpoints fileBreakpoints(EditorBuffer buffer, Path file) {
        List<DapModels.LineBreakpoint> lines = new ArrayList<>();
        // B1: in the numbering of the code the program runs — a breakpoint whose line was added since the
        // session started has no place in it, and waits for the next session.
        LaunchedLines.Mapping map = lineMap(buffer);
        var mgr = buffer.getBreakpointManager();
        String language = buffer.getLanguage();
        Set<String> ids = null; // the window's breakpoint ids, read once a triggered breakpoint asks
        for (Breakpoint bp : mgr.documentSnapshot()) {
            int line = map.toLaunched(bp.line());
            if (bp.isTriggered()) {
                ids = ids == null ? triggered.ids() : ids;
                if (TriggeredBreakpoints.waits(bp, ids, s().hitTriggers)) {
                    continue; // until its trigger is hit in this session
                }
            }
            if (bp.enabled() && !breakpointsMuted && line >= 0) { // Mute Breakpoints: the adapter is told none
                lines.addAll(BreakpointTargets.of(bp, line, documentLineText(buffer, bp.line()), language));
            }
        }
        return new DapModels.FileBreakpoints(file, lines);
    }

    /** The text of whole-document line {@code line} of {@code buffer}, or null while narrowing hides it. */
    private static @Nullable String documentLineText(EditorBuffer buffer, int line) {
        var area = buffer.getArea();
        int i = line - buffer.getBreakpointManager().regionFirstLine();
        return i >= 0 && i < area.getParagraphs().size() ? area.getParagraph(i).getText() : null;
    }

    // --- B1: lines of the code the program runs, against the document as it is now ---------------------

    /**
     * {@link #rebaselineLines(Path)} for each of {@code files}: after a hot code replace, the Java files saved
     * since the last successful replace (see {@link #codeReplaced}).
     */
    void rebaselineLines(Set<Path> files) {
        if (files != null) {
            files.forEach(this::rebaselineLines);
        }
    }

    /** The mapping between {@code b}'s launched text and its document (identity when there is none). */
    private LaunchedLines.Mapping lineMap(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        if (b == null || file == null || b.isNarrowed() || !s().launchedLines.has(file)) {
            return LaunchedLines.Mapping.IDENTITY; // a narrowed buffer's text is its region only
        }
        // The area's own text, not getContent(): that is cached per document version, which has not moved yet
        // when a breakpoint shift is reported in the middle of the edit.
        return s().launchedLines.mapping(file, b.getArea().getText());
    }

    /** Records every open file's text as the session's launched text (the files were saved for the launch). */
    private void snapshotLaunchedLines() {
        s().launchedLines.clear();
        s().changedNoticed.clear();
        host.forEachBuffer(this::snapshotLaunched);
    }

    /** {@code b}'s launched text: its document, or the file on disk when it has unsaved edits. */
    private void snapshotLaunched(EditorBuffer b) {
        Path file = b.getPath();
        if (file == null || b.isNarrowed() || !host.isLocalBuffer(b)) {
            return;
        }
        if (!b.isDirty()) {
            s().launchedLines.snapshotIfAbsent(file, b.getContent());
            return;
        }
        List<String> disk = ClosedFileBreakpoints.readLinesOrNull(file);
        if (disk != null) {
            s().launchedLines.snapshotIfAbsent(file, String.join("\n", disk));
        }
    }

    /** {@code frames} with each line as the document now has it (a frame names the launched line). */
    private List<DapModels.StackFrameInfo> currentLines(List<DapModels.StackFrameInfo> frames) {
        if (s().launchedLines.isEmpty() || frames == null) {
            return frames;
        }
        List<DapModels.StackFrameInfo> out = new ArrayList<>(frames.size());
        for (DapModels.StackFrameInfo f : frames) {
            EditorBuffer b = f.file() == null ? null : ops.bufferForPath(f.file());
            LaunchedLines.Mapping map = lineMap(b);
            int line = map.changed() ? map.toCurrent(f.line()) : f.line();
            out.add(line >= 0 && line != f.line() ? f.atLine(line) : f);
        }
        return out;
    }

    /**
     * At a stop in a file edited since the session started, says the program still runs the code it was
     * started with — the console once per file, the status bar every time.
     */
    private void noteChangedSinceLaunch(DapModels.StackFrameInfo frame) {
        Path file = frame == null ? null : frame.file();
        EditorBuffer b = file == null ? null : ops.bufferForPath(file);
        if (file == null || b == null || !lineMap(b).changed()) {
            return;
        }
        String message = tr("status.debug.changedSinceLaunch", file.getFileName());
        if (s().changedNoticed.add(key(file))) {
            panel().appendNotice(message);
        }
        host.setStatus(message);
    }

    /**
     * The program now runs {@code file}'s current text — a hot code replace redefined its classes: breakpoint and
     * frame lines are mapped from it from now on, and its breakpoints are re-sent in that numbering.
     */
    void rebaselineLines(Path file) {
        EditorBuffer b = file == null ? null : ops.bufferForPath(file);
        if (b == null || b.getPath() == null || !dap().isActive()) {
            return; // a buffer found by its path always has one
        }
        s().launchedLines.rebaseline(file, b.getContent());
        DapModels.FileBreakpoints now = fileBreakpoints(b, b.getPath());
        if (!now.equals(s().sentBreakpoints.put(file, now))) {
            dap().updateBreakpoints(now);
        }
        showBreakpointStates(b);
    }

    /**
     * Every armed breakpoint sent to the adapter: open buffers' live breakpoints merged with
     * {@link #closedBreakpoints} (files with no open tab, re-anchored at session start). A closed file that
     * has since been opened is taken from its live buffer, not the cached snapshot.
     */
    private List<DapModels.FileBreakpoints> collectBreakpoints() {
        List<DapModels.FileBreakpoints> out = new ArrayList<>();
        Set<String> open = new HashSet<>();
        host.forEachBuffer(b -> {
            if (b.getPath() == null) {
                return;
            }
            open.add(key(b.getPath()));
            DapModels.FileBreakpoints fb = fileBreakpoints(b, b.getPath());
            s().sentBreakpoints.put(b.getPath(), fb);
            if (!fb.breakpoints().isEmpty()) {
                out.add(fb);
            }
        });
        for (DapModels.FileBreakpoints fb : closedBreakpoints) {
            if (fb.file() != null && !open.contains(key(fb.file())) && !breakpointsMuted) {
                out.add(fb);
            }
        }
        return out;
    }

    /**
     * Re-anchors the persisted breakpoints of files with no open tab (off the FX thread), caches them in
     * {@link #closedBreakpoints}, then runs {@code then} on the FX thread. Called before every session start
     * so the initial {@code setBreakpoints} includes closed files. The open-tab set + a copy of the map are
     * snapshotted on the FX thread; the file reads happen on a daemon thread.
     */
    private void withClosedBreakpoints(Runnable then) {
        Object token = new Object();
        DebugSession session = s();
        session.closedBreakpointsRead = token; // a newer start, or Stop, drops this one
        Set<String> openPaths = new HashSet<>();
        host.forEachBuffer(b -> {
            if (b.getPath() != null) {
                openPaths.add(key(b.getPath()));
            }
        });
        Map<String, List<Breakpoint>> map = new LinkedHashMap<>(ops.breakpointMap());
        Predicate<Breakpoint> sent = sentPredicate(session);
        Thread t = new Thread(
                () -> {
                    List<DapModels.FileBreakpoints> computed = ClosedFileBreakpoints.of(
                            map, openPaths, RemoteExec::runsFor, ClosedFileBreakpoints::readLinesOrNull, sent);
                    laterIn(session, () -> {
                        if (s().closedBreakpointsRead != token) {
                            return; // stopped, or replaced by another start, while the files were read
                        }
                        s().closedBreakpointsRead = null;
                        closedBreakpoints = computed;
                        then.run();
                    });
                },
                "debug-breakpoints");
        t.setDaemon(true);
        t.start();
    }

    /**
     * Which saved breakpoints {@code session} is told of now: all but the triggered ones whose trigger it has not hit.
     * Read on the FX thread; the answer may be asked off it.
     */
    private Predicate<Breakpoint> sentPredicate(DebugSession session) {
        Set<String> ids = triggered.ids();
        Set<String> hit = Set.copyOf(session.hitTriggers);
        return bp -> !TriggeredBreakpoints.waits(bp, ids, hit);
    }

    /** See {@link ClosedFileBreakpoints#of}. */
    static List<DapModels.FileBreakpoints> closedFileBreakpoints(
            Map<String, List<Breakpoint>> map,
            Set<String> openPaths,
            Predicate<Path> isLocal,
            Function<Path, @Nullable List<String>> readLines) {
        return ClosedFileBreakpoints.of(map, openPaths, isLocal, readLines);
    }

    // --- DAP event sink + panel actions ------------------------------------------------------------

    private DapManager.Listener dapListener() {
        return new DapManager.Listener() {
            @Override
            public void onState(DapManager.State state) {
                closeSmartStepPicker(state); // review E108
                if (state != DapManager.State.SUSPENDED) {
                    hideExceptionCard(); // Continue, a step, the session's end: the stop it described is over
                    s().stopException = null;
                }
                // INACTIVE with no panel built is what startup (configure, stop) reports: a panel built later
                // starts in that state by itself, so there is nothing to show and no reason to build it now.
                boolean show = s().panel != null || state != DapManager.State.INACTIVE;
                if (state == DapManager.State.STARTING || state == DapManager.State.INACTIVE) {
                    s().runtimeGeneration++; // a probe still out answers for the session that just ended
                    if (s().panel != null) {
                        panel().setRuntime(null);
                    }
                }
                if (state == DapManager.State.STARTING) {
                    if (!s().consoleStarted) {
                        panel().clearConsole(); // each launch starts with its own output
                    }
                    s().consoleStarted = false;
                }
                if (show) {
                    panel().setRestartBlocked(
                                    restartAvailable()
                                            ? null
                                            : tr(
                                                    s().relaunch == SUBPROCESS
                                                            ? "status.debug.cannotRestartSubprocess"
                                                            : "status.debug.cannotRestartAttached"));
                    panel().setState(state);
                }
                evaluateWindows.forEach(DebugEvaluateWindow::sessionChanged); // their results were that stop's
                if (state == DapManager.State.STARTING) {
                    s().recordedStops = 0; // the recording breakpoints' limit is per session
                    nameSession(s().sessionLabel); // ending the replaced session cleared it
                    refreshExceptionToggles(); // the session's language decides what Caught / Uncaught mean
                    s().sessionAnnounced = !s().announceStart;
                    s().announceStart = false;
                } else if ((state == DapManager.State.RUNNING || state == DapManager.State.SUSPENDED)
                        && !s().sessionAnnounced) {
                    // Once per session: replaces "Running the before-launch step…" or "Attaching the debugger on
                    // port …", which stood in the status bar while the program sat paused (review 2, A105).
                    s().sessionAnnounced = true;
                    host.setStatus(
                            s().sessionLabel.isEmpty()
                                    ? tr("status.debug.sessionStarted")
                                    : tr("status.debug.sessionStartedNamed", s().sessionLabel));
                }
                if (state != DapManager.State.SUSPENDED) {
                    s().shownFrame = null;
                    if (show) {
                        panel().setStopReason(null);
                    }
                }
                updateDebugStatus(state);
                forgetCodeReplace(state);
                primeCodeReplace(state);
                if (state == DapManager.State.STARTING || state == DapManager.State.INACTIVE) {
                    closeMainClassPicker(); // it chose for a launch that has been replaced or has ended (A106)
                }
                if (state == DapManager.State.INACTIVE || state == DapManager.State.STARTING) {
                    if (state == DapManager.State.STARTING) {
                        snapshotLaunchedLines(); // B1: what the program is built from (the files were saved)
                    } else {
                        s().launchedLines.clear();
                    }
                    s().sentBreakpoints.clear(); // the next session is told everything afresh
                    s().hitTriggers.clear(); // and its triggered breakpoints wait again
                    s().breakpointStatus.clear(); // and what the last one said about them no longer holds
                    s().evaluationFailures.clear();
                    s().reportedRejections.clear();
                    host.forEachBuffer(DebugCoordinator.this::showBreakpointStates);
                }
                if (state != DapManager.State.SUSPENDED) {
                    clearExecHighlight();
                    clearDebugEditorSurfaces(); // inline values + hover live only while suspended
                }
                if (state == DapManager.State.INACTIVE
                        && s().relaunch == ONE_SHOT_ATTACH
                        && !queuedAttaches.isEmpty()) {
                    later(queuedAttaches.poll()); // the next JVM of the same run is waiting
                } else if (state == DapManager.State.INACTIVE
                        && s().pendingAttachStop != null
                        && !beforeLaunchActive()) {
                    // A Debug Test run still waits for its next fork: Stop ends it.
                    panel().setPreparing(true, tr("debugpanel.state.waitingTest"));
                }
                boolean starting = state == DapManager.State.STARTING;
                debugLoading(starting);
                updateDebugAvailability(); // keep the window available during a session; hide it once it ends
                if (starting || state == DapManager.State.SUSPENDED) {
                    // A stop shows the window again if it was closed meanwhile (review C5) — without focus: the
                    // editor keeps it, and in the panel plain letters are debugger commands (review C2).
                    ops.openToolWindow();
                }
                if (state == DapManager.State.INACTIVE) {
                    stopChildren(); // the program ended: its subprocesses' sessions with it
                    DebugSession self = s();
                    if (self.parent != null && !shown()) {
                        later(() -> closeSession(self)); // an ended subprocess's tab goes, unless it is shown
                    }
                }
                refreshSessionTabs();
            }

            @Override
            public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> stopFrames) {
                s().stopSerial++;
                s().stopException = null; // a new stop: the last one's exception is not this one's
                hideExceptionCard();
                List<DapModels.StackFrameInfo> frames = currentLines(stopFrames);
                oldCodeHintFrom(stopFrames); // B209: the stack after a replace names the old-code frames
                panel().setStopReason(reason);
                panel().setStoppedThread(threadId); // Debug History keys this stop's frames by it
                // The frame that threw: its line is drawn red while it is the one shown (review C13).
                s().exceptionFrameId = "exception".equals(reason) && !frames.isEmpty()
                        ? frames.get(firstFrameWithSource(frames, DebugCoordinator.this::hasSource))
                                .id()
                        : -1;
                // Selects the first frame that has source to show → selectFrame highlights + loads vars. A step
                // that went into library code shows that code (B5): the user asked to go there.
                int shown = frameToShow(reason, frames, DebugCoordinator.this::hasSource);
                // A recording breakpoint: the stop is kept in Debug History and the program goes on, so the editor
                // is not taken to it (recordAndContinue).
                boolean recording = recordingHit(reason, frames);
                armTriggered(reason, frames); // a breakpoint others wait for: they stop from now on
                s().quietStop = recording ? s().stopSerial : -1;
                if (!recording && !shown()) {
                    focusSession(s()); // another session stopped: it is shown now, as in VS Code and IntelliJ
                }
                panel().setCallStack(frames, shown);
                if (recording) {
                    recordAndContinue(frames.getFirst());
                    return;
                }
                removeTemporaryBreakpointHit(reason, frames);
                if (shown > 0) {
                    // A JDK or library frame jdtls can show is "library code", not "no source" (review 2, B107).
                    host.setStatus(tr(
                            frames.get(0).sourceUri() != null
                                    ? "status.debug.stoppedInLibrary"
                                    : "status.debug.stoppedWithoutSource",
                            DebugPanelText.frameName(frames.get(0).name())));
                }
                dap().threads(list -> panel().setThreads(list, dap().currentThreadId()));
            }

            @Override
            public void onOutput(String text, String category) {
                panel().appendOutput(text, category);
            }

            @Override
            public void onProgramInput(boolean available) {
                panel().setProgramInput(available);
            }

            @Override
            public void onProgramExit(int code) {
                panel().appendNotice(tr("run.exited", code));
                panel().setExitCode(code);
                // What the status bar says now: an earlier "Stopped on <exception>" no longer holds (review C19).
                host.setStatus(tr("run.exited", code));
            }

            @Override
            public void onChildSession(java.util.Map<String, Object> configuration) {
                openChildSession(configuration);
            }

            @Override
            public void onDebuggeeRuntime(
                    List<String> runtime, @Nullable Path where, java.util.Map<String, String> env) {
                long generation = s().runtimeGeneration;
                com.editora.run.RuntimeProbe.probe(runtime, where, env, version -> {
                    if (generation == s().runtimeGeneration && s().panel != null) {
                        panel().setRuntime(version);
                    }
                });
            }

            @Override
            public void onError(@Nullable String message) {
                if (message != null && com.editora.dap.DapErrors.multiLine(message)) {
                    // A compiler's or adapter's report does not fit the one-line status bar: the whole of it
                    // goes to the Debug console, the status bar names its first line and says where the rest is.
                    panel().appendOutput(message.endsWith("\n") ? message : message + "\n", "stderr");
                    host.setStatus(tr(
                            "status.debug.error",
                            tr("debug.error.detailsInConsole", com.editora.dap.DapErrors.firstLine(message))));
                    return;
                }
                host.setStatus(tr("status.debug.error", message));
            }

            @Override
            public void onBreakpointStatus(Path file, List<DapModels.BreakpointStatus> statuses, boolean whole) {
                breakpointStatusChanged(file, statuses, whole);
            }

            @Override
            public void onNotice(String message, boolean error) {
                adapterNotice(message, error);
            }

            @Override
            public void onThreadsChanged() {
                if (s().panel != null) {
                    s().panel.threadsChanged();
                }
            }

            @Override
            public void onExceptionInfo(int threadId, DapModels.ExceptionInfo info) {
                // Which exception: the one thing an exception stop has to say, and the stop event does not.
                panel().setStoppedException(DebugFeedback.exceptionSummary(info, true));
                panel().setExceptionInfo(info);
                s().stopException = info;
                showExceptionCard(false); // under the line that threw, when it is the one shown
                host.setStatus(tr("status.debug.stoppedOnException", DebugFeedback.exceptionSummary(info, false)));
            }

            @Override
            public void onAdapterLost() {
                String message = tr("status.debug.adapterLost");
                panel().appendNotice(message);
                host.setStatus(message);
            }

            @Override
            public void onStatus(String message) {
                host.setStatus(message);
            }

            @Override
            public void onOutputTrailing() {
                panel().showOutputTrailing(); // choice 27: no longer "Running" while the last output arrives
            }

            @Override
            public void onCompileFailed() { // javac refused it: the header says so, as for a failed build (A201)
                panel().setLaunchRefused(tr("debugpanel.state.buildFailed"));
            }
        };
    }

    /** The status bar's debug spinner, for the session shown. */
    private void debugLoading(boolean on) {
        if (shown()) {
            ops.setStatusDebugLoading(on);
        }
    }

    private void updateDebugStatus(DapManager.State state) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        switch (state) {
            case INACTIVE, TERMINATED -> ops.setStatusDebug(null);
            case STARTING -> ops.setStatusDebug(tr("debug.state.starting"));
            case RUNNING -> ops.setStatusDebug(tr("debug.state.running"));
            case SUSPENDED -> ops.setStatusDebug(tr("debug.state.suspended"));
        }
    }

    private DebugPanel.Actions debugActions() {
        return new DebugPanel.Actions() {
            @Override
            public void start() {
                debugStart(); // start a session when idle, or continue when suspended
            }

            @Override
            public List<javafx.scene.control.MenuItem> stackMenuItems(
                    DapModels.StackFrameInfo frame, List<DapModels.StackFrameInfo> frames) {
                return DebugCoordinator.this.stackMenuItems(frame, frames);
            }

            @Override
            public void pause() {
                dap().pause();
            }

            @Override
            public void runToCursor() {
                debugRunToCursor();
            }

            @Override
            public void threadStatuses(Consumer<List<DapModels.ThreadStatus>> cb) {
                dap().threadStatuses(cb);
            }

            @Override
            public void pauseThread(int threadId) {
                dap().pauseThread(threadId);
            }

            @Override
            public void resumeThread(int threadId) {
                dap().resumeThread(threadId);
            }

            @Override
            public void showThread(DapModels.ThreadStatus thread) {
                if (dap().state() == DapManager.State.SUSPENDED) {
                    panel().showThread(thread.id()); // as if picked in the dropdown
                    selectThread(thread.id());
                } else {
                    dap().showSuspendedThread(thread.id(), thread.reason());
                }
            }

            @Override
            public void selectThread(int threadId) {
                int previous = dap().currentThreadId();
                dap().selectThread(threadId, threadFrames -> {
                    List<DapModels.StackFrameInfo> frames = currentLines(threadFrames);
                    if (!frames.isEmpty() || threadId == previous) {
                        panel().setCallStack(frames, firstFrameWithSource(frames, DebugCoordinator.this::hasSource));
                        return;
                    }
                    // A thread that is not suspended has no stack to inspect. Stay on the stopped one: showing
                    // an empty stack beside the other thread's variables and execution line — with Step now
                    // aimed at the running thread — said nothing about why.
                    host.setStatus(tr("status.debug.threadRunning"));
                    panel().showThread(previous);
                    dap().selectThread(previous, back -> {
                        if (back.isEmpty()) { // it has been resumed meanwhile: nothing is stopped to show
                            panel().setCallStack(back, 0);
                            panel().setScopes(List.of());
                            clearExecHighlight();
                            clearDebugEditorSurfaces();
                        }
                    });
                });
            }

            @Override
            public void stepOver() {
                DebugCoordinator.this.stepOver();
            }

            @Override
            public void stepInto() {
                DebugCoordinator.this.stepInto();
            }

            @Override
            public boolean canViewMemory() {
                return dap().supportsReadMemory();
            }

            @Override
            public void viewMemory(String expression) {
                ops.openToolWindow();
                panel().memoryView().setAddressText(expression);
                panel().showArea(DebugPanel.AREA_MEMORY);
                memoryGo(expression);
            }

            @Override
            public void stepOut() {
                dap().stepOut();
            }

            @Override
            public void stop() {
                DebugCoordinator.this.stop();
            }

            @Override
            public void restart() {
                DebugCoordinator.this.restart();
            }

            @Override
            public void selectFrame(DapModels.StackFrameInfo frame) {
                if (dap().state() != DapManager.State.SUSPENDED) {
                    return; // a frame of the previous stop, clicked while the program runs: there is no "here"
                }
                s().debugFrameId = frame.id(); // the hover evaluator's frame context
                s().shownFrame = frame;
                if (s().quietStop == s().stopSerial) {
                    return; // a recording breakpoint's stop: kept, not shown (recordAndContinue)
                }
                highlightFrame(frame);
                noteChangedSinceLaunch(frame);
                dap().scopes(frame.id(), scopes -> {
                    panel().setScopes(scopes);
                    applyInlineValues(frame, scopes);
                });
                pinnedValues.refresh(frame.id()); // every pinned value, evaluated in this frame
                refreshLowLevelViews(); // the Disassembly view follows the frame; Memory is read again
            }

            @Override
            public void loadChildren(int ref, Consumer<List<DapModels.VariableInfo>> cb) {
                dap().variables(ref, cb);
            }

            @Override
            public void loadChildrenPage(
                    int ref, boolean indexed, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                dap().variables(ref, indexed ? "indexed" : "named", start, count, cb);
            }

            @Override
            public void evaluate(String expr, int frameId, Consumer<com.editora.dap.ConsoleEvaluation> cb) {
                dap().evaluate(expr, frameId, "repl", cb);
            }

            @Override
            public void evaluateWatch(String expr, int frameId, Consumer<DapModels.EvalResult> cb) {
                dap().evaluateFull(expr, frameId, "watch", cb);
            }

            @Override
            public void evaluateCode(String expr, int frameId, Consumer<DapModels.EvalResult> cb) {
                dap().evaluateFull(expr, frameId, "repl", cb);
            }

            @Override
            public void complete(String text, int caret, int frameId, Consumer<List<DapModels.CompletionTarget>> cb) {
                dap().completions(frameId, text, caret, cb);
            }

            @Override
            public void sendInput(String line) {
                dap().sendProgramInput(line);
            }

            @Override
            public void endInput() {
                endProgramInput();
            }

            @Override
            public void toggleExceptions(boolean caught) {
                toggleExceptionKind(caught);
            }

            @Override
            public void setVariable(int parentRef, String name, String value, Consumer<String> cb) {
                dap().setVariable(parentRef, name, value, cb);
            }

            @Override
            public boolean canWatchField() {
                return memberBreakpoints.canWatchFields();
            }

            @Override
            public void watchField(int parentRef, String name) {
                memberBreakpoints.watchVariable(parentRef, name);
            }

            @Override
            public void setVariable(
                    int parentRef, String name, String value, String assignTo, int frameId, Consumer<String> cb) {
                dap().setVariable(parentRef, name, value, assignTo, frameId, cb);
            }

            @Override
            public void loadValue(DapModels.VariableInfo row, Consumer<DapModels.VariableInfo> cb) {
                dap().loadValue(row, cb);
            }

            @Override
            public boolean hexValues() {
                return dap().hexValues();
            }

            @Override
            public void setHexValues(boolean on) {
                forEachSession(
                        () -> { // a setting of the window's: every session shows numbers this way
                            dap().setHexValues(on);
                            refreshValues();
                        });
                host.setStatus(tr(on ? "status.debug.hexOn" : "status.debug.hexOff"));
            }

            @Override
            public void showHistoryFrame(DebugHistory.Stop stop, int frame) {
                DebugCoordinator.this.showHistoryFrame(stop, frame);
            }

            @Override
            public void leaveHistory() {
                DebugCoordinator.this.leaveHistory();
            }

            @Override
            public void pickValueHistory(
                    String title, List<DebugHistory.Seen> seen, java.util.function.IntConsumer go) {
                DebugCoordinator.this.pickValueHistory(title, seen, go);
            }

            @Override
            public boolean refreshValues() {
                return refreshStopValues();
            }
        };
    }

    // --- editor surfaces while suspended (inline values / hover / execution line) ------------------

    /** Fetches the selected frame's local variables and paints them as inline values in the frame's
     *  file buffer; also arms the hover value tooltip there (IntelliJ's editor surfaces). */
    private void applyInlineValues(DapModels.StackFrameInfo frame, List<DapModels.ScopeInfo> scopes) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        if (frame == null || frame.file() == null || scopes.isEmpty()) {
            return;
        }
        DapModels.ScopeInfo locals =
                scopes.stream().filter(s -> !s.expensive()).findFirst().orElse(null);
        if (locals == null) {
            return;
        }
        dap().variables(locals.variablesReference(), vars -> {
            if (dap().state() != DapManager.State.SUSPENDED || !shown()) {
                return; // resumed, or another session shown, while the request was in flight
            }
            Map<String, String> values = new LinkedHashMap<>();
            for (DapModels.VariableInfo v : vars) {
                if (values.size() == MAX_INLINE_VALUES) {
                    break;
                }
                values.put(v.name(), DebugValues.display(v.value())); // a function: its first line (review D9)
            }
            if (s().panel != null && s().panel.viewing() != null) {
                return; // an earlier stop is shown: its own values are painted
            }
            Path file = frame.file();
            if (s().panel != null && file != null) {
                s().panel.recordInlineValues(frame, file, frame.line(), values);
            }
            EditorBuffer b = ops.bufferForPath(frame.file());
            if (b == null) {
                return;
            }
            if (debugValuesBuffer != null && debugValuesBuffer != b) {
                debugValuesBuffer.setInlineValues(null, -1); // frame moved to another file
                debugValuesBuffer.setDebugHoverActive(false);
            }
            debugValuesBuffer = b;
            // Region-relative in a narrowed buffer, like the execution line.
            b.setInlineValues(values, frame.line() - b.getBreakpointManager().regionFirstLine());
            b.setDebugHoverActive(true);
        });
    }

    // --- Debug History: an earlier stop in the editor ---------------------------------------------

    /**
     * Shows frame {@code index} of earlier stop {@code stop} in the editor: its line marked grey, the inline values and
     * pinned values it showed then. Nothing is asked of the adapter, and hovering evaluates nothing.
     */
    private void showHistoryFrame(DebugHistory.Stop stop, int index) {
        DapModels.StackFrameInfo frame = stop.frames.get(index);
        clearExecHighlight();
        if (debugValuesBuffer != null) {
            debugValuesBuffer.setInlineValues(null, -1);
            debugValuesBuffer.setDebugHoverActive(false);
            debugValuesBuffer = null;
        }
        pinnedValues.showRecorded(stop.pins());
        if (frame.file() == null || !hasSource(frame)) {
            host.setStatus(tr("status.debug.noSource", frame.name()));
            return;
        }
        Node keepFocus = debugPanelFocusOwner();
        ops.openPath(frame.file());
        EditorBuffer b = ops.bufferForPath(frame.file());
        if (keepFocus != null) {
            later(keepFocus::requestFocus);
        }
        if (b == null) {
            return;
        }
        execHighlightBuffer = b;
        ops.afterBufferLoad(b, () -> {
            if (execHighlightBuffer != b || s().panel == null || s().panel.viewing() != stop) {
                return; // another stop or frame, or back to the live one, meanwhile
            }
            int first = b.getBreakpointManager().regionFirstLine();
            b.setExecutionLine(frame.line() - first);
            b.setExecutionLineHistory(true);
            DebugHistory.Inline inline = stop.inline(index);
            if (inline != null) {
                b.setInlineValues(inline.values(), inline.line() - first);
                debugValuesBuffer = b;
            }
        });
    }

    /** Back from an earlier stop: the live stop's line, inline and pinned values; nothing once the session ended. */
    private void leaveHistory() {
        clearExecHighlight();
        pinnedValues.clear();
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (dap().state() == DapManager.State.SUSPENDED && frame != null) {
            highlightFrame(frame);
            dap().scopes(frame.id(), scopes -> applyInlineValues(frame, scopes));
            pinnedValues.refresh(frame.id());
        } else {
            clearDebugEditorSurfaces();
        }
    }

    /** Show Value History: a value's stops, newest first; a pick goes back to that stop. */
    private void pickValueHistory(String title, List<DebugHistory.Seen> seen, java.util.function.IntConsumer go) {
        if (seen.isEmpty()) {
            host.setStatus(tr("status.debug.noValueHistory"));
            return;
        }
        List<DebugHistory.Seen> newestFirst = new java.util.ArrayList<>(seen);
        java.util.Collections.reverse(newestFirst);
        QuickOpen<DebugHistory.Seen> picker = new QuickOpen<>(
                title,
                tr("debugpanel.history.valueHistoryPrompt"),
                () -> newestFirst,
                v -> tr("debugpanel.history.seen", v.stop(), DebugValues.display(v.value())),
                v -> v.time().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))
                        + (v.changed() ? " · " + tr("debugpanel.history.changed") : ""),
                v -> {
                    if (v != null) {
                        go.accept(v.stop());
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Debug: Back (History). */
    void historyBack() {
        if (s().panel != null) {
            ops.openToolWindow();
            s().panel.historyBack();
        }
    }

    /** Debug: Forward (History). */
    void historyForward() {
        if (s().panel != null) {
            s().panel.historyForward();
        }
    }

    /** Debug: Show History…: every stop kept, newest first; a pick goes back to it. */
    void showHistory() {
        List<DebugHistory.Stop> stops =
                s().panel == null ? List.of() : s().panel.history().stops();
        if (stops.isEmpty()) {
            host.setStatus(tr("status.debug.noHistory"));
            return;
        }
        List<DebugHistory.Stop> newestFirst = new java.util.ArrayList<>(stops);
        java.util.Collections.reverse(newestFirst);
        QuickOpen<DebugHistory.Stop> picker = new QuickOpen<>(
                tr("debugpanel.history.title"),
                tr("debugpanel.history.prompt"),
                () -> newestFirst,
                DebugPanelText::historyEntry,
                st -> st.time.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss")),
                st -> {
                    if (st != null && s().panel != null) {
                        ops.openToolWindow();
                        s().panel.viewStop(st.number, -1);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    // --- recording breakpoints: record the stop, then go on ------------------------------------------

    /** A session records at most this many stops; the next hit stops as a plain breakpoint does, and says why. */
    static final int MAX_RECORDED_STOPS = 500;

    /**
     * Whether the program stopped ({@code reason}) on a recording breakpoint at the top frame's line, as the adapter
     * was told the breakpoints (open files' and closed ones'), and can record it: Debug History keeps stops and the
     * session has not reached {@link #MAX_RECORDED_STOPS}. Says why when it stops instead.
     */
    private boolean recordingHit(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty()) {
            return false;
        }
        DapModels.StackFrameInfo top = frames.getFirst();
        Path file = top.file();
        if (file == null || !recordingBreakpointAt(file, top.line())) {
            return false;
        }
        if (panel().history().capacity() == 0) {
            host.setStatus(tr("status.debug.recordingHistoryOff"));
            return false;
        }
        if (s().recordedStops >= MAX_RECORDED_STOPS) {
            host.setStatus(tr("status.debug.recordingLimit", MAX_RECORDED_STOPS));
            return false;
        }
        return true;
    }

    /** Whether a recording breakpoint was sent for 0-based line {@code line} of {@code file}. */
    private boolean recordingBreakpointAt(Path file, int line) {
        String wanted = key(file);
        List<DapModels.FileBreakpoints> sent =
                new ArrayList<>(s().sentBreakpoints.values());
        sent.addAll(closedBreakpoints);
        for (DapModels.FileBreakpoints fb : sent) {
            if (fb.file() != null && key(fb.file()).equals(wanted)) {
                for (DapModels.LineBreakpoint lb : fb.breakpoints()) {
                    if (lb.line() == line && lb.recording()) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * A recording breakpoint's stop: the top frame's local values, its inline values and the watches are kept in Debug
     * History (the stop itself is already recorded with its call stack), then the program goes on. A stop the user
     * acts on first (a step, Stop) or another stop meanwhile is left as it is.
     */
    private void recordAndContinue(DapModels.StackFrameInfo top) {
        s().recordedStops++;
        int serial = s().stopSerial;
        DebugPanel panel = panel();
        DebugHistory.Stop stop = panel.history().last();
        Runnable goOn = () -> {
            if (serial != s().stopSerial || dap().state() != DapManager.State.SUSPENDED) {
                return;
            }
            if (stop != null) {
                panel.appendNotice(tr("debug.console.recorded", stop.number, DebugPanelText.historyWhere(stop)));
            }
            dap().resume();
        };
        dap().scopes(top.id(), scopes -> {
            DapModels.ScopeInfo locals =
                    scopes.stream().filter(sc -> !sc.expensive()).findFirst().orElse(null);
            if (locals == null || serial != s().stopSerial) {
                recordWatches(top, serial, goOn);
                return;
            }
            dap().variables(locals.variablesReference(), vars -> {
                if (serial != s().stopSerial) {
                    return;
                }
                DebugHistory history = panel.history();
                Map<String, String> inline = new LinkedHashMap<>();
                for (DapModels.VariableInfo v : vars) {
                    history.record(
                            0,
                            List.of(locals.name(), v.name()),
                            v.value(),
                            v.type() == null ? "" : v.type(),
                            v.variablesReference() > 0);
                    if (inline.size() < MAX_INLINE_VALUES) {
                        inline.put(v.name(), DebugValues.display(v.value()));
                    }
                }
                Path file = top.file();
                if (file != null) {
                    history.recordInline(0, file, top.line(), inline);
                }
                recordWatches(top, serial, goOn);
            });
        });
    }

    /** Evaluates the watches in {@code top} one after another into the newest stop, then runs {@code then}. */
    private void recordWatches(DapModels.StackFrameInfo top, int serial, Runnable then) {
        List<String> watches = new ArrayList<>(panel().getWatches());
        recordWatch(top, serial, watches, 0, then);
    }

    private void recordWatch(DapModels.StackFrameInfo top, int serial, List<String> watches, int index, Runnable then) {
        if (index >= watches.size() || serial != s().stopSerial) {
            then.run();
            return;
        }
        String expr = watches.get(index);
        dap().evaluateFull(expr, top.id(), "watch", r -> {
            if (serial == s().stopSerial && r != null && !r.failed()) {
                panel().history()
                        .record(
                                0,
                                List.of(tr("debugpanel.watches"), expr),
                                r.result(),
                                r.type() == null ? "" : r.type(),
                                r.variablesReference() > 0);
            }
            recordWatch(top, serial, watches, index + 1, then);
        });
    }

    private void clearDebugEditorSurfaces() {
        s().debugFrameId = -1;
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        pinnedValues.clear();
        if (debugValuesBuffer != null) {
            debugValuesBuffer.setInlineValues(null, -1);
            debugValuesBuffer.setDebugHoverActive(false);
            debugValuesBuffer = null;
        }
    }

    /**
     * Whether {@code frame}'s source can be shown: a file with an open tab, or one that exists on disk. A frame
     * in the JDK or a dependency names a {@code jdt:} URI, a jar entry, {@code <frozen importlib>} or a path
     * from the machine that built it — none of which can be opened as a tab.
     */
    private boolean hasSource(DapModels.StackFrameInfo frame) {
        if (frame == null || frame.file() == null) {
            return false;
        }
        try {
            return ops.bufferForPath(frame.file()) != null || Files.isRegularFile(frame.file());
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** How many frames from the top a stop looks for one with source before settling for the top frame. */
    private static final int MAX_SOURCE_SEARCH = 64;

    /**
     * Pure: the frame a stop selects. After a step into a JDK or library class whose text jdtls can supply (a
     * {@code jdt://} source), that frame: the user asked to go there, and showing the caller instead made Step
     * Into look as if it did nothing (B5). Any other stop: {@link #firstFrameWithSource}.
     */
    static int frameToShow(
            String reason, List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        if ("step".equals(reason) && !frames.isEmpty() && frames.get(0).sourceUri() != null) {
            return 0;
        }
        return firstFrameWithSource(frames, has);
    }

    /**
     * Pure: the frame a new stop selects — the topmost one whose source can be shown, so pausing a thread that
     * sits in {@code Thread.sleep} lands on the caller's line rather than nowhere; the top frame when none can.
     */
    static int firstFrameWithSource(List<DapModels.StackFrameInfo> frames, Predicate<DapModels.StackFrameInfo> has) {
        for (int i = 0; i < frames.size() && i < MAX_SOURCE_SEARCH; i++) {
            if (has.test(frames.get(i))) {
                return i;
            }
        }
        return 0;
    }

    // --- launch.json and the user's own adapters ------------------------------------------------------

    /** The user's own adapters ({@code debug-adapters.json}), by type, with what F5 sends each. */
    private java.util.Map<String, com.editora.dap.CustomAdapters.Adapter> customAdapters = java.util.Map.of();

    /** The launch.json configuration last started, by launch.json: the picker offers it first. */
    private final java.util.Map<Path, String> lastConfiguration = new java.util.HashMap<>();

    /** The user's {@code debug-adapters.json}: the config folder's (null without one). */
    private @Nullable Path userAdaptersFile() {
        Path config = host.configDir();
        return config == null ? null : config.resolve("debug-adapters.json");
    }

    /**
     * Reads the user's own adapters — the config folder's {@code debug-adapters.json}, then a trusted project's
     * {@code .editora/debug-adapters.json}, whose adapters replace those of the same type — and looks for each one's
     * program. One that cannot be read is said in the status bar.
     */
    private void loadCustomAdapters(boolean on) {
        List<List<com.editora.dap.CustomAdapters.Adapter>> files = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<Path> paths = new ArrayList<>();
        Path user = userAdaptersFile();
        if (user != null) {
            paths.add(user);
        }
        Path project = ops.projectRoot();
        if (project != null && host.projectScope().trusted()) {
            paths.add(project.resolve(".editora").resolve("debug-adapters.json"));
        }
        for (Path p : paths) {
            if (!java.nio.file.Files.isRegularFile(p)) {
                continue;
            }
            try {
                var parsed = com.editora.dap.CustomAdapters.parse(java.nio.file.Files.readString(p));
                files.add(parsed.adapters());
                parsed.problems().forEach(problem -> problems.add(p.getFileName() + ": " + problem));
            } catch (java.io.IOException e) {
                problems.add(p + ": " + e.getMessage());
            }
        }
        List<com.editora.dap.CustomAdapters.Adapter> adapters = com.editora.dap.CustomAdapters.merge(files);
        java.util.Map<String, com.editora.dap.CustomAdapters.Adapter> byType = new java.util.LinkedHashMap<>();
        adapters.forEach(a -> byType.put(a.profile().id(), a));
        customAdapters = byType;
        com.editora.dap.DebugLanguages.setCustom(adapters.stream()
                .map(com.editora.dap.CustomAdapters.Adapter::profile)
                .toList());
        for (var a : adapters) {
            dap().configureExternal(a.profile(), on, "", found -> applyGating());
        }
        if (!problems.isEmpty()) {
            host.setStatus(tr("status.debug.customAdaptersInvalid", String.join("; ", problems)));
        }
    }

    /** Debug: Edit Debug Adapters: the user's {@code debug-adapters.json}, written with an example the first time. */
    void editCustomAdapters() {
        Path file = userAdaptersFile();
        if (file == null) {
            return;
        }
        try {
            if (!java.nio.file.Files.exists(file)) {
                java.nio.file.Files.createDirectories(file.getParent());
                java.nio.file.Files.writeString(file, com.editora.dap.CustomAdapters.EXAMPLE);
            }
        } catch (java.io.IOException e) {
            host.setStatus(tr("debug.error.startFailed", e.getMessage()));
            return;
        }
        ops.openPath(file);
    }

    /** F5 on a file of one of the user's own adapters' languages: its {@code launch} template, variables put in. */
    private void debugCustomFile(EditorBuffer b, Path path, com.editora.dap.DebugLanguage profile, List<String> args) {
        com.editora.dap.CustomAdapters.Adapter adapter = customAdapters.get(profile.id());
        if (adapter == null || adapter.launch().isEmpty()) {
            host.setStatus(tr("status.debug.customNoLaunch", profile.displayName()));
            return;
        }
        Path root = ops.projectRoot();
        Path workspace = root != null && path.toAbsolutePath().startsWith(root.toAbsolutePath())
                ? root
                : java.util.Objects.requireNonNullElse(path.toAbsolutePath().getParent(), path);
        java.util.Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("type", profile.id());
        request.put("request", "launch");
        try {
            Object body = com.editora.dap.LaunchJson.substitute(
                    adapter.launch(),
                    launchContext(workspace, b, java.util.Map.of()),
                    java.util.Map.of(),
                    new java.util.HashMap<>());
            if (body instanceof java.util.Map<?, ?> m) {
                m.forEach((k, v) -> request.put(String.valueOf(k), v));
            }
        } catch (com.editora.dap.LaunchJson.LaunchJsonException e) {
            host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
            return;
        } catch (com.editora.dap.LaunchJson.Cancelled e) {
            return;
        }
        if (!request.containsKey("args") && !args.isEmpty()) {
            request.put("args", args);
        }
        startExternalSession(path, profile, request, String.valueOf(path.getFileName()));
    }

    /** What VS Code's variables stand for: {@code workspace}, the active file and its caret, the answered inputs. */
    private com.editora.dap.LaunchJson.Context launchContext(
            Path workspace, @Nullable EditorBuffer b, java.util.Map<String, String> answers) {
        Path file = b == null ? null : b.getPath();
        int line = b == null ? 1 : caretLine(b) + 1;
        String selected = b == null ? "" : b.getFocusedArea().getSelectedText();
        return new com.editora.dap.LaunchJson.Context(
                workspace,
                file,
                line,
                selected,
                System.getenv(),
                Path.of(System.getProperty("user.home", "")),
                in -> answers.get(in.id()));
    }

    /** Starts {@code request} on external adapter {@code profile}, anchored at {@code anchor}; Restart repeats it. */
    private void startExternalSession(
            Path anchor, com.editora.dap.DebugLanguage profile, java.util.Map<String, Object> request, String label) {
        ops.openToolWindow();
        nameSession(label);
        Runnable start = () -> withClosedBreakpoints(() -> dap().startExternal(anchor, profile, request));
        s().relaunch = start;
        start.run();
    }

    /**
     * Debug: Start Configuration: the configurations and compounds of the project's {@code .vscode/launch.json} (or the
     * nearest one above the active file), picked from a list — the one started last first — and started as VS Code
     * starts them: beside the running session, when there is one.
     */
    void startLaunchConfiguration() {
        EditorBuffer b = host.activeBuffer();
        LaunchFile launch = readLaunchJson(b);
        if (launch == null) {
            return; // said
        }
        if (launch.parsed().configurations().isEmpty()
                && launch.parsed().compounds().isEmpty()) {
            host.setStatus(tr("status.debug.noConfigurations", launch.path()));
            return;
        }
        List<LaunchChoice> choices = new ArrayList<>();
        launch.parsed().configurations().forEach(c -> choices.add(new LaunchChoice(c, null)));
        launch.parsed().compounds().forEach(c -> choices.add(new LaunchChoice(null, c)));
        String last = lastConfiguration.get(launch.path());
        choices.sort(java.util.Comparator.comparing(c -> !c.name().equals(last)));
        QuickOpen<LaunchChoice> picker = new QuickOpen<>(
                tr("debug.launchJson.title"),
                tr("debug.launchJson.prompt"),
                () -> choices,
                LaunchChoice::name,
                LaunchChoice::detail,
                c -> {
                    if (c != null) {
                        lastConfiguration.put(launch.path(), c.name());
                        startLaunchChoice(launch, c, b);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** A project's launch.json, read: where it is, the folder it is for, what it holds. */
    record LaunchFile(Path path, Path workspace, com.editora.dap.LaunchJson.File parsed) {}

    /** The launch.json for the active file's project, read; null after saying why there is none to use. */
    private @Nullable LaunchFile readLaunchJson(@Nullable EditorBuffer b) {
        Path file = b == null ? null : b.getPath();
        Path launchJson = com.editora.dap.LaunchJson.find(ops.projectRoot(), file);
        if (launchJson == null) {
            host.setStatus(tr("status.debug.noLaunchJson"));
            return null;
        }
        com.editora.dap.LaunchJson.File parsed;
        try {
            parsed = com.editora.dap.LaunchJson.parse(java.nio.file.Files.readString(launchJson));
        } catch (java.io.IOException | com.editora.dap.LaunchJson.LaunchJsonException e) {
            host.setStatus(tr("status.debug.launchJsonInvalid", launchJson, e.getMessage()));
            return null;
        }
        Path vscode = launchJson.toAbsolutePath().getParent();
        Path workspace = vscode == null || vscode.getParent() == null ? launchJson : vscode.getParent();
        return new LaunchFile(launchJson, workspace, parsed);
    }

    /** An entry of Debug: Start Configuration: one of launch.json's configurations, or one of its compounds. */
    record LaunchChoice(
            com.editora.dap.LaunchJson.@Nullable Configuration configuration,
            com.editora.dap.LaunchJson.@Nullable Compound compound) {

        String name() {
            com.editora.dap.LaunchJson.Configuration c = configuration;
            com.editora.dap.LaunchJson.Compound group = compound;
            return c != null ? c.name() : group != null ? group.name() : "";
        }

        String detail() {
            com.editora.dap.LaunchJson.Configuration c = configuration;
            com.editora.dap.LaunchJson.Compound group = compound;
            return c != null
                    ? c.type() + " · " + c.request()
                    : tr(
                            "debug.compound.detail",
                            group == null ? 0 : group.configurations().size());
        }
    }

    /** Starts {@code choice} of {@code launch}: a configuration, or every configuration of a compound. */
    private void startLaunchChoice(LaunchFile launch, LaunchChoice choice, @Nullable EditorBuffer b) {
        com.editora.dap.LaunchJson.Configuration c = choice.configuration();
        com.editora.dap.LaunchJson.Compound group = choice.compound();
        if (c != null) {
            runLaunchConfiguration(launch.path(), launch.workspace(), launch.parsed(), c, b);
        } else if (group != null) {
            runLaunchCompound(launch, group, b);
        }
    }

    /**
     * The session a launch asked for now goes into: the one being handled when it is free, else a new one beside it,
     * shown from now on — a configuration started while another session runs is debugged next to it, as in VS Code.
     */
    private DebugSession sessionForLaunch() {
        return sessionLive() ? openSession() : s();
    }

    /**
     * Starts launch.json compound {@code group}: asks the inputs its configurations use (each once), runs its
     * {@code preLaunchTask}, then starts each configuration in a session of its own, in the order listed — each with
     * its own {@code preLaunchTask}. Stopping one stops them all when the compound says {@code stopAll}.
     */
    void runLaunchCompound(LaunchFile launch, com.editora.dap.LaunchJson.Compound group, @Nullable EditorBuffer b) {
        List<com.editora.dap.LaunchJson.Configuration> members = new ArrayList<>();
        for (String name : group.configurations()) {
            com.editora.dap.LaunchJson.Configuration c = launch.parsed().configuration(name);
            if (c == null) {
                host.setStatus(tr("status.debug.compoundMissing", group.name(), name));
                return;
            }
            members.add(c);
        }
        if (members.isEmpty()) {
            host.setStatus(tr("status.debug.compoundEmpty", group.name()));
            return;
        }
        String os = com.editora.dap.LaunchJson.osKey(System.getProperty("os.name", ""));
        List<java.util.Map<String, Object>> bodies = members.stream()
                .map(c -> com.editora.dap.LaunchJson.forPlatform(c.body(), os))
                .toList();
        List<String> ids = bodies.stream()
                .flatMap(body -> com.editora.dap.LaunchJson.inputsUsed(body).stream())
                .distinct()
                .toList();
        askInputs(launch.parsed().inputs(), ids, 0, new java.util.LinkedHashMap<>(), answers -> {
            DebugCompound compound = new DebugCompound(group.name(), group.stopAll());
            DebugSession first = sessionForLaunch();
            Runnable startAll = () -> {
                for (int i = 0; i < members.size(); i++) {
                    DebugSession target = i == 0 ? first : openSession();
                    String name = members.get(i).name();
                    java.util.Map<String, Object> body = bodies.get(i);
                    compound.join(target);
                    inSession(target, () -> resolveAndRun(launch.path(), launch.workspace(), name, body, answers, b));
                }
            };
            if (group.preLaunchTask().isBlank()) {
                startAll.run();
                return;
            }
            inSession(first, () -> {
                List<String> argv = preLaunchTask(launch.path(), launch.workspace(), group.preLaunchTask(), b, answers);
                if (argv == null) {
                    return; // said
                }
                nameSession(group.name());
                beforeLaunch()
                        .run(
                                host,
                                new BeforeLaunchStep.Command(group.preLaunchTask(), argv, "status.run.preLaunchTask"),
                                launch.workspace(),
                                java.util.Map.of(),
                                beforeLaunchConsole(),
                                () -> later(startAll)); // once the step has ended: its service is free again
            });
        });
    }

    /**
     * Debugs compound {@code compound} of saved run configurations: each in a session of its own, in the order listed,
     * as Debug ▸ Run Configuration debugs one. Says which configuration is missing, and starts none, when one is.
     */
    void debugRunCompound(com.editora.config.RunCompound compound) {
        List<com.editora.config.RunConfiguration> saved = ops.runConfigurations();
        List<com.editora.config.RunConfiguration> members = new ArrayList<>();
        for (String name : compound.configurations()) {
            com.editora.config.RunConfiguration cfg = saved.stream()
                    .filter(c -> c.name().equals(name))
                    .findFirst()
                    .orElse(null);
            if (cfg == null) {
                host.setStatus(tr("status.debug.compoundMissing", compound.name(), name));
                return;
            }
            members.add(cfg);
        }
        if (members.isEmpty()) {
            host.setStatus(tr("status.debug.compoundEmpty", compound.name()));
            return;
        }
        DebugCompound group = new DebugCompound(compound.name(), compound.stopAll());
        for (int i = 0; i < members.size(); i++) {
            DebugSession target = i == 0 ? sessionForLaunch() : openSession();
            com.editora.config.RunConfiguration cfg = members.get(i);
            group.join(target);
            inSession(target, () -> debugConfig(cfg));
        }
    }

    /**
     * Debug: Start Compound — the project's compounds (its own and its launch.json's), picked from a list and debugged
     * together; the last entry makes a new one.
     */
    void startCompound() {
        EditorBuffer b = host.activeBuffer();
        Path launchJson = com.editora.dap.LaunchJson.find(ops.projectRoot(), b == null ? null : b.getPath());
        LaunchFile launch = launchJson == null ? null : readLaunchJson(b);
        List<CompoundChoice> choices = new ArrayList<>();
        ops.runCompounds().forEach(c -> choices.add(new CompoundChoice(c, null)));
        if (launch != null) {
            launch.parsed().compounds().forEach(c -> choices.add(new CompoundChoice(null, c)));
        }
        choices.add(new CompoundChoice(null, null)); // New Compound…
        QuickOpen<CompoundChoice> picker = new QuickOpen<>(
                tr("debug.compound.title"),
                tr("debug.compound.prompt"),
                () -> choices,
                CompoundChoice::name,
                CompoundChoice::detail,
                c -> {
                    if (c == null) {
                        return;
                    }
                    com.editora.config.RunCompound own = c.own();
                    com.editora.dap.LaunchJson.Compound fromJson = c.launchJson();
                    if (own != null) {
                        debugRunCompound(own);
                    } else if (fromJson != null && launch != null) {
                        runLaunchCompound(launch, fromJson, b);
                    } else {
                        ops.editCompounds(null);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Debug: Edit Compounds… — the project's own compounds, in their editor. */
    void editCompounds() {
        ops.editCompounds(
                ops.runCompounds().isEmpty()
                        ? null
                        : ops.runCompounds().getFirst().name());
    }

    /** An entry of Debug: Start Compound: one of the project's own, one of its launch.json's, or New Compound…. */
    record CompoundChoice(
            com.editora.config.@Nullable RunCompound own, com.editora.dap.LaunchJson.@Nullable Compound launchJson) {

        String name() {
            com.editora.config.RunCompound o = own;
            com.editora.dap.LaunchJson.Compound j = launchJson;
            return o != null ? o.name() : j != null ? j.name() : tr("debug.compound.new");
        }

        String detail() {
            com.editora.config.RunCompound o = own;
            com.editora.dap.LaunchJson.Compound j = launchJson;
            if (o != null) {
                return String.join(", ", o.configurations());
            }
            return j != null ? "launch.json · " + String.join(", ", j.configurations()) : "";
        }
    }

    /** Runs configuration {@code c}: asks its inputs, then {@link #resolveAndRun}. */
    void runLaunchConfiguration(
            Path launchJson,
            Path workspace,
            com.editora.dap.LaunchJson.File parsed,
            com.editora.dap.LaunchJson.Configuration c,
            @Nullable EditorBuffer b) {
        java.util.Map<String, Object> body = com.editora.dap.LaunchJson.forPlatform(
                c.body(), com.editora.dap.LaunchJson.osKey(System.getProperty("os.name", "")));
        askInputs(
                parsed.inputs(),
                com.editora.dap.LaunchJson.inputsUsed(body),
                0,
                new java.util.LinkedHashMap<>(),
                answers -> inSession(
                        sessionForLaunch(), () -> resolveAndRun(launchJson, workspace, c.name(), body, answers, b)));
    }

    /** Asks inputs {@code ids} from {@code index} on, one after another, then hands the answers to {@code done}. */
    private void askInputs(
            java.util.Map<String, com.editora.dap.LaunchJson.Input> inputs,
            List<String> ids,
            int index,
            java.util.Map<String, String> answers,
            Consumer<java.util.Map<String, String>> done) {
        if (index >= ids.size()) {
            done.accept(answers);
            return;
        }
        String id = ids.get(index);
        com.editora.dap.LaunchJson.Input in = inputs.get(id);
        if (in == null) {
            host.setStatus(tr("status.debug.launchVariable", "${input:" + id + "}"));
            return;
        }
        String title = in.description().isBlank() ? id : in.description();
        Consumer<String> next = value -> {
            answers.put(id, value);
            askInputs(inputs, ids, index + 1, answers, done);
        };
        if ("pickString".equals(in.type()) && !in.options().isEmpty()) {
            QuickOpen<String> pick = new QuickOpen<>(title, title, in::options, o -> o, o -> "", o -> {
                if (o != null) {
                    next.accept(o);
                }
            });
            pick.setOverlayHost(host.overlayHost());
            pick.show(host.window());
        } else {
            host.promptText(title, title, in.defaultValue(), next);
        }
    }

    /**
     * Puts the variables in, runs the {@code preLaunchTask} (a plain {@code tasks.json} command), and starts the
     * configuration on its debugger ({@link com.editora.dap.LaunchRoute}).
     */
    private void resolveAndRun(
            Path launchJson,
            Path workspace,
            String name,
            java.util.Map<String, Object> raw,
            java.util.Map<String, String> answers,
            @Nullable EditorBuffer b) {
        java.util.Map<String, Object> body;
        try {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> substituted =
                    (java.util.Map<String, Object>) com.editora.dap.LaunchJson.substitute(
                            raw,
                            launchContext(workspace, b, answers),
                            java.util.Map.of(),
                            new java.util.HashMap<>(answers));
            body = substituted == null ? raw : substituted;
        } catch (com.editora.dap.LaunchJson.LaunchJsonException e) {
            host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
            return;
        } catch (com.editora.dap.LaunchJson.Cancelled e) {
            return;
        }
        Runnable start = () -> startRoute(workspace, name, body, b);
        if (!(body.get("preLaunchTask") instanceof String task) || task.isBlank()) {
            start.run();
            return;
        }
        List<String> argv = preLaunchTask(launchJson, workspace, task, b, answers);
        if (argv == null) {
            return; // said
        }
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(task, argv, "status.run.preLaunchTask"),
                        workspace,
                        java.util.Map.of(),
                        beforeLaunchConsole(),
                        () -> later(start));
    }

    /** The command line of {@code tasks.json}'s task {@code label}; null when it cannot be run here (said). */
    private @Nullable List<String> preLaunchTask(
            Path launchJson,
            Path workspace,
            String label,
            @Nullable EditorBuffer b,
            java.util.Map<String, String> answers) {
        Path tasksJson = launchJson.resolveSibling("tasks.json");
        try {
            com.editora.dap.LaunchJson.File tasks =
                    com.editora.dap.LaunchJson.parse(java.nio.file.Files.readString(tasksJson));
            java.util.Map<String, Object> task = com.editora.dap.LaunchJson.task(tasks, label);
            if (task == null) {
                host.setStatus(tr("status.debug.taskMissing", label));
                return null;
            }
            Object resolved = com.editora.dap.LaunchJson.substitute(
                    com.editora.dap.LaunchJson.forPlatform(
                            task, com.editora.dap.LaunchJson.osKey(System.getProperty("os.name", ""))),
                    launchContext(workspace, b, answers),
                    tasks.inputs(),
                    new java.util.HashMap<>(answers));
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> t = (java.util.Map<String, Object>) resolved;
            List<String> argv =
                    t == null ? null : com.editora.dap.LaunchJson.taskArgv(t, java.io.File.pathSeparatorChar == ';');
            if (argv == null) {
                host.setStatus(tr("status.debug.taskUnsupported", label));
            }
            return argv;
        } catch (java.io.IOException e) {
            host.setStatus(tr("status.debug.taskMissing", label));
        } catch (com.editora.dap.LaunchJson.LaunchJsonException e) {
            host.setStatus(tr("status.debug.launchVariable", e.getMessage()));
        } catch (com.editora.dap.LaunchJson.Cancelled e) {
            // asked and cancelled: nothing runs
        }
        return null;
    }

    /** Starts a resolved configuration on the debugger its type names. */
    private void startRoute(Path workspace, String name, java.util.Map<String, Object> body, @Nullable EditorBuffer b) {
        java.util.Map<String, com.editora.dap.DebugLanguage> own = new java.util.LinkedHashMap<>();
        customAdapters.forEach((type, a) -> own.put(type, a.profile()));
        com.editora.dap.LaunchRoute.Route route = com.editora.dap.LaunchRoute.of(body, own);
        Path active = b == null ? null : b.getPath();
        Path program = body.get("program") instanceof String p && !p.isBlank() ? Path.of(p) : null;
        Path anchor = active != null ? active : program != null ? program : workspace;
        com.editora.dap.DebugLanguage profile = route.profile();
        switch (route.kind()) {
            case UNSUPPORTED -> host.setStatus(tr("status.debug.launchUnsupported", name, route.reason()));
            case JAVA -> debugJavaConfiguration(name, body, workspace);
            case DEBUGPY -> {
                if (!debugEffectiveFor("python")) {
                    host.setStatus(unavailableMessage("python", null));
                } else if (route.attach()) {
                    String[] where = attachAddress(body, 5678);
                    ops.openToolWindow();
                    nameSession(name);
                    s().relaunch = null;
                    withClosedBreakpoints(() -> dap().startPythonAttach(anchor, where[0], Integer.parseInt(where[1])));
                } else {
                    ops.openToolWindow();
                    nameSession(name);
                    Path file = program != null ? program : anchor;
                    s().relaunch = null;
                    withClosedBreakpoints(() -> dap().startConfigured(file, "python", route.request()));
                }
            }
            case JS_DEBUG -> {
                if (!debugEffectiveFor("javascript")) {
                    host.setStatus(unavailableMessage("javascript", null));
                } else if (route.attach()) {
                    String[] where = attachAddress(body, com.editora.dap.JsLaunch.DEFAULT_INSPECTOR_PORT);
                    ops.openToolWindow();
                    nameSession(name);
                    s().relaunch = null;
                    withClosedBreakpoints(() -> dap().startJsAttach(
                                    where[0],
                                    Integer.parseInt(where[1]),
                                    name,
                                    workspace,
                                    List.of(com.editora.dap.JsLaunch.NODE_INTERNALS),
                                    true));
                } else {
                    ops.openToolWindow();
                    nameSession(name);
                    Path file = program != null ? program : anchor;
                    s().relaunch = null;
                    withClosedBreakpoints(() -> dap().startConfigured(file, "javascript", route.request()));
                }
            }
            case DELVE, CODELLDB, NETCOREDBG, RDBG, PHP_DEBUG, GDB, CUSTOM -> {
                if (profile == null) {
                    return;
                }
                // GDB and the user's own adapters are ready when their program was found; Delve and CodeLLDB as F5
                // finds them (their language's switch too).
                boolean byProgram = route.kind() == com.editora.dap.LaunchRoute.Kind.GDB
                        || route.kind() == com.editora.dap.LaunchRoute.Kind.CUSTOM;
                boolean ready = byProgram
                        ? debugSupportEnabled() && dap().isExternalLocated(profile)
                        : debugEffectiveFor(profile.editorLanguages().iterator().next());
                if (!ready) {
                    host.setStatus(
                            route.kind() == com.editora.dap.LaunchRoute.Kind.GDB
                                    ? tr("status.debug.gdbMissing")
                                    : byProgram
                                            ? tr("debug.error.customUnavailable")
                                            : unavailableMessage(profile.id(), null));
                    return;
                }
                java.util.Map<String, Object> request = new java.util.LinkedHashMap<>(route.request());
                if (route.kind() == com.editora.dap.LaunchRoute.Kind.DELVE
                        && !route.attach()
                        && !request.containsKey("output")) {
                    request.put("output", com.editora.dap.GoLaunch.debugBinary()); // not __debug_bin in the package
                }
                if (route.kind() == com.editora.dap.LaunchRoute.Kind.CODELLDB
                        && request.get("cargo") instanceof java.util.Map<?, ?> cargo) {
                    cargoThenStart(workspace, name, anchor, profile, request, cargo);
                    return;
                }
                if (route.kind() == com.editora.dap.LaunchRoute.Kind.RDBG) {
                    rdbgSession(anchor, request, route.attach(), name);
                    return;
                }
                startExternalSession(anchor, profile, request, name);
            }
        }
    }

    /**
     * An {@code rdbg} configuration: a launch runs its script under rdbg ({@link com.editora.dap.LaunchRoute#rdbgTail});
     * an attach connects to the program already under {@code rdbg --open} on its {@code debugPort}.
     */
    private void rdbgSession(Path anchor, java.util.Map<String, Object> request, boolean attach, String label) {
        ops.openToolWindow();
        nameSession(label);
        com.editora.dap.DebugLanguage ruby = com.editora.dap.DebugLanguages.RUBY;
        Runnable start = attach
                ? () -> withClosedBreakpoints(() ->
                        dap().startPortAttach(anchor, ruby, com.editora.dap.LaunchRoute.rdbgPort(request), request))
                : () -> withClosedBreakpoints(() ->
                        dap().startExternal(anchor, ruby, request, com.editora.dap.LaunchRoute.rdbgTail(request)));
        s().relaunch = start;
        start.run();
    }

    /** {@code host:port} an attach configuration names ({@code connect: {host, port}}, or {@code address} / {@code port}). */
    static String[] attachAddress(java.util.Map<String, Object> body, int defaultPort) {
        Object connect = body.get("connect");
        java.util.Map<?, ?> where = connect instanceof java.util.Map<?, ?> m ? m : body;
        Object host = where.get("host") != null ? where.get("host") : where.get("address");
        Object port = where.get("port");
        String p = port instanceof Number n ? String.valueOf(n.longValue()) : port == null ? "" : String.valueOf(port);
        return new String[] {
            host == null || String.valueOf(host).isBlank() ? "127.0.0.1" : String.valueOf(host),
            p.matches("\\d+") ? p : String.valueOf(defaultPort)
        };
    }

    /**
     * CodeLLDB's {@code cargo} field, as its VS Code extension runs it: {@code cargo <args>} (after the folder's
     * trust), then the executable its JSON messages name — the one {@code filter} ({@code name}, {@code kind})
     * picks — becomes the {@code program}.
     */
    private void cargoThenStart(
            Path workspace,
            String name,
            Path anchor,
            com.editora.dap.DebugLanguage profile,
            java.util.Map<String, Object> request,
            java.util.Map<?, ?> cargo) {
        List<String> cargoArgv = ops.buildExecutable(com.editora.build.BuildTool.CARGO, workspace);
        if (cargoArgv == null) {
            return; // not trusted
        }
        List<String> args = new ArrayList<>();
        if (cargo.get("args") instanceof List<?> l) {
            l.forEach(a -> args.add(String.valueOf(a)));
        }
        java.util.Map<?, ?> filter = cargo.get("filter") instanceof java.util.Map<?, ?> f ? f : java.util.Map.of();
        List<String> build = new ArrayList<>(cargoArgv);
        build.addAll(args);
        List<String> json = new ArrayList<>(build);
        json.add("--message-format=json");
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(name, build, "status.run.cargoBuild"),
                        workspace,
                        java.util.Map.of(),
                        beforeLaunchConsole(),
                        () -> startOffFx("editora-cargo-artifacts", session -> () -> {
                            var listed = com.editora.dap.RustLaunch.listArtifacts(workspace, json, CARGO_LIST_TIMEOUT);
                            laterIn(session, () -> {
                                Path exe = listed.ok()
                                        ? com.editora.dap.LaunchRoute.cargoExecutable(
                                                com.editora.dap.RustLaunch.artifacts(listed.out()),
                                                args.contains("test"),
                                                filter.get("name") instanceof String n ? n : null,
                                                filter.get("kind") instanceof String k ? k : null)
                                        : null;
                                if (exe == null) {
                                    host.setStatus(tr("status.debug.cargoNoArtifact", name));
                                    return;
                                }
                                java.util.Map<String, Object> resolved = new java.util.LinkedHashMap<>(request);
                                resolved.remove("cargo");
                                resolved.put("program", exe.toString());
                                startExternalSession(anchor, profile, resolved, name);
                            });
                        }));
    }

    /**
     * A {@code java} configuration as a run configuration of this window ({@code mainClass}, {@code projectName},
     * {@code args}, {@code vmArgs}, {@code cwd}, {@code env}), debugged as one is; a {@code mainClass} that is a
     * {@code .java} file debugs that file.
     */
    private void debugJavaConfiguration(String name, java.util.Map<String, Object> body, Path workspace) {
        String mainClass = body.get("mainClass") instanceof String m ? m : "";
        if (mainClass.endsWith(".java")) {
            Path file = Path.of(mainClass);
            ops.openPath(file);
            EditorBuffer opened = ops.bufferForPath(file);
            if (opened != null) {
                launchBuffer(opened);
            }
            return;
        }
        if (mainClass.isBlank()) {
            host.setStatus(tr("status.debug.launchUnsupported", name, "a Java configuration without a mainClass"));
            return;
        }
        debugConfig(com.editora.dap.LaunchRoute.javaRunConfiguration(name, body, workspace));
    }

    // --- Disassembly and Memory views ---------------------------------------------------------------------

    /** Instructions shown before and after the instruction pointer. */
    static final int DISASSEMBLY_BEFORE = 40;

    static final int DISASSEMBLY_COUNT = 120;

    /**
     * Step Over (F10, the toolbar): one machine instruction while the Disassembly view has the keyboard, as in VS
     * Code; one line otherwise.
     */
    void stepOver() {
        if (s().panel != null && s().panel.disassemblyView().hasFocus() && dap().supportsInstructionStepping()) {
            dap().stepOverInstruction();
        } else {
            dap().stepOver();
        }
    }

    /** Step Into: as {@link #stepOver}, one instruction (into a call) while the Disassembly view has the keyboard. */
    void stepInto() {
        if (s().panel != null && s().panel.disassemblyView().hasFocus() && dap().supportsInstructionStepping()) {
            dap().stepIntoInstruction();
        } else {
            dap().stepInto();
        }
    }

    /** Debug: Step Over Instruction (and Into): one machine instruction, wherever the keyboard is. */
    void stepInstruction(boolean into) {
        if (!dap().supportsInstructionStepping()) {
            host.setStatus(tr("status.debug.noInstructionStepping"));
            return;
        }
        if (into) {
            dap().stepIntoInstruction();
        } else {
            dap().stepOverInstruction();
        }
    }

    /** Debug: Show Disassembly / Show Memory: the Debug window's view, filled for the frame shown. */
    void showLowLevelView(String area) {
        ops.openToolWindow();
        panel().showArea(area);
    }

    /** Fills the Disassembly or Memory view, when shown, for the stop and frame shown. */
    private void refreshLowLevelViews() {
        if (s().panel == null) {
            return;
        }
        DebugPanel p = s().panel;
        String area = p.shownArea();
        if (dap().state() != DapManager.State.SUSPENDED) {
            if (DebugPanel.AREA_DISASSEMBLY.equals(area)) {
                p.disassemblyView().setMessage(tr("debugpanel.disassembly.idle"));
            }
            return;
        }
        if (DebugPanel.AREA_DISASSEMBLY.equals(area)) {
            DapModels.StackFrameInfo frame = s().shownFrame;
            String ip = frame == null ? null : frame.instructionPointer();
            if (!dap().supportsDisassemble()) {
                p.disassemblyView().setMessage(tr("debugpanel.disassembly.unsupported"));
            } else if (ip == null) {
                p.disassemblyView().setMessage(tr("debugpanel.disassembly.noPointer"));
            } else {
                dap().disassemble(ip, -DISASSEMBLY_BEFORE, DISASSEMBLY_COUNT, list -> {
                    if (s().shownFrame == frame) {
                        p.disassemblyView().show(list, ip);
                    }
                });
            }
        } else if (DebugPanel.AREA_MEMORY.equals(area)) {
            if (!dap().supportsReadMemory()) {
                p.memoryView().setMessage(tr("debugpanel.memory.unsupported"));
            } else if (s().memoryReference != null) {
                readMemory();
            }
        }
    }

    /** The Memory view at {@code text}: an address as typed, else the address of the expression's value. */
    private void memoryGo(String text) {
        DebugMemoryView view = panel().memoryView();
        if (text == null || text.isBlank()) {
            return;
        }
        if (dap().state() != DapManager.State.SUSPENDED) {
            view.setMessage(tr("debugpanel.memory.idle"));
            return;
        }
        if (!dap().supportsReadMemory()) {
            view.setMessage(tr("debugpanel.memory.unsupported"));
            return;
        }
        String literal = DebugMemoryView.literalAddress(text);
        if (literal != null) {
            s().memoryReference = literal;
            s().memoryOffset = 0;
            readMemory();
            return;
        }
        dap().memoryReferenceOf(text, s().debugFrameId, ref -> {
            if (ref == null) {
                view.setMessage(tr("debugpanel.memory.noAddress", text));
                return;
            }
            s().memoryReference = ref;
            s().memoryOffset = 0;
            readMemory();
        });
    }

    /** Reads a page at the Memory view's reference and offset into it. */
    private void readMemory() {
        String ref = s().memoryReference;
        if (ref == null || s().panel == null) {
            return;
        }
        DebugMemoryView view = s().panel.memoryView();
        dap().readMemory(
                        ref,
                        s().memoryOffset,
                        DebugMemoryView.PAGE,
                        view::show,
                        why -> view.setMessage(tr("debugpanel.memory.failed", ref, why)));
    }

    /** Opens {@code file} at 0-based {@code line}: a double-clicked instruction's source line. */
    private void openSourceLine(Path file, int line) {
        ops.openPath(file);
        EditorBuffer b = ops.bufferForPath(file);
        if (b != null && line >= 0) {
            int first = b.getBreakpointManager().regionFirstLine();
            b.getFocusedArea().moveTo(Math.max(0, line - first), 0);
            b.getFocusedArea().requestFollowCaret();
        }
    }

    /** Opens the frame's file and paints the execution-line highlight there. */
    private void highlightFrame(DapModels.StackFrameInfo frame) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        clearExecHighlight();
        if (frame == null) {
            return;
        }
        if (!hasSource(frame)) {
            if (frame.sourceUri() != null && dap().debugFile() != null) {
                // B5: a JDK or library class jdtls can supply the text of (java/classFileContents) — shown in its
                // read-only library tab, as go-to-definition and the console's stack-trace links already do.
                ops.openLibraryFrame(dap().debugFile(), frame.sourceUri(), frame.line(), lib -> {
                    if (s().shownFrame == frame && dap().state() == DapManager.State.SUSPENDED) {
                        clearExecHighlight();
                        paintExecutionLine(lib, frame);
                    }
                });
                return;
            }
            // Asking for it anyway opened a loading tab, failed, removed the tab again and reported "Failed to
            // open" as an error — on every stop and step inside library code. Say what is the case instead;
            // the frame stays selected with its variables, and the other frames stay a click away.
            host.setStatus(tr("status.debug.noSource", frame.name()));
            return;
        }
        // Revealing the line focuses the editor (openPath calls requestFocus on the buffer). When the user
        // is driving the session from the Debug panel — the single-key step shortcuts — that yanks focus to
        // the code after every step, so the next key press is lost and they must re-focus the panel each
        // time. Preserve the panel's focus across the reveal in that case only; a fresh breakpoint hit while
        // editing (focus not in the panel) still lands in the code at the stopped line, as before.
        Node keepFocus = debugPanelFocusOwner();
        ops.openPath(frame.file()); // opens or focuses the tab
        // Take the frame's OWN buffer, not whatever is active — mirrors applyInlineValues.
        EditorBuffer b = ops.bufferForPath(frame.file());
        if (b != null) {
            paintExecutionLine(b, frame);
        }
        if (keepFocus != null) {
            later(keepFocus::requestFocus); // after openPath's own requestFocus, so the panel wins
        }
    }

    /** The frame whose line the editor marks as the execution line, or null. */
    private DapModels.@Nullable StackFrameInfo paintedFrame;

    private @Nullable DebugExceptionCard exceptionCard;

    /**
     * Opens the exception card under the line that threw, when that frame is the one shown and the adapter has said
     * which exception it is. By itself ({@code asked} false) only once per stop: closing it keeps it closed.
     */
    private void showExceptionCard(boolean asked) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        DapModels.ExceptionInfo info = s().stopException;
        DapModels.StackFrameInfo frame = paintedFrame;
        EditorBuffer b = execHighlightBuffer;
        if (info == null
                || frame == null
                || b == null
                || frame.id() != s().exceptionFrameId
                || dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        if (!asked && s().exceptionCardStop == s().stopSerial) {
            return;
        }
        s().exceptionCardStop = s().stopSerial;
        if (exceptionCard == null) {
            exceptionCard = new DebugExceptionCard(new DebugExceptionCard.Actions(
                    () -> {
                        if (exceptionToggleOn(true)) {
                            toggleExceptionKind(true); // says so on the status line
                        }
                    },
                    () -> host.setStatus(tr("status.debug.exceptionCopied"))));
        }
        int line = frame.line() - b.getBreakpointManager().regionFirstLine();
        exceptionCard.show(b.getFocusedArea(), line, info, info.caught() && exceptionToggleOn(true));
    }

    private void hideExceptionCard() {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        if (exceptionCard != null) {
            exceptionCard.hide();
        }
    }

    /** The exception card (test read; null until an exception stop opened it). */
    @Nullable
    DebugExceptionCard exceptionCard() {
        return exceptionCard;
    }

    /**
     * Debug: Show Exception — opens the card of the exception the program stopped on again, going back to the frame
     * that threw when another one is shown.
     */
    void showException() {
        if (s().stopException == null || dap().state() != DapManager.State.SUSPENDED) {
            host.setStatus(tr("status.debug.noException"));
            return;
        }
        DapModels.StackFrameInfo frame = paintedFrame;
        if (frame == null || frame.id() != s().exceptionFrameId) {
            if (!panel().selectFrameById(s().exceptionFrameId)) {
                host.setStatus(tr("status.debug.noException"));
                return;
            }
            s().exceptionCardStop = -1; // the frame's paint opens it, once laid out
            return;
        }
        showExceptionCard(true);
    }

    private void paintExecutionLine(EditorBuffer b, DapModels.StackFrameInfo frame) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        execHighlightBuffer = b;
        ops.afterBufferLoad(b, () -> {
            // The user may resume or select another frame while this file is still loading. Never let
            // that obsolete completion repaint an execution marker that clearExecHighlight removed.
            if (execHighlightBuffer == b) {
                // A narrowed buffer shows only its region: the frame's file line is region-relative
                // there (a stop outside the region has no line to show and paints nothing).
                b.setExecutionLine(frame.line() - b.getBreakpointManager().regionFirstLine());
                b.setExecutionLineException(frame.id() == s().exceptionFrameId);
                paintedFrame = frame;
                if (frame.id() == s().exceptionFrameId) {
                    later(() -> showExceptionCard(false)); // once the line has been laid out
                } else {
                    hideExceptionCard(); // another frame's line: the card belongs to the one that threw
                }
            }
        });
    }

    /** The Debug-panel descendant that currently owns keyboard focus (so it can be restored across an
     *  editor-focusing reveal), or {@code null} when focus is elsewhere. */
    private @Nullable Node debugPanelFocusOwner() {
        var window = host.window();
        var scene = window == null ? null : window.getScene();
        Node owner = scene == null ? null : scene.getFocusOwner();
        for (Node n = owner; n != null; n = n.getParent()) {
            if (s().panel != null && n == s().panel) {
                return owner;
            }
        }
        return null;
    }

    private void clearExecHighlight() {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        paintedFrame = null;
        hideExceptionCard();
        if (execHighlightBuffer != null) {
            execHighlightBuffer.clearExecutionLine();
            execHighlightBuffer = null;
        }
    }

    // --- debug commands ----------------------------------------------------------------------------

    /** Program arguments read as Run reads them; null after saying why when a quote is never closed. */
    private @Nullable List<String> debugArgs(String text) {
        try {
            return ProgramArgs.parse(text);
        } catch (ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.argsUnclosedQuote", String.valueOf(e.quote())));
            return null;
        }
    }

    /**
     * Debug ▸ Start for the active file (saving first, like Run) — or Continue, when the session there is
     * paused or stepping.
     */
    void debugStart() {
        EditorBuffer b = host.activeBuffer();
        if (!clearToStart(b, true)) {
            return;
        }
        s().startCleared = true; // Debug Test's own check (clearToDebugTest) has been answered here
        try {
            if (debugsTest(b)) {
                return;
            }
        } finally {
            s().startCleared = false;
        }
        launchBuffer(b);
    }

    /** Debug File (the editor menu, the Debug File handler): {@code b}, after the same check as F5's. */
    void debugFile(EditorBuffer b) {
        if (clearToStart(b, false)) {
            launchBuffer(b);
        }
    }

    /** Debug Test at the caret or from the editor menu: false when a live launch stays (said, or asked and kept). */
    boolean clearToDebugTest() {
        return s().startCleared || clearToStart(host.activeBuffer(), false);
    }

    /**
     * The one check every way to start goes through (review 3, C202–C204, A208, A209). A launch is live — or still
     * getting ready: a build before it, a before-launch step, a test run waiting for its JVM, a compile, the
     * main-class picker. For F5 ({@code f5}) a paused (or mid-step) session is continued: never yank a paused
     * session. Otherwise the same file is already being debugged (said so, in words that fit how far it has got),
     * and another file replaces it — after asking, since that ends the program (review A106: a starting launch was
     * replaced unasked; review 3 C202: the editor menu's Debug File ended a paused session unasked). True when
     * {@code b} may be launched now.
     */
    private boolean clearToStart(@Nullable EditorBuffer b, boolean f5) {
        Path path = b == null ? null : b.getPath();
        if (path != null) {
            for (DebugSession other : sessions.all()) {
                if (other != s() && inSession(other, () -> launchLiveFor(path))) {
                    focusSession(other); // the file is another session's: that one is shown, and asked about
                    return clearToStart(b, f5);
                }
            }
        }
        boolean preparing = launchPreparing();
        Path liveFile = preparing ? preparingFile() : dap().debugFile();
        boolean differentFile = path != null && !samePath(path, liveFile);
        boolean debuggable = b != null && path != null && debugEffectiveFor(b.getLanguage(), path);
        boolean active = dap().isActive();
        DapManager.State state = dap().state();
        DebugStartChoice choice = f5
                ? DebugStartChoice.of(active, state, dap().isStepping(), preparing, differentFile, debuggable)
                : DebugStartChoice.forFile(active, state, preparing, differentFile);
        DebugStartChoice.Phase phase = DebugStartChoice.Phase.of(active, state, dap().isStepping(), preparing);
        String label = preparing && s().pendingBuild == null && s().pendingAttachFile != null
                ? String.valueOf(s().pendingAttachFile.getFileName())
                : s().sessionLabel;
        switch (choice) {
            case CONTINUE -> {
                dap().resume(); // F5-style continue
                return false;
            }
            case ALREADY_RUNNING -> {
                host.setStatus(tr(phase.busyKey, label));
                return false;
            }
            case ASK_RETARGET -> {
                if (path == null) {
                    return false;
                }
                // Both at once beside a session that has started; one still getting ready (a build, a test run's
                // JVM) is replaced or kept, as before.
                boolean both = active && !preparing;
                Retarget answer = askRetarget(
                        tr(both ? "dialog.debug.retargetBothQuestion" : phase.retargetKey, label, path.getFileName()),
                        both);
                switch (answer) {
                    case BOTH -> {
                        openSession(); // the launch asked for is the new session's
                        return true;
                    }
                    case STOP -> {
                        endLiveLaunch(); // retarget to the file asked for
                        return true;
                    }
                    default -> {
                        return false;
                    }
                }
            }
            default -> {
                return true; // nothing to end first
            }
        }
    }

    /** A launch is getting ready with no session yet: a jdtls build, a before-launch step, a test run's JVM. */
    private boolean launchPreparing() {
        return !dap().isActive() && (s().pendingBuild != null || beforeLaunchActive() || s().pendingAttachStop != null);
    }

    /** The file the launch getting ready is for, when known. */
    private @Nullable Path preparingFile() {
        return s().pendingBuild != null
                ? s().pendingBuildFile
                : s().pendingAttachStop != null ? s().pendingAttachFile : null;
    }

    /** A launch of {@code file} is live or getting ready. */
    private boolean launchLiveFor(Path file) {
        if (launchPreparing()) {
            return samePath(file, preparingFile());
        }
        return dap().state() != DapManager.State.INACTIVE && samePath(file, dap().debugFile());
    }

    /** Ends whatever launch is live or getting ready, for a launch of another file. */
    private void endLiveLaunch() {
        if (beforeLaunchActive()) {
            beforeLaunch().stop();
        }
        dropPendingBuild();
        stop(); // the session, or a test run waiting for its JVM
    }

    /** Debugs a JUnit test at the caret ({@code true}: the whole class), as the gutter's Debug Test; set by the
     *  window. Null: Debug ▶ launches a test file like any other. */
    private @Nullable Consumer<Boolean> testDebugger;

    void setTestDebugger(Consumer<Boolean> testDebugger) {
        this.testDebugger = testDebugger;
    }

    /** Debug ▶ in a JUnit test file debugs the test at the caret, or its class (review A102); true when it did. */
    private boolean debugsTest(@Nullable EditorBuffer b) {
        if (b == null || testDebugger == null || !"java".equals(b.getLanguage())) {
            return false;
        }
        b.settleRunScan(); // the test lines come from the load's off-FX scan
        DebugTestRoute.Route route = DebugTestRoute.route(
                true,
                b.isCompactSource(),
                b.testClassTarget() != null,
                ownMainClass(b.getContent()) != null,
                b.testTargetAtCaret(false) != null);
        if (route == DebugTestRoute.Route.LAUNCH) {
            return false;
        }
        testDebugger.accept(route == DebugTestRoute.Route.TEST_CLASS);
        return true;
    }

    /**
     * Asks before F5 in another tab ends the running session (review A13); a test replaces it. The answer is
     * the user's: a running server is not stopped by a key pressed in the wrong tab.
     */
    @Nullable
    Predicate<String> confirmRetarget;

    /** What to do with the live launch when another file is asked to be debugged. */
    enum Retarget {
        /** Debug the other file in a session of its own, beside the live one. */
        BOTH,
        /** End the live launch and debug the other file instead. */
        STOP,
        /** Leave things as they are. */
        CANCEL
    }

    /**
     * Answers {@link #askRetarget} in tests: the question, and whether Debug Both was offered. Null: ask the user.
     * When only {@link #confirmRetarget} is set, its yes is {@link Retarget#STOP}.
     */
    java.util.function.@Nullable BiFunction<String, Boolean, Retarget> retargetChoice;

    /**
     * Asks before F5 in another tab ends the live launch: Debug Both (when {@code bothOffered}: the default, a running
     * server is not stopped by a key pressed in the wrong tab), Stop and Debug, or Cancel.
     */
    private Retarget askRetarget(String question, boolean bothOffered) {
        if (retargetChoice != null) {
            return retargetChoice.apply(question, bothOffered);
        }
        if (confirmRetarget != null) {
            return confirmRetarget.test(question) ? Retarget.STOP : Retarget.CANCEL;
        }
        javafx.scene.control.ButtonType both = new javafx.scene.control.ButtonType(
                tr("dialog.debug.retargetBoth"), javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        javafx.scene.control.ButtonType stop = new javafx.scene.control.ButtonType(
                tr("dialog.debug.retargetStop"),
                bothOffered
                        ? javafx.scene.control.ButtonBar.ButtonData.OTHER
                        : javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        List<javafx.scene.control.ButtonType> buttons = bothOffered
                ? List.of(both, stop, javafx.scene.control.ButtonType.CANCEL)
                : List.of(stop, javafx.scene.control.ButtonType.CANCEL);
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.CONFIRMATION,
                question,
                buttons.toArray(javafx.scene.control.ButtonType[]::new));
        alert.setTitle(tr(bothOffered ? "dialog.debug.retargetBothTitle" : "dialog.debug.retargetTitle"));
        alert.setHeaderText(null);
        if (host.window() != null) {
            alert.initOwner(host.window());
        }
        javafx.scene.control.ButtonType answer = alert.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL);
        return answer == both ? Retarget.BOTH : answer == stop ? Retarget.STOP : Retarget.CANCEL;
    }

    /** Saves {@code b} and launches a debug session for it; also what Restart repeats for such a session. */
    private void launchBuffer(@Nullable EditorBuffer b) {
        launchBuffer(b, List.of());
    }

    /**
     * Debugs Go file {@code path} on Delve: in a test file the test at the caret (its package's tests outside any), else
     * its package — the file alone outside a module — with {@code args} as the program's or the test binary's. Delve
     * builds it with the {@code go} the Go build tool runs, after the module's trust as a Go task asks it.
     */
    private void debugGoFile(EditorBuffer b, Path path, List<String> args) {
        Path module = com.editora.dap.GoLaunch.moduleRoot(path);
        List<String> go = module != null
                ? ops.buildExecutable(com.editora.build.BuildTool.GO, module)
                : com.editora.build.BuildExecutable.resolve(
                        List.of(), host.settings().getGoCommand(), "go");
        if (go == null) {
            return; // the module was not trusted: nothing is built
        }
        int caret = caretLine(b);
        String text = b.getContent();
        java.util.Map<String, Object> launch =
                com.editora.dap.GoLaunch.launch(path, text, caret, module != null, args, java.util.Map.of(), false);
        nameSession(com.editora.dap.GoLaunch.sessionName(path, text, caret));
        dap().setExternalPathDirs("go", goToolDirs(go));
        Path built = module != null ? module : path.toAbsolutePath().getParent();
        s().relaunch = () -> startGo(path, built, launch); // the same test again, wherever the caret is now
        startGo(path, built, launch);
    }

    /**
     * Saves the edited Go files under {@code built} (the module, or the loose file's folder: Delve builds them from
     * disk), then starts {@code launch} for {@code path}.
     */
    private void startGo(Path path, @Nullable Path built, java.util.Map<String, Object> launch) {
        saveBuffersUnder(built, "go");
        withClosedBreakpoints(() -> dap().startExternal(path, com.editora.dap.DebugLanguages.GO, launch));
    }

    /** The folder of {@code go} (its argv) when it is a path, for Delve's PATH: Delve builds with the {@code go} found there. */
    static List<String> goToolDirs(List<String> go) {
        if (go.isEmpty()) {
            return List.of();
        }
        Path exe;
        try {
            exe = Path.of(go.getFirst());
        } catch (java.nio.file.InvalidPathException e) {
            return List.of();
        }
        Path dir = exe.isAbsolute() ? exe.getParent() : null;
        return dir == null ? List.of() : List.of(dir.toString());
    }

    /**
     * Debugs Rust file {@code path} on CodeLLDB: the test at the caret (its test binary, filtered to it), else in an
     * integration test all its tests, else the package's program — or, in a library, the file's tests. Cargo builds
     * it first in the Debug console (after the package's trust, as a Cargo task asks it); its JSON messages then say
     * which executable to launch.
     */
    private void debugRustFile(EditorBuffer b, Path path, List<String> args) {
        Path root = com.editora.dap.RustLaunch.packageRoot(path);
        if (root == null) {
            host.setStatus(tr("status.debug.rustNoPackage", path.getFileName()));
            return;
        }
        List<String> cargo = ops.buildExecutable(com.editora.build.BuildTool.CARGO, root);
        if (cargo == null) {
            return; // the package was not trusted: nothing is built
        }
        boolean hasProgram =
                java.nio.file.Files.isRegularFile(root.resolve("src").resolve("main.rs"))
                        || java.nio.file.Files.isDirectory(root.resolve("src").resolve("bin"));
        com.editora.dap.RustLaunch.Plan plan =
                com.editora.dap.RustLaunch.plan(root, path, b.getContent(), caretLine(b), hasProgram);
        nameSession(plan.sessionName());
        s().relaunch = () -> startRust(path, root, cargo, plan, args); // the same test again, wherever the caret is now
        startRust(path, root, cargo, plan, args);
    }

    /**
     * Saves the package's edited Rust files, builds {@code plan} with Cargo as a before-launch step (its output in the
     * Debug console; Stop ends it), asks Cargo again for the executables it built (nothing is left to build), and
     * launches the one for {@code path} on CodeLLDB.
     */
    private void startRust(
            Path path, Path root, List<String> cargo, com.editora.dap.RustLaunch.Plan plan, List<String> args) {
        saveBuffersUnder(root, "rust");
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        List<String> build = new java.util.ArrayList<>(cargo);
        build.addAll(plan.buildArgs());
        List<String> json = new java.util.ArrayList<>(cargo);
        json.addAll(plan.jsonArgs());
        boolean terminal = host.settings().isDebugProgramConsole();
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(
                                String.valueOf(path.getFileName()), build, "status.run.cargoBuild"),
                        root,
                        java.util.Map.of(),
                        beforeLaunchConsole(),
                        () -> startOffFx("editora-cargo-artifacts", session -> () -> {
                            var listed = com.editora.dap.RustLaunch.listArtifacts(root, json, CARGO_LIST_TIMEOUT);
                            laterIn(session, () -> launchRustArtifact(path, root, plan, args, terminal, listed));
                        }));
        updateDebugAvailability();
    }

    /** How long Cargo has to list what it just built. */
    private static final java.time.Duration CARGO_LIST_TIMEOUT = java.time.Duration.ofMinutes(2);

    /** Launches the executable Cargo's {@code listed} messages name for {@code path}, or says why there is none. */
    private void launchRustArtifact(
            Path path,
            Path root,
            com.editora.dap.RustLaunch.Plan plan,
            List<String> args,
            boolean terminal,
            com.editora.process.ProcessRunner.Result listed) {
        if (!listed.ok()) {
            String why = listed.err().strip();
            host.setStatus(tr(
                    "status.debug.rustBuildFailed",
                    why.isEmpty()
                            ? "exit " + listed.exit()
                            : why.lines().reduce((a, z) -> z).orElse(why)));
            return;
        }
        com.editora.dap.RustLaunch.Artifact artifact =
                com.editora.dap.RustLaunch.pick(com.editora.dap.RustLaunch.artifacts(listed.out()), path, plan.mode());
        if (artifact == null) {
            host.setStatus(tr("status.debug.rustNoProgram", path.getFileName()));
            return;
        }
        List<String> programArgs = new java.util.ArrayList<>(plan.testArgs());
        programArgs.addAll(args);
        java.util.Map<String, Object> launch = com.editora.dap.RustLaunch.launch(
                artifact.executable(), programArgs, root, java.util.Map.of(), false, terminal);
        ops.openToolWindow();
        withClosedBreakpoints(() -> dap().startExternal(path, com.editora.dap.DebugLanguages.RUST, launch));
    }

    /**
     * Debugs C or C++ file {@code path} on CodeLLDB: built first — its CMake project's Debug build, its Make project's
     * default goal (each after the folder's trust: their build files are code), or the file alone by the C or C++
     * compiler — then the executable that made is launched ({@link com.editora.dap.NativeBuild}).
     */
    private void debugNativeFile(EditorBuffer b, Path path, List<String> args) {
        Path cmake = com.editora.dap.NativeBuild.cmakeRoot(path);
        Path make = cmake == null ? com.editora.dap.NativeBuild.makeRoot(path) : null;
        com.editora.dap.NativeBuild.Plan plan =
                com.editora.dap.NativeBuild.plan(path, "cpp".equals(b.getLanguage()), cmake, make, nativeOutputDir());
        if (plan == null) {
            host.setStatus(tr("status.debug.nativeHeaderAlone", path.getFileName()));
            return;
        }
        if (plan.tool() != com.editora.dap.NativeBuild.Tool.COMPILER) {
            String buildFile = plan.tool() == com.editora.dap.NativeBuild.Tool.CMAKE ? "CMakeLists.txt" : "Makefile";
            String key = plan.tool() == com.editora.dap.NativeBuild.Tool.CMAKE
                    ? "dialog.trust.item.cmake"
                    : "dialog.trust.item.make";
            if (!ops.trustBuild(plan.root(), List.of(tr(key, buildFile)))) {
                return; // not trusted: nothing is built
            }
        }
        nameSession(plan.sessionName());
        s().relaunch = () -> startNative(path, plan, args);
        startNative(path, plan, args);
    }

    /** Where a lone C or C++ file's program is compiled to: the config folder's cache, else the temporary folder. */
    private Path nativeOutputDir() {
        Path config = host.configDir();
        return config != null
                ? config.resolve("cache").resolve("native-debug")
                : Path.of(System.getProperty("java.io.tmpdir"), "editora-native-debug");
    }

    /** Saves the project's C and C++ files, runs {@code plan}'s build steps in the Debug console, then launches. */
    private void startNative(Path path, com.editora.dap.NativeBuild.Plan plan, List<String> args) {
        saveBuffersUnder(plan.root(), "c");
        saveBuffersUnder(plan.root(), "cpp");
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        try {
            if (plan.buildDir() != null) {
                com.editora.dap.NativeBuild.requestCodemodel(plan.buildDir());
            }
            if (plan.output() != null && plan.output().getParent() != null) {
                java.nio.file.Files.createDirectories(plan.output().getParent());
            }
        } catch (java.io.IOException e) {
            host.setStatus(tr("debug.error.startFailed", e.getMessage()));
            return;
        }
        boolean terminal = host.settings().isDebugProgramConsole();
        runNativeSteps(path, plan, 0, () -> launchNative(path, plan, args, terminal));
        updateDebugAvailability();
    }

    /** Runs {@code plan}'s build step {@code index} and those after it, then {@code then}; a failed one ends it. */
    private void runNativeSteps(Path path, com.editora.dap.NativeBuild.Plan plan, int index, Runnable then) {
        if (index >= plan.steps().size()) {
            then.run();
            return;
        }
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(
                                String.valueOf(path.getFileName()), plan.steps().get(index), "status.run.nativeBuild"),
                        plan.root(),
                        java.util.Map.of(),
                        beforeLaunchConsole(index == 0),
                        // after the step has ended: the next one is not refused as "still running"
                        () -> later(() -> runNativeSteps(path, plan, index + 1, then)));
    }

    /** Finds the executable {@code plan} built (off the FX thread) and launches it, or says there is none. */
    private void launchNative(Path path, com.editora.dap.NativeBuild.Plan plan, List<String> args, boolean terminal) {
        startOffFx("editora-native-executable", session -> () -> {
            Path exe = switch (plan.tool()) {
                case CMAKE ->
                    plan.buildDir() == null ? null : com.editora.dap.NativeBuild.cmakeExecutable(plan.buildDir(), path);
                case MAKE -> com.editora.dap.NativeBuild.makeExecutable(plan.root(), path);
                case COMPILER -> plan.output();
            };
            boolean there = exe != null && java.nio.file.Files.isRegularFile(exe);
            laterIn(session, () -> {
                if (!there || exe == null) {
                    host.setStatus(tr("status.debug.nativeNoProgram", path.getFileName()));
                    return;
                }
                boolean gdb = gdbForCpp();
                java.util.Map<String, Object> launch = gdb
                        ? com.editora.dap.NativeBuild.gdbLaunch(exe, args, plan.root())
                        : com.editora.dap.NativeBuild.launch(exe, args, plan.root(), terminal);
                com.editora.dap.DebugLanguage profile =
                        gdb ? com.editora.dap.DebugLanguages.GDB : com.editora.dap.DebugLanguages.CPP;
                ops.openToolWindow();
                withClosedBreakpoints(() -> dap().startExternal(path, profile, launch));
            });
        });
    }

    /**
     * Debugs C# file {@code path} on netcoredbg: its project — after the folder's trust, as MSBuild runs the project's
     * targets — or, outside every project, the file as a .NET 10 file-based app, is built with {@code dotnet build} in
     * the Debug console; the assembly that made is then launched in the project's folder
     * ({@link com.editora.dap.DotnetLaunch}).
     */
    private void debugCsharpFile(Path path, List<String> args) {
        Path project = com.editora.dap.DotnetLaunch.projectFile(path);
        com.editora.dap.DotnetLaunch.Plan plan = com.editora.dap.DotnetLaunch.plan(path, project);
        if (plan.project()
                && !ops.trustBuild(
                        plan.root(),
                        List.of(tr("dialog.trust.item.dotnet", plan.target().getFileName())))) {
            return; // not trusted: nothing is built
        }
        nameSession(plan.sessionName());
        s().relaunch = () -> startCsharp(path, plan, args);
        startCsharp(path, plan, args);
    }

    /** The .NET SDK's command (a test's stand-in replaces it). */
    List<String> dotnetCommand = List.of("dotnet");

    /** How long {@code dotnet} has to name the assembly it built (it only evaluates the project). */
    private static final java.time.Duration DOTNET_QUERY_TIMEOUT = java.time.Duration.ofMinutes(2);

    /** Saves the C# files under the project, builds it in the Debug console, then launches its assembly. */
    private void startCsharp(Path path, com.editora.dap.DotnetLaunch.Plan plan, List<String> args) {
        saveBuffersUnder(plan.root(), "csharp");
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        List<String> dotnet = dotnetCommand;
        List<String> build = new java.util.ArrayList<>(dotnet);
        build.addAll(plan.buildArgs());
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(
                                String.valueOf(path.getFileName()), build, "status.run.dotnetBuild"),
                        plan.root(),
                        com.editora.dap.DotnetLaunch.BUILD_ENV,
                        beforeLaunchConsole(),
                        () -> startOffFx("editora-dotnet-target", session -> () -> {
                            var listed =
                                    com.editora.dap.DotnetLaunch.queryTargetPath(dotnet, plan, DOTNET_QUERY_TIMEOUT);
                            laterIn(session, () -> launchCsharpAssembly(path, plan, args, listed));
                        }));
        updateDebugAvailability();
    }

    /** Launches the assembly {@code listed} names for {@code path} on netcoredbg, or says why there is none. */
    private void launchCsharpAssembly(
            Path path,
            com.editora.dap.DotnetLaunch.Plan plan,
            List<String> args,
            com.editora.process.ProcessRunner.Result listed) {
        Path assembly = listed.ok() ? com.editora.dap.DotnetLaunch.targetPath(listed.out()) : null;
        if (!listed.ok()) {
            String why = (listed.err().isBlank() ? listed.out() : listed.err()).strip();
            host.setStatus(tr(
                    "status.debug.csharpBuildFailed",
                    why.isEmpty()
                            ? "exit " + listed.exit()
                            : why.lines().reduce((a, z) -> z).orElse(why)));
            return;
        }
        if (assembly == null || !java.nio.file.Files.isRegularFile(assembly)) {
            host.setStatus(tr("status.debug.csharpNoProgram", path.getFileName()));
            return;
        }
        java.util.Map<String, Object> launch = com.editora.dap.DotnetLaunch.launch(assembly, args, plan.root());
        ops.openToolWindow();
        withClosedBreakpoints(() -> dap().startExternal(path, com.editora.dap.DebugLanguages.CSHARP, launch));
    }

    /**
     * Debugs Ruby or PHP file {@code path} in its folder: a Ruby script under rdbg ({@code -- ruby <file>}), a PHP one by
     * {@code php} with Xdebug, which vscode-php-debug starts ({@link com.editora.dap.ScriptLaunch}).
     */
    private void debugScriptFile(Path path, String language, List<String> args) {
        Path cwd = path.toAbsolutePath().getParent();
        Path dir = cwd != null ? cwd : path.toAbsolutePath();
        Runnable start = "ruby".equals(language)
                ? () -> withClosedBreakpoints(() -> dap().startExternal(
                                path,
                                com.editora.dap.DebugLanguages.RUBY,
                                com.editora.dap.ScriptLaunch.rubyLaunch(path, args, dir),
                                com.editora.dap.ScriptLaunch.rubyTail(path, args)))
                : () -> withClosedBreakpoints(() -> dap().startExternal(
                                path,
                                com.editora.dap.DebugLanguages.PHP,
                                com.editora.dap.ScriptLaunch.phpLaunch(path, args, dir)));
        s().relaunch = start;
        start.run();
    }

    /** Saves the edited buffers of {@code language} under {@code root}: a build reads them from disk. */
    private void saveBuffersUnder(@Nullable Path root, String language) {
        host.forEachBuffer(other -> {
            Path p = other.getPath();
            if (other.isDirty()
                    && p != null
                    && language.equals(other.getLanguage())
                    && root != null
                    && p.toAbsolutePath().startsWith(root)) {
                ops.saveBuffer(other);
            }
        });
    }

    /** The 0-based document line of {@code b}'s caret. */
    private static int caretLine(EditorBuffer b) {
        return b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
    }

    /**
     * Debug Test at Caret in a Rust file: the {@code #[test]} function at the caret, its test binary filtered to it
     * ({@link #debugRustFile}). False — nothing done — outside a test, or for any other buffer.
     */
    boolean debugRustTestAtCaret(@Nullable EditorBuffer b) {
        if (b == null
                || b.getPath() == null
                || !"rust".equals(b.getLanguage())
                || com.editora.dap.RustLaunch.testAt(b.getContent(), caretLine(b)) == null) {
            return false;
        }
        launchBuffer(b);
        return true;
    }

    /**
     * Debug Test at Caret on a Go test file ({@code *_test.go}): the test at the caret under Delve's test mode, the
     * package's tests outside any. False — nothing done — for any other buffer.
     */
    boolean debugGoTestAtCaret(@Nullable EditorBuffer b) {
        if (b == null
                || b.getPath() == null
                || !"go".equals(b.getLanguage())
                || !com.editora.dap.GoLaunch.isTestFile(b.getPath())) {
            return false;
        }
        launchBuffer(b);
        return true;
    }

    /**
     * Debug Test at Caret on a JavaScript/TypeScript test file ({@link com.editora.dap.JsLaunch#isTestFile}): the
     * file runs under {@code node --test}, narrowed to the test at the caret by name when there is one. False —
     * nothing done — for any other buffer.
     */
    boolean debugJsTestAtCaret(@Nullable EditorBuffer b) {
        if (b == null
                || b.getPath() == null
                || !"javascript".equals(DapServerRegistry.adapterLanguage(b.getLanguage()))
                || !com.editora.dap.JsLaunch.isTestFile(b.getPath())) {
            return false;
        }
        int caret = b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
        List<String> nodeArgs = new java.util.ArrayList<>(List.of("--test"));
        com.editora.dap.JsLaunch.testNameAt(b.getContent(), caret).ifPresent(name -> {
            nodeArgs.add("--test-name-pattern");
            nodeArgs.add(com.editora.dap.JsLaunch.literalPattern(name));
        });
        launchBuffer(b, nodeArgs);
        return true;
    }

    /** {@link #launchBuffer(EditorBuffer)} with Node.js arguments for a JavaScript program ({@code --test}). */
    private void launchBuffer(@Nullable EditorBuffer b, List<String> nodeArgs) {
        if (b != null && b.isHttpFile()) {
            ops.debugHttpRequest(); // its scripts, around the request at the caret
            return;
        }
        // A file no adapter can debug is told so first: an untitled one used to get a Save As dialog, and then
        // a shell script "Debugging is unavailable for this file (check Settings → Debugging)", which no
        // setting can change.
        if (b != null && b.isShell()) {
            host.setStatus(tr("status.debug.shellNotDebuggable"));
            return;
        }
        if (b != null && b.getPath() == null && !DapServerRegistry.isDebuggable(b.getLanguage())) {
            host.setStatus(tr("status.debug.languageNotDebuggable"));
            return;
        }
        if (b == null || b.getPath() == null && !ops.saveBuffer(b)) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        String language = DapServerRegistry.adapterLanguage(b.getLanguage()); // a .ts file: JavaScript
        if (!debugEffectiveFor(language, b.getPath())) {
            host.setStatus(unavailableMessage(language, b.getPath()));
            return;
        }
        if ((b.isDirty() || b.getPath() == null) && !ops.saveBuffer(b)) {
            return;
        }
        Path path = b.getPath();
        if (path == null) {
            return; // saved just above, so it has one
        }
        // A Clojure file of a Leiningen, deps.edn or bb.edn project, a file of a Scala CLI project: its tool loads
        // the project, not just the file — the workspace-trust gate first, as Run asks it, so "no" starts nothing.
        if (!trusted(com.editora.run.RunTrust.forFile(
                language, path, b.getContent(), null, java.nio.file.Files::isRegularFile))) {
            return;
        }
        b.settleRunScan(); // compact-source detection is the load's off-FX scan; decide from its result
        Integer shebangSource = b.getShebangJavaSource();
        if ("java".equals(language) && b.isCompactSource() && shebangSource != null && shebangSource < 25) {
            host.setStatus(tr("status.debug.needSource25", shebangSource));
            return;
        }
        // The debuggee gets the same per-file program arguments the Run feature uses, read the same way.
        List<String> fileArgs = debugArgs(ops.programArgs(path));
        if (fileArgs == null) {
            return;
        }
        ops.openToolWindow();
        nameSession(path.getFileName().toString());
        // Said once it runs: "Saved …" or the last run's exit stood in the status bar while it was paused (choice 12).
        s().announceStart = true;
        dap().setProgramArgs(fileArgs);
        dap().setVmArgs(""); // no user VM args on a plain debug
        dap().setHostJavaExec("");
        if (b.isPython()) {
            planPython(path, firstLine(b.getContent()));
        }
        dap().setJsProjectRoot(ops.projectRoot()); // a JavaScript file's Node.js, package root, workspace
        dap().setJsNodeArgs(nodeArgs);
        dap().setJsProgram(null); // the file itself, not a configuration's planned launch
        if ("javascript".equals(language) && com.editora.process.ExecTargets.runsHere(path)) {
            // Planned exactly as Run plans it (review D104, D106): the same Node.js, flags, environment and
            // folder — and for a TypeScript file whatever Run runs for it (tsx, or the compiled file).
            Path launchedJs = path;
            s().relaunch = () -> relaunchFor(launchedJs, again -> launchBuffer(again, nodeArgs));
            debugJsFile(path, fileArgs, nodeArgs);
            return;
        }
        if ("go".equals(language)) {
            debugGoFile(b, path, fileArgs);
            return;
        }
        if ("rust".equals(language)) {
            debugRustFile(b, path, fileArgs);
            return;
        }
        if ("cpp".equals(language)) {
            debugNativeFile(b, path, fileArgs);
            return;
        }
        if ("csharp".equals(language)) {
            debugCsharpFile(path, fileArgs);
            return;
        }
        if ("ruby".equals(language) || "php".equals(language)) {
            debugScriptFile(path, language, fileArgs);
            return;
        }
        com.editora.dap.DebugLanguage own = com.editora.dap.DebugLanguages.forLanguage(b.getLanguage());
        if (own != null && com.editora.dap.DebugLanguages.custom().contains(own)) {
            debugCustomFile(b, path, own, fileArgs);
            return;
        }
        // Re-anchor closed files' breakpoints first (off-thread) so the initial setBreakpoints arms them too.
        boolean java = "java".equals(language);
        Path projectRoot = java ? JavaProjectRoot.find(path) : null;
        boolean compactSource = java && b.isCompactSource();
        // A plain-folder project window: its program runs in the project root, like Run's (review A16).
        Path project = java ? JavaLaunchPrep.projectOf(path, projectRoot, ops.projectRoot()) : null;
        if (java && !saveJavaBuffers(path, project, false)) { // the launched file was saved above
            return;
        }
        if (projectRoot != null && !compactSource) {
            // A saved configuration for this class is how the project runs it — its before-launch build,
            // arguments, environment, working directory — exactly as Run ▶ uses it (review D1).
            String ownMain = ownMainClass(b.getContent());
            RunConfiguration configured = ownMain == null
                    ? null
                    : com.editora.run.JavaLaunchPlan.configurationFor(ownMain, ops.runConfigurations());
            if (configured != null) {
                debugConfig(configured);
                return;
            }
        }
        // A remote file's program runs on its host: none of this machine's JDK setting, PATH or JAVA_HOME.
        // A Groovy script runs on the Settings JDK too (its JAVA_HOME), as Run runs it.
        boolean jvmScript = com.editora.run.JvmScriptRun.supports(language);
        String jdkHome = presentJdk(
                !(java || jvmScript) || !com.editora.process.ExecTargets.runsHere(path)
                        ? ""
                        : compactSource || jvmScript
                                ? host.settings().getMavenJdkHome()
                                : configuredJdkHome(projectRoot, null));
        String selectedJava = com.editora.run.JdkToolchain.javaExecutable(jdkHome);
        // A Java file without a build tool runs on Run's JDK: the Settings JDK, else PATH's java (review 2, B102).
        String javaExec = java && projectRoot == null && com.editora.process.ExecTargets.runsHere(path)
                ? looseJava(selectedJava)
                : selectedJava;
        dap().setEnv(com.editora.run.JdkToolchain.environment(
                jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        Path launched = path;
        s().relaunch = () -> relaunchFor(launched, again -> launchBuffer(again, nodeArgs));
        if ("kotlin".equals(language)
                && com.editora.run.KotlinRun.shape(launched.getFileName().toString())
                        == com.editora.run.KotlinRun.Shape.PROGRAM) {
            // Compiled first (into the Debug console), then `java -jar` with the agent: Restart compiles an edit.
            debugKotlinProgram(
                    launched,
                    null,
                    javaExec,
                    com.editora.run.JdkToolchain.environment(
                            jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
            return;
        }
        Path looseCwd = projectRoot == null ? project : null;
        Runnable start = () -> withClosedBreakpoints(() -> {
            Path file = b.getPath(); // read when the launch starts, after any build
            if (file == null) {
                return;
            }
            if (compactSource && shebangSource != null) {
                dap().startCompactShebang(file, shebangSource, javaExec);
            } else if (compactSource && file.getFileName().toString().endsWith(".java")) {
                dap().startCompactSource(file, javaExec);
            } else if (java) {
                dap().startLaunch(file, this::pickMainClass, javaExec, projectRoot, looseCwd);
            } else {
                dap().startLaunch(file, language, this::pickMainClass, javaExec, projectRoot);
            }
        });
        if (projectRoot != null && !compactSource) {
            buildThenLaunch(path, start);
        } else if (java && !compactSource) {
            looseBuildThenLaunch(path, start); // a loose file or a plain folder is built too (review 2, A101)
        } else {
            start.run();
        }
    }

    // --- Java launch preparation (review A2, B3, D1) ------------------------------------------------------

    /**
     * {@code home}, or "" — PATH's java — when it names a JDK that is no longer there: the Debug console and the
     * status bar say so and where to choose another, where java-debug's raw "Cannot run program" stood beside an
     * empty console (review 3, A206).
     */
    private String presentJdk(String home) {
        if (!com.editora.run.JdkToolchain.isGone(home)) {
            return home;
        }
        String said = tr("debug.console.defaultJdkMissing", home.strip());
        panel().clearConsole(); // this launch's console: kept when its session starts
        panel().appendNotice(said);
        s().consoleStarted = true;
        host.setStatus(said);
        return "";
    }

    /** The {@code java} a file without a build tool runs on: {@code selected} (the Settings JDK's), else PATH's. */
    private static String looseJava(String selected) {
        if (selected != null && !selected.isBlank()) {
            return selected;
        }
        List<String> onPath = com.editora.process.ProcessRunner.resolveExecutable(List.of("java"));
        return com.editora.dap.LooseJavaLaunch.launchJava(selected, onPath.isEmpty() ? null : onPath.get(0));
    }

    /**
     * {@link #buildThenLaunch} for a Java file with no Maven or Gradle build — a loose file or a plain-folder
     * project (review 2, A101). jdtls built that folder's classes once, at import, and nothing rebuilt them: every
     * later launch ran old code, and a file that did not compile ran as "Unresolved compilation problem". Its
     * source folder is corrected first (a packaged class in a folder jdtls guessed wrong would not build), then
     * jdtls builds it, and only a launch whose build succeeded may use jdtls's output
     * ({@link DapManager#setLooseClassesBuilt}); any other compiles the file with javac from its declared source
     * root, and javac's errors — only those the program reaches — stop it ({@link JavaLaunchPrep#afterBuild}).
     */
    private void looseBuildThenLaunch(Path routing, Runnable launch) {
        lsp.ensureManaged(routing);
        Object token = new Object();
        s().pendingBuild = token;
        showPreparing(routing); // from the first moment: the source-folder step can wait seconds on jdtls (C203)
        DebugSession session = s();
        com.editora.dap.JavaSourceRootFix.correct(
                lspManager,
                routing,
                com.editora.dap.JavaSourceRootFix.declaredPackage(readQuietly(routing)),
                () -> session.pendingBuild == token,
                asThisSession(fixed -> {
                    if (s().pendingBuild == token) {
                        buildThenLaunch(routing, launch, true);
                    }
                }));
    }

    /**
     * The build before {@code routing}'s launch has begun: the Debug window opens with "Building…", a Stop that cancels
     * it and the spinner, and F5 there says the launch is still starting (review 3, C203, C204).
     */
    private void showPreparing(Path routing) {
        boolean shown = s().pendingBuildFile != null && samePath(s().pendingBuildFile, routing);
        s().pendingBuildFile = routing;
        ops.openToolWindow();
        panel().setPreparing(true);
        debugLoading(true);
        updateDebugAvailability();
        if (!shown) {
            host.setStatus(tr("status.debug.building", String.valueOf(routing.getFileName())));
        }
    }

    /** Drops the build before a launch, if one is running: it finishes on its own, but nothing is launched. */
    private void dropPendingBuild() {
        if (s().pendingBuild == null) {
            return;
        }
        s().pendingBuild = null;
        s().pendingBuildFile = null;
        panel().setPreparing(false);
        debugLoading(false);
        updateDebugAvailability();
    }

    /** The fully-qualified name of the first {@code main} in {@code source}, or null. */
    private static @Nullable String ownMainClass(String source) {
        List<com.editora.run.MainMethodScanner.MainMethod> mains =
                com.editora.run.MainMethodScanner.scan(source == null ? "" : source);
        return mains.isEmpty() ? null : mains.get(0).fqn();
    }

    /**
     * Saves every unsaved Java file of {@code project} (the launched file's folder tree without one) before a
     * Java launch: the program is compiled from what is on disk, and a breakpoint in an unsaved file would sit
     * on lines the program does not have (review B3). False when a save failed or was cancelled.
     */
    private boolean saveJavaBuffers(Path launched, @Nullable Path project, boolean includeLaunched) {
        List<EditorBuffer> dirty = new ArrayList<>();
        host.forEachBuffer(buf -> {
            if (buf.isDirty()
                    && (includeLaunched || !samePath(buf.getPath(), launched))
                    && JavaLaunchPrep.saveBeforeLaunch(buf.getPath(), buf.getLanguage(), launched, project)) {
                dirty.add(buf);
            }
        });
        for (EditorBuffer buf : dirty) {
            if (!ops.saveBuffer(buf)) {
                return false;
            }
        }
        return true;
    }

    /**
     * Builds {@code project} with jdtls ({@code java/buildWorkspace}, what VS Code's build before launch does)
     * and runs {@code launch} when it compiled. Editora runs jdtls with autobuild off, so nothing else writes
     * the class files a project launch runs: a fresh project ended in ClassNotFoundException and an edited one
     * ran its old code under breakpoints placed on the new lines (review A2). Compile errors in the project are
     * listed in the Debug console (each a link to its line) and nothing is launched. A build that cannot run
     * launches anyway; the launch then checks the class files itself (missing: refused; older: a warning).
     */
    private void buildThenLaunch(Path routing, Runnable launch) {
        buildThenLaunch(routing, launch, false);
    }

    /** As above; {@code loose}: a file without a build tool, whose launch is told whether its build succeeded. */
    private void buildThenLaunch(Path routing, Runnable launch, boolean loose) {
        Object token = new Object();
        s().pendingBuild = token;
        lsp.ensureManaged(routing);
        showPreparing(routing);
        lspManager.buildJavaWorkspaceForLaunch(routing, asThisSession(result -> {
            if (s().pendingBuild != token) {
                return; // stopped, or replaced by another launch
            }
            s().pendingBuild = null;
            s().pendingBuildFile = null;
            panel().setPreparing(false);
            debugLoading(false);
            JavaLaunchPrep.AfterBuild next = JavaLaunchPrep.afterBuild(result.outcome(), loose);
            if (next == JavaLaunchPrep.AfterBuild.REFUSE) {
                showBuildErrors(routing, result.errors(), BUILD_ERROR_WAITS);
                return;
            }
            if (loose) { // without jdtls's output, javac compiles it from its declared source root (review 3, A201)
                dap().setLooseClassesBuilt(next == JavaLaunchPrep.AfterBuild.LAUNCH_BUILT);
            }
            launch.run();
        }));
    }

    /**
     * Lists a failed build's compile errors in the Debug console and says so; nothing is launched. jdtls's
     * workspace is this project. Its build reports the errors of files that are not open as it finds them; an
     * open file's come from the editor's copy (the Problems view has them, and the copy on disk is the same:
     * every Java file of the project was saved first) — and may still be on their way, so with none known yet
     * this looks again a few times before saying only that there are errors.
     */
    private void showBuildErrors(Path routing, Map<Path, List<com.editora.text.LspDiagnostic>> published, int waits) {
        Map<Path, List<com.editora.text.LspDiagnostic>> found = new java.util.TreeMap<>(lsp.problems());
        found.putAll(published);
        // Only this launch's project: the Problems window holds every open file's, of every language (review 2, A104).
        Path project = JavaLaunchPrep.projectOf(routing, JavaProjectRoot.find(routing), ops.projectRoot());
        java.util.function.Predicate<Path> scope = JavaLaunchPrep.errorScope(routing, project);
        int errors = JavaLaunchPrep.javaErrorCount(found, scope);
        List<String> importErrors = JavaLaunchPrep.importErrors(found, project);
        if (errors == 0 && importErrors.isEmpty() && waits > 0) {
            javafx.animation.PauseTransition later =
                    new javafx.animation.PauseTransition(javafx.util.Duration.millis(250));
            later.setOnFinished(e -> showBuildErrors(routing, published, waits - 1));
            later.play();
            return;
        }
        panel().clearConsole();
        s().consoleStarted = false;
        List<String> lines = JavaLaunchPrep.javaErrorLines(found, scope, ops.projectRoot(), MAX_BUILD_ERROR_LINES);
        for (String line : lines) {
            panel().appendOutput(line + "\n", "stderr");
        }
        if (errors > lines.size()) {
            panel().appendNotice(tr("debug.console.moreBuildErrors", errors - lines.size()));
        }
        // A failed Maven/Gradle import is no compile error: it says so, and where its JDK is chosen (review 2, A103).
        for (String message : importErrors) {
            panel().appendOutput(message + "\n", "stderr");
        }
        boolean importFailed = errors == 0 && !importErrors.isEmpty();
        panel().appendNotice(tr(importFailed ? "debug.console.importFailed" : "debug.console.buildFailed"));
        host.setStatus(
                importFailed
                        ? tr("status.debug.importFailed")
                        : errors > 0 ? tr("status.debug.buildErrors", errors) : tr("status.debug.buildErrorsUnlisted"));
        panel().setLaunchRefused(tr(importFailed ? "debugpanel.state.importFailed" : "debugpanel.state.buildFailed"));
        updateDebugAvailability();
    }

    private static String readQuietly(Path file) {
        try {
            return java.nio.file.Files.size(file) > 2_000_000 ? "" : java.nio.file.Files.readString(file);
        } catch (java.io.IOException | RuntimeException e) {
            return "";
        }
    }

    /** How many times a failed build's errors are looked for again before the console says only that there are some. */
    private static final int BUILD_ERROR_WAITS = 8;

    /** How many compile errors the Debug console lists before saying how many more there are. */
    private static final int MAX_BUILD_ERROR_LINES = 50;

    /**
     * Why {@code language} cannot be debugged here, as the user can act on it: Python or JavaScript debugging
     * turned off, or its adapter not found (with the command that installs it). One message used to cover all
     * of these, and every other reason too.
     */
    String unavailableMessage(@Nullable String language, @Nullable Path file) {
        boolean local = file == null || com.editora.process.ExecTargets.runsHere(file);
        if (!local || !debugSupportEnabled()) {
            return tr("status.debug.unavailable");
        }
        String id = language == null ? "" : DapServerRegistry.adapterLanguage(language);
        if ("java".equals(id)) {
            return javaUnavailableMessage();
        }
        if ("cpp".equals(id) && gdbForCpp() && host.settings().isCppDebugEnabled()) {
            return tr("status.debug.gdbMissing");
        }
        DebugAdapterSettings.Binding settings = DebugAdapterSettings.of(id);
        if (settings == null || settings.offKey() == null || settings.missing() == null) {
            return tr("status.debug.unavailable");
        }
        var s = host.settings();
        return !settings.isEnabled(s)
                ? tr(settings.offKey())
                : settings.missing().apply(s, host.projectScope());
    }

    /**
     * Which piece of Java debugging is missing, as the user can act on it (review A7): it runs through the Java
     * language server, so LSP being off, the Java server being off or not installed, and the java-debug plugin
     * missing each have their own message. "Check Settings → Debugging" covered them all, and that page shows
     * none of the first three.
     */
    private String javaUnavailableMessage() {
        if (!lspEnabled()) {
            return tr("status.debug.javaNeedsLsp");
        }
        if (!lsp.serverEnabled("java")) {
            return tr("status.debug.javaServerOff");
        }
        if (!lsp.isServerAvailable("java")) {
            return tr("status.debug.jdtlsMissing");
        }
        if (!dap().isAdapterAvailable()) {
            return tr("status.debug.javaDebugMissing");
        }
        return tr("status.debug.unavailable");
    }

    /**
     * Debug Test at Caret on a pytest file ({@code test_*.py}, {@code *_test.py}): the test at the caret (its class
     * with {@code classLevel}, the whole file outside any test) runs under pytest in debugpy. False — nothing
     * done — for any other buffer, which the caller then handles as before.
     */
    boolean debugPythonTestAtCaret(@Nullable EditorBuffer b, boolean classLevel) {
        if (b == null
                || b.getPath() == null
                || !b.isPython()
                || !com.editora.test.PytestScanner.isTestFile(
                        b.getPath().getFileName().toString())) {
            return false;
        }
        int caret = b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
        com.editora.test.PytestScanner.TestTarget target = com.editora.test.PytestScanner.atLine(b.getContent(), caret);
        if (classLevel && target != null && target.className() != null) {
            target = new com.editora.test.PytestScanner.TestTarget(
                    target.line(), target.endLine(), target.className(), null);
        }
        debugPythonTest(b, target);
        return true;
    }

    /**
     * Debugs {@code target} of pytest file {@code b} ({@code null}: the whole file): {@code python -m pytest
     * <node id>} under debugpy, from the project root (the file's folder with no project), under the file's
     * interpreter. Without pytest, a {@code unittest.TestCase} test runs under {@code python -m unittest}
     * instead. Public for the per-test gutter ▶'s Debug entry ({@code TestNavigationCoordinator}).
     */
    public void debugPythonTest(EditorBuffer b, com.editora.test.PytestScanner.@Nullable TestTarget target) {
        Path file = b == null ? null : b.getPath();
        if (file == null) {
            host.setStatus(tr("status.debug.saveFirst"));
            return;
        }
        Path root = pythonRootOf(file);
        Path cwd = root != null ? root : file.getParent();
        if (cwd == null) {
            return; // a saved file is always in a folder
        }
        String relative = cwd.relativize(file).toString().replace('\\', '/');
        debugPythonTests(
                file,
                cwd,
                List.of(com.editora.test.PytestScanner.nodeId(relative, target)),
                unittestFallback(b.getContent(), relative, target),
                file.getFileName() + (target == null ? "" : " :: " + target.displayName()));
    }

    /**
     * Pure: the pytest test a Python test glyph stands for. The glyphs carry {@link
     * com.editora.test.JavaTestScanner.TestTarget}s ({@code RunScan.pythonTestLines}): a class (method null), a
     * module-level function (class {@code ""}), or — class {@code ""} and no method — the whole file (null here).
     */
    static com.editora.test.PytestScanner.@Nullable TestTarget pytestTarget(
            String source, com.editora.test.JavaTestScanner.TestTarget glyph) {
        if (glyph == null || (glyph.className().isEmpty() && glyph.methodName() == null)) {
            return null;
        }
        com.editora.test.PytestScanner.TestTarget found = com.editora.test.PytestScanner.atLine(source, glyph.line());
        int end = found == null ? glyph.line() : found.endLine();
        return new com.editora.test.PytestScanner.TestTarget(
                glyph.line(), end, glyph.className().isEmpty() ? null : glyph.className(), glyph.methodName());
    }

    /**
     * Debugs Python tests by id in {@code cwd}, under {@code anchor}'s interpreter (the test file, or the folder
     * a run was in): pytest node ids — with {@code unittestIds} to fall back on when the interpreter has no
     * pytest — or, with no pytest ids, {@code python -m unittest} on {@code unittestIds} (a run that was
     * unittest's). What a Test Results row's Debug and a test ▶'s Debug Test come to.
     */
    void debugPythonTests(
            Path anchor, Path cwd, List<String> pytestIds, List<String> unittestIds, @Nullable String label) {
        if (!debugEffectiveFor("python", anchor)) {
            host.setStatus(unavailableMessage("python", anchor));
            return;
        }
        EditorBuffer open = ops.bufferForPath(anchor);
        if (open != null && open.isDirty() && !ops.saveBuffer(open)) {
            return;
        }
        List<String> fallback = unittestIds == null ? List.of() : unittestIds;
        ops.openToolWindow();
        nameSession(label);
        dap().setProgramArgs(List.of());
        planPython(anchor, open == null ? null : firstLine(open.getContent()));
        s().relaunch = () -> debugPythonTests(anchor, cwd, pytestIds, unittestIds, label);
        withClosedBreakpoints(() -> {
            if (pytestIds == null || pytestIds.isEmpty()) {
                dap().startPythonModule(anchor, "unittest", fallback, cwd);
            } else {
                dap().startPythonTest(anchor, pytestIds, fallback, cwd);
            }
        });
    }

    /**
     * Hands the next Python launch Run's plan inputs ({@link com.editora.run.PythonRunPlan}) for {@code file}:
     * its project root, the interpreter picked for the project, the interpreter setting where it runs (a remote
     * host's {@code python} tool), its first line (a shebang) — and that machine. Debug and Run then start the
     * same Python with the same {@code PYTHONPATH}, here, on a host, in WSL or in a container.
     */
    private void planPython(Path file, @Nullable String firstLine) {
        Path dir = file.getParent() == null ? file : file.getParent();
        dap().setPythonPlan(
                        com.editora.run.PythonRunPlan.Request.file(
                                file,
                                pythonRootOf(file),
                                com.editora.process.ExecTargets.forPath(file).toTargetPath(file),
                                List.of(),
                                firstLine,
                                ops.pythonInterpreterChoice(),
                                PythonRunLauncher.setting(dir, host.settings())),
                        PythonRunLauncher.hostFor(dir));
    }

    private static @Nullable String firstLine(String text) {
        if (text == null) {
            return null;
        }
        int nl = text.indexOf('\n');
        return nl < 0 ? text : text.substring(0, nl);
    }

    /** This window's project root when {@code file} is inside it, else null. */
    private @Nullable Path pythonRootOf(Path file) {
        Path root = ops.projectRoot();
        return root != null
                        && file.toAbsolutePath()
                                .normalize()
                                .startsWith(root.toAbsolutePath().normalize())
                ? root
                : null;
    }

    /**
     * Pure: the {@code python -m unittest} arguments that run {@code target} when pytest is missing — only for a
     * {@code unittest.TestCase} class (or a method of one), named as a dotted path from the folder it runs in;
     * empty when unittest cannot run it.
     */
    static List<String> unittestFallback(
            String source, String relative, com.editora.test.PytestScanner.@Nullable TestTarget target) {
        if (target == null || target.className() == null || !relative.endsWith(".py")) {
            return List.of();
        }
        String[] lines = source.split("\n", -1);
        String header = target.line() < lines.length ? lines[target.line()] : "";
        if (!header.contains("TestCase")) {
            // the method's class header: the nearest "class Name" above
            header = "";
            for (int i = Math.min(target.line(), lines.length - 1); i >= 0; i--) {
                if (lines[i].stripLeading().startsWith("class " + target.className())) {
                    header = lines[i];
                    break;
                }
            }
        }
        if (!header.contains("TestCase")) {
            return List.of();
        }
        String module = relative.substring(0, relative.length() - 3).replace('/', '.');
        return List.of(
                module + "." + target.className() + (target.functionName() == null ? "" : "." + target.functionName()));
    }

    /** Restart of a session started from {@code file}'s tab: repeat it there, or plainly if the tab is gone. */
    private void relaunchFor(Path file, Consumer<EditorBuffer> launch) {
        EditorBuffer b = ops.bufferForPath(file);
        if (b != null) {
            launch.accept(b);
        } else {
            dap().restart();
        }
    }

    /**
     * {@code debug.mainClass}: debug an arbitrary main class in the active file's Maven/Gradle project (not
     * just the active file's own). Requires a Java file open in a Maven/Gradle project to route jdtls; jdtls
     * enumerates every project main class, the user picks one (when several), and it launches with the project
     * root as the working directory.
     */
    void debugMainClass() {
        startMainClassDebug(null);
    }

    /** Debugs a specific main class by fully-qualified name (the gutter ▶ / editor menu on a {@code main}). */
    void debugMainClassNamed(String fqn) {
        startMainClassDebug(fqn);
    }

    /**
     * Debugs a saved {@link RunConfiguration} — its main class, program args, VM args, environment and
     * working directory, and its before-launch step.
     *
     * <p>Java, Groovy, Clojure and Scala: the other script types run but do not debug yet.
     */
    void debugConfig(RunConfiguration cfg) {
        if (cfg.isDeployed()) {
            ops.debugDeployed(cfg); // the program runs on the host; the debugger attaches through a tunnel
            return;
        }
        if (com.editora.run.ScriptRunCommand.isJvmScript(cfg.type())) {
            debugJvmScriptConfig(cfg);
            return;
        }
        if (com.editora.run.ScriptRunCommand.NODE.equals(cfg.type())
                || com.editora.run.ScriptRunCommand.NPM.equals(cfg.type())) {
            debugNodeConfig(cfg); // planned as Run plans it (NodeRunPlan / the package manager)
            return;
        }
        if (!cfg.isJava()) {
            // Script types run but do not debug yet. Say so plainly: falling through would resolve a Java
            // main class that a Python configuration does not have and report a confusing Java error.
            host.setStatus(
                    com.editora.run.ScriptRunCommand.SHELL.equals(cfg.type())
                            ? tr("status.debug.shellNotDebuggable")
                            : tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type." + cfg.type())));
            return;
        }
        // Same guard as the Run path: a blank main class is an internal NPE from jdtls, not an error.
        if (cfg.missingMainClass()) {
            host.setStatus(tr("status.run.configNeedsMainClass", cfg.name()));
            ops.editConfiguration(cfg.name()); // take them to the field that would make it run
            return;
        }
        if (cfg.mainClassLooksLikeAFile()) { // see the Run path: an empty classpath, not an error
            host.setStatus(tr("status.run.mainClassIsAFile", cfg.mainClass()));
            ops.editConfiguration(cfg.name());
            return;
        }
        // Same reasoning as RunCoordinator.runConfig: a named configuration must not depend on which tab is
        // in front, only on a Java file being open somewhere in its project.
        Path routing = RunCoordinator.routingFor(host, cfg);
        if (!debugEffectiveFor("java", routing)) { // a remote project is debugged on its host (review A9)
            host.setStatus(unavailableMessage("java", routing));
            return;
        }
        if (routing == null) {
            host.setStatus(tr("status.run.configNeedsJavaFile"));
            return;
        }
        Path buildRoot = JavaProjectRoot.find(routing);
        Path root = JavaLaunchPrep.projectOf(routing, buildRoot, ops.projectRoot());
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (!saveJavaBuffers(routing, root, true)) {
            return; // every unsaved Java file of the project is written first (review B3)
        }
        // One plan for Run and Debug (review D9): working directory, JDK, environment, arguments.
        com.editora.run.JavaLaunchPlan.Result planned = com.editora.run.JavaLaunchPlan.forConfiguration(
                cfg,
                com.editora.run.JavaLaunchPlan.Inputs.of(root, host.settings().getMavenJdkHome()));
        com.editora.run.JavaLaunchPlan plan = planned.plan();
        if (plan == null) { // not ok: the plan names its problem
            if (planned.problem() instanceof com.editora.run.JavaLaunchPlan.Problem problem) {
                String quote = String.valueOf(problem.quote());
                host.setStatus(
                        !problem.envFile().isEmpty()
                                ? tr("status.run.envFileUnreadable", cfg.name(), problem.envFile())
                                : problem.field() == com.editora.run.JavaLaunchPlan.Field.ENV
                                        ? tr("status.run.envUnclosedQuote", cfg.name(), quote)
                                        : tr("status.run.argsUnclosedQuote", quote));
            }
            ops.editConfiguration(cfg.name());
            return;
        }
        // The same gate the Run path has always had: a failed build aborts the launch rather than debugging
        // the previous class files, where every breakpoint would sit on a stale line number.
        //
        // Placed after the guards above rather than around the whole method — unlike Run, which wraps its
        // dispatch — because those guards reject a configuration that cannot launch at all (a script type, a
        // blank main class), and spending a multi-minute build on one before saying so is worse than not
        // building.
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        // Restart reads the configuration again: an edit made since the first launch applies (review A14).
        String name = cfg.name();
        s().relaunch = () -> debugConfig(currentConfiguration(name, cfg));
        boolean plainFolder = buildRoot == null;
        Runnable launch = () -> {
            // routing may be a background tab whose server start was deferred; open it on jdtls first, or the
            // resolve below comes back "no language server for file" while jdtls is running perfectly.
            lsp.ensureManaged(routing);
            dap().resolveMainClasses(routing, options -> {
                DapManager.MainClassOption match = matchConfiguredClass(options, cfg.mainClass(), plainFolder);
                if (match == null) {
                    host.setStatus(
                            options.isEmpty()
                                    ? tr("status.debug.noMainClassYet")
                                    : tr("status.debug.configClassNotFound", cfg.name(), cfg.mainClass()));
                    return;
                }
                ops.openToolWindow();
                nameSession(shortName(match.mainClass()));
                dap().setProgramArgs(plan.args());
                dap().setVmArgs(plan.vmArgs());
                dap().setEnv(plan.environment());
                // On a remote host the plan names the host's configured JDK, as Run uses it (review D9).
                boolean onHost = !com.editora.process.ExecTargets.runsHere(routing);
                dap().setHostJavaExec(onHost ? plan.javaExecutable() : "");
                String java = onHost ? "" : plainFolder ? looseJava(plan.javaExecutable()) : plan.javaExecutable();
                withClosedBreakpoints(() -> dap().startLaunchMainClass(routing, match, plan.cwd(), java, plainFolder));
            });
        };
        // A before-launch step builds the project the user's way, in the project root (review A12); without
        // one, jdtls builds it (review A2). A plain folder is built by jdtls after any step (review 2, A101).
        boolean hasStep = cfg.beforeLaunch() != null && !cfg.beforeLaunch().isBlank();
        Path toolRoot = buildRoot != null ? buildRoot : root;
        beforeLaunch()
                .run(
                        host,
                        cfg,
                        tool -> ops.buildExecutable(
                                tool, toolRoot), // a symbolic step (maven:compile) runs the tree's Maven
                        root,
                        plan.jdkEnvironment(),
                        beforeLaunchConsole(),
                        () -> {
                            if (plainFolder) {
                                looseBuildThenLaunch(routing, launch); // after its own step too: jdtls's output (A101)
                            } else if (!hasStep) {
                                buildToolThenLaunch(
                                        buildRoot, routing, shortName(cfg.mainClass()), plan.jdkEnvironment(), launch);
                            } else { // the user's step built it; a Gradle launch still runs jdtls's bin/main (A202)
                                afterToolBuild(
                                                buildRoot == null ? null : BuildBeforeLaunch.toolAt(buildRoot),
                                                routing,
                                                launch)
                                        .run();
                            }
                        });
    }

    /**
     * The build before a main-class launch in a Maven or Gradle project: with "Build before Run and Debug" on,
     * the build tool's compile ({@code test-compile} / {@code compileTestJava} for a class under
     * {@code src/test}) in the Debug console, through the executable the tasks tree uses, aborting the launch
     * when it fails (review D2) — the tool is the authority on how the project compiles, where jdtls's own
     * build knows nothing of annotation processors or generated sources; with the setting off, jdtls's
     * workspace build as before (review A2). {@code source} decides the scope; {@code env} is the JDK's.
     */
    private void buildToolThenLaunch(
            @Nullable Path buildRoot, Path source, String label, Map<String, String> env, Runnable launch) {
        com.editora.build.BuildTool tool = buildRoot != null && com.editora.process.ExecTargets.runsHere(buildRoot)
                ? BuildBeforeLaunch.toolAt(buildRoot)
                : null;
        if (tool == null
                || buildRoot == null
                || !BuildBeforeLaunch.wanted(host.settings().isBuildBeforeRun(), "", tool)) {
            buildThenLaunch(source, launch);
            return;
        }
        List<String> executable = ops.buildExecutable(tool, buildRoot);
        if (executable == null) {
            return; // the trust gate said why
        }
        List<String> argv =
                BuildBeforeLaunch.compileArgv(tool, executable, BuildBeforeLaunch.isTestSource(source, buildRoot));
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(label, argv, "status.run.buildBeforeLaunch"),
                        buildRoot,
                        env,
                        beforeLaunchConsole(),
                        afterToolBuild(tool, source, launch));
        updateDebugAvailability();
    }

    /**
     * Whether a debug launch that loads {@code gate}'s project may start: that tool's tasks tree asks for workspace
     * trust as it would before its own build ({@link Ops#buildExecutable}), as Run does
     * ({@code RunCoordinator.trusted}). True when there is no gate. Declining starts nothing; the gate said why.
     */
    private boolean trusted(com.editora.run.RunTrust.@Nullable Gate gate) {
        return gate == null || ops.buildExecutable(gate.tool(), gate.root()) != null;
    }

    /** {@code launch}, after jdtls's build too when the launch runs jdtls's output, not the tool's (Gradle, A202). */
    private Runnable afterToolBuild(com.editora.build.@Nullable BuildTool tool, Path source, Runnable launch) {
        return BuildBeforeLaunch.jdtlsBuildsAfter(tool) ? () -> buildThenLaunch(source, launch) : launch;
    }

    /**
     * Debugs a Groovy, Clojure or Scala script configuration: its script, arguments, folder, environment, JDK
     * and VM arguments (where the tool hands them to its JVM, beside the debugger's agent: Scala CLI's as
     * {@code --java-opt}), after its before-launch step.
     * On this machine only: a configuration whose folder is on a remote host runs there but does not debug yet.
     */
    private void debugJvmScriptConfig(RunConfiguration cfg) {
        List<String> args = debugArgs(cfg.args());
        if (args == null) {
            return;
        }
        Path root = ops.projectRoot();
        Path cwd = com.editora.run.RunConfigWorkingDirectory.resolve(cfg, root);
        Path script = cwd == null ? null : com.editora.run.RunConfigWorkingDirectory.script(cfg, root, cwd);
        if (cwd == null || script == null) {
            host.setStatus(tr("status.run.configNeedsTarget", cfg.name()));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (!com.editora.process.ExecTargets.runsHere(script)) {
            host.setStatus(tr("debug.error.remoteLanguage"));
            return;
        }
        if (!java.nio.file.Files.isRegularFile(script)) {
            host.setStatus(tr("status.run.scriptMissing", cfg.name(), script.toString()));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (!debugEffectiveFor(cfg.type(), script)) {
            host.setStatus(unavailableMessage(cfg.type(), script));
            return;
        }
        java.util.Map<String, String> env;
        try {
            env = com.editora.run.EnvVars.resolve(cfg.env(), RunCoordinator.inheritedVariables(false), cwd);
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.envUnclosedQuote", cfg.name(), String.valueOf(e.quote())));
            ops.editConfiguration(cfg.name());
            return;
        } catch (com.editora.run.EnvVars.EnvFileException e) {
            host.setStatus(
                    tr("status.run.envFileUnreadable", cfg.name(), e.file().toString()));
            ops.editConfiguration(cfg.name());
            return;
        }
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        // A Clojure file's project (project.clj, deps.edn, bb.edn) or a Scala CLI project folder is loaded with it:
        // that tool's workspace-trust gate first, as Run asks it, so "no" starts nothing.
        com.editora.run.RunTrust.Gate gate = com.editora.run.ScriptRunCommand.SCALA.equals(cfg.type())
                ? com.editora.run.RunTrust.scalaCli(
                        com.editora.run.ScalaRun.ownerOf(script, null, java.nio.file.Files::isRegularFile))
                : com.editora.run.JvmScriptRun.trustGate(
                        cfg.type(), script, cfg.workingDir().isBlank() ? null : cwd);
        if (!trusted(gate)) {
            return;
        }
        String jdkHome = cfg.jdkHome().isBlank() ? host.settings().getMavenJdkHome() : cfg.jdkHome();
        java.util.Map<String, String> launchEnv = new java.util.LinkedHashMap<>(
                com.editora.run.JdkToolchain.environment(jdkHome, com.editora.process.ProcessRunner.augmentedPath()));
        launchEnv.putAll(env);
        // A Kotlin program is started by `java` itself: its VM arguments go on that command line.
        boolean program = com.editora.run.ScriptRunCommand.KOTLIN.equals(cfg.type())
                && com.editora.run.KotlinRun.shape(script.getFileName().toString())
                        == com.editora.run.KotlinRun.Shape.PROGRAM;
        java.util.Map<String, String> programEnv = launchEnv;
        // A Clojure file runs in its project's folder unless the configuration names one.
        Path folder =
                cfg.workingDir().isBlank() && com.editora.run.ScriptRunCommand.CLOJURE.equals(cfg.type()) ? null : cwd;
        String name = cfg.name();
        s().relaunch = () -> debugConfig(currentConfiguration(name, cfg));
        beforeLaunch()
                .run(
                        host,
                        cfg,
                        cwd,
                        com.editora.run.JdkToolchain.environment(
                                jdkHome, com.editora.process.ProcessRunner.augmentedPath()),
                        beforeLaunchConsole(),
                        () -> {
                            ops.openToolWindow();
                            nameSession(cfg.name());
                            dap().setProgramArgs(args);
                            if (program || com.editora.run.JvmScriptRun.takesJvmOptionsInArgv(cfg.type())) {
                                // `java` (a Kotlin program) and Scala CLI take VM arguments on their command line.
                                dap().setVmArgs(com.editora.run.ProgramArgs.tokenize(cfg.vmArgs()));
                            } else {
                                dap().setVmArgs(cfg.vmArgs()
                                        .strip()); // placed where the tool reads them (JvmScriptRun)
                            }
                            dap().setHostJavaExec("");
                            dap().setEnv(programEnv);
                            if (program) {
                                debugKotlinProgram(
                                        script, cwd, com.editora.run.JdkToolchain.javaExecutable(jdkHome), programEnv);
                            } else {
                                withClosedBreakpoints(() -> dap().startJvmScript(script, cfg.type(), folder));
                            }
                        });
    }

    /**
     * Debugs Kotlin program {@code source}: compiled by {@code kotlinc} into its cached jar (in the Debug
     * console, as a step Stop can end) unless this content's jar is there, then run as {@code java -jar} with the
     * debugger's agent, in {@code cwd} (null: its folder), on {@code java}. The arguments, VM arguments and
     * environment are already set on the session.
     */
    private void debugKotlinProgram(Path source, @Nullable Path cwd, String java, java.util.Map<String, String> env) {
        String kotlinc = com.editora.run.JvmTools.locate(com.editora.run.KotlinRun.TOOL);
        if (kotlinc == null) {
            host.setStatus(tr(com.editora.run.JvmScriptRun.missingToolKey("kotlin")));
            return;
        }
        com.editora.run.KotlinBuild.Plan plan;
        try {
            plan = com.editora.run.KotlinBuild.plan(source, kotlinc, host.configDir());
        } catch (java.io.IOException e) {
            host.setStatus(tr("debug.error.startFailed", e.getMessage()));
            return;
        }
        String javaExec = java == null || java.isBlank() ? "java" : java;
        Runnable start = () -> {
            ops.openToolWindow();
            withClosedBreakpoints(() -> dap().startJvmProgram(source, "kotlin", cwd, plan.jar(), javaExec));
        };
        if (plan.compile().isEmpty()) {
            start.run();
            return;
        }
        if (beforeLaunchActive() || s().pendingBuild != null) {
            host.setStatus(tr("status.run.busy"));
            return;
        }
        beforeLaunch()
                .run(
                        host,
                        new BeforeLaunchStep.Command(
                                source.getFileName().toString(), plan.compile(), "status.run.kotlinCompile"),
                        cwd != null ? cwd : source.getParent(),
                        env,
                        beforeLaunchConsole(),
                        () -> {
                            try {
                                com.editora.run.KotlinBuild.finish(plan);
                            } catch (java.io.IOException e) {
                                host.setStatus(tr("debug.error.startFailed", e.getMessage()));
                                return;
                            }
                            start.run();
                        });
        updateDebugAvailability();
    }

    /**
     * The language of {@code file} when Editora's own JVM adapter debugs it (Kotlin, Groovy) — an attach for it
     * goes there, since java-debug never binds a breakpoint outside a {@code .java} file; null otherwise. Pure.
     */
    static @Nullable String jvmAdapterLanguage(Path file) {
        if (file == null || file.getFileName() == null) {
            return null;
        }
        String language =
                com.editora.text.LanguageRegistry.forFileName(file.getFileName().toString());
        DapServerRegistry.DapServerSpec spec = DapServerRegistry.specFor(language);
        return spec != null && spec.kind() == DapServerRegistry.Kind.JVM ? language : null;
    }

    /** The saved configuration called {@code name} as it is now, else {@code fallback} (it was deleted). */
    private RunConfiguration currentConfiguration(String name, RunConfiguration fallback) {
        for (RunConfiguration c : ops.runConfigurations()) {
            if (c.name().equals(name)) {
                return c;
            }
        }
        return fallback;
    }

    /**
     * jdtls's entry for a configuration's {@code mainClass}. In a plain folder jdtls may know a packaged class
     * without its package (it guesses the source folder — see DapManager): the simple name then matches.
     */
    static DapManager.@Nullable MainClassOption matchConfiguredClass(
            List<DapManager.MainClassOption> options, String mainClass, boolean plainFolder) {
        for (DapManager.MainClassOption o : options) {
            if (mainClass.equals(o.className())) {
                return o;
            }
        }
        if (!plainFolder) {
            return null;
        }
        String simple = mainClass.substring(mainClass.lastIndexOf('.') + 1);
        for (DapManager.MainClassOption o : options) {
            String cls = o.className();
            if (cls != null && simple.equals(cls.substring(cls.lastIndexOf('.') + 1))) {
                return o;
            }
        }
        return null;
    }

    private void startMainClassDebug(@Nullable String targetFqn) {
        startMainClassDebug(host.activeBuffer(), targetFqn);
    }

    private void startMainClassDebug(@Nullable EditorBuffer b, @Nullable String targetFqn) {
        if (b != null && b.getLanguage() != null && !"java".equals(b.getLanguage())) {
            // Said first: "turn on LSP for Java" on a JavaScript file sent the user the wrong way (review D7).
            host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        if (!debugEffectiveFor("java", b == null ? null : b.getPath())) {
            host.setStatus(unavailableMessage("java", b == null ? null : b.getPath()));
            return;
        }
        if (b == null || b.getPath() == null || !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.debug.needJavaFile"));
            return;
        }
        Path routing = b.getPath();
        Path buildRoot = JavaProjectRoot.find(routing);
        // A plain-folder project window has main classes too (review A16).
        Path root = JavaLaunchPrep.projectOf(routing, buildRoot, ops.projectRoot());
        if (root == null) {
            host.setStatus(tr("status.debug.noProject"));
            return;
        }
        if (!saveJavaBuffers(routing, root, true)) {
            return;
        }
        boolean plainFolder = buildRoot == null;
        dap().resolveMainClasses(routing, options -> {
            if (options.isEmpty()) {
                host.setStatus(tr("status.debug.noMainClass"));
                return;
            }
            Consumer<DapManager.@Nullable MainClassOption> launch = opt -> {
                if (opt == null) {
                    return;
                }
                // The configuration for this class is how the project runs it, as for Run ▶ (review D1).
                RunConfiguration configured = plainFolder || opt.className() == null
                        ? null
                        : com.editora.run.JavaLaunchPlan.configurationFor(opt.className(), ops.runConfigurations());
                if (configured != null) {
                    debugConfig(configured);
                    return;
                }
                List<String> mainArgs = debugArgs(programArgsForMain(opt, routing));
                if (mainArgs == null) {
                    return;
                }
                ops.openToolWindow();
                nameSession(shortName(opt.mainClass()));
                dap().setProgramArgs(mainArgs);
                dap().setVmArgs(""); // the gutter/command debug carries no VM args/env
                dap().setHostJavaExec("");
                dap().setEnv(configuredJdkEnvironment(buildRoot, null));
                lsp.ensureManaged(routing); // see above
                s().relaunch = () -> relaunchFor(routing, again -> startMainClassDebug(again, opt.className()));
                String javaExec = configuredJavaExecutable(buildRoot, null);
                Runnable go = () -> withClosedBreakpoints(
                        () -> dap().startLaunchMainClass(routing, opt, root, javaExec, plainFolder));
                if (plainFolder) {
                    looseBuildThenLaunch(routing, go); // built like a loose file (review 2, A101)
                } else {
                    Path source =
                            opt.filePath() == null || opt.filePath().isBlank() ? routing : Path.of(opt.filePath());
                    buildToolThenLaunch(
                            buildRoot,
                            source,
                            shortName(opt.mainClass()),
                            configuredJdkEnvironment(buildRoot, null),
                            go);
                }
            };
            if (targetFqn != null) {
                DapManager.MainClassOption match = matchConfiguredClass(options, targetFqn, plainFolder);
                if (match == null) {
                    host.setStatus(tr("status.debug.noMainClass"));
                    return;
                }
                launch.accept(match);
            } else if (options.size() == 1) {
                launch.accept(options.get(0));
            } else {
                pickMainClass(options, launch);
            }
        });
    }

    /** Per-main-class program args, reusing the per-file store keyed by the main class's own source file. */
    private String programArgsForMain(DapManager.MainClassOption opt, Path routing) {
        if (opt.filePath() == null || opt.filePath().isBlank()) {
            return "";
        }
        try {
            // jdtls names the file as the machine it runs on does (a remote host's, a container's path).
            return ops.programArgs(
                    com.editora.process.ExecTargets.forPath(routing).toPath(opt.filePath()));
        } catch (RuntimeException e) {
            return "";
        }
    }

    /** The default JDK for a loose file or Maven project; a saved configuration may override it. */
    private String configuredJdkHome(@Nullable Path root, @Nullable RunConfiguration cfg) {
        if (root != null && !com.editora.process.ExecTargets.runsHere(root)) {
            return ""; // a JDK setting names a folder on this machine; the jdtls where it runs resolves its own
        }
        if (root != null && !java.nio.file.Files.isRegularFile(root.resolve("pom.xml"))) {
            return "";
        }
        return com.editora.run.JdkToolchain.effectiveHome(
                cfg == null ? "" : cfg.jdkHome(), host.settings().getMavenJdkHome());
    }

    private String configuredJavaExecutable(@Nullable Path root, @Nullable RunConfiguration cfg) {
        String selected = com.editora.run.JdkToolchain.javaExecutable(presentJdk(configuredJdkHome(root, cfg)));
        // No build tool (a plain folder): the Settings JDK, else PATH's java, as Run (review 2, B102).
        return root == null ? looseJava(selected) : selected;
    }

    private java.util.Map<String, String> configuredJdkEnvironment(
            @Nullable Path root, @Nullable RunConfiguration cfg) {
        String home = configuredJdkHome(root, cfg);
        return com.editora.run.JdkToolchain.environment(
                com.editora.run.JdkToolchain.isGone(home) ? "" : home,
                com.editora.process.ProcessRunner.augmentedPath());
    }

    /** The simple class name of a fully-qualified main class (for the session label). */
    private static String shortName(@Nullable String fqn) {
        if (fqn == null) {
            return "";
        }
        int dot = fqn.lastIndexOf('.');
        return dot < 0 ? fqn : fqn.substring(dot + 1);
    }

    /** Whether two paths refer to the same file (normalized absolute comparison; null-safe). */
    private static boolean samePath(@Nullable Path a, @Nullable Path b) {
        return a != null && b != null && PathKeys.sameNormalized(a, b);
    }

    /** Resumes and stops at the active buffer's caret line via a one-shot temporary breakpoint. */
    void debugRunToCursor() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        int line = lineMap(b).toLaunched(fileLineAtCaret(b));
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        dap().runToCursor(b.getPath(), line);
    }

    /** Debug: Show Numbers as Hexadecimal — on or off, in Variables, watches, hover and inline values. */
    void toggleHex() {
        panel().toggleHexValues();
    }

    /** Debug: Force Run to Cursor — Run to Cursor with every breakpoint on the way ignored. */
    void debugForceRunToCursor() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        int line = lineMap(b).toLaunched(fileLineAtCaret(b));
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        dap().forceRunToCursor(b.getPath(), line);
    }

    /** Jump to Line: move the execution pointer to the caret line without executing in-between code.
     *  Capability-gated — debugpy supports it; java-debug/js-debug report "not supported". */
    void debugJumpToLine() {
        EditorBuffer b = host.activeBuffer();
        if (b == null || b.getPath() == null || dap().state() != DapManager.State.SUSPENDED) {
            return;
        }
        if (!dap().supportsJumpToLine()) {
            host.setStatus(tr("status.debug.jumpUnsupported"));
            return;
        }
        int line = lineMap(b).toLaunched(fileLineAtCaret(b));
        if (line < 0) {
            host.setStatus(tr("status.debug.lineAddedSinceLaunch"));
            return;
        }
        dap().jumpToLine(
                        b.getPath(),
                        line,
                        err -> host.setStatus(err.isEmpty() ? tr("status.debug.jumpNoTarget") : err));
    }

    /** The caret's line in the whole file (a narrowed buffer's paragraphs are region-relative). */
    private static int fileLineAtCaret(EditorBuffer b) {
        return b.getFocusedArea().getCurrentParagraph()
                + b.getBreakpointManager().regionFirstLine();
    }

    /**
     * Attaches to an already-suspended JVM on {@code host:port} without prompting — used by the Test Results
     * "Debug Test" action, where Surefire/Gradle forked the test JVM suspended and printed its JDWP port.
     * {@code anchorFile} names the debug session and anchors breakpoint resolution.
     */
    void attachToPort(Path anchorFile, String attachHost, int port) {
        attachToPort(anchorFile, attachHost, port, null);
    }

    /**
     * JVMs of the same run that printed their banner while one of them was being debugged (several surefire
     * forks, a multi-module build), attached in turn as each session ends (review D11). Each waits suspended.
     */
    private final java.util.ArrayDeque<Runnable> queuedAttaches = new java.util.ArrayDeque<>();

    /**
     * As above; {@code stopOwner} ends the test or build run that started the JVM: Stop on this session runs
     * it after detaching, so Stop ends the program as it does every other session's — detaching alone left
     * the test or app running on (review A4).
     */
    void attachToPort(Path anchorFile, String attachHost, int port, @Nullable Runnable stopOwner) {
        if (s().relaunch == ONE_SHOT_ATTACH && dap().isActive()) {
            queuedAttaches.add(() -> attachToPort(anchorFile, attachHost, port, stopOwner));
            host.setStatus(tr("status.debug.attachQueued", port));
            return;
        }
        if (s().pendingAttachStop != null) {
            setPendingAttach(null); // the wait is over: from here Stop goes through the session (ownerStop)
        }
        s().ownerStop = stopOwner;
        s().announceStart = stopOwner != null; // "Attaching the debugger on port …" would outlive it (review 2, A105)
        String jvmLanguage = jvmAdapterLanguage(anchorFile);
        if (jvmLanguage != null) {
            // A Kotlin (or Groovy) program of a build: Editora's JVM adapter, which binds by source name.
            if (!debugEffectiveFor(jvmLanguage, anchorFile)) {
                host.setStatus(unavailableMessage(jvmLanguage, anchorFile));
                return;
            }
            ops.openToolWindow();
            nameSession(anchorFile.getFileName().toString());
            s().relaunch = ONE_SHOT_ATTACH;
            withClosedBreakpoints(() -> dap().startJvmAttach(anchorFile, jvmLanguage, attachHost, port));
            return;
        }
        // On a remote anchor's host the adapter attaches from there: "localhost" is the host itself.
        if (!debugEffectiveFor("java", anchorFile)) {
            host.setStatus(unavailableMessage("java", anchorFile));
            return;
        }
        ops.openToolWindow();
        nameSession(anchorFile == null ? "" : anchorFile.getFileName().toString());
        // The anchor names the session, but the adapter is started through jdtls, which only answers for a
        // document it has open: a test class found on disk (Debug Test with no tab for it) has no session.
        Path routing = attachRouting(anchorFile);
        lsp.ensureManaged(routing); // an open tab whose server start was deferred
        s().relaunch = ONE_SHOT_ATTACH; // the run that owns this JVM has to be started again instead
        withClosedBreakpoints(() -> dap().startAttach(routing, attachHost, port));
    }

    /** The open Java file an attach anchored at {@code anchor} is routed through — see {@link #attachRouting}. */
    private @Nullable Path attachRouting(Path anchor) {
        List<Path> openJava = new ArrayList<>();
        host.forEachBuffer(b -> {
            // Java files whose tools run where the anchor's do: the attach goes through that machine's jdtls.
            if (b.getPath() != null
                    && "java".equals(b.getLanguage())
                    && (anchor == null
                            ? host.isLocalBuffer(b) && com.editora.process.ExecTargets.runsHere(b.getPath())
                            : com.editora.process.ExecTargets.forPath(b.getPath())
                                    .id()
                                    .equals(com.editora.process.ExecTargets.forPath(anchor)
                                            .id()))) {
                openJava.add(b.getPath());
            }
        });
        return attachRouting(anchor, openJava, JavaProjectRoot.find(anchor), host::isLspManaged);
    }

    /**
     * Pure: the file whose jdtls session starts the debug adapter for an attach. The anchor itself when it is
     * open in a tab; otherwise an open Java file of the anchor's project (one already on the server first),
     * else any open Java file already on the server, else the anchor — which then fails with the precise
     * "no language server for file" rather than attaching through an unrelated project.
     */
    static @Nullable Path attachRouting(
            Path anchor, List<Path> openJavaFiles, @Nullable Path projectRoot, Predicate<Path> managed) {
        if (anchor == null) {
            return openJavaFiles.stream().filter(managed).findFirst().orElse(null);
        }
        Path sameProject = null;
        Path anyManaged = null;
        for (Path open : openJavaFiles) {
            if (PathKeys.sameNormalized(open, anchor)) {
                return anchor;
            }
            boolean onServer = managed.test(open);
            if (projectRoot != null && open.toAbsolutePath().normalize().startsWith(projectRoot)) {
                if (sameProject == null || (onServer && !managed.test(sameProject))) {
                    sameProject = open;
                }
            } else if (onServer && anyManaged == null) {
                anyManaged = open;
            }
        }
        return sameProject != null ? sameProject : anyManaged != null ? anyManaged : anchor;
    }

    /** What Attach offers when this project has attached nowhere yet: the JDWP default. */
    private static final String DEFAULT_ATTACH_ADDRESS = "localhost:5005";

    /** The most addresses Attach remembers per project. */
    static final int MAX_ATTACH_TARGETS = 8;

    /** Pure: {@code address} first, then the earlier ones without it, at most {@link #MAX_ATTACH_TARGETS}. */
    static List<String> rememberAttachTarget(List<String> earlier, String address) {
        List<String> out = new ArrayList<>();
        out.add(address);
        for (String a : earlier) {
            if (out.size() >= MAX_ATTACH_TARGETS) {
                break;
            }
            if (a != null && !a.isBlank() && !a.equalsIgnoreCase(address)) {
                out.add(a);
            }
        }
        return out;
    }

    /** The JVM adapter language of the active file Attach starts from, or null when it cannot (it says why). */
    private @Nullable String attachLanguage(@Nullable EditorBuffer b) {
        if (b == null || b.getPath() == null) {
            host.setStatus(tr("status.debug.saveFirst"));
            return null;
        }
        String jvmLanguage = jvmAdapterLanguage(b.getPath());
        if (jvmLanguage == null && !"java".equals(b.getLanguage())) {
            host.setStatus(tr("status.debug.attachLanguages"));
            return null;
        }
        String adapterLanguage = jvmLanguage != null ? jvmLanguage : "java";
        if (!debugEffectiveFor(adapterLanguage, b.getPath())) { // a remote file attaches from its host (review A9)
            host.setStatus(unavailableMessage(adapterLanguage, b.getPath()));
            return null;
        }
        return adapterLanguage;
    }

    /**
     * Debug ▸ Attach: picks an address this project attached to before (most recent first), or asks for another,
     * pre-filled with the most recent one.
     */
    void debugAttach() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && "javascript".equals(DapServerRegistry.adapterLanguage(b.getLanguage()))) {
            attachToNode(b); // review D7: a Node.js inspector, not a JVM
            return;
        }
        String language = attachLanguage(b);
        if (b == null || language == null) {
            return;
        }
        List<String> earlier = ops.debugAttachTargets();
        if (earlier.isEmpty()) {
            promptAttachAddress(b, language, DEFAULT_ATTACH_ADDRESS);
            return;
        }
        List<String> rows = new ArrayList<>(earlier);
        rows.add(""); // "Another address…"
        rows.add(PROCESS_ROW); // "A program on this machine…"
        QuickOpen<String> picker = new QuickOpen<>(
                tr("dialog.debug.attachTitle"),
                tr("debug.attach.pickPrompt"),
                () -> rows,
                a -> a.isEmpty() ? tr("debug.attach.another") : PROCESS_ROW.equals(a) ? tr("debug.attach.process") : a,
                a -> "",
                a -> {
                    if (a == null) {
                        return;
                    }
                    if (a.isEmpty()) {
                        promptAttachAddress(b, language, earlier.get(0));
                    } else if (PROCESS_ROW.equals(a)) {
                        debugAttachToProcess();
                    } else {
                        attachTo(b, language, a);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** The Attach picker's row that opens Attach to Process (no address can look like it). */
    private static final String PROCESS_ROW = "\u0000process";

    /**
     * Debug: Attach to Process — the Java, Node.js and Python programs running on this machine, those listening for
     * a debugger first (a JVM's JDWP agent, Node's inspector, debugpy's {@code --listen}); picking one attaches the
     * right debugger to it. One started without debugging says what to start it with.
     */
    void debugAttachToProcess() {
        host.setStatus(tr("status.debug.scanningProcesses"));
        Thread.ofPlatform().daemon().name("editora-process-scan").start(() -> {
            List<com.editora.dap.LocalProcesses.Target> found;
            try {
                found = com.editora.dap.LocalProcesses.scan();
            } catch (RuntimeException e) {
                found = List.of();
            }
            List<com.editora.dap.LocalProcesses.Target> targets = found;
            Platform.runLater(() -> showProcessPicker(targets)); // for the session shown when the scan ends
        });
    }

    private void showProcessPicker(List<com.editora.dap.LocalProcesses.Target> targets) {
        host.setStatus(null);
        QuickOpen<com.editora.dap.LocalProcesses.Target> picker = new QuickOpen<>(
                tr("debug.attachProcess.title"),
                tr("debug.attachProcess.prompt"),
                () -> targets,
                t -> tr("debug.attachProcess.row", t.name(), String.valueOf(t.pid())),
                DebugCoordinator::processDetail,
                t -> {
                    if (t != null) {
                        attachToProcess(t);
                    }
                });
        if (targets.isEmpty()) {
            picker.setPlaceholder(tr("debug.attachProcess.none"));
        }
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** A row's second line: which debugger, and where it listens — or what to start the program with. Pure. */
    static String processDetail(com.editora.dap.LocalProcesses.Target t) {
        String kind = tr("debug.attachProcess.kind." + t.kind().name().toLowerCase(java.util.Locale.ROOT));
        if (t.kind().byPid()) {
            return tr(
                    t.kind() == com.editora.dap.LocalProcesses.Kind.GO
                            ? "debug.attachProcess.byPid"
                            : "debug.attachProcess.byPidLldb",
                    kind);
        }
        return t.attachable()
                ? tr("debug.attachProcess.listening", kind, t.address())
                : tr("debug.attachProcess.notListening", kind, startFlag(t.kind()));
    }

    /** What a program of {@code kind} is started with so a debugger can attach. Pure. */
    static String startFlag(com.editora.dap.LocalProcesses.Kind kind) {
        return switch (kind) {
            case JAVA -> "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=5005";
            case NODE -> "--inspect";
            case PYTHON -> "-m debugpy --listen 5678";
            case GO, RUST, NATIVE -> "";
        };
    }

    /** Attaches the debugger of {@code t}'s kind to it, or says what to start it with when it is not listening. */
    void attachToProcess(com.editora.dap.LocalProcesses.Target t) {
        if (!t.attachable()) {
            host.setStatus(tr("status.debug.processNotListening", t.name(), startFlag(t.kind())));
            return;
        }
        switch (t.kind()) {
            case JAVA -> {
                EditorBuffer anchor = jvmAnchor();
                if (anchor == null) {
                    host.setStatus(tr("status.debug.attachNeedsJavaFile"));
                    return;
                }
                String language = attachLanguage(anchor);
                if (language != null) {
                    attachTo(anchor, language, t.address());
                }
            }
            case NODE -> {
                if (!debugEffectiveFor("javascript")) {
                    host.setStatus(unavailableMessage("javascript", null));
                    return;
                }
                EditorBuffer b = host.activeBuffer();
                Path dir = b != null && b.getPath() != null ? b.getPath().getParent() : ops.projectRoot();
                String label = t.name() + " (" + t.pid() + ")";
                ops.openToolWindow();
                nameSession(label);
                s().relaunch = null; // an attach is re-attached as it was
                withClosedBreakpoints(() -> dap().startJsAttach(
                                t.host(),
                                t.port(),
                                label,
                                dir,
                                List.of(com.editora.dap.JsLaunch.NODE_INTERNALS),
                                false));
            }
            case PYTHON -> {
                if (!debugSupportEnabled()) {
                    host.setStatus(tr("statusbar.tip.debugDisabled"));
                    return; // the program brings its own debugpy: none is needed here
                }
                EditorBuffer b = host.activeBuffer();
                Path active = b != null && "python".equals(b.getLanguage()) ? b.getPath() : null;
                Path root = ops.projectRoot();
                Path anchor = active != null ? active : root != null ? root : Path.of(System.getProperty("user.home"));
                ops.openToolWindow();
                nameSession(t.name() + " (" + t.pid() + ")");
                s().relaunch = null;
                withClosedBreakpoints(() -> dap().startPythonAttach(anchor, t.host(), t.port()));
            }
            case GO -> attachToGo(t.pid(), t.name());
            case RUST -> attachToNative(t.pid(), t.name(), com.editora.dap.DebugLanguages.RUST);
            case NATIVE -> attachToNative(t.pid(), t.name(), com.editora.dap.DebugLanguages.CPP);
        }
    }

    /**
     * Attaches CodeLLDB to native program {@code pid} of this machine (Rust's or C and C++'s, {@code language}): Stop
     * detaches and leaves it running. The session is anchored at the active file of that language, else the project.
     */
    private void attachToNative(long pid, String name, com.editora.dap.DebugLanguage language) {
        if (!debugEffectiveFor(language.id())) {
            host.setStatus(unavailableMessage(language.id(), null));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        Path active = b != null && language.editorLanguages().contains(b.getLanguage()) ? b.getPath() : null;
        Path root = ops.projectRoot();
        Path anchor = active != null ? active : root != null ? root : Path.of(System.getProperty("user.home"));
        java.util.Map<String, Object> attach = com.editora.dap.NativeBuild.attach(pid, language.id());
        ops.openToolWindow();
        nameSession(name + " (" + pid + ")");
        Runnable start = () -> withClosedBreakpoints(() -> dap().startExternal(anchor, language, attach));
        s().relaunch = start; // attaches again
        start.run();
    }

    /**
     * Attaches Delve to Go program {@code pid} of this machine ({@code dlv attach}): Stop detaches and leaves it running.
     * The session is anchored at the active Go file, else the project; breakpoints are its Go files'.
     */
    private void attachToGo(long pid, String name) {
        if (!debugEffectiveFor("go")) {
            host.setStatus(unavailableMessage("go", null));
            return;
        }
        EditorBuffer b = host.activeBuffer();
        Path active = b != null && "go".equals(b.getLanguage()) ? b.getPath() : null;
        Path root = ops.projectRoot();
        Path anchor = active != null ? active : root != null ? root : Path.of(System.getProperty("user.home"));
        java.util.Map<String, Object> attach = com.editora.dap.GoLaunch.attach(pid);
        ops.openToolWindow();
        nameSession(name + " (" + pid + ")");
        Runnable start = () ->
                withClosedBreakpoints(() -> dap().startExternal(anchor, com.editora.dap.DebugLanguages.GO, attach));
        s().relaunch = start; // attaches again
        start.run();
    }

    /**
     * The file a JVM attach is made from: the active one when it is Java (or another JVM language), else the first
     * such tab — java-debug resolves the attached program's classes through that file's project.
     */
    private @Nullable EditorBuffer jvmAnchor() {
        EditorBuffer active = host.activeBuffer();
        if (isJvmFile(active)) {
            return active;
        }
        EditorBuffer[] found = new EditorBuffer[1];
        host.forEachBuffer(b -> {
            if (found[0] == null && isJvmFile(b)) {
                found[0] = b;
            }
        });
        return found[0];
    }

    private static boolean isJvmFile(@Nullable EditorBuffer b) {
        return b != null
                && b.getPath() != null
                && ("java".equals(b.getLanguage()) || jvmAdapterLanguage(b.getPath()) != null);
    }

    /**
     * Debug: Reattach — attaches again to the address this project attached to last (Visual Studio's Reattach to
     * Process), without asking.
     */
    void debugReattach() {
        EditorBuffer b = host.activeBuffer();
        if (b != null && "javascript".equals(DapServerRegistry.adapterLanguage(b.getLanguage()))) {
            attachToNode(b);
            return;
        }
        String language = attachLanguage(b);
        if (b == null || language == null) {
            return;
        }
        List<String> earlier = ops.debugAttachTargets();
        if (earlier.isEmpty()) {
            host.setStatus(tr("status.debug.noEarlierAttach"));
            return;
        }
        attachTo(b, language, earlier.get(0));
    }

    private void promptAttachAddress(EditorBuffer b, String language, String initial) {
        host.promptText(
                tr("dialog.debug.attachTitle"),
                tr("dialog.debug.attachContent"),
                initial,
                input -> attachTo(b, language, input == null ? "" : input));
    }

    /** Attaches the {@code language} adapter to {@code address} ({@code host:port}, or a port on localhost). */
    private void attachTo(EditorBuffer b, String language, String address) {
        String jvmLanguage = "java".equals(language) ? null : language;
        String text = address.trim();
        String hostName = "localhost";
        String portStr = text;
        int colon = text.lastIndexOf(':');
        if (colon >= 0) {
            hostName = text.substring(0, colon);
            portStr = text.substring(colon + 1);
        }
        int port;
        try {
            port = Integer.parseInt(portStr.trim());
        } catch (NumberFormatException e) {
            host.setStatus(tr("status.debug.badAddress", text));
            return;
        }
        Path file = b.getPath(); // checked by attachLanguage; a saved buffer keeps a path
        if (file == null) {
            return;
        }
        // Offered first next time, in this project (review 2, A109: it was only kept for the window).
        ops.persistDebugAttachTargets(rememberAttachTarget(ops.debugAttachTargets(), text));
        ops.openToolWindow();
        nameSession(file.getFileName().toString());
        String attachHost = hostName;
        s().relaunch = null; // an attach is re-attached as it was
        if (jvmLanguage != null) {
            withClosedBreakpoints(() -> dap().startJvmAttach(file, jvmLanguage, attachHost, port));
        } else {
            withClosedBreakpoints(() -> dap().startAttach(file, attachHost, port));
        }
    }

    /**
     * Debug ▸ Attach on a JavaScript or TypeScript file: asks for the inspector's {@code host:port} (a Node.js started
     * with {@code --inspect} or {@code --inspect-brk}) and attaches vscode-js-debug to it, with every breakpoint.
     * Restart attaches again; Stop detaches.
     */
    private void attachToNode(EditorBuffer b) {
        if (!debugEffectiveFor("javascript")) {
            host.setStatus(unavailableMessage("javascript", null));
            return;
        }
        String defaultAddress = "localhost:" + com.editora.dap.JsLaunch.DEFAULT_INSPECTOR_PORT;
        host.promptText(
                tr("dialog.debug.attachNodeTitle"), tr("dialog.debug.attachNodeContent"), defaultAddress, input -> {
                    if (input == null) {
                        return; // cancelled
                    }
                    var address = com.editora.dap.JsLaunch.parseAddress(input);
                    if (address.isEmpty()) {
                        host.setStatus(tr("status.debug.badAddress", input.strip()));
                        return;
                    }
                    String label = address.get().host() + ":" + address.get().port();
                    Path dir = b.getPath() != null ? b.getPath().getParent() : ops.projectRoot();
                    ops.openToolWindow();
                    nameSession(label);
                    s().relaunch = null; // an attach is re-attached as it was
                    withClosedBreakpoints(() -> dap().startJsAttach(
                                    address.get().host(),
                                    address.get().port(),
                                    label,
                                    dir,
                                    List.of(com.editora.dap.JsLaunch.NODE_INTERNALS),
                                    false));
                });
    }

    /**
     * Debug on a {@code node} or {@code npm} run configuration: the same launch Run makes ({@link NodeDebugConfigs}),
     * under vscode-js-debug. Restart reads the configuration again.
     */
    private void debugNodeConfig(RunConfiguration cfg) {
        if (!debugEffectiveFor("javascript")) {
            host.setStatus(unavailableMessage("javascript", null));
            return;
        }
        String name = cfg.name();
        List<String> args;
        List<String> nodeOptions;
        try {
            args = com.editora.run.ProgramArgs.parse(cfg.args());
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.argsUnclosedQuote", String.valueOf(e.quote())));
            ops.editConfiguration(name);
            return;
        }
        try {
            nodeOptions = com.editora.run.ProgramArgs.parse(cfg.interpreterOptions());
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.optionsUnclosedQuote", name, String.valueOf(e.quote())));
            ops.editConfiguration(name);
            return;
        }
        Path root = ops.projectRoot();
        Path cwd = com.editora.run.RunConfigWorkingDirectory.resolve(cfg, root);
        if (cwd == null) {
            host.setStatus(tr("status.run.configNeedsWorkingDir", name));
            ops.editConfiguration(name);
            return;
        }
        if (!com.editora.process.ExecTargets.runsHere(cwd)) {
            host.setStatus(tr("status.debug.nodeConfigRemote", tr("settings.runConfig.type." + cfg.type())));
            return;
        }
        Map<String, String> env;
        try {
            env = com.editora.run.EnvVars.resolve(cfg.env(), RunCoordinator.inheritedVariables(false), cwd);
        } catch (com.editora.run.ProgramArgs.UnclosedQuoteException e) {
            host.setStatus(tr("status.run.envUnclosedQuote", name, String.valueOf(e.quote())));
            ops.editConfiguration(name);
            return;
        } catch (com.editora.run.EnvVars.EnvFileException e) {
            host.setStatus(tr("status.run.envFileUnreadable", name, e.file().toString()));
            ops.editConfiguration(name);
            return;
        }
        s().relaunch = () -> debugConfig(currentConfiguration(name, cfg));
        if (com.editora.run.ScriptRunCommand.NPM.equals(cfg.type())) {
            if (!trusted(com.editora.run.RunTrust.npm(cwd))) {
                return; // a package script runs its pre/post hooks: npm's workspace-trust gate, as Run asks it
            }
            // The project's own package manager, as Run and the npm tool window use it.
            var detected = com.editora.build.BuildTool.npmManager(cwd, root);
            String override = host.settings().getNpmCommand();
            List<String> manager = com.editora.build.BuildExecutable.resolve(
                    List.of(),
                    com.editora.build.NpmPackageManager.overrideApplies(override, detected.manager()) ? override : "",
                    detected.manager());
            Map<String, String> npmEnv = new LinkedHashMap<>(NpmRunSupport.environment(System.getenv("NO_COLOR")));
            npmEnv.putAll(env);
            startJsProgram(
                    cwd.resolve("package.json"),
                    NodeDebugConfigs.forPackageManager(
                            com.editora.run.ScriptRunCommand.npm(manager, cfg.target(), args), cwd, npmEnv),
                    name);
            return;
        }
        Path script = com.editora.run.RunConfigWorkingDirectory.script(cfg, root, cwd);
        if (script == null || !Files.isRegularFile(script)) {
            host.setStatus(tr("status.run.scriptMissing", name, String.valueOf(script)));
            ops.editConfiguration(name);
            return;
        }
        NodeRunLauncher.plan(
                new NodeRunLauncher.Spec(
                        script,
                        root,
                        cfg.interpreter(),
                        host.settings().getNodePath(),
                        args,
                        nodeOptions,
                        false,
                        env,
                        cfg.workingDir().isBlank() ? null : cwd),
                ready -> {
                    if (ready.plan().error() != null) {
                        host.setStatus(tr(ready.plan().error()));
                        return;
                    }
                    var program = NodeDebugConfigs.fromRunPlan(
                            ready.plan().argv(),
                            ready.target(), // the compiled .js when that runs instead of a .ts
                            ready.plan().env(),
                            ready.dir());
                    if (program == null) {
                        host.setStatus(
                                tr("status.debug.configTypeUnsupported", tr("settings.runConfig.type." + cfg.type())));
                        return;
                    }
                    startJsProgram(script, program, name);
                });
    }

    /**
     * Debug on a local JavaScript or TypeScript file: planned by {@link NodeRunLauncher} as Run plans it, then split
     * into js-debug's launch ({@link NodeDebugConfigs#fromRunPlan}). A plan js-debug cannot launch — a shebang
     * naming Deno or Bun — falls back to the file under the resolved Node.js.
     */
    private void debugJsFile(Path file, List<String> fileArgs, List<String> nodeArgs) {
        String problem = com.editora.dap.JsLaunch.launchProblemKey(String.valueOf(file.getFileName()), null);
        if (problem != null) {
            host.setStatus(tr(problem, "?")); // JSX/TSX: Node.js cannot run it, whatever its version
            return;
        }
        NodeRunLauncher.plan(
                new NodeRunLauncher.Spec(
                        file,
                        ops.projectRoot(),
                        "",
                        host.settings().getNodePath(),
                        fileArgs,
                        nodeArgs,
                        false,
                        Map.of(),
                        null),
                ready -> {
                    if (ready.plan().error() != null) {
                        host.setStatus(tr(ready.plan().error()));
                        return;
                    }
                    var program = NodeDebugConfigs.fromRunPlan(
                            ready.plan().argv(), file.toString(), ready.plan().env(), ready.dir());
                    if (program == null || !NodeDebugConfigs.debuggableRuntime(program.runtime())) {
                        withClosedBreakpoints(
                                () -> dap().startLaunch(file, "javascript", this::pickMainClass, "", null));
                        return;
                    }
                    dap().setProgramArgs(List.of());
                    dap().setJsNodeArgs(List.of());
                    dap().setJsProgram(program);
                    withClosedBreakpoints(() -> dap().startLaunch(file, "javascript", this::pickMainClass, "", null));
                });
    }

    /** Starts a planned JavaScript session called {@code label}, anchored at {@code anchor} (FX thread). */
    private void startJsProgram(Path anchor, com.editora.dap.DapManager.@Nullable JsProgram program, String label) {
        if (program == null) {
            return;
        }
        ops.openToolWindow();
        nameSession(label);
        dap().setProgramArgs(List.of());
        dap().setJsNodeArgs(List.of());
        dap().setJsProjectRoot(ops.projectRoot());
        dap().setJsProgram(program);
        withClosedBreakpoints(() -> dap().startLaunch(anchor, "javascript", this::pickMainClass, "", null));
    }

    /** Main-class chooser (QuickOpen) when jdtls finds several. */
    private void pickMainClass(
            List<DapManager.MainClassOption> options, Consumer<DapManager.@Nullable MainClassOption> chosen) {
        closeMainClassPicker();
        QuickOpen<DapManager.MainClassOption> picker = new QuickOpen<>(
                tr("debug.pickMainTitle"),
                tr("debug.pickMainPrompt"),
                () -> options,
                o -> o.mainClass() == null ? "" : o.mainClass(),
                o -> JavaLaunchPrep.projectLabel(o.projectName()),
                o -> chosen.accept(o));
        // Esc ends the launch it was asked for: it used to sit in "Starting…" with nothing left to start.
        picker.setOnCancelled(() -> chosen.accept(null));
        picker.setOverlayHost(host.overlayHost());
        mainClassPicker = picker;
        picker.show(host.window());
    }

    /** The main-class picker last shown, while it may still be open. */
    private @Nullable QuickOpen<DapManager.MainClassOption> mainClassPicker;

    /** Closes the main-class picker if it is still open; its answer goes to a launch that no longer waits. */
    private void closeMainClassPicker() {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        QuickOpen<DapManager.MainClassOption> picker = mainClassPicker;
        mainClassPicker = null;
        if (picker != null && picker.isShown()) {
            picker.hide();
        }
    }

    /** The main-class picker, while it is open (tests). */
    @Nullable
    QuickOpen<DapManager.MainClassOption> openMainClassPicker() {
        QuickOpen<DapManager.MainClassOption> picker = mainClassPicker;
        return picker != null && picker.isShown() ? picker : null;
    }

    /** Toggles a breakpoint on the active buffer's caret line. */
    void toggleBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b != null) {
            int line = b.getFocusedArea().getCurrentParagraph();
            if (breakpointAllowed(b, line)) {
                b.toggleBreakpoint(line);
            }
        }
    }

    /**
     * Whether a breakpoint may go on {@code line} of {@code b}: anywhere a debugger can stop, which in a {@code .http}
     * file is only a script line (adl, 2026-10-16) — else says so. Removing one is always allowed.
     */
    private boolean breakpointAllowed(EditorBuffer b, int line) {
        if (b.getBreakpointManager().isBreakpoint(line)) {
            return true;
        }
        if (b.isHttpFile()) {
            if (com.editora.http.HttpScriptLines.isScriptLine(b.getContent(), line)) {
                return true;
            }
            host.setStatus(tr("status.debug.httpScriptLinesOnly"));
            return false;
        }
        // B8: a blank or comment-only line has no code to stop on. java-debug even called such a breakpoint
        // verified, and it never stopped.
        if (com.editora.dap.CodeLessLines.isCodeLess(b.getContent(), line, b.getLanguage())) {
            host.setStatus(tr("status.debug.noCodeOnLine"));
            return false;
        }
        return true;
    }

    /**
     * The active buffer if breakpoints can be set in it, else {@code null} after saying why. A file with no
     * debug adapter has no breakpoint gutter: a breakpoint toggled there by key was invisible, could only be
     * removed by pressing the key again on the same line, and was still sent to the adapter in every session.
     */
    private @Nullable EditorBuffer breakpointBuffer() {
        EditorBuffer b = host.activeBuffer();
        if (b == null) {
            return null;
        }
        if (!debugSupportEnabled()) {
            host.setStatus(tr("statusbar.tip.debugDisabled"));
            return null;
        }
        if (!isDebuggableBuffer(b)) {
            host.setStatus(tr("status.debug.noBreakpointsHere"));
            return null;
        }
        return b;
    }

    /**
     * Edits the caret line's breakpoint (creating one if absent): its condition, its log message — which
     * makes it a logpoint: the adapter logs and does not suspend — and whether it is enabled. All three
     * already persist, re-anchor and reach the adapter; this form is what reaches them.
     */
    void editBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!breakpointAllowed(b, line)) {
            return;
        }
        var mgr = b.getBreakpointManager();
        // A line without a breakpoint gets one only when the form is accepted: creating it up front left it
        // behind — saved, and sent to a live session — when the form was cancelled.
        Breakpoint existing = mgr.get(line);
        boolean existed = existing != null;
        Breakpoint bp = existing != null
                ? existing
                : Breakpoint.plain(
                        line, b.getArea().getParagraph(line).getText().strip());

        TextField condition = new TextField(bp.condition());
        condition.setPrefColumnCount(32);
        TextField logMessage = new TextField(bp.logMessage());
        logMessage.setPrefColumnCount(32);
        // Completion in the stopped frame (follow-up 5): the whole condition, and the {expression} of a log message.
        DebugCompletion.attach(condition, completionSource(false));
        DebugCompletion.attach(logMessage, completionSource(true));
        TextField hitCondition = new TextField(bp.hitCondition());
        hitCondition.setPrefColumnCount(32);
        hitCondition.setPromptText(BreakpointFormText.hitConditionPrompt(b.getLanguage()));
        CheckBox enabled = new CheckBox(tr("dialog.debug.breakpointEnabled"));
        enabled.setSelected(bp.enabled());
        CheckBox temporary = new CheckBox(tr("dialog.debug.breakpointTemporary"));
        temporary.setSelected(bp.temporary());
        CheckBox recording = new CheckBox(tr("dialog.debug.breakpointRecording"));
        recording.setSelected(bp.recording());
        // Wait for: another breakpoint this one waits for in each session (a triggered breakpoint).
        javafx.scene.control.ComboBox<BreakpointsPicker.Entry> waitFor = waitForChoice(b, line, bp);

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        // The line the breakpoint is on: the card covers the editor, often that very line (review 2 C106).
        Label lineText = new Label(
                BreakpointFormText.lineText(b.getArea().getParagraph(line).getText(), 64));
        lineText.getStyleClass().add("debug-breakpoint-line");
        grid.add(lineText, 0, 0, 2, 1);
        grid.add(new Label(tr("dialog.debug.conditionLabel")), 0, 1);
        grid.add(condition, 1, 1);
        grid.add(new Label(tr("dialog.debug.logMessageLabel")), 0, 2);
        grid.add(logMessage, 1, 2);
        grid.add(new Label(tr("dialog.debug.hitConditionLabel")), 0, 3);
        grid.add(hitCondition, 1, 3);
        grid.add(new Label(tr("dialog.debug.triggeredByLabel")), 0, 4);
        grid.add(waitFor, 1, 4);
        grid.add(new javafx.scene.layout.HBox(16, enabled, temporary, recording), 1, 5);
        grid.add(note(tr("dialog.debug.logMessageHint")), 1, 6);
        GridPane.setHgrow(condition, Priority.ALWAYS);
        GridPane.setHgrow(logMessage, Priority.ALWAYS);

        OverlayInput.show(
                host.overlayHost(),
                BreakpointFormText.title(b.getPath(), line),
                grid,
                condition,
                tr("dialog.ok"),
                null,
                () -> {
                    if (!existed && !mgr.isBreakpoint(line)) {
                        b.toggleBreakpoint(line);
                    }
                    mgr.setCondition(line, condition.getText().trim());
                    mgr.setLogMessage(line, logMessage.getText().trim());
                    mgr.setHitCondition(line, hitCondition.getText().trim());
                    mgr.setEnabled(line, enabled.isSelected());
                    mgr.setTemporary(line, temporary.isSelected());
                    mgr.setRecording(line, recording.isSelected());
                    BreakpointsPicker.Entry trigger = waitFor.getValue();
                    if (trigger != null && trigger.line() < 0) {
                        trigger = null; // "no other breakpoint"
                    }
                    Breakpoint now = mgr.get(line);
                    String was = now == null ? "" : now.triggeredBy();
                    if (now != null
                            && !(trigger == null
                                    ? was.isEmpty()
                                    : was.equals(trigger.breakpoint().id()))) {
                        triggered.link(b, line, trigger);
                    }
                },
                null,
                false);
    }

    /**
     * The edit form's Wait for choice: no other breakpoint (a row with line -1), then each breakpoint the one on
     * {@code line} of {@code b} ({@code bp}) can wait for, with the one it waits for chosen.
     */
    private javafx.scene.control.ComboBox<BreakpointsPicker.Entry> waitForChoice(
            EditorBuffer b, int line, Breakpoint bp) {
        Path file = b.getPath();
        List<BreakpointsPicker.Entry> all = triggered.entries();
        List<BreakpointsPicker.Entry> choices = file == null
                ? List.of()
                : TriggeredBreakpoints.choices(
                        key(file), line + b.getBreakpointManager().regionFirstLine(), bp.id(), all);
        BreakpointsPicker.Entry none = new BreakpointsPicker.Entry(Path.of(""), "", -1, bp, null);
        javafx.scene.control.ComboBox<BreakpointsPicker.Entry> combo = new javafx.scene.control.ComboBox<>();
        combo.getItems().add(none);
        combo.getItems().addAll(choices);
        combo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(BreakpointsPicker.@Nullable Entry e) {
                return e == null || e.line() < 0 ? tr("dialog.debug.triggeredByNone") : BreakpointsPicker.label(e);
            }

            @Override
            public BreakpointsPicker.@Nullable Entry fromString(String text) {
                return null; // not editable
            }
        });
        combo.setValue(choices.stream()
                .filter(e -> bp.isTriggered()
                        && bp.triggeredBy().equals(e.breakpoint().id()))
                .findFirst()
                .orElse(none));
        combo.setMaxWidth(Double.MAX_VALUE);
        return combo;
    }

    /**
     * Debug: Add Triggered Breakpoint — a breakpoint on the caret line that waits, in each debug session, until the
     * breakpoint chosen from a list is hit (VS Code's Add Triggered Breakpoint, IntelliJ's "Disable until hitting the
     * following breakpoint"). On a line with a breakpoint it makes that one wait; choosing the one it waits for
     * already makes it wait for none.
     */
    void addTriggeredBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null || b.getPath() == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!breakpointAllowed(b, line)) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint existing = mgr.get(line);
        String selfId = existing == null ? "" : existing.id();
        String waitsFor = existing == null ? "" : existing.triggeredBy();
        List<BreakpointsPicker.Entry> choices = TriggeredBreakpoints.choices(
                key(b.getPath()), line + mgr.regionFirstLine(), selfId, triggered.entries());
        if (choices.isEmpty()) {
            host.setStatus(tr("status.debug.noTriggerChoices"));
            return;
        }
        QuickOpen<BreakpointsPicker.Entry> picker = new QuickOpen<>(
                tr("command.debug.addTriggeredBreakpoint"),
                tr("debug.triggered.prompt", line + 1),
                () -> choices,
                e -> (!waitsFor.isEmpty() && waitsFor.equals(e.breakpoint().id()) ? "✓ " : "    ")
                        + BreakpointsPicker.label(e),
                e -> String.join("  ·  ", com.editora.editor.debug.BreakpointDescription.lines(e.breakpoint())),
                e -> {
                    if (e != null) {
                        linkTriggered(b, line, e);
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /**
     * Makes the breakpoint on {@code line} of {@code b} (setting one when there is none) wait for {@code trigger}'s,
     * or — when it waits for that one already — for none; says which.
     */
    void linkTriggered(EditorBuffer b, int line, BreakpointsPicker.Entry trigger) {
        var mgr = b.getBreakpointManager();
        if (!mgr.isBreakpoint(line)) {
            b.toggleBreakpoint(line);
        }
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            return;
        }
        if (bp.isTriggered() && bp.triggeredBy().equals(trigger.breakpoint().id())) {
            triggered.link(b, line, null);
            host.setStatus(tr("status.debug.triggeredRemoved", line + 1));
            return;
        }
        triggered.link(b, line, trigger);
        host.setStatus(tr("status.debug.triggeredAdded", line + 1, TriggeredBreakpoints.place(trigger)));
    }

    /**
     * Debug: Add Logpoint — asks for the message to log on the caret line and makes that line a logpoint: the
     * adapter logs the message each time the line runs and the program never suspends (VS Code's Add Logpoint,
     * IntelliJ's Shift+click in the gutter). On a line that already has a breakpoint it edits that one's message,
     * keeping its condition and hit count.
     */
    void addLogpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b != null) {
            addLogpoint(b, b.getFocusedArea().getCurrentParagraph());
        }
    }

    /** {@link #addLogpointAtCaret} for {@code line} of {@code b} — also what a Shift+click in the gutter runs. */
    void addLogpoint(EditorBuffer b, int line) {
        if (!breakpointAllowed(b, line)) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint existing = mgr.get(line);
        TextField message = new TextField(existing == null ? "" : existing.logMessage());
        message.setPrefColumnCount(32);
        message.setPromptText(tr("dialog.debug.logpointPrompt"));
        DebugCompletion.attach(message, completionSource(true)); // the {expression}s, in the stopped frame
        Label lineText = new Label(
                BreakpointFormText.lineText(b.getArea().getParagraph(line).getText(), 64));
        lineText.getStyleClass().add("debug-breakpoint-line");
        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);
        grid.add(lineText, 0, 0, 2, 1);
        grid.add(new Label(tr("dialog.debug.logMessageLabel")), 0, 1);
        grid.add(message, 1, 1);
        grid.add(note(tr("dialog.debug.logpointHint")), 1, 2);
        GridPane.setHgrow(message, Priority.ALWAYS);
        // Nothing to log is not a logpoint: OK waits for a message rather than leaving a breakpoint that stops.
        var hasMessage = javafx.beans.binding.Bindings.createBooleanBinding(
                () -> !message.getText().isBlank(), message.textProperty());
        OverlayInput.show(
                host.overlayHost(),
                BreakpointFormText.logpointTitle(b.getPath(), line),
                grid,
                message,
                tr("dialog.ok"),
                hasMessage,
                () -> {
                    String text = message.getText().trim();
                    if (text.isEmpty()) {
                        return;
                    }
                    if (!mgr.isBreakpoint(line)) {
                        b.toggleBreakpoint(line);
                    }
                    mgr.setLogMessage(line, text);
                    b.refreshGutterLine(line);
                    host.setStatus(tr("status.debug.logpointAdded", line + 1));
                },
                null,
                false);
    }

    /**
     * A small muted hint label under a form field, wrapped at a fixed width. Left at its own preferred width a
     * wrapping label asks for its whole text on one line, and the overlay card hugs its content, so the hint
     * stretched the form across the window and squeezed the field labels to an ellipsis. (A width bound to the
     * field's instead changes mid-layout and leaves the card at a stale height.)
     */
    private static Label note(String text) {
        Label l = new Label(text);
        l.getStyleClass().add("settings-note");
        l.setWrapText(true);
        l.setPrefWidth(420);
        return l;
    }

    /**
     * Debug: Exception Breakpoints — a picker over the filters the adapter offers (the live session's
     * {@code exceptionBreakpointFilters}, else those that language's adapter is known to offer), each marked
     * when on; choosing one turns it on or off. A language starts with its adapter's defaults (debugpy: uncaught
     * exceptions), and a choice holds for that language's sessions from then on.
     */
    void toggleExceptionBreakpoints() {
        String language = exceptionLanguage();
        List<DapModels.ExceptionFilterOption> options =
                language == null ? List.of() : dap().exceptionFilterOptions(language);
        if (language == null || options.isEmpty()) {
            host.setStatus(tr("status.debug.exceptionsNoLanguage"));
            return;
        }
        QuickOpen<DapModels.ExceptionFilterOption> picker = new QuickOpen<>(
                tr("command.debug.toggleExceptionBreakpoints"),
                tr("debug.exceptions.prompt", debugAdapterLabel(language)),
                () -> options,
                o -> exceptionRow(language, o),
                DapModels.ExceptionFilterOption::filter,
                o -> {
                    if (o != null) {
                        toggleExceptionFilter(language, o.filter());
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** A picker row: the filter's label, marked when it is on for {@code language}. */
    String exceptionRow(String language, DapModels.ExceptionFilterOption o) {
        return (dap().exceptionFiltersFor(language).contains(o.filter()) ? "✓ " : "    ")
                + com.editora.dap.ExceptionFilterCatalog.displayLabel(o);
    }

    /** Turns {@code language}'s exception filter {@code filter} on or off (the picker's choice), and says so. */
    void toggleExceptionFilter(String language, @Nullable String filter) {
        restoreExceptionFilters();
        List<DapModels.ExceptionFilterOption> options = dap().exceptionFilterOptions(language);
        List<String> next = toggledFilter(dap().exceptionFiltersFor(language), filter, options);
        forEachSession(() -> dap().setExceptionFilters(language, next)); // each live session of that language
        ops.persistDebugExceptionFilters(dap().exceptionChoices()); // C12: kept across restarts, per project
        refreshExceptionToggles();
        host.setStatus(exceptionStatus(language, next, options));
    }

    /** The adapter language the exception picker is about: the live session's, else the active file's. */
    private @Nullable String exceptionLanguage() {
        if (dap().isActive()) {
            return dap().sessionLanguage();
        }
        EditorBuffer b = host.activeBuffer();
        String language = b == null ? null : b.getLanguage();
        return language != null && DapServerRegistry.isDebuggable(language)
                ? DapServerRegistry.adapterLanguage(language)
                : null;
    }

    // --- Caught / Uncaught exception toggles (Run menu + Debug window) ---------------------------------

    /** Whether the persisted exception choices have been handed to the debug manager. */
    private boolean exceptionFiltersRestored;

    /** Hands the exception choices this project saved to the debug manager, once (B10 / C12). */
    void restoreExceptionFilters() {
        if (exceptionFiltersRestored) {
            return;
        }
        exceptionFiltersRestored = true;
        ops.debugExceptionFilters().forEach((language, filters) -> {
            if (language != null && filters != null) {
                dap().setExceptionFilters(language, filters);
            }
        });
        if (ops.debugBreakpointsMuted() && !breakpointsMuted) {
            breakpointsMuted = true; // Mute Breakpoints is kept per project, like the exception choices
            dap().setExceptionsMuted(true);
            showBreakpointsMuted();
        }
        memberBreakpoints.push(); // the method breakpoints and field watchpoints this project keeps
    }

    /**
     * The language the Caught / Uncaught toggles act on: the live session's, else the active file's — and Java
     * when neither says (the toggles are always there to set up the next Java session).
     */
    String exceptionToggleLanguage() {
        String language = exceptionLanguage();
        return language == null || com.editora.dap.ExceptionFilterCatalog.caughtFilter(language) == null
                ? "java"
                : language;
    }

    /** Whether the Caught ({@code caught}) or Uncaught toggle is on for {@link #exceptionToggleLanguage()}. */
    boolean exceptionToggleOn(boolean caught) {
        restoreExceptionFilters();
        String language = exceptionToggleLanguage();
        String filter = caught
                ? com.editora.dap.ExceptionFilterCatalog.caughtFilter(language)
                : com.editora.dap.ExceptionFilterCatalog.uncaughtFilter(language);
        return filter != null && dap().exceptionFiltersFor(language).contains(filter);
    }

    /** The Caught or Uncaught toggle (commands {@code debug.exceptions.caught} / {@code .uncaught}, panel). */
    void toggleExceptionKind(boolean caught) {
        String language = exceptionToggleLanguage();
        String filter = caught
                ? com.editora.dap.ExceptionFilterCatalog.caughtFilter(language)
                : com.editora.dap.ExceptionFilterCatalog.uncaughtFilter(language);
        toggleExceptionFilter(language, filter);
    }

    /** Shows the toggles' state in the Debug window (when it has been built). */
    private void refreshExceptionToggles() {
        forEachSession(() -> {
            if (s().panel != null) { // each session's own language decides what they mean
                s().panel.setExceptionToggles(exceptionToggleOn(true), exceptionToggleOn(false));
            }
        });
    }

    /** Pure: {@code current} with {@code filter} turned on or off, in the order the adapter lists its filters. */
    static List<String> toggledFilter(
            List<String> current, @Nullable String filter, List<DapModels.ExceptionFilterOption> options) {
        Set<String> on = new LinkedHashSet<>(current);
        if (!on.remove(filter)) {
            on.add(filter);
        }
        List<String> out = new ArrayList<>();
        for (DapModels.ExceptionFilterOption o : options) {
            if (on.remove(o.filter())) {
                out.add(o.filter());
            }
        }
        out.addAll(on); // anything the adapter no longer lists stays, last
        return out;
    }

    private String exceptionStatus(
            String language, List<String> filters, List<DapModels.ExceptionFilterOption> options) {
        if (filters.isEmpty()) {
            return tr("status.debug.exceptionFiltersNone", debugAdapterLabel(language));
        }
        List<String> labels = new ArrayList<>();
        for (String f : filters) {
            labels.add(options.stream()
                    .filter(o -> o.filter().equals(f))
                    .map(DapModels.ExceptionFilterOption::label)
                    .findFirst()
                    .orElse(f));
        }
        // While muted, the choice is kept but stops nothing: it must not read as active (review 3, note on Mute).
        return tr(
                breakpointsMuted ? "status.debug.exceptionFiltersMuted" : "status.debug.exceptionFilters",
                debugAdapterLabel(language),
                String.join(", ", labels));
    }

    // --- adapter pickers ---------------------------------------------------------------------------

    private static String debugAdapterLabel(String id) {
        return DebugAdapterPickers.label(id);
    }

    /** Picker over the python/javascript debug adapters: toggles the chosen one's enable. */
    void chooseAdapterToggle() {
        new DebugAdapterPickers(host, this::applySupport).chooseToggle();
    }

    /** Picker over the java/python/javascript debug adapters, then prompts for that adapter's path. */
    void chooseAdapterPath() {
        new DebugAdapterPickers(host, this::applySupport).choosePath();
    }

    // --- buffer wiring + panel-action delegates ----------------------------------------------------

    /** Wires the debug hooks onto a freshly-added buffer (breakpoint gutter gate + change/hover hooks +
     *  restore persisted breakpoints). Called from {@code MainController.addBuffer}. */
    void wireBuffer(EditorBuffer buffer) {
        buffer.setBreakpointsEnabled(debugSupportEnabled() && isDebuggableBuffer(buffer));
        buffer.getBreakpointManager().setMuted(breakpointsMuted ? tr("debug.breakpoint.muted") : "");
        buffer.setGutterBreakpointClick((b, line) -> {
            var mark = b.getBreakpointManager().isBreakpoint(line)
                    ? null
                    : b.getBreakpointManager().memberMark(line);
            if (mark != null) {
                mark.members().forEach(memberBreakpoints::toggle); // a method breakpoint's mark: removes it
            } else if (breakpointAllowed(b, line)) {
                b.toggleBreakpoint(line);
            }
        });
        buffer.setGutterLogpointClick((b, line) -> {
            if (b.getBreakpointManager().isBreakpoint(line)
                    || b.getBreakpointManager().memberMark(line) == null) {
                addLogpoint(b, line); // IntelliJ's Shift+click: a breakpoint that logs and does not suspend
            }
        });
        buffer.setOnBreakpointsChanged(() -> onBreakpointsChanged(buffer));
        // Hover value tooltip while suspended: evaluate the hovered identifier in the selected frame.
        buffer.setDebugHoverEvaluator((expr, cb) -> dap().evaluateHover(expr, s().debugFrameId, cb));
        buffer.setDebugValuePresenter(valuePopup()); // an object's fields unfold in the popup (follow-up 6)
        // "Debug File" beside "Run File" in the editor's menu: Debug was only in the Run menu and the palette.
        // Through F5's check: a live launch of another file is replaced only after asking (review 3, C202).
        buffer.setDebugFileHandler(() -> debugFile(buffer), () -> debugSupportEnabled() && isDebuggableBuffer(buffer));
        buffer.setGutterBreakpointMenu(this::showGutterMenu); // right-click a breakpoint: Edit / Disable / Remove
        buffer.setDebugMenuContributor(() -> editorMenuItems(buffer));
    }

    /**
     * Debug: Threads — opens the Debug window on its Threads view: every thread of the program, which are suspended,
     * and Pause / Resume / Show Frames for each.
     */
    void showThreads() {
        ops.openToolWindow();
        panel().showThreadsView(true);
    }

    /** Focuses the evaluate field at the top of Variables ({@code debug.focusEvaluate}). */
    void focusEvaluate() {
        ops.openToolWindow();
        panel().focusEvaluate();
        later(panel()::focusEvaluate); // a window opened just now is laid out on the next pulse
    }

    /**
     * Fetches the selected frame's variables, watches and inline values again: an evaluation or Set Value may have
     * changed them (B205). False when there is nothing to fetch them from.
     */
    boolean refreshStopValues() {
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (frame == null || dap().state() != DapManager.State.SUSPENDED) {
            return false;
        }
        dap().forgetValues(); // B205: what the program now says, not this stop's first answers
        dap().scopes(frame.id(), scopes -> {
            panel().setScopes(scopes);
            applyInlineValues(frame, scopes);
        });
        return true;
    }

    // --- the Evaluate window ------------------------------------------------------------------------------

    /** The expressions evaluated in this window's evaluate fields, oldest first: one list, walked by every field. */
    private DebugPanelText.@Nullable History evaluateHistory;

    /** Every Evaluate window opened; a closed one is reused by the next Evaluate. */
    private final List<DebugEvaluateWindow> evaluateWindows = new ArrayList<>();

    DebugPanelText.History evaluateHistory() {
        if (evaluateHistory == null) {
            evaluateHistory = new DebugPanelText.History();
            ops.debugEvaluateHistory().forEach(evaluateHistory::add);
        }
        return evaluateHistory;
    }

    /**
     * Debug: Evaluate Expression ({@code debug.evaluate}, IntelliJ's Alt+F8): opens an Evaluate window, its
     * expression the editor's selection (one line) or the expression at the caret. Another Evaluate while one is open
     * opens another, as IntelliJ does — unless the open one was opened for the same text.
     */
    void evaluateExpression() {
        EditorBuffer b = host.activeBuffer();
        String initial = null;
        if (b != null) {
            var area = b.getFocusedArea();
            String selected = area.getSelectedText();
            initial = selected != null && !selected.isBlank()
                    ? selected.strip()
                    : com.editora.editor.debug.DebugExpressions.atCaret(area);
        }
        openEvaluateWindow(initial);
    }

    /** Opens an Evaluate window with {@code initial} as its expression (null: the field as it was). */
    DebugEvaluateWindow openEvaluateWindow(@Nullable String initial) {
        javafx.stage.Window owner = host.window();
        DebugEvaluateWindow window = null;
        int open = 0;
        for (DebugEvaluateWindow w : evaluateWindows) {
            if (w.isShowing()) {
                open++;
            } else if (window == null) {
                window = w;
            }
        }
        if (window == null) {
            window = new DebugEvaluateWindow(evaluateSource(), evaluateHistory());
            evaluateWindows.add(window);
        }
        window.setPlainValueColours(plainValueColours());
        window.show(owner, initial, s().panel == null ? "" : panel().expressionLanguageFile(), open * 28.0);
        return window;
    }

    /** The Evaluate windows (tests). */
    List<DebugEvaluateWindow> evaluateWindows() {
        return List.copyOf(evaluateWindows);
    }

    private DebugEvaluateWindow.Source evaluateSource() {
        return new DebugEvaluateWindow.Source() {
            @Override
            public boolean paused() {
                return dap().state() == DapManager.State.SUSPENDED;
            }

            @Override
            public String frameName() {
                DapModels.StackFrameInfo frame = s().shownFrame;
                if (frame == null) {
                    return "";
                }
                String where = frame.file() == null
                        ? ""
                        : (frame.line() >= 0 ? ":" + (frame.line() + 1) : "") + ", "
                                + DebugValues.sourceName(frame.file());
                return DebugPanelText.frameName(frame.name()) + where;
            }

            @Override
            public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                dap().evaluateFull(expression, s().debugFrameId, "repl", cb);
            }

            @Override
            public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                dap().completions(s().debugFrameId, text, caret, cb);
            }

            @Override
            public void addWatch(String expression) {
                ops.openToolWindow();
                panel().addWatchExpression(expression);
            }

            @Override
            public void evaluated(boolean succeeded) {
                ops.persistDebugEvaluateHistory(evaluateHistory().entries());
                if (succeeded) {
                    refreshStopValues(); // what it changed shows in Variables, the watches and the inline values
                }
            }

            @Override
            public void children(
                    int ref, @Nullable String filter, int start, int count, Consumer<List<DapModels.VariableInfo>> cb) {
                dap().variables(ref, filter, start, count, cb);
            }
        };
    }

    /**
     * Ends the debugged program's standard input ({@code debug.endProgramInput}, and Ctrl+D in the console's
     * input field): whatever reads it sees end of file. Only a program Editora started itself has an input
     * to end — see {@link DapManager#setProgramConsole}.
     */
    void endProgramInput() {
        if (dap().closeProgramInput()) {
            panel().showInputEnded();
        } else {
            host.setStatus(tr("status.debug.noProgramInput"));
        }
    }

    void addWatch() {
        ops.openToolWindow(); // also while idle (review 2 C109): the watch is added where the user sees it
        panel().addWatch();
    }

    void setSelectedValue() {
        panel().setSelectedValue();
    }

    // --- Java code changes and frame control: hot code replace, restart frame, smart step into -----------

    private DapManager.CodeReplaceMode codeReplaceMode() {
        return DapManager.CodeReplaceMode.of(host.settings().getDebugHotCodeReplace());
    }

    private static boolean isJavaSource(@Nullable Path file) {
        return file != null
                && file.getFileName() != null
                && file.getFileName()
                        .toString()
                        .toLowerCase(java.util.Locale.ROOT)
                        .endsWith(".java");
    }

    /**
     * A user save (not an autosave) reached the language server. During a Java session in {@code auto} mode
     * the change goes into the running program at once and the console says what happened; in {@code manual}
     * mode the status bar says how to apply it. FX thread.
     */
    void sourceSaved(EditorBuffer buffer) {
        Path file = buffer == null ? null : buffer.getPath();
        if (!isJavaSource(file) || !dap().canReplaceCode()) {
            return;
        }
        s().savedSinceReplace.add(file);
        switch (codeReplaceMode()) {
            case AUTO -> requestCodeReplace(file, false);
            case MANUAL -> host.setStatus(tr("debug.hcr.manualHint"));
            case OFF -> {
                // the user chose to keep the running code as it is
            }
        }
    }

    /**
     * A session started or ended: a replace still running belongs to the one before, and so do the saves it has
     * not applied. Left set, the running flag held every later save and Apply Code Changes of the window, which
     * then did nothing at all (review D101).
     */
    private void forgetCodeReplace(DapManager.State state) {
        if (state != DapManager.State.STARTING && state != DapManager.State.INACTIVE) {
            return;
        }
        s().codeReplaceSession++;
        s().codeReplaceRunning = false;
        s().codeReplaceAgain = null;
        s().savedSinceReplace.clear();
        s().lastCodeReplaceError = "";
    }

    /**
     * A reasonless refusal said with its likely reason ({@link CodeReplaceJdk}): the program's Java (its runtime
     * chip) and the one jdtls builds a file without a build tool for; null when that cannot be told.
     */
    private @Nullable String explainCodeReplace(@Nullable String message) {
        Path file = dap().sessionFile();
        if (!CodeReplaceJdk.reasonless(message) || file == null || JavaProjectRoot.find(file) != null) {
            return null; // a build project is compiled for its own release, not jdtls's default
        }
        return CodeReplaceJdk.explain(
                message, CodeReplaceJdk.programMajor(panel().runtimeText()), lspManager.javaDefaultMajor(file));
    }

    /** A Java session started running: let jdtls build what it will compare saved changes with. */
    private void primeCodeReplace(DapManager.State state) {
        if (state == DapManager.State.RUNNING && codeReplaceMode() != DapManager.CodeReplaceMode.OFF) {
            dap().primeCodeReplace();
        }
    }

    /** {@code debug.applyCodeChanges}: loads what is saved into the running program (auto and manual modes). */
    void applyCodeChanges() {
        if (!dap().canReplaceCode()) {
            host.setStatus(tr("debug.hcr.noSession"));
            return;
        }
        if (codeReplaceMode() == DapManager.CodeReplaceMode.OFF) {
            host.setStatus(tr("debug.hcr.off"));
            return;
        }
        EditorBuffer active = host.activeBuffer();
        Path file = active != null && isJavaSource(active.getPath()) ? active.getPath() : dap().sessionFile();
        if (active != null && active.isDirty() && isJavaSource(active.getPath())) {
            panel().appendNotice(tr("debug.hcr.unsavedNote")); // only saved files are compiled
        }
        requestCodeReplace(file, true);
    }

    /** Starts a replace, or folds this request into the one running (one more round once it ends). */
    private void requestCodeReplace(@Nullable Path file, boolean asked) {
        if (s().codeReplaceRunning) {
            s().codeReplaceAgain = file;
            return;
        }
        s().codeReplaceRunning = true;
        s().lastCodeReplaceError = "";
        Set<Path> covered = new LinkedHashSet<>(s().savedSinceReplace);
        long session = s().codeReplaceSession;
        host.setStatus(tr("debug.hcr.applying"));
        dap().applyCodeChanges(file, outcome -> {
            if (session != s().codeReplaceSession) {
                return; // its session ended meanwhile, which reset all of this (review D101)
            }
            s().codeReplaceRunning = false;
            reportCodeReplace(outcome, covered, asked);
            Path again = s().codeReplaceAgain;
            s().codeReplaceAgain = null;
            if (again != null && dap().canReplaceCode()) {
                requestCodeReplace(again, false);
            }
        });
    }

    /** Says how a replace ended: status bar always, Debug console for anything the user must act on. */
    private void reportCodeReplace(DapManager.CodeReplaceOutcome outcome, Set<Path> covered, boolean asked) {
        switch (outcome.result()) {
            case APPLIED -> {
                String names = CodeReplaceText.appliedNames(outcome.classes(), covered);
                panel().appendNotice(tr("debug.hcr.applied", names));
                if (outcome.message() == null || outcome.message().isEmpty()) {
                    host.setStatus(tr("debug.hcr.appliedStatus", names));
                } else {
                    // Methods that were running keep their old code (java-debug's "obsolete methods"): say how to
                    // run one again with the new code (review 2, B103).
                    explainOldCode();
                    host.setStatus(tr("debug.hcr.appliedOldStatus", names));
                }
                s().savedSinceReplace.removeAll(covered);
                codeReplaced(covered);
            }
            case NOTHING_CHANGED -> {
                s().savedSinceReplace.removeAll(covered); // the program already runs what these files say
                codeReplaced(covered);
                host.setStatus(tr(asked ? "debug.hcr.nothing" : "debug.hcr.nothingQuiet"));
            }
            case COMPILE_ERRORS -> {
                panel().appendNotice(tr("debug.hcr.compileErrors"));
                host.setStatus(tr("debug.hcr.compileErrors"));
            }
            case JAVAC_CLASSES -> {
                panel().appendNotice(tr("debug.hcr.javacClasses"));
                host.setStatus(tr("debug.hcr.javacClassesStatus"));
            }
            case FAILED -> {
                String explained = explainCodeReplace(outcome.message());
                if (explained != null && !explained.equals(s().lastCodeReplaceError)) {
                    panel().appendNotice(tr("debug.hcr.notice", explained));
                } else if (explained != null
                        || CodeReplaceText.saidAlready(outcome.message(), s().lastCodeReplaceError)) {
                    panel().appendNotice(tr("debug.hcr.restartNeeded")); // the adapter's event gave the reason
                } else {
                    panel().appendNotice(tr("debug.hcr.failed", outcome.message()));
                }
                host.setStatus(tr("debug.hcr.failedStatus"));
            }
            case BUILD_FAILED -> {
                panel().appendNotice(tr("debug.hcr.buildFailed"));
                host.setStatus(tr("debug.hcr.buildFailed"));
            }
            case NO_SESSION -> host.setStatus(tr("debug.hcr.noSession"));
        }
    }

    /**
     * Says how to run a method left on its old code again — Restart Frame, or, when the only such method is the
     * thread's first one ({@code main}), restarting the program (review 3, B209). java-debug names an old-code frame
     * {@code Obsolete method()} only in the stack it stops on after the replace, so the hint waits for that stack (a
     * second at most; then it reads the stack shown).
     */
    private void explainOldCode() {
        // While java-debug replaces, the program reads as running: the stack it stops on again names the frames.
        List<String> shown =
                panel().callStack().stream().map(DapModels.StackFrameInfo::name).toList();
        if (shown.stream().anyMatch(DebugPanelText::isObsoleteFrame)) {
            panel().appendNotice(CodeReplaceText.oldCodeHint(shown)); // that stack came before the answer
            return;
        }
        long asked = ++s().oldCodeHintAsked;
        s().oldCodeHintPending = asked;
        javafx.animation.PauseTransition wait = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1));
        wait.setOnFinished(e -> {
            if (s().oldCodeHintPending == asked) {
                s().oldCodeHintPending = 0;
                panel().appendNotice(CodeReplaceText.oldCodeHint(panel().callStack().stream()
                        .map(DapModels.StackFrameInfo::name)
                        .toList()));
            }
        });
        wait.play();
    }

    /** A stack arrived: say the waiting old-code hint from it (B209). */
    private void oldCodeHintFrom(List<DapModels.StackFrameInfo> frames) {
        if (s().oldCodeHintPending != 0) {
            s().oldCodeHintPending = 0;
            panel().appendNotice(CodeReplaceText.oldCodeHint(
                    frames.stream().map(DapModels.StackFrameInfo::name).toList()));
        }
    }

    /**
     * The running program now runs what {@code files} say on disk: a successful replace, or one that found
     * nothing to change. Breakpoint and frame lines of these files no longer need mapping to launch-time lines.
     */
    private void codeReplaced(Set<Path> files) {
        rebaselineLines(files); // B1: the code that runs matches the saved file again
    }

    /**
     * java-debug's own hot code replace events. A replace it could not finish leaves the program on the old code
     * somewhere: that is said in the console. Progress events need no words — Editora reports the outcome.
     */
    private void codeReplaceNotice(String changeType, String rawMessage) {
        String explained = "ERROR".equals(changeType) ? explainCodeReplace(rawMessage) : null;
        String message = explained != null ? explained : rawMessage;
        String text = CodeReplaceText.notice(changeType, message);
        if ("ERROR".equals(changeType)) {
            s().lastCodeReplaceError = message == null ? "" : message.strip();
        }
        if (text != null) {
            panel().appendNotice(tr("debug.hcr.notice", text));
        }
    }

    /**
     * {@code debug.restartFrame}: runs the selected call-stack frame's method again from its start (the top frame
     * when none is selected). Paused Java sessions only; the adapter says when a frame cannot be restarted.
     */
    void restartFrame() {
        restartFrame(s().shownFrame);
    }

    private void restartFrame(DapModels.@Nullable StackFrameInfo frame) {
        if (dap().state() != DapManager.State.SUSPENDED || frame == null) {
            host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        if (!dap().supportsRestartFrame()) {
            host.setStatus(tr("debug.restartFrame.unsupported"));
            return;
        }
        List<DapModels.StackFrameInfo> frames = panel().callStack();
        if (frames.contains(frame) && !canRestartFrame(true, true, frame, frames)) {
            // The thread's first method: nothing called it, so nothing can call it again (review 3, B209).
            host.setStatus(tr("debug.restartFrame.bottom"));
            return;
        }
        dap().restartFrame(frame.id());
    }

    /**
     * Whether the call-stack menu offers Restart Frame for {@code frame} of {@code frames}: a paused session whose
     * adapter restarts frames, and not the bottom frame of a complete stack (nothing called it, so nothing can
     * call it again).
     */
    static boolean canRestartFrame(
            boolean suspended,
            boolean supported,
            DapModels.StackFrameInfo frame,
            List<DapModels.StackFrameInfo> frames) {
        if (!suspended || !supported || frame == null || frames == null || frames.isEmpty()) {
            return false;
        }
        int index = frames.indexOf(frame);
        return index >= 0 && index < frames.size() - 1;
    }

    /** The call-stack context menu for {@code frame} (Restart Frame). */
    List<javafx.scene.control.MenuItem> stackMenuItems(
            DapModels.StackFrameInfo frame, List<DapModels.StackFrameInfo> frames) {
        javafx.scene.control.MenuItem restart = new javafx.scene.control.MenuItem(tr("debugpanel.restartFrame"));
        restart.setDisable(!canRestartFrame(
                dap().state() == DapManager.State.SUSPENDED, dap().supportsRestartFrame(), frame, frames));
        restart.setOnAction(e -> restartFrame(frame));
        return List.of(restart);
    }

    /** The last Smart Step Into picker shown (null before the first). */
    private @Nullable QuickOpen<DapModels.StepTarget> smartStepPicker;

    /**
     * {@code debug.smartStepInto}: lists the calls on the current line and steps into the one picked — straight
     * into it when there is only one. Plain Step Into is unchanged.
     */
    void smartStepInto() {
        if (dap().state() != DapManager.State.SUSPENDED) {
            host.setStatus(tr("debug.frame.notPaused"));
            return;
        }
        if (!dap().supportsStepInTargets()) {
            host.setStatus(tr("debug.smartStep.unsupported"));
            return;
        }
        String lineText = shownLineText();
        dap().stepInTargets(found -> {
            List<DapModels.StepTarget> targets = StepTargetOrder.sort(found, lineText);
            if (targets.isEmpty()) {
                host.setStatus(tr("debug.smartStep.none"));
            } else if (targets.size() == 1) {
                dap().stepIntoTarget(targets.get(0).id());
            } else {
                QuickOpen<DapModels.StepTarget> picker = smartStepPicker = new QuickOpen<>(
                        tr("debug.smartStep.title"),
                        tr("debug.smartStep.prompt"),
                        () -> targets,
                        DapModels.StepTarget::label,
                        t -> t.line() >= 0 ? tr("debug.smartStep.line", t.line() + 1) : "",
                        t -> dap().stepIntoTarget(t.id()));
                picker.setOverlayHost(host.overlayHost());
                picker.show(host.window());
            }
        });
    }

    /** The text of the stopped line Smart Step Into lists the calls of ("" when its file is not open). */
    private String shownLineText() {
        DapModels.StackFrameInfo frame = s().shownFrame;
        if (frame == null || frame.file() == null) {
            return "";
        }
        EditorBuffer b = ops.bufferForPath(frame.file());
        int line = frame.line();
        return b == null || line < 0 || line >= b.getArea().getParagraphs().size()
                ? ""
                : b.getArea().getParagraph(line).getText();
    }

    /** A Smart Step Into list belongs to one stop: it closes once the program runs on or the session ends. */
    private void closeSmartStepPicker(DapManager.State state) {
        if (!shown()) {
            return; // another session's: the editor shows the focused one's
        }
        QuickOpen<DapModels.StepTarget> picker = smartStepPicker;
        if (picker != null && state != DapManager.State.SUSPENDED) {
            smartStepPicker = null;
            picker.hide();
        }
    }

    // --- review C: breakpoints list, mute, gutter and editor menus, Show Value -----------------------------

    /**
     * Mute Breakpoints: the adapter is told no breakpoints until it is turned off again. Kept per project in the
     * workspace state and restored when the window starts; every breakpoint glyph is drawn faded meanwhile.
     */
    private boolean breakpointsMuted;

    boolean breakpointsMuted() {
        return breakpointsMuted;
    }

    /** Debug: Breakpoints — the palette-style list of every breakpoint (see {@link BreakpointsPicker}). */
    void showBreakpoints() {
        new BreakpointsPicker(
                        host,
                        ops,
                        this::allManagers,
                        this::toggleMuteBreakpoints,
                        () -> breakpointsMuted,
                        memberBreakpoints)
                .show();
    }

    /**
     * Debug: Mute Breakpoints — none of them stops the program until muted again; a live session is told at once
     * (every file it knows is sent again: empty while muted, its enabled breakpoints after).
     */
    void toggleMuteBreakpoints() {
        breakpointsMuted = !breakpointsMuted;
        memberBreakpoints.push(); // and method breakpoints and field watchpoints
        forEachSession(() -> {
            dap().setExceptionsMuted(breakpointsMuted); // exception stops too (review 2, B109)
            if (dap().isActive()) {
                Set<String> open = new HashSet<>();
                host.forEachBuffer(b -> {
                    if (b.getPath() != null && open.add(key(b.getPath()))) {
                        DapModels.FileBreakpoints fb = fileBreakpoints(b, b.getPath());
                        s().sentBreakpoints.put(b.getPath(), fb);
                        dap().updateBreakpoints(fb);
                    }
                });
                for (DapModels.FileBreakpoints fb : closedBreakpoints) {
                    if (fb.file() != null && !open.contains(key(fb.file()))) {
                        dap().updateBreakpoints(
                                        breakpointsMuted ? new DapModels.FileBreakpoints(fb.file(), List.of()) : fb);
                    }
                }
            }
        });
        ops.persistDebugBreakpointsMuted(breakpointsMuted);
        showBreakpointsMuted();
        panel().setBreakpointsMuted(breakpointsMuted);
        host.setStatus(tr(breakpointsMuted ? "status.debug.breakpointsMuted" : "status.debug.breakpointsUnmuted"));
    }

    /** Shows Mute Breakpoints on every breakpoint glyph (faded, and said on hover) and in the Debug window. */
    private void showBreakpointsMuted() {
        String hover = breakpointsMuted ? tr("debug.breakpoint.muted") : ""; // "": not muted
        host.forEachBuffer(b -> b.getBreakpointManager().setMuted(hover));
        forEachPanel(panel -> panel.setBreakpointsMuted(breakpointsMuted));
    }

    /**
     * Debug: Toggle Temporary Breakpoint — a breakpoint on the caret line that is removed once the program stops on
     * it (IntelliJ's Ctrl+Alt+Shift+F8). On a line with a temporary breakpoint it removes it; on one with an
     * ordinary breakpoint it makes that one temporary.
     */
    void toggleTemporaryBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!breakpointAllowed(b, line)) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp != null && bp.temporary()) {
            b.toggleBreakpoint(line);
            return;
        }
        if (bp == null) {
            b.toggleBreakpoint(line);
        }
        mgr.setTemporary(line, true);
        b.refreshGutterLine(line);
        host.setStatus(tr("status.debug.temporaryAdded", line + 1));
    }

    /**
     * Debug: Toggle Recording Breakpoint — a breakpoint on the caret line whose stops Debug History keeps while the
     * program goes on, as Visual Studio's tracepoints and IntelliJ's non-suspending breakpoints log. On a line with a
     * recording breakpoint it removes it; on one with an ordinary breakpoint it makes that one record.
     */
    void toggleRecordingBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        if (!breakpointAllowed(b, line)) {
            return;
        }
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp != null && bp.recording()) {
            b.toggleBreakpoint(line);
            return;
        }
        if (bp == null) {
            b.toggleBreakpoint(line);
        }
        mgr.setRecording(line, true);
        b.refreshGutterLine(line);
        host.setStatus(tr("status.debug.recordingAdded", line + 1));
    }

    /**
     * The program stopped on a breakpoint: when it is a temporary one, it is removed, which also takes it out of the
     * live session. Waits for the stopped file's buffer to load: the stop may have just opened it.
     */
    private void removeTemporaryBreakpointHit(String reason, List<DapModels.StackFrameInfo> frames) {
        if (!"breakpoint".equals(reason) || frames.isEmpty() || frames.get(0).file() == null) {
            return;
        }
        DapModels.StackFrameInfo top = frames.get(0);
        EditorBuffer b = ops.bufferForPath(top.file());
        if (b == null) {
            return;
        }
        ops.afterBufferLoad(b, () -> {
            var mgr = b.getBreakpointManager();
            int line = top.line() - mgr.regionFirstLine();
            Breakpoint bp = mgr.get(line);
            if (bp != null && bp.temporary() && bp.enabled() && !bp.isLogpoint()) {
                b.toggleBreakpoint(line);
                host.setStatus(tr("status.debug.temporaryRemoved", top.line() + 1));
            }
        });
    }

    /**
     * Debug: Toggle Lambda Breakpoint — a breakpoint that stops inside the lambda at the caret when its body runs
     * (see {@link LambdaBreakpoints}).
     */
    void toggleLambdaBreakpointAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b != null && breakpointAllowed(b, b.getFocusedArea().getCurrentParagraph())) {
            lambdaBreakpoints.toggleAtCaret(b);
        }
    }

    /** Debug: Enable / Disable Breakpoint — flips the caret line's breakpoint without removing it. */
    void toggleBreakpointEnabledAtCaret() {
        EditorBuffer b = breakpointBuffer();
        if (b == null) {
            return;
        }
        int line = b.getFocusedArea().getCurrentParagraph();
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            host.setStatus(tr("status.debug.noBreakpointHere"));
            return;
        }
        mgr.setEnabled(line, !bp.enabled());
        b.refreshGutterLine(line);
    }

    /** Debug: Show Value at Caret — the value popup for the name at the caret, from the keyboard. */
    void showValueAtCaret() {
        EditorBuffer b = host.activeBuffer();
        if (dap().state() != DapManager.State.SUSPENDED) {
            host.setStatus(tr("palette.disabled.needsSuspended"));
        } else if (b == null || !b.showDebugValueAtCaret()) {
            host.setStatus(tr("status.debug.noValueAtCaret"));
        }
    }

    /**
     * F11 in the CUA and Sublime keymaps: Step Into while the program is paused, Full Screen otherwise — what F11
     * does in VS Code, whose debugging keys those keymaps now carry (review C8).
     */
    void stepIntoOrElse(Runnable otherwise) {
        if (debugSupportEnabled() && dap().state() == DapManager.State.SUSPENDED) {
            dap().stepInto();
        } else {
            otherwise.run();
        }
    }

    /** A right-click on a breakpoint glyph: Edit…, Enable / Disable, Remove (review C11). */
    private void showGutterMenu(EditorBuffer b, int line, javafx.scene.input.ContextMenuEvent e) {
        var mgr = b.getBreakpointManager();
        Breakpoint bp = mgr.get(line);
        if (bp == null) {
            var mark = mgr.memberMark(line);
            if (mark != null) {
                showGutterMenu(memberGutterItems(mark), e);
            }
            return;
        }
        // Bare titles, as every other row of this menu ("Debug: Edit Breakpoint…" beside "Remove Breakpoint",
        // review 2 C110).
        javafx.scene.control.MenuItem edit = menuItem("debug.editBreakpoint", Icons.edit(), () -> {
            b.getFocusedArea().moveTo(line, 0);
            editBreakpointAtCaret();
        });
        boolean enabled = bp.enabled();
        javafx.scene.control.MenuItem flip =
                new javafx.scene.control.MenuItem(tr(enabled ? "debug.gutter.disable" : "debug.gutter.enable"));
        flip.setOnAction(ev -> {
            mgr.setEnabled(line, !enabled);
            b.refreshGutterLine(line);
        });
        javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem(tr("debug.gutter.remove"));
        remove.setGraphic(Icons.remove());
        remove.setOnAction(ev -> {
            if (mgr.isBreakpoint(line)) {
                b.toggleBreakpoint(line);
            }
        });
        javafx.scene.control.MenuItem list = menuItem("debug.breakpoints", null, this::showBreakpoints);
        javafx.scene.control.MenuItem waitFor = menuItem("debug.addTriggeredBreakpoint", null, () -> {
            b.getFocusedArea().moveTo(line, 0);
            addTriggeredBreakpointAtCaret();
        });
        gutterMenu.getItems().setAll(edit, flip, remove, waitFor);
        List<javafx.scene.control.MenuItem> lambdas = lambdaBreakpoints.choices(b, line, bp);
        if (!lambdas.isEmpty()) {
            gutterMenu.getItems().add(new javafx.scene.control.SeparatorMenuItem());
            gutterMenu.getItems().addAll(lambdas);
        }
        gutterMenu.getItems().addAll(new javafx.scene.control.SeparatorMenuItem(), list);
        showGutterMenu(List.copyOf(gutterMenu.getItems()), e);
    }

    /** The gutter menu of a method breakpoint's or field watchpoint's mark: Enable / Disable, Remove, Breakpoints. */
    private List<javafx.scene.control.MenuItem> memberGutterItems(
            com.editora.editor.debug.BreakpointManager.MemberMark mark) {
        boolean enabled = mark.enabled();
        javafx.scene.control.MenuItem flip =
                new javafx.scene.control.MenuItem(tr(enabled ? "debug.gutter.disable" : "debug.gutter.enable"));
        flip.setOnAction(ev -> mark.members().forEach(m -> memberBreakpoints.replace(m.withEnabled(!enabled))));
        javafx.scene.control.MenuItem remove = new javafx.scene.control.MenuItem(
                tr(mark.field() ? "debug.gutter.removeFieldWatchpoint" : "debug.gutter.removeMethodBreakpoint"));
        remove.setGraphic(Icons.remove());
        remove.setOnAction(ev -> mark.members().forEach(memberBreakpoints::remove));
        javafx.scene.control.MenuItem list = menuItem("debug.breakpoints", null, this::showBreakpoints);
        return List.of(flip, remove, new javafx.scene.control.SeparatorMenuItem(), list);
    }

    private void showGutterMenu(List<javafx.scene.control.MenuItem> items, javafx.scene.input.ContextMenuEvent e) {
        gutterMenu.getItems().setAll(items);
        Node source = e.getSource() instanceof Node n ? n : null;
        if (source != null && source.getScene() != null && source.getScene().getWindow() != null) {
            gutterMenu.show(source, e.getScreenX(), e.getScreenY());
        } else if (host.window() != null) {
            gutterMenu.show(host.window(), e.getScreenX(), e.getScreenY());
        }
    }

    private final javafx.scene.control.ContextMenu gutterMenu = new javafx.scene.control.ContextMenu();

    /**
     * Whether the editor menu offers Debug File of its own for a file of {@code language}: one that Run File can't
     * run ({@code runnable} false; a runnable file has Debug File beside Run File) but Debug can. That is a classic
     * Java main class outside a Maven or Gradle project ({@code inJavaProject} false), and any Go, Rust, C, C++, C#,
     * Ruby or PHP file, which only the debugger's own launch runs. Pure.
     */
    static boolean offersOwnDebugFile(String language, boolean runnable, boolean inJavaProject) {
        if (runnable) {
            return false;
        }
        return switch (language) {
            case "java" -> !inJavaProject;
            case "go", "rust", "c", "cpp", "csharp", "ruby", "php" -> true;
            default -> false;
        };
    }

    /**
     * The editor's right-click debugger items (review C10): the Breakpoints submenu on a debuggable file, and while
     * paused Run to Cursor, Show Value and Add to Watches for the name at the click. Empty when debugging is off.
     */
    List<javafx.scene.control.MenuItem> editorMenuItems(EditorBuffer b) {
        List<javafx.scene.control.MenuItem> items = new ArrayList<>();
        if (!debugSupportEnabled() || !isDebuggableBuffer(b)) {
            return items;
        }
        Path menuPath = b.getPath();
        if (menuPath != null
                && offersOwnDebugFile(
                        b.getLanguage(),
                        b.isRunnable(),
                        "java".equals(b.getLanguage()) && JavaProjectRoot.find(menuPath) != null)
                // Not while this file is being debugged or built for it: it offered a second launch of it (review 2
                // C110). Another file's live launch is replaced only after asking (review 3, C202).
                && !launchLiveFor(menuPath)) {
            // Debug File sits beside Run File for a runnable file; a file Run File can't run but Debug can gets
            // it here, or Debug was only in the Run menu and the palette.
            javafx.scene.control.MenuItem debugFile = new javafx.scene.control.MenuItem(tr("editmenu.debugFile"));
            debugFile.setGraphic(Icons.debug());
            debugFile.setOnAction(e -> debugFile(b));
            items.add(debugFile);
        }
        items.add(breakpointsMenu(b));
        if (dap().state() == DapManager.State.SUSPENDED) {
            items.add(menuItem("debug.runToCursor", Icons.debugRunToCursor(), this::debugRunToCursor));
            items.add(menuItem("debug.forceRunToCursor", Icons.debugRunToCursor(), this::debugForceRunToCursor));
            items.add(menuItem("debug.showValue", Icons.find(), this::showValueAtCaret));
            items.add(menuItem("debug.evaluate", Icons.debug(), this::evaluateExpression));
            var area = b.getFocusedArea();
            String selected = area.getSelectedText();
            String expression = selected != null && !selected.isBlank() && !selected.contains("\n")
                    ? selected.strip()
                    : com.editora.editor.debug.DebugExpressions.atCaret(area);
            if (expression != null) {
                javafx.scene.control.MenuItem watch =
                        new javafx.scene.control.MenuItem(tr("debugpanel.addToWatches", expression));
                watch.setGraphic(Icons.newFile()); // as Add to Watches in the Variables menu
                watch.setOnAction(ev -> {
                    ops.openToolWindow();
                    panel().addWatchPrompt(expression);
                });
                items.add(watch);
            }
        }
        return items;
    }

    /**
     * The editor menu's <b>Breakpoints</b> submenu: every breakpoint action for the right-clicked line (the caret is
     * there by now), then the list and Mute Breakpoints. One row instead of four to six flat ones, which pushed the
     * rest of the menu down on every debuggable file — the shape of the LSP and AI submenus.
     */
    private javafx.scene.control.Menu breakpointsMenu(EditorBuffer b) {
        javafx.scene.control.Menu menu =
                new javafx.scene.control.Menu(tr("editmenu.breakpoints"), breakpointGlyph()); // icons: review 2 C110
        var items = menu.getItems();
        items.add(menuItem("debug.toggleBreakpoint", breakpointGlyph(), this::toggleBreakpointAtCaret));
        items.add(
                menuItem("debug.toggleTemporaryBreakpoint", breakpointGlyph(), this::toggleTemporaryBreakpointAtCaret));
        items.add(
                menuItem("debug.toggleRecordingBreakpoint", breakpointGlyph(), this::toggleRecordingBreakpointAtCaret));
        if (LambdaBreakpoints.caretLineHasLambdas(b)) {
            items.add(menuItem("debug.toggleLambdaBreakpoint", breakpointGlyph(), this::toggleLambdaBreakpointAtCaret));
        }
        items.add(menuItem("debug.addLogpoint", logpointGlyph(), this::addLogpointAtCaret));
        items.add(menuItem("debug.addTriggeredBreakpoint", breakpointGlyph(), this::addTriggeredBreakpointAtCaret));
        items.add(menuItem("debug.editBreakpoint", Icons.edit(), this::editBreakpointAtCaret));
        javafx.scene.control.MenuItem flip =
                menuItem("debug.toggleBreakpointEnabled", Icons.check(), this::toggleBreakpointEnabledAtCaret);
        flip.setDisable(b.getBreakpointManager().get(b.getFocusedArea().getCurrentParagraph()) == null);
        items.add(flip);
        items.add(new javafx.scene.control.SeparatorMenuItem());
        items.add(menuItem("debug.breakpoints", Icons.outline(), this::showBreakpoints));
        javafx.scene.control.CheckMenuItem mute =
                new javafx.scene.control.CheckMenuItem(DebugPanelText.bareTitle(tr("command.debug.muteBreakpoints")));
        mute.setGraphic(Icons.hidden());
        mute.setSelected(breakpointsMuted);
        mute.setOnAction(e -> toggleMuteBreakpoints());
        items.add(mute);
        return menu;
    }

    private static javafx.scene.control.MenuItem menuItem(String commandId, @Nullable Node icon, Runnable action) {
        javafx.scene.control.MenuItem item =
                new javafx.scene.control.MenuItem(DebugPanelText.bareTitle(tr("command." + commandId)));
        com.editora.editor.MenuChords.tag(item, commandId);
        item.setGraphic(icon);
        item.setOnAction(e -> action.run());
        return item;
    }

    /** A small breakpoint dot for Toggle Breakpoint in the editor menu (the gutter's red, from CSS). */
    private static Node breakpointGlyph() {
        javafx.scene.shape.Circle dot = new javafx.scene.shape.Circle(4);
        dot.getStyleClass().add("debug-menu-breakpoint");
        return dot;
    }

    /** Add Logpoint's mark in the same menu: the gutter's logpoint diamond, blue, at the dot's size. */
    private static Node logpointGlyph() {
        javafx.scene.shape.Polygon diamond = new javafx.scene.shape.Polygon(4, 0, 8, 4, 4, 8, 0, 4);
        diamond.getStyleClass().add("debug-menu-logpoint");
        return diamond;
    }

    // --- follow-ups 5 and 6: completion in expression fields, the expandable value popup ----------------

    /**
     * What a debugger expression field completes against: the selected frame of a stopped session (DAP
     * {@code completions}). {@code braces}: a log message, where only the {@code {expression}} at the caret is code.
     */
    DebugCompletion.Source completionSource(boolean braces) {
        return new DebugCompletion.Source() {
            @Override
            public boolean paused() {
                return dap().state() == DapManager.State.SUSPENDED && s().debugFrameId >= 0;
            }

            @Override
            public void complete(String text, int caret, Consumer<List<DapModels.CompletionTarget>> cb) {
                int from = braces ? DebugCompletionText.braceStart(text, caret) : 0;
                if (from < 0) {
                    cb.accept(List.of()); // outside {…}: plain log text
                    return;
                }
                dap().completions(s().debugFrameId, text.substring(from, caret), caret - from, cb);
            }
        };
    }

    /** Add / Edit Watch: the window's one-field prompt, its field completing expressions in the stopped frame. */
    private void expressionPrompt(String title, String label, String initial, Consumer<String> onAccept) {
        Label promptLabel = new Label(label);
        TextField field = new TextField(initial == null ? "" : initial);
        field.setPrefColumnCount(32);
        DebugCompletion.attach(field, completionSource(false));
        com.editora.command.TextInputKeymap.installShared(field);
        OverlayInput.show(
                host.overlayHost(),
                title,
                new javafx.scene.layout.VBox(6, promptLabel, field),
                field,
                tr("dialog.ok"),
                null,
                () -> onAccept.accept(field.getText()),
                null,
                false);
    }

    private @Nullable DebugValuePopup valuePopup;

    /** The hover / Show Value at Caret popup, shared by this window's editors. */
    DebugValuePopup valuePopup() {
        if (valuePopup == null) {
            valuePopup = new DebugValuePopup(new DebugValuePopup.Source() {
                @Override
                public void evaluate(String expression, Consumer<DapModels.EvalResult> cb) {
                    dap().evaluateFull(expression, s().debugFrameId, "hover", cb);
                }

                @Override
                public void children(
                        int ref,
                        @Nullable String filter,
                        int start,
                        int count,
                        Consumer<List<DapModels.VariableInfo>> cb) {
                    dap().variables(ref, filter, start, count, cb);
                }

                @Override
                public void addWatch(String expression) {
                    ops.openToolWindow();
                    panel().addWatchPrompt(expression);
                }

                @Override
                public void evaluateCode(String expression, Consumer<DapModels.EvalResult> cb) {
                    dap().evaluateFull(expression, s().debugFrameId, "repl", r -> {
                        cb.accept(r);
                        if (r != null && !r.failed()) {
                            refreshStopValues(); // Quick Evaluate runs a call: what it changed shows (B205)
                        }
                    });
                }

                @Override
                public void pin(org.fxmisc.richtext.CodeArea area, int line, String expression) {
                    EditorBuffer[] owner = new EditorBuffer[1];
                    host.forEachBuffer(b -> {
                        if (owner[0] == null && (b.getArea() == area || b.getFocusedArea() == area)) {
                            owner[0] = b;
                        }
                    });
                    if (owner[0] != null) {
                        pinnedValues.toggle(owner[0], line, expression);
                    }
                }
            });
            if (valuePopup.popup().getSkin() != null) {
                DebugPanel.setPlainValueColours(valuePopup.popup().getSkin().getNode(), plainValueColours()); // C111
            }
        }
        return valuePopup;
    }
}
