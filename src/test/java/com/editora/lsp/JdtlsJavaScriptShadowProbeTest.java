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
import org.eclipse.lsp4j.TextEdit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Opt-in check, against Editora's installed JDT LS, of how an extensionless Java script is shown to it
 * ({@link JavaScriptShadow}): through its {@code .java} stand-in it completes, reports syntax errors on the
 * script, and puts an added import <em>after</em> the shebang line — the reason the line becomes a block
 * comment rather than a {@code //} one.
 */
@Tag("probe")
class JdtlsJavaScriptShadowProbeTest {

    static final String SCRIPT = """
            #!/usr/bin/env -S java --source 25
            void main() {
                IO.println(1);
                ArrayLis
            }
            """;

    @TempDir
    Path temporary;

    @Test
    void aScriptIsServedThroughItsStandIn() throws Exception {
        assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path jdtls = Path.of(System.getProperty("user.home"), ".editora/plugins/lsp/java/bin/jdtls");
        assumeTrue(Files.isExecutable(jdtls), "needs the installed JDT LS");
        Path project = Files.createDirectories(temporary.resolve("scripts"));
        Path script = project.resolve("launcher");
        Files.writeString(script, SCRIPT);
        Path standIn =
                Files.createDirectories(temporary.resolve("stand-ins/s1")).resolve("launcher.java");
        Files.writeString(standIn, JavaScriptShadow.wireText(SCRIPT));
        String key = DocumentUris.of(script);
        var reports = new CopyOnWriteArrayList<PublishDiagnosticsParams>();
        var ready = new CountDownLatch(1);
        var session = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(
                        "java",
                        List.of(
                                jdtls.toString(),
                                "-data",
                                temporary.resolve("workspace").toString()),
                        List.of("pom.xml")),
                project,
                reports::add,
                (type, message) -> {
                    if ("ServiceReady".equals(type)) ready.countDown();
                },
                LspManager.javaInitOptions(List.of()));
        try {
            assertTrue(session.start());
            assertTrue(ready.await(70, TimeUnit.SECONDS), "JDT LS should initialize");
            session.alias(key, DocumentUris.of(standIn), JavaScriptShadow::wireText);
            session.didOpen(key, "java", SCRIPT);

            // Completion offers a type that needs an import; the import goes after the shebang line.
            Position at = new Position(3, "    ArrayLis".length());
            CompletionItem arrayList = null;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
            while (arrayList == null && System.nanoTime() < deadline) {
                arrayList = CompletionMapper.itemsOf(session.completion(key, at).get(30, TimeUnit.SECONDS)).stream()
                        .filter(i -> i.getLabel().startsWith("ArrayList"))
                        .filter(i -> String.valueOf(i.getDetail()).contains("java.util")
                                || String.valueOf(i.getLabelDetails()).contains("java.util"))
                        .findFirst()
                        .orElse(null);
                if (arrayList == null) Thread.sleep(200);
            }
            assertNotNull(arrayList, "the script should complete through its stand-in");
            List<TextEdit> imports = session.resolveCompletion(arrayList)
                    .get(30, TimeUnit.SECONDS)
                    .getAdditionalTextEdits();
            System.out.println("Import edits for the script: " + imports);
            assertNotNull(imports);
            assertFalse(imports.isEmpty(), "ArrayList needs an import");
            int shebangEnd = SCRIPT.indexOf('\n');
            for (TextEdit edit : imports) {
                var start = edit.getRange().getStart();
                assertTrue(
                        start.getLine() > 0 || start.getCharacter() >= shebangEnd,
                        "an import must not go in front of the shebang: " + edit);
            }

            // The syntax error on line 4 is reported, and names the script.
            boolean reported = false;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!reported && System.nanoTime() < deadline) {
                reported = reports.stream()
                        .filter(r -> key.equals(session.uris().toKey(r.getUri())))
                        .anyMatch(r -> r.getDiagnostics().stream()
                                .anyMatch(d -> d.getRange().getStart().getLine() == 3));
                if (!reported) Thread.sleep(100);
            }
            assertTrue(reported, "the script's syntax error should be reported on it: " + reports);
            assertEquals(script, session.uris().toPath(DocumentUris.of(standIn)));
        } finally {
            // Let jdtls exit before the temporary folder it writes into is deleted.
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
}
