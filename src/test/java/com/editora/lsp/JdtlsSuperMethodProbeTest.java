package com.editora.lsp;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Opt-in: what a real jdtls answers where Go to Super Method asks (eighth review BQ1, BQ2, BR2): a method's
 * {@code @Override} line, an anonymous class's method, a lambda's arrow, an interface default method, a constructor's
 * {@code super(...)} call, and a method two interfaces declare.
 */
@Tag("probe")
class JdtlsSuperMethodProbeTest {
    private static String jdtls() {
        String given = System.getProperty("lsp.probe.jdtls", "");
        if (!given.isBlank() && Files.isExecutable(Path.of(given))) return given;
        for (String candidate : List.of("/opt/homebrew/bin/jdtls", "/usr/local/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) return candidate;
        }
        return "";
    }

    static final String SOURCE = """
            package demo;
            interface Shape { double area(); default String label() { return "shape"; } }
            interface Named extends Shape { default String label() { return "named"; } }
            interface Walker { void go(); }
            interface Runner { void go(); }
            abstract class Base implements Shape {
                Base(String id) { }
                Base() { this("x"); }
            }
            public class App extends Base implements Named, Walker, Runner {
                App() {
                    super("app");
                }
                @Override
                public double area() { return 1; }
                public void go() { }
                @Override
                public String label() { return "app"; }
                void anon() {
                    Runnable r = new Runnable() {
                        @Override
                        public void run() {
                            System.out.println("anon");
                        }
                    };
                    Runnable l = () -> System.out.println("lambda");
                    java.util.function.Function<String, Integer> f = s -> {
                        return s.length();
                    };
                }
            }
            """;

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(45, TimeUnit.SECONDS);
    }

    static Position pos(String text, String needle, int offset) {
        int at = text.indexOf(needle) + offset;
        String pre = text.substring(0, at);
        return new Position((int) pre.chars().filter(c -> c == '\n').count(), at - pre.lastIndexOf('\n') - 1);
    }

    private static void record(String name, Object value) {
        System.out.println("EVALUATION " + name + " = " + value);
    }

    @Test
    void superMethodSpots() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls (-Dlsp.probe.jdtls=…)");
        Path root = Files.createTempDirectory("editora-super-probe-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path file = project.resolve("src/main/java/demo/App.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, SOURCE);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>superprobe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties></project>
                """);
        String uri = file.toUri().toString();
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls(), "-data", root.resolve("data").toString()), List.of("pom.xml")),
                project,
                d -> {},
                (type, message) -> {},
                LspManager.javaInitOptions(List.of()));
        try {
            assertTrue(s.start());
            s.didOpen(uri, "java", SOURCE);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
            while (!s.isInitialized() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(s.isInitialized(), "real handshake");
            var symbols = get(s.documentSymbol(uri));
            record("documentSymbols", symbols);
            String[][] spots = {
                {"anon run name", "void run()", "5"},
                {"lambda arrow", "() -> System", "3"},
                {"block lambda arrow", "s -> {", "2"},
                {"default label in Named", "default String label() { return \"named\"", "15"},
                {"App.go", "public void go()", "12"},
                {"App.label", "public String label() { return \"app\"", "14"},
                {"App ctor name", "App() {", "0"},
                {"super call", "super(\"app\")", "0"},
                {"this call", "this(\"x\")", "0"},
            };
            var types = get(s.prepareTypeHierarchy(uri, pos(SOURCE, "App extends", 1)));
            record("App typeHierarchy", types);
            if (!types.isEmpty()) {
                var supers = get(s.supertypes(types.get(0)));
                record(
                        "App supertypes",
                        supers.stream()
                                .map(t -> t.getName() + " " + t.getKind() + " " + t.getUri())
                                .toList());
            }
            var anonTypes = get(s.prepareTypeHierarchy(uri, pos(SOURCE, "new Runnable()", 4)));
            record("anon typeHierarchy", anonTypes);
            var runLinks = get(s.findLinks("superImplementation", uri, pos(SOURCE, "void run()", 5)));
            assertFalse(runLinks.getLeft().isEmpty(), "an anonymous class's method: findLinks answers (Runnable.run)");
            String libUri = runLinks.getLeft().get(0).getUri();
            var libraryOutline = get(s.documentSymbol(libUri));
            record("library outline", libraryOutline);
            assertFalse(libraryOutline.isEmpty(), "an unopened class file has an outline: the walk reads supertypes'");
            assertTrue(
                    DocumentSymbolMapper.members(symbols).stream()
                            .flatMap(m -> m.children().stream())
                            .filter(m -> m.name().equals("area()"))
                            .anyMatch(m -> m.startLine() < m.nameLine()),
                    "a method's range starts at its @Override");
            var arrow = get(s.definition(uri, pos(SOURCE, "() -> System", 3)));
            assertTrue(arrow.getLeft().get(0).getUri().contains("Runnable"), "a lambda's arrow: Runnable.run");
            var superCall = get(s.definition(uri, pos(SOURCE, "super(\"app\")", 0)));
            assertFalse(superCall.getLeft().isEmpty(), "super(...): the super constructor");
            for (String[] spot : spots) {
                Position at = pos(SOURCE, spot[1], Integer.parseInt(spot[2]));
                try {
                    record(spot[0] + " findLinks", get(s.findLinks("superImplementation", uri, at)));
                } catch (Exception ex) {
                    record(spot[0] + " findLinks failure", ex);
                }
                try {
                    record(spot[0] + " definition", get(s.definition(uri, at)));
                } catch (Exception ex) {
                    record(spot[0] + " definition failure", ex);
                }
            }
        } finally {
            s.dispose();
        }
    }
}
