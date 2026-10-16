package com.editora.ui;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * U3: what the environment pill and status bar say, which environments the "Use environment" quick fix offers for an
 * unresolved {@code {{variable}}}, and how a squiggle's reason and a run's warning name the environments that define it.
 */
class HttpEnvChoiceTest {

    private static final Map<String, List<String>> DEFINED = Map.of(
            "token", List.of("dev", "staging"), "host", List.of("dev", "staging", "prod"), "debug", List.of("dev"));

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    @Test
    void theLabelNamesTheEnvironmentOrSaysThereIsNone() {
        assertEquals("Env: dev", HttpEnvChoice.label("dev"));
        assertEquals("No environment", HttpEnvChoice.label(""));
        assertEquals("No environment", HttpEnvChoice.label(null));
    }

    @Test
    void onlyAPlainVariableCanBeDefinedByAnEnvironment() {
        assertEquals("token", HttpEnvChoice.variableOf("{{token}}"));
        assertEquals("token", HttpEnvChoice.variableOf("{{ token }}"));
        assertNull(HttpEnvChoice.variableOf("{{$uuid}}"), "a built-in");
        assertNull(HttpEnvChoice.variableOf("{{login.response.body.$.token}}"), "a response reference");
        assertNull(HttpEnvChoice.variableOf("{{}}"));
        assertNull(HttpEnvChoice.variableOf("token"));
        assertNull(HttpEnvChoice.variableOf(null));
    }

    @Test
    void theFixOffersEveryEnvironmentDefiningTheVariableButTheSelectedOne() {
        assertEquals(List.of("dev", "staging"), HttpEnvChoice.fixesFor("{{token}}", "", DEFINED), "none selected");
        assertEquals(List.of("staging"), HttpEnvChoice.fixesFor("{{token}}", "dev", DEFINED), "the other one");
        assertEquals(List.of("dev", "staging"), HttpEnvChoice.fixesFor("{{token}}", "prod", DEFINED), "prod lacks it");
        assertEquals(List.of(), HttpEnvChoice.fixesFor("{{nope}}", "", DEFINED), "no environment defines it");
        assertEquals(List.of(), HttpEnvChoice.fixesFor("{{debug}}", "dev", DEFINED), "only the selected one does");
        assertEquals(List.of(), HttpEnvChoice.fixesFor("{{$uuid}}", "", DEFINED));
        assertEquals(List.of(), HttpEnvChoice.fixesFor("{{token}}", "", null));
    }

    @Test
    void aPlainNotDefinedReasonNamesTheEnvironmentsThatDefineItWhenAnotherIsSelected() {
        String undefined = tr("http.unresolved.reason.undefined");
        assertEquals(
                "not defined in the environment prod (defined in dev, staging)",
                HttpEnvChoice.reason("{{token}}", undefined, "prod", DEFINED));
        assertEquals(undefined, HttpEnvChoice.reason("{{nope}}", undefined, "prod", DEFINED), "nobody defines it");
        assertEquals(undefined, HttpEnvChoice.reason("{{token}}", undefined, "", DEFINED), "the analysis says it");
        String other = "the request \"login\" has not run yet";
        assertEquals(other, HttpEnvChoice.reason("{{token}}", other, "prod", DEFINED), "another reason is kept");
    }

    @Test
    void aVariableSentEmptyIsExplainedWithTheEnvironmentsThatDefineIt() {
        String undefined = tr("http.unresolved.reason.undefined");
        String token = tr("http.warn.unresolved", "{{token}}", undefined);
        String nope = tr("http.warn.unresolved", "{{nope}}", undefined);
        List<String> warnings = List.of("redirected", token, nope);

        assertEquals(
                List.of(
                        "redirected",
                        "{{token}} was sent as empty text: no environment is selected (defined in dev, staging).",
                        nope),
                HttpEnvChoice.explainWarnings(warnings, "", DEFINED));
        assertEquals(
                List.of(
                        "redirected",
                        "{{token}} was sent as empty text: not defined in the environment prod (defined in dev, "
                                + "staging).",
                        nope),
                HttpEnvChoice.explainWarnings(warnings, "prod", DEFINED));
        List<String> untouched = List.of("redirected", nope);
        assertSame(untouched, HttpEnvChoice.explainWarnings(untouched, "", DEFINED), "nothing to explain");
        assertSame(warnings, HttpEnvChoice.explainWarnings(warnings, "", Map.of()), "no environments");
    }

    @Test
    void theMenuSaysWhyThereIsNothingToChoose() {
        Path dir = Path.of("/w");
        assertEquals(
                "No http-client.env.json beside this file",
                HttpEnvChoice.emptyHint(new HttpEnvironments.EnvFiles(dir, null, null)));
        assertEquals(
                "http-client.env.json defines no environments",
                HttpEnvChoice.emptyHint(new HttpEnvironments.EnvFiles(dir, "{}", null)));
        assertEquals(
                "http-client.private.env.json defines no environments",
                HttpEnvChoice.emptyHint(new HttpEnvironments.EnvFiles(dir, null, "{}")));
        assertEquals("", HttpEnvChoice.sources(new HttpEnvironments.EnvFiles(dir, null, null)));
        assertEquals(
                "http-client.env.json, http-client.private.env.json",
                HttpEnvChoice.sources(new HttpEnvironments.EnvFiles(dir, "{}", "{}")));
    }

    @Test
    void definedInListsTheEnvironmentsInFileOrder() {
        HttpEnvironments.EnvFiles files = new HttpEnvironments.EnvFiles(
                Path.of("/w"),
                "{\"dev\":{\"host\":\"a\",\"token\":\"t\"},\"prod\":{\"host\":\"b\"}}",
                "{\"local\":{\"token\":\"x\"}}");
        Map<String, List<String>> defined = HttpEnvChoice.definedIn(files);
        assertEquals(List.of("dev", "prod"), defined.get("host"));
        assertEquals(List.of("dev", "local"), defined.get("token"));
    }
}
