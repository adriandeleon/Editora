package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.config.Settings;
import org.eclipse.lsp4j.ConfigurationItem;
import org.eclipse.lsp4j.ConfigurationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings push and the {@code workspace/configuration} answers are chosen per server. */
class LspServerSettingsTest {

    /**
     * vscode-json-language-server turns validation off for any pushed object without
     * {@code json.validate.enable} — even an empty one — so servers we have no settings for get no push.
     */
    @Test
    void onlyTheServersWithSettingsOfTheirOwnArePushedAnything() {
        for (String id : List.of("css", "yaml", "typescript", "astro", "go", "xml")) { // html: its session pushes
            assertNull(LspServerSettings.push(id, true), id + " must not be pushed another server's settings");
        }
        assertNull(LspServerSettings.push(null, false));
        assertEquals(
                java.util.Set.of("python"),
                LspServerSettings.push("python", false).keySet());
        assertEquals(
                java.util.Set.of("java"), LspServerSettings.push("java", false).keySet());
    }

    /**
     * The JSON server is pushed its schemas, and always {@code json.validate.enable}: without it the push would
     * switch validation off (JS review A102).
     */
    @SuppressWarnings("unchecked")
    @Test
    void theJsonServerGetsTheBundledSchemasWithValidationOn() {
        Map<String, Object> json =
                (Map<String, Object>) LspServerSettings.push("json", false).get("json");
        assertEquals(Map.of("enable", true), json.get("validate"));
        List<Map<String, Object>> schemas = (List<Map<String, Object>>) json.get("schemas");
        assertEquals(2, schemas.size(), "package.json and tsconfig/jsconfig, both bundled");
        assertEquals(List.of("package.json"), schemas.get(0).get("fileMatch"));
        assertTrue(((List<String>) schemas.get(1).get("fileMatch")).contains("jsconfig.json"));
        var tsconfig = (com.google.gson.JsonObject) schemas.get(1).get("schema");
        var options = tsconfig.getAsJsonObject("properties").getAsJsonObject("compilerOptions");
        assertEquals(false, options.get("additionalProperties").getAsBoolean(), "a typo like strictt is flagged");
        assertTrue(options.getAsJsonObject("properties").has("strict"));
        var pkg = (com.google.gson.JsonObject) schemas.get(0).get("schema");
        assertTrue(pkg.getAsJsonObject("properties").has("workspaces"));
    }

    /** JSON with comments opens as jsonc, where the server allows comments and trailing commas (JS review A102). */
    @Test
    void commentedConfigFilesOpenAsJsonc() {
        for (String f : List.of(
                "tsconfig.json",
                "tsconfig.base.json",
                "jsconfig.json",
                ".eslintrc",
                ".eslintrc.json",
                ".babelrc",
                ".swcrc",
                "x.jsonc",
                "deno.json",
                "biome.jsonc",
                "app.code-workspace",
                ".vscode/settings.json",
                "bun.lock")) {
            assertEquals("jsonc", LspServerRegistry.protocolLanguageId("json", java.nio.file.Path.of("/p/" + f)), f);
        }
        for (String f : List.of("package.json", "data.json", "composer.json")) {
            assertEquals("json", LspServerRegistry.protocolLanguageId("json", java.nio.file.Path.of("/p/" + f)), f);
        }
    }

    /** Pyright reads {@code autoSearchPaths = !!analysis.autoSearchPaths} once an analysis object exists. */
    @SuppressWarnings("unchecked")
    @Test
    void everyPythonAnalysisObjectKeepsAutoSearchPathsOn() {
        var pushed =
                (Map<String, Object>) LspServerSettings.push("python", false).get("python");
        var whole = (Map<String, Object>) LspServerSettings.answer("python", "python");
        for (Object analysis : List.of(
                pushed.get("analysis"),
                whole.get("analysis"),
                LspServerSettings.answer("python", "python.analysis"),
                LspServerSettings.answer("python", "basedpyright.analysis"))) {
            Map<String, Object> a = (Map<String, Object>) analysis;
            assertEquals(Boolean.TRUE, a.get("autoSearchPaths"));
            assertEquals(Boolean.TRUE, a.get("autoImportCompletions"));
        }
        assertEquals(Boolean.TRUE, LspServerSettings.answer("python", "python.analysis.autoImportCompletions"));
        assertNull(LspServerSettings.answer("python", "pyright"));
    }

