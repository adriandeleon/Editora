package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Characterization tests for {@link BuildCoordinator}, exercised against a fake {@link CoordinatorHost}
 * (via {@link CoordinatorHostStub}) plus a fake {@link BuildCoordinator.Ops}. Pins the Settings/Simple-Mode
 * gating, the async marker-file detection (a real temp {@code pom.xml}/{@code package.json} on disk, polled
 * after {@link BuildCoordinator#refresh()}), the malformed-file distinction, disabled/unavailable command
 * reporting, and that a launch attempt actually reaches {@code BuildService} (a deliberately bogus command so
 * it fails fast on {@code ProcessBuilder.start()} instead of spawning a real build). Covers both the Maven and
 * npm {@link BuildTool}s on the same generic coordinator.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BuildCoordinatorFxTest {

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class FakeHost extends CoordinatorHostStub {
        final Settings settings = new Settings();
        boolean simpleMode = false;
        String lastStatus;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public boolean simpleModeActive() {
            return simpleMode;
        }

        @Override
        public void setStatus(String message) {
            lastStatus = message;
        }
    }

    private static final class FakeOps implements BuildCoordinator.Ops {
        Path projectRoot;
        int openTasksCount;
        int openConsoleCount;
        int toolbarVisibleCount;
        boolean lastVisible;

        /** Workspace trust: the answer the fake prompt gives, and what it was asked about. */
        boolean trusted;

        boolean promptAnswer;
        int promptCount;
        Path promptedRoot;
        Path promptedWrapper;
        int trustCount;

        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public boolean isTrusted(Path root) {
            return trusted;
        }

        @Override
        public boolean confirmTrust(Path root, Path wrapper) {
            promptCount++;
            promptedRoot = root;
            promptedWrapper = wrapper;
            return promptAnswer;
        }

        @Override
        public void trust(Path root) {
            trustCount++;
            trusted = true;
        }

        @Override
        public void openTasks() {
            openTasksCount++;
        }

        @Override
        public void openConsole() {
            openConsoleCount++;
        }

        @Override
        public void setToolWindowsAvailable(boolean available) {
            toolbarVisibleCount++;
            lastVisible = available;
        }
    }

    /** A fresh shared console for each coordinator under test (the panel content isn't asserted here). */
    private static BuildCoordinator coordinator(BuildTool tool, FakeHost host, BuildCoordinator.Ops ops) {
        return new BuildCoordinator(tool, host, ops, new BuildOutputPanel());
    }

    private static final String VALID_POM = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>demo-app</artifactId>
              <version>1.0.0</version>
              <build>
                <plugins>
                  <plugin>
                    <groupId>org.apache.maven.plugins</groupId>
                    <artifactId>maven-compiler-plugin</artifactId>
                    <executions>
                      <execution>
                        <id>default-compile</id>
                        <phase>compile</phase>
                        <goals><goal>compile</goal></goals>
                      </execution>
                    </executions>
                  </plugin>
                </plugins>
              </build>
              <profiles>
                <profile>
                  <id>dist</id>
                </profile>
              </profiles>
            </project>
            """;

    private static final String MALFORMED_POM = "<project><groupId>oops</groupId>";

    private static final String VALID_PACKAGE_JSON =
            "{ \"name\": \"demo-web\", \"scripts\": { \"build\": \"tsc\", \"test\": \"vitest\" } }";

    private static final String MALFORMED_PACKAGE_JSON = "{ not json";

    private static String disp(BuildTool tool) {
        return tool.displayName();
    }

    @Test
    void isEnabledTracksSettingAndSimpleMode() {
        FakeHost host = new FakeHost();
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, new FakeOps());
        host.settings.setMavenSupport(true);
        assertTrue(c.isEnabled());
        host.simpleMode = true;
        assertFalse(c.isEnabled(), "suppressed in Simple UI mode");
        host.simpleMode = false;
        host.settings.setMavenSupport(false);
        assertFalse(c.isEnabled());
    }

    @Test
    void refreshWhenDisabledClearsDetectionWithoutTouchingDisk(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(false);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        assertFalse(c.isDetected(), "disabled → never even looks for a marker file");
        assertEquals(1, ops.toolbarVisibleCount);
        assertFalse(ops.lastVisible);
    }

    @Test
    void refreshFindsAndParsesARealPomXml(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isDetected, "pom.xml detection");

        assertEquals("demo-app", c.detectedLabel());
        assertTrue(ops.lastVisible, "toolbar button shown once a pom.xml resolves");
    }

    @Test
    void refreshFindsAndParsesARealPackageJson(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setNpmSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("package.json"), VALID_PACKAGE_JSON);
        BuildCoordinator c = coordinator(BuildTool.NPM, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isDetected, "package.json detection");

        assertEquals("demo-web", c.detectedLabel(), "npm label is the package name");
        assertTrue(ops.lastVisible, "toolbar button shown once a package.json resolves");
    }

    @Test
    void refreshWithNoMarkerLeavesTheToolbarButtonHidden(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir; // empty — no pom.xml anywhere in this ancestry
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        // Give the (short-lived) detect thread a moment even though nothing should ever flip true.
        Thread.sleep(300);
        assertFalse(FxTestSupport.callOnFx(c::isDetected));
        assertNull(FxTestSupport.callOnFx(c::detectedLabel));
    }

    @Test
    void malformedPomIsTrackedDistinctlyFromNoMarker(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), MALFORMED_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isDetected, "malformed pom.xml is still 'found'");
        assertNull(c.detectedLabel(), "but never parses to a model");

        FxTestSupport.runOnFx(() -> c.showActionsPopup());
        assertEquals(tr("status.build.malformed", disp(BuildTool.MAVEN)), host.lastStatus);
    }

    @Test
    void malformedPackageJsonIsTrackedDistinctlyFromNoMarker(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setNpmSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("package.json"), MALFORMED_PACKAGE_JSON);
        BuildCoordinator c = coordinator(BuildTool.NPM, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isDetected, "malformed package.json is still 'found'");
        assertNull(c.detectedLabel());

        FxTestSupport.runOnFx(() -> c.showActionsPopup());
        assertEquals(tr("status.build.malformed", disp(BuildTool.NPM)), host.lastStatus);
    }

    @Test
    void disabledCommandsReportAndNeverOpenTheConsole() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(false);
        FakeOps ops = new FakeOps();
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        String disabled = tr("status.build.disabled", disp(BuildTool.MAVEN));

        FxTestSupport.runOnFx(() -> {
            c.showActionsPopup();
            assertEquals(disabled, host.lastStatus);

            c.runTask(List.of("clean"), List.of());
            assertEquals(disabled, host.lastStatus);

            c.runCustom();
            assertEquals(disabled, host.lastStatus);
        });
        assertEquals(0, ops.openConsoleCount, "disabled → a run never opens the console");
    }

    @Test
    void showActionsPopupWithNoMarkerReportsNotDetected() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, new FakeOps());

        FxTestSupport.runOnFx(() -> c.showActionsPopup());
        assertEquals(tr("status.build.notDetected", disp(BuildTool.MAVEN)), host.lastStatus);
    }

    @Test
    void rerunLastWithNothingPreviousReportsNoRerun() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, new FakeOps());

        FxTestSupport.runOnFx(c::rerunLast);
        assertEquals(tr("status.build.noRerun", disp(BuildTool.MAVEN)), host.lastStatus);
    }

    @Test
    void runTaskWithABogusCommandOpensTheConsoleAndReportsFailureFast(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "valid pom.xml parses");

        // No mvnw wrapper on disk, so the bogus override command is what actually gets launched — and
        // ProcessBuilder.start() throws synchronously (no such executable), so this never spawns a real
        // process or blocks on a build.
        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(1, ops.openConsoleCount, "a run attempt opens the console even though it fails");
        String failedPrefix = tr("status.build.failed", disp(BuildTool.MAVEN), "");
        assertTrue(
                host.lastStatus != null && host.lastStatus.startsWith(failedPrefix),
                "reports the launch failure: " + host.lastStatus);
    }

    @Test
    void reapplyVisibilityUsesCachedDetectionWithoutReDetecting(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isDetected, "pom.xml detected once");

        // Simulate entering Simple Mode: isEnabled() flips false, but nothing on disk changed.
        host.simpleMode = true;
        FxTestSupport.runOnFx(c::reapplyVisibility);
        assertFalse(ops.lastVisible, "Simple Mode hides the button even though a pom.xml is still cached");

        host.simpleMode = false;
        FxTestSupport.runOnFx(c::reapplyVisibility);
        assertTrue(ops.lastVisible, "leaving Simple Mode re-shows it from the cached detection, no re-detect");
    }

    // --- workspace trust (#412) ---------------------------------------------------------------------

    /** Writes an executable {@code mvnw} beside the pom, i.e. a repo-supplied script the build would run. */
    private static void writeMavenWrapper(Path dir) throws Exception {
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        Path f = Files.writeString(dir.resolve(windows ? "mvnw.cmd" : "mvnw"), "#!/bin/sh\nexit 0\n");
        if (!windows) {
            f.toFile().setExecutable(true);
        }
    }

    /** A detected Maven coordinator over {@code dir}, with a wrapper on disk. */
    private static BuildCoordinator detectedWithWrapper(FakeHost host, FakeOps ops, Path dir) throws Exception {
        host.settings.setMavenSupport(true);
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        writeMavenWrapper(dir);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "pom.xml parses");
        return c;
    }

    @Test
    void aRootWithNoWrapperIsNeverPrompted(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM); // no mvnw
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "pom.xml parses");
        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(0, ops.promptCount, "argv[0] is the user's own tool — nothing to consent to");
        assertEquals(1, ops.openConsoleCount, "and the run proceeds as before");
    }

    @Test
    void anUntrustedWrapperRootBlocksTheRunAndNeverOpensTheConsole(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.promptAnswer = false; // the user declines
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(1, ops.promptCount, "an untrusted wrapper root must ask");
        assertEquals(0, ops.trustCount, "declining must not record trust");
        assertEquals(0, ops.openConsoleCount, "declining must not run anything");
        assertEquals(tr("status.build.untrusted", disp(BuildTool.MAVEN)), host.lastStatus);
    }

    @Test
    void decliningDoesNotFallBackToThePathTool(@TempDir Path dir) throws Exception {
        // The tempting "fall back to mvn" would still execute a hostile pom's plugins — no must mean no build.
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.promptAnswer = false;
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(0, ops.openConsoleCount);
        assertFalse(
                host.lastStatus.startsWith(tr("status.build.failed", disp(BuildTool.MAVEN), "")),
                "must not have attempted a launch at all, got: " + host.lastStatus);
    }

    @Test
    void acceptingRecordsTrustAndProceeds(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.promptAnswer = true; // the user trusts the folder
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(1, ops.promptCount);
        assertEquals(1, ops.trustCount, "accepting must persist the decision");
        assertEquals(1, ops.openConsoleCount, "and the run proceeds");
        assertEquals(dir, ops.promptedRoot, "prompts about the marker root");
        assertNotNull(ops.promptedWrapper);
        assertTrue(
                ops.promptedWrapper.getFileName().toString().startsWith("mvnw"),
                "names the script it is about to run: " + ops.promptedWrapper);
    }

    @Test
    void anAlreadyTrustedRootIsNotPromptedAgain(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.trusted = true; // trusted in an earlier session
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(0, ops.promptCount, "trust is remembered — asking once per repo, not once per build");
        assertEquals(1, ops.openConsoleCount);
    }

    @Test
    void trustIsAskedOnceThenRemembered(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.promptAnswer = true;
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));
        FxTestSupport.runOnFx(() -> c.runTask(List.of("test"), List.of()));
        FxTestSupport.runOnFx(() -> c.runTask(List.of("package"), List.of()));

        assertEquals(1, ops.promptCount, "three builds, one prompt");
        assertEquals(3, ops.openConsoleCount);
    }

    @Test
    void rerunLastIsGatedToo(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        ops.promptAnswer = true;
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));
        assertEquals(1, ops.promptCount);

        // Trust revoked between runs (Settings → Workspace): the remembered rerun must re-ask, not slip past.
        ops.trusted = false;
        ops.promptAnswer = false;
        FxTestSupport.runOnFx(c::rerunLast);

        assertEquals(2, ops.promptCount, "rerunLast goes through the same gate");
        assertEquals(1, ops.openConsoleCount, "and the declined rerun never ran");
    }

    /** Polls {@code condition} (each check on the FX thread) until it's true or a few seconds elapse. */
    private static void waitUntil(FxCheck condition, String what) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (FxTestSupport.callOnFx(condition::check)) {
                return;
            }
            Thread.sleep(25);
        }
        fail("Timed out waiting for: " + what);
    }

    @FunctionalInterface
    private interface FxCheck {
        boolean check();
    }

    private static String tr(String key, Object... args) {
        return com.editora.i18n.Messages.tr(key, args);
    }
}
