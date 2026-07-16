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
     *
     * <p>A saved macro's own {@code macro.run.<slug>} command is <b>not</b> a control command and IS
     * recorded — composing macros is the point. The prefix test used to swallow those too, so invoking a
     * macro while recording silently vanished from the recording. Recursion is stopped by
     * {@link MacroPlayer}'s re-entrancy guard, not by refusing to record.
     */
    public void onCommand(String id) {
        if (!recorder.isRecording()
                || player.isPlaying()
                || id == null
                || isControlCommand(id)
                || id.equals("palette.show")) {
            return;
        }
        recorder.recordCommand(id);
    }

    /** True for the {@code macro.*} control commands (record/replay/save/delete) — but not {@code macro.run.*}. */
    private static boolean isControlCommand(String id) {
        return id.startsWith("macro.") && !id.startsWith(RUN_PREFIX);
    }

    /** Records a literally-typed character. Ignored while replaying or not recording (the idle hot path). */
    public void onTypedChar(char c) {
        if (!recorder.isRecording() || player.isPlaying()) {
            return;
        }
        recorder.recordChar(c);
    }

    /**
     * Records a bare editing/navigation key (Backspace, Delete, an arrow, Home/End, …) by {@code KeyCode}
     * name. Ignored while replaying or not recording (the idle hot path).
     */
    public void onKey(String keyCodeName) {
        if (!recorder.isRecording() || player.isPlaying()) {
            return;
        }
        recorder.recordKey(keyCodeName);
    }

    public boolean hasLast() {
        return lastMacro != null && !lastMacro.isEmpty();
    }

    public void replayLast(
            int times, Consumer<String> runCommand, Consumer<String> typeText, Consumer<String> pressKey) {
        if (hasLast()) {
            player.play(lastMacro, times, runCommand, typeText, pressKey);
        }
    }

    /**
     * Replays a saved macro by name. Returns false when there is no such macro <b>or</b> the replay was
     * dropped by the re-entrancy guard (a macro reached from inside another replay) — reporting success for
     * a replay that did nothing left the caller echoing a lie.
     */
    public boolean run(
            String name, int times, Consumer<String> runCommand, Consumer<String> typeText, Consumer<String> pressKey) {
        Macro m = config.getMacroStore().find(name);
        if (m == null) {
            return false;
        }
        return player.play(m, times, runCommand, typeText, pressKey);
    }

    /**
     * Saves the last-recorded macro under a name (replacing any same-named one); returns it, or null when
     * there's nothing to save, the name is blank, or it would {@linkplain #slugClash collide} with another
     * macro's command id.
     */
    public Macro saveLast(String name) {
        if (!hasLast() || name == null || name.isBlank()) {
            return null;
        }
        String clean = name.strip();
        if (slugClash(clean)) {
            return null;
        }
        Macro m = new Macro(clean, lastMacro.steps());
        config.getMacroStore().put(m);
        config.saveMacros();
        return m;
    }

    /**
     * True when {@code name} would produce the same {@code macro.run.<slug>} command id as a <b>different</b>
     * saved macro. The store keys by name but commands key by slug, so {@code my macro} and {@code my-macro}
     * (or any two symbol-only names, which both fall back to {@code macro}) registered one id twice — last
     * write won, and the shadowed macro became unreachable by command or keybinding.
     */
    public boolean slugClash(String name) {
        String id = commandIdFor(name);
        for (Macro m : saved()) {
            if (!m.name().equalsIgnoreCase(name == null ? "" : name.strip())
                    && commandIdFor(m.name()).equals(id)) {
                return true;
            }
        }
        return false;
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

    /** Command-id prefix for a saved macro's synthetic run command. */
    public static final String RUN_PREFIX = "macro.run.";

    /** The synthetic command id under which a saved macro is registered (so it is palette- and key-bindable). */
    public static String commandIdFor(String name) {
        return RUN_PREFIX + slug(name);
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
