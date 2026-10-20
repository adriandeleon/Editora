package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import com.editora.dap.DapManager;
import com.editora.dap.DebugAdapterLocator;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * {@code -Ddebug.probe.mvn=<path>} a Maven not on PATH, {@code -Ddebug.probe.importWait=<ms>} the time given
 * to jdtls to import the project (25 s). Needs {@code ~/.editora/plugins/lsp/java} and {@code dap/java}.
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
            copyTree(sample, root);
            MainController c = fx.controller;
            Path test = root.resolve("src/test/java/com/example/shelf/ShelfTest.java");
            FxTestSupport.runOnFx(() -> c.openAndNavigate(test, 22)); // the assertEquals of checkoutStopsAtTheLimit
            DebugCoordinator debug = FxTestSupport.field(c, "debugCoordinator");
            boolean effective = false;
            for (int i = 0; i < 600 && !effective; i++) {
                effective = FxTestSupport.callOnFx(debug::debugEffective);
                Thread.sleep(100);
            }
            assertTrue(effective, "Java debugging never became available (jdtls + java-debug)");
            Thread.sleep(Long.getLong("debug.probe.importWait", 25_000)); // jdtls imports the Maven project
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
            DapManager.State state = null;
            for (int i = 0; i < 1800; i++) {
                state = FxTestSupport.callOnFx(dap::state);
                if (state == DapManager.State.SUSPENDED) {
                    break;
                }
                Thread.sleep(100);
            }
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
            boolean running = true;
            for (int i = 0; i < 300 && running; i++) {
                running = FxTestSupport.callOnFx(() -> dap.isActive() || maven.isRunning());
                Thread.sleep(100);
            }
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
