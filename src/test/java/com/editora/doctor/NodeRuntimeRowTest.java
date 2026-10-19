package com.editora.doctor;

import java.util.List;

import com.editora.run.NodeRuntimes;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Review E6: Doctor's one Run → Node.js row. */
class NodeRuntimeRowTest {

    @Test
    void aVersionIsOkAndNoVersionIsRedOnlyWhenTheProjectNeedsNode() {
        assertEquals(DoctorStatus.OK, NodeRuntimeRow.status("v24.18.1", false));
        assertEquals(DoctorStatus.MISSING, NodeRuntimeRow.status("", true));
        assertEquals(DoctorStatus.OPTIONAL, NodeRuntimeRow.status("", false));
        assertEquals(DoctorStatus.MISSING, NodeRuntimeRow.status("bash: node: not found", true));
        assertEquals(DoctorStatus.OPTIONAL, NodeRuntimeRow.status(null, false));
        assertEquals(
                DoctorStatus.MISSING, NodeRuntimeRow.status("/tmp/junit8552894125453687280/node: not found", true));
    }

    @Test
    void theDetailSaysWhichNodeAndWhy() {
        var fromFile = new NodeRuntimes.NodeRuntime(
                List.of("/h/.nvm/versions/node/v20.11.1/bin/node"), NodeRuntimes.Source.VERSION_FILE, ".nvmrc");
        assertEquals(
                "v20.11.1 — /h/.nvm/versions/node/v20.11.1/bin/node (.nvmrc, via nvm)",
                NodeRuntimeRow.detail("v20.11.1\n", fromFile));
        var onPath = new NodeRuntimes.NodeRuntime(List.of("/usr/bin/node"), NodeRuntimes.Source.PATH, null);
        assertEquals("v24.1.0 — /usr/bin/node (node on the PATH)", NodeRuntimeRow.detail("v24.1.0", onPath));
    }
}
