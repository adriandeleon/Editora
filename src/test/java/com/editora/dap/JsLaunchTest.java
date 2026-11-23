package com.editora.dap;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What Editora asks vscode-js-debug for and how it reads js-debug's own output (review D1–D12). */
class JsLaunchTest {

    @Test
    void theDebugConsoleSettingMakesEditoraStartTheProgram() {
        Map<String, Object> m = JsLaunch.request(
                "pwa-node",
                "/p/src/app.js",
                "/p",
                "/p",
                List.of("/nvm/v24/bin/node", "--max-old-space-size=4096", "--test"),
                List.of("a b", "c"),
                true);
        assertEquals("pwa-node", m.get("type"));
        assertEquals("launch", m.get("request"));
        assertEquals("/p/src/app.js", m.get("program"));
        assertEquals("/p", m.get("cwd")); // the package root, as npm runs it (D3)
        assertEquals("/p", m.get("__workspaceFolder")); // what ${workspaceFolder} (source maps) resolves to
        assertEquals("/nvm/v24/bin/node", m.get("runtimeExecutable"));
        assertEquals(List.of("--max-old-space-size=4096", "--test"), m.get("runtimeArgs"));
        assertEquals(List.of("a b", "c"), m.get("args"));
        assertEquals(List.of("<node_internals>/**"), m.get("skipFiles")); // D10
        assertEquals("integratedTerminal", m.get("console")); // runInTerminal: stdin, all output (D1/D5)
        assertFalse(m.containsKey("outputCapture"));
    }

    @Test
    void withoutTheConsoleSettingJsDebugCapturesStdoutAndStderr() {
        Map<String, Object> m = JsLaunch.request("pwa-node", "/p/a.js", "/p", null, List.of("node"), List.of(), false);
        assertEquals("internalConsole", m.get("console"));
        assertEquals("std", m.get("outputCapture")); // "console" (the default) lost process.stdout.write (D1)
        assertEquals("/p", m.get("__workspaceFolder")); // defaults to the working folder
        assertFalse(m.containsKey("runtimeArgs"));
        assertFalse(m.containsKey("args"));
    }

    @Test
    void aPackageManagerLaunchHasNoProgramAndCarriesItsEnvironment() {
        Map<String, Object> m = JsLaunch.request(
                "pwa-node", "", "/p", "/p", List.of("/usr/bin/npm", "run", "start"), List.of(), true, Map.of("X", "1"));
        assertFalse(m.containsKey("program"));
        assertEquals("/usr/bin/npm", m.get("runtimeExecutable"));
        assertEquals(List.of("run", "start"), m.get("runtimeArgs"));
        assertEquals(Map.of("X", "1"), m.get("env"));
        assertFalse(JsLaunch.request("pwa-node", "/p/a.js", "/p", null, List.of("node"), List.of(), true)
                .containsKey("env"));
    }

    @Test
    void typescriptNeedsANodeThatStripsTypesAndJsxNeverRuns() {
        assertNull(JsLaunch.launchProblemKey("app.js", "v14.0.0"));
        assertNull(JsLaunch.launchProblemKey("main.ts", "v24.18.1"));
        assertNull(JsLaunch.launchProblemKey("main.mts", "v22.18.0"));
        assertEquals("debug.error.tsNeedsNode", JsLaunch.launchProblemKey("main.ts", "v20.11.0"));
        assertEquals("debug.error.tsNeedsNode", JsLaunch.launchProblemKey("x.CTS", "v22.6.0"));
        assertNull(JsLaunch.launchProblemKey("main.ts", null)); // unknown: Node says so itself
        assertEquals("debug.error.jsxNotRunnable", JsLaunch.launchProblemKey("App.tsx", "v24.0.0"));
        assertEquals("debug.error.jsxNotRunnable", JsLaunch.launchProblemKey("App.jsx", null));
    }

    @Test
    void theAdapterNeedsARecentNode() {
        assertTrue(JsLaunch.runsAdapter("v24.18.1"));
        assertTrue(JsLaunch.runsAdapter("v18.0.0"));
        assertFalse(JsLaunch.runsAdapter("v14.21.3"));
        assertTrue(JsLaunch.runsAdapter(null)); // unknown gets the chance; its stderr explains a failure
        assertEquals("v24.18.1", JsLaunch.version("v24.18.1\n"));
        assertEquals("v20.1.0", JsLaunch.version("20.1.0"));
        assertNull(JsLaunch.version("command not found"));
        assertNull(JsLaunch.version(null));
    }

