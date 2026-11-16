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
import com.editora.dap.DapModels;
import com.editora.dap.DebugAdapterLocator;
import com.editora.editor.EditorBuffer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of breakpoints in Django and Jinja templates against debugpy, in a window: a project that renders a
 * template (Jinja named in its requirements; a Django project with its manage.py) is debugged with Settings' Template
 * breakpoints on Automatic, and a breakpoint on the template's loop line stops there — the template's frame, on that
 * line — with its context in Variables ({@code i=1}, {@code name='Ada'}). Needs a venv with Jinja2 and Django: {@code
 * -Dtemplates.venv=…}, by default {@code ~/.cache/editora/templates-venv}. Run with {@code ./mvnw test
 * -Dtest=PythonTemplatesProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class PythonTemplatesProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static final Path HOME = Path.of(System.getProperty("user.home"));
    private static final Path VENV = Path.of(System.getProperty(
            "templates.venv", HOME.resolve(".cache/editora/templates-venv").toString()));

    private static final String PAGE = """
            <h1>Hello {{ name }}</h1>
            <ul>
            {% for i in items %}
              <li>{{ i }}</li>
            {% endfor %}
            </ul>
            """;

    @Test
    void jinja(@TempDir Path dir) throws Exception {
        // Flask's, Jinja's: found in the project's requirements, so Automatic turns debugpy's jinja switch on.
        probe(dir, "Jinja2>=3\n", false, """
                import os
                from jinja2 import Environment, FileSystemLoader
                here = os.path.dirname(os.path.abspath(__file__))
                env = Environment(loader=FileSystemLoader(os.path.join(here, "templates")))
                print(env.get_template("page.html").render(name="Ada", items=[1, 2, 3]))
                """);
    }

    @Test
    void django(@TempDir Path dir) throws Exception {
        // A manage.py: a Django project, so Automatic turns debugpy's django switch on.
        probe(dir, null, true, """
                import os
                from django.conf import settings
                here = os.path.dirname(os.path.abspath(__file__))
                settings.configure(TEMPLATES=[{
                    "BACKEND": "django.template.backends.django.DjangoTemplates",
                    "DIRS": [os.path.join(here, "templates")]}])
                import django
                django.setup()
                from django.template.loader import get_template
                print(get_template("page.html").render({"name": "Ada", "items": [1, 2, 3]}))
                """);
    }

    /**
     * A project of {@code app.py} rendering {@code templates/page.html}, its requirements {@code requirements} (none
     * when null) and a {@code manage.py} when {@code manage}; a breakpoint on the loop's line of the template stops
     * there, with the template's variables.
     */
    private static void probe(Path dir, @Nullable String requirements, boolean manage, String program)
            throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        assumeTrue(DebugAdapterLocator.locateDebugpy("", HOME).isPresent(), "needs debugpy");
        assumeTrue(Files.isExecutable(VENV.resolve("bin/python")), "needs a venv with jinja2 and django: " + VENV);
        Path root = Files.createDirectory(dir.resolve("site")).toRealPath();
        Files.createSymbolicLink(root.resolve(".venv"), VENV);
        Files.createDirectory(root.resolve("templates"));
        Path page = Files.writeString(root.resolve("templates/page.html"), PAGE);
        Path app = Files.writeString(root.resolve("app.py"), program);
        if (requirements != null) {
            Files.writeString(root.resolve("requirements.txt"), requirements);
        }
        if (manage) {
            Files.writeString(root.resolve("manage.py"), "import django\n");
        }
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getSettings().setDebugSupport(true);
        assertTrue(seed.saveSettings());
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(page, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("python", 120, () -> call(() -> debug.debugEffectiveFor("python")));
            EditorBuffer template = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> template.toggleBreakpoint(3)); // <li>{{ i }}</li>
            FxTestSupport.runOnFx(() -> c.fileWorkflows.openPath(app));
            await(
                    "app.py in front",
                    30,
                    () -> call(() -> app.equals(c.activeBuffer().getPath())));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("debug.start"));
            await(
                    "a stop in the template",
                    120,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()));
            DapModels.StackFrameInfo top = call(() -> debug.panel().callStack().getFirst());
            assertEquals(page, top.file(), "the template's frame: " + top);
            assertEquals(3, top.line(), "on the breakpoint's line");
            TreeView<DebugPanel.VarRow> tree = FxTestSupport.field(debug.panel(), "variables");
            await(
                    "the template's variables",
                    60,
                    () -> call(() -> {
                        String all = variables(tree);
                        return all.contains("i=1") && all.contains("name='Ada'");
                    }));
            FxTestSupport.runOnFx(dap::stop);
            await("stopped", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    /** Every variable shown, name=value, its scopes expanded. FX thread. */
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
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > end) {
                throw new AssertionError("timed out waiting for " + what);
            }
            Thread.sleep(50);
        }
    }
}
