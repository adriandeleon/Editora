package com.editora.dap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * jdtls answers {@code vscode.java.resolveMainClass} with {@code [{mainClass, projectName, filePath}, …]}.
 *
 * <p>Captured verbatim from a real jdtls 1.60 + java-debug 0.53.2 against the generated Maven quickstart
 * ({@code JdtlsResolveMainClassProbeTest}), <b>including the Java type lsp4j hands back</b>:
 *
 * <pre>
 * type  = java.util.ArrayList
 * value = [{mainClass=com.example.adltest.App, projectName=adltest,
 *           filePath=/home/adl/src/adl/adltest/src/main/java/com/example/adltest/App.java}]
 * </pre>
 *
 * <p>That type is the whole point of this class. The parser used to route every reply through
 * {@code JsonParser.parseString(String.valueOf(res))}, and gson's lenient reader cannot read an unquoted
 * value starting with {@code /} — so an absolute {@code filePath}, i.e. every real project on every
 * platform, threw and was swallowed into an empty list. Debugging a saved run configuration then reported
 * "No main class was found in this project" about a project whose main class jdtls had just named, and the
 * gutter's Debug quietly took its compile-it-ourselves fallback instead of the real launch.
 */
class MainClassParseTest {

    /** Exactly what lsp4j delivered: a List of Maps, not a JsonElement. */
    private static Object lsp4jReply(String mainClass, String projectName, String filePath) {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("mainClass", mainClass);
        m.put("projectName", projectName);
        m.put("filePath", filePath);
        return new java.util.ArrayList<>(List.of(m));
    }

    @Test
    void readsTheRealJdtlsReplyAsAPlainListOfMaps() {
        List<DapManager.MainClassOption> out = FxlessAccess.parseMainClasses(lsp4jReply(
                "com.example.adltest.App",
                "adltest",
                "/home/adl/src/adl/adltest/src/main/java/com/example/adltest/App.java"));

        assertEquals(1, out.size(), "the reply names one main class");
        assertEquals("com.example.adltest.App", out.get(0).mainClass());
        assertEquals("adltest", out.get(0).projectName());
        assertEquals(
                "/home/adl/src/adl/adltest/src/main/java/com/example/adltest/App.java",
                out.get(0).filePath(),
                "the absolute path is the field the toString() round trip used to choke on");
    }

    /** A path with a space or comma does not survive a toString() round trip either — nor a Windows one. */
    @Test
    void awkwardPathsSurvive() {
        List<DapManager.MainClassOption> out =
                FxlessAccess.parseMainClasses(lsp4jReply("demo.App", "My, Project", "/home/My Projects/a,b/App.java"));
        assertEquals("/home/My Projects/a,b/App.java", out.get(0).filePath());
        assertEquals("My, Project", out.get(0).projectName());

        out = FxlessAccess.parseMainClasses(lsp4jReply("demo.App", "demo", "C:\\Users\\me\\src\\App.java"));
        assertEquals("C:\\Users\\me\\src\\App.java", out.get(0).filePath());
    }

    /** The gson shape still parses — a server (or lsp4j version) that hands back a JsonElement is unaffected. */
    @Test
    void readsTheSameReplyAsAGsonElement() {
        Object res = JsonParser.parseString("[{\"mainClass\":\"com.example.adltest.App\",\"projectName\":\"adltest\","
                + "\"filePath\":\"/home/adl/src/adl/adltest/src/main/java/com/example/adltest/App.java\"}]");
        List<DapManager.MainClassOption> out = FxlessAccess.parseMainClasses(res);
        assertEquals(1, out.size());
        assertEquals("com.example.adltest.App", out.get(0).mainClass());
        assertEquals(
                "/home/adl/src/adl/adltest/src/main/java/com/example/adltest/App.java",
                out.get(0).filePath());
    }

    @Test
    void severalMainClassesAreAllReturned() {
        Object res = new java.util.ArrayList<>(List.of(
                Map.of("mainClass", "demo.A", "projectName", "p", "filePath", "/p/A.java"),
                Map.of("mainClass", "demo.B", "projectName", "p", "filePath", "/p/B.java")));
        assertEquals(
                List.of("demo.A", "demo.B"),
                FxlessAccess.parseMainClasses(res).stream()
                        .map(DapManager.MainClassOption::mainClass)
                        .toList());
    }

    /** A missing field is null rather than a crash — the caller matches on mainClass and tolerates the rest. */
    @Test
    void aReplyMissingFieldsDoesNotThrow() {
        Object res = new java.util.ArrayList<>(List.of(Map.of("mainClass", "demo.App")));
        List<DapManager.MainClassOption> out = FxlessAccess.parseMainClasses(res);
        assertEquals("demo.App", out.get(0).mainClass());
        assertEquals(null, out.get(0).filePath());
    }

    @Test
    void junkYieldsEmptyRatherThanThrowing() {
        assertEquals(List.of(), FxlessAccess.parseMainClasses(null));
        assertEquals(List.of(), FxlessAccess.parseMainClasses("not a reply"));
        assertEquals(List.of(), FxlessAccess.parseMainClasses(List.of()));
    }
}