    @Test
    void inspectorLinesAreDroppedAndProgramOutputKept() {
        assertEquals("hello\n", JsLaunch.withoutInspectorNoise("Debugger attached.\nhello\n"));
        assertEquals("", JsLaunch.withoutInspectorNoise("Waiting for the debugger to disconnect...\n"));
        assertEquals(
                "x\r\n",
                JsLaunch.withoutInspectorNoise("Debugger listening on ws://127.0.0.1:9229/abc-def\r\n"
                        + "For help, see: https://nodejs.org/en/docs/inspector\r\nx\r\n"));
        String plain = "Debugger attached. (not alone on its line)\n";
        assertTrue(plain == JsLaunch.withoutInspectorNoise(plain)); // untouched text is the same string
        assertEquals("partial", JsLaunch.withoutInspectorNoise("partial"));
        assertEquals(
                "ok\n",
                JsLaunch.withoutInspectorNoise(
                        "Error in the js-debug bootloader, please report to "
                                + "https://aka.ms/js-dbg-issue: This Environment was initialized without a V8::Inspector\nok\n"));
    }

    @Test
    void aConditionJsDebugCannotCompileIsRecognised() {
        String twice = "Syntax error setting breakpoint with condition \"z ===\" on line 3: Unexpected token ';'"
                + "Syntax error setting breakpoint with condition \"z ===\" on line 3: Unexpected token ';'";
        Optional<JsLaunch.ConditionError> e = JsLaunch.conditionError(twice);
        assertTrue(e.isPresent());
        assertEquals("z ===", e.get().condition());
        assertEquals(3, e.get().line());
        assertEquals("Unexpected token ';'", e.get().reason());
        assertTrue(JsLaunch.conditionError("loop 1 2 3\n").isEmpty());
    }

    @Test
    void inspectorAddressesAreRead() {
        assertEquals(
                new JsLaunch.Address("localhost", 9229),
                JsLaunch.parseAddress("").orElseThrow());
        assertEquals(
                new JsLaunch.Address("localhost", 9230),
                JsLaunch.parseAddress("9230").orElseThrow());
        assertEquals(
                new JsLaunch.Address("10.0.0.2", 9229),
                JsLaunch.parseAddress("10.0.0.2:9229").orElseThrow());
        assertEquals(
                new JsLaunch.Address("::1", 9229),
                JsLaunch.parseAddress("[::1]:9229").orElseThrow());
        assertEquals(
                new JsLaunch.Address("127.0.0.1", 9229),
                JsLaunch.parseAddress("ws://127.0.0.1:9229/0f2c936f-b1cd-4ac9-aab3-f63b0f33d55e")
                        .orElseThrow());
        assertTrue(JsLaunch.parseAddress("localhost:0").isEmpty());
        assertTrue(JsLaunch.parseAddress("localhost:70000").isEmpty());
        assertTrue(JsLaunch.parseAddress("host:port").isEmpty());
        assertTrue(JsLaunch.parseAddress("::1:9229").isEmpty()); // an IPv6 address needs brackets
    }

    @Test
    void testFilesFollowNodesConventions() {
        assertTrue(JsLaunch.isTestFile(Path.of("/p/sum.test.js")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/src/api.spec.ts")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/test/helpers.mjs")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/src/__tests__/a.tsx")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/test-utils.cjs")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/db_test.js")));
        assertFalse(JsLaunch.isTestFile(Path.of("/p/src/app.js")));
        assertFalse(JsLaunch.isTestFile(Path.of("/p/test/readme.md")));
        assertFalse(JsLaunch.isTestFile(Path.of("/p/contest.js")));
    }

    @Test
    void theTestAtTheCaretIsFoundByName() {
        String src = """
                const test = require("node:test");
                describe("math", () => {
                  test("adds two", () => {
                    const r = 1 + 2;
                  });
                  it.only('it\\'s fine', () => {});
                });
                """;
        assertEquals(Optional.of("adds two"), JsLaunch.testNameAt(src, 3));
        assertEquals(Optional.of("math"), JsLaunch.testNameAt(src, 1));
        assertEquals(Optional.of("it\\'s fine"), JsLaunch.testNameAt(src, 5));
        assertEquals(Optional.empty(), JsLaunch.testNameAt(src, 0));
        assertEquals("adds \\(two\\) \\$1\\.0", JsLaunch.literalPattern("adds (two) $1.0"));
    }

    @Test
    void aTestFilesDebugArgumentsNameTheTestForNodeAndTheOutermostOneForDeno() {
        String src = """
                Deno.test("outer", async (t) => {
                  await t.step("inner", () => {});
                });
                """;
        assertEquals(
                List.of("--test-name-pattern", "outer", com.editora.run.DenoRun.FILTER_ARG + "outer"),
                JsLaunch.testArgsAt(src, 0));
        assertEquals(
                List.of("--test-name-pattern", "outer", com.editora.run.DenoRun.FILTER_ARG + "outer"),
                JsLaunch.testArgsAt(src, 1),
                "Node's nearest name above (t.step is no test() call); Deno's top-level test");
        assertEquals(List.of(), JsLaunch.testArgsAt("const a = 1;\n", 0));
    }

