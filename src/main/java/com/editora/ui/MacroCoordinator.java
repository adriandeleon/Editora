package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import javafx.application.Platform;
import javafx.event.EventHandler;
import javafx.event.EventTarget;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.VBox;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.command.MacroCapture;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.macro.Macro;
import com.editora.macro.MacroKey;
import com.editora.macro.MacroPlayer;
import com.editora.macro.MacroReplay;
import com.editora.macro.MacroService;
import com.editora.macro.MacroStep;

import static com.editora.i18n.Messages.tr;

/**
 * Keyboard macros (record / replay / save / run), extracted from {@link MainController} via the
 * {@link CoordinatorHost} pattern. Owns the per-window {@link MacroService}, the capture hooks (it is the
 * key dispatcher's {@link MacroCapture} and the command registry's listener), the replay loop and the
 * {@code macro.*} commands. Reaches the window through the shared host plus a small {@link Ops} extension.
 *
 * <h2>Recording</h2>
 *
 * Three things are recorded, in the order they happen: commands the user invoked, text typed, and keys that
 * act rather than type (Enter, Tab, Backspace, the arrows, Escape, …). Text and keys carry where they went —
 * the document or "whatever else had the focus" (the find bar, a prompt, a picker, a tool window) — so a
 * macro can drive a prompt. Keys typed into the command palette are the exception: the palette's outcome is
 * a command, and that command is what gets recorded.
 *
 * <h2>Replay</h2>
 *
 * A step is delivered the way it arrived: a command through the registry, text and keys as real key events,
 * so every filter and handler that saw the live key sees the replayed one (snippet expansion, table
 * navigation, completion accept, multiple carets, auto-close). A document step is fired at the active
 * editor; a prompt step at the scene's focus owner — after waiting, if need be, for the command before it to
 * have moved the focus there. The loop runs synchronously while it can and yields to the event loop when it
 * has to wait or has used its time slice, so a long replay does not freeze the window and Escape stops it.
 */
final class MacroCoordinator implements MacroCapture {

    /** Window hooks beyond {@link CoordinatorHost}. */
    interface Ops {
        /** Re-register {@code macro.run.*} in every open window. */
        void refreshAllWindows();

        /** Show/hide the status-bar "● REC" recording indicator. */
        void setRecordingIndicator(boolean recording);

        /** The prefix argument ({@code C-u N}) the running command was given, or null. */
        Integer prefixArgument();

        /** Drop the user's key binding for {@code commandId}. */
        void resetShortcut(String commandId);
    }

    /**
     * Node property marking a subtree whose keys a macro never records, because what the user does there
     * ends in a command that is recorded instead (the command palette).
     */
    static final String OPAQUE = "editora.macroOpaque";

    /** Most repeats one replay accepts, from the prompt and from a prefix argument alike. */
    static final int MAX_REPLAY_TIMES = 10_000;

    /** How long the first, synchronous stretch of a replay may run before it starts yielding. */
    private static final long FIRST_SLICE_NANOS = 250_000_000L;
    /** How long each later stretch runs between two turns of the event loop. */
    private static final long SLICE_NANOS = 12_000_000L;
    /** How long a step waits for the focus to be where it was when recorded before the replay gives up. */
    private static final long FOCUS_WAIT_NANOS = 2_000_000_000L;

    private final CoordinatorHost host;
    private final Ops ops;
    private final CommandRegistry registry;
    private final MacroService service;

    /** Depth of key events this coordinator is firing right now (see {@link MacroCapture#SYNTHETIC}). */
    private int synthetic;
    /** The replay in progress, or null. */
    private Run run;

    // --- recording state ---
    private Scene recordingScene;
    private boolean mouseHintShown;
    private boolean dialogHintShown;
    /** The outermost command now running, while recording — to notice one that blocks in a dialog. */
    private String commandInFlight;

    private final EventHandler<KeyEvent> escapeCancels = this::onUnhandledKey;
    private final EventHandler<MouseEvent> mouseWatch = this::onMousePressed;

    MacroCoordinator(ConfigManager config, CommandRegistry registry, CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.registry = registry;
        this.service = new MacroService(config);
    }

