package com.editora.lsp;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the {@code java.edit.smartSemicolonDetection} wire shapes captured from a live jdtls. */
class JdtlsSmartSemicolonTest {

    @Test
    void paramsJsonIsTheStringifiedUriAndPosition() {
        String json = JdtlsSmartSemicolon.paramsJson("file:///tmp/App.java", 5, 28);
        JsonObject p = JsonParser.parseString(json).getAsJsonObject();
        assertEquals("file:///tmp/App.java", p.get("uri").getAsString());
        assertEquals(5, p.getAsJsonObject("position").get("line").getAsInt());
        assertEquals(28, p.getAsJsonObject("position").get("character").getAsInt());
    }

    @Test
    void supportedReadsTheAdvertisedCommandList() {
        ServerCapabilities caps = new ServerCapabilities();
        assertFalse(JdtlsSmartSemicolon.supported(caps));
        caps.setExecuteCommandProvider(new ExecuteCommandOptions(List.of("java.edit.organizeImports")));
        assertFalse(JdtlsSmartSemicolon.supported(caps));
        caps.setExecuteCommandProvider(new ExecuteCommandOptions(List.of(JdtlsSmartSemicolon.COMMAND)));
        assertTrue(JdtlsSmartSemicolon.supported(caps));
        assertFalse(JdtlsSmartSemicolon.supported(null));
    }

    @Test
    void readsThePositionOutOfTheCapturedAnswer() {
        // Verbatim from the live probe: caret before the ')' at char 28 → the ';' belongs at 29.
        String answer = "{\"uri\":\"file:///tmp/App.java\",\"position\":{\"line\":5.0,\"character\":29.0}}";
        assertArrayEquals(new int[] {5, 29}, JdtlsSmartSemicolon.answeredPosition(JsonParser.parseString(answer)));
    }

    @Test
    void theNumbersSurviveArrivingAsJsonDoubles() {
        // gson's untyped mapping makes every number a double; reading them as ints throws on "29.0".
        assertArrayEquals(
                new int[] {0, 0},
                JdtlsSmartSemicolon.answeredPosition(
                        JsonParser.parseString("{\"position\":{\"line\":0.0,\"character\":0.0}}")));
    }

    @Test
    void anythingUnusableIsNullNotAThrow() {
        assertNull(JdtlsSmartSemicolon.answeredPosition(null), "no answer (the ordinary case)");
        assertNull(JdtlsSmartSemicolon.answeredPosition("not json"));
        assertNull(JdtlsSmartSemicolon.answeredPosition(JsonParser.parseString("{}")));
        assertNull(JdtlsSmartSemicolon.answeredPosition(JsonParser.parseString("{\"position\":null}")));
        assertNull(JdtlsSmartSemicolon.answeredPosition(JsonParser.parseString("{\"position\":{\"line\":1}}")));
        assertNull(
                JdtlsSmartSemicolon.answeredPosition(
                        JsonParser.parseString("{\"position\":{\"line\":-1,\"character\":2}}")),
                "a negative position is not addressable");
    }
}