    @Test
    void theCompiledFileJsDebugRunsInsteadOfATypeScriptSourceIsFound() {
        Path src = Path.of("/p/src/main.ts");
        Path cwd = Path.of("/p");
        assertEquals(
                Path.of("/p/dist/main.js"),
                JsLaunch.compiledInstead(src, cwd, List.of("/n/node", "--enable-source-maps", "./dist/main.js", "x")));
        assertEquals(
                Path.of("/q/out/main.mjs"), JsLaunch.compiledInstead(src, cwd, List.of("node", "/q/out/main.mjs")));
        assertNull(JsLaunch.compiledInstead(src, cwd, List.of("node", "./src/main.ts")), "the source itself runs");
        assertNull(JsLaunch.compiledInstead(src, cwd, List.of("node", "./dist/other.js")), "another program");
        assertNull(JsLaunch.compiledInstead(Path.of("/p/app.js"), cwd, List.of("node", "./app.js")), "not TypeScript");
    }

    @Test
    void aBuiltFileOlderThanItsSourceIsStale() {
        Path src = Path.of("main.ts");
        Path out = Path.of("main.js");
        java.time.Instant t = java.time.Instant.parse("2026-11-03T10:00:00Z");
        Map<Path, java.time.Instant> times = Map.of(src, t, out, t.minusSeconds(60));
        assertTrue(JsLaunch.builtIsStale(src, out, p -> Optional.ofNullable(times.get(p))));
        Map<Path, java.time.Instant> fresh = Map.of(src, t, out, t.plusSeconds(1));
        assertFalse(JsLaunch.builtIsStale(src, out, p -> Optional.ofNullable(fresh.get(p))));
        assertFalse(JsLaunch.builtIsStale(src, out, p -> Optional.empty()), "unknown times: no warning");
    }

    @Test
    void aDebuggedProgramGetsRunsColoursUnlessNoColorIsSet() {
        assertEquals(Map.of("FORCE_COLOR", "1"), JsLaunch.colourEnvironment(null));
        assertEquals(Map.of("FORCE_COLOR", "1"), JsLaunch.colourEnvironment(""));
        assertEquals(Map.of(), JsLaunch.colourEnvironment("1"));
    }

    @Test
    void theTestConcurrencyWarningAndItsHintAreDroppedAcrossEvents() {
        JsLaunch.InspectorNoise noise = new JsLaunch.InspectorNoise();
        assertEquals(
                "",
                noise.filter("(node:4242) Warning: Using the inspector with --test forces running at a concurrency"
                        + " of 1. Use the inspectPort option to run with concurrency\n"));
        assertEquals("", noise.filter("(Use `node --trace-warnings ...` to show where the warning was created)\n"));
        assertEquals("✔ adds (2ms)\n", noise.filter("✔ adds (2ms)\n"));
        // The same hint after any other warning is the user's to read.
        String other = "(node:1) [DEP0040] DeprecationWarning: punycode\n"
                + "(Use `node --trace-warnings ...` to show where the warning was created)\n";
        assertEquals(other, noise.filter(other));
    }

    @Test
    void aRequestWithoutAProgramFolderArgumentsOrEnvironment() {
        Map<String, Object> m = JsLaunch.request(null, "", " ", null, List.of(" "), null, false, null);
        assertEquals("pwa-node", m.get("type"));
        assertFalse(m.containsKey("program"));
        assertFalse(m.containsKey("cwd"));
        assertFalse(m.containsKey("__workspaceFolder"));
        assertFalse(m.containsKey("args"));
        assertFalse(m.containsKey("runtimeExecutable"), "a blank runtime is none");
        assertFalse(m.containsKey("env"));
        Map<String, Object> run = JsLaunch.request(" ", "", "/w", "", List.of("npm"), List.of(), true, Map.of());
        assertEquals("pwa-node", run.get("type"));
        assertEquals("/w", run.get("__workspaceFolder"), "the folder is the cwd when none is named");
        assertEquals("npm", run.get("runtimeExecutable"));
        assertFalse(run.containsKey("runtimeArgs"));
        assertFalse(run.containsKey("args"));
        assertFalse(run.containsKey("env"));
        assertEquals("integratedTerminal", run.get("console"));
        assertNull(JsLaunch.request("pwa-node", "a.js", null, null, null, null, false)
                .get("runtimeExecutable"));
    }

