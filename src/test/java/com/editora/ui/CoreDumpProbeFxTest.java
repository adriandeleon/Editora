package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import javafx.scene.control.ListView;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.dap.DebugLanguages;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Open Core Dump against real cores and debuggers: a C program and a Go program each dump their own
 * core with {@code gcore} while totalling an order, and the core opens in a window — on CodeLLDB and on Delve — with
 * the program found in it, the stack shown at {@code total}, the order's fields there, and stepping refused. Run with
 * {@code ./mvnw test -Dtest=CoreDumpProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs {@code gcore} (GDB's), and
 * {@code cc} with CodeLLDB, or {@code go} with Delve.
 */
@Tag("probe")
@Tag("fx")
class CoreDumpProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** The program lets any process trace it (Yama's ptrace_scope 1 lets only its parent), so gcore may. */
    private static final String C_PROGRAM = """
            #include <stdio.h>
            #include <stdlib.h>
            #include <unistd.h>
            #include <sys/prctl.h>

            struct order { int n; const char *who; };

            static int total(struct order *o, const char *core) {
                char cmd[4096];
                if (o->n > 1) {
                    snprintf(cmd, sizeof cmd, "gcore -o '%s' %d > /dev/null 2>&1", core, (int) getpid());
                    return system(cmd);
                }
                return o->n * 2;
            }

            int main(int argc, char **argv) {
                prctl(PR_SET_PTRACER, PR_SET_PTRACER_ANY, 0, 0, 0);
                struct order a = {1, "ann"}, b = {2, "bob"};
                total(&a, argv[1]);
                return total(&b, argv[1]) == 0 ? 0 : 1;
            }
            """;

    private static final String GO_PROGRAM = """
            package main

            import (
            \t"fmt"
            \t"os"
            \t"os/exec"
            \t"strconv"
            \t"syscall"
            )

            type Order struct {
            \tN   int
            \tWho string
            }

            func total(o *Order) int {
            \tif o.N > 1 {
            \t\tif err := exec.Command("gcore", "-o", os.Args[1], strconv.Itoa(os.Getpid())).Run(); err != nil {
            \t\t\tfmt.Println(err)
            \t\t\tos.Exit(2)
            \t\t}
            \t}
            \treturn o.N * 2
            }

            func main() {
            \tsyscall.RawSyscall(syscall.SYS_PRCTL, 0x59616d61, ^uintptr(0), 0)
            \ta, b := &Order{1, "ann"}, &Order{2, "bob"}
            \tfmt.Println(total(a), total(b))
            }
            """;

    @Test
    void c(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("gcore") && onPath("cc"), "needs gcore and cc");
        String codelldb = DapManager.locateExternal(DebugLanguages.RUST, System.getProperty("codelldb.path", ""));
        assumeTrue(codelldb != null, "needs CodeLLDB");
        Path root = Files.createDirectory(dir.resolve("shelf")).toRealPath();
        Path source = Files.writeString(root.resolve("shelf.c"), C_PROGRAM);
        exec(root, "cc", "-g", "-O0", "-o", "shelf", source.toString());
        Path core = dump(root, root.resolve("shelf").toString());
        probe(dir, source, core, "n", st -> st.setRustDebugPath(codelldb));
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(onPath("gcore") && onPath("go"), "needs gcore and go");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        Path root = Files.createDirectory(dir.resolve("orders")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/orders\n\ngo 1.22\n");
        Path source = Files.writeString(root.resolve("main.go"), GO_PROGRAM);
        exec(root, "go", "build", "-gcflags=all=-N -l", "-o", "orders", ".");
        Path core = dump(root, root.resolve("orders").toString());
        probe(dir, source, core, "N", st -> {});
    }

    /** Runs {@code program}, which dumps its own core under {@code root}; that core. */
    private static Path dump(Path root, String program) throws Exception {
        exec(root, program, root.resolve("core").toString());
        try (Stream<Path> files = Files.list(root)) {
            return files.filter(p -> p.getFileName().toString().startsWith("core."))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError("no core written in " + root));
        }
    }

    private static void probe(
            Path dir, Path source, Path core, String field, java.util.function.Consumer<com.editora.config.Settings> st)
            throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        st.accept(seed.getSettings());
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(source, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            String language = source.toString().endsWith(".go") ? "go" : "cpp";
            await(language + " debugging available", 120, () -> call(() -> debug.debugEffectiveFor(language)));
            FxTestSupport.runOnFx(() -> debug.chooseFile = chooser -> core.toFile());
            FxTestSupport.runOnFx(
                    () -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.openCoreDump"));
            await(
                    "the core's stop",
                    180,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED && selected(debug) != null));
            assertTrue(
                    call(() -> selected(debug).name()).contains("total"),
                    call(() -> selected(debug).name()));
            assertEquals("2", awaitField(debug, "o", field), "order b");
            assertTrue(call(dap::isPostMortem));

            FxTestSupport.runOnFx(dap::stepOver);
            FxTestSupport.drainFx();
            assertEquals(DapManager.State.SUSPENDED, call(dap::state), "nothing ran");
            StatusBar bar = FxTestSupport.field(c, "statusBar");
            assertEquals(
                    tr("status.debug.coreCannotRun"),
                    call(() -> FxTestSupport.<javafx.scene.control.Label>field(bar, "echo")
                            .getText()));
            FxTestSupport.runOnFx(dap::stop);
            await("closed", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static DapModels.StackFrameInfo selected(DebugCoordinator debug) {
        return FxTestSupport.<ListView<DapModels.StackFrameInfo>>field(debug.panel(), "stack")
                .getSelectionModel()
                .getSelectedItem();
    }

    /**
     * {@code name}'s field {@code field}, once its row is loaded. A pointer's fields are a level down, under the one
     * row of what it points to (Delve's {@code *o}, named "").
     */
    private static String awaitField(DebugCoordinator debug, String name, String field) throws Exception {
        await(name, 60, () -> call(() -> row(debug, name) != null));
        FxTestSupport.runOnFx(() -> row(debug, name).setExpanded(true));
        await(
                name + "." + field,
                60,
                () -> call(() -> {
                    TreeItem<DebugPanel.VarRow> item = row(debug, name);
                    if (child(item, field) != null) {
                        return true;
                    }
                    if (item.getChildren().size() == 1) {
                        item.getChildren().getFirst().setExpanded(true);
                        return child(item.getChildren().getFirst(), field) != null;
                    }
                    return false;
                }));
        return call(() -> {
            TreeItem<DebugPanel.VarRow> item = row(debug, name);
            TreeItem<DebugPanel.VarRow> direct = child(item, field);
            return (direct != null ? direct : child(item.getChildren().getFirst(), field))
                    .getValue()
                    .value();
        });
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

    private static boolean onPath(String tool) {
        return Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                .isAbsolute();
    }

    private static void exec(Path dir, String... command) throws Exception {
        Process p = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of(command)))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(p.waitFor(300, TimeUnit.SECONDS), String.join(" ", command) + " timed out");
        assertEquals(0, p.exitValue(), String.join(" ", command) + ": " + out);
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
