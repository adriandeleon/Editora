package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.*;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in live JDT LS regression/latency probe, with a disposable Maven project and production capabilities. */
@Tag("probe")
class JdtlsTypingProbeTest {
    @TempDir
    Path temporary;

    private Path root;

    @Test
    void realisticTypingScenarios() throws Exception {
        String command = System.getProperty("lsp.java.probe.command");
        assumeTrue(command != null, "set -Dlsp.java.probe.command=/path/to/jdtls");
        root = Files.createDirectories(temporary.resolve("project"));
        Path source = Files.createDirectories(root.resolve("src/main/java/demo"));
        Files.writeString(root.resolve("pom.xml"), """
            <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>typing</artifactId>
            <version>1</version><properties><maven.compiler.release>25</maven.compiler.release></properties></project>
            """);
        Path file = source.resolve("Probe.java");
        Files.writeString(file, "package demo; class Probe {}\n");
        var ready = new CountDownLatch(1);
        var spec = new LspServerRegistry.ServerSpec(
                "java", List.of(command, "-data", temporary.resolve("workspace").toString()), List.of("pom.xml"));
        var session = new LanguageServerSession(
                spec,
                root,
                d -> {},
                (type, message) -> {
                    if ("ServiceReady".equals(type)) ready.countDown();
                },
                LspManager.javaInitOptions(List.of()));
        String uri = file.toUri().toString();
        try {
            assertTrue(session.start());
            assertTrue(ready.await(60, TimeUnit.SECONDS));
            session.didOpen(uri, "java", Files.readString(file));
            // Completion itself is the readiness signal; no fixed 45-second sleep or private fixture path.
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            List<CompletionItem> warm = List.of();
            do {
                warm = complete(session, uri, "void run() { System.| }", null);
                if (warm.stream().anyMatch(i -> i.getLabel().startsWith("out"))) break;
                new CountDownLatch(1).await(100, TimeUnit.MILLISECONDS);
            } while (System.nanoTime() < deadline);
            assertTrue(
                    warm.stream().anyMatch(i -> i.getLabel().startsWith("out")),
                    "project must resolve java.lang.System");
            var measurements = new ArrayList<String>();
            String[][] cases = {
                {"void run() { Str| }", "String"},
                {"void run() { System.| }", "out"},
                {"void run() { System.out.| }", "println"},
                {"void run() { System.out.pr| }", "println"},
                {"void run() { String value = \"\"; int variant = 1; String result = va|; }", "value"},
                {"String run() { String value = \"\"; return va|; }", "value"},
                {"void run() { boolean flag = true; if (fl|) }", "flag"},
                {"void run() { new Arr| }", "ArrayList"},
                {"@Override public String toS|", "toString"},
                {"void run() { String obj = \"\"; return obj.| }", "length"},
                {"void run() { java.util.List<Str| }", "String"},
                {"void run() { java.util.function.Function<String,Integer> f = String::len|; }", "length"}
            };
            for (String[] c : cases) {
                var items = complete(session, uri, c[0], measurements);
                System.out.println(measurements.getLast());
                assertTrue(items.stream().anyMatch(i -> i.getLabel().startsWith(c[1])), c[0] + " should offer " + c[1]);
            }
            var classes = complete(session, uri, "void run() { new ArrayLi| }", measurements);
            var array = classes.stream()
                    .filter(i -> i.getLabel().startsWith("ArrayList"))
                    .findFirst()
                    .orElseThrow();
            var resolved = session.resolveCompletion(array).get(30, TimeUnit.SECONDS);
            assertTrue(
                    resolved.getAdditionalTextEdits() != null
                            && resolved.getAdditionalTextEdits().stream()
                                    .anyMatch(e -> e.getNewText().contains("import java.util.ArrayList;")),
                    "auto-import available on resolve");
            complete(session, uri, "void run() { System.out.println(| }", measurements);
            String text = "package demo;\nclass Probe { void run() { System.out.println( }\n}";
            session.didChange(uri, text);
            var help = session.signatureHelp(
                            uri, new Position(1, "class Probe { void run() { System.out.println(".length()), "(", false)
                    .get(30, TimeUnit.SECONDS);
            assertNotNull(help);
            assertFalse(help.getSignatures().isEmpty());
            for (String imports : List.of("import java.util.ArrayList;\n", "import java.util.*;\n")) {
                String marked = "package demo;\n" + imports + "class Probe { void run() { new ArrayLi| } }";
                var proposals = completeDocument(session, uri, marked, measurements);
                var proposal = proposals.stream()
                        .filter(i -> i.getLabel().startsWith("ArrayList"))
                        .findFirst()
                        .orElseThrow();
                var known = session.resolveCompletion(proposal).get(30, TimeUnit.SECONDS);
                // A server may rewrite an existing import block, including its original import.
                // Validate the applied text, not whether a replacement happens to mention ArrayList.
                String after = applyEdits(marked.replace("|", ""), known.getAdditionalTextEdits());
                assertTrue(
                        after.split("import java\\.util\\.ArrayList;", -1).length <= 2,
                        "existing import must not be duplicated: " + known.getAdditionalTextEdits());
                assertTrue(
                        after.contains("import java.util.ArrayList;") || after.contains("import java.util.*;"),
                        "imports=" + imports + " edits=" + known.getAdditionalTextEdits() + " after=" + after);
            }
            var ambiguous = completeDocument(
                    session,
                    uri,
                    "package demo;\nclass ArrayList {}\nclass Probe { void run() { new ArrayLi| } }",
                    measurements);
            var external = ambiguous.stream()
                    .filter(i -> i.getDetail() != null && i.getDetail().contains("java.util.ArrayList"))
                    .findFirst();
            if (external.isPresent()) {
                var conflict = session.resolveCompletion(external.get()).get(30, TimeUnit.SECONDS);
                boolean conflictingImport = conflict.getAdditionalTextEdits() != null
                        && conflict.getAdditionalTextEdits().stream()
                                .anyMatch(e -> e.getNewText().contains("import java.util.ArrayList;"));
                // This is a server conformance observation, not an Editora transformation. JDT LS
                // 1.61 can return a conflicting import in this broken source. Keep it visible in
                // probe output; strict mode is useful when checking a prospective server upgrade.
                System.out.println("JAVA_PROBE serverGap.sameFileTypeImportConflict=" + conflictingImport);
                if (conflictingImport)
                    System.out.println("JAVA_PROBE conflictingEdits=" + conflict.getAdditionalTextEdits());
                if (Boolean.getBoolean("lsp.java.probe.strict"))
                    assertFalse(conflictingImport, "must not import over a same-package class");
            }
            measurements.forEach(System.out::println);
            session.didClose(uri);
        } finally {
            var processes = ProcessHandle.current()
                    .descendants()
                    .filter(process -> java.util.Arrays.stream(
                                    process.info().arguments().orElse(new String[0]))
                            .anyMatch(argument -> argument.startsWith(temporary.toString())))
                    .toList();
            session.dispose();
            // Disposal is intentionally non-blocking in production; let this test's workspace owner exit
            // before JUnit deletes the temporary Eclipse workspace it may still be writing.
            for (var process : processes) process.onExit().get(10, TimeUnit.SECONDS);
        }
    }

