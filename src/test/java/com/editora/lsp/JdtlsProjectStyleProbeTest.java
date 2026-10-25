package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import com.editora.editorconfig.EditorConfig;
import org.eclipse.lsp4j.CodeActionContext;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project's own style reaching a real jdtls ({@link JavaProjectSettings}) and chain completion: an IntelliJ
 * {@code .editorconfig} star threshold makes Organize Imports write {@code java.util.*}, the Eclipse profile named
 * by Spotless puts a method's brace on its own line, and {@code collect(} offers {@code Collectors.toList()}.
 * Opt-in (run with {@code -Dgroups=probe -Dlsp.probe=true}; {@code -Dlsp.probe.jdtls=PATH} picks the server).
 */
@Tag("probe")
class JdtlsProjectStyleProbeTest {

    private static final String APP = """
            package demo;

            import java.util.ArrayList;
            import java.util.HashMap;
            import java.util.List;
            import java.util.Map;
            import java.util.stream.Stream;

            public class App {
                List<String> a = new ArrayList<>();
                Map<String, String> m = new HashMap<>();

                long go() {
                    return Stream.of("1").count();
                }
            }
            """;

    /** A method that overrides without saying so: the addOverride clean-up adds the annotation. */
    private static final String SHAPE = """
            package demo;

            class Shape {
                public String toString() {
                    return "shape";
                }
            }
            """;

    /** Chain completion needs the call open, which leaves the file unparsed: a file of its own. */
    private static final String CHAIN = """
            package demo;

            import java.util.stream.Stream;

            class Chain {
                void go() {
                    Stream.of("1").collect()
                }
            }
            """;

    private static final String PROFILE = """
            <?xml version="1.0" encoding="UTF-8" standalone="no"?>
            <profiles version="23">
            <profile kind="CodeFormatterProfile" name="Probe" version="23">
            <setting id="org.eclipse.jdt.core.formatter.brace_position_for_method_declaration" value="next_line"/>
            </profile>
            </profiles>
            """;

    private static String jdtls() {
        String chosen = System.getProperty("lsp.probe.jdtls", "");
        if (!chosen.isBlank()) {
            return chosen;
        }
        String home = System.getProperty("user.home");
        for (String candidate : List.of(
                home + "/.editora/plugins/lsp/java/bin/jdtls", home + "/.editora-dev/plugins/lsp/java/bin/jdtls")) {
            if (Files.isExecutable(Path.of(candidate))) {
                return candidate;
            }
        }
        return "";
    }

    private static <T> T get(CompletableFuture<T> f) throws Exception {
        return f.get(60, TimeUnit.SECONDS);
    }

    /** {@code text} with LSP {@code edits} applied, the last first. */
    private static String applied(String text, List<? extends TextEdit> edits) {
        List<String> lines = List.of(text.split("\n", -1));
        java.util.function.ToIntFunction<Position> offset = p -> {
            int o = 0;
            for (int i = 0; i < p.getLine(); i++) {
                o += lines.get(i).length() + 1;
            }
            return o + p.getCharacter();
        };
        StringBuilder out = new StringBuilder(text);
        edits.stream()
                .sorted((a, b) -> Integer.compare(
                        offset.applyAsInt(b.getRange().getStart()),
                        offset.applyAsInt(a.getRange().getStart())))
                .forEach(e -> out.replace(
                        offset.applyAsInt(e.getRange().getStart()),
                        offset.applyAsInt(e.getRange().getEnd()),
                        e.getNewText()));
        return out.toString();
    }

