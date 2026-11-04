package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.run.NodeRunPlan;
import com.editora.run.NodeRuntimes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JS review C102 meets D106: Debug splits Run's tsx plan (Node with --import tsx) at the source file. */
class NodeDebugTsxPlanTest {

    @Test
    void aTsxPlanDebugsTheSourceUnderTheChosenNodeWithTsxLoaded() {
        NodeRunPlan plan = NodeRunPlan.plan(new NodeRunPlan.Request(
                "/p/src/main.ts",
                "main.ts",
                List.of("a"),
                null,
                new NodeRuntimes.NodeRuntime(List.of("/n/bin/node"), NodeRuntimes.Source.VERSION_FILE, ".nvmrc"),
                false,
                "v24.18.1",
                List.of(),
                false,
                false,
                Map.of(),
                null,
                List.of("--import", "tsx")));
        var program = NodeDebugConfigs.fromRunPlan(plan.argv(), "/p/src/main.ts", plan.env(), Path.of("/p"));
        assertNotNull(program);
        assertEquals(List.of("/n/bin/node", "--enable-source-maps", "--import", "tsx"), program.runtime());
        assertTrue(NodeDebugConfigs.debuggableRuntime(program.runtime()));
    }
}
