package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lombok through the agent {@link JavaLombok} adds to the jdtls command: without it jdtls reports the getter of a
 * {@code @Getter} field as undefined; with it, nothing. Opt-in, against a real jdtls and a Lombok jar given by
 * {@code -Dlsp.probe.lombok=PATH} (run with {@code -Dgroups=probe -Dlsp.probe=true}; {@code -Dlsp.probe.jdtls=PATH}
 * picks the server as for {@link JdtlsClientSettingsProbeTest}).
 */
@Tag("probe")
class JdtlsLombokProbeTest {

    private static final String POINT =
            "package demo;\n\n@lombok.Getter\npublic class Point {\n    private int x;\n}\n";
    private static final String USE =
            "package demo;\n\nclass Use {\n    int read(Point p) {\n        return p.getX();\n    }\n}\n";

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

    @Test
    void theAgentMakesLombokMembersVisible() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeFalse(jdtls().isBlank(), "needs a local jdtls");
        String lombok = System.getProperty("lsp.probe.lombok", "");
        Assumptions.assumeFalse(lombok.isBlank(), "needs -Dlsp.probe.lombok=PATH to a lombok jar");
        Path jar = Path.of(lombok).toAbsolutePath();

        assertTrue(undefinedGetter(jar, null), "control: without the agent jdtls cannot see getX()");
        assertFalse(undefinedGetter(jar, jar), "with JavaLombok's agent it can");
    }

    /** Whether jdtls, launched with {@code agent} (or none), reports {@code getX()} undefined in Use.java. */
    private static boolean undefinedGetter(Path lombokJar, @Nullable Path agent) throws Exception {
        Path root = Files.createTempDirectory("editora-jdtls-lombok-").toRealPath();
        Path project = Files.createDirectories(root.resolve("project"));
        Path src = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(src.resolve("Point.java"), POINT);
        Files.writeString(src.resolve("Use.java"), USE);
        // A system-scoped dependency: the import resolves it from the path, with nothing to download.
        Files.writeString(project.resolve("pom.xml"), """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>lombok-probe</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencies><dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId>
                <version>1.18.34</version><scope>system</scope><systemPath>%s</systemPath></dependency></dependencies>
                </project>
                """.formatted(lombokJar));
        List<String> command = JavaLombok.withAgent(
                List.of(jdtls(), "-data", root.resolve("data").toString()),
                agent,
                JavaServerEnvironment::isJdtlsLauncher);
        System.out.println("PROBE lombok command = " + command);
        var diagnostics = new CopyOnWriteArrayList<PublishDiagnosticsParams>();
        var imported = new java.util.concurrent.CountDownLatch(1);
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec("java", command, List.of("pom.xml")),
                project,
                diagnostics::add,
                (type, message) -> {
                    if ("ProjectStatus".equals(type)) {
                        imported.countDown();
                    }
                },
                LspManager.javaInitOptions(List.of()));
        try {
            assertTrue(s.start());
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!s.isInitialized() && System.nanoTime() < deadline) {
                Thread.sleep(100);
            }
            assertTrue(s.isInitialized(), "real handshake");
            assertTrue(imported.await(120, TimeUnit.SECONDS), "the project imported");
            String use = src.resolve("Use.java").toUri().toString();
            s.didOpen(src.resolve("Point.java").toUri().toString(), "java", POINT);
            s.didOpen(use, "java", USE);
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            PublishDiagnosticsParams latest = null;
            while (System.nanoTime() < until) {
                for (var p : diagnostics) {
                    if (p.getUri().endsWith("Use.java")) {
                        latest = p;
                    }
                }
                if (latest != null) {
                    break;
                }
                Thread.sleep(100);
            }
            Thread.sleep(1500); // a reconcile may follow the first publish
            for (var p : diagnostics) {
                if (p.getUri().endsWith("Use.java")) {
                    latest = p;
                }
            }
            System.out.println("PROBE lombok agent=" + (agent != null) + " Use.java = "
                    + (latest == null
                            ? "no publish"
                            : latest.getDiagnostics().stream()
                                    .map(d -> d.getMessage())
                                    .toList()));
            return latest != null
                    && latest.getDiagnostics().stream()
                            .anyMatch(d -> String.valueOf(
                                            d.getMessage().isLeft()
                                                    ? d.getMessage().getLeft()
                                                    : d.getMessage().getRight().getValue())
                                    .contains("getX()"));
        } finally {
            s.dispose();
        }
    }
}
