package com.editora.ui;

import java.util.ArrayList;
import java.util.List;

import com.editora.command.Command;
import com.editora.command.CommandRegistry;
import com.editora.config.ConfigManager;
import com.editora.editor.EditorBuffer;
import com.editora.macro.Macro;
import com.editora.macro.MacroService;

import static com.editora.i18n.Messages.tr;

/**
 * Keyboard macros (record / replay / save / run), extracted from {@link MainController} via the
 * {@link CoordinatorHost} pattern. Owns the per-window {@link MacroService} + the capture hooks
 * ({@link #onCommand}/{@link #onTypedChar}, fed from the command registry + key dispatcher) + the
 * {@code macro.*} commands. Reaches the window through the shared host plus a small {@link Ops} extension
 * (the cross-window command re-register, which needs {@code WindowManager}).
 */
final class MacroCoordinator {

    /** Window hooks beyond {@link CoordinatorHost}: re-register {@code macro.run.*} in every open window, and
     *  show/hide the status-bar "● REC" recording indicator. */
    interface Ops {
        void refreshAllWindows();

        void setRecordingIndicator(boolean recording);
    }

    private final CoordinatorHost host;
    private final Ops ops;
    private final CommandRegistry registry;
    private final MacroService service;

    MacroCoordinator(ConfigManager config, CommandRegistry registry, CoordinatorHost host, Ops ops) {
        this.host = host;
        this.ops = ops;
        this.registry = registry;
        this.service = new MacroService(config);
    }

    /** Capture hook for the command registry's execution listener (no-op unless recording). */
    void onCommand(String commandId) {
        service.onCommand(commandId);
    }

    /** Capture hook for the key dispatcher's typed-char listener (no-op unless recording). */
    void onTypedChar(char c) {
        service.onTypedChar(c);
    }

    /** Capture hook for the key dispatcher's bare-key listener (Backspace/Delete/arrows/…). */
    void onKey(String macroKeyToken) {
        service.onKey(macroKeyToken);
    }

    /**
     * Gate for both capture hooks: record a key only when it is aimed at the active editor's own area. The
     * hooks are scene filters, so they otherwise also see everything typed into the command palette, the find
     * bar, a picker or a tool-window filter field — which was then replayed into the document.
     */
    boolean isRecordableTarget(javafx.event.EventTarget target) {
        EditorBuffer b = host.activeBuffer();
        return b != null && b.ownsKeyTarget(target);
    }

    /** Registers the static {@code macro.*} commands + one {@code macro.run.<slug>} per saved macro. */
    void registerCommands() {
        registry.register(Command.of("macro.startRecording", this::startRecording));
        registry.register(Command.of("macro.stopRecording", this::stopRecording));
        registry.register(Command.of("macro.replayLast", () -> replayLast(1)));
        registry.register(Command.of("macro.replayLastN", this::replayLastN));
        registry.register(Command.of("macro.nameAndSave", this::nameAndSave));
        registry.register(Command.of("macro.runSaved", this::runSaved));
        registry.register(Command.of("macro.deleteSaved", this::deleteSaved));
        registerSavedCommands();
    }

    private void startRecording() {
        if (service.isRecording()) {
            host.setStatus(tr("status.macro.alreadyRecording"));
            return;
        }
        service.startRecording();
        ops.setRecordingIndicator(true);
        host.setStatus(tr("status.macro.recording"));
    }

    private void stopRecording() {
        if (!service.isRecording()) {
            host.setStatus(tr("status.macro.notRecording"));
            return;
        }
        int n = service.stopRecording();
        ops.setRecordingIndicator(false);
        if (n > 0) {
            // Always persist the just-recorded macro under a fixed name, so it's immediately visible in
            // Settings → Macros (and palette-/key-bindable) without an explicit "name and save" — the next
            // recording overwrites this same entry; nameAndSave() below renames it away to keep it.
            service.saveLast(tr("macro.unnamedName"));
            ops.refreshAllWindows();
        }
        host.setStatus(tr("status.macro.recorded", n));
    }

