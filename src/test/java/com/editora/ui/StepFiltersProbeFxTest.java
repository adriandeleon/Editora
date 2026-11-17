package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.DebugLanguages;
import com.editora.dap.StepFilter;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Don't step into against real debuggers, in a window: stopped on a line that calls filtered code, Step
 * Into does not stop inside it. debugpy skips a module ({@code rules}), java-debug a class ({@code skipClasses}) and
 * js-debug a file ({@code skipFiles}) themselves; Delve has no such option, and Editora steps out of {@code strings.*}
 * when a step ends there. Run with {@code ./mvnw test -Dtest=StepFiltersProbeFxTest -Dgroups=probe -Dlsp.probe=true}
 * (and {@code -Dnode.probe=true} for js-debug).
 */
@Tag("probe")
@Tag("fx")
class StepFiltersProbeFxTest {

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
        Path root = Files.createDirectory(dir.resolve("app")).toRealPath();
        Files.writeString(root.resolve("helper.py"), """
                def shout(s):
                    t = s.upper()
                    return t + "!"
                """);
        Path file = Files.writeString(root.resolve("m.py"), """
                import helper
                def main():
                    a = helper.shout("hi")
                    b = len(a)
                    print(a, b)
                main()
                """);
        probe(dir, file, "python", "helper", 2, "m.py", 3, st -> {});
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
                    public static void main(String[] args) {
                        String a = Util.shout("hi");
                        int b = a.length();
                        System.out.println(a + b);
                    }
                }
                class Util {
                    static String shout(String s) {
                        String t = s.toUpperCase();
                        return t + "!";
                    }
                }
                """);
        probe(dir, file, "java", "Util", 2, "Main.java", 3, st -> {
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
        Path root = Files.createDirectory(dir.resolve("shout")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/shout\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), """
                package main

                import (
                \t"fmt"
                \t"strings"
                )

                func main() {
                \ta := strings.Repeat("hi", 3)
                \tfmt.Println(a)
                }
                """);
        // Delve steps into strings.Repeat; Editora steps out of it, back to main at the call (or just after it).
        probe(dir, file, "go", "strings.*", 8, "main.go", -1, st -> {});
    }

    @Test
    void javascript(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path root = Files.createDirectory(dir.resolve("app")).toRealPath();
        Files.writeString(root.resolve("helper.js"), """
                function shout(s) {
                  const t = s.toUpperCase();
                  return t + '!';
                }
                module.exports = { shout };
                """);
        Path file = Files.writeString(root.resolve("main.js"), """
                const helper = require('./helper');
                function main() {
                  const a = helper.shout('hi');
                  const b = a.length;
                  console.log(a, b);
                }
                main();
                """);
        probe(dir, file, "javascript", "helper.js", 2, "main.js", 3, st -> {});
    }

    /**
     * Stops on 0-based {@code line} of {@code file} with {@code pattern} the one Don't step into filter, steps into
     * the call there, and expects the next stop in {@code stopFile} on 0-based {@code stopLine} (-1: any line of it).
     */
    private static void probe(
            Path dir,
            Path file,
            String language,
            String pattern,
            int line,
            String stopFile,
            int stopLine,
            Consumer<Settings> settings)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings()
                .setDebugStepFilterList(new java.util.ArrayList<>(List.of(new StepFilter(true, language, pattern))));
        settings.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            CommandRegistry registry = FxTestSupport.field(c, "registry");
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(line));
            FxTestSupport.runOnFx(() -> registry.run("debug.start"));
            await(
                    "a stop on line " + (line + 1),
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == line));
            int before = call(() -> debug.s().stopSerial);
            FxTestSupport.runOnFx(() -> registry.run("debug.stepInto"));
            await(
                    "the step shown",
                    60,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && debug.s().stopSerial > before
                            && debug.s().quietStop != debug.s().stopSerial
                            && !debug.panel().callStack().isEmpty()));
            DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
            String where = top.name() + " at " + top.file() + ":" + (top.line() + 1);
            assertTrue(top.file() != null && top.file().getFileName().toString().equals(stopFile), where);
            if (stopLine >= 0) {
                assertEquals(stopLine, top.line(), where);
            }
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
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

    private static void await(String what, int seconds, BooleanSupplier condition) throws InterruptedException {
        Await.until(condition, what, Duration.ofSeconds(seconds));
    }
}
