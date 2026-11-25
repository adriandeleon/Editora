package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugLanguages;
import com.editora.dap.InstalledAdapter;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of PowerShell against the real pwsh and PowerShell Editor Services, in a window: PSES as the language
 * server reports PSScriptAnalyzer's warning for an alias; F5 on a script stops at a breakpoint in a function with its
 * parameters in Variables, the Debug console evaluates an expression, and the script's output — its output, host and
 * warning streams — reaches the Debug console as it runs on; Run File runs it on pwsh. PSES is found where Editora
 * looks for it ({@code ~/.editora/plugins/dap/powershell}, VS Code's extension) or at {@code -Dpses.path}: its release
 * folder. Run with {@code ./mvnw test -Dtest=PowerShellProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class PowerShellProbeFxTest {

    private static final String SCRIPT = """
            param([string]$Name = "world")
            function Add-Numbers($a, $b) {
                $sum = $a + $b
                return $sum
            }
            $x = Add-Numbers 2 3
            Write-Output "sum is $x, hello $Name"
            Write-Host "host line"
            Write-Warning "careful"
            gci | Out-Null
            """;

    /** {@code $sum = $a + $b}, 0-based. */
    private static final int LINE = 2;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    /** PSES's release folder; skips without the opt-in, pwsh or PSES. */
    private static Path pses() {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(
                Path.of(ProcessRunner.resolveExecutable(List.of("pwsh")).getFirst())
                        .isAbsolute(),
                "needs pwsh");
        String configured = System.getProperty("pses.path", "");
        String found = DapManager.locateExternal(DebugLanguages.POWERSHELL, configured);
        assumeTrue(found != null, "needs PowerShell Editor Services (-Dpses.path=<its release folder>)");
        Path start = Path.of(found);
        return start.getParent().getParent(); // <release>/PowerShellEditorServices/Start-EditorServices.ps1
    }

    /** A window with {@code script} open, PSES installed as Editora's own (a link), language server and debugger on. */
    private static FxWindowFixture window(Path dir, Path release, Path script) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        Files.createDirectories(configDir.resolve("plugins/dap"));
        Files.createSymbolicLink(configDir.resolve("plugins/dap/powershell"), release);
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setLspSupport(true);
        seed.getSettings().setPowershellLspEnabled(true);
        seed.getSettings()
                .setPowershellDebugPath(release.resolve("PowerShellEditorServices")
                        .resolve(InstalledAdapter.POWERSHELL.file())
                        .toString());
        seed.getTrustStore().trust(dir.toRealPath());
        assertTrue(seed.saveSettings());
        seed.saveTrust();
        seed.shutdown();
        return FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(script, 0, 0)), true, c -> {});
    }

    @Test
    void psesServesAScriptAndDebugsIt(@TempDir Path dir) throws Exception {
        Path release = pses();
        Path project = Files.createDirectories(dir.resolve("app")).toRealPath();
        Path script = Files.writeString(project.resolve("calc.ps1"), SCRIPT);
        FxWindowFixture fx = window(dir, release, script);
        try {
            MainController c = fx.controller;
            LspCoordinator lsp = FxTestSupport.field(c, "lspCoordinator");
            List<LspDiagnostic> found = FxTestSupport.awaitFxValue(
                    () -> {
                        Map<Path, List<LspDiagnostic>> problems = FxTestSupport.field(lsp, "problems");
                        List<LspDiagnostic> all =
                                problems.values().stream().flatMap(List::stream).toList();
                        return all.stream()
                                        .anyMatch(
                                                d -> String.valueOf(d.message()).contains("Get-ChildItem"))
                                ? all
                                : null;
                    },
                    "PSScriptAnalyzer's alias warning from PSES",
                    Duration.ofSeconds(120));
            assertTrue(found.stream().anyMatch(d -> d.startLine() == 9), found.toString());

            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("PowerShell debugging available", 60, () -> call(() -> debug.debugEffectiveFor("powershell")));
            EditorBuffer b = call(c::activeBuffer);
            await("Run File's ▶", 30, () -> call(b::isRunnable));
            FxTestSupport.runOnFx(() -> b.toggleBreakpoint(LINE));
            run(c, "debug.start");
            await(
                    "a stop at calc.ps1:" + (LINE + 1),
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == LINE),
                    () -> console(debug));
            awaitRow(debug, "$a", "2");
            awaitRow(debug, "$b", "3");
            String[] evaluated = {null};
            FxTestSupport.runOnFx(() -> dap.evaluate(
                    "$a * 10", debug.panel().callStack().getFirst().id(), "repl", r -> evaluated[0] = r.text()));
            await("the console's evaluation", 30, () -> "20".equals(evaluated[0]));

            FxTestSupport.runOnFx(dap::resume);
            await("the session's end", 60, () -> !call(dap::isActive), () -> console(debug));
            String out = console(debug);
            assertTrue(out.contains("sum is 5, hello world"), out);
            assertTrue(out.contains("host line"), out);
            assertTrue(out.contains("WARNING: careful"), out);

            run(c, "file.run");
            await("Run File's output", 60, () -> runOutput(c).contains("sum is 5, hello world"), () -> runOutput(c));
        } finally {
            fx.dispose();
        }
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    private static String console(DebugCoordinator debug) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                .getText());
    }

    private static String runOutput(MainController c) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(c.runCoordinator.panel(), "output")
                .getText());
    }

    private static void awaitRow(DebugCoordinator debug, String name, String value) throws InterruptedException {
        await(
                name + " = " + value,
                60,
                () -> call(() -> {
                    javafx.scene.control.TreeView<DebugPanel.VarRow> tree =
                            FxTestSupport.field(debug.panel(), "variables");
                    return tree.getRoot() != null && find(tree.getRoot(), name, value);
                }));
    }

    private static boolean find(javafx.scene.control.TreeItem<DebugPanel.VarRow> item, String name, String value) {
        DebugPanel.VarRow row = item.getValue();
        if (row != null && name.equals(row.name()) && value.equals(row.value())) {
            return true;
        }
        for (var child : item.getChildren()) {
            if (find(child, name, value)) {
                return true;
            }
        }
        return false;
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

    private static void await(
            String what, int seconds, BooleanSupplier condition, java.util.function.Supplier<String> why)
            throws InterruptedException {
        try {
            await(what, seconds, condition);
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + why.get(), e);
        }
    }
}
