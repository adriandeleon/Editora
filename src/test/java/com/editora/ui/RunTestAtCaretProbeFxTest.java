package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import com.editora.command.CommandRegistry;
import com.editora.config.SharedConfig;
import com.editora.editor.EditorBuffer;
import com.editora.process.ProcessRunner;
import com.editora.testkit.Await;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in probe of Run Test at Caret without the debugger against the real runners, in a window: in a test file with two
 * tests, the caret in the second, {@code test.runAtCaret} runs that test alone — its runner's own summary says one test
 * ran, and the other's name is not in the output. Each case is skipped without its runner on the PATH. Run with {@code
 * ./mvnw test -Dtest=RunTestAtCaretProbeFxTest -Dgroups=probe -Dlsp.probe=true}.
 */
@Tag("probe")
@Tag("fx")
class RunTestAtCaretProbeFxTest {

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

    @Test
    void rust(@TempDir Path dir) throws Exception {
        needs("cargo");
        Path pkg = Files.createDirectories(dir.resolve("calc/src")).toRealPath().getParent();
        Files.writeString(
                pkg.resolve("Cargo.toml"), "[package]\nname = \"calc\"\nversion = \"0.1.0\"\nedition = \"2021\"\n");
        Path lib = Files.writeString(pkg.resolve("src/lib.rs"), """
                pub fn add(a: i32, b: i32) -> i32 { a + b }

                #[cfg(test)]
                mod tests {
                    use super::*;

                    #[test]
                    fn adds() { assert_eq!(add(2, 2), 4); }

                    #[test]
                    fn adds_more() { assert_eq!(add(2, 3), 5); }
                }
                """);
        runAtCaret(dir, lib, 10, "test tests::adds_more ... ok", "tests::adds ...", 600);
    }

    @Test
    void go(@TempDir Path dir) throws Exception {
        needs("go");
        Path pkg = Files.createDirectories(dir.resolve("calc")).toRealPath();
        Files.writeString(pkg.resolve("go.mod"), "module calc\n\ngo 1.21\n");
        Files.writeString(pkg.resolve("calc.go"), "package calc\n\nfunc Add(a, b int) int { return a + b }\n");
        Path test = Files.writeString(pkg.resolve("calc_test.go"), """
                package calc

                import "testing"

                func TestAdds(t *testing.T) {
                	if Add(2, 2) != 4 { t.Fail() }
                }

                func TestAddsMore(t *testing.T) {
                	if Add(2, 3) != 5 { t.Fail() }
                }
                """);
        runAtCaret(dir, test, 9, "--- PASS: TestAddsMore", "--- PASS: TestAdds ", 300);
    }

    @Test
    void nodeTest(@TempDir Path dir) throws Exception {
        needs("node");
        Path project = Files.createDirectories(dir.resolve("calc")).toRealPath();
        Files.writeString(project.resolve("package.json"), "{\"name\": \"calc\", \"type\": \"module\"}\n");
        Path test = Files.writeString(project.resolve("calc.test.mjs"), """
                import { test } from 'node:test';
                import assert from 'node:assert';

                test('adds', () => {
                  assert.equal(2 + 2, 4);
                });

                test('adds more', () => {
                  assert.equal(2 + 3, 5);
                });
                """);
        runAtCaret(dir, test, 8, "adds more", null, 120);
        // node --test reports both names only when both run: the pattern ran one ("pass 1").
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

                Deno.test("adds more", () => {
                  if (2 + 3 !== 5) throw new Error("no");
                });
                """);
        runAtCaret(dir, test, 5, "1 passed", null, 180);
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
                  test('adds', () {
                    expect(2 + 2, 4);
                  });

                  test('adds more', () {
                    expect(2 + 3, 5);
                  });
                }
                """);
        runAtCaret(dir, test, 8, "+1: All tests passed!", "+2", 300);
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

                          it "adds more" do
                            expect(2 + 3).to eq(5)
                          end
                        end
                        """);
        runAtCaret(dir, spec, 6, "1 example, 0 failures", null, 180);
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

                    public function testAddsMore(): void
                    {
                        $this->assertSame(5, 2 + 3);
                    }
                }
                """);
        runAtCaret(dir, test, 13, "OK (1 test, 1 assertion)", null, 180);
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

                  test "adds more" do
                    assert 2 + 3 == 5
                  end
                end
                """);
        // ExUnit counts the test left out: "2 tests, 0 failures, 1 excluded".
        runAtCaret(dir, test, 8, "0 failures, 1 excluded", null, 200);
    }

    /**
     * Opens {@code file} with the caret on 0-based {@code line}, runs Run Test at Caret, and waits for {@code ran} in the
     * Run console; {@code other} (the other test's mark) must not be there.
     */
    private static void runAtCaret(Path dir, Path file, int line, String ran, String other, int seconds)
            throws Exception {
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
            EditorBuffer b = FxTestSupport.callOnFx(c::activeBuffer);
            FxTestSupport.runOnFx(() -> b.getFocusedArea().moveTo(line, 0));
            FxTestSupport.runOnFx(() -> ((CommandRegistry) FxTestSupport.field(c, "registry")).run("test.runAtCaret"));
            try {
                await(ran, seconds, () -> output(c).contains(ran));
            } catch (AssertionError e) {
                MessageLog log = FxTestSupport.field(FxTestSupport.field(c, "statusBar"), "messageLog");
                String status = call(() -> String.join(
                        " | ",
                        log.entries().stream().map(MessageLog.Entry::text).toList()));
                throw new AssertionError(e.getMessage() + "\nRun console:\n" + output(c) + "\nstatus: " + status, e);
            }
            if (other != null) {
                assertFalse(output(c).contains(other), "the other test ran too:\n" + output(c));
            }
            await("the run's end", 120, () -> !call(() -> c.runCoordinator.isRunning()));
        } finally {
            fx.dispose();
        }
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
