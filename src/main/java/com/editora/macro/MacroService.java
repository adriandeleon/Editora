package com.editora.macro;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;

/**
 * Per-window coordinator for keyboard macros: owns the {@link MacroRecorder} and {@link MacroPlayer},
 * tracks the just-recorded ("last") macro, and reads/writes the app-global saved macros through the
 * window's {@link ConfigManager} (which delegates the store to the shared config). UI-agnostic — the editor
 * effects of replay (running a command, typing text) are supplied as callbacks by the caller, so this class
 * depends only on lower layers (config + the pure macro model).
 */
public final class MacroService {

    private final ConfigManager config;
    private final MacroRecorder recorder = new MacroRecorder();
    private final MacroPlayer player = new MacroPlayer();
    /** The macro just recorded (unnamed) — the target of "replay last" until saved under a name. */
    private Macro lastMacro;

    public MacroService(ConfigManager config) {
        this.config = config;
    }

    public boolean isRecording() {
        return recorder.isRecording();
    }

    public boolean isReplaying() {
        return player.isPlaying();
    }

    public void startRecording() {
        recorder.start();
    }

    /** Stops recording, retains the result as the "last macro", and returns its step count. */
    public int stopRecording() {
        List<MacroStep> steps = recorder.stop();
        lastMacro = new Macro("", steps);
        return steps.size();
    }

    /**
     * Records an executed command. Ignored while replaying (replay is never recorded) and for the
     * {@code macro.*} control commands and {@code palette.show} (so recording via the palette captures the
     * chosen command, not the act of opening the palette).
     */
    public void onCommand(String id) {
        if (!recorder.isRecording()
                || player.isPlaying()
                || id == null
                || id.startsWith("macro.")
                || id.equals("palette.show")) {
            return;
        }
        recorder.recordCommand(id);
    }

    /** Records a literally-typed character. Ignored while replaying or not recording (the idle hot path). */
    public void onTypedChar(char c) {
        if (!recorder.isRecording() || player.isPlaying()) {
            return;
        }
        recorder.recordChar(c);
    }

    public boolean hasLast() {
        return lastMacro != null && !lastMacro.isEmpty();
    }

    public void replayLast(int times, Consumer<String> runCommand, Consumer<String> typeText) {
        if (hasLast()) {
            player.play(lastMacro, times, runCommand, typeText);
        }
    }

    /** Replays a saved macro by name; returns false if no such macro exists. */
    public boolean run(String name, int times, Consumer<String> runCommand, Consumer<String> typeText) {
        Macro m = config.getMacroStore().find(name);
        if (m == null) {
            return false;
        }
        player.play(m, times, runCommand, typeText);
        return true;
    }

    /** Saves the last-recorded macro under a name (replacing any same-named one); returns it, or null. */
    public Macro saveLast(String name) {
        if (!hasLast() || name == null || name.isBlank()) {
            return null;
        }
        Macro m = new Macro(name.strip(), lastMacro.steps());
        config.getMacroStore().put(m);
        config.saveMacros();
        return m;
    }

    public List<Macro> saved() {
        return config.getMacroStore().macros;
    }

    public boolean delete(String name) {
        boolean removed = config.getMacroStore().remove(name);
        if (removed) {
            config.saveMacros();
        }
        return removed;
    }

    /** The synthetic command id under which a saved macro is registered (so it is palette- and key-bindable). */
    public static String commandIdFor(String name) {
        return "macro.run." + slug(name);
    }

    /** A filesystem/command-id-safe slug of a macro name. */
    public static String slug(String name) {
        String s = (name == null ? "" : name)
                .trim()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "macro" : s;
    }
}