    // ---------------------------------------------------------------- capture hooks

    /** Capture hook for the command registry's start listener (see {@link #onCommand}). */
    void onCommandStart(String commandId) {
        if (!service.isRecording() || run != null) {
            return;
        }
        commandInFlight = commandId;
        // If this runs before the command has returned, the command is spinning its own event loop: a
        // native file chooser or an alert. The keys typed there never reach this window, so the macro
        // cannot replay them — say so now rather than record something that silently stops half-way.
        Platform.runLater(() -> {
            if (commandId.equals(commandInFlight) && registry.isRunning() && service.isRecording()) {
                commandInFlight = null;
                if (!dialogHintShown) {
                    dialogHintShown = true;
                    host.setStatus(tr("status.macro.dialogCannotReplay", titleOf(commandId)));
                }
            }
        });
    }

    /** Capture hook for the command registry's execution listener (no-op unless recording). */
    void onCommand(String commandId) {
        commandInFlight = null;
        service.onCommand(commandId);
    }

    @Override
    public int mode() {
        if (synthetic > 0) {
            return SYNTHETIC;
        }
        if (run != null) {
            return REPLAYING;
        }
        return service.isRecording() ? RECORDING : IDLE;
    }

    @Override
    public void keySeen() {
        service.keySeen();
    }

    @Override
    public void text(String chars, EventTarget target) {
        int where = classify(target);
        if (where != Target.NONE) {
            service.onText(chars, where == Target.PROMPT);
            boundConsequences();
        }
    }

    /** Bumped by every recorded key or text step; see {@link #boundConsequences}. */
    private long consequenceGeneration;

    /**
     * A command that runs right after a recorded key is that key's doing (Enter in a picker runs the pick)
     * and is not recorded a second time — see {@link MacroService#keySeen}. "Right after" ends with the next
     * key or mouse press, and here also after two turns of the event loop: enough for a picker that closes
     * first and acts a turn later, and short enough that a command the user then picks from a menu that
     * sends this window no key or mouse press (the macOS system menu bar) is still recorded.
     */
    private void boundConsequences() {
        long generation = ++consequenceGeneration;
        Platform.runLater(() -> Platform.runLater(() -> {
            if (generation == consequenceGeneration) {
                service.keySeen();
            }
        }));
    }

    @Override
    public void key(KeyEvent event, EventTarget target) {
        int where = classify(target);
        if (where != Target.NONE) {
            service.onKey(
                    MacroKey.encode(
                            event.isControlDown(),
                            event.isAltDown(),
                            event.isMetaDown(),
                            event.isShiftDown(),
                            event.getCode().name()),
                    where == Target.PROMPT);
            boundConsequences();
        }
    }

    @Override
    public void cancelReplay() {
        if (run != null) {
            run.cancelled = true;
        }
    }

    /** Where a recorded key was aimed. */
    private static final class Target {
        static final int NONE = 0;
        static final int EDITOR = 1;
        static final int PROMPT = 2;
    }

    /**
     * Sorts an event target into the document, a prompt, or nothing recordable. Only called while
     * recording, so the walk up the scene graph costs nothing on the idle typing path.
     */
    private int classify(EventTarget target) {
        if (!(target instanceof Node node)) {
            return Target.NONE;
        }
        EditorBuffer b = host.activeBuffer();
        if (b != null && b.ownsKeyTarget(target)) {
            return Target.EDITOR;
        }
        for (Node n = node; n != null; n = n.getParent()) {
            if (n.hasProperties() && Boolean.TRUE.equals(n.getProperties().get(OPAQUE))) {
                return Target.NONE;
            }
        }
        return Target.PROMPT;
    }

    /**
     * Escape that reached the scene unconsumed, with the focus in the document: nothing else wanted it (no
     * snippet, popup or extra carets to dismiss), so it cancels the recording. Escape in the find bar or a
     * prompt closes that instead, and is a step like any other.
     */
    private void onUnhandledKey(KeyEvent event) {
        if (synthetic == 0
                && service.isRecording()
                && event.getCode() == KeyCode.ESCAPE
                && !event.isShiftDown()
                && !event.isControlDown()
                && !event.isAltDown()
                && !event.isMetaDown()
                && classify(event.getTarget()) == Target.EDITOR) {
            cancelRecording();
        }
    }

