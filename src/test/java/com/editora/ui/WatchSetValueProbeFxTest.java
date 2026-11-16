package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Set Value on a watch against real debuggers, in a window: stopped with a local {@code x} and an
 * object's field, each watch is set and read back — through {@code setExpression} where the debugger has it (debugpy,
 * js-debug), by setting the variable a watch names where it does not (Delve), else by evaluating the assignment
 * (java-debug). Run with {@code ./mvnw test -Dtest=WatchSetValueProbeFxTest -Dgroups=probe -Dlsp.probe=true} (and
 * {@code -Dnode.probe=true} for js-debug).
 */
@Tag("probe")
@Tag("fx")
class WatchSetValueProbeFxTest {

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
                class O:
                    def __init__(self):
                        self.n = 1
                def main():
                    x = 1
                    o = O()
                    print(x, o.n)
                main()
                """);
        probe(dir, file, "python", 6, List.of("x", "o.n"), st -> {});
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
                    static class O { int n = 1; }
                    public static void main(String[] args) {
                        int x = 1;
                        O o = new O();
                        System.out.println(x + o.n);
                    }
                }
                """);
        probe(dir, file, "java", 5, List.of("x", "o.n"), st -> {
            st.setLspSupport(true);
            st.setJavaLspEnabled(true);
            st.setJavaLspCommand(jdtls.toString());
            st.setJavaDebugPluginPath(plugin.toString());
        });
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        Path root = Files.createDirectory(dir.resolve("watch")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/watch\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), """
                package main

                import "fmt"

                func main() {
                \tx := 1
                \tfmt.Println(x)
                }
                """);
        probe(dir, file, "go", 6, List.of("x"), st -> {});
    }

    @Test
    void javascript(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("main.js"), """
                function main() {
                  let x = 1;
                  const o = { n: 1 };
                  console.log(x, o.n);
                }
                main();
                """);
        probe(dir, file, "javascript", 3, List.of("x", "o.n"), st -> {});
    }

    /** Stops on 0-based {@code line} of {@code file}, sets each of {@code watches} to 41 and reads it back. */
    private static void probe(
            Path dir, Path file, String language, int line, List<String> watches, Consumer<Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await(
                    "a stop on line " + (line + 1),
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));
            await("the locals", 60, () -> call(() -> scopeHolding(debug, "x") > 0));
            List<String> failures = new ArrayList<>();
            for (String watch : watches) {
                int frameId = call(() -> debug.panel().selectedFrameId());
                int ref = call(() -> scopeHolding(debug, watch));
                AtomicReference<String> answered = new AtomicReference<>();
                FxTestSupport.runOnFx(() -> dap.setExpression(watch, "41", frameId, ref, answered::set));
                await(watch + " set", 30, () -> answered.get() != null);
                AtomicReference<String> now = new AtomicReference<>();
                FxTestSupport.runOnFx(() -> dap.evaluateFull(watch, frameId, "watch", r -> now.set(r.result())));
                await(watch + " read back", 30, () -> now.get() != null);
                if (!"41".equals(now.get())) {
                    failures.add(watch + " = " + now.get() + " (set answered " + answered.get() + ")");
                }
            }
            assertEquals(List.of(), failures);
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    /** The panel's own lookup: the scope shown that holds a variable called {@code name}, else 0. */
    private static int scopeHolding(DebugCoordinator debug, String name) {
        return (Integer) FxTestSupport.call(debug.panel(), "scopeHolding", new Class<?>[] {String.class}, name);
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
