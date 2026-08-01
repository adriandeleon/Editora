package com.editora.lsp;

import java.util.List;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.eclipse.lsp4j.ExecuteCommandOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code java.edit.handlePasteEvent} wire shapes (no toolkit). Both were established against a
 * live jdtls ({@code JdtlsPasteProbeTest}); these tests keep them from drifting — a shape regression here
 * fails no compile and no other test, the paste just silently stops importing (#723's failure mode).
 */
class JdtlsPasteTest {

    @Test
    void paramsJsonMatchesTheShapeTheLiveServerAccepted() {
        String json = JdtlsPaste.paramsJson("file:///tmp/App.java", 4, 0, 5, 0, "List<String> xs;\n", 4, true);
        JsonObject p = JsonParser.parseString(json).getAsJsonObject();
        JsonObject location = p.getAsJsonObject("location");
        assertEquals("file:///tmp/App.java", location.get("uri").getAsString());
        JsonObject range = location.getAsJsonObject("range");
        assertEquals(4, range.getAsJsonObject("start").get("line").getAsInt());
        assertEquals(0, range.getAsJsonObject("start").get("character").getAsInt());
        assertEquals(5, range.getAsJsonObject("end").get("line").getAsInt());
        assertEquals("List<String> xs;\n", p.get("text").getAsString());
        JsonObject fmt = p.getAsJsonObject("formattingOptions");
        assertEquals(4, fmt.get("tabSize").getAsInt());
        assertTrue(fmt.get("insertSpaces").getAsBoolean());
    }

    @Test
    void paramsJsonEscapesThePastedTextItself() {
        // The pasted text is arbitrary source — quotes, newlines, backslashes. Built by gson, not concat,
        // so it must survive a parse round-trip verbatim.
        String nasty = "String s = \"a\\\"b\";\n\t// \\ tricky\n";
        String json = JdtlsPaste.paramsJson("file:///x", 0, 0, 2, 0, nasty, 2, false);
        assertEquals(
                nasty,
                JsonParser.parseString(json).getAsJsonObject().get("text").getAsString());
    }

    @Test
    void supportsPasteEventReadsTheAdvertisedCommandList() {
        ServerCapabilities caps = new ServerCapabilities();
        assertFalse(JdtlsPaste.supportsPasteEvent(caps), "no provider");
        caps.setExecuteCommandProvider(new ExecuteCommandOptions(List.of("java.edit.organizeImports")));
        assertFalse(JdtlsPaste.supportsPasteEvent(caps), "provider without the command");
        caps.setExecuteCommandProvider(
                new ExecuteCommandOptions(List.of("java.edit.organizeImports", "java.edit.handlePasteEvent")));
        assertTrue(JdtlsPaste.supportsPasteEvent(caps));
        assertFalse(JdtlsPaste.supportsPasteEvent(null));
    }

    @Test
    void additionalEditIsExtractedFromTheDocumentPasteEditShape() {
        // The captured live answer: {insertText: ..., additionalEdit: {changes: {uri: [textEdit...]}}}.
        String answer = "{\"insertText\":\"        List<String> xs;\\n\",\"additionalEdit\":{\"changes\":"
                + "{\"file:///tmp/App.java\":[{\"range\":{\"start\":{\"line\":0,\"character\":13},"
                + "\"end\":{\"line\":2,\"character\":0}},\"newText\":\"\\n\\nimport java.util.List;\\n\\n\"}]}}}";
        var edit = JdtlsPaste.additionalEdit(JsonParser.parseString(answer));
        assertTrue(edit != null && edit.isJsonObject());
        assertTrue(edit.getAsJsonObject().has("changes"));
    }

    @Test
    void anAnswerWithoutAnEditYieldsNullNotAThrow() {
        assertNull(JdtlsPaste.additionalEdit(null));
        assertNull(JdtlsPaste.additionalEdit("not json"));
        assertNull(JdtlsPaste.additionalEdit(JsonParser.parseString("{\"insertText\":\"x\"}")));
        assertNull(JdtlsPaste.additionalEdit(JsonParser.parseString("{\"additionalEdit\":null}")));
        assertNull(JdtlsPaste.additionalEdit(JsonParser.parseString("[]")));
    }
}
