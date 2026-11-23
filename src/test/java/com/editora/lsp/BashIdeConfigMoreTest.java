package com.editora.lsp;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * More of {@link BashIdeConfig}: which of a project's keys a window uses, trusted or not, the values of the wrong
 * type that are left out, and the argument tokenizer's escapes.
 */
class BashIdeConfigMoreTest {

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void theKeysAProjectOverridesDependOnTheirTypeAndTrust() {
        Map<String, Object> project = map(
                "shellcheckPath",
                "/opt/sc",
                "explainshellEndpoint",
                "https://explain.example",
                "shellcheckArguments",
                List.of("-x"),
                "backgroundAnalysisMaxFiles",
                10,
                "includeAllWorkspaceSymbols",
                true,
                "enableSourceErrorDiagnostics",
                "yes",
                "globPattern",
                "**/*.sh",
                "logLevel",
                "verbose",
                "custom",
                3,
                "shfmt",
                map(
                        "path", "/opt/shfmt",
                        "languageDialect", "posix",
                        "caseIndent", true,
                        "keepPadding", "no",
                        "additionalArguments", "-mn",
                        "future", false));

        assertEquals(
                List.of(
                        "shellcheckArguments",
                        "backgroundAnalysisMaxFiles",
                        "includeAllWorkspaceSymbols",
                        "globPattern",
                        "shfmt.languageDialect",
                        "shfmt.caseIndent",
                        "shfmt.additionalArguments"),
                BashIdeConfig.overriddenKeys(project, false));
        assertEquals(
                List.of(
                        "shellcheckPath",
                        "explainshellEndpoint",
                        "shellcheckArguments",
                        "backgroundAnalysisMaxFiles",
                        "includeAllWorkspaceSymbols",
                        "globPattern",
                        "custom",
                        "shfmt.path",
                        "shfmt.languageDialect",
                        "shfmt.caseIndent",
                        "shfmt.additionalArguments",
                        "shfmt.future"),
                BashIdeConfig.overriddenKeys(project, true));
        assertEquals(
                List.of("shellcheckPath", "shfmt.path"),
                BashIdeConfig.overriddenKeys(
                        map("shellcheckPath", " ", "shfmt", map("path", "", "languageDialect", "fish")), false),
                "a blank path turns the tool off, trusted or not");
        assertEquals(
                List.of(), BashIdeConfig.overriddenKeys(map("shfmt", "not a map", "shellcheckArguments", 5), true));
        assertEquals(List.of(), BashIdeConfig.overriddenKeys(Map.of(), true));
        assertEquals(List.of(), BashIdeConfig.overriddenKeys(null, true));
    }

    @Test
    void onlyAValueATrustedFolderWouldBeGivenIsATrustRequest() {
        assertEquals(
                List.of("bash: custom = 3"),
                BashIdeConfig.trustRequests(map("custom", 3, "globPattern", "**/*.sh", "logLevel", "INFO")));
        assertEquals(List.of(), BashIdeConfig.trustRequests(map("shellcheckPath", " ", "shfmt", "x")));
        assertEquals(List.of(), BashIdeConfig.trustRequests(null));
    }

    @Test
    void aProjectsWrongTypesAreLeftOutOfTheSection() {
        Map<String, Object> project = new HashMap<>();
        project.put("globPattern", " ");
        project.put("logLevel", " INFO ");
        project.put("shellcheckExternalSources", "true");
        project.put("backgroundAnalysisIgnore", List.of("a", 1));
        project.put("object", new Object());
        project.put("shfmt", map("additionalArguments", List.of("-i", "2"), "future", new Object()));
        Map<String, Object> section =
                BashIdeConfig.defaults().withProject(project, true).toSection();

        assertEquals(BashIdeConfig.DEFAULT_GLOB, section.get("globPattern"), "a blank pattern is none");
        assertEquals("info", section.get("logLevel"));
        assertFalse(section.containsKey("shellcheckExternalSources"), "not a boolean");
        assertFalse(section.containsKey("backgroundAnalysisIgnore"), "not all strings");
        assertFalse(section.containsKey("object"), "not JSON");
        Map<?, ?> shfmt = (Map<?, ?>) section.get("shfmt");
        assertEquals(List.of("-i", "2"), shfmt.get("additionalArguments"));
        assertFalse(shfmt.containsKey("future"));

        BashIdeConfig ignored = BashIdeConfig.defaults()
                .withProject(
                        map("shfmt", map("languageDialect", "fish"), "shellcheckArguments", List.of("", "-a b")),
                        false);
        assertEquals("auto", ignored.toSection().get("shfmt") instanceof Map<?, ?> m ? m.get("languageDialect") : null);
        assertEquals(List.of("-a b"), ignored.toSection().get("shellcheckArguments"), "quoted to keep its space");
        assertEquals(BashIdeConfig.defaults(), BashIdeConfig.defaults().withProject(Map.of(), true));
    }

    @Test
    void theTokenizerKeepsEscapesAndQuotes() {
        assertEquals(List.of("a\"b", "c d", "e\\"), BashIdeConfig.tokenize("\"a\\\"b\" c\\ d e\\"));
        assertEquals(List.of("x\\"), BashIdeConfig.tokenize("\"x\\"), "a lone escape at the end of a quote");
        assertEquals(List.of("it's"), BashIdeConfig.tokenize("'it'\\''s'"));
        assertEquals(List.of(""), BashIdeConfig.tokenize("''"), "an empty quoted word");
        assertEquals(List.of(), BashIdeConfig.tokenize(null));
    }

    @Test
    void anEndpointIsSentOnlyOverHttpsOrToThisMachine() {
        assertTrue(BashIdeConfig.explainshellAllowed(null));
        assertTrue(BashIdeConfig.explainshellAllowed(" "));
        assertTrue(BashIdeConfig.explainshellAllowed("http://app.localhost:5000"));
        assertTrue(BashIdeConfig.explainshellAllowed("http://127.0.0.1:5000"));
        assertFalse(BashIdeConfig.explainshellAllowed("http://example.com"));
        assertFalse(BashIdeConfig.explainshellAllowed("ftp://localhost"));
        assertFalse(BashIdeConfig.explainshellAllowed("not a uri"));
        assertFalse(BashIdeConfig.explainshellAllowed("mailto:x"), "no host");
    }

    @Test
    void aRootUsesTheProjectsConfigOnlyInsideOrAboveTheProject() {
        BashIdeConfig user = BashIdeConfig.defaults();
        BashIdeConfig project = user.withProject(map("includeAllWorkspaceSymbols", true), false);
        var perRoot = BashIdeConfig.perRoot(project, user, Path.of("/p"));
        assertSame(project, perRoot.apply(Path.of("/p/sub")));
        assertSame(project, perRoot.apply(Path.of("/")));
        assertSame(user, perRoot.apply(Path.of("/q")));
        assertSame(user, BashIdeConfig.perRoot(project, user, null).apply(Path.of("/p")));
        assertSame(user, BashIdeConfig.perRoot(user, user, Path.of("/p")).apply(Path.of("/p")));
        assertEquals("shfmt", user.toolCommand(BashIdeConfig.SHFMT));
        assertEquals("shellcheck", user.toolCommand(BashIdeConfig.SHELLCHECK));
    }
}
