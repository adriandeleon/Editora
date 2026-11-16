package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.Settings;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.DebugLanguages;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Show Value As against real debuggers, in a window: stopped with whole numbers of several types, the
 * Debug: Show Selected Value As … commands show each in the radix chosen — a negative number as its two's complement
 * where the debugger names its type, an array's elements, a character's code — from what each debugger writes (java-
 * debug's bare {@code A} and its sign-extended hexadecimal, debugpy's {@code -0x2a}, Delve's {@code 255 = 0xff}, GDB's
 * {@code 65 'A'}, CodeLLDB's {@code '\xff'}, js-debug's BigInt). Run with {@code ./mvnw test
 * -Dtest=ValueFormatsProbeFxTest -Dgroups=probe -Dlsp.probe=true} (and {@code -Dnode.probe=true} for js-debug).
 */
@Tag("probe")
@Tag("fx")
class ValueFormatsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));

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
                        int n = 42; int neg = -42; long big = 1L << 40; byte b = -1; short s = 300; char c = 'A';
                        byte[] bytes = {1, -1, 127}; boolean flag = true; double d = 1.5; Integer boxed = 7;
                        System.out.println(n);
                    }
                }
                """);
        probe(
                dir,
                file,
                "java",
                4,
                List.of(
                        show("neg", "Hex", "0xffffffd6"),
                        show("c", "Decimal", "65"),
                        show("bytes", "Binary", "0b11111111", "1"),
                        hexOn("neg", "Default", "0xffffffffffffffd6"),
                        show("neg", "Decimal", "-42")),
                st -> {
                    st.setLspSupport(true);
                    st.setJavaLspEnabled(true);
                    st.setJavaLspCommand(jdtls.toString());
                    st.setJavaDebugPluginPath(plugin.toString());
                });
    }

    @Test
    void python(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("m.py"), """
                def main():
                    n = 42
                    neg = -42
                    c = 'A'
                    data = b'\\x01\\xff'
                    flag = True
                    d = 1.5
                    print(n)
                main()
                """);
        probe(
                dir,
                file,
                "python",
                7,
                List.of(
                        show("neg", "Hex", "-0x2a"),
                        show("n", "Character", "'*'"),
                        hexOn("neg", "Default", "-0x2a"),
                        show("neg", "Decimal", "-42")),
                st -> {});
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        Path root = Files.createDirectory(dir.resolve("nums")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/nums\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), """
                package main

                import "fmt"

                func main() {
                \tn := 42
                \tneg := -42
                \tvar b int8 = -1
                \tvar u uint8 = 255
                \tr := 'A'
                \tbs := []byte{1, 255}
                \tflag := true
                \tfmt.Println(n, neg, b, u, r, bs, flag)
                }
                """);
        probe(
                dir,
                file,
                "go",
                12,
                List.of(
                        show("u", "Decimal", "255"),
                        show("b", "Hex", "-0x1"),
                        show("r", "Character", "'A'"),
                        show("bs", "Hex", "0xff", "[1]")),
                st -> {});
    }

    private static final String C = """
            #include <stdio.h>
            int main(void) {
                int n = 42;
                int neg = -42;
                signed char b = -1;
                unsigned char u = 255;
                char c = 'A';
                long long big = 1LL << 40;
                unsigned char bytes[3] = {1, 255, 127};
                double d = 1.5;
                printf("%d\\n", n + neg + b + u + c + (int) big + bytes[0] + (int) d);
                return 0;
            }
            """;

    @Test
    void gdb(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("c")).toRealPath().resolve("n.c"), C);
        probe(
                dir,
                file,
                "cpp",
                10,
                List.of(
                        show("c", "Decimal", "65"),
                        show("b", "Decimal", "-1"),
                        show("neg", "Hex", "-0x2a"),
                        hexOn("neg", "Default", "0xffffffd6"),
                        // GDB names no type: its hexadecimal is read as it is written.
                        show("neg", "Decimal", "4294967254")),
                st -> st.setCppDebugger("gdb"));
    }

    @Test
    void codelldb(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.CPP, "") != null, "needs CodeLLDB");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("c")).toRealPath().resolve("n.c"), C);
        probe(
                dir,
                file,
                "cpp",
                10,
                List.of(
                        show("b", "Decimal", "-1"),
                        show("u", "Decimal", "255"),
                        show("neg", "Hex", "0xffffffd6"),
                        show("c", "Binary", "0b1000001"),
                        show("bytes", "Hex", "0xff", "[1]")),
                st -> st.setCppDebugger("codelldb"));
    }

    @Test
    void javascript(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe") && Boolean.getBoolean("node.probe"), "opt-in: Node probes");
        assumeTrue(DebugAdapterLocator.locateJsDebugServer("", HOME).isPresent(), "needs js-debug");
        Path file = Files.writeString(
                Files.createDirectory(dir.resolve("app")).toRealPath().resolve("main.js"), """
                function main() {
                  let n = 42;
                  let neg = -42;
                  let big = 2n ** 40n;
                  let c = 'A';
                  const bytes = new Uint8Array([1, 255]);
                  console.log(n, neg, big, c, bytes);
                }
                main();
                """);
        probe(
                dir,
                file,
                "javascript",
                6,
                List.of(
                        show("neg", "Hex", "-0x2a"),
                        show("big", "Hex", "0x10000000000"),
                        show("bytes", "Binary", "0b11111111", "1")),
                st -> {});
    }

    /**
     * One step: {@code command} (Show Selected Value As …) on variable {@code name} — {@code element} of it, when
     * given, is the row read — then the row shows {@code expected}. {@code hexOn} first turns Show Numbers as
     * Hexadecimal on.
     */
    private record Step(
            String name,
            String radix,
            String expected,
            @Nullable String element,
            boolean hexOn) {}

    private static Step show(String name, String radix, String expected) {
        return new Step(name, radix, expected, null, false);
    }

    private static Step show(String name, String radix, String expected, String element) {
        return new Step(name, radix, expected, element, false);
    }

    private static Step hexOn(String name, String radix, String expected) {
        return new Step(name, radix, expected, null, true);
    }

    private static void probe(
            Path dir, Path file, String language, int line, List<Step> steps, Consumer<Settings> settings)
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
            List<String> failures = new ArrayList<>();
            for (Step step : steps) {
                if (step.hexOn()) {
                    FxTestSupport.runOnFx(() -> debug.panel().toggleHexValues());
                    Thread.sleep(1500); // the values fetched again
                }
                await(step.name(), 60, () -> call(() -> variable(debug, step.name()) != null));
                if (step.element() != null) {
                    FxTestSupport.runOnFx(() -> variable(debug, step.name()).setExpanded(true));
                    await(step.name() + " " + step.element(), 60, () -> call(() -> element(debug, step) != null));
                }
                FxTestSupport.runOnFx(() -> {
                    tree(debug).getSelectionModel().select(variable(debug, step.name()));
                    ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.showSelectedAs" + step.radix());
                });
                try {
                    await(step.toString(), 10, () -> step.expected().equals(call(() -> shown(debug, step))));
                } catch (AssertionError e) {
                    failures.add(step + " showed " + call(() -> shown(debug, step)) + " for "
                            + call(() -> element(debug, step).getValue()));
                }
            }
            assertEquals(List.of(), failures);
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static TreeView<DebugPanel.VarRow> tree(DebugCoordinator debug) {
        return FxTestSupport.field(debug.panel(), "variables");
    }

    /** The variable called {@code name} in the first scope (the locals), expanded. */
    private static @Nullable TreeItem<DebugPanel.VarRow> variable(DebugCoordinator debug, String name) {
        for (TreeItem<DebugPanel.VarRow> scope : tree(debug).getRoot().getChildren()) {
            DebugPanel.VarRow v = scope.getValue();
            if (v == null || v.kind() != DebugPanel.Kind.SCOPE || v.name().equals(tr("debugpanel.watches"))) {
                continue;
            }
            scope.setExpanded(true);
            for (TreeItem<DebugPanel.VarRow> child : scope.getChildren()) {
                if (child.getValue() != null && child.getValue().name().equals(name)) {
                    return child;
                }
            }
            return null;
        }
        return null;
    }

    private static @Nullable TreeItem<DebugPanel.VarRow> element(DebugCoordinator debug, Step step) {
        TreeItem<DebugPanel.VarRow> v = variable(debug, step.name());
        if (v == null || step.element() == null) {
            return v;
        }
        for (TreeItem<DebugPanel.VarRow> child : v.getChildren()) {
            if (child.getValue() != null && child.getValue().name().equals(step.element())) {
                return child;
            }
        }
        return null;
    }

    private static @Nullable String shown(DebugCoordinator debug, Step step) {
        TreeItem<DebugPanel.VarRow> item = element(debug, step);
        return item == null ? null : debug.panel().shown(item, item.getValue()).value();
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
