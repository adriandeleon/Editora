package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.dap.DapManager;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.test.TestNode;
import com.editora.test.TestRun;
import com.editora.testkit.Await;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of C++ test files under GoogleTest, Catch2 3 and doctest, in a CMake project, in a window: the file
 * has a ▶ per test; Run File builds its program and fills Test Results with its three tests, one failing, the failure
 * opening its line; Rerun Failed builds again and runs the failure alone; Run Test at Caret runs the test the caret is
 * in; Debug Test at Caret stops (on GDB) at a breakpoint in it. Each case is skipped without its framework (found by
 * CMake's {@code find_package}). Run with {@code ./mvnw test -Dtest=CppTestsProbeFxTest -Dgroups=probe
 * -Dlsp.probe=true}; needs cmake, a C++ compiler and gdb.
 */
@Tag("probe")
@Tag("fx")
class CppTestsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    @Test
    void googleTest(@TempDir Path dir) throws Exception {
        probe(dir, "find_package(GTest REQUIRED)", "GTest::gtest_main", """
                #include <gtest/gtest.h>

                int add(int a, int b) { return a + b; }

                TEST(Math, Adds) {
                  int sum = add(1, 2);
                  EXPECT_EQ(sum, 3);
                }

                TEST(Math, Fails) {
                  EXPECT_EQ(add(1, 2), 4);
                }

                TEST(Other, AlsoAdds) { EXPECT_EQ(add(2, 2), 4); }
                """, "Math.Fails", 10);
    }

    @Test
    void catch2(@TempDir Path dir) throws Exception {
        probe(dir, "find_package(Catch2 3 REQUIRED)", "Catch2::Catch2WithMain", """
                #include <catch2/catch_test_macros.hpp>

                int add(int a, int b) { return a + b; }

                TEST_CASE("adds, as it should") {
                  int sum = add(1, 2);
                  REQUIRE(sum == 3);
                }

                TEST_CASE("fails") {
                  REQUIRE(add(1, 2) == 4);
                }

                TEST_CASE("also adds") { REQUIRE(add(2, 2) == 4); }
                """, "fails", 10);
    }

    @Test
    void doctest(@TempDir Path dir) throws Exception {
        probe(dir, "find_package(doctest REQUIRED)", "doctest::doctest", """
                #define DOCTEST_CONFIG_IMPLEMENT_WITH_MAIN
                #include <doctest/doctest.h>
                int add(int a, int b) { return a + b; }

                TEST_CASE("adds, as it should") {
                  int sum = add(1, 2);
                  CHECK(sum == 3);
                }

                TEST_CASE("fails") {
                  CHECK(add(1, 2) == 4);
                }

                TEST_CASE("also adds") { CHECK(add(2, 2) == 4); }
                """, "fails", 10);
    }

    /** 0-based: the line in the first (passing) test's body the caret and the breakpoint go on. */
    private static final int BODY = 5;

    private static void probe(Path dir, String find, String link, String source, String failed, int failLine)
            throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        for (String tool : List.of("cmake", "c++", "gdb")) {
            assumeTrue(
                    Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                            .isAbsolute(),
                    "needs " + tool);
        }
        Path project = Files.createDirectories(dir.resolve("calc")).toRealPath();
        Files.writeString(project.resolve("CMakeLists.txt"), """
                cmake_minimum_required(VERSION 3.16)
                project(calc CXX)
                set(CMAKE_CXX_STANDARD 17)
                %s
                add_executable(calc_test calc_test.cpp)
                target_link_libraries(calc_test %s)
                """.formatted(find, link));
        Path test = Files.writeString(project.resolve("calc_test.cpp"), source);
        // The framework where CMake finds it: skipped, not failed, without it.
        Process configure = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of(
                        "cmake",
                        "-S",
                        project.toString(),
                        "-B",
                        dir.resolve("probe-build").toString())))
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("configure.log").toFile())
                .start();
        assumeTrue(
                configure.waitFor(120, TimeUnit.SECONDS) && configure.exitValue() == 0,
                () -> "CMake finds the framework: " + read(dir.resolve("configure.log")));

        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getTrustStore().trust(dir.toRealPath()); // the trust question (CMake runs the project's code), answered
        seed.getSettings().setDebugSupport(true);
        seed.getSettings().setCppDebugger("gdb");
        assertTrue(seed.saveSettings());
        seed.saveTrust();
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(test, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            EditorBuffer b = call(c::activeBuffer);
            await(
                    "a ▶ per test, and the file's",
                    60,
                    () -> call(() -> b.isRunnable()
                            && glyphs(b).contains(0)
                            && glyphs(b).contains(4)
                            && glyphs(b).contains(9)
                            && glyphs(b).contains(13)));

            run(c, "file.run");
            TestRun first = awaitRun(c, null, 3, 120);
            assertEquals(1, call(() -> first.counts().failedOrErrored()), () -> describe(c, first));
            TestNode failure = call(() -> first.root().failedLeaves().getFirst());
            assertEquals(failed, failure.methodName(), () -> describe(c, first));
            assertTrue(failure.failureMessage() != null, "the failure says why");
            TestRunCoordinator coordinator = FxTestSupport.field(c, "testRunCoordinator");
            TestRunCoordinator.ExternalRun external = FxTestSupport.field(coordinator, "external");
            FxTestSupport.runOnFx(() -> external.jump(failure));
            await(
                    "the failure's line",
                    30,
                    () -> call(() -> b.getFocusedArea().getCurrentParagraph() + 1) == failLine + 1
                            || call(() -> b.getFocusedArea().getCurrentParagraph() + 1) == failLine);
            await("the run's end", 120, () -> !call(() -> c.runCoordinator.isRunning()));

            run(c, "test.rerunFailed"); // built again, then the failure alone
            TestRun again = awaitRun(c, first, 1, 300);
            assertEquals(
                    failed, call(() -> again.root().failedLeaves().getFirst().methodName()));
            await("the rerun's end", 120, () -> !call(() -> c.runCoordinator.isRunning()));

            FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(BODY, 0));
            run(c, "test.runAtCaret");
            TestRun one = awaitRun(c, again, 1, 300);
            assertEquals(0, call(() -> one.counts().failedOrErrored()), () -> describe(c, one));
            await("the caret run's end", 120, () -> !call(() -> c.runCoordinator.isRunning()));

            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            DapManager dap = FxTestSupport.field(c, "dapManager");
            await("C++ debugging available", 60, () -> call(() -> debug.debugEffectiveFor("cpp")));
            FxTestSupport.runOnFx(() -> {
                b.toggleBreakpoint(BODY + 1);
                b.getFocusedArea().moveTo(BODY, 0);
            });
            run(c, "test.debugAtCaret");
            await(
                    "a stop in the test at the caret",
                    300,
                    () -> call(() -> dap.state() == DapManager.State.SUSPENDED
                            && !debug.panel().callStack().isEmpty()
                            && debug.panel().callStack().getFirst().line() == BODY + 1));
            FxTestSupport.runOnFx(dap::stop);
            await("the session's end", 60, () -> !call(dap::isActive));
        } finally {
            fx.dispose();
        }
    }

    /** The lines with a test's ▶. FX thread. */
    private static java.util.Set<Integer> glyphs(EditorBuffer b) {
        java.util.Map<Integer, ?> lines = FxTestSupport.field(FxTestSupport.field(b, "runGutter"), "testLines");
        return lines.keySet();
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (java.io.IOException e) {
            return "";
        }
    }

    private static TestRun awaitRun(MainController c, @Nullable TestRun before, int total, int seconds)
            throws Exception {
        try {
            await(
                    total + " tests in Test Results",
                    seconds,
                    () -> call(() -> {
                        TestRun r = testRun(c);
                        return r != null
                                && r != before
                                && !r.isRunning()
                                && r.counts().total() == total;
                    }));
        } catch (AssertionError e) {
            throw new AssertionError(e.getMessage() + "\n" + describe(c, call(() -> testRun(c))), e);
        }
        return call(() -> testRun(c));
    }

    private static String describe(MainController c, @Nullable TestRun r) {
        MessageLog log = call(() -> FxTestSupport.field(FxTestSupport.field(c, "statusBar"), "messageLog"));
        String status = call(() -> String.join(
                " | ", log.entries().stream().map(MessageLog.Entry::text).toList()));
        StringBuilder tree = new StringBuilder();
        if (r != null) {
            call(() -> {
                for (TestNode suite : r.root().children()) {
                    tree.append(suite.displayName()).append('\n');
                    for (TestNode t : suite.children()) {
                        tree.append("  ")
                                .append(t.methodName())
                                .append(' ')
                                .append(t.status())
                                .append(' ')
                                .append(t.failureMessage())
                                .append('\n');
                    }
                }
                return null;
            });
        }
        String console =
                call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(c.runCoordinator.panel(), "output")
                        .getText());
        return "Test Results:\n" + tree + "Run console:\n" + console + "\nstatus: " + status;
    }

    private static @Nullable TestRun testRun(MainController c) throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.field(c, "testRunCoordinator");
        return FxTestSupport.field(coordinator, "currentRun");
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
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
