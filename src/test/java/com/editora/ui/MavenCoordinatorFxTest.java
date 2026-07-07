package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.editora.config.Settings;
import com.editora.run.StackTraceLinks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Characterization tests for {@link MavenCoordinator}, exercised against a fake {@link CoordinatorHost}
 * (via {@link CoordinatorHostStub}) plus a fake {@link MavenCoordinator.Ops}. Pins the Settings/Simple-Mode
 * gating, the async pom.xml detection (a real temp {@code pom.xml} on disk, polled after {@link
 * MavenCoordinator#refresh()}), the malformed-pom distinction, disabled/unavailable command reporting, and
 * that a launch attempt actually reaches {@code MavenService} (a deliberately bogus command so it fails
 * fast on {@code ProcessBuilder.start()} instead of spawning a real build).
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MavenCoordinatorFxTest {

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

    private static final class FakeOps implements MavenCoordinator.Ops {
        Path projectRoot;
        int openConsoleCount;
        int toolbarVisibleCount;
        boolean lastVisible;
        StackTraceLinks.Link lastLink;

        @Override
        public Path projectRoot() {
            return projectRoot;
        }

        @Override
        public void openConsole() {
            openConsoleCount++;
        }

        @Override
        public void onOutputLink(StackTraceLinks.Link link) {
            lastLink = link;
        }

        @Override
        public void setToolbarButtonVisible(boolean visible) {
            toolbarVisibleCount++;
            lastVisible = visible;
        }
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

    @Test
    void isEnabledTracksSettingAndSimpleMode() {
        FakeHost host = new FakeHost();
        MavenCoordinator c = new MavenCoordinator(host, new FakeOps());
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
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(c::refresh);
        assertFalse(c.hasPom(), "disabled → never even looks for a pom.xml");
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
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.hasPom(), "pom.xml detection");

        assertEquals("demo-app", c.detectedArtifactId());
        assertTrue(ops.lastVisible, "toolbar button shown once a pom.xml resolves");
    }

    @Test
    void refreshWithNoPomLeavesTheToolbarButtonHidden(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir; // empty — no pom.xml anywhere in this ancestry
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(c::refresh);
        // Give the (short-lived) detect thread a moment even though nothing should ever flip true.
        Thread.sleep(300);
        assertFalse(FxTestSupport.callOnFx(c::hasPom));
        assertNull(FxTestSupport.callOnFx(c::detectedArtifactId));
    }

    @Test
    void malformedPomIsTrackedDistinctlyFromNoPom(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), MALFORMED_POM);
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.hasPom(), "malformed pom.xml is still 'found'");
        assertNull(c.detectedArtifactId(), "but never parses to a model");

        FxTestSupport.runOnFx(() -> c.showActionsPopup(null));
        assertEquals(tr("status.maven.malformedPom"), host.lastStatus);
    }

    @Test
    void disabledCommandsReportAndNeverOpenTheConsole() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(false);
        FakeOps ops = new FakeOps();
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(() -> {
            c.showActionsPopup(null);
            assertEquals(tr("statusbar.tip.mavenDisabled"), host.lastStatus);

            c.runGoals(List.of("clean"), List.of());
            assertEquals(tr("statusbar.tip.mavenDisabled"), host.lastStatus);

            c.runCustom();
            assertEquals(tr("statusbar.tip.mavenDisabled"), host.lastStatus);
        });
        assertEquals(0, ops.openConsoleCount, "disabled → a run never opens the console");
    }

    @Test
    void showActionsPopupWithNoPomReportsNoPom() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        MavenCoordinator c = new MavenCoordinator(host, new FakeOps());

        FxTestSupport.runOnFx(() -> c.showActionsPopup(null));
        assertEquals(tr("status.maven.noPom"), host.lastStatus);
    }

    @Test
    void rerunLastWithNothingPreviousReportsNoRerun() throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        MavenCoordinator c = new MavenCoordinator(host, new FakeOps());

        FxTestSupport.runOnFx(c::rerunLast);
        assertEquals(tr("status.maven.noRerun"), host.lastStatus);
    }

    @Test
    void runGoalsWithABogusCommandOpensTheConsoleAndReportsFailureFast(@TempDir Path dir) throws Exception {
        FakeHost host = new FakeHost();
        host.settings.setMavenSupport(true);
        host.settings.setMavenCommand("editora-test-nonexistent-maven-binary-zzz");
        FakeOps ops = new FakeOps();
        ops.projectRoot = dir;
        Files.writeString(dir.resolve("pom.xml"), VALID_POM);
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.hasPom() && c.detectedArtifactId() != null, "valid pom.xml parses");

        // No mvnw wrapper on disk, so the bogus override command is what actually gets launched — and
        // ProcessBuilder.start() throws synchronously (no such executable), so this never spawns a real
        // process or blocks on a build.
        FxTestSupport.runOnFx(() -> c.runGoals(List.of("compile"), List.of()));

        assertEquals(1, ops.openConsoleCount, "a run attempt opens the console even though it fails");
        String failedPrefix = tr("status.maven.failed", "");
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
        MavenCoordinator c = new MavenCoordinator(host, ops);

        FxTestSupport.runOnFx(c::refresh);
        waitUntil(() -> c.hasPom(), "pom.xml detected once");

        // Simulate entering Simple Mode: isEnabled() flips false, but nothing on disk changed.
        host.simpleMode = true;
        FxTestSupport.runOnFx(c::reapplyVisibility);
        assertFalse(ops.lastVisible, "Simple Mode hides the button even though a pom.xml is still cached");

        host.simpleMode = false;
        FxTestSupport.runOnFx(c::reapplyVisibility);
        assertTrue(ops.lastVisible, "leaving Simple Mode re-shows it from the cached detection, no re-detect");
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

    private static String tr(String key) {
        return com.editora.i18n.Messages.tr(key);
    }

    private static String tr(String key, Object arg) {
        return com.editora.i18n.Messages.tr(key, arg);
    }
}