    /** A click cannot be replayed; say so the first time one lands in the document during a recording. */
    private void onMousePressed(MouseEvent event) {
        if (!service.isRecording()) {
            return;
        }
        service.keySeen(); // what the click runs is the user's doing, not the last key's
        if (!mouseHintShown && classify(event.getTarget()) == Target.EDITOR) {
            mouseHintShown = true;
            host.setStatus(tr("status.macro.mouseCannotReplay"));
        }
    }

    // ---------------------------------------------------------------- commands

    /** Registers the static {@code macro.*} commands + one {@code macro.run.<id>} per saved macro. */
    void registerCommands() {
        registry.register(Command.of("macro.startRecording", this::startRecording));
        registry.register(Command.of("macro.stopRecording", this::stopRecording));
        registry.register(Command.of("macro.toggleRecording", this::toggleRecording));
        registry.register(Command.of("macro.cancelRecording", this::cancelRecordingCommand));
        registry.register(Command.of("macro.replayLast", () -> replayLast(countArgument())));
        registry.register(Command.of("macro.replayLastN", this::replayLastN));
        registry.register(Command.of("macro.nameAndSave", this::nameAndSave));
        registry.register(Command.of("macro.runSaved", this::runSaved));
        registry.register(Command.of("macro.deleteSaved", this::deleteSaved));
        registerSavedCommands();
    }

    /**
     * Whether {@code commandId} reads a prefix argument itself. The replay commands do: {@code C-u 500 C-x e}
     * must be one replay of 500 passes — one undo step, in slices, cancellable — not the dispatcher running
     * the command 500 times.
     */
    boolean isCountAware(String commandId) {
        return commandId != null
                && (commandId.equals("macro.replayLast") || commandId.startsWith(MacroService.RUN_PREFIX));
    }

    private int countArgument() {
        Integer arg = ops.prefixArgument();
        return arg == null ? 1 : MacroReplay.clampTimes(Math.abs((long) arg), MAX_REPLAY_TIMES);
    }

    boolean isRecording() {
        return service.isRecording();
    }

    private void startRecording() {
        if (service.isRecording()) {
            host.setStatus(tr("status.macro.alreadyRecording"));
            return;
        }
        if (run != null) {
            return;
        }
        service.startRecording();
        mouseHintShown = false;
        dialogHintShown = false;
        commandInFlight = null;
        watchScene(true);
        ops.setRecordingIndicator(true);
        host.setStatus(tr("status.macro.recording"));
    }