    /**
     * A {@code null} answer makes the CSS validator throw, so these servers' own sections get an object. The HTML
     * server's {@code html} section is VS Code's defaults: {@code {}} left {@code <pre>} and {@code <textarea>}
     * unprotected from Format Document (HTML review B2).
     */
    @SuppressWarnings("unchecked")
    @Test
    void cssAndHtmlServersGetAnObjectForTheirOwnSections() {
        for (String section : List.of("css", "scss", "less")) {
            assertEquals(Map.of(), LspServerSettings.answer("css", section));
        }
        for (String section : List.of("css", "javascript")) { // VS Code's format defaults (HTML review 2 B105)
            var embedded = (Map<String, Object>) LspServerSettings.answer("html", section);
            assertTrue(embedded.get("format") instanceof Map, section);
        }
        var html = (Map<String, Object>) LspServerSettings.answer("html", "html");
        String unformatted = (String) ((Map<String, Object>) html.get("format")).get("contentUnformatted");
        assertTrue(List.of(unformatted.split(",")).containsAll(List.of("pre", "code", "textarea")), unformatted);
        assertEquals(Boolean.TRUE, html.get("autoClosingTags"));
        // {} for js/ts makes the HTML server's JavaScript mode throw (strictNullChecks of undefined).
        assertNull(LspServerSettings.answer("html", "js/ts"));
        assertNull(LspServerSettings.answer("css", "editor"));
    }

    @Test
    void sectionsThatBelongToAnotherServerStayNull() {
        assertNull(LspServerSettings.answer("json", "css"));
        assertNull(LspServerSettings.answer("java", "python"));
        assertNull(LspServerSettings.answer("yaml", "yaml"));
        assertNull(LspServerSettings.answer("css", null));
        assertNull(LspServerSettings.answer(null, "css"));
    }

