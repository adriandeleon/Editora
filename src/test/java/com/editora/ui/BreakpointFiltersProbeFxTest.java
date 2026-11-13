package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.config.StopFilters;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of breakpoint filters against debugpy and java-debug with JDT LS, in a window. The program calls
 * {@code total()} on two orders from two callers, then once more in a thread named {@code worker-1}. A thread filter
 * stops only in the worker; a caller filter with an instance filter (the order marked at the first stop) stops only
 * when that order is totalled from {@code place}. Run with {@code ./mvnw test -Dtest=BreakpointFiltersProbeFxTest
 * -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class BreakpointFiltersProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), """
                import threading
                class Order:
                    def __init__(self, n):
                        self.n = n
                    def total(self):
                        return self.n * 2
                def place(o):
                    return o.total()
                def report(o):
                    return o.total()
                a, b = Order(1), Order(2)
                place(a); report(a); place(b); place(a)
                t = threading.Thread(target=lambda: place(b), name="worker-1")
                t.start(); t.join()
                print("done")
                """);
        probe(dir, file, "python", 5, "self", st -> {});
    }

    @Test
    void java(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = HOME.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Path plugin = DebugAdapterLocator.locate("", HOME).orElse(null);
        assumeTrue(Files.isExecutable(jdtls) && plugin != null, "needs JDT LS and java-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("loose")).toRealPath().resolve("Main.java"), """
                public class Main {
                    static class Order {
                        final int n;
                        Order(int n) { this.n = n; }
                        int total() {
                            return n * 2;
                        }
                    }
                    static int place(Order o) { return o.total(); }
                    static int report(Order o) { return o.total(); }
                    public static void main(String[] args) throws Exception {
                        Order a = new Order(1), b = new Order(2);
                        place(a); report(a); place(b); place(a);
                        Thread t = new Thread(() -> place(b), "worker-1");
                        t.start(); t.join();
                        System.out.println("done");
                    }
                }
                """);
        probe(dir, file, "java", 5, "this", st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
    }

    /** Both runs on {@code file}, its breakpoint on 0-based {@code line} in {@code total()}. */
    private static void probe(
            Path dir,
            Path file,
            String language,
            int line,
            String self,
            java.util.function.Consumer<com.editora.config.Settings> settings)
            throws Exception {
        FxWindowFixture fx = window(dir, file, settings);
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(line);
                b.getBreakpointManager().setFilters(line, new StopFilters("worker-*", "", ""));
            });

            // Run 1: only the worker thread stops.
            run(c, "debug.start");
            awaitStop(dap, debug, line);
            await(
                    "the stopped thread named",
                    30,
                    () -> call(() ->
                                    FxTestSupport
                                            .<javafx.scene.control.ComboBox<com.editora.dap.DapModels.ThreadInfo>>field(
                                                    debug.panel(), "threads")
                                            .getValue())
                            != null);
            String thread = call(() -> DebugPanelText.threadName(
                    FxTestSupport.<javafx.scene.control.ComboBox<com.editora.dap.DapModels.ThreadInfo>>field(
                                    debug.panel(), "threads")
                            .getValue()
                            .name()));
            assertEquals("worker-1", thread, "the one stop is the worker's");
            assertEquals("2", awaitField(debug, self, "n"), "and on order b");
            FxTestSupport.runOnFx(dap::resume);
            await("the end", 120, () -> !call(dap::isActive));

            // Run 2: from place, on order a (marked at the first stop, which stops as usual).
            FxTestSupport.runOnFx(
                    () -> b.getBreakpointManager().setFilters(line, new StopFilters("", "place", "first")));
            run(c, "debug.start");
            awaitStop(dap, debug, line);
            assertEquals("1", awaitField(debug, self, "n"), "place(a): no object marked yet");
            String a = call(() -> row(debug, self).getValue().value());
            assertTrue(call(() -> debug.s().marks.mark(a, "first")), "marked: " + a);
            int stops = call(() -> debug.s().stopSerial);
            FxTestSupport.runOnFx(dap::resume);
            // report(a) and place(b) go on unseen; place(a) stops.
            await(
                    "the next stop shown",
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && debug.s().stopSerial > stops
                            && debug.s().quietStop != debug.s().stopSerial));
            assertEquals("1", awaitField(debug, self, "n"), "order a again");
            String caller = call(() -> debug.panel().callStack().get(1).name());
            assertTrue(StopFilterCheck.matches("place", StopFilterCheck.callerNames(caller)), caller);
            FxTestSupport.runOnFx(dap::resume);
            await("the end, the worker's place(b) unseen", 120, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static void awaitStop(DapManager dap, DebugCoordinator debug, int line) throws InterruptedException {
        await(
                "a stop on line " + (line + 1),
                180,
                () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                        && debug.s().quietStop != debug.s().stopSerial
                        && !debug.panel().callStack().isEmpty()
                        && debug.panel().callStack().getFirst().line() == line));
    }

    /** {@code self}'s field {@code name}, once its row is loaded. */
    private static String awaitField(DebugCoordinator debug, String self, String name) throws Exception {
        await(self, 60, () -> call(() -> row(debug, self) != null));
        FxTestSupport.runOnFx(() -> row(debug, self).setExpanded(true));
        await(self + "." + name, 60, () -> call(() -> child(row(debug, self), name) != null));
        return call(() -> child(row(debug, self), name).getValue().value());
    }

    private static TreeItem<DebugPanel.VarRow> row(DebugCoordinator debug, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        if (tree.getRoot() == null) {
            return null;
        }
        for (TreeItem<DebugPanel.VarRow> top : tree.getRoot().getChildren()) {
            TreeItem<DebugPanel.VarRow> found = child(top, name);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static TreeItem<DebugPanel.VarRow> child(TreeItem<DebugPanel.VarRow> item, String name) {
        if (item == null) {
            return null;
        }
        return item.getChildren().stream()
                .filter(i -> i.getValue() != null && i.getValue().name().equals(name))
                .findFirst()
                .orElse(null);
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    private static FxWindowFixture window(
            Path dir, Path file, java.util.function.Consumer<com.editora.config.Settings> settings) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        return FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
