package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.FakeDebugAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Go's goroutines in the Threads view through the window, against a {@link FakeDebugAdapter} reached as Delve is (a
 * stand-in {@code dlv}): a launch carries Settings' options (runtime goroutines hidden, every pprof label), the Threads
 * view's Hide Runtime Goroutines and Filter Goroutines… tell the running Delve through its console ({@code dlv config})
 * and save the choice, and another language's Threads view offers neither. {@code GoGoroutinesProbeFxTest} drives the
 * real Delve.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("fx")
class GoroutinesWindowFxTest {

    private static final String MAIN = "package main\n\nfunc main() {\n\tx := 1\n\t_ = x\n}\n";

    private FxWindowFixture fx;
    private DapManager manager;
    private DebugCoordinator debug;
    private FakeDebugAdapter adapter;
    private Path main;

    @BeforeAll
    void setUp() throws Exception {
        assumeFalse(java.io.File.pathSeparatorChar == ';', "the stand-in dlv is a shell script");
        FxTestSupport.bootToolkit();
        adapter = new FakeDebugAdapter(false);
        fx = FxWindowFixture.create();
        manager = FxTestSupport.field(fx.controller, "dapManager");
        debug = FxTestSupport.field(fx.controller, "debugCoordinator");
        Path root = Files.createDirectories(fx.configDir.resolve("gomod"));
        Files.writeString(root.resolve("go.mod"), "module example.com/m\n\ngo 1.22\n");
        main = Files.writeString(root.resolve("main.go"), MAIN);
        String jvm = ProcessHandle.current().info().command().orElse("java");
        Path fakeDlv = fx.configDir.resolve("dlv");
        Files.writeString(
                fakeDlv,
                "#!/bin/sh\nexec '" + jvm + "' -cp '" + System.getProperty("java.class.path")
                        + "' com.editora.dap.RelayAdapterMain announce " + adapter.port() + "\n");
        assertTrue(fakeDlv.toFile().setExecutable(true));
        FxTestSupport.runOnFx(() -> {
            fx.shared.getSettings().setDebugSupport(true);
            fx.shared.getSettings().setGoDebugPath(fakeDlv.toString());
            debug.applySupport();
        });
        await("Go debugging available", () -> call(() -> debug.debugEffectiveFor("go")));
    }

    @AfterAll
    void tearDown() throws Exception {
        if (fx != null) {
            FxTestSupport.runOnFx(manager::stop);
            fx.dispose();
        }
        if (adapter != null) {
            adapter.close();
        }
    }

    private interface Check<T> {
        T get() throws Exception;
    }

    private static <T> T call(Check<T> check) {
        try {
            return FxTestSupport.callOnFx(check::get);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static void await(String what, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + 20_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(25);
        }
    }

    private void run(String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(fx.controller, "registry")).run(command));
    }

    private static List<String> console(FakeDebugAdapter.Session session) {
        return session.evaluateRequests.stream()
                .filter(a -> "repl".equals(a.getContext()))
                .map(org.eclipse.lsp4j.debug.EvaluateArguments::getExpression)
                .toList();
    }

    @Test
    void theLaunchCarriesTheOptionsAndTheThreadsViewChangesThemLive() throws Exception {
        FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.openPath(main));
        await(
                "main.go in front",
                () -> call(() -> fx.controller.activeBuffer() != null
                        && main.getFileName()
                                .equals(fx.controller.activeBuffer().getPath().getFileName())));
        run("debug.start");
        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals(true, session.launchArgs.get("hideSystemGoroutines"), "runtime goroutines hidden by default");
        assertEquals(List.of("*"), session.launchArgs.get("showPprofLabels"), "every pprof label by default");
        assertFalse(session.launchArgs.containsKey("goroutineFilters"));
        session.stop(session.mainThread, "breakpoint");
        await("paused", () -> call(manager::state) == DapManager.State.SUSPENDED);

        run("debug.toggleRuntimeGoroutines");
        await("Delve told, one command after another", () -> console(session).size() == 4);
        assertFalse(call(() -> fx.shared.getSettings().isDebugGoHideRuntimeGoroutines()), "saved");
        assertEquals(
                List.of(
                        "dlv config hideSystemGoroutines false",
                        "dlv config goroutineFilters ",
                        "dlv config showPprofLabels -clear",
                        "dlv config showPprofLabels *"),
                console(session));

        int asked = console(session).size();
        FxTestSupport.runOnFx(() -> debug.goroutines().setFilter(" -with label job=* "));
        await("the filter told", () -> console(session).size() == asked + 4);
        assertTrue(console(session).contains("dlv config goroutineFilters -with label job=*"));
        assertEquals("-with label job=*", call(() -> fx.shared.getSettings().getDebugGoGoroutineFilter()));
        assertEquals(asked + 4, console(session).size());
        await(
                "the threads listed again",
                () -> session.requests.stream().filter("threads"::equals).count() >= 3);

        // Told again only when something changed: Settings applied as they are asks Delve nothing.
        int before = console(session).size();
        FxTestSupport.runOnFx(debug::applySupport);
        FxTestSupport.drainFx();
        assertEquals(before, console(session).size());
        FxTestSupport.runOnFx(() -> debug.goroutines().setFilter(""));
        FxTestSupport.runOnFx(debug.goroutines()::toggleRuntime);
        await("back as it was", () -> call(() -> fx.shared.getSettings().isDebugGoHideRuntimeGoroutines()));
    }
}
