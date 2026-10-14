package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.build.BuildService;
import com.editora.build.BuildTool;
import com.editora.config.RunConfiguration;
import com.editora.process.ExecTargets;
import com.editora.run.RunConfigWorkingDirectory;
import com.editora.run.RunService;
import com.editora.vfs.EmbeddedSftpFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run and Build for a remote folder start the program on the folder's host: its output, input, exit code
 * and Stop all go through the same SSH connection.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteRunFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private Path remoteDir;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Files.createDirectories(sftp.serverPath("app"));
        remoteDir = sftp.remotePath("app");
    }

    @AfterEach
    void tearDown() throws Exception {
        sftp.close();
        ExecTargets.setRemoteEnabled(null);
    }

    /** Records a run's events; {@code exited} is released by the exit or an error. */
    private static final class Recorder implements RunService.Listener, BuildService.Listener {
        final List<String> out = new CopyOnWriteArrayList<>();
        final AtomicInteger exit = new AtomicInteger(Integer.MIN_VALUE);
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch exited = new CountDownLatch(1);

        @Override
        public void onStart(String commandLine) {
            started.countDown();
        }

        @Override
        public void onOutput(String line, boolean stderr) {
            out.add(line);
        }

        @Override
        public void onExit(int code) {
            exit.set(code);
            exited.countDown();
        }

        @Override
        public void onError(String message) {
            error.set(message);
            exited.countDown();
        }

        void awaitLine(String line) throws InterruptedException {
            for (int i = 0; i < 400 && !out.contains(line); i++) {
                Thread.sleep(25);
            }
            assertTrue(out.contains(line), "never printed '" + line + "': " + out);
        }
    }

    @Test
    void aProgramRunsOnTheHostAndReadsWhatIsTyped() throws Exception {
        RunService service = new RunService();
        Recorder rec = new Recorder();

        FxTestSupport.runOnFx(() -> service.runInDir(
                remoteDir, List.of("sh", "-c", "pwd -P; read name; echo \"hello $name\"; exit 4"), Map.of(), rec));
        assertTrue(rec.started.await(30, TimeUnit.SECONDS), rec.error.get());
        rec.awaitLine(sftp.serverPath("app").toRealPath().toString());
        assertTrue(FxTestSupport.callOnFx(service::pid) > 1, "the program's id on the host");
        FxTestSupport.runOnFx(() -> service.sendInput("Ñandú"));

        assertTrue(rec.exited.await(30, TimeUnit.SECONDS));
        assertTrue(rec.out.contains("hello Ñandú"), rec.out.toString());
        assertEquals(4, rec.exit.get());
    }

    @Test
    @EnabledOnOs(OS.LINUX) // the fixture gives a command its own process group through setsid(1)
    void stopEndsTheProgramAndWhatItStartedOnTheHost() throws Exception {
        RunService service = new RunService();
        Recorder rec = new Recorder();
        FxTestSupport.runOnFx(() ->
                service.runInDir(remoteDir, List.of("sh", "-c", "sleep 600 & echo child $!; wait"), Map.of(), rec));
        assertTrue(rec.started.await(30, TimeUnit.SECONDS), rec.error.get());
        for (int i = 0; i < 400 && rec.out.stream().noneMatch(l -> l.startsWith("child ")); i++) {
            Thread.sleep(25);
        }
        long child = Long.parseLong(rec.out.stream()
                .filter(l -> l.startsWith("child "))
                .findFirst()
                .orElseThrow()
                .substring(6)
                .strip());

        FxTestSupport.runOnFx(service::stop);

        assertTrue(rec.exited.await(30, TimeUnit.SECONDS), "Stop did not end the program");
        for (int i = 0;
                i < 200 && ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false);
                i++) {
            Thread.sleep(25);
        }
        assertTrue(ProcessHandle.of(child).map(h -> !h.isAlive()).orElse(true), "its child survived Stop");
    }

    @Test
    void aBuildRunsOnTheHost() throws Exception {
        BuildService service = new BuildService();
        Recorder rec = new Recorder();

        FxTestSupport.runOnFx(() -> service.run(
                remoteDir, List.of("sh", "-c", "echo building in $(basename \"$PWD\"); exit 3"), Map.of(), rec));

        assertTrue(rec.exited.await(30, TimeUnit.SECONDS), rec.error.get());
        assertTrue(rec.out.contains("building in app"), rec.out.toString());
        assertEquals(3, rec.exit.get());
        assertTrue(
                sftp.commands().stream().anyMatch(line -> line.contains("building in")),
                "the build did not run on the host");
    }

    @Test
    void withTheSettingOffARemoteRunSaysWhy() throws Exception {
        ExecTargets.setRemoteEnabled(() -> false);
        RunService service = new RunService();
        Recorder rec = new Recorder();
        int before = sftp.commands().size();

        FxTestSupport.runOnFx(() -> service.runInDir(remoteDir, List.of("echo", "hi"), Map.of(), rec));

        assertTrue(rec.exited.await(10, TimeUnit.SECONDS));
        assertEquals("running commands on remote hosts is turned off", rec.error.get());
        assertEquals(before, sftp.commands().size());
    }

    @Test
    void theJavaOnTheHostIsTheOneAskedForItsVersion() throws Exception {
        RunService service = new RunService();
        AtomicInteger major = new AtomicInteger(Integer.MIN_VALUE);
        CountDownLatch done = new CountDownLatch(1);

        FxTestSupport.runOnFx(() -> service.detectJavaMajor("java", remoteDir, m -> {
            major.set(m);
            done.countDown();
        }));

        assertTrue(done.await(30, TimeUnit.SECONDS));
        assertTrue(major.get() >= 8, "java -version on the host: " + major.get());
        assertTrue(sftp.commands().stream().anyMatch(line -> line.contains("'-version'")), "not asked on the host");
    }

    @Test
    void aMavenWrapperOnTheHostIsLaunchedThePosixWayWhateverThisMachineIs() throws Exception {
        Path wrapper = Files.writeString(sftp.serverPath("app/mvnw"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(sftp.serverPath("app/pom.xml"), "<project/>");

        List<String> argv = BuildTool.MAVEN.executable(remoteDir, remoteDir, true /* this machine: Windows */, "");

        assertEquals(List.of("./mvnw"), argv);
    }

    @Test
    void aRunConfigurationsDirectoryInARemoteProjectIsOnTheHost() {
        RunConfiguration cfg =
                new RunConfiguration("script", "shell", "run.sh", "", "", "", "", "/srv/elsewhere", "", "", "");

        Path cwd = RunConfigWorkingDirectory.resolve(cfg, remoteDir);

        assertEquals(remoteDir.getFileSystem(), cwd.getFileSystem());
        assertEquals("/srv/elsewhere", cwd.toString());
    }
}
