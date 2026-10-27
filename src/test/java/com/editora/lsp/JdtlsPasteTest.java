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
    void theCopiedFileIsSent(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir) throws Exception {
        String json =
                JdtlsPaste.paramsJson("file:///x/B.java", 0, 0, 1, 0, "Duration d;\n", 4, true, "file:///x/A.java");
        org.junit.jupiter.api.Assertions.assertEquals(
                "file:///x/A.java",
                com.google.gson.JsonParser.parseString(json)
                        .getAsJsonObject()
                        .get("copiedDocumentUri")
                        .getAsString());
        java.nio.file.Path a = java.nio.file.Files.writeString(dir.resolve("A.java"), "class A {}\n");
        JdtlsPaste.copied("Duration d;\n", a);
        org.junit.jupiter.api.Assertions.assertEquals(a, JdtlsPaste.copiedFrom("Duration d;\r\n"));
        org.junit.jupiter.api.Assertions.assertNull(JdtlsPaste.copiedFrom("other"), "not what was copied");
        java.nio.file.Files.delete(a);
        org.junit.jupiter.api.Assertions.assertNull(
                JdtlsPaste.copiedFrom("Duration d;\n"), "deleted since: jdtls would answer nothing (twelfth AL3)");
        JdtlsPaste.copied("x", java.nio.file.Path.of("/x/notes.md"));
        org.junit.jupiter.api.Assertions.assertNull(JdtlsPaste.copiedFrom("x"), "only a Java file");
    }

    /** An import edit above the pasted text applies though the user typed after it (twelfth review AL2). */
    @org.junit.jupiter.api.Test
    void anImportEditAboveThePasteIsAbove() {
        var imports = new org.eclipse.lsp4j.WorkspaceEdit(java.util.Map.of(
                "file:///x/B.java",
                java.util.List.of(new org.eclipse.lsp4j.TextEdit(
                        new org.eclipse.lsp4j.Range(
                                new org.eclipse.lsp4j.Position(1, 0), new org.eclipse.lsp4j.Position(1, 0)),
                        "import java.time.Instant;\n"))));
        org.junit.jupiter.api.Assertions.assertTrue(JdtlsPaste.above(imports, 5, 4));
        org.junit.jupiter.api.Assertions.assertFalse(JdtlsPaste.above(imports, 0, 3), "below the paste");
        org.junit.jupiter.api.Assertions.assertFalse(
                JdtlsPaste.above(new org.eclipse.lsp4j.WorkspaceEdit(java.util.Map.of()), 5, 0), "nothing");
    }

    /**
     * jdtls's Errors that are no import's failure: a build's compile error, a request on a file outside the source
     * folders (eleventh and twelfth review CM1).
     */
    @org.junit.jupiter.api.Test
    void requestErrorsAreNoImportFailure() {
        org.junit.jupiter.api.Assertions.assertFalse(
                LanguageServerSession.importError("Error occured while building workspace. Details: …"));
        org.junit.jupiter.api.Assertions.assertFalse(LanguageServerSession.importError(
                "Java Model Exception: Java Model Status [scripts [in shelf-debug-demo] does not exist]"));
        org.junit.jupiter.api.Assertions.assertTrue(
                LanguageServerSession.importError("Project build error: Non-parseable POM /x/pom.xml"));
        org.junit.jupiter.api.Assertions.assertFalse(LanguageServerSession.importError(null));
    }

    /** The files a server's own watcher reports beside the Project panel: grammars and Java build files (CL1). */
    @org.junit.jupiter.api.Test
    void buildFilesAreModelFiles() {
        org.junit.jupiter.api.Assertions.assertTrue(LspManager.modelFile(java.nio.file.Path.of("app/pom.xml")));
        org.junit.jupiter.api.Assertions.assertTrue(
                LspManager.modelFile(java.nio.file.Path.of("lib/build.gradle.kts")));
        org.junit.jupiter.api.Assertions.assertTrue(LspManager.modelFile(java.nio.file.Path.of("x/note.xsd")));
        org.junit.jupiter.api.Assertions.assertFalse(LspManager.modelFile(java.nio.file.Path.of("src/A.java")));
    }
}
