package com.editora.lsp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import com.editora.install.InstallCatalog;
import com.editora.install.JvmLspSmoke;
import com.editora.install.MavenServerInstall;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) — the real lemminx-maven server, as Editora installs it, against
 * the post-install request and the detection check added for Maven review C1/C3:
 *
 * <ol>
 *   <li>the upstream bundle <b>without</b> guava, commons-lang3 and jsoup fails the smoke with the
 *       {@code NoClassDefFoundError} that used to be invisible, and detection calls the folder incomplete;</li>
 *   <li>the same bundle <b>with</b> the three pinned jars passes the smoke, detection calls it complete, and a
 *       session's hover on a real pom answers without a server fault.</li>
 * </ol>
 *
 * <p>Needs the installed server at {@code ~/.editora/plugins/lsp/maven} (any state) and the three jars either
 * in that folder or in {@code ~/.m2}. Run: {@code ./mvnw test -Dtest=LemminxMavenProbeTest -Dlsp.probe=true}.
 * The server inherits {@code HOME}, so it writes its schema cache to {@code ~/.lemminx} as it does in Editora.
 */
@Tag("probe")
class LemminxMavenProbeTest {

    private static final String POM = """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion>
              <groupId>probe</groupId>
              <artifactId>probe</artifactId>
              <version>1</version>
              <dependencies>
                <dependency>
                  <groupId>org.jsoup</groupId>
                  <artifactId>jsoup</artifactId>
                  <version>1.23.2</version>
                </dependency>
              </dependencies>
            </project>
            """;

    private static Path installed() {
        return Path.of(System.getProperty("user.home"), ".editora", "plugins", "lsp", "maven");
    }

    private static void record(String name, Object value) {
        System.out.println("EVALUATION " + name + " = " + value);
    }

    private static List<String> argv(Path dir) {
        return com.editora.run.ProgramArgs.commandLine(
                InstallCatalog.jvmClasspathCommand(dir, InstallCatalog.LEMMINX_MAIN_CLASS));
    }

    private static boolean extra(String name) {
        return name.startsWith("guava-") || name.startsWith("commons-lang3-") || name.startsWith("jsoup-");
    }

    /** The three extra jars: from the install if a repair put them there, else from {@code ~/.m2}; empty when neither. */
    private static List<Path> extraJars(Path install) {
        Path m2 = Path.of(System.getProperty("user.home"), ".m2", "repository");
        List<Path> found = new java.util.ArrayList<>();
        for (InstallCatalog.JvmLspJar jar : InstallCatalog.LEMMINX_MAVEN_EXTRA_JARS) {
            Path inInstall = install.resolve(jar.fileName());
            if (Files.isRegularFile(inInstall)) {
                found.add(inInstall);
                continue;
            }
            String rel = jar.url().substring(InstallCatalog.MAVEN_CENTRAL.length());
            Path inM2 = m2.resolve(rel);
            if (Files.isRegularFile(inM2)) {
                found.add(inM2);
            }
        }
        return found.size() == InstallCatalog.LEMMINX_MAVEN_EXTRA_JARS.size() ? found : List.of();
    }

    @Test
    void theBundleFailsWithoutTheThreeJarsAndWorksWithThem() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Assumptions.assumeTrue(Files.isDirectory(installed()), "needs the installed server");
        Assumptions.assumeTrue(LspManager.available(List.of("java")), "needs java on PATH");
        Path work = Files.createTempDirectory("editora-lemminx-maven-probe-").toRealPath();

        // 1. the bundle as upstream ships it
        Path bare = Files.createDirectories(work.resolve("bare"));
        copyJars(installed(), bare, name -> !extra(name));
        assertEquals(
                List.of("guava-*.jar", "commons-lang3-*.jar", "jsoup-*.jar"), MavenServerInstall.missingJars(bare));
        assertFalse(LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, argv(bare)), "detection: incomplete");
        Optional<String> bareVerdict = JvmLspSmoke.hoverCheck(argv(bare), Duration.ofSeconds(90));
        record("smoke.bare", bareVerdict.orElse("passed"));
        assertTrue(bareVerdict.isPresent(), "the bundle without its provided jars must fail the smoke");
        assertTrue(bareVerdict.get().contains("NoClassDefFoundError"), bareVerdict.get());

        // 2. the bundle plus the three pinned jars
        List<Path> extras = extraJars(installed());
        Assumptions.assumeFalse(extras.isEmpty(), "needs guava, commons-lang3 and jsoup in the install or ~/.m2");
        Path full = Files.createDirectories(work.resolve("full"));
        copyJars(bare, full, name -> true);
        for (Path jar : extras) {
            Files.copy(jar, full.resolve(jar.getFileName().toString()));
        }
        assertTrue(MavenServerInstall.complete(full));
        assertTrue(LspManager.available(LspServerRegistry.MAVEN_POM_SERVER_ID, argv(full)), "detection: complete");
        Optional<String> fullVerdict = JvmLspSmoke.hoverCheck(argv(full), Duration.ofSeconds(90));
        record("smoke.full", fullVerdict.orElse("passed"));
        assertEquals(Optional.empty(), fullVerdict);

        // 3. a session on a real pom: hover answers, no server fault
        Path project = Files.createDirectories(work.resolve("project"));
        Path pom = project.resolve("pom.xml");
        Files.writeString(pom, POM);
        String uri = pom.toUri().toString();
        var s = new LanguageServerSession(
                new LspServerRegistry.ServerSpec(LspServerRegistry.MAVEN_POM_SERVER_ID, argv(full), List.of("pom.xml")),
                project,
                diagnostics -> {},
                (type, message) -> {},
                LspManager.initOptionsFor(LspServerRegistry.MAVEN_POM_SERVER_ID, List.of()));
        try {
            assertTrue(s.start());
            s.didOpen(uri, "xml", POM);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
            while (!s.isInitialized() && System.nanoTime() < deadline) Thread.sleep(100);
            assertTrue(s.isInitialized(), "handshake");
            // The Maven extension answers null until its own init (Plexus, the local-repo index) is done, which
            // lands after the handshake (review C18): poll, as the jdtls probe polls its inlay hints.
            long started = System.nanoTime();
            Hover onArtifact = null;
            while (onArtifact == null && System.nanoTime() < deadline) {
                onArtifact = s.hover(uri, new Position(8, 20)).get(90, TimeUnit.SECONDS);
                if (onArtifact == null) Thread.sleep(1_000);
            }
            record("hover.artifactId.waitedMillis", (System.nanoTime() - started) / 1_000_000);
            record("hover.artifactId", onArtifact == null ? "null" : onArtifact.getContents());
            assertTrue(onArtifact != null && onArtifact.getContents() != null, "hover on the artifactId must answer");
            Hover onProject = s.hover(uri, new Position(0, 2)).get(90, TimeUnit.SECONDS);
            record("hover.project", onProject == null ? "null" : onProject.getContents());
        } finally {
            s.dispose();
        }
    }

    private static void copyJars(Path from, Path to, java.util.function.Predicate<String> keep) throws IOException {
        try (Stream<Path> list = Files.list(from)) {
            for (Path p : (Iterable<Path>) list::iterator) {
                String name = p.getFileName().toString();
                if (Files.isRegularFile(p) && name.endsWith(".jar") && keep.test(name)) {
                    Files.copy(p, to.resolve(name));
                }
            }
        }
    }
}
