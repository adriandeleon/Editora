package com.editora.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression (CI, PR #1859): Debug on an Elixir file right after its project opened started ElixirLS's debug adapter
 * while the language server was still booting from the same install; the adapter waited for its build lock and died
 * before the program started. The debugger now starts once the server has.
 */
class ElixirDebugLaunchWaitTest {

    private final List<Runnable> later = new ArrayList<>();
    private final AtomicInteger started = new AtomicInteger();
    private final AtomicInteger said = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();

    private void gate(AtomicBoolean starting, long deadline) {
        ElixirDebugLaunch.whenStarted(
                starting::get, deadline, now::get, later::add, said::incrementAndGet, started::incrementAndGet);
    }

    private void tick() {
        List<Runnable> due = new ArrayList<>(later);
        later.clear();
        due.forEach(Runnable::run);
    }

    @Test
    void noServerStartingStartsAtOnce() {
        gate(new AtomicBoolean(false), 100);
        assertEquals(1, started.get());
        assertEquals(0, said.get(), "nothing to wait for, nothing said");
        assertEquals(List.of(), later);
    }

    @Test
    void aStartingServerIsWaitedForAndSaidOnce() {
        AtomicBoolean starting = new AtomicBoolean(true);
        gate(starting, 100);
        assertEquals(0, started.get(), "not while the server boots");
        tick();
        tick();
        assertEquals(0, started.get());
        assertEquals(1, said.get(), "the status says it once");

        starting.set(false);
        tick();
        assertEquals(1, started.get(), "started once the server has");
        assertEquals(List.of(), later);
    }

    @Test
    void aServerThatNeverStartsIsWaitedForOnlySoLong() {
        gate(new AtomicBoolean(true), 100);
        now.set(99);
        tick();
        assertEquals(0, started.get());
        now.set(100);
        tick();
        assertEquals(1, started.get(), "the debugger starts anyway");
    }
}
