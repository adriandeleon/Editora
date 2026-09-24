package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in check of compact-source language behavior against Editora's installed JDT LS. */
@Tag("probe")
class JdtlsCompactSourceProbeTest {

    @TempDir
    Path temporary;

    @Test
    void compactSourceInMavenProject() throws Exception {
        checkCompactSource(true);
    }

    @Test
    void looseCompactSource() throws Exception {
        checkCompactSource(false);
    }

    private void checkCompactSource(boolean maven) throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        assumeTrue(Files.isExecutable(jdtls), "needs the installed JDT LS");
        Path project = Files.createDirectories(temporary.resolve(maven ? "maven" : "loose"));
        if (maven) {
            Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>demo</groupId><artifactId>compact</artifactId>
                <version>1</version><properties><maven.compiler.release>25</maven.compiler.release></properties></project>
                """);
        }
        Path file = project.resolve(maven ? "src/main/java/Hello.java" : "Hello.java");
        Files.createDirectories(file.getParent());
        String valid = "void main() { IO.println(List.of(\"ok\")); Path p = Path.of(\".\"); }\n";
        Files.writeString(file, valid);
        String uri = file.toUri().toString();
        var reports = new CopyOnWriteArrayList<PublishDiagnosticsParams>();
        var ready = new CountDownLatch(1);
        var spec = new LspServerRegistry.ServerSpec(
                "java",
                List.of(
                        jdtls.toString(),
                        "-data",
                        temporary.resolve("workspace").toString()),
                List.of("pom.xml"));
        var session = new LanguageServerSession(
                spec,
                project,
                reports::add,
                (type, message) -> {
                    if ("ServiceReady".equals(type)) ready.countDown();
                },
                LspManager.javaInitOptions(List.of()));
        try {
            assertTrue(session.start());
            assertTrue(ready.await(70, TimeUnit.SECONDS), "JDT LS should initialize");
            session.didOpen(uri, "java", valid);
            // Completion is the readiness signal for the imported project.
            String completing = "void main() { Lis }\n";
            session.didChange(uri, completing);
            boolean offeredList = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (!offeredList && System.nanoTime() < deadline) {
                var raw = session.completion(uri, new Position(0, "void main() { Lis".length()))
                        .get(30, TimeUnit.SECONDS);
                offeredList = CompletionMapper.itemsOf(raw).stream()
                        .map(CompletionItem::getLabel)
                        .anyMatch(label -> label.startsWith("List"));
                if (!offeredList) Thread.sleep(150);
            }
            assertTrue(offeredList, "compact source should complete automatically imported List");

            reports.clear();
            session.didChange(uri, valid);
            assertTrue(
                    waitForReport(reports, uri, valid, false),
                    "valid compact source should have no errors: " + reports);

            String invalid = "void main() { IO.println(List.of(\"ok\")); MissingThing value = null; }\n";
            reports.clear();
            session.didChange(uri, invalid);
            assertTrue(waitForReport(reports, uri, invalid, true), "genuine errors must be reported: " + reports);
        } finally {
            var processes = ProcessHandle.current()
                    .descendants()
                    .filter(process -> java.util.Arrays.stream(
                                    process.info().arguments().orElse(new String[0]))
                            .anyMatch(argument -> argument.startsWith(temporary.toString())))
                    .toList();
            session.dispose();
            for (var process : processes) process.onExit().get(10, TimeUnit.SECONDS);
        }
    }

    private static boolean waitForReport(
            List<PublishDiagnosticsParams> reports, String uri, String source, boolean expectMissing) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            List<PublishDiagnosticsParams> matching = reports.stream()
                    .filter(report -> uri.equals(report.getUri()))
                    .toList();
            if (!matching.isEmpty()) {
                var diagnostics = matching.getLast().getDiagnostics();
                boolean missing = diagnostics.stream()
                        .anyMatch(d -> d.getMessage().toString().contains("MissingThing"));
                boolean otherErrors = diagnostics.stream()
                        .anyMatch(d -> d.getSeverity() == org.eclipse.lsp4j.DiagnosticSeverity.Error
                                && !d.getMessage().toString().contains("MissingThing"));
                // The unused-local warning shows that JDT finished compiling this version; an initial
                // empty publish can arrive before the semantic diagnostics and is not a readiness signal.
                boolean compiledValid = diagnostics.stream()
                        .anyMatch(d -> d.getMessage().toString().contains("p is not used"));
                if (missing == expectMissing && !otherErrors && (expectMissing || compiledValid)) {
                    System.out.println("Compact source diagnostics for " + source + ": " + diagnostics);
                    return true;
                }
            }
            Thread.sleep(100);
        }
        return false;
    }
}
