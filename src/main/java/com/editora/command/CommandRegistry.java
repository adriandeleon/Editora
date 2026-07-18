package com.editora.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/** Central registry of all commands, keyed by id. Insertion order is preserved for the palette. */
public class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    /** Notified with each command id that actually ran (after the command executes). Null = no listener. */
    private Consumer<String> executionListener;

    /** Reentrancy depth of {@link #run}: the listener fires only for the outermost call, so a command that
     *  synchronously delegates to another records what the user invoked, not the internal decomposition. */
    private int runDepth;

    public void register(Command command) {
        commands.put(command.id(), command);
    }

    /** Removes a command by id (used to drop synthetic macro-run commands on rename/delete). */
    public void remove(String id) {
        commands.remove(id);
    }

    public Optional<Command> get(String id) {
        return Optional.ofNullable(commands.get(id));
    }

    /**
     * Installs a listener notified with every command id that runs (used by the macro recorder). The
     * listener fires <em>after</em> the command executes, so a control command like {@code macro.startRecording}
     * has already changed recording state before the notification — and the listener must itself ignore the
     * {@code macro.*} ids so it never records the act of recording.
     */
    public void setExecutionListener(Consumer<String> listener) {
        this.executionListener = listener;
    }

    public boolean run(String id) {
        Command command = commands.get(id);
        if (command == null) {
            return false;
        }
        runDepth++;
        try {
            command.run();
        } finally {
            runDepth--;
        }
        // Fire the execution/macro listener only for the OUTERMOST run. If a command synchronously delegates
        // to another (`registry.run(...)` from its body), firing per-run would record the inner command first
        // (its run() returns before the outer's does) — reversing the order and, on replay, running the inner
        // command twice. A macro should record what the user invoked, not the internal decomposition (#449).
        // A command that throws propagates out of the try, so it is never recorded (as before the change).
        if (runDepth == 0 && executionListener != null) {
            executionListener.accept(id);
        }
        return true;
    }

    public Collection<Command> all() {
        return commands.values();
    }
}