    private void replayLast(int times) {
        if (service.isRecording()) {
            stopRecording(); // C-x e while still defining: finalize, then play (Emacs behavior)
        }
        if (!service.hasLast()) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        service.replayLast(times, registry::run, this::typeText, this::pressKey);
        host.setStatus(times == 1 ? tr("status.macro.replayed") : tr("status.macro.replayedN", times));
    }

    private void replayLastN() {
        if (service.isRecording()) {
            stopRecording();
        }
        if (!service.hasLast()) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        host.promptText(tr("command.macro.replayLastN"), tr("palette.macro.countPrompt"), "1", s -> {
            int times = parseTimes(s);
            replayLast(times);
        });
    }

    /** Repeat count for "replay N times", clamped: replay is synchronous on the FX thread, so an
     *  unbounded count (a fat-fingered "99999999") freezes the window with no way to cancel. */
    static final int MAX_REPLAY_TIMES = 10_000;

    static int parseTimes(String s) {
        try {
            int n = Integer.parseInt(s == null ? "" : s.trim());
            return Math.max(1, Math.min(n, MAX_REPLAY_TIMES));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void nameAndSave() {
        if (service.isRecording()) {
            stopRecording();
        }
        if (!service.hasLast()) {
            host.setStatus(tr("status.macro.none"));
            return;
        }
        host.promptText(tr("command.macro.nameAndSave"), tr("palette.macro.namePrompt"), "", name -> {
            if (name == null || name.isBlank()) {
                return;
            }
            Macro m = service.saveLast(name);
            if (m != null) {
                // stopRecording() auto-saved this same recording under the fixed placeholder name — drop
                // that entry now that it has a real one, so there's no leftover duplicate (unless the user
                // chose the placeholder name itself).
                String unnamed = tr("macro.unnamedName");
                if (!m.name().equals(unnamed)) {
                    service.delete(unnamed);
                }
                ops.refreshAllWindows();
                host.setStatus(tr("status.macro.saved", m.name()));
            }
        });
    }

    private void runSaved() {
        if (service.isRecording()) {
            stopRecording(); // finalize first, like replayLast — else the picker's own keys join the recording
        }
        if (service.saved().isEmpty()) {
            host.setStatus(tr("status.macro.noSaved"));
            return;
        }
        QuickOpen<Macro> picker = new QuickOpen<>(
                tr("command.macro.runSaved"),
                tr("palette.macro.runPrompt"),
                () -> new ArrayList<>(service.saved()),
                Macro::name,
                m -> tr("palette.macro.stepCount", m.steps().size()),
                m -> service.run(m.name(), 1, registry::run, this::typeText, this::pressKey));
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
                Macro::name,
                m -> tr("palette.macro.stepCount", m.steps().size()),
                m -> {
                    if (service.delete(m.name())) {
                        ops.refreshAllWindows();
                        host.setStatus(tr("status.macro.deleted", m.name()));
                    }
                });
        picker.setOverlayHost(host.overlayHost());
        picker.show(host.window());
    }

    /** Replay TEXT steps through the active buffer's typing path (auto-close / auto-indent reproduced). */
    private void typeText(String text) {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.typeString(text);
        }
    }

    /** Replay KEY steps (Backspace/Delete/arrows/…) at the active buffer's focused area. */
    private void pressKey(String keyCodeName) {
        EditorBuffer b = host.activeBuffer();
        if (b != null) {
            b.pressKey(keyCodeName);
        }
    }

    /** Registers one {@code macro.run.<slug>} command per saved macro (palette- and key-bindable). */
    private void registerSavedCommands() {
        for (Macro m : service.saved()) {
            String name = m.name();
            registry.register(Command.of(
                    MacroService.commandIdFor(name),
                    name,
                    () -> service.run(name, 1, registry::run, this::typeText, this::pressKey)));
        }
    }

    /** Drops stale {@code macro.run.*} commands and re-registers the current saved set (this window). */
    void refreshCommands() {
        List<String> stale = new ArrayList<>();
        for (Command c : registry.all()) {
            if (c.id().startsWith("macro.run.")) {
                stale.add(c.id());
            }
        }
        stale.forEach(registry::remove);
        registerSavedCommands();
    }
}
