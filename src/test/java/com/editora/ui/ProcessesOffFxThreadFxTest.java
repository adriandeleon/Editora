package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import javafx.application.Platform;

import com.editora.build.BuildTool;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.git.GitService;
import com.editora.perf.BlockingSites;
import com.editora.process.ProcessRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PERF-01 (Pauta 6.0.1), the programs Editora starts: Run, Build, the debugged program, Run in Terminal, Git,
 * gpg, the Doctor's probes and the rest start, wait for and plan their processes off the FX thread. Each test
 * drives a representative set of those paths on the FX thread, the way a command does, and listens to
 * {@link BlockingSites}: the process start ({@code BusyExecutable}), the PATH lookup, a wait for a process, the
 * login shell's PATH, the process ledger's write, the walk of the process table, a pseudo-terminal's start, the
 * reads that plan a build or a launch. None may be heard on the FX thread, and each must be heard on its own.
 *
 * <p>What it cannot see: blocking at a place that does not name itself (it checks the named places, not every
 * file call — the small stats Run and Debug still make while they plan, listed in {@code docs/performance.md},
 * are not named); a debug launch other than Java's (the debugged program's start is shared, see
 * {@code DebugProgramConsoleFxTest}); a real container or WSL distribution (a mapped folder stands in, and its
 * commands run here); a real gpg (a stand-in that lists no keys); the Doctor's rows that a missing tool skips;
 * and the paths outside this change (file commands, per-keystroke checks, plugin and server installs, the
 * language servers).
 */
@Tag("fx")
class ProcessesOffFxThreadFxTest {

    /** Every {@link BlockingSites#enter} heard: the site, the thread's name, and whether it was the FX thread. */
    private record Heard(String site, String thread, boolean fx) {}

    private final List<Heard> heard = new CopyOnWriteArrayList<>();
    private FxWindowFixture fx;
    private BuildCoordinator build;

    @TempDir
    Path dir;

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @AfterEach
    void tearDown() throws Exception {
        BlockingSites.listenForTest(null);
        ProcessRegistry.setLedgerFile(null);
        if (build != null) {
            BuildCoordinator b = build;
            FxTestSupport.runOnFx(b::shutdown);
        }
        if (fx != null) {
            fx.dispose();
        }
    }

    private static boolean windows() {
        return com.editora.Os.isWindows();
    }

    private void listen() {
        BlockingSites.listenForTest(
                (site, thread) -> heard.add(new Heard(site, thread.getName(), Platform.isFxApplicationThread())));
    }

    /** Stops listening and checks that nothing was heard on the FX thread. */
    private void assertNothingOnFx() {
        BlockingSites.listenForTest(null);
        List<String> onFx = new ArrayList<>();
        for (Heard h : heard) {
            if (h.fx()) {
                onFx.add(h.site());
            }
        }
        assertEquals(List.of(), onFx, "a program started, waited for or planned on the FX thread; heard: " + heard);
    }

    private void assertHeardOn(String site, String thread) {
        assertTrue(
                heard.stream().anyMatch(h -> h.site().equals(site) && h.thread().startsWith(thread)),
                site + " ran on " + thread + "; heard: " + heard);
    }

    private static Path executable(Path file, String text) throws Exception {
        Files.writeString(file, text);
        assertTrue(file.toFile().setExecutable(true));
        return file;
    }

    @Test
    void runRunInTerminalAndStopStartNothingOnTheFxThread() throws Exception {
        Path project = Files.createDirectories(dir.resolve("project"));
        Path hello = Files.writeString(project.resolve("Hello.java"), "void main() {\n    IO.println(\"hi\");\n}\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {
            shared.getSettings().setMavenJdkHome(System.getProperty("java.home"));
        });
        ProcessRegistry.setLedgerFile(dir.resolve("spawned-servers.txt")); // so the ledger is written
        RunCoordinator run = fx.controller.runCoordinator;
        listen();

