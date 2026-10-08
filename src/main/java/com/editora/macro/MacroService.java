package com.editora.macro;

import java.util.List;

import com.editora.config.ConfigManager;
import com.editora.config.MacroStore;

/**
 * Per-window model of keyboard macros: owns the {@link MacroRecorder} and the {@link MacroPlayer} cursor,
 * tracks the just-recorded ("last") macro, and reads/writes the app-global saved macros through the window's
 * {@link ConfigManager} (which delegates the store to the shared config). UI-agnostic — delivering a step
 * (running a command, firing a key) is the caller's job, so this class depends only on lower layers (config
 * + the pure macro model).
 */
public final class MacroService {

    /** Command-id prefix for a saved macro's synthetic run command. */
    public static final String RUN_PREFIX = "macro.run.";

    /** The cycle-check key of the unsaved last recording (it has no id). */
    public static final String LAST_KEY = "\u0000last";

    private final ConfigManager config;
    private final MacroRecorder recorder = new MacroRecorder();
    private final MacroPlayer player = new MacroPlayer();
    /** The macro just recorded in this window — the target of "replay last" until another is recorded. */
    private Macro lastMacro;
    /**
     * True from a recorded key or text step until the next real key press: commands that run in between are
     * consequences of that step (Enter in a picker running the picked command) and replaying the step runs
     * them again, so recording them too would run them twice.
     */
    private boolean consequence;

    public MacroService(ConfigManager config) {
        this.config = config;
    }

    public MacroPlayer player() {
        return player;
    }

    public boolean isRecording() {
        return recorder.isRecording();
    }

    public boolean isReplaying() {
        return player.isPlaying();
    }

    public void startRecording() {
        consequence = false;
        recorder.start();
    }

    /**
     * Stops recording and returns how many steps were captured. A non-empty recording becomes the last
     * macro; an empty one changes nothing, so starting and stopping by accident does not cost the macro
     * recorded before (as in Emacs).
     */
    public int stopRecording() {
        List<MacroStep> steps = recorder.stop();
        if (!steps.isEmpty()) {
            lastMacro = new Macro("", steps);
        }
        return steps.size();
    }

    /** Abandons the recording in progress; the previous last macro stays. */
    public void cancelRecording() {
        recorder.cancel();
    }

    /** A real key press arrived: whatever runs next is the user's doing, not the previous step's. */
    public void keySeen() {
        consequence = false;
    }

    /**
     * Records an executed command. Ignored while replaying (replay is never recorded), for the
     * {@code macro.*} control commands and {@code palette.show} (so recording via the palette captures the
     * chosen command, not the act of opening the palette), and for a command that ran as the consequence of
     * a recorded key (see {@link #consequence}).
     *
     * <p>A saved macro's own {@code macro.run.<id>} command is a legitimate step, but it is recorded by
     * {@link #recordMacroRun} when the run starts rather than here: this hook fires when the command
     * returns, which for a long replay is before it has finished.
     */
    public void onCommand(String id) {
        if (!recorder.isRecording()
                || player.isPlaying()
                || id == null
                || id.startsWith("macro.")
                || id.equals("palette.show")
                || consequence) {
            return;
        }
        recorder.recordCommand(id);
    }

    /** Records that the saved macro {@code macro} was invoked {@code times} times while recording. */
    public void recordMacroRun(Macro macro, int times) {
        if (!recorder.isRecording() || player.isPlaying() || macro == null) {
            return;
        }
        for (int i = 0; i < times; i++) {
            recorder.recordCommand(commandIdFor(macro));
        }
    }

    /** Records typed text. Ignored while replaying or not recording (the idle hot path). */
    public void onText(String chars, boolean prompt) {
        if (!recorder.isRecording() || player.isPlaying()) {
            return;
        }
        recorder.recordText(chars, prompt);
        consequence = true;
    }

    /** Records a key press as a {@link MacroKey} token. Ignored while replaying or not recording. */
    public void onKey(String keyToken, boolean prompt) {
        if (!recorder.isRecording() || player.isPlaying()) {
            return;
        }
        recorder.recordKey(keyToken, prompt);
        consequence = true;
    }

    /**
     * The macro "replay last" plays: the one recorded in this window, else the one the store kept from the
     * last recording anywhere — so it survives a restart and is there in a second window.
     */
    public Macro last() {
        if (lastMacro != null && !lastMacro.isEmpty()) {
            return lastMacro;
        }
        Macro stored = store().placeholder();
        return stored != null && !stored.isEmpty() ? stored : null;
    }

    public boolean hasLast() {
        return last() != null;
    }

    private MacroStore store() {
        return config.getMacroStore();
    }

    public List<Macro> saved() {
        return store().macros;
    }

    public Macro findById(String id) {
        return store().findById(id);
    }

    public Macro findByName(String name) {
        return store().findByName(name == null ? null : name.strip());
    }

    public boolean isPlaceholder(Macro macro) {
        return store().isPlaceholder(macro);
    }

    /**
     * Stores the last recording in the unnamed slot (replacing the previous unnamed one), so it is visible
     * in Settings and bindable at once. Returns the stored entry, or null when there is nothing recorded.
     */
    public Macro saveLastAsPlaceholder() {
        if (lastMacro == null || lastMacro.isEmpty()) {
            return null;
        }
        MacroStore store = store();
        Macro existing = store.placeholder();
        String id = existing != null ? existing.id() : MacroIds.unique(MacroStore.DEFAULT_LAST_ID, store.ids());
        Macro m = new Macro(id, "", lastMacro.steps());
        store.put(m);
        store.lastId = id;
        config.saveMacros();
        return m;
    }

    /**
     * Saves the last macro under {@code name}: over {@code replace} when given (keeping its id and so its key
     * binding), else as a new macro with a fresh id. The unnamed slot is dropped when it holds this same
     * recording — it has a real name now — but not when another window has since recorded over it. Returns
     * the saved macro, or null when there is nothing to save or the name is blank.
     */
    public Macro saveLastAs(String name, Macro replace) {
        Macro source = last();
        if (source == null || name == null || name.isBlank()) {
            return null;
        }
        MacroStore store = store();
        String id = replace != null && store.findById(replace.id()) != null
                ? replace.id()
                : MacroIds.forName(name, store.ids());
        Macro m = new Macro(id, name.strip(), source.steps());
        store.put(m);
        Macro unnamed = store.placeholder();
        if (unnamed != null && !unnamed.id().equals(id) && unnamed.steps().equals(source.steps())) {
            store.removeById(unnamed.id());
        }
        config.saveMacros();
        return m;
    }

    public boolean delete(String id) {
        boolean removed = store().removeById(id);
        if (removed) {
            config.saveMacros();
        }
        return removed;
    }

    /** The synthetic command id under which a saved macro is registered (so it is palette- and key-bindable). */
    public static String commandIdFor(Macro macro) {
        return RUN_PREFIX + macro.id();
    }
}