    @Test
    void theProjectsImportLayoutFormatterAndChainCompletionReachJdtls() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        EditorConfig.clearCache();
        Path root = Files.createTempDirectory("editora-jdtls-style-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("App.java"), APP);
        Files.writeString(src.resolve("Chain.java"), CHAIN);
        Files.writeString(src.resolve("Shape.java"), SHAPE);
        Files.writeString(
                project.resolve(".editorconfig"),
                "root = true\n[*.java]\nij_java_class_count_to_use_import_on_demand = 3\n");
        Files.writeString(project.resolve("style.xml"), PROFILE);
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>style-probe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <build><plugins><plugin><groupId>com.diffplug.spotless</groupId><artifactId>spotless-maven-plugin</artifactId>
                <configuration><java><eclipse><file>${project.basedir}/style.xml</file></eclipse></java></configuration>
                </plugin></plugins></build></project>
                """);
        Map<String, Object> project0 = JavaProjectSettings.forRoot(project);
        System.out.println("PROBE style settings = " + project0);
        var imported = new java.util.concurrent.CountDownLatch(1);
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java", List.of(jdtls(), "-data", root.resolve("data").toString()), List.of("pom.xml")),
                project,
                d -> {},
                (type, message) -> {
                    if ("ProjectStatus".equals(type)) {
                        imported.countDown();
                    }
                },
                LspManager.withProjectSettings("java", LspManager.javaInitOptions(List.of()), project));
        s.setJavaCleanups(List.of("addOverride"));
        try {
            assertTrue(s.start());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!s.isInitialized() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(s.isInitialized(), "real handshake");
            assertTrue(imported.await(120, TimeUnit.SECONDS), "the project imported");
            String app = src.resolve("App.java").toUri().toString();
            s.didOpen(app, "java", APP);
            String chain = src.resolve("Chain.java").toUri().toString();
            s.didOpen(chain, "java", CHAIN);

            // Chain completion: Collectors.toList() inside collect(.
            int line = (int) CHAIN.substring(0, CHAIN.indexOf("collect("))
                    .chars()
                    .filter(c -> c == '\n')
                    .count();
            int col = CHAIN.lines().toList().get(line).indexOf("collect(") + "collect(".length();
            // Chain search walks jdtls's type index, which is still being built just after the import: ask again.
            List<String> chains = List.of();
            int total = 0;
            for (int attempt = 0; attempt < 20 && chains.isEmpty(); attempt++) {
                var completion = get(s.completion(chain, new Position(line, col)));
                List<CompletionItem> items = completion.isLeft()
                        ? completion.getLeft()
                        : completion.getRight().getItems();
                total = items.size();
                if (attempt == 0) {
                    System.out.println("PROBE labels = "
                            + items.stream().map(CompletionItem::getLabel).toList());
                }
                chains = items.stream()
                        .map(CompletionItem::getLabel)
                        .filter(l -> l.contains("Collectors."))
                        .toList();
                if (chains.isEmpty()) {
                    Thread.sleep(1000);
                }
            }
            System.out.println("PROBE completion items = " + total);
            System.out.println("PROBE chain items = " + chains);
            assertTrue(chains.contains("Collectors.toList"), "chain completion: " + chains);

            // Organize Imports with the .editorconfig threshold of 3: java.util's four types become one import.
            var params = new org.eclipse.lsp4j.CodeActionParams(
                    new org.eclipse.lsp4j.TextDocumentIdentifier(app),
                    new Range(new Position(0, 0), new Position(0, 0)),
                    new CodeActionContext(List.of()));
            String organized = String.valueOf(LspManager.asJson(get(s.rawRequest("java/organizeImports", params))));
            System.out.println("PROBE organize imports = " + organized.replace('\n', ' '));
            assertTrue(organized.contains("import java.util.*;"), "the star threshold reached jdtls");

            // Format Document with the Spotless profile: the method's brace goes to its own line.
            List<? extends TextEdit> edits = get(s.formatting(app, new org.eclipse.lsp4j.FormattingOptions(4, true)));
            String formatted = applied(APP, edits);
            System.out.println("PROBE formatted = " + formatted.replace("\n", "\\n"));
            assertTrue(formatted.contains("long go()\n    {"), "the profile's next-line method brace");
            // The profile edited and saved: the push names a new copy, and jdtls formats the new way with no
            // restart (third review AN5; an unchanged URL was never read again).
            Path style = project.resolve("style.xml");
            Files.writeString(style, Files.readString(style).replace("value=\"next_line\"", "value=\"end_of_line\""));
            s.refreshProjectSettings();
            String reformatted = "";
            int attempts = 0;
            for (int attempt = 0; attempt < 20 && !reformatted.contains("long go() {"); attempt++) {
                attempts++;
                Thread.sleep(300);
                reformatted = applied(APP, get(s.formatting(app, new org.eclipse.lsp4j.FormattingOptions(4, true))));
            }
            System.out.println("PROBE reformatted = " + reformatted.replace("\n", "\\n"));
            System.out.println("PROBE reformat attempts = " + attempts);
            assertTrue(reformatted.contains("long go() {"), "the edited profile's end-of-line brace");
            // Clean-ups (java/cleanup) run the actions pushed in java.cleanup.actions.
            String shape = src.resolve("Shape.java").toUri().toString();
            s.didOpen(shape, "java", SHAPE);
            String cleaned = String.valueOf(LspManager.asJson(
                    get(s.rawRequest("java/cleanup", new org.eclipse.lsp4j.TextDocumentIdentifier(shape)))));
            System.out.println("PROBE cleanup = " + cleaned.replace('\n', ' '));
            assertTrue(cleaned.contains("@Override"), "the addOverride clean-up ran");
        } finally {
            s.dispose();
        }
    }
}
