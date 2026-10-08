package com.editora.ui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;

import com.editora.config.RunConfiguration;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.run.JavaLaunchInfo;
import com.editora.run.JavaMainClass;
import com.editora.run.StackTraceLinks;
import org.fxmisc.richtext.CodeArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What {@link RunCoordinator} launches for each way of pressing Run — the active file, a main class through
 * jdtls or through Maven's classpath, a saved configuration — and what it says when it launches nothing.
 * No JDK is started: a {@code /bin/sh} script stands in for {@code java} and prints the command line it was
 * given, which is what the console then shows.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS) // the stand-in java and the script fixtures are /bin/sh scripts
class RunCoordinatorFlowsFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path project;

    /** Status messages and run-state changes, in order. */
    private final List<String> log = new ArrayList<>();

    private final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new ArrayList<>();
        volatile EditorBuffer active;
        volatile String promptAnswer;
        volatile String promptInitial;

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
            new ArrayList<>(buffers).forEach(action);
        }

        @Override
        public void setStatus(String message) {
            record("status:" + message);
        }

        @Override
        public void promptText(String title, String label, String initial, Consumer<String> onAccept) {
            promptInitial = initial;
            onAccept.accept(promptAnswer);
        }
    }

    private final class Ops implements RunCoordinator.Ops {
        boolean jdtls;
        boolean maven;
        boolean gradle;
        boolean saveOk = true;
        Path javaRoot;
        List<JavaMainClass> mainClasses = List.of();
        JavaLaunchInfo launchInfo;
        List<String> mavenClasspath;
        List<RunConfiguration> configurations = List.of();
        String selected = "";
        final Map<String, String> programArgs = new LinkedHashMap<>();
        final List<String> edited = new ArrayList<>();
        final List<String> gradleRuns = new ArrayList<>();
        final List<String> saved = new ArrayList<>();
        final List<String> mavenJdks = new ArrayList<>();
        final List<String> urls = new ArrayList<>();
        int toolWindowOpened;

        @Override
        public void openToolWindow() {
            toolWindowOpened++;
        }

        @Override
        public void onRunStateChanged() {
            record("state");
        }

        @Override
        public void editConfiguration(String name) {
            edited.add(name);
        }

        @Override
        public boolean saveBuffer(EditorBuffer b) {
            saved.add(String.valueOf(b.getPath() == null ? null : b.getPath().getFileName()));
            if (saveOk) {
                b.markClean();
            }
            return saveOk;
        }

        @Override
        public String programArgs(Path path) {
            return programArgs.getOrDefault(path.getFileName().toString(), "");
        }

        @Override
        public void setProgramArgs(Path path, String args) {
            programArgs.put(path.getFileName().toString(), args);
        }

        @Override
        public void openLink(StackTraceLinks.Link link) {}

        @Override
        public void openUrl(String url) {
            urls.add(url);
        }

        @Override
        public Path javaProjectRoot(Path file) {
            return javaRoot;
        }

        @Override
        public Path projectRoot() {
            return project;
        }

        @Override
        public boolean javaLaunchAvailable() {
            return jdtls;
        }

        @Override
        public List<RunConfiguration> runConfigurations() {
            return configurations;
        }

        @Override
        public String selectedRunConfigName() {
            return selected;
        }

        @Override
        public void resolveJavaMainClasses(Path routingFile, Consumer<List<JavaMainClass>> cb) {
            cb.accept(mainClasses);
        }

        @Override
        public void resolveJavaLaunch(Path routingFile, JavaMainClass mainClass, Consumer<JavaLaunchInfo> cb) {
            cb.accept(launchInfo);
        }

        @Override
        public boolean mavenProjectAt(Path r) {
            return maven;
        }

        @Override
        public boolean gradleProjectAt(Path r) {
            return gradle;
        }

        @Override
        public void resolveMavenClasspath(Path r, Consumer<List<String>> cb) {
            cb.accept(mavenClasspath);
        }

        @Override
        public void resolveMavenClasspath(Path r, String jdkHome, Consumer<List<String>> cb) {
            mavenJdks.add(jdkHome);
            cb.accept(mavenClasspath);
        }

        @Override
        public void runGradleRunTask(Path r) {
            gradleRuns.add(r.toString());
        }
    }

    private Host host;
    private Ops ops;
    private RunCoordinator run;
    private Path jdk;
    private String java;

    private static final String PATH_SEP = System.getProperty("path.separator");

    @BeforeEach
    void setUp() throws Exception {
        jdk = project.resolve("jdk-25");
        java = standInJava(jdk, "25.0.1");
        host = new Host();
        ops = new Ops();
        ops.javaRoot = project;
        run = FxTestSupport.callOnFx(() -> new RunCoordinator(host, ops));
    }

    @AfterEach
    void tearDown() throws Exception {
        FxTestSupport.runOnFx(run::shutdown);
    }

    // --- harness --------------------------------------------------------------------------------------

    /** A JDK home whose {@code java} reports {@code version} and otherwise prints what it was asked to run. */
    private static String standInJava(Path home, String version) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Path exe = bin.resolve("java");
        String versionLine = version == null ? "" : "echo 'openjdk version \"" + version + "\" 2099-01-01' >&2; ";
        Files.writeString(
                exe,
                "#!/bin/sh\nif [ \"$1\" = \"-version\" ]; then " + versionLine + "exit 0; fi\n"
                        + "echo \"java $*\"\necho \"JAVA_HOME=$JAVA_HOME GREETING=$GREETING\"\necho \"cwd=$(pwd)\"\n");
        Files.setPosixFilePermissions(exe, PosixFilePermissions.fromString("rwxr-xr-x"));
        return exe.toString();
    }

    private void record(String entry) {
        synchronized (log) {
            log.add(entry);
            log.notifyAll();
        }
    }

    private List<String> statuses() {
        synchronized (log) {
            return log.stream()
                    .filter(e -> e.startsWith("status:"))
                    .map(e -> e.substring("status:".length()))
                    .toList();
        }
    }

    private void clearLog() {
        synchronized (log) {
            log.clear();
        }
    }

    private void await(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        synchronized (log) {
            while (!condition.getAsBoolean()) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(what + " never happened; log=" + log);
                }
                TimeUnit.NANOSECONDS.timedWait(log, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(50)));
            }
        }
    }

    private void awaitStatus(String message) throws Exception {
        await("status '" + message + "'", () -> log.contains("status:" + message));
        FxTestSupport.drainFx();
    }

    private String console() throws Exception {
        CodeArea output = FxTestSupport.field(run.panel(), "output");
        return FxTestSupport.callOnFx(output::getText);
    }

    private String panelStatus() throws Exception {
        Label status = FxTestSupport.field(run.panel(), "status");
        return FxTestSupport.callOnFx(status::getText);
    }

    /** A saved tab in front; waits for the editor's own scan of what is runnable in it. */
    private EditorBuffer open(String name, String source, boolean expectRunnable) throws Exception {
        Path file = project.resolve(name);
        Files.createDirectories(file.getParent());
        Files.writeString(file, source);
        CountDownLatch scanned = new CountDownLatch(1);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer made = new EditorBuffer();
            made.setOnRunnableChanged(scanned::countDown);
            made.setShellRunEnabled(true); // as the window does when shell scripts may be run
            made.setPath(file);
            made.setContent(source);
            made.markClean();
            host.buffers.add(made);
            host.active = made;
            return made;
        });
        if (expectRunnable) {
            assertTrue(scanned.await(20, TimeUnit.SECONDS), "the editor never found " + name + " runnable");
            assertTrue(FxTestSupport.callOnFx(b::isRunnable));
        }
        return b;
    }

    private static RunConfiguration javaConfig(String name, String mainClass, String workingDir, String jdkHome) {
        return new RunConfiguration(
                name,
                "java",
                "",
                mainClass,
                "",
                "one 'two three'",
                "-Dmode=test",
                workingDir,
                "GREETING=hi",
                "",
                jdkHome);
    }

    // --- Run File ---------------------------------------------------------------------------------------

    @Test
    void runFileOnSomethingThatIsNotRunnableSaysSo() throws Exception {
        FxTestSupport.runOnFx(run::runActiveFile);
        open("notes.txt", "just text\n", false);
        FxTestSupport.runOnFx(run::runActiveFile);

        assertEquals(List.of(tr("status.run.notCompact"), tr("status.run.notCompact")), statuses());
        assertFalse(FxTestSupport.callOnFx(run::consoleInUse));
        assertEquals(tr("run.idle"), panelStatus());
    }

    @Test
    void aShellScriptRunsWithItsRememberedArgumentsAndRerunRepeatsIt() throws Exception {
        open("hello.sh", "#!/bin/sh\necho \"hello $1 from $(basename \"$(pwd)\")\"\nexit 4\n", true);
        ops.programArgs.put("hello.sh", "world");

        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.exit", 4));

        assertEquals("hello world from " + project.getFileName() + "\n", console());
        assertEquals(tr("run.exited", 4), panelStatus());
        assertTrue(
                statuses().contains(tr("status.run.started", "hello.sh")),
                statuses().toString());
        assertEquals(project, run.lastRunDir());
        assertTrue(FxTestSupport.callOnFx(run::consoleInUse), "the output stays to be read after the exit");
        assertFalse(FxTestSupport.callOnFx(run::isRunning));

        clearLog();
        host.active = null; // Rerun does not depend on the tab in front
        FxTestSupport.runOnFx(run::rerunLast);
        awaitStatus(tr("status.run.exit", 4));
        assertEquals("hello world from " + project.getFileName() + "\n", console());
    }

    @Test
    void rerunWithNothingRunYetSaysSo() throws Exception {
        FxTestSupport.runOnFx(run::rerunLast);

        assertEquals(List.of(tr("status.run.noRerun")), statuses());
        assertEquals(0, ops.toolWindowOpened);
    }

    @Test
    void runWithArgumentsAsksForThemAndRemembersTheAnswer() throws Exception {
        open("hello.sh", "#!/bin/sh\necho \"args: $*\"\n", true);
        ops.programArgs.put("hello.sh", "old");
        host.promptAnswer = "  new 'two words'  ";

        FxTestSupport.runOnFx(run::runActiveFileWithArgs);
        awaitStatus(tr("status.run.ok"));

        assertEquals("old", host.promptInitial, "the prompt starts from the remembered arguments");
        assertEquals("new 'two words'", ops.programArgs.get("hello.sh"));
        assertEquals("args: new two words\n", console());

        clearLog();
        host.promptAnswer = null; // an empty answer clears them
        FxTestSupport.runOnFx(run::runActiveFileWithArgs);
        awaitStatus(tr("status.run.ok"));
        assertEquals("", ops.programArgs.get("hello.sh"));
        assertEquals("args: \n", console());
    }

    @Test
    void anEditedFileIsSavedBeforeItRunsAndACancelledSaveRunsNothing() throws Exception {
        EditorBuffer b = open("hello.sh", "#!/bin/sh\necho ran\n", true);
        ops.saveOk = false;
        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().insertText(b.getFocusedArea().getLength(), "echo more\n");
            run.runActiveFile();
            run.runMakeTarget(b, "all");
            run.runMakeTarget(null, "all");
        });
        FxTestSupport.drainFx();

        assertEquals(List.of("hello.sh", "hello.sh"), ops.saved);
        assertEquals(List.of(), statuses(), "nothing was started, and nothing claims otherwise");
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
        assertEquals("", console());
    }

    @Test
    void aSecondRunWhileOneIsAliveIsRefusedAndStopEndsTheFirst() throws Exception {
        open("wait.sh", "#!/bin/sh\necho started\nexec sleep 300\n", true);
        Button stop = FxTestSupport.field(run.panel(), "stopButton");

        FxTestSupport.runOnFx(run::runActiveFile);
        await("the first line of output", () -> {
            try {
                return console().contains("started\n");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        assertTrue(FxTestSupport.callOnFx(run::isRunning));
        assertFalse(FxTestSupport.callOnFx(stop::isDisabled));

        clearLog();
        FxTestSupport.runOnFx(() -> {
            run.runActiveFile();
            run.rerunLast();
            run.runMainClass();
            run.runConfig(new RunConfiguration("S", "shell", "wait.sh", "", "", "", "", "", "", "", ""));
        });
        assertEquals(
                List.of(
                        tr("status.run.busy"),
                        tr("status.run.busy"),
                        tr("status.run.needJavaFile"),
                        tr("status.run.busy")),
                statuses());

        FxTestSupport.runOnFx(stop::fire);
        awaitStatus(tr("status.run.stopped"));
        await("the process to be gone", () -> log.contains("state"));
        FxTestSupport.drainFx();
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
        assertTrue(FxTestSupport.callOnFx(stop::isDisabled));
    }

    @Test
    void aLineTypedIntoTheConsoleReachesTheProgramAndIsEchoed() throws Exception {
        open("ask.sh", "#!/bin/sh\nprintf 'name? '\nread name\necho \"hi $name\"\n", true);
        TextField input = FxTestSupport.field(run.panel(), "input");
        assertTrue(FxTestSupport.callOnFx(input::isDisabled), "nothing is running to type to");

        FxTestSupport.runOnFx(run::runActiveFile);
        await("the prompt", () -> {
            try {
                return console().contains("name? ");
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        FxTestSupport.runOnFx(() -> {
            assertFalse(input.isDisabled());
            input.setText("Ada");
            input.fireEvent(new javafx.event.ActionEvent());
        });
        awaitStatus(tr("status.run.ok"));

        assertEquals("name? Ada\nhi Ada\n", console());
        assertEquals("", FxTestSupport.callOnFx(input::getText));

        FxTestSupport.runOnFx(run::clearConsole);
        assertEquals("", console());
    }

    @Test
    void aMakefileRunsItsDefaultGoalOrANamedTargetThroughMakeWithThatFile() throws Exception {
        // `make` itself is not started: a stand-in on the configured PATH would be needed for that. What is
        // asserted is the command the console announces before the launch succeeds or fails.
        EditorBuffer b = open("build.mk", "all:\n\techo building\n", false);
        FxTestSupport.runOnFx(() -> run.runMakeTarget(b, " "));
        await(
                "the launch to settle",
                () -> log.stream()
                        .anyMatch(e -> e.startsWith("status:")
                                && !e.equals("status:" + tr("status.run.started", "build.mk"))));
        assertTrue(
                statuses().contains(tr("status.run.started", "build.mk")),
                statuses().toString());
        assertEquals(project, run.lastRunDir());
    }

    // --- a compact Java source: the launcher is probed first --------------------------------------------

    @Test
    void aCompactSourceRunsOnTheSelectedJdkAfterItsVersionIsChecked() throws Exception {
        host.settings.setMavenJdkHome(jdk.toString());
        open("Hello.java", "void main() {\n    IO.println(\"hi\");\n}\n", true);
        ops.programArgs.put("Hello.java", "--name Ada");

        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));

        String shown = console();
        assertTrue(shown.contains("java " + project.resolve("Hello.java") + " --name Ada\n"), shown);
        assertTrue(shown.contains("JAVA_HOME=" + jdk + " "), "the selected JDK is the program's JAVA_HOME: " + shown);
        assertTrue(shown.contains("cwd=" + project.toRealPath() + "\n"), shown);
    }

    @Test
    void aCompactSourceIsNotRunOnAJdkThatIsTooOldOrSaysNothingAboutItself() throws Exception {
        Path old = project.resolve("jdk-21");
        standInJava(old, "21.0.2");
        host.settings.setMavenJdkHome(old.toString());
        open("Hello.java", "void main() {\n}\n", true);

        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.needJdk25", 21));
        assertEquals("", console(), "nothing was launched");

        clearLog();
        Path mute = project.resolve("jdk-mute");
        String muteJava = standInJava(mute, null);
        host.settings.setMavenJdkHome(mute.toString());
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.javaVersionUnreadable", muteJava));
        assertEquals("", console());
        assertFalse(FxTestSupport.callOnFx(run::consoleInUse));
    }

    // --- Run Main Class ---------------------------------------------------------------------------------

    @Test
    void runMainClassNeedsAJavaFileInAProject() throws Exception {
        open("notes.txt", "text\n", false);
        FxTestSupport.runOnFx(run::runMainClass);
        assertEquals(List.of(tr("status.run.needJavaFile")), statuses());

        clearLog();
        open("src/main/java/demo/App.java", "package demo;\npublic class App {}\n", false);
        ops.javaRoot = null;
        FxTestSupport.runOnFx(run::runMainClass);
        assertEquals(List.of(tr("status.run.noProject")), statuses());

        clearLog();
        ops.javaRoot = project; // a project, but no jdtls, no pom and no Gradle build to ask
        FxTestSupport.runOnFx(run::runMainClass);
        assertEquals(List.of(tr("status.run.javaUnavailable")), statuses());
    }

    @Test
    void throughJdtlsTheNamedMainClassRunsWithItsFilesArguments() throws Exception {
        open("src/main/java/demo/Helper.java", "package demo;\nclass Helper {}\n", false);
        ops.jdtls = true;
        Path appFile = project.resolve("src/main/java/demo/App.java");
        ops.mainClasses = List.of(
                new JavaMainClass("demo.App", "proj", appFile.toString()), new JavaMainClass("demo.Tool", "proj", ""));
        ops.launchInfo = new JavaLaunchInfo(java, List.of("/m/mod.jar"), List.of("/c/classes"), null, true);
        ops.programArgs.put("App.java", "--port 8080");

        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.App"));
        awaitStatus(tr("status.run.ok"));

        String shown = console();
        assertTrue(
                shown.contains("java --enable-preview -cp /m/mod.jar" + PATH_SEP + "/c/classes demo.App --port 8080\n"),
                shown);
        assertTrue(shown.contains("cwd=" + project.toRealPath() + "\n"), "a main class runs in the project root");
        assertTrue(
                statuses().contains(tr("status.run.started", "App")), statuses().toString());

        clearLog();
        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.Tool")); // no file of its own: no arguments
        awaitStatus(tr("status.run.ok"));
        assertTrue(console().contains("/c/classes demo.Tool\n"), console());
    }

    @Test
    void throughJdtlsAMissingClassOrAFailedResolutionIsReported() throws Exception {
        open("src/main/java/demo/App.java", "package demo;\npublic class App {}\n", false);
        ops.jdtls = true;

        FxTestSupport.runOnFx(run::runMainClass); // jdtls lists none
        ops.mainClasses = List.of(new JavaMainClass("demo.App", "proj", ""));
        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.Missing"));
        FxTestSupport.runOnFx(run::runMainClass); // the only one: resolved, and jdtls has nothing to say
        ops.launchInfo = new JavaLaunchInfo(null, List.of(), List.of(), "The project is still importing", false);
        FxTestSupport.runOnFx(run::runMainClass);

        assertEquals(
                List.of(
                        tr("status.run.noMainClass"),
                        tr("status.run.noMainClass"),
                        tr("status.run.resolveFailed"),
                        "The project is still importing"),
                statuses());
        assertEquals("", console());
    }

    @Test
    void withoutJdtlsAMavenProjectRunsTheFilesOwnMainOnTheResolvedClasspath() throws Exception {
        host.settings.setMavenJdkHome(jdk.toString());
        EditorBuffer b = open(
                "src/main/java/demo/App.java",
                "package demo;\npublic class App {\n  public static void main(String[] args) {}\n}\n",
                false);
        ops.maven = true;
        ops.mavenClasspath = List.of("/repo/dep.jar", "/proj/target/classes");
        ops.programArgs.put("App.java", "x y");

        FxTestSupport.runOnFx(() -> {
            b.getFocusedArea().insertText(0, "// edited\n");
            run.runMainClass();
        });
        awaitStatus(tr("status.run.ok"));

        assertEquals(List.of("App.java"), ops.saved, "the edited file is saved before Maven compiles it");
        assertEquals(List.of(jdk.toString()), ops.mavenJdks, "the classpath is resolved under the selected JDK");
        String shown = console();
        assertTrue(shown.contains("java -cp /repo/dep.jar" + PATH_SEP + "/proj/target/classes demo.App x y\n"), shown);
        assertTrue(
                statuses().contains(tr("status.run.resolvingClasspath")),
                statuses().toString());

        clearLog();
        ops.mavenClasspath = List.of();
        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.App"));
        awaitStatus(tr("status.run.resolveFailed"));

        clearLog();
        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.Elsewhere")); // not a main of this file
        assertEquals(List.of(tr("status.run.noMainInFile")), statuses());
    }

    @Test
    void withoutJdtlsAFileWithNoMainHasNothingToRunAndGradleRunsItsOwnTask() throws Exception {
        open("src/main/java/demo/Lib.java", "package demo;\npublic class Lib {}\n", false);
        ops.maven = true;
        FxTestSupport.runOnFx(run::runMainClass);
        assertEquals(List.of(tr("status.run.noMainInFile")), statuses());

        clearLog();
        ops.maven = false;
        ops.gradle = true;
        FxTestSupport.runOnFx(run::runMainClass);
        assertEquals(List.of(tr("status.run.gradleFallback")), statuses());
        assertEquals(List.of(project.toString()), ops.gradleRuns);
    }

    @Test
    void theGutterRunsTheSavedConfigurationForItsClassElseTheSelectedOne() throws Exception {
        open("src/main/java/demo/App.java", "package demo;\npublic class App {}\n", false);
        ops.jdtls = true;
        ops.maven = true;
        ops.mainClasses =
                List.of(new JavaMainClass("demo.App", "proj", ""), new JavaMainClass("demo.Tool", "proj", ""));
        ops.launchInfo = new JavaLaunchInfo(java, List.of(), List.of("/c/classes"), null);
        ops.configurations = List.of(
                new RunConfiguration("Script", "shell", "x.sh", "", "", "", "", "", "", "", ""),
                javaConfig("Tool with args", "demo.Tool", "", ""),
                javaConfig("The app", "demo.App", "", ""));

        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.App"));
        awaitStatus(tr("status.run.ok"));
        assertTrue(console().contains("java -Dmode=test -cp /c/classes demo.App one two three\n"), console());

        // A class no configuration names runs the toolbar's selection — as capable as the Run button.
        clearLog();
        ops.selected = "Tool with args";
        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.Other"));
        awaitStatus(tr("status.run.ok"));
        assertTrue(console().contains("/c/classes demo.Tool one two three\n"), console());

        // With nothing selected (or a script selected) the plain main-class run is what is left.
        clearLog();
        ops.selected = "Script";
        FxTestSupport.runOnFx(() -> run.runMainClassNamed("demo.Other"));
        assertEquals(List.of(tr("status.run.noMainClass")), statuses());
    }

    // --- a saved configuration --------------------------------------------------------------------------

    @Test
    void aJavaConfigurationRunsWithItsArgumentsEnvironmentWorkingDirectoryAndJdk() throws Exception {
        open("src/main/java/demo/Helper.java", "package demo;\nclass Helper {}\n", false);
        ops.jdtls = true;
        ops.maven = true;
        ops.mainClasses = List.of(new JavaMainClass("demo.App", "proj", ""));
        Path other = project.resolve("jdk-other");
        String otherJava = standInJava(other, "25");
        ops.launchInfo = new JavaLaunchInfo("/never/used/java", List.of(), List.of("/c/classes"), null);
        Path work = Files.createDirectories(project.resolve("work"));

        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("App", "demo.App", work.toString(), other.toString())));
        awaitStatus(tr("status.run.ok"));

        String shown = console();
        assertTrue(shown.contains("java -Dmode=test -cp /c/classes demo.App one two three\n"), shown);
        assertTrue(shown.contains("JAVA_HOME=" + other + " GREETING=hi\n"), "the configuration's own JDK: " + shown);
        assertTrue(shown.contains("cwd=" + work.toRealPath() + "\n"), shown);
        assertTrue(panelStatus().equals(tr("run.exited", 0)), panelStatus());
        assertTrue(otherJava.startsWith(other.toString()));
    }

    @Test
    void aJavaConfigurationThatCannotRunSaysWhatIsMissing() throws Exception {
        FxTestSupport.runOnFx(() -> {
            run.runConfig(javaConfig("Blank", " ", "", ""));
            run.runConfig(javaConfig("File", "App.java", "", ""));
            run.runConfig(javaConfig("NoTab", "demo.App", "", ""));
        });
        assertEquals(
                List.of(
                        tr("status.run.configNeedsMainClass", "Blank"),
                        tr("status.run.mainClassIsAFile", "App.java"),
                        tr("status.run.configNeedsJavaFile")),
                statuses());
        assertEquals(List.of("Blank", "File"), ops.edited);

        clearLog();
        open("src/main/java/demo/App.java", "package demo;\npublic class App {}\n", false);
        ops.javaRoot = null;
        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("Loose", "demo.App", "", "")));
        ops.javaRoot = project;
        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("NoTool", "demo.App", "", "")));
        ops.jdtls = true;
        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("Unresolved", "demo.App", "", "")));
        ops.launchInfo = new JavaLaunchInfo(null, List.of(), List.of(), "No such class: demo.App", false);
        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("Refused", "demo.App", "", "")));
        assertEquals(
                List.of(
                        tr("status.run.noProject"),
                        tr("status.run.javaUnavailable"),
                        tr("status.run.resolveFailed"),
                        "No such class: demo.App"),
                statuses());
        assertEquals("", console());
    }

    @Test
    void withoutJdtlsAJavaConfigurationOfAMavenProjectUsesMavensClasspath() throws Exception {
        host.settings.setMavenJdkHome(jdk.toString());
        open("src/main/java/demo/App.java", "package demo;\npublic class App {}\n", false);
        ops.maven = true;
        ops.mavenClasspath = List.of("/proj/target/classes");

        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("App", "demo.App", "", "")));
        awaitStatus(tr("status.run.ok"));
        assertTrue(console().contains("java -Dmode=test -cp /proj/target/classes demo.App one two three\n"), console());

        clearLog();
        ops.mavenClasspath = null;
        FxTestSupport.runOnFx(() -> run.runConfig(javaConfig("App", "demo.App", "", "")));
        assertEquals(List.of(tr("status.run.resolvingClasspath"), tr("status.run.resolveFailed")), statuses());
    }

    @Test
    void aScriptConfigurationWithoutATargetOrOfAnUnknownTypeIsNotRun() throws Exception {
        FxTestSupport.runOnFx(() -> {
            run.runConfig(new RunConfiguration("NoScript", "shell", " ", "", "", "", "", "", "", "", ""));
            run.runConfig(new RunConfiguration("Odd", "cobol", "x.cbl", "", "", "", "", "", "", "", ""));
        });

        assertEquals(
                List.of(tr("status.run.configNeedsTarget", "NoScript"), tr("status.run.configBadType", "Odd")),
                statuses());
        assertEquals(List.of("NoScript"), ops.edited, "a form cannot put an unknown type right");
        assertEquals("", console());
    }

    @Test
    void aBeforeLaunchCommandThatCannotStartIsShownAsAFailedRun() throws Exception {
        Files.writeString(project.resolve("x.sh"), "echo ran\n");
        String missing = project.resolve("no-such-tool").toString();

        FxTestSupport.runOnFx(() -> run.runConfig(
                new RunConfiguration("Built", "shell", "x.sh", "", "", "", "", project.toString(), "", missing, "")));
        await(
                "the failure",
                () -> log.stream()
                        .anyMatch(e -> e.startsWith("status:")
                                && !e.equals("status:" + tr("status.run.beforeLaunch", "Built"))));
        FxTestSupport.drainFx();

        assertTrue(panelStatus().startsWith(tr("run.failed", "").trim().split(" ")[0]), panelStatus());
        assertFalse(console().contains("ran"), "the script the step was gating did not run");
        assertFalse(FxTestSupport.callOnFx(run::isRunning));
        assertEquals(project, run.lastRunDir());
    }
}