    /** One key for both: the "record macro" key of editors that have a single one. */
    private void toggleRecording() {
        if (service.isRecording()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    private void watchScene(boolean on) {
        if (recordingScene != null) {
            recordingScene.removeEventHandler(KeyEvent.KEY_PRESSED, escapeCancels);
            recordingScene.removeEventFilter(MouseEvent.MOUSE_PRESSED, mouseWatch);
            recordingScene = null;
        }
        Scene scene = on && host.window() != null ? host.window().getScene() : null;
        if (scene != null) {
            scene.addEventHandler(KeyEvent.KEY_PRESSED, escapeCancels);
            scene.addEventFilter(MouseEvent.MOUSE_PRESSED, mouseWatch);
            recordingScene = scene;
        }
    }

    private void stopRecording() {
        if (!service.isRecording()) {
            host.setStatus(tr("status.macro.notRecording"));
            return;
        }
        int n = service.stopRecording();
        watchScene(false);
        ops.setRecordingIndicator(false);
        if (n == 0) {
            // Nothing recorded: the macro recorded before is still the last one.
            host.setStatus(tr(service.hasLast() ? "status.macro.recordedNothingKept" : "status.macro.recordedNothing"));
            return;
        }
        // Always persist the just-recorded macro in the unnamed slot, so it's immediately visible in
        // Settings → Macros (and palette-/key-bindable) without an explicit "name and save" — the next
        // recording overwrites this same entry; nameAndSave() gives it a name of its own.
        service.saveLastAsPlaceholder();
        ops.refreshAllWindows();
        host.setStatus(tr("status.macro.recorded", n));
    }

    /**
     * Abandons the recording in progress: nothing is saved and the previous last macro is still there.
     * Returns whether there was a recording to cancel — the window's cancel command ({@code C-g}) asks.
     */
    boolean cancelRecording() {
        if (!service.isRecording()) {
            return false;
        }
        service.cancelRecording();
        watchScene(false);
        ops.setRecordingIndicator(false);
        host.setStatus(
                tr(service.hasLast() ? "status.macro.recordingCancelledKept" : "status.macro.recordingCancelled"));
        return true;
    }

    private void cancelRecordingCommand() {
        if (!cancelRecording()) {
            host.setStatus(tr("status.macro.notRecording"));
        }
    }

    private void replayLast(int times) {
        if (service.isRecording()) {
            stopRecording(); // C-x e while still defining: finalize, then play (Emacs behavior)
        }
        Macro last = service.last();
        if (last == null) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        play(MacroService.LAST_KEY, last, times, null);
    }

    private void replayLastN() {
        if (service.isRecording()) {
            stopRecording();
        }
        if (!service.hasLast()) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        host.promptText(
                tr("command.macro.replayLastN"),
                tr("palette.macro.countPrompt", MAX_REPLAY_TIMES),
                "1",
                s -> replayLast(MacroReplay.parseTimes(s, MAX_REPLAY_TIMES)));
    }

    private void nameAndSave() {
        if (service.isRecording()) {
            stopRecording();
        }
        if (!service.hasLast()) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        if (host.overlayHost() == null) {
            return;
        }
        TextField field = new TextField();
        field.setPrefColumnCount(32);
        com.editora.command.TextInputKeymap.installShared(field);
        VBox body = new VBox(6, new Label(tr("palette.macro.namePrompt")), field);
        // The name that was refused as "already exists": submitting it again is the answer "yes, replace".
        String[] confirmed = {null};
        OverlayInput.showSubmitting(
                host.overlayHost(),
                tr("command.macro.nameAndSave"),
                body,
                field,
                tr("dialog.save"),
                null,
                submission -> {
                    String name = field.getText() == null ? "" : field.getText().strip();
                    if (name.isEmpty()) {
                        submission.failed(tr("palette.macro.nameEmpty"));
                        return;
                    }
                    Macro existing = service.findByName(name);
                    if (existing != null && !name.equals(confirmed[0])) {
                        confirmed[0] = name;
                        submission.failed(tr("palette.macro.nameReplace", name));
                        return;
                    }
                    Macro saved = service.saveLastAs(name, existing);
                    if (saved == null) {
                        submission.failed(tr("status.macro.none"));
                        return;
                    }
                    submission.done();
                    ops.refreshAllWindows();
                    host.setStatus(tr(existing != null ? "status.macro.replaced" : "status.macro.saved", saved.name()));
                },
                false);
    }

    private void runSaved() {
        if (service.isRecording()) {
            stopRecording(); // finalize first, like replayLast — else the picker's own keys join the recording
        }
        if (service.saved().isEmpty()) {
            host.setStatus(tr("status.macro.noSaved"));
            return;
        }
        // Through the registry, like the key binding and the palette entry: one path, so the three behave
        // the same (undo boundaries, caret reveal, what a recording sees).
        QuickOpen<Macro> picker = new QuickOpen<>(
                tr("command.macro.runSaved"),
                tr("palette.macro.runPrompt"),
                () -> new ArrayList<>(service.saved()),
                MacroCoordinator::displayName,
                m -> tr("palette.macro.stepCount", m.steps().size()),
                m -> registry.run(MacroService.commandIdFor(m)));
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    private void deleteSaved() {
        if (service.saved().isEmpty()) {
            host.setStatus(tr("status.macro.noSaved"));
            return;
        }
        QuickOpen<Macro> picker = new QuickOpen<>(
                tr("command.macro.deleteSaved"),
                tr("palette.macro.deletePrompt"),
                () -> new ArrayList<>(service.saved()),
                MacroCoordinator::displayName,
                m -> tr("palette.macro.stepCount", m.steps().size()),
                this::confirmDelete);
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Asks before deleting — Enter in a picker is too easy — and takes the macro's key binding with it. */
    private void confirmDelete(Macro m) {
        Label question = new Label(tr("settings.macro.deleteConfirm", displayName(m)));
        question.setWrapText(true);
        question.setMaxWidth(420);
        // Deferred: this runs from the picker's accept handler, around which the picker takes its own card down.
        Platform.runLater(() -> OverlayInput.show(
                host.overlayHost(),
                tr("settings.macro.deleteConfirmTitle"),
                new VBox(question),
                null,
                tr("settings.macro.delete"),
                null,
                () -> delete(m),
                null,
                false));
    }

    /** Deletes a saved macro and its key binding. Package-visible for tests. */
    void delete(Macro m) {
        if (service.delete(m.id())) {
            // Without this the chord kept pointing at a command that no longer exists, and the next macro
            // given the same name inherited it.
            ops.resetShortcut(MacroService.commandIdFor(m));
            ops.refreshAllWindows();
            host.setStatus(tr("status.macro.deleted", displayName(m)));
        }
    }

    /** The name a macro is shown under: its own, or the localized label of the unnamed last recording. */
    static String displayName(Macro m) {
        return m == null || m.name().isBlank() ? tr("macro.unnamedName") : m.name();
    }

    private String titleOf(String commandId) {
        return registry.get(commandId).map(Command::title).orElse(commandId);
    }

    /** Registers one {@code macro.run.<id>} command per saved macro (palette- and key-bindable). */
    private void registerSavedCommands() {
        for (Macro m : service.saved()) {
            String id = m.id();
            // "Macro: " in front, like the built-in macro commands: a macro named Save or Find must not
            // pass for the editor's own command in the palette.
            registry.register(Command.of(
                    MacroService.commandIdFor(m),
                    tr("palette.macro.runTitle", displayName(m)),
                    () -> runSavedMacro(id)));
        }
    }

    /** Drops stale {@code macro.run.*} commands and re-registers the current saved set (this window). */
    void refreshCommands() {
        List<String> stale = new ArrayList<>();
        for (Command c : registry.all()) {
            if (c.id().startsWith(MacroService.RUN_PREFIX)) {
                stale.add(c.id());
            }
        }
        stale.forEach(registry::remove);
        registerSavedCommands();
    }

    private void runSavedMacro(String id) {
        Macro m = service.findById(id);
        if (m == null) {
            host.setError(tr("status.macro.runFailed", id));
            return;
        }
        int times = run != null ? 1 : countArgument(); // a nested run must not inherit the outer C-u count
        service.recordMacroRun(m, times); // while recording: this run is a step of the macro being defined
        play(m.id(), m, times, displayName(m));
    }

    // ---------------------------------------------------------------- replay

    /**
     * Plays {@code macro}. From inside a running replay this pushes it onto that replay — the step that
     * invoked it resumes when it ends — refusing a macro that is already running further down (it would
     * never finish) or a pile of macros deeper than anyone builds by hand.
     */
    private void play(String key, Macro macro, int times, String name) {
        MacroPlayer player = service.player();
        if (run != null && !run.delivering) {
            // Asked for from outside the replay (a menu item clicked while a long one is still running):
            // one at a time. Only a step of the running replay may start a macro inside it.
            host.setStatus(tr("status.macro.replaying", player.rootIteration(), run.times));
            return;
        }
        if (run != null) {
            MacroPlayer.Push pushed = player.push(key, macro, times);
            if (pushed == MacroPlayer.Push.CYCLE || pushed == MacroPlayer.Push.TOO_DEEP) {
                run.failure = tr(
                        pushed == MacroPlayer.Push.CYCLE ? "status.macro.cycleError" : "status.macro.depthError",
                        name == null ? tr("macro.unnamedName") : name);
            }
            return;
        }
        if (!player.begin(key, macro, times)) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        run = new Run(name, times);
        run.loop();
    }

    /** Whether a replay is in progress (between slices, or waiting for a prompt). */
    boolean isReplaying() {
        return run != null;
    }

    /** One replay, from the first step to the status line that reports how it went. */
    private final class Run {
        final String name;
        final int times;
        final MacroPlayer player = service.player();

        /** True while a command step is running — the only time a macro may be started inside this one. */
        boolean delivering;

        boolean cancelled;
        /** Why the replay must stop, already translated; null while it is fine. */
        String failure;
        /** True once the loop has yielded to the event loop at least once. */
        boolean async;

        long waitingSince;
        int readOnlySkips;
        int missingCommands;
        String firstMissing;
        boolean noBuffer;

        EditorBuffer spanBuffer;
        Runnable spanEnd;

        Run(String name, int times) {
            this.name = name;
            this.times = times;
        }

        void loop() {
            if (run != this) {
                return;
            }
            long sliceStart = System.nanoTime();
            long budget = async ? SLICE_NANOS : FIRST_SLICE_NANOS;
            try {
                while (true) {
                    if (cancelled || failure != null) {
                        finish();
                        return;
                    }
                    MacroStep step = player.peek();
                    if (step == null) {
                        finish();
                        return;
                    }
                    MacroReplay.Readiness ready = readiness(step);
                    long now = System.nanoTime();
                    if (ready != MacroReplay.Readiness.READY) {
                        if (waitingSince == 0) {
                            waitingSince = now;
                        } else if (now - waitingSince > FOCUS_WAIT_NANOS) {
                            failure = tr(
                                    ready == MacroReplay.Readiness.WAIT_FOR_PROMPT
                                            ? "status.macro.promptError"
                                            : "status.macro.overlayError");
                            continue;
                        }
                        pause(false);
                        return;
                    }
                    waitingSince = 0;
                    player.advance();
                    deliver(step);
                    if (System.nanoTime() - sliceStart > budget) {
                        pause(true);
                        return;
                    }
                }
            } catch (RuntimeException | Error e) {
                failure = tr("status.macro.replayFailed", String.valueOf(e.getMessage()));
                finish();
                throw e;
            }
        }

        /** Gives the event loop a turn, then carries on. */
        private void pause(boolean showProgress) {
            async = true;
            if (showProgress && times > 1) {
                host.setStatus(tr("status.macro.replaying", player.rootIteration(), times));
            }
            Platform.runLater(this::loop);
        }

        /** Whether the window is in the state {@code step} was recorded in. */
        private MacroReplay.Readiness readiness(MacroStep step) {
            if (step.isCommand()) {
                return MacroReplay.Readiness.READY;
            }
            OverlayHost overlay = host.overlayHost();
            boolean overlayShowing = overlay != null && overlay.isShowing();
            Node owner = focusOwner();
            EditorBuffer b = host.activeBuffer();
            return MacroReplay.readiness(
                    step.isPrompt(),
                    owner != null,
                    owner != null && b != null && b.ownsKeyTarget(owner),
                    overlayShowing,
                    overlayShowing && owner != null && overlay.contains(owner));
        }

        private Node focusOwner() {
            Scene scene = host.window() == null ? null : host.window().getScene();
            return scene == null ? null : scene.getFocusOwner();
        }

        private void deliver(MacroStep step) {
            if (step.isCommand()) {
                trackSpan();
                if (registry.get(step.value()).isEmpty()) {
                    missingCommands++;
                    if (firstMissing == null) {
                        firstMissing = step.value();
                    }
                    return;
                }
                delivering = true;
                try {
                    registry.run(step.value());
                } finally {
                    delivering = false;
                }
                return;
            }
            Node target;
            EditorBuffer buffer = null;
            if (step.isPrompt()) {
                target = focusOwner();
            } else {
                buffer = host.activeBuffer();
                target = buffer == null ? null : buffer.getFocusedArea();
                trackSpan();
            }
            if (target == null) {
                noBuffer = true;
                return;
            }
            if (target instanceof javafx.scene.control.Control control && control.getSkin() == null) {
                // A control shown this very turn has no skin yet — and so nothing that handles keys — until
                // the next pulse. A person is never that fast; a replay is. Give it its skin now.
                control.applyCss();
            }
            if (step.isKey()) {
                MacroKey.Decoded k = MacroKey.decode(step.value());
                KeyCode code = k == null ? null : keyCode(k.keyCodeName());
                if (code != null) {
                    press(target, code, k.shift(), k.ctrl(), k.alt(), k.meta(), step.isPrompt());
                }
            } else {
                if (buffer != null && !buffer.isEditable()) {
                    readOnlySkips++;
                    return; // the area would drop every character; say so at the end instead of "Replayed"
                }
                type(target, step.value(), buffer);
                return;
            }
            if (buffer != null) {
                buffer.flushDeferredCaretFixes();
            }
        }

        /** The key a step names, or null for a name that is none (a hand-edited macros.json). */
        private KeyCode keyCode(String name) {
            try {
                return KeyCode.valueOf(name);
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        /**
         * Types {@code text} at {@code target}, one key event per character, as the keyboard would.
         * {@code buffer} is the editor being typed into, or null for a prompt.
         */
        private void type(Node target, String text, EditorBuffer buffer) {
            boolean prompt = buffer == null;
            for (int i = 0; i < text.length(); ) {
                int cp = text.codePointAt(i);
                int len = Character.charCount(cp);
                if (cp == '\n' || cp == '\r' || cp == '\t') {
                    // Hand-written or older macros hold these as text; the keys are what act on them.
                    if (cp == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                        len = 2;
                    }
                    press(target, cp == '\t' ? KeyCode.TAB : KeyCode.ENTER, false, false, false, false, prompt);
                } else if (cp >= 0x20 && cp != 0x7F) {
                    fire(
                            target,
                            new KeyEvent(
                                    KeyEvent.KEY_TYPED,
                                    text.substring(i, i + len),
                                    "",
                                    KeyCode.UNDEFINED,
                                    false,
                                    false,
                                    false,
                                    false));
                }
                i += len;
                if (buffer != null) {
                    // After every character, not once per step: an abbreviation expanded by this character
                    // left a caret fix-up for "later", and the next character must land after it.
                    buffer.flushDeferredCaretFixes();
                }
                if (prompt) {
                    Node now = focusOwner(); // a key can move the focus: the rest follows it
                    if (now == null) {
                        return;
                    }
                    target = now;
                }
            }
        }

        private void press(
                Node target, KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta, boolean release) {
            fire(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, shift, ctrl, alt, meta));
            if (release && target.getScene() != null) {
                // Controls outside the editor may act on the release (a button fires on Space-up).
                fire(target, new KeyEvent(KeyEvent.KEY_RELEASED, "", "", code, shift, ctrl, alt, meta));
            }
        }

        private void fire(Node target, KeyEvent event) {
            synthetic++;
            try {
                target.fireEvent(event);
            } finally {
                synthetic--;
            }
        }

        /** Keeps one undo span open on whichever buffer the replay is editing. */
        private void trackSpan() {
            EditorBuffer b = host.activeBuffer();
            if (b == spanBuffer) {
                return;
            }
            endSpan();
            spanBuffer = b;
            spanEnd = b == null ? null : b.beginUndoSpan();
        }

        private void endSpan() {
            Runnable end = spanEnd;
            spanEnd = null;
            spanBuffer = null;
            if (end != null) {
                end.run();
            }
        }

        private void finish() {
            int done = player.rootIteration();
            player.end();
            endSpan();
            run = null;
            report(done);
        }

        /** Says what actually happened — not "Replayed macro" whatever the outcome. */
        private void report(int done) {
            if (failure != null) {
                host.setError(failure);
            } else if (cancelled) {
                host.setStatus(tr("status.macro.replayCancelled", done, times));
            } else if (missingCommands > 0) {
                host.setStatus(tr("status.macro.replayCannotRunCommand", missingCommands, firstMissing));
            } else if (noBuffer) {
                host.setStatus(tr("status.macro.replayCannotNoEditor"));
            } else if (readOnlySkips > 0) {
                host.setStatus(tr("status.macro.replayCannotReadOnly"));
            } else if (name != null) {
                host.setStatus(tr("status.macro.ran", name, times));
            } else {
                host.setStatus(tr("status.macro.replayed", times));
            }
        }
    }
}
