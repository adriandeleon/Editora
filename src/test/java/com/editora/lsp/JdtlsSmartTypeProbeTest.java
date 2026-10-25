package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.completion.Completion;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smart Type completion over a real jdtls's proposals: the expected type read from the code
 * ({@link JavaExpectedType}) or from jdtls's signature help, and {@link SmartTypeFilter} keeping what fits —
 * including a project class that implements the expected interface, which only jdtls's relevance boost tells.
 * Opt-in (run with {@code -Dgroups=probe -Dlsp.probe=true -Dlsp.probe.jdtls=PATH}).
 */
@Tag("probe")
class JdtlsSmartTypeProbeTest {

    private static final String SMART = """
            package demo;

            import java.util.ArrayList;
            import java.util.List;

            class Smart {
                interface Shape {}

                static class Circle implements Shape {}

                static class Label {}

                String name = "n";
                int count = 1;
                List<String> names = new ArrayList<>();
                StringBuilder sb = new StringBuilder();
                CharSequence chars = "c";
                Circle circle = new Circle();
                Label caption = new Label();

                void take(int n) {}

                String go(String param, int idx) {
                    String s = n
                    take(c);
                    Shape shape = c
                    String t = name.t
                    String f = String.format("%d", c
                    List<String> l = List.of(n
                    return s;
                }
            }
            """;

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(60, TimeUnit.SECONDS);
    }

    private static Position position(String marker) {
        int at = SMART.indexOf(marker) + marker.length();
        int line = (int) SMART.substring(0, at).chars().filter(c -> c == '\n').count();
        return new Position(line, at - SMART.lastIndexOf('\n', at - 1) - 1);
    }

    private static List<String> kept(LanguageServerSession s, String uri, String marker, String type) throws Exception {
        return kept(s, uri, marker, List.of(type));
    }

    private static List<String> kept(LanguageServerSession s, String uri, String marker, List<String> type)
            throws Exception {
        List<CompletionItem> items = List.of();
        for (int attempt = 0;
                attempt < 20 && items.isEmpty();
                attempt++) { // the index is still filling just after import
            items = CompletionMapper.itemsOf(get(s.completion(uri, position(marker))));
            if (items.isEmpty()) {
                Thread.sleep(500);
            }
        }
        List<Completion> mapped = CompletionMapper.map(items);
        List<String> labels = SmartTypeFilter.filter(mapped, type, false).stream()
                .map(Completion::label)
                .toList();
        System.out.println("PROBE " + marker + " as " + type + ": " + labels + " of " + mapped.size());
        return labels;
    }

    @Test
    void onlyWhatFitsTheExpectedTypeStaysOfARealJdtlsList() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String jdtls = System.getProperty("lsp.probe.jdtls", "");
        Assumptions.assumeFalse(jdtls.isBlank(), "needs -Dlsp.probe.jdtls");
        Path root = Files.createTempDirectory("editora-jdtls-smart-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Smart.java"), SMART);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>smart-probe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties></project>
                """);
        var imported = new java.util.concurrent.CountDownLatch(1);
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls, "-data", root.resolve("data").toString()), List.of("pom.xml")),
                project,
                d -> {},
                (type, message) -> {
                    if ("ProjectStatus".equals(type)) {
                        imported.countDown();
                    }
                },
                LspManager.withProjectSettings("java", LspManager.javaInitOptions(List.of()), project));
        try {
            assertTrue(s.start());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!s.isInitialized() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(imported.await(120, TimeUnit.SECONDS), "the project imported");
            String uri = src.resolve("Smart.java").toUri().toString();
            s.didOpen(uri, "java", SMART);

            // A declaration: the type is in the code.
            int at = SMART.indexOf("String s = n") + "String s = n".length();
            assertEquals("String", JavaExpectedType.at(SMART, at).type());
            List<String> declared = kept(s, uri, "String s = n", "String");
            assertTrue(declared.contains("name"), declared.toString());
            assertFalse(declared.contains("names"), declared.toString());

            // An argument: jdtls's signature help names the parameter.
            assertEquals(
                    JavaExpectedType.Source.ARGUMENT,
                    JavaExpectedType.at(SMART, SMART.indexOf("take(c") + 5).source());
            var help = SignatureFormat.resolve(get(s.signatureHelp(uri, position("take(c"), null, false, null)));
            assertNotNull(help, "signature help inside take(");
            String parameter =
                    JavaExpectedType.parameterType(help.label().substring(help.paramStart(), help.paramEnd()));
            assertEquals("int", parameter);
            List<String> argument = kept(s, uri, "take(c", parameter);
            assertTrue(argument.contains("count"), argument.toString());
            assertFalse(argument.contains("chars"), argument.toString());

            // A project interface: Circle implements it, Label does not; only jdtls's boost tells them apart.
            List<String> shapes = kept(s, uri, "Shape shape = c", "Shape");
            assertTrue(shapes.contains("circle"), shapes.toString());
            assertFalse(shapes.contains("caption"), shapes.toString());
            assertFalse(shapes.contains("chars"), shapes.toString());

            // After a dot: the members whose type fits what the declaration expects (re-review AN3).
            assertEquals(
                    "String",
                    JavaExpectedType.at(SMART, SMART.indexOf("name.t") + 6).type());
            List<String> members = kept(s, uri, "String t = name.t", "String");
            assertTrue(members.stream().anyMatch(l -> l.startsWith("trim")), members.toString());
            assertFalse(members.stream().anyMatch(l -> l.startsWith("toCharArray")), members.toString());

            // A varargs argument: an element, or the array (re-review AN2).
            var fmt =
                    SignatureFormat.resolve(get(s.signatureHelp(uri, position("format(\"%d\", c"), null, false, null)));
            assertNotNull(fmt, "signature help inside format(");
            List<String> varargs =
                    JavaExpectedType.parameterTypes(fmt.label().substring(fmt.paramStart(), fmt.paramEnd()));
            assertEquals(List.of("Object", "Object[]"), varargs);
            List<String> elements = kept(s, uri, "format(\"%d\", c", varargs);
            assertTrue(elements.contains("count"), "an int is an Object: " + elements);

            // A generic parameter: List.of's E is bound to the declaration's element type (third review AT1).
            var of = SignatureFormat.resolve(get(s.signatureHelp(uri, position("List.of(n"), null, false, null)));
            assertNotNull(of, "signature help inside List.of(");
            List<String> raw = JavaExpectedType.parameterTypes(of.label().substring(of.paramStart(), of.paramEnd()));
            String line = "                    List<String> l = List.of(n";
            List<String> bound =
                    JavaExpectedType.bindTypeVariables(raw, JavaExpectedType.declaredTarget(line, line.length()));
            assertEquals("String", bound.get(0), raw + " → " + bound);
            List<String> generic = kept(s, uri, "List.of(n", bound);
            assertTrue(generic.contains("name"), "not only null: " + generic);

            // return: the enclosing method's type.
            assertEquals(
                    "String",
                    JavaExpectedType.at(SMART, SMART.indexOf("return s") + 8).type());
            List<String> returned = kept(s, uri, "return s", "String");
            assertTrue(returned.contains("s"), returned.toString());
            assertFalse(returned.contains("sb"), returned.toString());
        } finally {
            s.dispose();
        }
    }
}
