package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import com.editora.config.ConfigManager;
import com.editora.config.Deployment;
import com.editora.config.RunConfiguration;
import com.editora.config.Settings;
import com.editora.deploy.Deployer;
import com.editora.editor.EditorBuffer;
import com.editora.i18n.Messages;
import com.editora.process.ExecTargets;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.StackTraceLinks;
import com.editora.vfs.EmbeddedSftpFixture;
import com.editora.vfs.RemoteConnection;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A local project deployed to a host and run there: the copy is brought up to date over the connection (one tar
 * stream), only what changed is sent again, only Editora's own files are removed, a file changed on the host is
 * asked about, and a configuration that runs on the deployment runs its before-launch step and its program in
 * the copy. The host is the embedded SSH server with a real {@code /bin/sh}.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class DeploymentFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private Path project;
    private Path served; // the deployment's folder as the host's files (the fixture is not chrooted)
    private ConfigManager config;
    private final List<String> status = new CopyOnWriteArrayList<>();
    private final List<String> edited = new CopyOnWriteArrayList<>();
    private DeploymentCoordinator deployments;
    private final Decisions decisions = new Decisions();

    /** Scripted answers to a deploy's questions, and what was asked. */
    private static final class Decisions implements Deployer.Decisions {
        volatile Conflicts conflictAnswer = Conflicts.CANCEL;
        volatile boolean overwriteAnswer;
        final List<List<String>> conflictsAsked = new CopyOnWriteArrayList<>();
        final List<List<String>> overwriteAsked = new CopyOnWriteArrayList<>();

        @Override
        public Conflicts conflicts(List<String> files) {
            conflictsAsked.add(files);
            return conflictAnswer;
        }

        @Override
        public boolean overwriteExisting(List<String> files) {
            overwriteAsked.add(files);
            return overwriteAnswer;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        served = sftp.serverPath("srv/app");
        project = Files.createDirectories(base.resolve("project"));
        write("run.sh", "#!/bin/sh\necho \"ran in $(pwd -P) with $1\"\n");
        Files.setPosixFilePermissions(project.resolve("run.sh"), PosixFilePermissions.fromString("rwxr-xr-x"));
        write("src/notes.txt", "notes");
        write("target/stale.jar", "built locally"); // left out: the host builds for itself
        write(".git-not-a-repo.txt", "kept");
        config = new ConfigManager(base.resolve("config"));
        config.getWorkspaceState().setDeployments(new ArrayList<>(List.of(deployment(served.toString()))));
        deployments = new DeploymentCoordinator(new Host());
        deployments.decisions = decisions;
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    private Deployment deployment(String root) {
        RemoteConnection c = sftp.connection();
        return new Deployment("dev", c.host(), c.port(), c.user(), c.auth(), "", root, "", true, true, false);
    }

    private void write(String relative, String text) throws Exception {
        Path file = project.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    private final class Host implements DeploymentCoordinator.Host {
        @Override
        public ConfigManager config() {
            return config;
        }

        @Override
        public Path projectRoot() {
            return project;
        }

        @Override
        public void hold(RemoteConnection conn, Consumer<Path> onReady) {
            onReady.accept(sftp.remotePath("x").getFileSystem().getPath("/")); // the fixture's live connection
        }

        @Override
        public javafx.stage.Window window() {
            return null;
        }

        @Override
        public void setStatus(String message) {
            status.add(message);
        }

        @Override
        public void setError(String message) {
            status.add("error:" + message);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return null;
        }

        @Override
        public RunConfiguration selectedRunConfiguration() {
            return null;
        }

        @Override
        public void editDeployments(String name) {
            edited.add(String.valueOf(name));
        }

        @Override
        public void compare(Path local, Path deployed, String hostLabel) {}

        @Override
        public void openTerminal(Path dir) {}

        @Override
        public void pick(List<Deployment> all, Consumer<Deployment> onPick) {
            onPick.accept(all.get(0));
        }
    }

    /** Records a deploy's console; {@code ended} is released when it ends, however it ends. */
    private static final class Console implements DeploymentCoordinator.Console {
        final List<String> lines = new CopyOnWriteArrayList<>();
        final AtomicReference<String> endedWith = new AtomicReference<>();
        final CountDownLatch ended = new CountDownLatch(1);
        volatile boolean ok;

        @Override
        public void started(String title) {
            lines.add(title);
        }

        @Override
        public void line(String text) {
            lines.add(text);
        }

        @Override
        public void ended(boolean ok, String message) {
            this.ok = ok;
            endedWith.set(message);
            ended.countDown();
        }
    }

    /** Deploys "dev" and waits for the end; the copy handed over, or null. */
    private DeploymentCoordinator.Deployed deploy(Console console) throws Exception {
        AtomicReference<DeploymentCoordinator.Deployed> got = new AtomicReference<>();
        FxTestSupport.runOnFx(() -> deployments.deployThen("dev", console, got::set));
        assertTrue(console.ended.await(60, TimeUnit.SECONDS), "the deploy ended: " + console.lines);
        FxTestSupport.drainFx();
        return got.get();
    }

    @Test
    void aFirstDeploySendsTheProjectWithModesAndLeavesOutWhatTheHostBuilds() throws Exception {
        DeploymentCoordinator.Deployed d = deploy(new Console());
        assertNotNull(d, "deployed: " + status);
        assertEquals("notes", Files.readString(served.resolve("src/notes.txt")));
        assertTrue(Files.getPosixFilePermissions(served.resolve("run.sh")).contains(PosixFilePermission.OWNER_EXECUTE));
        assertFalse(Files.exists(served.resolve("target")), "target/ is left out");
        assertTrue(d.summary().contains("3 sent"), d.summary());
        assertEquals(served.toString(), d.remoteRoot().toString(), "the copy is the folder on the host");
    }

    @Test
    void aSecondDeploySendsOnlyWhatChangedAndRemovesOnlyEditorasOwnFiles() throws Exception {
        assertNotNull(deploy(new Console()));
        Files.writeString(served.resolve("app.log"), "the host's own file");
        write("src/notes.txt", "notes, edited"); // a different size: sent
        Files.delete(project.resolve(".git-not-a-repo.txt")); // gone: removed from the host

        DeploymentCoordinator.Deployed d = deploy(new Console());
        assertNotNull(d, "deployed: " + status);
        assertTrue(d.summary().contains("1 sent") && d.summary().contains("1 removed"), d.summary());
        assertEquals("notes, edited", Files.readString(served.resolve("src/notes.txt")));
        assertFalse(Files.exists(served.resolve(".git-not-a-repo.txt")));
        assertTrue(Files.exists(served.resolve("app.log")), "a file Editora never sent stays");

        DeploymentCoordinator.Deployed again = deploy(new Console());
        assertTrue(again.summary().contains("0 sent"), "nothing changed: " + again.summary());
    }

    @Test
    void aFileChangedOnTheHostIsAskedAboutAndKeptWhenTheUserSaysSo() throws Exception {
        assertNotNull(deploy(new Console()));
        Files.writeString(served.resolve("src/notes.txt"), "edited on the host, longer");
        decisions.conflictAnswer = Deployer.Decisions.Conflicts.KEEP_HOST;

        assertNotNull(deploy(new Console()));
        assertEquals(List.of(List.of("src/notes.txt")), decisions.conflictsAsked);
        assertEquals("edited on the host, longer", Files.readString(served.resolve("src/notes.txt")));

        decisions.conflictAnswer = Deployer.Decisions.Conflicts.CANCEL;
        assertNotNull(deploy(new Console()), "kept files are the host's now: not asked about again");
        assertEquals(1, decisions.conflictsAsked.size());
        assertEquals(
                "edited on the host, longer",
                Files.readString(served.resolve("src/notes.txt")),
                "and not overwritten by the next deploy either");

        write("src/notes.txt", "notes, edited here since");
        decisions.conflictAnswer = Deployer.Decisions.Conflicts.OVERWRITE;
        assertNotNull(deploy(new Console()));
        assertEquals(2, decisions.conflictsAsked.size(), "a local edit after keeping asks again");
        assertEquals("notes, edited here since", Files.readString(served.resolve("src/notes.txt")));
    }

    @Test
    void aFirstDeployIntoAFolderWithOtherFilesAsksBeforeReplacingThem() throws Exception {
        Files.createDirectories(served.resolve("src"));
        Files.writeString(served.resolve("src/notes.txt"), "someone else's notes");
        decisions.overwriteAnswer = false;

        Console console = new Console();
        assertEquals(null, deploy(console), "declined: nothing runs");
        assertEquals(List.of(List.of("src/notes.txt")), decisions.overwriteAsked);
        assertEquals("someone else's notes", Files.readString(served.resolve("src/notes.txt")));
        assertFalse(Files.exists(served.resolve("run.sh")), "nothing was sent");
    }

    @Test
    void aFolderTooWideForADeploymentIsRefusedBeforeAnythingIsSent() throws Exception {
        config.getWorkspaceState().setDeployments(new ArrayList<>(List.of(deployment("/usr"))));
        Console console = new Console();
        assertEquals(null, deploy(console));
        assertFalse(console.ok);
        assertTrue(status.stream().anyMatch(s -> s.contains("/usr")), status.toString());
        assertEquals(List.of("dev"), edited, "the deployment is opened to fix it");
    }

    @Test
    void anUnknownDeploymentIsSaidAndEndsTheConsole() throws Exception {
        Console console = new Console();
        FxTestSupport.runOnFx(() -> deployments.deployThen("nope", console, d -> {}));
        assertTrue(console.ended.await(5, TimeUnit.SECONDS));
        assertEquals(null, console.endedWith.get(), "nothing started: the status bar says why");
        assertTrue(status.stream().anyMatch(s -> s.contains("nope")), status.toString());
    }

    // --- a configuration that runs on the deployment ---

    private final class RunHost extends CoordinatorHostStub {
        final Settings settings = new Settings();

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void setStatus(String message) {
            status.add(message);
        }
    }

    private final class RunOps implements RunCoordinator.Ops {
        @Override
        public void deployThen(
                String name, DeploymentCoordinator.Console console, Consumer<DeploymentCoordinator.Deployed> then) {
            deployments.deployThen(name, console, then);
        }

        @Override
        public void cancelDeploy() {
            deployments.cancel();
        }

        @Override
        public void openToolWindow() {}

        @Override
        public void onRunStateChanged() {}

        @Override
        public void editConfiguration(String name) {
            edited.add("config:" + name);
        }

        @Override
        public boolean saveBuffer(EditorBuffer buffer) {
            return true;
        }

        @Override
        public String programArgs(Path path) {
            return "";
        }

        @Override
        public void setProgramArgs(Path path, String args) {}

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public Path javaProjectRoot(Path file) {
            return project;
        }

        @Override
        public Path projectRoot() {
            return project;
        }

        @Override
        public boolean javaLaunchAvailable() {
            return false;
        }

        @Override
        public List<RunConfiguration> runConfigurations() {
            return List.of();
        }

        @Override
        public String selectedRunConfigName() {
            return "";
        }

        @Override
        public void resolveJavaMainClasses(Path routingFile, Consumer<List<JavaMainClass>> cb) {
            cb.accept(List.of());
        }

        @Override
        public void resolveJavaLaunch(Path routingFile, JavaMainClass mainClass, Consumer<JavaLaunchInfo> cb) {
            cb.accept(null);
        }

        @Override
        public boolean mavenProjectAt(Path root) {
            return false;
        }

        @Override
        public boolean gradleProjectAt(Path root) {
            return false;
        }

        @Override
        public void resolveMavenClasspath(Path root, Consumer<List<String>> cb) {
            cb.accept(List.of());
        }

        @Override
        public void runGradleRunTask(Path root) {}
    }

    private static void await(String what, BooleanSupplier done) throws Exception {
        for (int i = 0; i < 1200 && !done.getAsBoolean(); i++) {
            Thread.sleep(25);
        }
        assertTrue(done.getAsBoolean(), what);
    }

    private static String console(RunCoordinator run) throws Exception {
        CodeArea output = FxTestSupport.field(run.panel(), "output");
        return FxTestSupport.callOnFx(output::getText);
    }

    @Test
    void aConfigurationOnTheDeploymentRunsItsStepAndProgramInTheCopyOnTheHost() throws Exception {
        RunCoordinator run = new RunCoordinator(new RunHost(), new RunOps());
        RunConfiguration cfg = new RunConfiguration(
                        "remote run", "shell", "run.sh", "", "", "hello", "", "", "", "sh -c 'pwd -P > step.txt'", "")
                .withDeployment("dev");
        FxTestSupport.runOnFx(() -> run.runConfig(cfg));

        await("the program ran on the host: " + status, () -> {
            try {
                return Files.isDirectory(served)
                        && console(run).contains("ran in " + served.toRealPath() + " with hello");
            } catch (Exception e) {
                return false;
            }
        });
        assertEquals(
                served.toRealPath().toString(),
                Files.readString(served.resolve("step.txt")).strip(),
                "the before-launch step ran in the copy too");
        String text = console(run);
        assertTrue(text.startsWith("Deployed to"), "the deploy's summary heads the program's console: " + text);
        assertEquals(
                project.toString(),
                run.lastRunDir().toString(),
                "the console's file links resolve in the project, not in the host's copy");
    }

    @Test
    void aRunRefusedDuringTheBeforeLaunchStepLeavesTheLaunchOnTheHost() throws Exception {
        RunCoordinator run = new RunCoordinator(new RunHost(), new RunOps());
        // The step says it started, then holds the launch long enough for a second Run to arrive.
        RunConfiguration cfg = new RunConfiguration(
                        "remote run",
                        "shell",
                        "run.sh",
                        "",
                        "",
                        "hello",
                        "",
                        "",
                        "",
                        "sh -c 'touch started; sleep 2'",
                        "")
                .withDeployment("dev");
        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        await("the before-launch step is running on the host", () -> Files.exists(served.resolve("started")));

        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        assertTrue(status.contains(Messages.tr("status.run.busy")), "the second Run is refused: " + status);

        await("the first launch ran: " + status, () -> {
            try {
                return console(run).contains(" with hello");
            } catch (Exception e) {
                return false;
            }
        });
        assertTrue(
                console(run).contains("ran in " + served.toRealPath() + " with hello"),
                "the refused Run left the launch on the host's copy, not the local project: " + console(run));
    }

    @Test
    void aRunDuringTheDeployIsRefusedAndStopStillEndsTheDeploy() throws Exception {
        for (int i = 0; i < 400; i++) { // many small files, so the deploy is still sending
            write("bulk/f" + i + ".txt", "x".repeat(2000) + i);
        }
        RunCoordinator run = new RunCoordinator(new RunHost(), new RunOps());
        RunConfiguration cfg = new RunConfiguration("remote run", "shell", "run.sh", "", "", "", "", "", "", "", "")
                .withDeployment("dev");
        FxTestSupport.runOnFx(() -> {
            run.runConfig(cfg);
            run.runConfig(cfg);
            assertTrue(run.isRunning(), "the second Run did not end the first one's deploy");
            run.stopRun();
        });
        assertTrue(status.contains(Messages.tr("status.run.busy")), "the second Run is refused: " + status);
        assertFalse(status.contains(Messages.tr("status.deploy.busy")), "no second deploy was tried: " + status);
        await("the deploy ended", () -> !deployments.isBusy());
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
        assertFalse(console(run).contains("ran in"), "Stop ended the deploy, so nothing ran");
    }

    @Test
    void stopDuringTheDeployEndsItAndNothingRuns() throws Exception {
        // Many small files, so the deploy is still sending when Stop arrives.
        for (int i = 0; i < 400; i++) {
            write("bulk/f" + i + ".txt", "x".repeat(2000) + i);
        }
        RunCoordinator run = new RunCoordinator(new RunHost(), new RunOps());
        RunConfiguration cfg = new RunConfiguration("remote run", "shell", "run.sh", "", "", "", "", "", "", "", "")
                .withDeployment("dev");
        FxTestSupport.runOnFx(() -> {
            run.runConfig(cfg);
            assertTrue(run.isRunning(), "a deploy counts as running: Stop applies to it");
            run.stopRun();
        });
        await("the deploy ended", () -> !deployments.isBusy());
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
        assertFalse(Files.exists(served.resolve("run.sh")) && console(run).contains("ran in"), "nothing ran");
    }
}
