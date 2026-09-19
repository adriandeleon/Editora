package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in cross-module resolution and rapid structural-edit probe; no installed user project is modified. */
@Tag("probe")
class JavaProjectEditingProbeTest {
    @TempDir
    Path temporary;

    private Path root;

    @Test
    void projectResolutionAndImportFreshness() throws Exception {
        String command = System.getProperty("lsp.java.probe.command");
        assumeTrue(command != null, "set -Dlsp.java.probe.command=/path/to/jdtls");
        root = Files.createDirectories(temporary.resolve("project"));
        String kind = System.getProperty("lsp.java.probe.project", "maven");
        createProject(kind);
        Path file = root.resolve("app/src/main/java/demo/Probe.java");
        String initial = "package demo; class Probe {}";
        Files.writeString(file, initial);
        List<String> argv = new ArrayList<>(List.of(command));
        String join = System.getProperty("lsp.java.probe.join");
        if (join != null) argv.add("--jvm-arg=-Djava.lsp.joinOnCompletion=" + join);
        argv.addAll(List.of("-data", temporary.resolve("workspace").toString()));
        Map<String, Object> options = new HashMap<>(LspManager.javaInitOptions(List.of()));
        if (kind.equals("gradle")) {
            String home = System.getProperty("lsp.java.probe.gradleHome");
            assumeTrue(home != null, "set -Dlsp.java.probe.gradleHome to an installed Gradle distribution");
            @SuppressWarnings("unchecked")
            var settings = (Map<String, Object>) options.get("settings");
            @SuppressWarnings("unchecked")
            var java = new HashMap<>((Map<String, Object>) settings.get("java"));
            java.put(
                    "import",
                    Map.of("gradle", Map.of("home", home, "java", Map.of("home", System.getProperty("java.home")))));
            options.put("settings", Map.of("java", java));
        }
        var session = new LanguageServerSession(
                new LspServerRegistry.ServerSpec("java", argv, List.of()),
                root,
                diagnostics -> {},
                (type, message) -> {},
                options);
        String uri = file.toUri().toString();
        long launched = System.nanoTime();
        try {
            assertTrue(session.start());
            session.didOpen(uri, "java", initial);
            // java.lang alone also works for unimported standalone files. Require our sibling module.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
            boolean resolved = false;
            while (System.nanoTime() < deadline) {
                var items = complete(
                        session, uri, "package demo; import fixture.Api; class Probe { void run() { Api.co| } }");
                if (items.stream().anyMatch(item -> item.getLabel().startsWith("compose"))) {
                    resolved = true;
                    break;
                }
                new java.util.concurrent.CountDownLatch(1).await(150, TimeUnit.MILLISECONDS);
            }
            assertTrue(resolved, "sibling module must resolve, not merely java.lang: " + kind);
            System.out.printf(
                    Locale.ROOT,
                    "PROJECT_PROBE %s ready=%.1fms join=%s%n",
                    kind,
                    (System.nanoTime() - launched) / 1e6,
                    join);
            StringBuilder fields = new StringBuilder();
            for (int i = 0; i < 2000; i++)
                fields.append("  private String field").append(i).append(" = \"value\";\n");
            String large = "package demo; import fixture.Api; class Probe {\n" + fields
                    + "void run() { Api.compose(\"x\").| } }";
            long start = System.nanoTime();
            assertTrue(complete(session, uri, large).stream()
                    .anyMatch(i -> i.getLabel().startsWith("length")));
            System.out.printf(
                    Locale.ROOT,
                    "PROJECT_PROBE largeFile=%d chars chainedRequest=%.1fms%n",
                    large.length(),
                    (System.nanoTime() - start) / 1e6);
            int errors = 0;
            int conflicts = 0;
            List<Double> latencies = new ArrayList<>();
            int rounds = Integer.getInteger("lsp.java.probe.rounds", 10);
            for (int round = 0; round < rounds; round++) {
                for (String imports :
                        List.of("", "import java.util.ArrayList;\n", "import java.util.*;\n", "class ArrayList {}\n")) {
                    String marked = "package demo;\n" + imports + "class Probe { void run() { new ArrayLi| } }";
                    start = System.nanoTime();
                    var items = complete(session, uri, marked);
                    var candidate = items.stream()
                            .filter(i -> i.getLabel().startsWith("ArrayList")
                                    && i.getDetail() != null
                                    && i.getDetail().contains("java.util.ArrayList"))
                            .findFirst();
                    if (candidate.isEmpty() && imports.startsWith("class")) continue;
                    assertTrue(candidate.isPresent(), "ArrayList from java.util must be available");
                    var resolvedItem =
                            session.resolveCompletion(candidate.get()).get(30, TimeUnit.SECONDS);
                    latencies.add((System.nanoTime() - start) / 1e6);
                    String after = apply(marked.replace("|", ""), resolvedItem.getAdditionalTextEdits());
                    boolean invalid = after.split("import java\\.util\\.ArrayList;", -1).length > 2
                            || (!imports.startsWith("class")
                                    && !after.contains("import java.util.ArrayList;")
                                    && !after.contains("import java.util.*;"));
                    if (invalid) {
                        errors++;
                        System.out.println("PROJECT_PROBE importFailure round=" + round + " before=" + marked
                                + " insert=" + resolvedItem.getTextEdit() + " edits="
                                + resolvedItem.getAdditionalTextEdits() + " after=" + after);
                    }
                    if (imports.startsWith("class") && after.contains("import java.util.ArrayList;")) conflicts++;
                }
            }
            Collections.sort(latencies);
            System.out.printf(
                    Locale.ROOT,
                    "PROJECT_PROBE rounds=%d importFailures=%d conflicts=%d completion+resolve median=%.1fms max=%.1fms%n",
                    rounds,
                    errors,
                    conflicts,
                    latencies.get(latencies.size() / 2),
                    latencies.getLast());
            assertEquals(0, errors, "raw resolved edits must preserve imports");
            if (Boolean.getBoolean("lsp.java.probe.strict")) assertEquals(0, conflicts, "same-file type conflicts");
            session.didClose(uri);
        } finally {
            var processes = ProcessHandle.current()
                    .descendants()
                    .filter(p -> Arrays.stream(p.info().arguments().orElse(new String[0]))
                            .anyMatch(arg -> arg.startsWith(temporary.toString())))
                    .toList();
            session.dispose();
            for (var process : processes) process.onExit().get(20, TimeUnit.SECONDS);
        }
    }

