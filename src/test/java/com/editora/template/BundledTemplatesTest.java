package com.editora.template;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.editora.config.ConfigManager;
import com.editora.editorconfig.EditorConfigParser;
import com.editora.http.HttpEnv;
import com.editora.http.HttpFile;
import com.editora.markwhen.MarkwhenParser;
import com.editora.markwhen.MwNode;
import com.editora.markwhen.Timeline;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bundled templates as a user gets them by pressing Create without changing a field: every one is usable
 * as it stands, and the ones in a format Editora parses itself parse cleanly.
 */
class BundledTemplatesTest {

    private static List<Template> bundled(Path tmp) {
        return new TemplateRegistry(new ConfigManager(tmp.resolve("cfg"))).bundledTemplates();
    }

    private static Template bundled(Path tmp, String id) {
        return bundled(tmp).stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    /** Each file the template would create in a fresh folder, by relative path, with the wizard's defaults. */
    private static Map<String, String> rendered(Path tmp, Template t) throws Exception {
        Path dir = java.nio.file.Files.createDirectories(tmp.resolve("out").resolve(t.id()));
        TemplateOutput.Plan plan = TemplateOutput.plan(new TemplateOutput.Request(
                t, dir, Map.of(), "Ada", "demo", null, LocalDateTime.of(2026, 3, 4, 5, 6), p -> null));
        assertFalse(plan.refused(), t.id());
        Map<String, String> out = new LinkedHashMap<>();
        for (TemplateOutput.Target target : plan.targets()) {
            out.put(target.relative(), target.text());
        }
        return out;
    }

    @Test
    void theIndexListsEveryBundledTemplateAndEachOneLoads(@TempDir Path tmp) throws Exception {
        List<String> index;
        try (var in = BundledTemplatesTest.class.getResourceAsStream("/com/editora/templates/index.json")) {
            assertNotNull(in);
            index = List.of(new ObjectMapper().readValue(in, String[].class));
        }
        assertEquals(index, bundled(tmp).stream().map(Template::id).toList(), "a listed file that fails to load");
        for (Template t : bundled(tmp)) {
            assertFalse(t.description().isBlank(), t.id() + " has no description for the picker");
        }
    }

    @Test
    void everyAskedForVariableHasADefaultSoCreateWorksStraightAway(@TempDir Path tmp) {
        for (Template t : bundled(tmp)) {
            for (TemplateEngine.TemplateVar v :
                    TemplateEngine.promptedVariables(t, new TemplateEngine.Context(false))) {
                assertFalse(v.defaultValue().isBlank(), t.id() + ": ${" + v.name() + "} has no default");
            }
        }
    }

    @Test
    void everyAskedForVariableHasATranslatedLabel(@TempDir Path tmp) throws Exception {
        java.util.Properties catalog = new java.util.Properties();
        try (var in = BundledTemplatesTest.class.getResourceAsStream("/com/editora/i18n/messages.properties")) {
            assertNotNull(in);
            catalog.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        for (Template t : bundled(tmp)) {
            assertTrue(t.labels().isEmpty(), t.id() + ": a bundled label would be English in every language");
            for (TemplateEngine.TemplateVar v :
                    TemplateEngine.promptedVariables(t, new TemplateEngine.Context(false))) {
                assertNotNull(
                        catalog.getProperty("template.var." + v.name()),
                        t.id() + ": ${" + v.name() + "} needs template.var." + v.name() + " in the six catalogs");
            }
        }
    }

    @Test
    void noReferenceIsLeftUnresolved(@TempDir Path tmp) throws Exception {
        for (Template t : bundled(tmp)) {
            rendered(tmp, t).forEach((path, text) -> {
                // The one intended literal is the TypeScript template string, written as $${name} in the JSON.
                String checked = text.replace("`Hello, ${name}!`", "");
                assertFalse(checked.contains("${"), t.id() + "/" + path + " still has a ${…}:\n" + text);
            });
        }
    }

    @Test
    void theHttpRequestsParseWithTheirChecksAndBothEnvironmentsResolve(@TempDir Path tmp) throws Exception {
        Map<String, String> files = rendered(tmp, bundled(tmp, "http-requests"));
        List<HttpFile.Request> requests = HttpFile.parse(files.get("requests.http"));
        assertEquals(
                List.of("GET", "POST", "GET"),
                requests.stream().map(HttpFile.Request::method).toList());
        assertEquals("{{host}}/items", requests.get(0).url());
        for (HttpFile.Request r : requests) {
            assertTrue(r.warnings().isEmpty(), r.name() + ": " + r.warnings());
            assertTrue(HttpFile.parseRequest(r).warnings().isEmpty(), r.name());
        }
        assertEquals(1, requests.get(0).directives().checks().size(), "the @assert");
        assertEquals(2, requests.get(1).directives().checks().size(), "the @assert and the @capture");
        assertTrue(HttpFile.parseRequest(requests.get(1)).body().contains("\"name\": \"example\""));

        String pub = files.get(HttpEnv.PUBLIC_FILE);
        String priv = files.get(HttpEnv.PRIVATE_FILE);
        Map<String, String> dev = HttpEnv.merged(pub, priv, "dev");
        assertEquals("http://localhost:8080", dev.get("host"));
        assertEquals("dev-token-change-me", dev.get("token"));
        assertEquals(
                "https://api.example.com", HttpEnv.merged(pub, priv, "prod").get("host"));
    }

    @Test
    void theJsonFilesAreValidJson(@TempDir Path tmp) throws Exception {
        ObjectMapper json = new ObjectMapper();
        for (Template t : bundled(tmp)) {
            for (var e : rendered(tmp, t).entrySet()) {
                if (e.getKey().endsWith(".json")) {
                    assertTrue(json.readTree(e.getValue()).isObject(), t.id() + "/" + e.getKey());
                }
            }
        }
    }

    @Test
    void theMarkwhenTimelineHasItsTitleTagsAndEveryEvent(@TempDir Path tmp) throws Exception {
        Timeline tl = MarkwhenParser.parse(
                rendered(tmp, bundled(tmp, "markwhen-timeline")).get("timeline.mw"));
        assertEquals("Timeline", tl.title());
        assertEquals(2, tl.tagColors().size());
        assertEquals(2, tl.nodes().size(), "two groups");
        long events = tl.nodes().stream()
                .mapToLong(n -> ((MwNode.Group) n).children().size())
                .sum();
        assertEquals(6, events, "a line whose date does not parse is dropped silently");
    }

    @Test
    void theEditorConfigIsARootFileWithEverySection(@TempDir Path tmp) throws Exception {
        EditorConfigParser.Parsed p = EditorConfigParser.parse(
                rendered(tmp, bundled(tmp, "editorconfig")).get(".editorconfig"));
        assertTrue(p.root());
        assertEquals(5, p.sections().size());
        assertEquals("lf", p.sections().get(0).properties().get("end_of_line"));
    }

    @Test
    void theJavaScriptHasNoJavaExtensionAndIsTypedAsJava(@TempDir Path tmp) throws Exception {
        Template t = bundled(tmp, "java-script");
        // The java launcher only runs a shebang file in source mode when its name does not end in .java.
        assertEquals(List.of("script"), List.copyOf(rendered(tmp, t).keySet()));
        assertEquals("java", t.language());
        assertTrue(rendered(tmp, t).get("script").startsWith("#!/usr/bin/env -S java --source 25\n"));
    }
}