    @Test
    void launchProblemsVersionsAndAddresses() {
        assertNull(JsLaunch.launchProblemKey(null, "v20.0.0"));
        assertNull(JsLaunch.launchProblemKey("a.mts", null), "an unknown Node says so itself");
        assertNull(JsLaunch.launchProblemKey("a.cts", "nonsense"));
        assertNull(JsLaunch.launchProblemKey("a.ts", "v23.6.0"));
        assertEquals("debug.error.tsNeedsNode", JsLaunch.launchProblemKey("a.cts", "v20.1.0"));
        assertNull(JsLaunch.version(null));
        assertNull(JsLaunch.version("Welcome\nv1.2.3.4\n"));
        assertEquals("v22.1.0", JsLaunch.version("22.1.0"));
        assertTrue(JsLaunch.runsAdapter(null));
        assertTrue(JsLaunch.runsAdapter("garbage"));
        assertEquals(Optional.empty(), JsLaunch.parseAddress("[::1"));
        assertEquals(Optional.empty(), JsLaunch.parseAddress("[::1]9229"));
        assertEquals(Optional.of(new JsLaunch.Address("::1", 9229)), JsLaunch.parseAddress("[::1]:9229"));
        assertEquals(Optional.empty(), JsLaunch.parseAddress(":9229"), "a colon needs a host before it");
        assertEquals(Optional.empty(), JsLaunch.parseAddress("a:b:9229"));
        assertEquals(Optional.empty(), JsLaunch.parseAddress("host:0"));
        assertEquals(Optional.empty(), JsLaunch.parseAddress("host:port"));
        assertEquals(Optional.of(new JsLaunch.Address("localhost", 9229)), JsLaunch.parseAddress(null));
        assertEquals(Optional.of(new JsLaunch.Address("h", 1)), JsLaunch.parseAddress("wss://h:1"));
    }

    @Test
    void conditionErrorsAndInspectorNoiseInTheirEdgeCases() {
        assertEquals(Optional.empty(), JsLaunch.conditionError(null));
        assertEquals(
                Optional.empty(),
                JsLaunch.conditionError("Syntax error setting breakpoint with condition without the rest"));
        JsLaunch.InspectorNoise noise = new JsLaunch.InspectorNoise();
        assertNull(noise.filter(null));
        assertEquals("", noise.filter(""));
        String warning = "(node:12) Warning: Using the inspector with --test forces running at a concurrency of 1.\n";
        String hint = "(Use `node --trace-warnings ...` to show where the warning was created)\n";
        assertEquals(
                "\nok\n",
                noise.filter(warning + "\n" + hint + "ok\n"),
                "a blank line between them keeps the hint noise");
        assertEquals(hint, noise.filter(hint), "the hint alone is the user's");
        assertEquals("x", noise.filter("x"));
    }

    @Test
    void testFilesTestNamesAndCompiledFilesInTheirEdgeCases() {
        assertFalse(JsLaunch.isTestFile(null));
        assertFalse(JsLaunch.isTestFile(Path.of("/")));
        assertFalse(JsLaunch.isTestFile(Path.of("/p/test.md")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/test.js")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/test-api.mjs")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/api-test.cjs")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/api_test.ts")));
        assertTrue(JsLaunch.isTestFile(Path.of("/p/__tests__/api.tsx")));
        assertFalse(JsLaunch.isTestFile(Path.of("/p/src/api.jsx")));
        assertEquals(Optional.empty(), JsLaunch.testNameAt(null, 0));
        assertEquals(Optional.empty(), JsLaunch.testNameAt("test('a', () => {})", -1));
        assertEquals(Optional.empty(), JsLaunch.testNameAt("test('', () => {})", 0), "an empty name is none");
        assertEquals(Optional.of("b"), JsLaunch.testNameAt("it.only(\"a\"); it(`b`)\nx", 5));
        assertEquals(List.of("node"), JsLaunch.withNodeArgs(List.of("node"), null));
        assertEquals(List.of("--x"), JsLaunch.withNodeArgs(null, List.of("--x")));

        Path cwd = Path.of("/p");
        assertNull(JsLaunch.compiledInstead(null, cwd, List.of()));
        assertNull(JsLaunch.compiledInstead(Path.of("/"), cwd, List.of()));
        assertNull(JsLaunch.compiledInstead(Path.of("/p/a.ts"), cwd, null));
        assertNull(
                JsLaunch.compiledInstead(Path.of("/p/a.js"), cwd, List.of("node", "a.js")), "the source runs itself");
        java.util.List<String> argv = new java.util.ArrayList<>(List.of("node", "--x", "b.js", "\u0000.js"));
        argv.add(null);
        argv.add("a.cjs");
        assertEquals(Path.of("/p/a.cjs"), JsLaunch.compiledInstead(Path.of("/p/src/a.ts"), cwd, argv));
        assertEquals(Path.of("a.cjs"), JsLaunch.compiledInstead(Path.of("/p/src/a.ts"), null, argv));
        assertNull(JsLaunch.compiledInstead(Path.of("/p/Main"), cwd, List.of("node", "/o/Main.js")), "no TypeScript");
        assertNull(JsLaunch.compiledInstead(Path.of("/p/a.mts"), cwd, List.of("node", "/")));
    }
}
