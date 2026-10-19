package com.editora.ui;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        final List<String> terminalRuns = new ArrayList<>();
        java.util.function.IntConsumer terminalExit;

        @Override
        public void openToolWindow() {
            toolWindowOpened++;
        }

        @Override
        public com.editora.terminal.ProgramRun runInTerminal(
                String title,
                Path dir,
                List<String> command,
                Map<String, String> env,
                java.util.function.IntConsumer onExit) {
            terminalRuns.add(title + " | " + dir.getFileName() + " | " + command.getLast() + " | " + env.keySet());
            terminalRunning = true;
            terminalExit = code -> {
                terminalRunning = false;
                onExit.accept(code);
            };
            return new com.editora.terminal.ProgramRun() {
                @Override
                public long pid() {
                    return 42;
                }

                @Override
                public boolean isRunning() {
                    return terminalRunning;
                }

                @Override
                public void stop() {
                    terminalStops++;
                    terminalExit.accept(-1);
                }
            };
        }

        volatile boolean terminalRunning;
        int terminalStops;

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

        final List<String> httpRuns = new ArrayList<>();

        @Override
        public boolean runHttpFile(EditorBuffer b) {
            httpRuns.add(String.valueOf(b.getPath().getFileName()));
            return true;
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

    /** The program's output: the console without the muted command line a script run starts it with. */
    private String console() throws Exception {
        CodeArea output = FxTestSupport.field(run.panel(), "output");
        return FxTestSupport.callOnFx(() -> {
            String text = output.getText();
            boolean header = !text.isEmpty() && output.getStyleOfChar(0).contains("run-command");
            return header ? text.substring(text.indexOf('\n') + 1) : text;
        });
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

        // It says what Run File does run (review C1), not only that this is not it.
        assertEquals(List.of(tr("status.run.notRunnable"), tr("status.run.notRunnable")), statuses());
        assertFalse(FxTestSupport.callOnFx(run::consoleInUse));
        assertEquals(tr("run.idle"), panelStatus());
    }

    @Test
    void runFileOnARequestFileSendsItsRequestsInsteadOfLaunchingJava() throws Exception {
        // It used to run `java api.http` ("Could not find or load main class"): the requests make it runnable.
        open("api.http", "### one\nGET http://127.0.0.1:1/\n", false);
        FxTestSupport.runOnFx(run::runActiveFile);
        FxTestSupport.runOnFx(run::runActiveFileWithArgs);

        assertEquals(List.of("api.http", "api.http"), ops.httpRuns);
        assertEquals(0, ops.toolWindowOpened, "nothing was launched in the Run console");
        assertFalse(FxTestSupport.callOnFx(run::consoleInUse));
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

    private void awaitConsole(String text) throws Exception {
        await("console containing '" + text + "'", () -> {
            try {
                return console().contains(text);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
    }

    private void type(String line) throws Exception {
        TextField input = FxTestSupport.field(run.panel(), "input");
        FxTestSupport.runOnFx(() -> {
            input.setText(line);
            input.fireEvent(new javafx.event.ActionEvent());
        });
    }

    @Test
    void theShebangsInterpreterAndFlagsAreHonoured() throws Exception {
        // It always ran `bash <file>`: the -e here was dropped and the script went on past `false`.
        open("strict.sh", "#!/usr/bin/env -S bash -e\nfalse\necho AFTER\n", true);

        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.exit", 1));

        assertEquals("", console());
        assertTrue(panelStatus().contains(tr("run.exited", 1)), panelStatus());
    }

    @Test
    void aShShebangRunsShAndAZshFileRunsZsh() throws Exception {
        // Ask /bin/sh itself: on macOS it is its own binary but runs bash 3.2 in sh mode, so BASH_VERSION is set
        // and there is nothing to tell apart; the file name alone does not show that.
        Process probe = new ProcessBuilder("/bin/sh", "-c", "echo \"${BASH_VERSION:-not bash}\"")
                .redirectErrorStream(true)
                .start();
        String shSays = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        probe.waitFor();
        org.junit.jupiter.api.Assumptions.assumeTrue(
                "not bash\n".equals(shSays), "/bin/sh is bash here: nothing to tell apart");
        open("posix.sh", "#!/bin/sh\necho \"${BASH_VERSION:-not bash}\"\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));
        assertEquals("not bash\n", console());

        // No shebang in a .zsh: zsh, whether or not it is installed (then the launch names it).
        clearLog();
        open("prompt.zsh", "echo \"zsh ${ZSH_VERSION:+yes}\"\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        await(
                "the run to end",
                () -> statuses().stream()
                        .anyMatch(m -> m.startsWith(tr("status.run.ok"))
                                || m.startsWith(tr("status.run.failed", "").strip())));
        FxTestSupport.drainFx();
        assertTrue(console().equals("zsh yes\n") || panelStatus().contains("zsh"), panelStatus() + " / " + console());
    }

    @Test
    void readPromptsShowAndEndInputFinishesAReadLoop() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0, "needs bash");
        open(
                "ask.sh",
                "#!/usr/bin/env bash\nread -r -p \"Your name: \" n\necho \"hi [$n]\"\n"
                        + "while read -r l; do echo \"got [$l]\"; done\necho \"loop ended\"\n",
                true);

        FxTestSupport.runOnFx(run::runActiveFile);
        awaitConsole("Your name: "); // bash prints nothing for read -p on a pipe: the user saw an empty console
        type("Bob");
        awaitConsole("hi [Bob]");
        type("x");
        awaitConsole("got [x]");
        assertTrue(FxTestSupport.callOnFx(run::isRunning), "the loop waits for more input");

        FxTestSupport.runOnFx(run::endInput); // what End Input and Ctrl+D on an empty line do
        awaitStatus(tr("status.run.ok"));
        assertTrue(console().endsWith("loop ended\n"), console());
        TextField input = FxTestSupport.field(run.panel(), "input");
        assertTrue(FxTestSupport.callOnFx(input::isDisabled));
    }

    @Test
    void theRunWindowSaysWhichRuntimeTheProgramRunsUnder() throws Exception {
        Process ask = new ProcessBuilder(
                        "bash", "-c", "echo \"${BASH_VERSINFO[0]}.${BASH_VERSINFO[1]}.${BASH_VERSINFO[2]}\"")
                .redirectErrorStream(true)
                .start();
        String bashVersion = new String(ask.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        org.junit.jupiter.api.Assumptions.assumeTrue(ask.waitFor() == 0, "needs bash");
        open("which.sh", "#!/usr/bin/env bash\necho hi\n", true);

        FxTestSupport.runOnFx(run::runActiveFile);
        awaitRuntimeChip("Bash " + bashVersion);
        awaitStatus(tr("status.run.ok"));
        assertEquals("Bash " + bashVersion, runtimeChip(), "the chip stays after the program exits");

        // A runtime it cannot ask (a plain sh) shows no chip — never the previous run's.
        clearLog();
        open("plain.sh", "#!/bin/sh\necho hi\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));
        assertEquals("", runtimeChip());
    }

    /** Polls outside the log's lock: the chip is read on the FX thread, which takes that lock to log. */
    private void awaitRuntimeChip(String expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!expected.equals(runtimeChip())) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("runtime chip never said '" + expected + "', it says '" + runtimeChip() + "'");
            }
            Thread.sleep(20);
        }
    }

    private String runtimeChip() {
        try {
            return FxTestSupport.callOnFx(run.panel()::runtimeText);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void ctrlDOnAnEmptyInputLineEndsTheInput() throws Exception {
        open("cat.sh", "#!/bin/sh\ncat\necho \"cat done\"\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        await("the run to start", () -> {
            try {
                return FxTestSupport.callOnFx(run::isRunning);
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        type("one");
        awaitConsole("one\none\n"); // the echo of what was typed, then cat's copy
        TextField input = FxTestSupport.field(run.panel(), "input");
        FxTestSupport.runOnFx(() -> input.fireEvent(new javafx.scene.input.KeyEvent(
                javafx.scene.input.KeyEvent.KEY_PRESSED,
                "",
                "",
                javafx.scene.input.KeyCode.D,
                false,
                true,
                false,
                false)));
        awaitStatus(tr("status.run.ok"));
        assertTrue(console().endsWith("cat done\n"), console());
    }

    @Test
    void anUnclosedQuoteInTheArgumentsRunsNothingAndSaysWhy() throws Exception {
        open("args.sh", "#!/bin/sh\nfor a in \"$@\"; do echo \"[$a]\"; done\n", true);
        ops.programArgs.put("args.sh", "O'Brien tail");
        FxTestSupport.runOnFx(run::runActiveFile);
        FxTestSupport.drainFx();
        assertTrue(
                statuses().contains(tr("status.run.argsUnclosedQuote", "'")),
                statuses().toString());
        assertFalse(FxTestSupport.callOnFx(run::isRunning));

        clearLog();
        ops.programArgs.put("args.sh", "O\\'Brien tail\\ x \"say \\\"hi\\\"\"");
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));
        assertEquals("[O'Brien]\n[tail x]\n[say \"hi\"]\n", console());
    }

    @Test
    void aShellConfigurationsInterpreterOverridesTheShebang() throws Exception {
        Files.createDirectories(project.resolve("sub"));
        Files.writeString(project.resolve("sub/cfg.sh"), "#!/bin/sh\nfalse\necho \"AFTER in $(basename \"$PWD\")\"\n");
        RunConfiguration cfg =
                new RunConfiguration("Strict", "shell", "cfg.sh", "", "", "", "", "sub", "", "", "", "bash", "-e");

        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        awaitStatus(tr("status.run.exit", 1));
        assertEquals("", console(), "bash -e from the configuration stopped at `false`");

        // Without the override the shebang's plain sh runs on; the relative directory is under the project.
        clearLog();
        RunConfiguration plain = new RunConfiguration("Plain", "shell", "cfg.sh", "", "", "", "", "sub", "", "", "");
        FxTestSupport.runOnFx(() -> run.runConfig(plain));
        awaitStatus(tr("status.run.ok"));
        assertEquals("AFTER in sub\n", console());
    }

    @Test
    void aConfigurationThatRunsInATerminalGoesThereNotToTheConsole() throws Exception {
        Files.createDirectories(project.resolve("sub"));
        Files.writeString(project.resolve("sub/menu.sh"), "#!/bin/bash\nread -p 'Pick: ' x\n");
        RunConfiguration cfg = new RunConfiguration(
                "Menu", "shell", "menu.sh", "", "", "", "", "sub", "MODE=tty", "", "", "", "", true);

        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        awaitStatus(tr("status.run.inTerminal", "Menu"));

        assertEquals(1, ops.terminalRuns.size(), ops.terminalRuns.toString());
        String launched = ops.terminalRuns.getFirst();
        assertTrue(launched.startsWith("Menu | sub | "), launched);
        assertTrue(launched.contains("menu.sh"), launched);
        assertTrue(launched.contains("MODE"), "the configuration's own variables: " + launched);
        assertFalse(launched.contains("BASH_ENV"), "a terminal needs no read -p shim: " + launched);
        assertTrue(FxTestSupport.callOnFx(run::isRunning), "Run's Stop and its stripe see the terminal's program");
        assertEquals("", console());

        FxTestSupport.runOnFx(() -> ops.terminalExit.accept(2));
        awaitStatus(tr("status.run.exit", 2));
        assertFalse(FxTestSupport.callOnFx(run::isRunning), "and its end");

        // Run's Stop ends a program running in a terminal.
        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        awaitStatus(tr("status.run.inTerminal", "Menu"));
        FxTestSupport.runOnFx(run::stopRun);
        assertEquals(1, ops.terminalStops);
        awaitStatus(tr("status.run.stopped"));
        assertFalse(FxTestSupport.callOnFx(run::isRunning));

        // The next configuration without the flag runs in the console, as ever.
        RunConfiguration plain = new RunConfiguration("Plain", "shell", "menu.sh", "", "", "", "", "sub", "", "", "");
        Files.writeString(project.resolve("sub/menu.sh"), "#!/bin/sh\necho console\n");
        FxTestSupport.runOnFx(() -> run.runConfig(plain));
        awaitStatus(tr("status.run.ok"));
        assertEquals("console\n", console());
        assertEquals(2, ops.terminalRuns.size());
    }

    @Test
    void aShellConfigurationNamesAMissingDirectoryOrScript() throws Exception {
        RunConfiguration noDir = new RunConfiguration("NoDir", "shell", "x.sh", "", "", "", "", "nope", "", "", "");
        FxTestSupport.runOnFx(() -> run.runConfig(noDir));
        FxTestSupport.drainFx();
        assertEquals(
                List.of(tr(
                        "status.run.workingDirMissing",
                        "NoDir",
                        project.resolve("nope").toString())),
                statuses());
        assertEquals(List.of("NoDir"), ops.edited, "its form opens at what to fix");

        clearLog();
        RunConfiguration noScript =
                new RunConfiguration("NoScript", "shell", "gone.sh", "", "", "", "", "", "", "", "");
        FxTestSupport.runOnFx(() -> run.runConfig(noScript));
        FxTestSupport.drainFx();
        assertEquals(
                List.of(tr(
                        "status.run.scriptMissing",
                        "NoScript",
                        project.resolve("gone.sh").toString())),
                statuses());
        assertFalse(FxTestSupport.callOnFx(run::consoleInUse), "nothing was launched");
    }

    @Test
    void aShellConfigurationsEnvironmentExpandsInheritedVariables() throws Exception {
        Files.writeString(
                project.resolve("env.sh"), "echo \"$FOO|$GREETING\"\ncommand -v sh >/dev/null && echo found\n");
        RunConfiguration cfg = new RunConfiguration(
                "Env",
                "shell",
                "env.sh",
                "",
                "",
                "",
                "",
                "",
                "PATH=/opt/none:$PATH FOO=$HOME GREETING='$HOME'",
                "",
                "");
        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        awaitStatus(tr("status.run.ok"));
        assertEquals(System.getenv("HOME") + "|$HOME\nfound\n", console(), "PATH kept its real value after /opt/none");
    }

    @Test
    void stopIsReportedAsStoppedNotAsAnExitCode() throws Exception {
        open("wait.sh", "#!/bin/sh\necho started\nsleep 300\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitConsole("started\n");
        clearLog();
        FxTestSupport.runOnFx(run::stopRun);
        // Once from Stop itself, once from the exit (which said "Run finished (exit code 143)").
        await(
                "the stopped exit",
                () -> log.stream()
                                .filter(e -> e.equals("status:" + tr("status.run.stopped")))
                                .count()
                        >= 2);
        FxTestSupport.drainFx();
        // It said "Process finished with exit code 143", and the status bar "Run finished (exit code 143)".
        assertEquals(tr("run.stopped"), panelStatus());
        assertEquals(
                tr("status.run.stopped"),
                statuses().get(statuses().size() - 1),
                statuses().toString());
    }

    @Test
    void runWithTraceLinksEveryTracedLineToItsSource() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0, "needs bash");
        EditorBuffer b = open("traced.sh", "#!/usr/bin/env bash\nx=1\necho \"x is $x\"\n", true);
        FxTestSupport.runOnFx(run::runActiveFileWithTrace);
        awaitStatus(tr("status.run.ok"));
        String file = project.resolve("traced.sh").toString();
        // stdout and stderr (where the trace goes) are two pipes: compare the lines, not their interleaving.
        assertEquals(
                Set.of("+" + file + ":2: x=1", "+" + file + ":3: echo 'x is 1'", "x is 1"),
                Set.copyOf(console().lines().toList()),
                console());
        String traced =
                console().lines().filter(l -> l.contains(":3: ")).findFirst().orElseThrow();
        StackTraceLinks.Link link = StackTraceLinks.parse(traced);
        assertEquals(file, link.file());
        assertEquals(3, link.line());
        assertTrue(FxTestSupport.callOnFx(() -> run.editorMenuItems(b)).stream()
                .anyMatch(i -> i.getText().equals(tr("editmenu.runWithTrace"))));
    }

    @Test
    void runWithTraceSaysWhenItCannotTrace() throws Exception {
        open("prompt.zsh", "echo hi\n", true);
        FxTestSupport.runOnFx(run::runActiveFileWithTrace);
        assertEquals(List.of(tr("status.run.traceUnsupported", "prompt.zsh", "zsh")), statuses());
        clearLog();
        open("notes.txt", "x\n", false);
        FxTestSupport.runOnFx(run::runActiveFileWithTrace);
        assertEquals(List.of(tr("status.run.traceNotShell")), statuses());
        assertFalse(FxTestSupport.callOnFx(run::consoleInUse));
    }

    @Test
    void aShellRunGetsAColourTerminalType() throws Exception {
        open("term.sh", "#!/bin/sh\necho \"TERM=$TERM\"\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));
        assertEquals("TERM=xterm-256color\n", console());
    }

    @Test
    void aShellConfigurationWithXInItsOptionsTracesWithLinks() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                new ProcessBuilder("bash", "-c", "exit 0").start().waitFor() == 0, "needs bash");
        Files.writeString(project.resolve("cfg.sh"), "echo hi\n");
        RunConfiguration cfg =
                new RunConfiguration("T", "shell", "cfg.sh", "", "", "", "", "", "", "", "", "bash", "-x");
        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        awaitStatus(tr("status.run.ok"));
        assertEquals(
                Set.of("+cfg.sh:1: echo hi", "hi"), Set.copyOf(console().lines().toList()));
    }

    @Test
    void rerunSavesTheScriptItRerunsAsWellAsTheTabInFront() throws Exception {
        EditorBuffer script = open("again.sh", "#!/bin/sh\necho one\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));
        FxTestSupport.runOnFx(() -> script.getFocusedArea().insertText(0, "# edited\n"));
        EditorBuffer readme = open("README.md", "# readme\n", false);
        FxTestSupport.runOnFx(() -> readme.getFocusedArea().insertText(0, "x"));
        ops.saved.clear();
        clearLog();

        FxTestSupport.runOnFx(run::rerunLast);
        awaitStatus(tr("status.run.ok"));
        assertEquals(List.of("again.sh", "README.md"), ops.saved, "it saved only README.md and ran the old again.sh");
    }

    @Test
    void aScriptConfigurationSavesItsOwnScript() throws Exception {
        EditorBuffer script = open("cfg2.sh", "#!/bin/sh\necho one\n", true);
        FxTestSupport.runOnFx(() -> script.getFocusedArea().insertText(0, "# edited\n"));
        EditorBuffer readme = open("README.md", "# readme\n", false);
        FxTestSupport.runOnFx(() -> readme.getFocusedArea().insertText(0, "x"));
        ops.saved.clear();
        RunConfiguration cfg = new RunConfiguration("C", "shell", "cfg2.sh", "", "", "", "", "", "", "", "");
        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        awaitStatus(tr("status.run.ok"));
        assertEquals(List.of("cfg2.sh", "README.md"), ops.saved, "the script first, then the tab in front");
    }

    @Test
    void theShebangsEnvUnsetAndChdirApply() throws Exception {
        Files.createDirectories(project.resolve("sub"));
        open("envopts.sh", "#!/usr/bin/env -u HOME -C sub sh\necho \"[${HOME-unset}] $(basename \"$PWD\")\"\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        awaitStatus(tr("status.run.ok"));
        // `-u HOME` was taken for the interpreter: Run tried to start a program called HOME.
        assertEquals("[unset] sub\n", console());

        clearLog();
        open("nodir.sh", "#!/usr/bin/env -C missing sh\necho hi\n", true);
        FxTestSupport.runOnFx(run::runActiveFile);
        FxTestSupport.drainFx();
        assertEquals(
                List.of(tr(
                        "status.run.shebangDirMissing",
                        project.resolve("missing").toString())),
                statuses());
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

    /**
     * A Java configuration's before-launch step is a build: it runs in the project root whatever folder the
     * program runs in — with a working directory of "sub", "mvn compile" found no pom (review A12).
     */
    @Test
    @org.junit.jupiter.api.condition.DisabledOnOs(org.junit.jupiter.api.condition.OS.WINDOWS)
    void aJavaConfigurationsBeforeLaunchStepRunsInTheProjectRoot() throws Exception {
        open("src/main/java/demo/App.java", "package demo;\npublic class App {}\n", false);
        Path sub = Files.createDirectories(project.resolve("sub"));
        RunConfiguration cfg = new RunConfiguration(
                "Sub", "java", "", "demo.App", "", "", "", sub.toString(), "", "sh -c 'pwd; exit 3'", "");

        FxTestSupport.runOnFx(() -> run.runConfig(cfg));
        String real = project.toRealPath().toString();
        String plain = project.toString();
        java.util.concurrent.atomic.AtomicReference<String> shown =
                new java.util.concurrent.atomic.AtomicReference<>("");
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (!(shown.get().contains(real + "\n") || shown.get().contains(plain + "\n")) && System.nanoTime() < end) {
            shown.set(console());
            Thread.onSpinWait();
        }
        assertTrue(shown.get().contains(real + "\n") || shown.get().contains(plain + "\n"), shown.get());
        assertFalse(shown.get().contains(sub + "\n"), shown.get());
    }
}
