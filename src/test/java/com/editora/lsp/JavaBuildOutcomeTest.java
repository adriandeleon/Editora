package com.editora.lsp;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** jdtls's {@code BuildWorkspaceStatus}, however the answer to {@code java/buildWorkspace} arrives. */
class JavaBuildOutcomeTest {

    @Test
    void theStatusReadsAsANumberANameOrAGsonPrimitive() {
        assertEquals(LspManager.BuildOutcome.FAILED, LspManager.buildOutcome(0));
        assertEquals(LspManager.BuildOutcome.SUCCEEDED, LspManager.buildOutcome(1));
        assertEquals(LspManager.BuildOutcome.SUCCEEDED, LspManager.buildOutcome(1.0));
        assertEquals(LspManager.BuildOutcome.WITH_ERRORS, LspManager.buildOutcome(new JsonPrimitive(2)));
        assertEquals(LspManager.BuildOutcome.CANCELLED, LspManager.buildOutcome("CANCELLED"));
        assertEquals(LspManager.BuildOutcome.WITH_ERRORS, LspManager.buildOutcome(new JsonPrimitive("WITH_ERROR")));
        assertEquals(LspManager.BuildOutcome.UNAVAILABLE, LspManager.buildOutcome(null));
        assertEquals(LspManager.BuildOutcome.UNAVAILABLE, LspManager.buildOutcome("7"));
    }
}
