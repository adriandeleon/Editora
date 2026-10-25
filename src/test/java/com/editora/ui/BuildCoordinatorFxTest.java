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
import static org.junit.jupiter.api.Assertions.assertSame;
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

        final List<String> statuses = new java.util.ArrayList<>();

        @Override
        public void setStatus(String message) {
            lastStatus = message;
            statuses.add(message);
        }

        /** The file whose project drives detection, when a test needs one below the project root. */
        com.editora.editor.EditorBuffer active;

        @Override
        public com.editora.editor.EditorBuffer activeBuffer() {
            return active;
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

        com.editora.build.BuildTrust.Summary promptedSummary;
        Path opened;

        @Override
        public void openFile(Path file) {
            opened = file;
        }

        @Override
        public boolean confirmTrust(Path root, com.editora.build.BuildTrust.Summary summary) {
            promptCount++;
            promptedRoot = root;
            promptedWrapper = summary.wrapper();
            promptedSummary = summary;
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
        // It says where the file is broken (review C13): the parser's message after the tool's name.
        String prefix = tr("status.build.malformedAt", disp(BuildTool.MAVEN), "");
        assertTrue(host.lastStatus.startsWith(prefix) && host.lastStatus.length() > prefix.length(), host.lastStatus);
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
        // It says where the file is broken (review C13): the parser's message after the tool's name.
        String prefix = tr("status.build.malformedAt", disp(BuildTool.NPM), "");
        assertTrue(host.lastStatus.startsWith(prefix) && host.lastStatus.length() > prefix.length(), host.lastStatus);
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
        ops.trusted = true; // a Maven folder is gated on its first build (review B10)
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

    /**
     * A Maven root with no wrapper is gated too (review B10): the pom's plugins run on the user's own
     * {@code mvn} just the same, so the first build asks once, listing them, and remembers the project root.
     */
    @Test
    void aMavenRootWithNoWrapperAsksOnceListingThePomsPlugins(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        ops.promptAnswer = true;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM); // no mvnw
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "pom.xml parses");
        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

        assertEquals(1, ops.promptCount, "a pom's plugins are repository code: the first build asks");
        assertNull(ops.promptedWrapper, "nothing but the pom to name");
        assertEquals(
                dir.toAbsolutePath().normalize(),
                ops.promptedRoot.toAbsolutePath().normalize());
        assertFalse(ops.promptedSummary.items().isEmpty(), "the prompt says what will run");
        assertEquals(1, ops.trustCount, "accepting records the folder");
        assertEquals(1, ops.openConsoleCount, "and the run proceeds");

        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));
        assertEquals(1, ops.promptCount, "trusted now: the second build does not ask again");
    }

    /** A tool whose builds run nothing of the repository's (npm) is never gated. */
    @Test
    void anNpmRootIsNeverPrompted(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setNpmSupport(true);
        host.settings.setNpmCommand("editora-test-nonexistent-npm-binary-zzz");
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("package.json"), "{\"name\": \"demo\", \"scripts\": {\"build\": \"tsc\"}}");
        BuildCoordinator c = coordinator(BuildTool.NPM, host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "package.json parses");
        FxTestSupport.runOnFx(() -> c.runTask(List.of("run", "build"), List.of()));

        assertEquals(0, ops.promptCount, "argv[0] is the user's own tool and the manifest runs nothing itself");
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

    /**
     * A module of a multi-module build: the nearest pom.xml is the module's, the wrapper is the project
     * root's. The build must launch that wrapper (not fall back to {@code mvn} on PATH), and the trust
     * prompt must be about the folder that ships the script actually run — the project root.
     */
    @Test
    void aModuleBuildUsesAndGatesOnTheWrapperAtTheProjectRoot(@TempDir Path project) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = project;
        ops.promptAnswer = false;
        Files.writeString(project.resolve("pom.xml"), VALID_POM);
        writeMavenWrapper(project);
        Path module = Files.createDirectories(project.resolve("services").resolve("billing"));
        Files.writeString(module.resolve("pom.xml"), VALID_POM);
        Path source = Files.writeString(module.resolve("App.java"), "class App {}\n");
        host.active = FxTestSupport.callOnFx(() -> {
            com.editora.editor.EditorBuffer b = new com.editora.editor.EditorBuffer();
            b.setPath(source);
            return b;
        });
        try {
            BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
            FxTestSupport.runOnFx(c::refresh);
            waitUntil(
                    () -> module.equals(c.markerRoot()) && c.detectedLabel() != null,
                    "the module's pom.xml roots the tool");

            FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));

            assertEquals(1, ops.promptCount, "the root's wrapper is what would run, so it must be consented to");
            assertEquals(project, ops.promptedRoot, "trust is asked about the folder that ships the wrapper");
            assertEquals(project, ops.promptedWrapper.getParent());
            assertTrue(ops.promptedWrapper.getFileName().toString().startsWith("mvnw"));
            assertEquals(0, ops.openConsoleCount, "declined: nothing runs, and no quiet fallback to mvn");
        } finally {
            com.editora.editor.EditorBuffer active = host.active;
            FxTestSupport.runOnFx(active::dispose);
        }
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

        // Each run really forks the wrapper script, and runTask refuses to start while one is alive — so the
        // next run has to wait for the previous to exit or it is dropped by the busy guard, not the trust gate.
        runToCompletion(c, "compile");
        runToCompletion(c, "test");
        runToCompletion(c, "package");

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

        // Must finish before the rerun: rerunLast's busy guard returns ahead of the trust gate, so a still-live
        // first build would make the rerun skip the prompt for the wrong reason and pass the assertion below.
        runToCompletion(c, "compile");
        assertEquals(1, ops.promptCount);

        // Trust revoked between runs (Settings → Workspace): the remembered rerun must re-ask, not slip past.
        ops.trusted = false;
        ops.promptAnswer = false;
        FxTestSupport.runOnFx(c::rerunLast);

        assertEquals(2, ops.promptCount, "rerunLast goes through the same gate");
        assertEquals(1, ops.openConsoleCount, "and the declined rerun never ran");
    }

    private static Path script(Path file, String body) throws Exception {
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    /**
     * Test Results repeats a run where it ran. The marker root follows the active tab, so after opening a
     * file of another module the rerun used to launch that module's build instead.
     */
    @Test
    void runTaskAtLaunchesInTheGivenRootNotTheActiveTabsModule(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"), "needs a POSIX sh");
        Path moduleA = Files.createDirectories(project.resolve("module-a"));
        Path moduleB = Files.createDirectories(project.resolve("module-b"));
        Files.writeString(moduleA.resolve("pom.xml"), VALID_POM);
        Files.writeString(moduleB.resolve("pom.xml"), VALID_POM);
        Path cwdLog = project.resolve("cwd.log");
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand(
                script(project.resolve("fakemvn.sh"), "pwd >> '" + cwdLog + "'").toString());
        FakeOps ops = new FakeOps();
        ops.trusted = true; // a Maven folder is gated on its first build (review B10)
        ops.projectRoot = moduleB; // what the active tab now resolves to
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> moduleB.equals(c.markerRoot()), "module-b is the detected root");

        FxTestSupport.runOnFx(() -> c.runTaskAt(moduleA, List.of("test"), List.of()));
        waitUntil(() -> !c.isRunning() && Files.exists(cwdLog), "the rerun finished");

        assertEquals(
                List.of(moduleA.toRealPath().toString()),
                Files.readAllLines(cwdLog).stream()
                        .map(l -> uncheckedRealPath(Path.of(l)))
                        .toList());
    }

    /**
     * The Maven submenu on {@code moduleA/pom.xml} opens the actions popup <em>for module A</em> while the
     * editor sits in module B, and a task picked there runs in A. The popup used to be the active tab's
     * (B's), whatever row the menu was opened on.
     */
    @Test
    void showActionsAtOpensThePopupForTheGivenRootAndItsTasksRunThere(@TempDir Path project) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"), "needs a POSIX sh");
        Path moduleA = Files.createDirectories(project.resolve("module-a"));
        Path moduleB = Files.createDirectories(project.resolve("module-b"));
        Files.writeString(moduleA.resolve("pom.xml"), VALID_POM);
        Files.writeString(moduleB.resolve("pom.xml"), VALID_POM);
        Path cwdLog = project.resolve("cwd.log");
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand(
                script(project.resolve("fakemvn.sh"), "pwd >> '" + cwdLog + "'").toString());
        FakeOps ops = new FakeOps();
        ops.trusted = true; // a Maven folder is gated on its first build (review B10)
        ops.projectRoot = moduleB;
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> moduleB.equals(c.markerRoot()), "module-b is the detected root");

        FxTestSupport.runOnFx(() -> c.showActionsAt(moduleA));
        waitUntil(() -> moduleA.equals(FxTestSupport.field(c, "popupRoot")), "the popup is A's");
        BuildActionsPopup popup = FxTestSupport.field(c, "popup");
        BuildActionsPopup.RunHandler onRun = FxTestSupport.field(popup, "onRun");
        FxTestSupport.runOnFx(() -> onRun.run(List.of("test"), List.of()));
        String started = host.lastStatus; // set synchronously by launch, before the fake mvn exits
        waitUntil(() -> !c.isRunning() && Files.exists(cwdLog), "the run finished");
        assertEquals(
                List.of(moduleA.toRealPath().toString()),
                Files.readAllLines(cwdLog).stream()
                        .map(l -> uncheckedRealPath(Path.of(l)))
                        .toList());
        assertTrue(started.contains("module-a"), "the status names the folder it ran in: " + started);

        // The active tab's popup is back to the active tab's module.
        FxTestSupport.runOnFx(c::showActionsPopup);
        assertNull(FxTestSupport.field(c, "popupRoot"));
    }

    private static String uncheckedRealPath(Path p) {
        try {
            return p.toRealPath().toString();
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    /**
     * "Load all tasks…" takes Gradle about 90 s. Its result was dropped whenever anything re-detected the
     * project meanwhile — a tab switch, a save, the window regaining focus — although the project was the same.
     */
    @Test
    void loadedGradleTasksSurviveARefreshOfTheSameProjectDuringTheLoad(@TempDir Path dir) throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(
                System.getProperty("os.name", "").toLowerCase().contains("win"), "needs a POSIX sh");
        Files.writeString(dir.resolve("build.gradle"), "plugins { id 'java' }\n");
        Path fake = Files.createTempFile("fakegradle", ".sh"); // outside the project: not a repo wrapper
        try {
            script(
                    fake,
                    "sleep 1\ncat <<'EOT'\nBuild tasks\n-----------\nassemble - Assembles the outputs.\n"
                            + "customDeploy - Deploys.\n\nEOT");
            FakeHost host = new FakeHost();
            host.settings.setGradleSupport(true);
            host.settings.setGradleCommand(fake.toString());
            FakeOps ops = new FakeOps();
            ops.trusted = true; // a Maven folder is gated on its first build (review B10)
            ops.projectRoot = dir;
            BuildCoordinator c = coordinator(BuildTool.GRADLE, host, ops);
            FxTestSupport.runOnFx(c::refresh);
            waitUntil(c::isDetected, "the Gradle project is detected");

            FxTestSupport.runOnFx(() -> FxTestSupport.call(c, "loadAllTasks", new Class<?>[] {}));
            FxTestSupport.runOnFx(c::refresh); // what a tab switch / save / focus-regain does
            waitUntil(
                    () -> !((List<?>) FxTestSupport.field(c, "loadedTasks")).isEmpty(),
                    "the enumeration is kept, not discarded");

            List<String> loaded = FxTestSupport.field(c, "loadedTasks");
            assertTrue(loaded.contains("assemble") && loaded.contains("customDeploy"), loaded.toString());
            assertEquals(tr("status.build.loadedTasks", disp(BuildTool.GRADLE), loaded.size()), host.lastStatus);
        } finally {
            Files.deleteIfExists(fake);
        }
    }

    /**
     * Runs one task and waits for its process to die.
     *
     * <p>The wrapper fixture is a real {@code #!/bin/sh\nexit 0} script, so a run is a genuine fork whose exit
     * lands asynchronously — and {@code runTask}/{@code rerunLast} both refuse to start while one is alive.
     * Firing them back to back therefore raced: on an idle machine the {@code sh} exited within the FX
     * round-trip and every run proceeded, but under CI load the busy guard swallowed one, which surfaced as an
     * off-by-one on the prompt/console counters ("expected 3 but was 2") — a flake that reached master.
     */
    private static void runToCompletion(BuildCoordinator c, String task) throws Exception {
        FxTestSupport.runOnFx(() -> c.runTask(List.of(task), List.of()));
        waitUntil(() -> !c.isRunning(), "the '" + task + "' build finished");
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

    // --- Maven pass 2: tree group (B8 one toggle set, B11 projects, B14, B15, B21, B32) ---

    private static final String PROFILES_POM = """
            <project>
              <groupId>com.example</groupId>
              <artifactId>profiled</artifactId>
              <version>1.0.0</version>
              <profiles>
                <profile><id>dev</id><activation><activeByDefault>true</activeByDefault></activation></profile>
                <profile><id>ci</id><activation><property><name>env.CI</name></property></activation></profile>
              </profiles>
            </project>
            """;

    /** The tree and the popup read one toggle set per root, and Run/Debug read its argv through it. */
    @Test
    void theTreeAndThePopupShareOneToggleSetPerRoot(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), PROFILES_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "pom.xml parses");

        BuildActionsTree tree = FxTestSupport.callOnFx(c::tasksPanel);
        FxTestSupport.runOnFx(() -> tree.activeToggles().add("dev"));
        assertEquals(List.of("-Pdev"), FxTestSupport.callOnFx(c::activeToggleArgs), "the accessor Run and Debug use");

        FxTestSupport.runOnFx(c::showActionsPopup);
        BuildActionsPopup popup = FxTestSupport.field(c, "popup");
        assertSame(tree.activeToggles(), popup.activeToggles(), "the popup opens on the same set, not an empty one");
        FxTestSupport.runOnFx(() -> popup.activeToggles().add("!ci"));
        assertEquals(List.of("-Pdev,!ci"), FxTestSupport.callOnFx(c::activeToggleArgs), "an explicitly-off profile");

        // Re-detecting the same pom (a save, a tab switch) keeps the set; the tree is handed the same one.
        FxTestSupport.runOnFx(c::refreshMarker);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "re-detected");
        assertEquals(List.of("-Pdev,!ci"), FxTestSupport.callOnFx(c::activeToggleArgs));
        assertSame(tree.activeToggles(), popup.activeToggles());
    }

    /** Simple UI mode gets its own message: the Settings toggle is on, so "enable it in Settings" was wrong. */
    @Test
    void simpleModeReportsItselfNotTheSettingsToggle() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.simpleMode = true;
        FakeOps ops = new FakeOps();
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::showActionsPopup);
        assertEquals(tr("status.build.simpleMode", disp(BuildTool.MAVEN)), host.lastStatus);
        FxTestSupport.runOnFx(() -> c.runTask(List.of("clean"), List.of()));
        assertEquals(tr("status.build.simpleMode", disp(BuildTool.MAVEN)), host.lastStatus);
    }

    /** A pom that does not parse still offers the lifecycle (mvn says what is wrong), and the tree shows why. */
    @Test
    void aMalformedPomKeepsTheLifecycleAndShowsTheReasonWithALinkToTheFile(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), MALFORMED_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(c::isMalformed, "the broken pom is detected as such");
        assertNull(c.detectedLabel(), "no artifactId to show");

        BuildActionsTree tree = FxTestSupport.callOnFx(c::tasksPanel);
        javafx.scene.control.TreeView<?> view = FxTestSupport.field(tree, "tree");
        assertNotNull(FxTestSupport.callOnFx(view::getRoot), "the lifecycle section is still there");
        javafx.scene.layout.VBox notice = FxTestSupport.field(tree, "notice");
        javafx.scene.control.Label text = FxTestSupport.field(tree, "noticeLabel");
        javafx.scene.control.Hyperlink link = FxTestSupport.field(tree, "noticeLink");
        assertTrue(FxTestSupport.callOnFx(notice::isVisible), "the reason is shown in the window itself");
        String prefix = tr("status.build.malformedAt", disp(BuildTool.MAVEN), "");
        assertTrue(text.getText().startsWith(prefix) && text.getText().length() > prefix.length(), text.getText());
        assertEquals(tr("buildtree.openBuildFile", "pom.xml"), link.getText());
        FxTestSupport.runOnFx(link::fire);
        assertEquals(dir.resolve("pom.xml"), ops.opened, "the link opens the build file");

        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        FxTestSupport.runOnFx(c::refreshMarker);
        waitUntil(() -> c.detectedLabel() != null, "fixed: parses again");
        assertFalse(c.isMalformed());
        assertFalse(FxTestSupport.callOnFx(notice::isVisible), "and the notice is gone");
    }

    /** A workspace whose poms sit below the root lists them; picking one switches the detected root. */
    @Test
    void projectsBelowTheRootAreListedAndSelectable(@TempDir Path dir) throws Exception {
        Path backend = Files.createDirectories(dir.resolve("backend"));
        Path billing = Files.createDirectories(dir.resolve("services").resolve("billing"));
        Files.writeString(backend.resolve("pom.xml"), VALID_POM);
        Files.writeString(billing.resolve("pom.xml"), VALID_POM.replace("demo-app", "billing"));
        Files.writeString(dir.resolve("notes.txt"), "");
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir; // the Welcome tab: nothing active, the project root is the context
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> FxTestSupport.<List<Path>>field(c, "projectRoots").size() == 2, "two roots found below");
        assertNull(c.markerRoot(), "nothing detected for the root itself");

        BuildActionsTree tree = FxTestSupport.callOnFx(c::tasksPanel);
        javafx.scene.control.TreeView<?> view = FxTestSupport.field(tree, "tree");
        assertNotNull(FxTestSupport.callOnFx(view::getRoot), "the Projects section is shown even with no provider");
        assertEquals(List.of(backend, billing), FxTestSupport.<List<Path>>field(tree, "projects"));

        FxTestSupport.runOnFx(() -> c.selectRoot(billing));
        waitUntil(
                () -> billing.equals(c.markerRoot()) && "billing".equals(c.detectedLabel()), "billing is the root now");
        assertTrue(ops.lastVisible, "and its stripe is up");

        // The active file changing lets the file lead again.
        Path source = Files.writeString(backend.resolve("App.java"), "class App {}\n");
        host.active = FxTestSupport.callOnFx(() -> {
            com.editora.editor.EditorBuffer b = new com.editora.editor.EditorBuffer();
            b.setPath(source);
            return b;
        });
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> backend.equals(c.markerRoot()), "the active file's module");
    }

    /** The started status always names the folder (Rerun Last may run where no stripe shows). */
    @Test
    void theStartedStatusAlwaysNamesTheRoot(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");
        FakeOps ops = new FakeOps();
        ops.trusted = true;
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        BuildCoordinator c = coordinator(BuildTool.MAVEN, host, ops);
        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.isDetected() && c.detectedLabel() != null, "pom.xml parses");
        FxTestSupport.runOnFx(() -> c.runTask(List.of("compile"), List.of()));
        // launch() states it before the bogus command's synchronous start failure overwrites it.
        assertTrue(
                host.statuses.contains(tr(
                        "status.build.startedIn",
                        disp(BuildTool.MAVEN),
                        "compile",
                        dir.getFileName().toString())),
                host.statuses.toString());
    }

    /** Settings' project row and Doctor show what a build from the detected root runs as. */
    @Test
    void theExecutableSummaryNamesTheWrapperAndItsTrust(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        FakeOps ops = new FakeOps();
        BuildCoordinator c = detectedWithWrapper(host, ops, dir);
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("win");
        String name = windows ? "./mvnw.cmd" : "./mvnw";
        assertEquals(
                tr("settings.buildTools.exec.wrapperUntrusted", name), FxTestSupport.callOnFx(c::executableSummary));
        ops.trusted = true;
        assertEquals(tr("settings.buildTools.exec.wrapperTrusted", name), FxTestSupport.callOnFx(c::executableSummary));

        FakeHost plain = new FakeHost();
        plain.settings.setMavenSupport(true);
        FakeOps plainOps = new FakeOps();
        Path other = Files.createDirectories(dir.resolve("plain"));
        plainOps.projectRoot = other;
        Files.writeString(other.resolve("pom.xml"), VALID_POM);
        BuildCoordinator p = coordinator(BuildTool.MAVEN, plain, plainOps);
        FxTestSupport.runOnFx(p::refresh);
        waitUntil(() -> p.isDetected() && p.detectedLabel() != null, "pom.xml parses");
        assertEquals(tr("settings.buildTools.exec.onPath", "mvn"), FxTestSupport.callOnFx(p::executableSummary));
        plain.settings.setMavenCommand("/opt/maven/bin/mvn");
        assertEquals(
                tr("settings.buildTools.exec.override", "/opt/maven/bin/mvn"),
                FxTestSupport.callOnFx(p::executableSummary));
    }
}
