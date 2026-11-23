package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.dap.DapManager;
import com.editora.dap.DapModels;
import com.editora.editor.EditorBuffer;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Kotlin and Groovy tests at the caret, in a window, through a real Gradle build: a Kotlin JUnit 5 test
 * with a backticked name and a Spock feature each get their ▶, and Debug Test at Caret runs that one test with the
 * test JVM waiting, attaches Editora's JVM debugger and stops at its breakpoint with its locals. Run with {@code ./mvnw
 * test -Dtest=JvmLanguageTestsProbeFxTest -Dgroups=probe -Dlsp.probe=true}; needs a Gradle 9 distribution in
 * {@code ~/.gradle/wrapper/dists} (to make the project's wrapper) and its plugins and libraries reachable (Maven
 * Central, or Gradle's cache).
 */
@Tag("probe")
@Tag("fx")
class JvmLanguageTestsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    static final String BUILD = """
            plugins {
                kotlin("jvm") version "2.4.21"
                groovy
            }
            repositories { mavenCentral() }
            dependencies {
                testImplementation("org.junit.jupiter:junit-jupiter:5.14.1")
                testRuntimeOnly("org.junit.platform:junit-platform-launcher")
                testImplementation("org.apache.groovy:groovy:4.0.29")
                testImplementation("org.spockframework:spock-core:2.4-M6-groovy-4.0")
            }
            tasks.test { useJUnitPlatform() }
            """;

    /** 0-based line 15 is {@code assertEquals(2, diff)} in {@code subtracts}; the caret goes to its line 14. */
    static final String KOTLIN = """
            package demo

            import org.junit.jupiter.api.Assertions.assertEquals
            import org.junit.jupiter.api.Test

            class CalcTest {
                @Test
                fun `adds two`() {
                    val sum = 1 + 1
                    assertEquals(2, sum)
                }

                @Test
                fun subtracts() {
                    val diff = 3 - 1
                    assertEquals(2, diff)
                }
            }
            """;

    /** 0-based line 9 is {@code b == 2} in the feature {@code adds one}; the caret goes to its line 6. */
    static final String SPOCK = """
            package demo

            import spock.lang.Specification

            class MathSpec extends Specification {
                def "adds one"() {
                    given:
                    def b = 1 + 1
                    expect:
                    b == 2
                }

                def "doubles"() {
                    expect:
                    2 * 2 == 4
                }
            }
            """;

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, unit = TimeUnit.MINUTES)
    void aKotlinTestAtTheCaretStopsAtItsBreakpoint(@TempDir Path dir) throws Exception {
        probe(dir, "src/test/kotlin/demo/CalcTest.kt", KOTLIN, 15, 14, "diff", "2");
    }

    @Test
    @org.junit.jupiter.api.Timeout(value = 20, unit = TimeUnit.MINUTES)
    void aSpockFeatureAtTheCaretStopsAtItsBreakpoint(@TempDir Path dir) throws Exception {
        probe(dir, "src/test/groovy/demo/MathSpec.groovy", SPOCK, 9, 6, "b", "2");
    }

    private static void probe(
            Path dir, String relative, String source, int breakpoint, int caret, String local, String value)
            throws Exception {
        Path project = project(dir.resolve("config").resolve("project")); // where the window's project is
        Path test = project.resolve(relative);
        Files.createDirectories(test.getParent());
        Files.writeString(test, source);
        try (Window w = Window.open(dir, project, test)) {
            FxTestSupport.runOnFx(() -> {
                w.buffer.toggleBreakpoint(breakpoint);
                w.buffer.getFocusedArea().moveTo(caret, 0);
            });
            await("the test at the caret", 120, () -> call(() -> w.buffer.testTargetAtCaret(false) != null));
            w.run("test.debugAtCaret");
            try {
                await(
                        "a stop",
                        400, // Gradle compiles the project first
                        () -> call(() -> w.dap.state() == DapManager.State.SUSPENDED
                                && !w.debug.panel().callStack().isEmpty()));
            } catch (AssertionError e) {
                throw new AssertionError(
                        e.getMessage() + "; said: " + w.said + "; debug state " + call(w.dap::state) + "; console: "
                                + w.console(),
                        e);
            }
            DapModels.StackFrameInfo top =
                    call(() -> w.debug.panel().callStack().getFirst());
            assertEquals(breakpoint, top.line(), "on the breakpoint: " + top);
            assertEquals(value, w.evaluate(local));
            FxTestSupport.runOnFx(() -> w.buffer.toggleBreakpoint(breakpoint));
            FxTestSupport.runOnFx(w.dap::resume);
            await("the end", 300, () -> !call(w.dap::isActive));
        }
    }

    /** A Gradle project at {@code project} with its wrapper; skips without a Gradle distribution. */
    private static Path project(Path where) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path dists = Path.of(System.getProperty("user.home"), ".gradle/wrapper/dists");
        Path gradle = null;
        if (Files.isDirectory(dists)) {
            try (var walk = Files.walk(dists, 5)) { // dists/gradle-9.x-bin/<hash>/gradle-9.x/bin/gradle
                gradle = walk.filter(p -> p.getFileName().toString().equals("gradle")
                                && p.getParent() != null
                                && p.getParent().getFileName().toString().equals("bin")
                                && p.toString().contains("gradle-9."))
                        .sorted(java.util.Comparator.reverseOrder())
                        .findFirst()
                        .orElse(null);
            }
        }
        assumeTrue(gradle != null && Files.isExecutable(gradle), "needs a Gradle 9 distribution");
        Path project = Files.createDirectories(where).toRealPath();
        Files.writeString(project.resolve("settings.gradle.kts"), "rootProject.name = \"app\"\n");
        Files.writeString(project.resolve("build.gradle.kts"), BUILD);
        Process wrapper = new ProcessBuilder(gradle.toString(), "--offline", "-q", "wrapper")
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(project.resolve("wrapper.log").toFile())
                .start();
        assumeTrue(wrapper.waitFor(300, TimeUnit.SECONDS) && wrapper.exitValue() == 0, "gradle wrapper");
        return project;
    }

    /** A window with {@code file} open in {@code root} (a trusted project), debugging on. */
    private static final class Window implements AutoCloseable {
        final FxWindowFixture fx;
        final DebugCoordinator debug;
        final DapManager dap;
        final CommandRegistry registry;
        final EditorBuffer buffer;

        /** Every line the status bar has said. */
        final java.util.List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();

        private Window(FxWindowFixture fx) throws Exception {
            this.fx = fx;
            MainController c = fx.controller;
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<javafx.scene.control.Label>field(FxTestSupport.field(c, "statusBar"), "echo")
                            .textProperty()
                            .addListener((o, was, now) -> said.add(String.valueOf(now))));
            debug = FxTestSupport.field(c, "debugCoordinator");
            dap = FxTestSupport.field(c, "dapManager");
            registry = FxTestSupport.field(c, "registry");
            buffer = FxTestSupport.callOnFx(c::activeBuffer);
        }

        static Window open(Path dir, Path root, Path file) throws Exception {
            FxWindowFixture fx = FxWindowFixture.create(dir.resolve("config"), shared -> {
                var s = shared.getSettings();
                s.setDebugSupport(true);
                s.setTestRunner(true);
                s.setGradleSupport(true);
                shared.getTrustStore().trust(root); // the user's own project: its build runs
            });
            Path opened = fx.useProject();
            assertEquals(root, opened.toRealPath(), "the project the build was written into");
            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
            Await.until(
                    () -> call(() -> fx.controller.activeBuffer() != null
                            && file.equals(fx.controller.activeBuffer().getPath())),
                    "the test file in front",
                    java.time.Duration.ofSeconds(30));
            return new Window(fx);
        }

        String status() {
            return call(() -> {
                StatusBar bar = FxTestSupport.field(fx.controller, "statusBar");
                return FxTestSupport.<javafx.scene.control.Label>field(bar, "echo")
                        .getText();
            });
        }

        String console() {
            return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(debug.panel(), "console")
                    .getText());
        }

        void run(String command) throws Exception {
            FxTestSupport.runOnFx(() -> registry.run(command));
        }

        String evaluate(String expression) throws Exception {
            int frame = call(() -> debug.panel().selectedFrameId());
            java.util.concurrent.atomic.AtomicReference<DapModels.EvalResult> r =
                    new java.util.concurrent.atomic.AtomicReference<>();
            FxTestSupport.runOnFx(() -> dap.evaluateFull(expression, frame, "watch", r::set));
            await(expression, 60, () -> r.get() != null);
            return r.get().result();
        }

        @Override
        public void close() throws Exception {
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
        Await.until(condition, what, java.time.Duration.ofSeconds(seconds));
    }
}
