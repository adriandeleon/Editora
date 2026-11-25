package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
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
 * Opt-in probe of Test Results for the test runners Editora starts as Run File runs a test file — Jest, Vitest, Deno,
 * RSpec, Minitest, PHPUnit, ExUnit, {@code dart test} — against the real runners, in a window: a file with a passing
 * and a failing test fills Test Results with both, the failure named as the runner names it; its row opens the test's
 * file where it failed or is declared; Rerun Failed runs the failure alone. Each case is skipped without its runner.
 * Run with {@code ./mvnw test -Dtest=ToolTestResultsProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class ToolTestResultsProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.process.ProcessRegistry.installShutdownHook();
    }

    private static void needs(String... tools) {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        for (String tool : tools) {
            assumeTrue(
                    Path.of(ProcessRunner.resolveExecutable(List.of(tool)).getFirst())
                            .isAbsolute(),
                    "needs " + tool);
        }
    }

    private static final String JS_TESTS = """
            describe('calc', () => {
              test('adds', () => {
                expect(2 + 2).toBe(4);
              });
              test('fails', () => {
                expect(2 + 2).toBe(5);
              });
            });
            """;

    @Test
    void jest(@TempDir Path dir) throws Exception {
        Path project = npmInstall(dir, "jest");
        Path test = Files.writeString(project.resolve("calc.test.js"), JS_TESTS);
        runFile(dir, test, "calc › fails", Set.of(5, 6), 300);
    }

    @Test
    void vitest(@TempDir Path dir) throws Exception {
        Path project = npmInstall(dir, "vitest");
        Path test = Files.writeString(
                project.resolve("calc.test.mjs"), "import { describe, test, expect } from 'vitest';\n" + JS_TESTS);
        runFile(dir, test, "calc › fails", Set.of(6, 7), 300);
    }

    @Test
    void deno(@TempDir Path dir) throws Exception {
        needs("deno");
        Path project = Files.createDirectories(dir.resolve("calc")).toRealPath();
        Files.writeString(project.resolve("deno.json"), "{}\n");
        Path test = Files.writeString(project.resolve("calc_test.ts"), """
                Deno.test("adds", () => {
                  if (2 + 2 !== 4) throw new Error("no");
                });

                Deno.test("fails", () => {
                  if (2 + 2 !== 5) throw new Error("not five");
                });
                """);
        runFile(dir, test, "fails", Set.of(5, 6), 180);
    }

    @Test
    void rspec(@TempDir Path dir) throws Exception {
        needs("ruby", "rspec");
        Path spec = Files.writeString(
                Files.createDirectories(dir.resolve("calc/spec")).toRealPath().resolve("calc_spec.rb"), """
                        RSpec.describe "calc" do
                          it "adds" do
                            expect(2 + 2).to eq(4)
                          end

                          it "fails" do
                            expect(2 + 2).to eq(5)
                          end
                        end
                        """);
        runFile(dir, spec, "calc fails", Set.of(6, 7), 180);
    }

    @Test
    void minitest(@TempDir Path dir) throws Exception {
        needs("ruby");
        Path test = Files.writeString(
                Files.createDirectories(dir.resolve("calc/test")).toRealPath().resolve("calc_test.rb"), """
                        require "minitest/autorun"

                        class CalcTest < Minitest::Test
                          def test_adds
                            assert_equal 4, 2 + 2
                          end

                          def test_fails
                            assert_equal 5, 2 + 2
                          end
                        end
                        """);
        runFile(dir, test, "test_fails", Set.of(8, 9), 180);
    }

    @Test
    void phpunit(@TempDir Path dir) throws Exception {
        needs("php", "phpunit");
        Path project =
                Files.createDirectories(dir.resolve("calc/tests")).toRealPath().getParent();
        Files.writeString(project.resolve("phpunit.xml"), "<phpunit/>\n");
        Path test = Files.writeString(project.resolve("tests/CalcTest.php"), """
                <?php
                use PHPUnit\\Framework\\TestCase;

                final class CalcTest extends TestCase
                {
                    public function testAdds(): void
                    {
                        $this->assertSame(4, 2 + 2);
                    }

                    public function testFails(): void
                    {
                        $this->assertSame(5, 2 + 2);
                    }
                }
                """);
        runFile(dir, test, "testFails", Set.of(11, 13), 180);
    }

    @Test
    void exunit(@TempDir Path dir) throws Exception {
        needs("elixir", "mix");
        Process mix = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of("mix", "new", "calc")))
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assertTrue(mix.waitFor(120, TimeUnit.SECONDS) && mix.exitValue() == 0, "mix new made the project");
        Path root = dir.resolve("calc").toRealPath();
        Path test = Files.writeString(root.resolve("test/calc_test.exs"), """
                defmodule CalcTest do
                  use ExUnit.Case

                  test "adds" do
                    assert 2 + 2 == 4
                  end

                  test "fails" do
                    assert 2 + 2 == 5
                  end
                end
                """);
        runFile(dir, test, "test fails", Set.of(8, 9), 240);
    }

    @Test
    void dart(@TempDir Path dir) throws Exception {
        needs("dart");
        Path pkg = Files.createDirectory(dir.resolve("calc")).toRealPath();
        Files.writeString(
                pkg.resolve("pubspec.yaml"),
                "name: calc\nenvironment:\n  sdk: ^3.0.0\ndev_dependencies:\n  test: any\n");
        Process get = new ProcessBuilder(ProcessRunner.resolveExecutable(List.of("dart", "pub", "get", "--offline")))
                .directory(pkg.toFile())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        assumeTrue(get.waitFor(300, TimeUnit.SECONDS) && get.exitValue() == 0, "package:test in the pub cache");
        Path test = Files.writeString(Files.createDirectory(pkg.resolve("test")).resolve("calc_test.dart"), """
                import 'package:test/test.dart';

                void main() {
                  group('calc', () {
                    test('adds', () {
                      expect(2 + 2, 4);
                    });
                    test('fails', () {
                      expect(2 + 2, 5);
                    });
                  });
                }
                """);
        runFile(dir, test, "calc fails", Set.of(8, 9), 300);
    }

    /** A package in {@code dir} depending on {@code runner}, installed; skips without the tools or the network. */
    private static Path npmInstall(Path dir, String runner) throws Exception {
        needs("node", "npm");
        Path project = Files.createDirectory(dir.resolve(runner + "-app")).toRealPath();
        Files.writeString(project.resolve("package.json"), "{ \"name\": \"probe\", \"private\": true }\n");
        Process npm = new ProcessBuilder(ProcessRunner.resolveExecutable(
                        List.of("npm", "install", "--prefer-offline", "--no-audit", "--no-fund", "-D", runner)))
                .directory(project.toFile())
                .redirectErrorStream(true)
                .redirectOutput(dir.resolve("npm.log").toFile())
                .start();
        assumeTrue(npm.waitFor(300, TimeUnit.SECONDS) && npm.exitValue() == 0, "npm install " + runner);
        return project;
    }

    /**
     * Opens {@code file} and runs it: Test Results gets its two tests, {@code failed} failing; that row opens the file
     * on one of the 1-based {@code lines} (where it failed, or is declared); Rerun Failed runs it alone.
     */
    private static void runFile(Path dir, Path file, String failed, Set<Integer> lines, int seconds) throws Exception {
        Path configDir = Files.createDirectory(dir.resolve("config"));
        SharedConfig seed = new SharedConfig(configDir, false);
        seed.load();
        seed.getTrustStore().trust(dir.toRealPath()); // the trust question (a build runs the project's code), answered
        assertTrue(seed.saveSettings());
        seed.saveTrust();
        seed.shutdown();
        FxWindowFixture fx = FxWindowFixture.create(
                configDir, false, false, false, List.of(new MainController.OpenTarget(file, 0, 0)), true, c -> {});
        try {
            MainController c = fx.controller;
            await(
                    "a ▶ for the file",
                    60,
                    () -> call(() -> {
                        EditorBuffer b = c.activeBuffer();
                        return b != null && b.isRunnable();
                    }));
            run(c, "file.run");
            TestRun first = awaitRun(c, null, 2, seconds);
            assertEquals(1, call(() -> first.counts().failedOrErrored()), () -> describe(c, first));
            TestNode failure = call(() -> first.root().failedLeaves().getFirst());
            assertEquals(failed, failure.methodName(), () -> describe(c, first));
            assertTrue(failure.failureMessage() != null, "the failure says why");

            TestRunCoordinator coordinator = FxTestSupport.field(c, "testRunCoordinator");
            TestRunCoordinator.ExternalRun external = FxTestSupport.field(coordinator, "external");
            FxTestSupport.runOnFx(() -> external.jump(failure));
            await(
                    "the failure's line in " + lines,
                    30,
                    () -> call(() -> {
                        EditorBuffer b = c.activeBuffer();
                        return b != null
                                && file.equals(b.getPath())
                                && lines.contains(b.getFocusedArea().getCurrentParagraph() + 1);
                    }));

            await("the run's end", 120, () -> !call(() -> c.runCoordinator.isRunning()));
            run(c, "test.rerunFailed");
            TestRun again = awaitRun(c, first, 1, seconds);
            TestNode rerun = call(() -> again.root().failedLeaves().getFirst());
            assertEquals(failed, rerun.methodName(), () -> describe(c, again));
            await("the rerun's end", 120, () -> !call(() -> c.runCoordinator.isRunning()));
        } finally {
            fx.dispose();
        }
    }

    /** The run after {@code before} once it ended with {@code total} tests; the console and status when it does not. */
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
            TestRun r = call(() -> testRun(c));
            throw new AssertionError(e.getMessage() + "\n" + describe(c, r), e);
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
        return "Test Results:\n" + tree + "Run console:\n" + output(c) + "\nstatus: " + status;
    }

    private static @Nullable TestRun testRun(MainController c) throws Exception {
        TestRunCoordinator coordinator = FxTestSupport.field(c, "testRunCoordinator");
        return FxTestSupport.field(coordinator, "currentRun");
    }

    private static void run(MainController c, String command) throws Exception {
        FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run(command));
    }

    private static String output(MainController c) {
        return call(() -> FxTestSupport.<org.fxmisc.richtext.CodeArea>field(c.runCoordinator.panel(), "output")
                .getText());
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