    private static LanguageServerSession session(String serverId) {
        var spec = new LspServerRegistry.ServerSpec(serverId, List.of(serverId + "-ls"), List.of());
        var s = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {}, null);
        s.attachForTest(new FakeLanguageServer(), new ServerCapabilities());
        return s;
    }

    private static List<Object> ask(LanguageServerSession session, String... sections) {
        var items = java.util.Arrays.stream(sections)
                .map(section -> {
                    var item = new ConfigurationItem();
                    item.setSection(section);
                    return item;
                })
                .toList();
        return session.configuration(new ConfigurationParams(items)).join();
    }

    /**
     * jdtls asks for a document's indentation before every code action and formats quick fixes and generated
     * members with the answer (Java review B8, D9).
     */
    @Test
    void jdtlsIsToldEachDocumentsIndentation() {
        var indent = new FormattingIndent(2, true);
        assertEquals(2, LspServerSettings.javaFormatAnswer("java.format.tabSize", indent));
        assertEquals(Boolean.TRUE, LspServerSettings.javaFormatAnswer("java.format.insertSpaces", indent));
        assertNull(LspServerSettings.javaFormatAnswer("java.format.enabled", indent));
        assertNull(LspServerSettings.javaFormatAnswer("java.format.tabSize", null), "unknown document: jdtls decides");

        var s = session("java");
        s.setIndentForUri(uri -> uri.endsWith("Tabbed.java") ? new FormattingIndent(8, false) : null);
        var items = new java.util.ArrayList<ConfigurationItem>();
        for (String section : List.of("java.format.tabSize", "java.format.insertSpaces")) {
            var item = new ConfigurationItem();
            item.setScopeUri("file:///p/src/Tabbed.java");
            item.setSection(section);
            items.add(item);
        }
        var unscoped = new ConfigurationItem();
        unscoped.setSection("java.format.tabSize");
        items.add(unscoped);
        assertEquals(
                java.util.Arrays.asList(8, false, null),
                s.configuration(new ConfigurationParams(items)).join());
    }

    /** The answers a session gives are its own server's — the request as the real HTML server sends it. */
    @Test
    void aSessionAnswersConfigurationForItsOwnServer() {
        List<Object> html = ask(session("html"), "css", "html", "javascript", "js/ts");
        HtmlServerSettings d = HtmlServerSettings.defaults();
        assertEquals(
                java.util.Arrays.asList(d.answer("css", null), d.section(null), d.answer("javascript", null), null),
                html);
        assertEquals(java.util.Arrays.asList((Object) null), ask(session("json"), "css"));
        assertTrue(ask(session("python"), "python").get(0) instanceof Map);
    }

    /**
     * The HTML server asks per document: the answer carries that document's wrap column and final newline
     * (HTML review A3, D6), and the session's own settings — a change of them is pushed, which makes the server ask
     * again (D3: the auto-close switch takes effect without a restart).
     */
    @SuppressWarnings("unchecked")
    @Test
    void theHtmlServerIsAnsweredPerDocumentAndPushedOnChange() {
        var fake = new FakeLanguageServer();
        var spec = new LspServerRegistry.ServerSpec("html", List.of("html-ls"), List.of());
        var s = new LanguageServerSession(spec, Path.of("/tmp"), d -> {}, (t, m) -> {}, null);
        s.attachForTest(fake, new ServerCapabilities());
        s.setIndentForUri(
                uri -> uri.endsWith("narrow.html") ? new FormattingIndent(2, true).withDocument(60, true) : null);
        var item = new ConfigurationItem();
        item.setScopeUri("file:///p/narrow.html");
        item.setSection("html");
        var html = (Map<String, Object>)
                s.configuration(new ConfigurationParams(List.of(item))).join().get(0);
        var format = (Map<String, Object>) html.get("format");
        assertEquals(60, format.get("wrapLineLength"));
        assertEquals(Boolean.FALSE, format.get("endWithNewline"), "Editora keeps the final newline (B104)");

        int before = fake.configurations.size();
        s.setHtmlSettings(HtmlServerSettings.defaults());
        assertEquals(before, fake.configurations.size(), "unchanged: nothing pushed");
        var noAutoClose = new Settings();
        noAutoClose.setAutoCloseTags(false);
        s.setHtmlSettings(HtmlServerSettings.of(noAutoClose));
        assertEquals(before + 1, fake.configurations.size());
        var pushed = (Map<String, Object>) fake.configurations.get(before).getSettings();
        assertEquals(Boolean.FALSE, ((Map<String, Object>) pushed.get("html")).get("autoClosingTags"));
        assertEquals(Boolean.FALSE, ((Map<String, Object>) ask(s, "html").get(0)).get("autoClosingTags"));
    }

    /**
     * The interpreter goes in the {@code python} object of both channels and as the leaf key (Python review
     * B1); with none resolved the key is absent, so Pyright's own lookup stays in charge.
     */
    @SuppressWarnings("unchecked")
    @Test
    void theInterpreterIsSentAsPythonPath() {
        String exe = "/proj/.venv/bin/python";
        var pushed = (Map<String, Object>)
                LspServerSettings.push("python", false, null, exe).get("python");
        assertEquals(exe, pushed.get("pythonPath"));
        assertEquals(Boolean.TRUE, ((Map<String, Object>) pushed.get("analysis")).get("autoSearchPaths"));
        assertEquals(
                exe, ((Map<String, Object>) LspServerSettings.answer("python", "python", null, exe)).get("pythonPath"));
        assertEquals(exe, LspServerSettings.answer("python", "python.pythonPath", null, exe));
        assertTrue(!((Map<String, Object>) LspServerSettings.answer("python", "python")).containsKey("pythonPath"));
        assertNull(LspServerSettings.answer("python", "python.pythonPath"));
        assertNull(LspServerSettings.answer("bash", "python.pythonPath", null, exe), "only the Python server");
    }
}
