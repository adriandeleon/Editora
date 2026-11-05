package com.editora.dap;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Which Node.js runs vscode-js-debug when the chosen one is too old (review D101). */
class JsAdapterNodeTest {

    @Test
    void aNewEnoughChosenNodeRunsTheServerItself() {
        assertEquals("/opt/node22", JsAdapterNode.choose("/opt/node22", "v22.1.0", List.of("node"), c -> "v24.0.0"));
        // An unknown version is given the chance (Node then says so itself).
        assertEquals("/opt/node", JsAdapterNode.choose("/opt/node", null, List.of("node"), c -> "v24.0.0"));
    }

    @Test
    void anOldChosenNodeFallsBackToTheFirstNewEnoughCandidate() {
        Map<String, String> versions = Map.of("/old16", "v16.20.2", "/x/v14", "v14.0.0", "/usr/bin/node", "v20.1.0");
        assertEquals(
                "/usr/bin/node",
                JsAdapterNode.choose(
                        "/old16", "v16.20.2", List.of("/old16", "/x/v14", "/usr/bin/node"), versions::get));
    }

    @Test
    void noNewEnoughCandidateIsNull() {
        assertNull(JsAdapterNode.choose("/old16", "v16.20.2", List.of("/old16", "/x"), c -> "v12.0.0"));
        assertNull(JsAdapterNode.choose("/old16", "v16.20.2", List.of("/y"), c -> null));
    }
}
