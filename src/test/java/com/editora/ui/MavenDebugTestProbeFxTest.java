package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import com.editora.testkit.Await;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MANUAL PROBE (opt-in, {@code @Tag("probe")}) — Debug Test on a real Maven project with a real Surefire 3 fork,
 * jdtls and java-debug: the suspended test JVM is attached through the port Editora chose and the session
 * suspends at the breakpoint (review D1: Surefire 3 withholds the JDWP banner until the fork has run, so the
 * banner watcher alone hung forever). Then Debug ▸ Stop ends the Maven run with the session.
 *
 * <p>Run from the repository root (the sample is {@code samples/debug/java}):
 * {@code JAVA_HOME=<jdk 25> mvn test -Dtest=MavenDebugTestProbeFxTest -Dgroups=probe -Dlsp.probe=true
 * -Dsurefire.failIfNoSpecifiedTests=false}; {@code -Ddebug.probe.sample=<dir>} names another project whose
 * {@code src/test/java/com/example/shelf/ShelfTest.java} has {@code checkoutStopsAtTheLimit} at line 21,
 * {@code -Ddebug.probe.mvn=<path>} a Maven not on PATH, {@code -Ddebug.probe.importWait=<ms>} the longest wait given
 * to jdtls to import the project (180 s; the wait ends at its ready line). Needs {@code ~/.editora/plugins/lsp/java} and {@code dap/java}.
 */
@Tag("probe")
@Tag("fx")
class MavenDebugTestProbeFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    @Timeout(value = 8, unit = TimeUnit.MINUTES)
    void debugTestOnAMavenProjectSuspendsAtTheBreakpointThroughTheChosenPort() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean("lsp.probe"), "opt-in: -Dlsp.probe=true");
        Path home = Path.of(System.getProperty("user.home"));
        Path jdtls = home.resolve(".editora/plugins/lsp/java/bin/jdtls");
        Assumptions.assumeTrue(Files.isExecutable(jdtls), "needs jdtls at " + jdtls);
        Optional<Path> javaDebug = DebugAdapterLocator.locate("", home);
        Assumptions.assumeTrue(javaDebug.isPresent(), "needs java-debug under ~/.editora/plugins/dap/java");
        Path sample = Path.of(System.getProperty("debug.probe.sample", "samples/debug/java"))
                .toAbsolutePath()
                .normalize();
        Assumptions.assumeTrue(Files.isRegularFile(sample.resolve("pom.xml")), "needs the sample at " + sample);
        String mvn = System.getProperty("debug.probe.mvn", "");
        String jdk = System.getProperty("debug.probe.jdk", System.getenv().getOrDefault("JAVA_HOME", ""));

        Path dir = Files.createTempDirectory("editora-maven-debug-probe-").toRealPath();
        FxWindowFixture fx = FxWindowFixture.create(dir.resolve("config"), shared -> {
            var s = shared.getSettings();
            s.setMavenSupport(true);
            s.setTestRunner(true);
            s.setLspSupport(true);
            s.setJavaLspEnabled(true);
            s.setJavaLspCommand(jdtls.toString());
            s.setDebugSupport(true);
            s.setJavaDebugPluginPath(javaDebug.get().toString());
            if (!jdk.isBlank()) {
                s.setMavenJdkHome(jdk);
            }
            if (!mvn.isBlank()) {
                s.setMavenCommand(mvn);
            }
        });
        try {
            Path root = fx.useProject();
            fx.shared
                    .getTrustStore()
                    .trust(root); // the user's own project: jdtls loads its build (lsp.UntrustedBuilds)
            copyTree(sample, root);
            MainController c = fx.controller;
            // What the status bar says, as it says it: jdtls's ready line ends its import of the Maven project.
            List<String> said = new java.util.concurrent.CopyOnWriteArrayList<>();
            FxTestSupport.runOnFx(
                    () -> FxTestSupport.<javafx.scene.control.Label>field(FxTestSupport.field(c, "statusBar"), "echo")
                            .textProperty()
                            .addListener((o, was, now) -> said.add(String.valueOf(now))));
            Path test = root.resolve("src/test/java/com/example/shelf/ShelfTest.java");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(test, 22)); // the assertEquals of checkoutStopsAtTheLimit
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            OnFx.until(
                    debug::debugEffective,
                    "Java debugging to become available (jdtls + java-debug)",
                    Duration.ofSeconds(60));
            String ready =
                    com.editora.i18n.Messages.tr("status.lsp.ready", "\u0000").split("\u0000", -1)[1];
            Await.until(
                    () -> said.stream().anyMatch(line -> line.endsWith(ready)),
                    "jdtls to import the Maven project",
                    Duration.ofMillis(Long.getLong("debug.probe.importWait", 180_000)));
            FxTestSupport.runOnFx(() -> c.activeBuffer().toggleBreakpoint(22));

            DapManager dap = FxTestSupport.field(c, "dapManager");
            TestNavigationCoordinator navigation = FxTestSupport.field(c, "testNavigation");
            long t0 = System.currentTimeMillis();
            FxTestSupport.runOnFx(() -> {
                var target = c.activeBuffer().testTargetAtCaret(false);
                assertNotNull(target, "a JUnit test at the caret");
                assertEquals("checkoutStopsAtTheLimit", target.methodName());
                navigation.debugSingleTest(target);
            });
            OnFx.holdsWithin(() -> dap.state() == DapManager.State.SUSPENDED, Duration.ofSeconds(180));
            DapManager.State state = FxTestSupport.callOnFx(dap::state);
            long took = System.currentTimeMillis() - t0;
            System.out.println("[MavenDebugTestProbe] state=" + state + " after " + took + " ms");
            assertEquals(
                    DapManager.State.SUSPENDED,
                    state,
                    "the suspended Surefire fork is attached through the chosen port and stops at the breakpoint");

            // Debug ▸ Stop ends the session and the Maven run that forked its JVM.
            BuildCoordinator maven =
                    FxTestSupport.<java.util.List<BuildCoordinator>>field(c, "buildCoordinators").stream()
                            .filter(b -> b.tool() == com.editora.build.BuildTool.MAVEN)
                            .findFirst()
                            .orElseThrow();
            FxTestSupport.runOnFx(debug::stop);
            OnFx.holdsWithin(() -> !dap.isActive() && !maven.isRunning(), Duration.ofSeconds(30));
            boolean running = FxTestSupport.callOnFx(() -> dap.isActive() || maven.isRunning());
            assertFalse(running, "Stop ended the session and the Maven run");
        } finally {
            fx.dispose();
        }
    }

    private static void copyTree(Path from, Path to) throws Exception {
        try (var files = Files.walk(from)) {
            for (Path p : (Iterable<Path>) files::iterator) {
                if (p.getFileName().toString().equals("target")) {
                    continue; // the sample's build output, if any: the run compiles afresh
                }
                Path q = to.resolve(from.relativize(p).toString());
                if (Files.isDirectory(p)) {
                    Files.createDirectories(q);
                } else if (!p.toString().contains("/target/")) {
                    Files.copy(p, q, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }
}
