package com.editora.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import com.editora.i18n.Messages;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What the editor marks as an unresolvable {@code {{variable}}}, and what {@code {{} completion offers. */
class HttpVariableIndexTest {

    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 10, 15, 12, 0, 0, 0, ZoneOffset.UTC);

    @BeforeAll
    static void english() {
        Messages.init("en");
    }

    private static List<HttpVariableIndex.Problem> problems(String text, Map<String, String> env, Path dir) {
        return HttpVariableIndex.unresolved(text, new HttpVariableIndex.Context(env, dir), NOW);
    }

    private static List<String> refs(List<HttpVariableIndex.Problem> ps) {
        return ps.stream().map(HttpVariableIndex.Problem::reference).toList();
    }

    @Test
    void anUndefinedNameIsMarkedWhereItIsWritten() {
        List<HttpVariableIndex.Problem> ps =
                problems("GET {{host}}/a?x={{tokn}}\nAuthorization: Bearer {{token}}\n", Map.of("host", "h"), null);
        assertEquals(List.of("{{tokn}}", "{{token}}"), refs(ps));
        HttpVariableIndex.Problem first = ps.get(0);
        assertEquals(0, first.line());
        assertEquals("GET {{host}}/a?x=".length(), first.column());
        assertEquals("{{tokn}}".length(), first.length());
        assertTrue(first.reason().contains("not defined"), first.reason());
        assertEquals(1, ps.get(1).line());
    }

    @Test
    void declarationsCountFromWhereTheyAreWritten() {
        String text = "@a = 1\n### one\nGET http://h/{{a}}/{{b}}\n\n### two\n@b = 2\nGET http://h/{{a}}/{{b}}\n";
        List<HttpVariableIndex.Problem> ps = problems(text, Map.of(), null);
        assertEquals(1, ps.size(), ps.toString());
        assertEquals(2, ps.get(0).line(), "{{b}} is declared only above the second request");
    }

    @Test
    void aDeclarationsOwnReferencesAreChecked() {
        List<HttpVariableIndex.Problem> ps =
                problems("@auth = Bearer {{token}}\nGET http://h\nX: {{auth}}\n", Map.of(), null);
        assertEquals(List.of("{{token}}", "{{auth}}"), refs(ps));
        assertEquals(0, ps.get(0).line());
        assertTrue(ps.get(1).reason().contains("uses {{token}}"), ps.get(1).reason());
    }

    @Test
    void environmentValuesAndTheirReferences() {
        Map<String, String> env = Map.of("base", "http://h", "api", "{{base}}/v1", "bad", "{{nope}}");
        List<HttpVariableIndex.Problem> ps = problems("GET {{api}}/x\nX: {{bad}}\n", env, null);
        assertEquals(List.of("{{bad}}"), refs(ps));
    }

    @Test
    void dynamicVariables(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve(".env"), "TOKEN=x\n");
        String text = "GET http://h?a={{$uuid}}&b={{$random.name.firstName}}&c={{$dotenv TOKEN}}&d={{$dotenv NOPE}}"
                + "&e={{$processEnv EDITORA_SURELY_NOT_SET_42}}&f={{$datetime \"yyyy\"}}\n";
        List<HttpVariableIndex.Problem> ps = problems(text, Map.of(), dir);
        assertEquals(
                List.of("{{$random.name.firstName}}", "{{$dotenv NOPE}}", "{{$processEnv EDITORA_SURELY_NOT_SET_42}}"),
                refs(ps));
    }

    @Test
    void aResponseOfARequestInTheFileIsNotMarkedButAnUnknownRequestIs() {
        String text = "### Login\n# @name login\nPOST http://h/login\n\n"
                + "### Me\n@t = {{login.response.body.$.token}}\nGET http://h/me\nA: {{login.response.body.$.token}}\n"
                + "B: {{t}}\nC: {{logout.response.status}}\n";
        List<HttpVariableIndex.Problem> ps = problems(text, Map.of(), null);
        assertEquals(List.of("{{logout.response.status}}"), refs(ps));
        assertTrue(ps.get(0).reason().contains("\"logout\""), ps.get(0).reason());
    }

    @Test
    void commentsAndScriptsAreNotLookedAt() {
        String text = "# {{commented}}\nGET http://h\n> {%\n  client.global.set(\"{{x}}\", 1);\n%}\n// {{also}}\n";
        assertTrue(problems(text, Map.of(), null).isEmpty());
        assertTrue(problems("< {% request.variables.set(\"{{y}}\") %}\nGET http://h\n", Map.of(), null)
                .isEmpty());
    }

    @Test
    void namesAndRequestsForCompletion() {
        String text = "@a = 1\n### one\n# @name first\nGET http://h\n\n### two\n@b = 2\nGET http://h/{{\n";
        HttpVariableIndex.Context ctx = new HttpVariableIndex.Context(Map.of("env1", "x"), null);
        assertEquals(List.of("a", "env1"), HttpVariableIndex.variablesAt(text, 3, ctx));
        assertEquals(List.of("a", "b", "env1"), HttpVariableIndex.variablesAt(text, 7, ctx));
        assertEquals(List.of("first", "two"), HttpVariableIndex.requestNames(text));
        assertTrue(HttpVariableIndex.dynamicVariables().contains("$uuid"));
    }

    @Test
    void aBigFileStopsAtTheLimit() {
        String text = "GET http://h\nX: " + "{{x}}".repeat(HttpVariableIndex.MAX_PROBLEMS + 50) + "\n";
        assertEquals(
                HttpVariableIndex.MAX_PROBLEMS, problems(text, Map.of(), null).size());
    }
}
