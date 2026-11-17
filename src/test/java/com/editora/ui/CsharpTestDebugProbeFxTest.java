package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opt-in probe of C#'s Debug Test at Caret against the real .NET SDK and netcoredbg, in a window: in an xUnit test
 * project, with the caret in a test and a breakpoint in it, Debug Test at Caret runs that test under {@code dotnet
 * test} (its host waiting for a debugger), netcoredbg attaches, the breakpoint stops with the test's locals, and the
 * run ends when the program goes on. Run with {@code ./mvnw test -Dtest=CsharpTestDebugProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}; needs {@code dotnet} (10 or later, its NuGet packages reachable) and netcoredbg
 * ({@code -Dnetcoredbg.path=…} when Editora does not find it).
 */
@Tag("probe")
@Tag("fx")
class CsharpTestDebugProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    static final String PROJECT = """
            <Project Sdk="Microsoft.NET.Sdk">
              <PropertyGroup>
                <TargetFramework>net10.0</TargetFramework>
                <ImplicitUsings>enable</ImplicitUsings>
                <Nullable>enable</Nullable>
                <IsPackable>false</IsPackable>
              </PropertyGroup>
              <ItemGroup>
                <PackageReference Include="Microsoft.NET.Test.Sdk" Version="17.14.1" />
                <PackageReference Include="xunit" Version="2.9.3" />
                <PackageReference Include="xunit.runner.visualstudio" Version="3.1.4" />
              </ItemGroup>
              <ItemGroup>
                <Using Include="Xunit" />
              </ItemGroup>
            </Project>
            """;

    /** 0-based line 9 is {@code Assert.Equal(21, price * qty);}. */
    static final String TESTS = """
            namespace Shop.Tests;

            public class PriceTests
            {
                [Fact]
                public void Total()
                {
                    int price = 7;
                    int qty = 3;
                    Assert.Equal(21, price * qty);
                }

                [Fact]
                public void Other()
                {
                    Assert.True(true);
                }
            }
            """;

    @Test
    void theTestAtTheCaretStopsAtItsBreakpointWithItsLocals(@TempDir Path dir) throws Exception {
        String configured = CsharpDebugProbeFxTest.assumeDotnetAndNetcoredbg();
        Path root = Files.createDirectory(dir.resolve("Shop.Tests")).toRealPath();
        Files.writeString(root.resolve("Shop.Tests.csproj"), PROJECT);
        Path tests = Files.writeString(root.resolve("PriceTests.cs"), TESTS);
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        if (!configured.isBlank()) {
            seed.getSettings().setCsharpDebugPath(configured);
        }
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(tests, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("C# debugging available", 120, () -> call(() -> debug.debugEffectiveFor("csharp")));
            FxTestSupport.runOnFx(() -> c.config.getTrustStore().trust(root)); // the trust question, answered
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(9);
                b.getArea().moveTo(8, 0); // in Total()
            });
            FxTestSupport.runOnFx(
                    () -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("test.debugAtCaret"));
            await(
                    "a stop on the breakpoint",
                    300,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));
            DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
            assertEquals(9, top.line(), "on the breakpoint: " + top);
            assertTrue(top.name().contains("Total"), "in the test: " + top.name());
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            await(
                    "the test's locals",
                    60,
                    () -> call(() -> {
                        String all = variables(tree);
                        return all.contains("price=7") && all.contains("qty=3");
                    }));
            FxTestSupport.runOnFx(dap::resume);
            await("the run over", 120, () -> !call(dap::isActive));
            await("dotnet test ended", 120, () -> call(() -> debug.s().testHost == null));
        } finally {
            fx.dispose();
        }
    }

    private static String variables(TreeView<DebugPanel.VarRow> tree) {
        StringBuilder sb = new StringBuilder();
        for (TreeItem<DebugPanel.VarRow> scope : tree.getRoot().getChildren()) {
            scope.setExpanded(true);
            for (TreeItem<DebugPanel.VarRow> v : scope.getChildren()) {
                sb.append(v.getValue().name())
                        .append('=')
                        .append(v.getValue().value())
                        .append("; ");
            }
        }
        return sb.toString();
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
