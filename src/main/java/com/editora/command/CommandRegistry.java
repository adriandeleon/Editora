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
        command.run();
        if (executionListener != null) {
            executionListener.accept(id);
        }
        return true;
    }

    public Collection<Command> all() {
        return commands.values();
    }
}
