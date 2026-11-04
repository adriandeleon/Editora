package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Debug on node / npm run configurations starts the program as Run does. */
class NodeDebugConfigsTest {

    @Test
    void aPlannedNodeRunIsSplitAtItsScript() {
        DapManager.JsProgram p = NodeDebugConfigs.fromRunPlan(
                List.of(
                        "/nvm/bin/node",
                        "--enable-source-maps",
                        "--max-old-space-size=512",
                        "/p/app.js",
                        "a",
                        "/p/app.js"),
                "/p/app.js",
                Map.of("FORCE_COLOR", "1", "MODE", "dev"),
                Path.of("/p"));
        assertEquals(List.of("/nvm/bin/node", "--enable-source-maps", "--max-old-space-size=512"), p.runtime());
        assertEquals("/p/app.js", p.program());
        assertEquals(List.of("a", "/p/app.js"), p.args()); // only the first occurrence is the script
        assertEquals("dev", p.env().get("MODE"));
        assertEquals(Path.of("/p"), p.cwd());
        assertNull(NodeDebugConfigs.fromRunPlan(List.of("/p/app.js"), "/p/app.js", Map.of(), Path.of("/p")));
        assertNull(NodeDebugConfigs.fromRunPlan(List.of("node", "x.js"), "/p/app.js", Map.of(), Path.of("/p")));
    }

    @Test
    void anNpmScriptLaunchesThePackageManagerItself() {
        DapManager.JsProgram p = NodeDebugConfigs.forPackageManager(
                List.of("/usr/bin/npm", "run", "start", "--", "--port", "3000"),
                Path.of("/p"),
                Map.of("FORCE_COLOR", "1"));
        assertEquals(List.of("/usr/bin/npm", "run", "start", "--", "--port", "3000"), p.runtime());
        assertEquals("", p.program());
        assertEquals(List.of(), p.args());
        assertNull(NodeDebugConfigs.forPackageManager(List.of(), Path.of("/p"), Map.of()));
    }

    @Test
    void aTypeScriptSourceRunAsItsCompiledFileSplitsThere() {
        List<String> argv = List.of("/n/node", "--enable-source-maps", "/p/dist/main.js", "a");
        assertEquals(2, NodeDebugConfigs.programIndex(argv, "/p/src/main.ts"));
        var program = NodeDebugConfigs.fromRunPlan(argv, "/p/src/main.ts", Map.of(), Path.of("/p"));
        assertEquals("/p/dist/main.js", program.program());
        assertEquals(List.of("/n/node", "--enable-source-maps"), program.runtime());
        assertEquals(List.of("a"), program.args());
        assertEquals(-1, NodeDebugConfigs.programIndex(List.of("node", "/p/dist/other.js"), "/p/src/main.ts"));
        // tsx runs the source itself
        assertEquals(
                1,
                NodeDebugConfigs.programIndex(List.of("/p/node_modules/.bin/tsx", "/p/src/main.ts"), "/p/src/main.ts"));
    }

    @Test
    void jsDebugLaunchesNodeAndNodeBasedRunnersOnly() {
        assertTrue(NodeDebugConfigs.debuggableRuntime(List.of("/usr/bin/node", "--no-warnings")));
        assertTrue(NodeDebugConfigs.debuggableRuntime(List.of("/p/node_modules/.bin/tsx")));
        assertTrue(NodeDebugConfigs.debuggableRuntime(List.of("C:\\p\\node_modules\\.bin\\tsx.cmd")));
        assertTrue(NodeDebugConfigs.debuggableRuntime(List.of("ts-node")));
        assertFalse(NodeDebugConfigs.debuggableRuntime(List.of("deno", "run")));
        assertFalse(NodeDebugConfigs.debuggableRuntime(List.of("/usr/local/bin/bun")));
        assertFalse(NodeDebugConfigs.debuggableRuntime(List.of()));
    }
}
