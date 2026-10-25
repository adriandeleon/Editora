package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Two windows on one project with a real jdtls (Java review C12): the second window's manager joins the
 * server the first one started, on the project's one workspace (no {@code -2}), the server outlives the first
 * window, answers the second, and stops with the last. Opt-in (run with
 * {@code -Dgroups=probe -Dlsp.probe=true -Dlsp.probe.jdtls=PATH}).
 */
@Tag("probe")
class JdtlsSharedServerProbeTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static void await(java.util.function.BooleanSupplier condition, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (!condition.getAsBoolean()) {
            assertTrue(System.nanoTime() < deadline, "timed out waiting for " + what);
            Thread.sleep(100);
        }
    }

    @Test
    void twoWindowsShareOneRealJdtls() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String jdtls = System.getProperty("lsp.probe.jdtls", "");
        Assumptions.assumeFalse(jdtls.isBlank(), "needs -Dlsp.probe.jdtls");
        Path scratch = Files.createTempDirectory("editora-jdtls-shared-").toRealPath();
        Path base = Files.createDirectories(scratch.resolve("workspaces"));
        Path project = Files.createDirectories(scratch.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Path one = src.resolve("One.java");
        Path two = src.resolve("Two.java");
        Files.writeString(one, "package demo;\n\npublic class One {\n    int size() { return 1; }\n}\n");
        Files.writeString(two, "package demo;\n\nclass Two {\n    int n = new One().size();\n}\n");
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>shared-probe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties></project>
                """);
        LspManager first = new LspManager((f, d) -> {}, (t, m) -> {});
        LspManager second = new LspManager((f, d) -> {}, (t, m) -> {});
        for (LspManager m : List.of(first, second)) {
            m.setJdtlsWorkspaceBase(base);
            m.configure(true, Map.of("java", jdtls));
        }
        try {
            first.openDocument(one, project, "java", Files.readString(one));
            await(() -> first.isRunning("java", project), "the first window's server");
            assertTrue(second.isRunning("java", project), "ready for the second window before it opens anything");

            second.openDocument(two, project, "java", Files.readString(two));
            LanguageServerSession shared = first.sessionForTest(one);
            assertSame(shared, second.sessionForTest(two), "the second window joined the first one's server");
            try (var dirs = Files.list(base)) {
                List<String> names = dirs.map(p -> p.getFileName().toString()).toList();
                assertEquals(1, names.size(), "one Eclipse workspace for the project: " + names);
                assertFalse(names.get(0).endsWith("-2"), names.toString());
            }

            first.close();
            assertFalse(shared.isDisposed(), "the server outlives the first window");
            var symbols = new java.util.concurrent.CompletableFuture<List<String>>();
            second.documentSymbols(
                    two,
                    list -> symbols.complete(list.stream().map(SymbolNode::name).toList()));
            List<String> names = symbols.get(60, TimeUnit.SECONDS);
            System.out.println("PROBE symbols from the second window: " + names);
            assertTrue(names.contains("Two"), names.toString());

            second.close();
            assertTrue(shared.isDisposed(), "stopped with the last window");
            shared.exited().get(30, TimeUnit.SECONDS);
        } finally {
            first.close();
            second.close();
        }
    }
}
