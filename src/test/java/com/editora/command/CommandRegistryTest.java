package com.editora.command;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void executionListenerRecordsOnlyTheOutermostCommandForNestedRuns() {
        // #449: a command that synchronously delegates to another must record what the user invoked (the
        // outer id), not the inner decomposition — and never in reverse order.
        CommandRegistry registry = new CommandRegistry();
        java.util.List<String> recorded = new java.util.ArrayList<>();
        registry.setExecutionListener(recorded::add);
        registry.register(Command.of("inner", "Inner", () -> {}));
        registry.register(Command.of("outer", "Outer", () -> registry.run("inner"))); // delegates synchronously

        registry.run("outer");
        assertEquals(java.util.List.of("outer"), recorded, "only the user-invoked outer command is recorded");

        // A direct (non-nested) run still records normally.
        recorded.clear();
        registry.run("inner");
        assertEquals(java.util.List.of("inner"), recorded);
    }

    @Test
    void aThrowingCommandIsNotRecorded() {
        CommandRegistry registry = new CommandRegistry();
        java.util.List<String> recorded = new java.util.ArrayList<>();
        registry.setExecutionListener(recorded::add);
        registry.register(Command.of("boom", "Boom", () -> {
            throw new RuntimeException("x");
        }));
        try {
            registry.run("boom");
        } catch (RuntimeException ignored) {
            // expected — propagates
        }
        assertTrue(recorded.isEmpty(), "a command that throws is not recorded");
    }

    @Test
    void theRunScopeBracketsOnlyTheOutermostRun() {
        CommandRegistry registry = new CommandRegistry();
        java.util.List<String> events = new java.util.ArrayList<>();
        registry.setRunScope(id -> {
            events.add("before " + id);
            return () -> events.add("after " + id);
        });
        registry.register(Command.of("inner", "Inner", () -> events.add("run inner")));
        registry.register(Command.of("outer", "Outer", () -> registry.run("inner")));

        registry.run("outer");

        assertEquals(java.util.List.of("before outer", "run inner", "after outer"), events);
    }

    @Test
    void aRunScopeMayDeclineAndAnUnknownCommandIsNotBracketed() {
        CommandRegistry registry = new CommandRegistry();
        AtomicInteger asked = new AtomicInteger();
        registry.setRunScope(id -> {
            asked.incrementAndGet();
            return null; // nothing to do afterwards
        });
        registry.register(Command.of("known", "Known", () -> {}));

        assertTrue(registry.run("known"));
        assertFalse(registry.run("missing"));
        assertEquals(1, asked.get(), "only a command that exists is bracketed");
    }

    @Test
    void theBoundaryHookRunsOnBothSidesOfTheOutermostCommandOnly() {
        CommandRegistry registry = new CommandRegistry();
        java.util.List<String> events = new java.util.ArrayList<>();
        registry.setBoundaryHook(() -> events.add("boundary"));
        registry.register(Command.of("inner", "Inner", () -> events.add("inner")));
        registry.register(Command.of("outer", "Outer", () -> {
            events.add("outer");
            registry.run("inner");
        }));
        registry.run("outer");
        assertEquals(java.util.List.of("boundary", "outer", "inner", "boundary"), events);
    }

    @Test
    void theBoundaryHookStillClosesAfterAThrowingCommand() {
        CommandRegistry registry = new CommandRegistry();
        java.util.List<String> events = new java.util.ArrayList<>();
        registry.setBoundaryHook(() -> events.add("boundary"));
        registry.register(Command.of("boom", "Boom", () -> {
            throw new RuntimeException("x");
        }));
        try {
            registry.run("boom");
        } catch (RuntimeException ignored) {
            // expected — propagates
        }
        assertEquals(java.util.List.of("boundary", "boundary"), events);
    }
}
