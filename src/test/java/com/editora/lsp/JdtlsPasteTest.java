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

    /** A paste answer's additionalEdit: one TextEdit at line 2, an insertion or a one-line replacement. */
    private static String edit(String newText, boolean insertion) {
        var start = new com.google.gson.JsonObject();
        start.addProperty("line", 2);
        start.addProperty("character", 0);
        var end = new com.google.gson.JsonObject();
        end.addProperty("line", insertion ? 2 : 3);
        end.addProperty("character", 0);
        var range = new com.google.gson.JsonObject();
        range.add("start", start);
        range.add("end", end);
        var textEdit = new com.google.gson.JsonObject();
        textEdit.add("range", range);
        textEdit.addProperty("newText", newText);
        var edits = new com.google.gson.JsonArray();
        edits.add(textEdit);
        var changes = new com.google.gson.JsonObject();
        changes.add("file:///p/A.java", edits);
        var edit = new com.google.gson.JsonObject();
        edit.add("changes", changes);
        return edit.toString();
    }

    private static String newText(com.google.gson.JsonElement edit) {
        return edit.getAsJsonObject()
                .getAsJsonObject("changes")
                .getAsJsonArray("file:///p/A.java")
                .get(0)
                .getAsJsonObject()
                .get("newText")
                .getAsString();
    }

    /** Only the imports the pasted text uses are added (Java review A11). */
    @Test
    void onlyThePastedNamesAreImported() {
        String both = "import java.time.Instant;\nimport java.time.LocalDate;\n";
        var kept =
                JdtlsPaste.keepPastedImports(JsonParser.parseString(edit(both, true)), "Instant now = Instant.now();");
        assertEquals("import java.time.Instant;\n", newText(kept));

        assertNull(
                JdtlsPaste.keepPastedImports(
                        JsonParser.parseString(edit("\nimport java.time.LocalDate;\n", true)), "int z = 1;"),
                "nothing the paste uses: no edit at all");
        var star = JdtlsPaste.keepPastedImports(JsonParser.parseString(edit("import java.util.*;\n", true)), "int z;");
        assertEquals("import java.util.*;\n", newText(star), "a package import cannot be judged by name");
        var replacing = JdtlsPaste.keepPastedImports(JsonParser.parseString(edit(both, false)), "int z;");
        assertEquals(both, newText(replacing), "a replacement could delete an existing import: left whole");
        var statics = JdtlsPaste.keepPastedImports(
                JsonParser.parseString(edit("import static java.lang.Math.max;\n", true)), "int m = max(a, b);");
        assertEquals("import static java.lang.Math.max;\n", newText(statics));
    }

    /** The copied file, sent so jdtls names the text's types by its imports (eleventh review A11). */
    @org.junit.jupiter.api.Test
    void theCopiedFileIsSent() {
        String json =
                JdtlsPaste.paramsJson("file:///x/B.java", 0, 0, 1, 0, "Duration d;\n", 4, true, "file:///x/A.java");
        org.junit.jupiter.api.Assertions.assertEquals(
                "file:///x/A.java",
                com.google.gson.JsonParser.parseString(json)
                        .getAsJsonObject()
                        .get("copiedDocumentUri")
                        .getAsString());
        java.nio.file.Path a = java.nio.file.Path.of("/x/A.java");
        JdtlsPaste.copied("Duration d;\n", a);
        org.junit.jupiter.api.Assertions.assertEquals(a, JdtlsPaste.copiedFrom("Duration d;\r\n"));
        org.junit.jupiter.api.Assertions.assertNull(JdtlsPaste.copiedFrom("other"), "not what was copied");
        JdtlsPaste.copied("x", java.nio.file.Path.of("/x/notes.md"));
        org.junit.jupiter.api.Assertions.assertNull(JdtlsPaste.copiedFrom("x"), "only a Java file");
    }
}
