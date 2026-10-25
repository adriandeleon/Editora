package com.editora.dap;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import com.editora.i18n.Messages;
import com.editora.lsp.LspManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxToolkit;

import static com.editora.i18n.Messages.tr;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attaching Editora's own JVM adapter to a program a build tool started under a suspended JDWP agent
 * ({@link DapManager#startJvmAttach}): the session sends the breakpoints, stops on one, resumes, and the program runs
 * to its end; a language without the JVM adapter, and a port nothing listens on, are reported instead.
 */
@Tag("fx")
class JvmAttachFxTest {

    private static final long WAIT = 60;
    private static final Pattern LISTENING = Pattern.compile("Listening for transport dt_socket at address: (\\d+)");

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
        onFx(() -> Messages.init("en"));
    }

    @TempDir
    Path dir;

    private LspManager lsp;
    private DapManager dap;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<DapManager.State> states = new CopyOnWriteArrayList<>();
    private final BlockingQueue<List<DapModels.StackFrameInfo>> stops = new LinkedBlockingQueue<>();
    private volatile List<DapModels.FileBreakpoints> breakpoints = List.of();
    private Process debuggee;

    @BeforeEach
    void setUp() throws Exception {
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        dap = new DapManager(lsp);
        onFx(() -> {
            dap.configure(true, dir.resolve("no-plugin-here").toString());
            dap.setBreakpointsSupplier(() -> breakpoints);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {
                    states.add(state);
                }

                @Override
                public void onStopped(int threadId, String reason, List<DapModels.StackFrameInfo> frames) {
                    stops.add(frames);
                }

                @Override
                public void onOutput(String text, String category) {}

                @Override
                public void onError(String message) {
                    errors.add(message);
                }
            });
        });
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        if (debuggee != null) {
            debuggee.destroyForcibly();
        }
    }

    private static void onFx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                action.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS), "the FX thread ran the action");
    }

    private void awaitError() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
        while (errors.isEmpty()) {
            assertTrue(System.nanoTime() < deadline, "no error was reported; states " + states);
            Thread.sleep(20);
        }
    }

    /** {@code Hello.java} of a Maven project, compiled with debug info, started suspended; returns its JDWP port. */
    private int startSuspended(Path source) throws Exception {
        var javac = javax.tools.ToolProvider.getSystemJavaCompiler();
        assertNotNull(javac, "tests run on a JDK");
        Path out = dir.resolve("app/target/classes");
        assertEquals(0, javac.run(null, null, null, "-g", "-d", out.toString(), source.toString()));
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        debuggee = new ProcessBuilder(
                        java,
                        "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0",
                        "-cp",
                        out.toString(),
                        "p.Hello")
                .redirectErrorStream(true)
                .start();
        BufferedReader lines =
                new BufferedReader(new InputStreamReader(debuggee.getInputStream(), StandardCharsets.UTF_8));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT);
        for (String line; (line = lines.readLine()) != null; ) {
            Matcher m = LISTENING.matcher(line);
            if (m.find()) {
                Thread.ofVirtual().start(() -> lines.lines().forEach(ignored -> {})); // keep its output flowing
                return Integer.parseInt(m.group(1));
            }
            assertTrue(System.nanoTime() < deadline, "the JVM never said where it listens");
        }
        throw new AssertionError("the JVM exited before listening");
    }

    @Test
    void anAttachedSessionStopsOnABreakpointResumesAndTheProgramRunsToItsEnd() throws Exception {
        Path project = Files.createDirectories(dir.resolve("app"));
        Files.writeString(project.resolve("pom.xml"), "<project/>\n"); // the build root the sources are under
        Path source =
                Files.createDirectories(project.resolve("src/main/java/p")).resolve("Hello.java");
        Files.writeString(source, """
                package p;
                public class Hello {
                    public static void main(String[] args) {
                        int x = 41;
                        String who = "adl";
                        x++;
                        System.exit(x == 42 && who.length() == 3 ? 7 : 1);
                    }
                }
                """);
        breakpoints = List.of(new DapModels.FileBreakpoints(
                source, List.of(new DapModels.LineBreakpoint(4, null, null)))); // 0-based: String who = "adl";
        int port = startSuspended(source);

        onFx(() -> dap.startJvmAttach(source, "kotlin", "localhost", port));
        List<DapModels.StackFrameInfo> frames = stops.poll(WAIT, TimeUnit.SECONDS);
        assertNotNull(frames, "the breakpoint is hit; states " + states + ", errors " + errors);
        DapModels.StackFrameInfo top = frames.get(0);
        assertEquals(
                source.toAbsolutePath().normalize(), top.file().toAbsolutePath().normalize());
        assertTrue(top.name().contains("main"), top.name());
        assertTrue(states.contains(DapManager.State.SUSPENDED), states.toString());

        onFx(dap::resume);
        assertTrue(debuggee.waitFor(WAIT, TimeUnit.SECONDS), "the program ran on after the resume");
        assertEquals(7, debuggee.exitValue(), "it ran its own code, to its own exit");
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void aLanguageWithoutTheJvmAdapterIsReported() throws Exception {
        onFx(() -> dap.startJvmAttach(dir.resolve("main.py"), "python", "localhost", 5005));
        awaitError();
        assertEquals(List.of(tr("debug.error.noAdapter")), errors);
    }

    @Test
    void aPortNothingListensOnIsReportedAsAFailedStart() throws Exception {
        int closed;
        try (ServerSocket probe = new ServerSocket(0)) {
            closed = probe.getLocalPort();
        }
        Path file = Files.writeString(dir.resolve("main.kts"), "println(1)\n");
        int port = closed;
        onFx(() -> dap.startJvmAttach(file, "kotlin", "", port));
        awaitError();
        String prefix = tr("debug.error.launchFailed", "").strip();
        assertTrue(
                errors.get(0).startsWith(prefix)
                        || errors.get(0)
                                .startsWith(tr("debug.error.startFailed", "").strip()),
                "the attach failure is the error: " + errors);
    }
}
