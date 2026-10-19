package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.dap.DapManager;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
