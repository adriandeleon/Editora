package com.editora.container;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import com.editora.process.ExecSpec;
import com.editora.process.ExecTargets;
import com.editora.process.ProcessRegistry;
import com.editora.process.ProcessRunner;
import com.editora.process.RemoteProcess;
import com.editora.run.RunService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Commands for a folder bind-mounted into a <b>real</b> container. Opt-in: needs an engine and an image that
 * is already on this machine (nothing is pulled), for example
 *
 * <pre>
 * mvn test -Dtest=ContainerProbeFxTest -Dgroups=fx -Dcontainer.probe=podman -Dcontainer.probe.image=eclipse-temurin:25-jdk
 * </pre>
 *
 * The image needs {@code sh}, {@code sleep} and {@code ps}. A Java debug session in the container also needs a
 * jdtls folder ({@code -Dcontainer.probe.jdtls=<its root>}, mounted read-only) and the java-debug plugin jar
 * ({@code -Dcontainer.probe.javaDebug=<jar>}, which Editora copies into the container's cache), and an image with a
 * JDK; without them that test is skipped.
 */
@Tag("fx")
@EnabledIfSystemProperty(named = "container.probe", matches = ".+")
class ContainerProbeFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path dir;

    private ContainerEngine engine;
    private String name;
    private Path folder;
    private ContainerExecTarget target;
    private com.editora.process.ExecTargets.Resolver resolver;

    private ProcessRunner.Result engine(String... args) {
        List<String> argv = new java.util.ArrayList<>(engine.command());
        argv.addAll(List.of(args));
        return ProcessRunner.run(null, Duration.ofSeconds(120), argv);
    }

    /** jdtls's own launcher is a Python script; an image without Python runs the same JVM with this. */
    private static final String JDTLS_WITHOUT_PYTHON = """
            #!/bin/sh
            # What jdtls's bin/jdtls (jdtls.py) runs, for an image with no python3.
            exec java -Djdk.xml.maxGeneralEntitySizeLimit=0 -Djdk.xml.totalEntitySizeLimit=0 \\
              -Declipse.application=org.eclipse.jdt.ls.core.id1 -Dosgi.bundles.defaultStartLevel=4 \\
              -Declipse.product=org.eclipse.jdt.ls.core.product -Dosgi.checkConfiguration=true \\
              -Dosgi.sharedConfiguration.area=/opt/jdtls/config_linux -Dosgi.sharedConfiguration.area.readOnly=true \\
              -Dosgi.configuration.cascaded=true -Xms1G --add-modules=ALL-SYSTEM \\
              --add-opens java.base/java.util=ALL-UNNAMED --add-opens java.base/java.lang=ALL-UNNAMED \\
              -jar "$(ls /opt/jdtls/plugins/org.eclipse.equinox.launcher_*.jar | head -n 1)" "$@"
            """;

    private Path loose;

    @BeforeEach
    void setUp() throws Exception {
        engine = ContainerEngine.of(System.getProperty("container.probe"));
        folder = Files.createDirectories(dir.toRealPath().resolve("app"));
        Files.writeString(folder.resolve("a.txt"), "hola\n");
        loose = Files.createDirectories(dir.toRealPath().resolve("loose"));
        name = "editora-probe-" + Long.toHexString(System.nanoTime());
        List<String> run = new java.util.ArrayList<>(List.of(
                "run",
                "-d",
                "--name",
                name,
                "-v",
                folder + ":/workspaces/app:Z",
                "-v",
                loose + ":/workspaces/loose:Z"));
        String jdtls = System.getProperty("container.probe.jdtls");
        if (jdtls != null && !jdtls.isBlank()) {
            Path bin = Files.createDirectories(dir.resolve("probe-bin"));
            Path launcher = Files.writeString(bin.resolve("jdtls"), JDTLS_WITHOUT_PYTHON);
            launcher.toFile().setExecutable(true, false);
            // Read-only and not relabelled: it is the user's own jdtls install.
            run.addAll(List.of("-v", jdtls + ":/opt/jdtls:ro", "-v", bin + ":/opt/editora-probe:ro"));
        }
        run.addAll(List.of(System.getProperty("container.probe.image", "eclipse-temurin:25-jdk"), "sleep", "infinity"));
        ProcessRunner.Result started = engine(run.toArray(String[]::new));
        assertTrue(started.ok(), "the container did not start: " + started.message());
        ContainerInfo info = engine.inspect(name);
        assertNotNull(info, "inspect");
        target = new ContainerExecTarget(engine, info);
        resolver = path -> target.covers(path) ? target : null;
        ExecTargets.setRemoteEnabled(() -> true);
        ExecTargets.registerMapped(resolver);
        ExecTargets.registerMapped(ContainerBindings.shared());
    }

    @AfterEach
    void tearDown() {
        ExecTargets.unregisterMapped(resolver);
        ExecTargets.unregisterMapped(ContainerBindings.shared());
        ExecTargets.setRemoteEnabled(null);
        // Under Docker the container's root wrote into the mounts (javac's classes, jdtls's .settings) as root
        // here too: hand them back to each mount folder's owner as the container sees it (under rootless Podman
        // that is root, which is this user here), or the test cannot remove its own temporary folder.
        engine(
                "exec",
                "-u",
                "0",
                name,
                "sh",
                "-c",
                "for d; do chown -R \"$(stat -c %u:%g \"$d\")\" \"$d\"; done",
                "sh",
                "/workspaces/app",
                "/workspaces/loose");
        engine("rm", "-f", name);
    }

    private static void onFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        javafx.application.Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private List<String> processes() {
        ProcessRunner.Result ps = engine("exec", name, "ps", "-eo", "args=");
        return ps.out().lines().map(String::strip).toList();
    }

    @Test
    void aTerminalForTheFolderIsALoginShellInTheContainerAndClosingItEndsIt() throws Exception {
        com.editora.process.TerminalChannel shell =
                ExecTargets.forPath(folder).startTerminal(new com.editora.process.TerminalSpec(folder, 100, 30));
        StringBuffer seen = new StringBuffer();
        Thread.ofVirtual().start(() -> {
            byte[] buf = new byte[4096];
            try {
                int n;
                while ((n = shell.output().read(buf)) > 0) {
                    seen.append(new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8));
                }
            } catch (java.io.IOException ended) {
                // closed
            }
        });
        try {
            type(shell, "stty size; tty; pwd; echo $TERM\n");
            awaitOutput(seen, "30 100");
            awaitOutput(seen, "/workspaces/app");
            assertTrue(seen.toString().contains("/dev/pts/"), "a tty in the container: " + seen);
            assertTrue(seen.toString().contains("xterm-256color"), "TERM: " + seen);
            assertFalse(seen.toString().contains("EDITORA"), "the pid line is the transport's: " + seen);
            assertTrue(((com.editora.terminal.PtyChannel) shell).pid() > 0, "the shell's pid in the container");

            shell.resize(120, 40);
            type(shell, "stty size\n");
            awaitOutput(seen, "40 120");

            type(shell, "exec sleep 6009\n");
            awaitTrue("the sleep up in the container", () -> processes().contains("sleep 6009"));
        } finally {
            shell.close();
        }
        // The engine keeps a program whose exec client went; closing hangs the shell up in the container.
        awaitTrue("the sleep gone from the container", () -> !processes().contains("sleep 6009"));
    }

    private static void type(com.editora.process.TerminalChannel shell, String text) throws java.io.IOException {
        shell.input().write(text.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        shell.input().flush();
    }

    private static void awaitOutput(StringBuffer seen, String needle) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && !seen.toString().contains(needle)) {
            Thread.sleep(50);
        }
        assertTrue(
                seen.toString().contains(needle), () -> "\"" + needle + "\" in the terminal's output: [" + seen + "]");
    }

    private static void awaitTrue(String what, java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline && !condition.getAsBoolean()) {
            Thread.sleep(100);
        }
        assertTrue(condition.getAsBoolean(), "timed out: " + what);
    }

    @Test
    void aCommandForTheFolderRunsInTheContainerWhereItIsMounted() {
        assertFalse(ExecTargets.runsHere(folder.resolve("a.txt")));

        ProcessRunner.Result r = ProcessRunner.run(
                folder,
                Duration.ofSeconds(60),
                List.of("sh", "-c", "pwd; cat a.txt; test -f /.dockerenv -o -f /run/.containerenv && echo inside"));

        assertTrue(r.ok(), r.message());
        assertEquals(
                List.of("/workspaces/app", "hola", "inside"), r.out().lines().toList());
        assertEquals("/workspaces/app/a.txt", target.toTargetPath(folder.resolve("a.txt")));
        assertEquals(folder.resolve("a.txt"), target.toPath("/workspaces/app/a.txt"));
    }

    @Test
    void editorasWorkingFolderIsInTheContainersHome() throws Exception {
        com.editora.process.TargetCache cache = target.cache();
        String home = target.env().home();
        Path jar = Files.writeString(dir.resolve("debug-0.53.jar"), "jar bytes");

        String copy = cache.putFile("java-debug/debug-0.53.jar", jar);
        String config = cache.putText("git-global-0011.config", "[core]\n\tpager = cat\n");
        String workspace = cache.makeDirectories("jdtls-workspaces/abc123");

        assertEquals(home + "/.cache/editora/java-debug/debug-0.53.jar", copy);
        ProcessRunner.Result seen = engine(
                "exec",
                name,
                "sh",
                "-c",
                "cat \"$0\"; cat \"$1\"; test -d \"$2\" && echo folder",
                copy,
                config,
                workspace);
        assertTrue(seen.ok(), seen.message());
        assertEquals("jar bytes[core]\n\tpager = cat\nfolder\n", seen.out());

        cache.delete("jdtls-workspaces");
        ProcessRunner.Result gone = engine("exec", name, "test", "-e", home + "/.cache/editora/jdtls-workspaces");
        assertFalse(gone.ok(), "removed in the container");
    }

    @Test
    void aProgramACrashedEditoraLeftInTheContainerIsStoppedByTheNextOne() throws Exception {
        RemoteProcess p = (RemoteProcess) target.start(ExecSpec.of(
                        List.of("sh", "-c", "sleep 300 & sleep 301; wait"),
                        folder,
                        Map.of(),
                        ExecSpec.Locale.PARSE_STABLE)
                .withLongLived(true));
        List<String> record = p.orphanRecord();
        assertEquals(ContainerExecTarget.ORPHAN_KIND, record.get(0), String.valueOf(record));
        List<String> fields = record.subList(1, record.size());

        // The crash: the engine's client here dies with Editora; the program in the container does not.
        List<ProcessHandle> clients = ProcessHandle.current()
                .children()
                .filter(h ->
                        h.info().commandLine().orElse("").contains(target.info().id()))
                .toList();
        assertFalse(clients.isEmpty(), "the exec client is a child of this process");
        clients.forEach(ProcessHandle::destroyForcibly);
        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the client ended");
        Thread.sleep(500);
        assertTrue(processes().stream().anyMatch(a -> a.startsWith("sleep 30")), "left running: " + processes());

        List<String> other = new java.util.ArrayList<>(fields);
        other.set(3, fields.get(3) + "1");
        assertFalse(ContainerExecTarget.reapOrphan(other), "another start time: not that program");
        assertTrue(processes().stream().anyMatch(a -> a.startsWith("sleep 30")));

        assertTrue(ContainerExecTarget.reapOrphan(fields));
        List<String> left = processes();
        for (int i = 0; i < 50 && left.stream().anyMatch(a -> a.startsWith("sleep 30")); i++) {
            Thread.sleep(200);
            left = processes();
        }
        assertFalse(left.stream().anyMatch(a -> a.startsWith("sleep 30")), "stopped with its group: " + left);
        assertFalse(ContainerExecTarget.reapOrphan(fields), "gone: nothing left to stop");
    }

    private static void await(String what, int seconds, java.util.function.BooleanSupplier done)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!done.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("timed out: " + what);
            }
            Thread.sleep(200);
        }
    }

    /** Debugs {@code file} to a breakpoint on {@code line}, then lets it finish; what happened, for the report. */
    private static String debugToBreakpoint(
            com.editora.dap.DapManager dap, Path file, Path projectRoot, int line, String javaDebug) throws Exception {
        List<List<com.editora.dap.DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
        List<String> output = new CopyOnWriteArrayList<>();
        List<String> errors = new CopyOnWriteArrayList<>();
        onFx(() -> {
            dap.configure(true, javaDebug);
            dap.setProgramConsole(true);
            dap.setListener(new com.editora.dap.DapManager.Listener() {
                @Override
                public void onState(com.editora.dap.DapManager.State state) {}

                @Override
                public void onStopped(
                        int threadId, String reason, List<com.editora.dap.DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {
                    output.add(text);
                }

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
            dap.setBreakpointsSupplier(() -> List.of(new com.editora.dap.DapModels.FileBreakpoints(
                    file, List.of(new com.editora.dap.DapModels.LineBreakpoint(line, null, null)))));
            dap.startLaunch(file, "java", (options, chosen) -> chosen.accept(options.get(0)), "", projectRoot);
        });
        await("stopped at the breakpoint (errors " + errors + ")", 180, () -> !stops.isEmpty() || !errors.isEmpty());
        assertEquals(List.of(), errors);
        com.editora.dap.DapModels.StackFrameInfo top = stops.get(0).get(0);
        assertEquals(file, top.file(), "the frame is this machine's file");
        assertEquals(line, top.line(), "on the breakpoint's line");
        onFx(dap::resume);
        await(
                "the program's output after Continue: " + output,
                60,
                () -> String.join("", output).contains("sum 2"));
        return "stopped at " + file.getFileName() + ":" + (line + 1);
    }

    @Test
    void aJavaProgramIsDebuggedInTheContainerByItsJdtls() throws Exception {
        String jdtls = System.getProperty("container.probe.jdtls");
        String javaDebug = System.getProperty("container.probe.javaDebug");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                jdtls != null && javaDebug != null, "container.probe.jdtls / container.probe.javaDebug not given");
        ExecTargets.setRemoteTools((id, tool) -> "lsp.java".equals(tool) ? "/opt/editora-probe/jdtls" : null);
        Path src = Files.createDirectories(folder.resolve("src/main/java/demo"));
        Files.writeString(
                folder.resolve("pom.xml"),
                "<project xmlns=\"http://maven.apache.org/POM/4.0.0\"><modelVersion>4.0.0</modelVersion>"
                        + "<groupId>demo</groupId><artifactId>app</artifactId><version>1</version>"
                        + "<properties><maven.compiler.release>17</maven.compiler.release></properties></project>\n");
        String text = "package demo;\npublic class Main {\n  public static void main(String[] a) {\n    int x = 1;\n"
                + "    System.out.println(\"sum \" + (x + 1));\n  }\n}\n";
        Path main = Files.writeString(src.resolve("Main.java"), text);
        ProcessRunner.Result javac = ProcessRunner.run(
                folder,
                Duration.ofSeconds(120),
                List.of("javac", "-g", "--release", "17", "-d", "target/classes", "src/main/java/demo/Main.java"));
        assertTrue(javac.ok(), "javac in the container: " + javac.message());

        List<String> ready = new CopyOnWriteArrayList<>();
        com.editora.lsp.LspManager lsp = new com.editora.lsp.LspManager((f, d) -> {}, (t, m) -> {
            if ("ServiceReady".equals(t)) {
                ready.add(t);
            }
        });
        lsp.configure(true, Map.of());
        lsp.setDebugBundles(List.of(javaDebug));
        com.editora.dap.DapManager dap = new com.editora.dap.DapManager(lsp);
        List<String> report = new java.util.ArrayList<>();
        try {
            onFx(() -> lsp.openDocument(main, folder, "java", text));
            await("jdtls ready in the container", 300, () -> !ready.isEmpty());
            Thread.sleep(3000);
            report.add(debugToBreakpoint(dap, main, folder, 3, javaDebug));

            // The plugin went into Editora's folder in the container, and so did jdtls's index.
            String home = target.env().home();
            ProcessRunner.Result cache = engine(
                    "exec", name, "sh", "-c", "ls \"$0\"/java-debug \"$0\"/jdtls-workspaces", home + "/.cache/editora");
            assertTrue(cache.ok() && cache.out().contains("com.microsoft.java.debug.plugin"), cache.message());

            // A Java file in no project: compiled in the container's cache for the debugger, then debugged.
            String looseText = "public class Loose {\n  public static void main(String[] a) {\n    int x = 1;\n"
                    + "    System.out.println(\"sum \" + (x + 1));\n  }\n}\n";
            Path looseFile = Files.writeString(loose.resolve("Loose.java"), looseText);
            onFx(() -> lsp.openDocument(looseFile, loose, "java", looseText));
            Thread.sleep(3000);
            report.add(debugToBreakpoint(dap, looseFile, null, 2, javaDebug));
            // The loose file's classes were compiled into the container's cache: gone with the session.
            onFx(dap::shutdown);
            String count = "?";
            for (int k = 0; k < 100 && !count.equals("0"); k++) {
                Thread.sleep(200);
                count = engine(
                                "exec",
                                name,
                                "sh",
                                "-c",
                                "ls -A \"$0\"/debug-classes 2>/dev/null | wc -l",
                                home + "/.cache/editora")
                        .out()
                        .strip();
            }
            assertEquals("0", count, "the debug classes are removed in the container");
            System.out.println("CONTAINER JAVA DEBUG: " + String.join(" | ", report));
        } finally {
            onFx(dap::shutdown);
            lsp.shutdownAll();
            ExecTargets.setRemoteTools(null);
        }
    }

    @Test
    void theContainersOwnPathAndLocaleAreUsed() throws Exception {
        ProcessRunner.Result r =
                ProcessRunner.run(folder, Duration.ofSeconds(60), List.of("sh", "-c", "echo \"$PATH\""));

        assertTrue(r.ok(), r.message());
        assertEquals(target.env().path(), r.out().strip(), "the container's login PATH, not this machine's");
    }

    @Test
    void stoppingAProgramStopsWhatItStartedInTheContainer() throws Exception {
        Process p = target.start(ExecSpec.of(
                        List.of("sh", "-c", "sleep 300 & sleep 301; wait"),
                        folder,
                        Map.of(),
                        ExecSpec.Locale.PARSE_STABLE)
                .withLongLived(true));
        assertTrue(p instanceof RemoteProcess);
        for (int i = 0; i < 100 && !processes().contains("sleep 301"); i++) {
            Thread.sleep(100);
        }
        assertTrue(processes().contains("sleep 300"), processes().toString());

        ProcessRegistry.killTree(p);

        assertTrue(p.waitFor(20, TimeUnit.SECONDS), "the program did not end");
        for (int i = 0; i < 100 && processes().stream().anyMatch(l -> l.startsWith("sleep 30")); i++) {
            Thread.sleep(100);
        }
        assertFalse(processes().stream().anyMatch(l -> l.startsWith("sleep 30")), "left running: " + processes());
    }

    @Test
    void runRunsTheProgramInTheContainerAndReadsWhatIsTyped() throws Exception {
        RunService run = new RunService();
        List<String> out = new CopyOnWriteArrayList<>();
        CountDownLatch done = new CountDownLatch(1);
        int[] exit = {Integer.MIN_VALUE};
        RunService.Listener listener = new RunService.Listener() {
            @Override
            public void onStart(String commandLine) {}

            @Override
            public void onOutput(String line, boolean stderr) {
                out.add(line);
            }

            @Override
            public void onExit(int code) {
                exit[0] = code;
                done.countDown();
            }

            @Override
            public void onError(String message) {
                out.add("ERROR " + message);
                done.countDown();
            }
        };
        onFx(() -> run.runInDir(
                folder, List.of("sh", "-c", "read name; echo \"hello $name\"; exit 3"), Map.of(), listener));
        for (int i = 0; i < 100 && run.pid() <= 0; i++) {
            Thread.sleep(50);
        }
        onFx(() -> run.sendInput("Ñandú"));

        assertTrue(done.await(60, TimeUnit.SECONDS));
        assertTrue(out.contains("hello Ñandú"), out.toString());
        assertEquals(3, exit[0]);
    }

    /** A dev container is found for its folder by the label the Dev Containers CLI and VS Code put on it. */
    @Test
    void aDevContainerIsFoundForItsFolderByItsLabel() throws Exception {
        Path other = Files.createDirectories(dir.toRealPath().resolve("dev"));
        String dev = name + "-dev";
        ProcessRunner.Result run = engine(
                "run",
                "-d",
                "--name",
                dev,
                "--label",
                ContainerInfo.LOCAL_FOLDER_LABEL + "=" + other,
                "-v",
                other + ":/workspaces/dev:Z",
                System.getProperty("container.probe.image", "eclipse-temurin:25-jdk"),
                "sleep",
                "infinity");
        assertTrue(run.ok(), run.message());
        try {
            ContainerExecTarget found = ContainerBindings.shared().bind(other, null, ContainerBindings.lookup(engine));

            assertNotNull(found, "the dev container for the folder");
            assertEquals("container:" + dev, found.id());
            ProcessRunner.Result pwd = ProcessRunner.run(other, Duration.ofSeconds(60), List.of("pwd"));
            assertEquals("/workspaces/dev", pwd.out().strip(), pwd.message());
            assertTrue(engine.runningContainers().contains(dev));
        } finally {
            ContainerBindings.shared().unbind(other);
            engine("rm", "-f", dev);
        }
    }

    /** A port only the container has is reached through a relay run with exec (here bash's /dev/tcp). */
    @Test
    void aPortInTheContainerIsReachedFromHere() throws Exception {
        Files.writeString(folder.resolve("Hello.java"), """
                public class Hello {
                    public static void main(String[] a) throws Exception {
                        try (var server = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
                            System.out.println(server.getLocalPort());
                            System.out.flush();
                            try (var s = server.accept()) {
                                String line = new java.io.BufferedReader(new java.io.InputStreamReader(s.getInputStream())).readLine();
                                s.getOutputStream().write(("hello from the container, " + line + "\\n").getBytes());
                            }
                        }
                    }
                }
                """);
        Process server =
                target.start(ExecSpec.of(List.of("java", "Hello.java"), folder, Map.of(), ExecSpec.Locale.PARSE_STABLE)
                        .withLongLived(true));
        try {
            String portLine =
                    new java.io.BufferedReader(new java.io.InputStreamReader(server.getInputStream())).readLine();
            int port = Integer.parseInt(portLine.strip());
            try (var forward = target.forwardLocal("127.0.0.1", port);
                    var socket = new java.net.Socket(java.net.InetAddress.getLoopbackAddress(), forward.localPort())) {
                socket.getOutputStream().write("Ana\n".getBytes());
                socket.getOutputStream().flush();
                String reply =
                        new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine();
                assertEquals("hello from the container, Ana", reply);
            }
        } finally {
            ProcessRegistry.killTree(server);
        }
    }
}
