package com.editora.lsp;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

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
        for (String id : List.of("json", "css", "html", "yaml", "typescript", "astro", "go", "xml")) {
            assertNull(LspServerSettings.push(id, true), id + " must not be pushed another server's settings");
        }
        assertNull(LspServerSettings.push(null, false));
        assertEquals(
                java.util.Set.of("python"),
                LspServerSettings.push("python", false).keySet());
        assertEquals(
                java.util.Set.of("java"), LspServerSettings.push("java", false).keySet());
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

    /** A {@code null} answer makes the CSS validator throw, so these servers' own sections get an object. */
    @Test
    void cssAndHtmlServersGetAnObjectForTheirOwnSections() {
        for (String section : List.of("css", "scss", "less")) {
            assertEquals(Map.of(), LspServerSettings.answer("css", section));
        }
        for (String section : List.of("css", "html", "javascript")) {
            assertEquals(Map.of(), LspServerSettings.answer("html", section));
        }
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

    /** The answers a session gives are its own server's — the request as the real HTML server sends it. */
    @Test
    void aSessionAnswersConfigurationForItsOwnServer() {
        List<Object> html = ask(session("html"), "css", "html", "javascript", "js/ts");
        assertEquals(java.util.Arrays.asList(Map.of(), Map.of(), Map.of(), null), html);
        assertEquals(java.util.Arrays.asList((Object) null), ask(session("json"), "css"));
        assertTrue(ask(session("python"), "python").get(0) instanceof Map);
    }
}