    private List<CompletionItem> complete(LanguageServerSession session, String uri, String body, List<String> rows)
            throws Exception {
        return completeDocument(session, uri, "package demo;\nclass Probe { " + body + "\n}", rows);
    }

    private static String applyEdits(String text, List<TextEdit> edits) {
        if (edits == null) return text;
        var ordered = new ArrayList<>(edits);
        ordered.sort(java.util.Comparator.comparingInt(
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

    private static int offset(String text, Position position) {
        int start = 0;
        for (int line = 0; line < position.getLine(); line++) {
            start = text.indexOf('\n', start) + 1;
            assertTrue(start > 0, "edit line exists");
        }
        return start + position.getCharacter();
    }

    private List<CompletionItem> completeDocument(
            LanguageServerSession session, String uri, String marked, List<String> rows) throws Exception {
        String body = marked.replace('\n', ' ');
        int offset = marked.indexOf('|');
        String text = marked.replace("|", "");
        int line =
                (int) text.substring(0, offset).chars().filter(c -> c == '\n').count();
        int col = offset - text.lastIndexOf('\n', offset - 1) - 1;
        long start = System.nanoTime();
        session.didChange(uri, text);
        long sent = System.nanoTime();
        var response = session.completion(uri, new Position(line, col)).get(30, TimeUnit.SECONDS);
        long arrived = System.nanoTime();
        var raw = CompletionMapper.itemsOf(response);
        var mapped = com.editora.completion.CompletionEngine.sortLspByRelevance(CompletionMapper.map(raw));
        long processed = System.nanoTime();
        if (rows != null)
            rows.add(String.format(
                    java.util.Locale.ROOT,
                    "JAVA_PROBE %s | sync=%.3fms request=%.3fms map+sort=%.3fms items=%d incomplete=%s top=%s",
                    body,
                    (sent - start) / 1e6,
                    (arrived - sent) / 1e6,
                    (processed - arrived) / 1e6,
                    raw.size(),
                    response.isRight() && response.getRight().isIncomplete(),
                    mapped.stream().limit(3).map(i -> i.label()).toList()));
        return raw;
    }
}
