package com.editora.run;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NodeRunPlanTest {

    private static final NodeRuntimes.NodeRuntime NODE =
            new NodeRuntimes.NodeRuntime(List.of("/nvm/v24/bin/node"), NodeRuntimes.Source.VERSION_FILE, ".nvmrc");

    private static NodeRunPlan.Request request(String name, String firstLine, String version, boolean test) {
        return new NodeRunPlan.Request(
                "/p/" + name,
                name,
                List.of("a", "b c"),
                firstLine,
                NODE,
                false,
                version,
                List.of(),
                test,
                false,
                Map.of(),
                null);
    }

    @Test
    void aScriptRunsUnderTheResolvedNodeWithSourceMapsAndColour() {
        NodeRunPlan plan = NodeRunPlan.plan(request("app.js", "console.log(1)", null, false));
        assertNull(plan.error());
        assertEquals(List.of("/nvm/v24/bin/node", "--enable-source-maps", "/p/app.js", "a", "b c"), plan.argv());
        assertEquals(Map.of("FORCE_COLOR", "1"), plan.env());
    }

    @Test
    void noColorAndTheConfigurationsVariablesWin() {
        NodeRunPlan.Request r = new NodeRunPlan.Request(
                "/p/a.js", "a.js", List.of(), null, NODE, false, null, List.of(), false, true, Map.of("X", "1"), null);
        assertEquals(Map.of("X", "1"), NodeRunPlan.plan(r).env());
        NodeRunPlan.Request own = new NodeRunPlan.Request(
                "/p/a.js",
                "a.js",
                List.of(),
                null,
                NODE,
                false,
                null,
                List.of(),
                false,
                false,
                Map.of("NO_COLOR", "1"),
                null);
        assertFalse(NodeRunPlan.plan(own).env().containsKey("FORCE_COLOR"));
    }

    @Test
    void aNodeShebangKeepsItsFlagsButRunsTheResolvedNode() {
        NodeRunPlan plan = NodeRunPlan.plan(request("tool", "#!/usr/bin/env -S node --no-warnings", null, false));
        assertEquals(
                List.of("/nvm/v24/bin/node", "--enable-source-maps", "--no-warnings", "/p/tool", "a", "b c"),
                plan.argv());
        NodeRunPlan bare = NodeRunPlan.plan(request("tool", "#!/usr/bin/env node", null, false));
        assertEquals("/nvm/v24/bin/node", bare.argv().get(0));
    }

    @Test
    void anotherRunnersShebangIsHonouredAsWritten() {
        NodeRunPlan deno = NodeRunPlan.plan(request("tool", "#!/usr/bin/env -S deno run --allow-read", null, false));
        assertEquals(List.of("deno", "run", "--allow-read", "/p/tool", "a", "b c"), deno.argv());
        NodeRunPlan tsx = NodeRunPlan.plan(request("tool", "#!/usr/bin/env tsx", "v18.0.0", false));
        assertNull(tsx.error(), "tsx strips types itself");
        assertEquals("tsx", tsx.argv().get(0));
    }

    @Test
    void typeScriptNeedsANodeThatStripsTypes() {
        assertEquals(
                NodeRunPlan.NEEDS_STRIP,
                NodeRunPlan.plan(request("main.ts", null, "v20.11.1", false)).error());
        assertEquals(
                NodeRunPlan.NEEDS_STRIP,
                NodeRunPlan.plan(request("main.mts", null, "v22.17.0", false)).error());
        assertNull(NodeRunPlan.plan(request("main.ts", null, "v22.18.0", false)).error());
        assertNull(
                NodeRunPlan.plan(request("main.cts", null, "v24.18.1", false)).error());
        assertNull(NodeRunPlan.plan(request("main.ts", null, null, false)).error(), "unknown: let it run");
        assertNull(NodeRunPlan.plan(request("app.js", null, "v18.0.0", false)).error(), "JavaScript: any version");
    }

    @Test
    void aTestFileRunsNodeTestWithTheTapReporterAndNoProgramArguments() {
        NodeRunPlan plan = NodeRunPlan.plan(request("a.test.js", null, null, true));
        assertEquals(
                List.of("/nvm/v24/bin/node", "--enable-source-maps", "--test", "--test-reporter=tap", "/p/a.test.js"),
                plan.argv());
    }

    @Test
    void aConfigurationsNodeArgumentsReplaceTheShebangsAndItsInterpreterWins() {
        NodeRuntimes.NodeRuntime configured =
                new NodeRuntimes.NodeRuntime(List.of("/opt/node18/bin/node"), NodeRuntimes.Source.CONFIGURATION, null);
        NodeRunPlan.Request r = new NodeRunPlan.Request(
                "/p/tool",
                "tool",
                List.of(),
                "#!/usr/bin/env -S deno run",
                configured,
                true,
                null,
                List.of("--inspect", "--enable-source-maps"),
                false,
                false,
                Map.of(),
                null);
        assertEquals(
                List.of("/opt/node18/bin/node", "--inspect", "--enable-source-maps", "/p/tool"),
                NodeRunPlan.plan(r).argv());
    }

    @Test
    void theShebangsEnvAssignmentsAndChdirCarryOver() {
        NodeRunPlan plan = NodeRunPlan.plan(request("tool", "#!/usr/bin/env -C sub DEBUG=1 node", null, false));
        assertEquals("1", plan.env().get("DEBUG"));
        assertEquals("sub", plan.chdir());
    }

    @Test
    void testFilesAreNamedOrUnderATestFolder() {
        Path root = Path.of("/p");
        assertTrue(NodeRunPlan.isTestFile(Path.of("/p/src/a.test.js"), root));
        assertTrue(NodeRunPlan.isTestFile(Path.of("/p/src/a.spec.mts"), root));
        assertTrue(NodeRunPlan.isTestFile(Path.of("/p/test/helpers.js"), root));
        assertTrue(NodeRunPlan.isTestFile(Path.of("/p/src/__tests__/x.ts"), root));
        assertFalse(NodeRunPlan.isTestFile(Path.of("/p/src/index.js"), root));
        assertFalse(NodeRunPlan.isTestFile(Path.of("/p/test/tool"), root), "an extensionless script is a script");
        assertFalse(NodeRunPlan.isTestFile(Path.of("/test/p/src/x.js"), root), "above the root does not count");
        assertTrue(NodeRunPlan.isTestFile(Path.of("/x/tests/y.cjs"), null));
        assertFalse(NodeRunPlan.isTestFile(Path.of("/test/x/y.js"), null));
    }

    @Test
    void namesAndLanguages() {
        assertTrue(NodeRunPlan.isNodeLanguage("javascript"));
        assertTrue(NodeRunPlan.isNodeLanguage("typescript"));
        assertFalse(NodeRunPlan.isNodeLanguage("typescriptreact"));
        assertFalse(NodeRunPlan.runnableName("index.d.ts"));
        assertTrue(NodeRunPlan.runnableName("index.ts"));
        assertTrue(NodeRunPlan.isNodeName("C:\\Program Files\\nodejs\\node.exe"));
        assertTrue(NodeRunPlan.isNodeName("nodejs"));
        assertFalse(NodeRunPlan.isNodeName("deno"));
    }

    @Test
    void nodeAndItsPackageManagersAreInterruptedOnStop() {
        for (String p : List.of("node", "/usr/bin/npm", "C:\\n\\npm.cmd", "pnpm", "yarn", "bun", "tsx")) {
            assertTrue(NodeRunPlan.interruptsOnStop(p), p);
        }
        for (String p : List.of("java", "bash", "make", "")) {
            assertFalse(NodeRunPlan.interruptsOnStop(p), p);
        }
    }
}