    private void createProject(String kind) throws Exception {
        Files.createDirectories(root.resolve("api/src/main/java/fixture"));
        Files.createDirectories(root.resolve("app/src/main/java/demo"));
        Files.writeString(
                root.resolve("api/src/main/java/fixture/Api.java"),
                "package fixture; public class Api { public static String compose(String text) { return text; } }");
        Path types = Files.createDirectories(root.resolve("api/src/main/java/fixture/types"));
        for (int i = 0; i < 300; i++)
            Files.writeString(
                    types.resolve("Candidate" + i + ".java"),
                    "package fixture.types; public class Candidate" + i + " {}");
        if (kind.equals("gradle")) {
            Files.writeString(
                    root.resolve("settings.gradle"), "rootProject.name = 'typing-probe'\ninclude 'api', 'app'\n");
            Files.writeString(
                    root.resolve("build.gradle"),
                    "allprojects { apply plugin: 'java-library'; java { toolchain { languageVersion = JavaLanguageVersion.of(25) } } }\nproject(':app') { dependencies { implementation project(':api') } }\n");
        } else {
            assertEquals("maven", kind);
            String parent =
                    "<parent><groupId>fixture</groupId><artifactId>root</artifactId><version>1</version></parent>";
            Files.writeString(
                    root.resolve("pom.xml"),
                    "<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>root</artifactId><version>1</version><packaging>pom</packaging><modules><module>api</module><module>app</module></modules><properties><maven.compiler.release>25</maven.compiler.release></properties></project>");
            Files.writeString(
                    root.resolve("api/pom.xml"),
                    "<project><modelVersion>4.0.0</modelVersion>" + parent + "<artifactId>api</artifactId></project>");
            Files.writeString(
                    root.resolve("app/pom.xml"),
                    "<project><modelVersion>4.0.0</modelVersion>" + parent
                            + "<artifactId>app</artifactId><dependencies><dependency><groupId>fixture</groupId><artifactId>api</artifactId><version>1</version></dependency></dependencies></project>");
        }
    }

    private List<CompletionItem> complete(LanguageServerSession session, String uri, String marked) throws Exception {
        int at = marked.indexOf('|');
        String text = marked.replace("|", "");
        int line = (int) text.substring(0, at).chars().filter(c -> c == '\n').count();
        int col = at - text.lastIndexOf('\n', at - 1) - 1;
        session.didChange(uri, text);
        return CompletionMapper.itemsOf(
                session.completion(uri, new Position(line, col)).get(30, TimeUnit.SECONDS));
    }

    private static int offset(String text, Position pos) {
        int at = 0;
        for (int i = 0; i < pos.getLine(); i++) {
            at = text.indexOf('\n', at) + 1;
            assertTrue(at > 0);
        }
        return at + pos.getCharacter();
    }

    private static String apply(String text, List<TextEdit> edits) {
        if (edits == null) return text;
        var ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt(
                        (TextEdit e) -> offset(text, e.getRange().getStart()))
                .reversed());
        var result = new StringBuilder(text);
        for (var edit : ordered)
            result.replace(
                    offset(text, edit.getRange().getStart()),
                    offset(text, edit.getRange().getEnd()),
                    edit.getNewText());
        return result.toString();
    }
}
