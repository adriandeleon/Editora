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
}
