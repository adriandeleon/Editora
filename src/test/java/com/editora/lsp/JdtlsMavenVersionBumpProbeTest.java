package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.editora.text.LspDiagnostic;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A multi-module Maven project whose every pom changes version at once — a pull or a merge that bumps the
 * development version — with a real jdtls: a module's imports of its sibling stay resolved. Opt-in (run with
 * {@code -Dgroups=probe -Dlsp.probe=true -Dlsp.probe.jdtls=PATH}).
 */
@Tag("probe")
@Tag("fx")
class JdtlsMavenVersionBumpProbeTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    private static String parentPom(String version) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>demo</groupId><artifactId>demo-parent</artifactId><version>%s</version>
                <packaging>pom</packaging>
                <modules><module>demo-core</module><module>demo-cli</module></modules>
                <properties><maven.compiler.release>21</maven.compiler.release></properties>
                <dependencyManagement><dependencies>
                <dependency><groupId>demo</groupId><artifactId>demo-core</artifactId><version>${project.version}</version></dependency>
                </dependencies></dependencyManagement>
                </project>
                """.formatted(version);
    }

    private static String modulePom(String artifact, String version, boolean dependsOnCore) {
        String deps = dependsOnCore
                ? "<dependencies><dependency><groupId>demo</groupId><artifactId>demo-core</artifactId></dependency></dependencies>"
                : "";
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <parent><groupId>demo</groupId><artifactId>demo-parent</artifactId><version>%s</version></parent>
                <artifactId>%s</artifactId>
                %s
                </project>
                """.formatted(version, artifact, deps);
    }

    private static void writePoms(Path project, String version) throws Exception {
        // the order git writes them in: the modules' first, the root's last
        Files.writeString(project.resolve("demo-cli/pom.xml"), modulePom("demo-cli", version, true));
        Files.writeString(project.resolve("demo-core/pom.xml"), modulePom("demo-core", version, false));
        Files.writeString(project.resolve("pom.xml"), parentPom(version));
    }

    @Test
    void siblingImportsSurviveAVersionBump() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        String jdtls = System.getProperty("lsp.probe.jdtls", "");
        Assumptions.assumeFalse(jdtls.isBlank(), "needs -Dlsp.probe.jdtls");
        Path scratch = Files.createTempDirectory("editora-jdtls-bump-").toRealPath();
        Path base = Files.createDirectories(scratch.resolve("workspaces"));
        Path project = Files.createDirectories(scratch.resolve("project"));
        Path coreSrc = Files.createDirectories(project.resolve("demo-core/src/main/java/demo/core"));
        Path cliSrc = Files.createDirectories(project.resolve("demo-cli/src/main/java/demo/cli"));
        Files.writeString(
                coreSrc.resolve("Core.java"),
                "package demo.core;\n\npublic class Core {\n    public int one() { return 1; }\n}\n");
        Path main = cliSrc.resolve("Main.java");
        Files.writeString(
                main,
                "package demo.cli;\n\nimport demo.core.Core;\n\nclass Main {\n    int n = new Core().one();\n}\n");
        writePoms(project, "1.0-SNAPSHOT");

        Map<Path, List<LspDiagnostic>> diagnostics = new ConcurrentHashMap<>();
        LspManager m =
                new LspManager((f, d) -> diagnostics.put(f.toAbsolutePath().normalize(), d), (t, s) -> {});
        m.setFolderTrust(folder -> true);
        m.setJdtlsWorkspaceBase(base);
        m.configure(true, Map.of("java", jdtls));
        Path key = main.toAbsolutePath().normalize();
        java.util.function.IntSupplier errors = () -> (int) diagnostics.getOrDefault(key, List.of()).stream()
                .filter(d -> d.severity() == LspDiagnostic.Severity.ERROR)
                .count();
        try {
            m.openDocument(main, project, "java", Files.readString(main));
            assertTrue(
                    com.editora.testkit.Await.holdsWithin(
                            () -> m.isRunning("java", project) && diagnostics.containsKey(key),
                            java.time.Duration.ofSeconds(120)),
                    "the server and Main's first diagnostics");
            Thread.sleep(5000); // the import settles
            System.out.println("PROBE errors before the bump: " + diagnostics.get(key));
            assertTrue(errors.getAsInt() == 0, "resolved before: " + diagnostics.get(key));

            writePoms(project, "1.1-SNAPSHOT");
            Thread.sleep(20000); // the watcher reports the burst, jdtls updates the projects and republishes
            System.out.println("PROBE errors after the bump: " + diagnostics.get(key));
            assertTrue(errors.getAsInt() == 0, "still resolved after the bump: " + diagnostics.get(key));
            // jdtls updated the parent on the watched-file event, and again on the forced update once the poms
            // settled: what puts right a project jdtls skipped mid-burst
            String log;
            try (var dirs = Files.list(base)) {
                log = Files.readString(dirs.findFirst().orElseThrow().resolve(".metadata/.log"));
            }
            assertTrue(log.contains("Projects updated in"), "the forced update ran (it logs once for all projects)");
        } finally {
            m.close();
        }
    }
}
