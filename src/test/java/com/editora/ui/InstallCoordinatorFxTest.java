package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;

import javafx.scene.control.Alert;

import com.editora.config.Settings;
import com.editora.install.InstallCatalog;
import com.editora.install.InstallCatalog.Lang;
import com.editora.install.InstallService;
import com.editora.install.InstallTestAccess;
import com.editora.io.LoopbackDownloads;
import com.editora.io.TestArchives;
import com.editora.process.ProcessRunner;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@link InstallCoordinator}'s flows from trigger to settled outcome: prerequisite check, the install on
 * the service's worker, and what the window is told afterwards — the command saved to Settings, the
 * re-detection, the status line, the error dialog. Downloads are answered by a loopback server and land in a
 * temp config dir; the error dialog is replaced through the coordinator's alert seam.
 *
 * <p>Only download-and-extract installs are driven. The npm/pip/toolchain installs run a real package
 * manager and are deliberately not triggered from a test.
 */
@Tag("fx")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InstallCoordinatorFxTest {

    @TempDir
    Path sandbox;

    @BeforeAll
    void setUp() throws Exception {
        FxTestSupport.bootToolkit();
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<String> statuses = new ArrayList<>();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void setStatus(String message) {
            statuses.add(message);
        }
    }

    private static final class Window implements InstallCoordinator.Ops {
        final Path configDir;
        final Set<String> lsp = new HashSet<>();
        final Set<String> dap = new HashSet<>();
        boolean typstCli;
        int redetected;

        Window(Path configDir) {
            this.configDir = configDir;
        }

        @Override
        public Path configDir() {
            return configDir;
        }

        @Override
        public boolean lspAvailable(String serverId) {
            return lsp.contains(serverId);
        }

        @Override
        public boolean dapAvailable(String language) {
            return dap.contains(language);
        }

        @Override
        public boolean mmdcAvailable() {
            return false;
        }

        @Override
        public boolean typstCliAvailable() {
            return typstCli;
        }

        @Override
        public void reapplyToolSupport() {
            redetected++;
        }
    }

    /** One coordinator with everything it talks to, torn down with the scope. */
    private final class Rig {
        final Host host = new Host();
        final Window window;
        final LoopbackDownloads web;
        final InstallService service;
        final InstallCoordinator coordinator;
        final List<String> alerts = new ArrayList<>();
        final List<Boolean> outcomes = new ArrayList<>();
        private final AsyncTestScope scope;

        Rig(AsyncTestScope scope, String name) throws Exception {
            this.scope = scope;
            window = new Window(Files.createDirectories(sandbox.resolve(name)));
            web = scope.own(new LoopbackDownloads());
            service = InstallTestAccess.service(web.client(), 1024 * 1024);
            coordinator = new InstallCoordinator(host, window, service);
            coordinator.setAlertForTest((type, message) -> alerts.add(type + ": " + message));
            scope.onClose(coordinator::shutdown);
        }

        /** Waits for the task the service's worker is on, then for what it posted to the FX thread. */
        void step() throws Exception {
            scope.awaitWorker(FxTestSupport.<ExecutorService>field(service, "exec"));
            scope.awaitFx();
        }

        /** Prerequisite probe, then the install it starts: both hops settled. */
        void settle() throws Exception {
            step();
            step();
        }

        void installServer(String id) throws Exception {
            FxTestSupport.runOnFx(() -> coordinator.installServer(id, outcomes::add));
        }

        Path dir(String sub) {
            return window.configDir.resolve(sub);
        }
    }

    private static boolean onPath(String tool) {
        return !ProcessRunner.resolveExecutable(List.of(tool)).get(0).equals(tool);
    }

    private static byte[] mavenBundle(String version) {
        return TestArchives.zip("lemminx.jar", version, "lemminx-maven.jar", version);
    }

    @Test
    void installingAServerSavesItsCommandRedetectsAndReportsSuccess() throws Exception {
        assumeTrue(onPath("java"), "the Maven pom.xml server needs java on PATH");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "ok");
            rig.web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, mavenBundle("1"));

            rig.installServer("maven-pom");
            rig.settle();

            assertEquals(List.of(true), rig.outcomes);
            assertEquals(List.of(), rig.alerts);
            Path dest = rig.dir("plugins/lsp/maven");
            assertEquals("1", Files.readString(dest.resolve("lemminx.jar")));
            assertEquals(
                    InstallCatalog.jvmClasspathCommand(dest, InstallCatalog.LEMMINX_MAIN_CLASS),
                    rig.host.settings.getMavenPomLspCommand(),
                    "the launch command is saved, so the server is found without a restart");
            assertEquals(1, rig.window.redetected);
            String name = tr("install.lang.maven-pom");
            assertEquals(
                    List.of(
                            tr("status.install.installing", name),
                            tr("status.install.installingTool", "lemminx-maven"),
                            tr("status.install.done", name)),
                    rig.host.statuses);
        }
    }

    @Test
    void aFailedInstallShowsTheReasonChangesNothingAndCanBeRetried() throws Exception {
        assumeTrue(onPath("java"), "the Maven pom.xml server needs java on PATH");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "failed");
            rig.web.status(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, 404);

            rig.installServer("maven-pom");
            rig.settle();

            assertEquals(List.of(false), rig.outcomes);
            assertEquals(
                    List.of(Alert.AlertType.ERROR + ": HTTP 404 for " + InstallCatalog.LEMMINX_MAVEN_ZIP_URL),
                    rig.alerts);
            assertEquals("", rig.host.settings.getMavenPomLspCommand(), "no command for a server that is not there");
            assertEquals(0, rig.window.redetected);
            assertEquals(
                    tr("status.install.failed", tr("install.lang.maven-pom")),
                    rig.host.statuses.get(rig.host.statuses.size() - 1));
            assertFalse(Files.exists(rig.dir("plugins")));

            // The failed run is over: a retry is a fresh install, not "already running".
            rig.web.serve(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, mavenBundle("1"));
            rig.installServer("maven-pom");
            rig.settle();

            assertEquals(List.of(false, true), rig.outcomes);
            assertFalse(rig.host.settings.getMavenPomLspCommand().isEmpty());
        }
    }

    @Test
    void aSecondTriggerWhileAnInstallRunsIsTurnedAway() throws Exception {
        assumeTrue(onPath("java"), "the Maven pom.xml server needs java on PATH");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "busy");
            CountDownLatch downloading = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            scope.onClose(release::countDown);
            rig.web.serveWhenReleased(InstallCatalog.LEMMINX_MAVEN_ZIP_URL, mavenBundle("1"), downloading, release);

            rig.installServer("maven-pom");
            // Not rig.step(): the worker is about to be held by the download, and a barrier task queued
            // behind it would wait just as long.
            scope.await(downloading, "the install to reach its download");
            scope.awaitFx();

            rig.installServer("maven-pom");
            assertEquals(List.of(false), rig.outcomes, "the second request is answered at once");
            assertEquals(tr("status.install.inProgress"), rig.host.statuses.get(rig.host.statuses.size() - 1));

            release.countDown();
            rig.step();

            assertEquals(List.of(false, true), rig.outcomes);
            assertEquals(List.of(InstallCatalog.LEMMINX_MAVEN_ZIP_URL), rig.web.requests(), "downloaded once");
            assertEquals(1, rig.window.redetected);
        }
    }

    @Test
    void aServerThatIsAlreadyThereIsNotDownloadedAgain() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "present");
            rig.window.lsp.add("maven-pom");

            rig.installServer("maven-pom");
            rig.settle();

            assertEquals(List.of(true), rig.outcomes);
            assertEquals(List.of(tr("status.install.already", tr("install.lang.maven-pom"))), rig.host.statuses);
            assertEquals(List.of(), rig.web.requests());
            assertEquals(0, rig.window.redetected);
        }
    }

    @Test
    void aServerWithNoInstallerIsReportedAsNotInstalled() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "unknown");

            rig.installServer("cobol");
            rig.installServer(null);
            rig.settle();

            assertEquals(List.of(false, false), rig.outcomes);
            assertEquals(List.of(), rig.web.requests());
            assertEquals(List.of(), rig.alerts);
        }
    }

    /** Only what is missing is installed: with the Java server present, just the debug adapter is fetched. */
    @Test
    void languageSupportInstallsOnlyTheMissingTool() throws Exception {
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "debug-only");
            rig.window.lsp.add("java");
            String api = InstallCatalog.steps(Lang.JAVA).get(1).apiUrl();
            String vsix = "https://open-vsx.org/api/vscjava/vscode-java-debug/0.58.2/file/java-debug.vsix";
            String jar = "com.microsoft.java.debug.plugin-0.53.1.jar";
            rig.web.serve(api, "{\"files\":{\"download\":\"" + vsix + "\"}}");
            rig.web.serve(vsix, TestArchives.zip("extension/server/" + jar, "adapter"));
            assertFalse(rig.coordinator.isSupportInstalled(Lang.JAVA));

            FxTestSupport.runOnFx(() -> rig.coordinator.installSupport(Lang.JAVA, rig.outcomes::add));
            rig.settle();

            assertEquals(List.of(true), rig.outcomes);
            assertEquals(List.of(api, vsix), rig.web.requests(), "the language server was not downloaded again");
            assertEquals("adapter", Files.readString(rig.dir("plugins/dap/java/" + jar)));
            assertEquals(1, rig.window.redetected);
            assertEquals(List.of(), rig.alerts);

            // Once the window detects both, there is nothing left to do.
            rig.window.dap.add("java");
            assertTrue(rig.coordinator.isSupportInstalled(Lang.JAVA));
            FxTestSupport.runOnFx(() -> rig.coordinator.installSupport(Lang.JAVA, rig.outcomes::add));
            rig.settle();

            assertEquals(List.of(true, true), rig.outcomes);
            assertEquals(2, rig.web.requests().size());
            assertEquals(
                    tr("status.install.already", tr("install.lang.java")),
                    rig.host.statuses.get(rig.host.statuses.size() - 1));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS) // the tarball is extracted by a POSIX tar; the launcher is jdtls.bat there
    void installingJavaSupportPointsTheEditorAtTheExtractedLauncher() throws Exception {
        assumeTrue(onPath("tar"), "tar is not installed");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "java support");
            String api = InstallCatalog.steps(Lang.JAVA).get(1).apiUrl();
            String vsix = "https://open-vsx.org/api/vscjava/vscode-java-debug/0.58.2/file/java-debug.vsix";
            rig.web.serve(
                    InstallCatalog.JDTLS_TARBALL_URL,
                    TestArchives.tar()
                            .executable("bin/jdtls", "launcher")
                            .file("plugins/launcher.jar", "jar")
                            .gz());
            rig.web.serve(api, "\"" + vsix + "\"");
            rig.web.serve(
                    vsix, TestArchives.zip("extension/server/com.microsoft.java.debug.plugin-0.53.1.jar", "adapter"));

            FxTestSupport.runOnFx(() -> rig.coordinator.installSupport(Lang.JAVA, rig.outcomes::add));
            rig.settle();

            assertEquals(List.of(true), rig.outcomes, String.valueOf(rig.alerts));
            Path launcher = rig.dir("plugins/lsp/java/bin/jdtls");
            assertTrue(Files.isExecutable(launcher));
            assertEquals(
                    List.of(launcher.toString()),
                    com.editora.lsp.LspServerRegistry.commandFor(
                            "java", java.util.Map.of("java", rig.host.settings.getJavaLspCommand())),
                    "the saved command is the extracted launcher — one argument, though the folder has a space");
            assertEquals(1, rig.window.redetected);
            String java = tr("install.lang.java");
            assertEquals(
                    List.of(
                            tr("status.install.installing", java),
                            tr("status.install.installingTool", "jdtls"),
                            tr("status.install.installingTool", "java-debug"),
                            tr("status.install.done", java)),
                    rig.host.statuses);
        }
    }

    /** A failure in the first tool stops the run: no later download, nothing saved, nothing re-detected. */
    @Test
    @DisabledOnOs(OS.WINDOWS)
    void aLanguageInstallThatFailsPartWaySavesNothing() throws Exception {
        assumeTrue(onPath("tar"), "tar is not installed");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "half");
            rig.web.serve(
                    InstallCatalog.JDTLS_TARBALL_URL,
                    TestArchives.tar().file("README.md", "not the server").gz());

            FxTestSupport.runOnFx(() -> rig.coordinator.installSupport(Lang.JAVA, rig.outcomes::add));
            rig.settle();

            assertEquals(List.of(false), rig.outcomes);
            assertEquals(
                    List.of(Alert.AlertType.ERROR + ": jdtls: expected files not found after extraction"), rig.alerts);
            assertEquals("", rig.host.settings.getJavaLspCommand());
            assertEquals(0, rig.window.redetected);
            assertEquals(List.of(InstallCatalog.JDTLS_TARBALL_URL), rig.web.requests());
            assertFalse(Files.exists(rig.dir("plugins/lsp/java")));
            assertFalse(Files.exists(rig.dir("plugins/dap")));
        }
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    void installingTheTypstCliPointsThePreviewAtTheExtractedBinary() throws Exception {
        assumeTrue(onPath("tar"), "tar is not installed");
        try (AsyncTestScope scope = new AsyncTestScope()) {
            Rig rig = new Rig(scope, "typst");
            var spec = InstallCatalog.archiveSpec("typst-cli").orElseThrow();
            String mine = spec.assetByPlatform().get(InstallCatalog.currentPlatform());
            String asset = "https://github.com/typst/typst/releases/download/v0.14.0/" + mine + ".tar.xz";
            rig.web.serve(spec.apiUrl(), "{\"browser_download_url\":\"" + asset + "\"}");
            rig.web.serve(
                    asset, TestArchives.tar().executable(mine + "/typst", "ELF").gz());

            FxTestSupport.runOnFx(() -> rig.coordinator.installTypstCli(rig.outcomes::add));
            rig.settle();

            assertEquals(List.of(true), rig.outcomes, String.valueOf(rig.alerts));
            assertEquals(rig.dir("plugins/typst/" + mine + "/typst").toString(), rig.host.settings.getTypstPath());
            assertEquals(1, rig.window.redetected);

            rig.window.typstCli = true;
            FxTestSupport.runOnFx(() -> rig.coordinator.installTypstCli(rig.outcomes::add));
            rig.settle();
            assertEquals(List.of(true, true), rig.outcomes);
            assertEquals(2, rig.web.requests().size(), "an installed CLI is not downloaded again");
        }
    }
}
