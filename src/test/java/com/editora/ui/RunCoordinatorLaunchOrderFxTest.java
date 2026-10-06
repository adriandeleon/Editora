package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import javafx.scene.Scene;
import javafx.scene.layout.VBox;

import com.editora.config.RunConfiguration;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.StackTraceLinks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The order of a configuration launch in {@link RunCoordinator}: the edited buffer is saved before the
 * before-launch build reads it, and a before-launch step does not disturb what Rerun repeats.
 */
@Tag("fx")
class RunCoordinatorLaunchOrderFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        assumeFalse(System.getProperty("os.name", "").toLowerCase().contains("win"), "needs bash and sh");
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        volatile EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            if (active != null) {
                action.accept(active);
            }
        }
    }

    private static final class Ops implements RunCoordinator.Ops {
        final Path project;
        final List<String> saved = new CopyOnWriteArrayList<>();

        Ops(Path project) {
            this.project = project;
        }

        @Override
        public void openToolWindow() {}

        @Override
        public void onRunStateChanged() {}

        @Override
        public void editConfiguration(String name) {}

        @Override
        public boolean saveBuffer(EditorBuffer b) {
            try {
                Files.writeString(b.getPath(), b.getContent());
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
            b.markClean();
            saved.add(b.getPath().getFileName().toString());
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
        public void resolveJavaMainClasses(Path routingFile, Consumer<List<JavaMainClass>> cb) {}

        @Override
        public void resolveJavaLaunch(Path routingFile, JavaMainClass mainClass, Consumer<JavaLaunchInfo> cb) {}

        @Override
        public boolean mavenProjectAt(Path r) {
            return false;
        }

        @Override
        public boolean gradleProjectAt(Path r) {
            return false;
        }

        @Override
        public void resolveMavenClasspath(Path r, Consumer<List<String>> cb) {}

        @Override
        public void runGradleRunTask(Path r) {}
    }

    private static RunConfiguration shell(String name, String script, Path cwd, String beforeLaunch) {
        return new RunConfiguration(name, "shell", script, "", "", "", "", cwd.toString(), "", beforeLaunch);
    }

    private static void runAndWait(RunCoordinator coordinator, Runnable launch) throws Exception {
        FxTestSupport.runOnFx(launch);
        long deadline = System.currentTimeMillis() + 30_000;
        while (FxTestSupport.callOnFx(coordinator::isRunning)) {
            if (System.currentTimeMillis() > deadline) {
                fail("the run did not finish");
            }
            Thread.sleep(25);
        }
        FxTestSupport.runOnFx(() -> {});
    }

    /**
     * The toolbar Run with a before-launch build and an unsaved edit: the build used to read the file as it
     * was on disk (the save came after it), so the program ran without the change on screen.
     */
    @Test
    void theEditedBufferIsSavedBeforeTheBeforeLaunchStepReadsIt(@TempDir Path project) throws Exception {
        Path app = project.resolve("App.java");
        Files.writeString(app, "class App { /* OLD on disk */ }\n");
        Files.writeString(project.resolve("run.sh"), "cp App.java ran.txt\n");
        Host host = new Host();
        host.active = FxTestSupport.callOnFx(() -> {
            EditorBuffer b = new EditorBuffer();
            b.setPath(app);
            b.setContent("class App { /* OLD on disk */ }\n");
            b.markClean();
            new Scene(new VBox(b.getNode()), 800, 600);
            b.getArea().replaceText("class App { /* NEW unsaved edit */ }\n");
            return b;
        });
        Ops ops = new Ops(project);
        RunCoordinator coordinator = FxTestSupport.callOnFx(() -> new RunCoordinator(host, ops));
        try {
            runAndWait(
                    coordinator, () -> coordinator.runConfig(shell("App", "run.sh", project, "cp App.java built.txt")));

            assertEquals(List.of("App.java"), ops.saved);
            assertTrue(Files.readString(project.resolve("built.txt")).contains("NEW"), "what the build compiled");
            assertTrue(Files.readString(project.resolve("ran.txt")).contains("NEW"), "what the script ran against");
        } finally {
            FxTestSupport.runOnFx(coordinator::shutdown);
        }
    }

    /**
     * Rerun repeats the last <em>program</em>. A before-launch step that fails launches nothing, yet it used
     * to move the remembered directory — and Rerun replayed the previous command in the wrong folder.
     */
    @Test
    void aBeforeLaunchStepDoesNotMoveWhereRerunRuns(@TempDir Path base) throws Exception {
        Path a = Files.createDirectories(base.resolve("a"));
        Path b = Files.createDirectories(base.resolve("b"));
        Path log = base.resolve("cwd.log");
        Files.writeString(a.resolve("where.sh"), "pwd >> '" + log + "'\n");
        Host host = new Host();
        RunCoordinator coordinator = FxTestSupport.callOnFx(() -> new RunCoordinator(host, new Ops(base)));
        try {
            runAndWait(coordinator, () -> coordinator.runConfig(shell("A", "where.sh", a, "")));
            runAndWait(
                    coordinator,
                    () -> coordinator.runConfig(shell("B", "other.sh", b, "sh -c \"echo building; exit 3\"")));
            assertEquals(b, coordinator.lastRunDir(), "the console's links resolve against the step's directory");

            runAndWait(coordinator, coordinator::rerunLast);

            List<String> lines = Files.readAllLines(log);
            assertEquals(2, lines.size(), "the rerun ran where.sh again: " + lines);
            assertEquals(a.toRealPath(), Path.of(lines.get(1)).toRealPath());
        } finally {
            FxTestSupport.runOnFx(coordinator::shutdown);
        }
    }
}
