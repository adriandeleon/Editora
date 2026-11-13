package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.dap.DebugLanguages;
import com.editora.dap.TypeRenderer;
import com.editora.editor.EditorBuffer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of type renderers against java-debug with JDT LS, debugpy and Delve, in a window. Each program has a
 * Money value with an Owner, and a list of two; a renderer shows Money as its amount and currency with the owner's
 * name as a child. The local, a list element (by the adapter's own element expression), the renderer's child, the
 * [Raw View] and a watch are checked. Run with {@code ./mvnw test -Dtest=TypeRenderersProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class TypeRenderersProbeFxTest {

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
                import java.util.*;
                public class Main {
                    record Owner(String name) {}
                    static class Money { final int amount; final String currency; final Owner owner;
                        Money(int a, String c, Owner o) { amount = a; currency = c; owner = o; } }
                    public static void main(String[] args) {
                        Money total = new Money(12, "EUR", new Owner("Ann"));
                        List<Money> items = new ArrayList<>(List.of(total, new Money(3, "USD", new Owner("Bo"))));
                        System.out.println(total.amount + items.size());
                    }
                }
                """);
        probe(
                dir,
                file,
                "java",
                8,
                new TypeRenderer(
                        true, "java", "Money", "this.amount + \" \" + this.currency", "owner = this.owner.name()"),
                "0",
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
                class Owner:
                    def __init__(self, name):
                        self.name = name
                class Money:
                    def __init__(self, amount, currency, owner):
                        self.amount = amount
                        self.currency = currency
                        self.owner = owner
                total = Money(12, "EUR", Owner("Ann"))
                items = [total, Money(3, "USD", Owner("Bo"))]
                print(total.amount + len(items))
                """);
        probe(
                dir,
                file,
                "python",
                10,
                new TypeRenderer(
                        true, "python", "Money", "str(self.amount) + ' ' + self.currency", "owner = self.owner.name"),
                "0",
                st -> {});
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DapManager.locateExternal(DebugLanguages.GO, "") != null, "needs Delve (dlv)");
        String go = com.editora.process.ProcessRunner.resolveExecutable(List.of("go"))
                .getFirst();
        assumeTrue(Path.of(go).isAbsolute(), "needs go on the PATH");
        Path root = Files.createDirectory(dir.resolve("shop")).toRealPath();
        Files.writeString(root.resolve("go.mod"), "module example.com/shop\n\ngo 1.22\n");
        Path file = Files.writeString(root.resolve("main.go"), """
                package main

                import "fmt"

                type Owner struct{ Name string }

                type Money struct {
                \tAmount   int
                \tCurrency string
                \tOwner    *Owner
                }

                func main() {
                \ttotal := Money{12, "EUR", &Owner{"Ann"}}
                \titems := []Money{total, {3, "USD", &Owner{"Bo"}}}
                \tfmt.Println(total.Amount + len(items))
                }
                """);
        // Delve's expressions do not join strings: the value is the currency alone.
        probe(
                dir,
                file,
                "go",
                15,
                new TypeRenderer(true, "go", "main.Money", "this.Currency", "owner = this.Owner.Name"),
                "[0]",
                st -> {});
    }

    /**
     * Debugs {@code file} to 0-based {@code line} with renderer {@code r} and checks {@code total} (rendered, its
     * owner child, its raw view), {@code items}' first element (named {@code first}) and a watch of {@code total}.
     */
    private static void probe(
            Path dir,
            Path file,
            String language,
            int line,
            TypeRenderer r,
            String first,
            java.util.function.Consumer<com.editora.config.Settings> settings)
            throws Exception {
        FxWindowFixture fx = window(dir, file, st -> {
            st.setDebugTypeRenderers(new java.util.ArrayList<>(List.of(r)));
            settings.accept(st);
        });
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
            String expected = "go".equals(language) ? "EUR" : "12 EUR";
            String rendered = awaitValue(debug, DebugPanel.Kind.VARIABLE, "total", expected);

            // Its renderer's child, then the debugger's own fields under [Raw View].
            FxTestSupport.runOnFx(
                    () -> row(debug, DebugPanel.Kind.VARIABLE, "total").setExpanded(true));
            awaitValue(debug, DebugPanel.Kind.VARIABLE, "owner", "python".equals(language) ? "'Ann'" : "\"Ann\"");
            FxTestSupport.runOnFx(() -> find(row(debug, DebugPanel.Kind.VARIABLE, "total"), TypeRendering.RAW_VIEW)
                    .setExpanded(true));
            await(
                    "the raw view's fields",
                    60,
                    () -> call(() -> {
                        TreeItem<DebugPanel.VarRow> raw =
                                find(row(debug, DebugPanel.Kind.VARIABLE, "total"), TypeRendering.RAW_VIEW);
                        return raw != null && raw.getChildren().size() >= 3;
                    }));

            // A list element, by the adapter's own expression for it.
            FxTestSupport.runOnFx(
                    () -> row(debug, DebugPanel.Kind.VARIABLE, "items").setExpanded(true));
            await(
                    "items' first element rendered",
                    60,
                    () -> call(() -> {
                        TreeItem<DebugPanel.VarRow> e = find(row(debug, DebugPanel.Kind.VARIABLE, "items"), first);
                        return e != null && expected.equals(e.getValue().value());
                    }));

            // A watch of a plain name: evaluated again by its renderer.
            FxTestSupport.runOnFx(() -> debug.panel().addWatchExpression("total"));
            awaitValue(debug, DebugPanel.Kind.WATCH, "total", expected);
            assertEquals(expected, rendered);

            // Muted: the debugger's own value.
            FxTestSupport.runOnFx(debug::toggleRenderersMuted);
            await("the debugger's own value", 60, () -> {
                String v = value(debug, DebugPanel.Kind.VARIABLE, "total");
                return v != null && !v.equals(expected);
            });
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 30, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    private static String awaitValue(DebugCoordinator debug, DebugPanel.Kind kind, String name, String expected)
            throws InterruptedException {
        try {
            await(name + " = " + expected, 60, () -> expected.equals(value(debug, kind, name)));
        } catch (AssertionError e) {
            throw new AssertionError(
                    e.getMessage() + ": was " + value(debug, kind, name) + ", type "
                            + call(() -> {
                                TreeItem<DebugPanel.VarRow> r = row(debug, kind, name);
                                return r == null
                                        ? null
                                        : r.getValue().type() + ", expression "
                                                + r.getValue().evaluateName();
                            }),
                    e);
        }
        return value(debug, kind, name);
    }

    private static String value(DebugCoordinator debug, DebugPanel.Kind kind, String name) {
        return call(() -> {
            TreeItem<DebugPanel.VarRow> r = row(debug, kind, name);
            return r == null ? null : r.getValue().value();
        });
    }

    private static TreeItem<DebugPanel.VarRow> row(DebugCoordinator debug, DebugPanel.Kind kind, String name) {
        TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
        return tree.getRoot() == null ? null : deep(tree.getRoot(), kind, name);
    }

    private static TreeItem<DebugPanel.VarRow> deep(
            TreeItem<DebugPanel.VarRow> item, DebugPanel.Kind kind, String name) {
        for (TreeItem<DebugPanel.VarRow> child : item.getChildren()) {
            DebugPanel.VarRow v = child.getValue();
            if (v != null && v.kind() == kind && v.name().equals(name)) {
                return child;
            }
            TreeItem<DebugPanel.VarRow> deeper = deep(child, kind, name);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static TreeItem<DebugPanel.VarRow> find(TreeItem<DebugPanel.VarRow> item, String name) {
        if (item == null) {
            return null;
        }
        return item.getChildren().stream()
                .filter(i -> i.getValue() != null && i.getValue().name().equals(name))
                .findFirst()
                .orElse(null);
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
