package com.editora.command;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Central registry of all commands, keyed by id. Insertion order is preserved for the palette. */
public class CommandRegistry {

    private final Map<String, Command> commands = new LinkedHashMap<>();

    public void register(Command command) {
        commands.put(command.id(), command);
    }

    public Optional<Command> get(String id) {
        return Optional.ofNullable(commands.get(id));
    }

    public boolean run(String id) {
        Command command = commands.get(id);
        if (command == null) {
            return false;
        }
        command.run();
        return true;
    }

    public Collection<Command> all() {
        return commands.values();
    }
}
