package com.editora.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class CommandRegistryTest {

    @Test
    void registersAndRunsCommand() {
        CommandRegistry registry = new CommandRegistry();
        AtomicInteger runs = new AtomicInteger();
        registry.register(Command.of("test.run", "Test Run", runs::incrementAndGet));

        assertTrue(registry.run("test.run"));
        assertEquals(1, runs.get());
    }

    @Test
    void runningUnknownCommandReturnsFalse() {
        CommandRegistry registry = new CommandRegistry();
        assertFalse(registry.run("nope"));
    }

    @Test
    void preservesRegistrationOrder() {
        CommandRegistry registry = new CommandRegistry();
        registry.register(Command.of("a", "A", () -> {}));
        registry.register(Command.of("b", "B", () -> {}));
        assertEquals(2, registry.all().size());
        assertEquals("a", registry.all().iterator().next().id());
    }
}
