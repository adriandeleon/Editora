package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.editora.build.BuildService;
import com.editora.build.BuildTool;
import com.editora.config.RunConfiguration;
import com.editora.process.ExecTargets;
import com.editora.process.MappedTestTarget;
import com.editora.run.RunConfigWorkingDirectory;
import com.editora.run.RunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Run, Build and the Java checks for a folder of this machine whose programs run elsewhere (a container, WSL):
 * they start on that target, by its names for the folder's files, with none of this machine's environment.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the stand-in target is a symlink
class RunMappedTargetFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private Path folder;
    private Path alias;
    private MappedTestTarget target;

    @BeforeEach
    void setUp() throws Exception {
        Path base = dir.toRealPath();
        folder = Files.createDirectories(base.resolve("app"));
        alias = base.resolve("mounted");
        ExecTargets.setRemoteEnabled(() -> true);
        target = MappedTestTarget.register(folder, alias);
    }

    @AfterEach
    void tearDown() {
        target.unregister();
        ExecTargets.setRemoteEnabled(null);
    }

    private static final class Recorder implements RunService.Listener, BuildService.Listener {
        final List<String> out = new CopyOnWriteArrayList<>();
        final AtomicReference<String> error = new AtomicReference<>();
        final CountDownLatch exited = new CountDownLatch(1);

        @Override
        public void onStart(String commandLine) {}

        @Override
        public void onOutput(String line, boolean stderr) {
            out.add(line);
        }

        @Override
        public void onExit(int code) {
            exited.countDown();
        }

        @Override
        public void onError(String message) {
            error.set(message);
            exited.countDown();
        }
    }

    @Test
    void aProgramAndABuildStartOnTheMappedTarget() throws Exception {
        RunService run = new RunService();
        Recorder ran = new Recorder();
        FxTestSupport.runOnFx(() -> run.runInDir(folder, List.of("sh", "-c", "echo ran"), Map.of(), ran));
        assertTrue(ran.exited.await(30, TimeUnit.SECONDS), ran.error.get());
        assertTrue(ran.out.contains("ran"), ran.out + " " + ran.error.get());

        BuildService build = new BuildService();
        Recorder built = new Recorder();
        FxTestSupport.runOnFx(() -> build.run(folder, List.of("sh", "-c", "echo built"), Map.of(), built));
        assertTrue(built.exited.await(30, TimeUnit.SECONDS), built.error.get());
        assertTrue(built.out.contains("built"), built.out + " " + built.error.get());

        assertTrue(target.started().contains(List.of("sh", "-c", "echo ran")), "the run, on the target");
        assertTrue(target.started().contains(List.of("sh", "-c", "echo built")), "the build, on the target");
        assertEquals(List.of(), target.leaks());
    }

    @Test
    void theJavaAskedForItsVersionIsTheTargets() throws Exception {
        CountDownLatch answered = new CountDownLatch(1);
        FxTestSupport.runOnFx(() -> new RunService().detectJavaMajor("java", folder, major -> answered.countDown()));
        assertTrue(answered.await(30, TimeUnit.SECONDS));
        assertTrue(
                target.started().contains(List.of("java", "-version")),
                target.started().toString());
    }

    @Test
    void aMavenWrapperAboveTheModuleIsLaunchedByItsNameThereThePosixWay() throws Exception {
        Path wrapper = Files.writeString(folder.resolve("mvnw"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(wrapper, PosixFilePermissions.fromString("rwxr-xr-x"));
        Path module = Files.createDirectories(folder.resolve("mod"));
        Files.writeString(module.resolve("pom.xml"), "<project/>");

        List<String> argv = BuildTool.MAVEN.executable(module, folder, true /* this machine: Windows */, "");

        assertEquals(List.of(alias.resolve("mvnw").toString()), argv);
    }

    @Test
    void anAbsoluteWorkingDirectoryIsNamedAsItIsWhereTheProgramRuns() {
        RunConfiguration cfg = new RunConfiguration(
                "script",
                "shell",
                "run.sh",
                "",
                "",
                "",
                "",
                alias.resolve("sub").toString(),
                "",
                "",
                "");

        assertEquals(folder.resolve("sub"), RunConfigWorkingDirectory.resolve(cfg, folder));
    }
}
