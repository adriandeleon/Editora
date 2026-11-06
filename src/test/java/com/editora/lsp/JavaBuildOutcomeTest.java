package com.editora.lsp;

import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** jdtls's {@code BuildWorkspaceStatus}, however the answer to {@code java/buildWorkspace} arrives. */
class JavaBuildOutcomeTest {

    @Test
    void theStatusReadsAsANumberANameOrAGsonPrimitive() {
        assertEquals(LspManager.BuildOutcome.FAILED, JavaBuilds.buildOutcome(0));
        assertEquals(LspManager.BuildOutcome.SUCCEEDED, JavaBuilds.buildOutcome(1));
        assertEquals(LspManager.BuildOutcome.SUCCEEDED, JavaBuilds.buildOutcome(1.0));
        assertEquals(LspManager.BuildOutcome.WITH_ERRORS, JavaBuilds.buildOutcome(new JsonPrimitive(2)));
        assertEquals(LspManager.BuildOutcome.CANCELLED, JavaBuilds.buildOutcome("CANCELLED"));
        assertEquals(LspManager.BuildOutcome.WITH_ERRORS, JavaBuilds.buildOutcome(new JsonPrimitive("WITH_ERROR")));
        assertEquals(LspManager.BuildOutcome.UNAVAILABLE, JavaBuilds.buildOutcome(null));
        assertEquals(LspManager.BuildOutcome.UNAVAILABLE, JavaBuilds.buildOutcome("7"));
    }
}