        // Run on a Java file: the version probe and the program, each on its own thread.
        FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(hello, 0));
        awaitActive(hello);
        FxTestSupport.runOnFx(run::runActiveFile);
        SettingsRig.awaitFx(
                "the Java run started and ended",
                () -> heard.stream().anyMatch(h -> h.thread().equals("run-start")) && !run.isRunning());
        assertHeardOn("process start", "run-start");
        assertHeardOn("ProcessRegistry.writeLedger", "run-start");
        assertHeardOn("RunCoordinator.planFile", "run-file-plan"); // the file's plan, read before the start

        if (!windows()) {
            // Run on a script, then a long one stopped while it runs: the kill walks the process table off FX.
            Path script = executable(project.resolve("hello.sh"), "#!/bin/sh\necho hi\n");
            Path sleeper = executable(project.resolve("sleeper.sh"), "#!/bin/sh\nsleep 30\n");
            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(script, 0));
            awaitActive(script);
            int before = starts();
            FxTestSupport.runOnFx(run::runActiveFile);
            SettingsRig.awaitFx("the script run ended", () -> starts() > before && !run.isRunning());

            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(sleeper, 0));
            awaitActive(sleeper);
            int beforeSleep = starts();
            FxTestSupport.runOnFx(run::runActiveFile);
            SettingsRig.awaitFx("the long run started", () -> starts() > beforeSleep && run.isRunning());
            FxTestSupport.runOnFx(run::stopRun);
            SettingsRig.awaitFx("the long run was stopped", () -> !run.isRunning());
            assertHeardOn("ProcessRegistry.descendants", "editora-process-reaper");

            // Run in Terminal: the program's pseudo-terminal opens off the FX thread.
            AtomicReference<Integer> exit = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> {
                try {
                    fx.controller.terminalCoordinator.runProgram(
                            "guard", project, List.of("/bin/sh", "-c", "echo hi"), Map.of(), exit::set);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            SettingsRig.awaitFx("the terminal program ended", () -> exit.get() != null);
            assertHeardOn("LocalShell.startProgram", "terminal-connect");
        }
        FxTestSupport.drainFx();
        assertNothingOnFx();
    }

    @Test
    void aBuildLooksUpItsWrapperAndTrustAndStartsOffTheFxThread() throws Exception {
        Path maven = Files.createDirectories(dir.resolve("maven"));
        Files.writeString(maven.resolve("pom.xml"), "<project><artifactId>guard</artifactId></project>");
        Path gradle = Files.createDirectories(dir.resolve("gradle"));
        Files.writeString(gradle.resolve("settings.gradle"), "rootProject.name = 'guard'\n");
        Files.writeString(gradle.resolve("build.gradle"), "\n");
        if (windows()) {
            Files.writeString(maven.resolve("mvnw.cmd"), "@echo off\r\necho built\r\n");
            Files.writeString(gradle.resolve("gradlew.bat"), "@echo off\r\necho built\r\n");
        } else {
            executable(maven.resolve("mvnw"), "#!/bin/sh\necho built\n");
            executable(gradle.resolve("gradlew"), "#!/bin/sh\necho built\n");
        }
        for (Object[] each : new Object[][] {{BuildTool.MAVEN, maven}, {BuildTool.GRADLE, gradle}}) {
            BuildTool tool = (BuildTool) each[0];
            Path root = (Path) each[1];
            BuildHost host = new BuildHost();
            host.settings.setMavenSupport(true);
            host.settings.setGradleSupport(true);
            BuildOps ops = new BuildOps(root);
            build = FxTestSupport.callOnFx(() -> new BuildCoordinator(tool, host, ops, new BuildOutput()));
            heard.clear();
            listen();
            BuildCoordinator c = build;
            FxTestSupport.runOnFx(() -> c.runTaskAt(root, List.of("compile"), List.of()));
            SettingsRig.awaitFx(
                    tool + " ran",
                    () -> heard.stream().anyMatch(h -> h.thread().equals("build-start")) && !c.isRunning());
            assertEquals(1, ops.asked, tool + ": the trust prompt was asked once, from the plan");
            assertHeardOn("BuildCoordinator.plan", "build-plan");
            assertHeardOn("process start", "build-start");
            FxTestSupport.drainFx();
            assertNothingOnFx();
            FxTestSupport.runOnFx(c::shutdown);
            build = null;
        }
    }

    @Test
    void gitGpgDoctorAndMavenAskNothingOfTheFxThread() throws Exception {
        Path project = Files.createDirectories(dir.resolve("repo"));
        Path pom = Files.writeString(project.resolve("pom.xml"), "<project><artifactId>bare</artifactId></project>");
        Path gpg = windows() ? null : executable(dir.resolve("gpg-stand-in"), "#!/bin/sh\nexit 0\n");
        fx = FxWindowFixture.createWithFileWorkOffThread(Files.createDirectories(dir.resolve("config")), shared -> {
            shared.getSettings().setMavenSupport(true);
            if (gpg != null) {
                shared.getSettings().setEncryptionGpgPath(gpg.toString());
            }
        });
        listen();

        // Git: a service that never probed answers on the FX thread without running git there.
        GitService fresh = new GitService();
        FxTestSupport.runOnFx(fresh::gitAvailable);
        SettingsRig.awaitFx(
                "the git probe", () -> heard.stream().anyMatch(h -> h.thread().equals("git-version-probe")));

        // Git Log of a repository: its shallow check and its git commands off the FX thread.
        java.util.concurrent.atomic.AtomicBoolean logged = new java.util.concurrent.atomic.AtomicBoolean();
        if (gitInit(project)) {
            Path file = Files.writeString(project.resolve("a.txt"), "a\n");
            FxTestSupport.runOnFx(() -> fx.controller.openAndNavigate(file, 0));
            awaitActive(file);
            SettingsRig.awaitFx("the repository", () -> fx.controller.git.repoRoot() != null);
            FxTestSupport.runOnFx(() -> fx.controller.gitWindows.loadGitLog(null));
            SettingsRig.awaitFx(
                    "the Git Log's shallow check",
                    () -> heard.stream().anyMatch(h -> h.site().equals("GitWindowCoordinator.shallow")));
            logged.set(true);
        }

        // Maven Update Versions on a pom with nothing pinned: read and parsed off the FX thread, no network.
        FxTestSupport.runOnFx(() -> fx.controller.mavenProjectCoordinator.updateVersions(pom));
        SettingsRig.awaitFx(
                "the pom read", () -> heard.stream().anyMatch(h -> h.site().equals("Maven update versions plan")));

        // gpg: the keyring is listed by a stand-in, off the FX thread.
        if (gpg != null) {
            AtomicReference<Object> keys = new AtomicReference<>();
            FxTestSupport.runOnFx(() -> fx.controller.fileWorkflows.encryption().withKeys(keys::set));
            SettingsRig.awaitFx("the gpg stand-in", () -> keys.get() != null);
            assertHeardOn("process start", "gpg-keys");
        }

        // The Doctor: its probes run on their pool.
        FxTestSupport.runOnFx(fx.controller.doctorCoordinator::runChecks);
        SettingsRig.awaitFx(
                "the Doctor's run",
                () -> !fx.controller.doctorCoordinator.pane().isRunning());

        FxTestSupport.drainFx();
        assertNothingOnFx();
        assertTrue(heard.stream().anyMatch(h -> h.site().equals("process start")), "programs did start: " + heard);
        if (logged.get()) {
            assertHeardOn("GitWindowCoordinator.shallow", "git-log-shallow");
        }
    }

    @Test
    void aProgramForAFolderThatRunsElsewhereStartsOffTheFxThread() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(windows(), "the stand-in target is a symlink");
        Path folder = Files.createDirectories(dir.toRealPath().resolve("app"));
        com.editora.process.ExecTargets.setRemoteEnabled(() -> true);
        com.editora.process.MappedTestTarget target = com.editora.process.MappedTestTarget.register(
                folder, dir.toRealPath().resolve("mounted"));
        com.editora.run.RunService service = new com.editora.run.RunService();
        java.util.concurrent.CountDownLatch ended = new java.util.concurrent.CountDownLatch(1);
        List<String> out = new CopyOnWriteArrayList<>();
        try {
            listen();
            FxTestSupport.runOnFx(() -> service.runInDir(
                    folder, List.of("/bin/sh", "-c", "echo elsewhere"), new com.editora.run.RunService.Listener() {
                        @Override
                        public void onStart(String commandLine) {}

                        @Override
                        public void onOutput(String line, boolean stderr) {
                            out.add(line);
                        }

                        @Override
                        public void onExit(int code) {
                            ended.countDown();
                        }

                        @Override
                        public void onError(String message) {
                            out.add("error: " + message);
                            ended.countDown();
                        }
                    }));
            assertTrue(ended.await(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(List.of("elsewhere"), out);
            assertEquals(1, target.started().size(), "it started on the folder's target");
            FxTestSupport.drainFx();
            assertNothingOnFx();
            assertHeardOn("process start", "run-start");
        } finally {
            FxTestSupport.runOnFx(service::shutdown);
            target.unregister();
            com.editora.process.ExecTargets.setRemoteEnabled(null);
        }
    }

    /** Process starts heard so far. */
    private int starts() {
        return (int)
                heard.stream().filter(h -> h.site().equals("process start")).count();
    }

    private void awaitActive(Path file) throws Exception {
        SettingsRig.awaitFx("the tab of " + file.getFileName(), () -> {
            EditorBuffer active = fx.controller.activeBuffer();
            return active != null && file.equals(active.getPath());
        });
    }

    /** {@code git init} and one commit in {@code root}, off the FX thread; false when git is not installed. */
    private static boolean gitInit(Path root) {
        try {
            for (List<String> argv : List.of(
                    List.of("git", "init", "-q"),
                    List.of(
                            "git",
                            "-c",
                            "user.name=t",
                            "-c",
                            "user.email=t@t",
                            "commit",
                            "-q",
                            "--allow-empty",
                            "-m",
                            "first"))) {
                Process p = new ProcessBuilder(argv)
                        .directory(root.toFile())
                        .redirectErrorStream(true)
                        .start();
                p.getInputStream().readAllBytes();
                if (p.waitFor() != 0) {
                    return false;
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static final class BuildHost extends CoordinatorHostStub {
        final Settings settings = new Settings();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public ProjectScope projectScope() {
            return ProjectScope.NONE;
        }
    }

    /** Trusts the folder when asked, and counts the asks. */
    private static final class BuildOps implements BuildCoordinator.Ops {
        private final Path root;
        int asked;
        boolean trusted;

        BuildOps(Path root) {
            this.root = root;
        }

        @Override
        public Path projectRoot() {
            return root;
        }

        @Override
        public void openTasks() {}

        @Override
        public void openConsole() {}

        @Override
        public void setToolWindowsAvailable(boolean available) {}

        @Override
        public boolean isTrusted(Path folder) {
            return trusted;
        }

        @Override
        public boolean confirmTrust(Path folder, com.editora.build.BuildTrust.Summary summary) {
            asked++;
            assertFalse(summary.items().isEmpty(), "the prompt lists what will run");
            return true;
        }

        @Override
        public void trust(Path folder) {
            trusted = true;
        }
    }
}
