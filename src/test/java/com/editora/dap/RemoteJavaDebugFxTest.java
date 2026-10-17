package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import javafx.application.Platform;

import com.editora.lsp.FakeLanguageServer;
import com.editora.lsp.LspManager;
import com.editora.lsp.LspTestHooks;
import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import org.eclipse.lsp4j.ExecuteCommandParams;
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
 * Debugging a Java file of a remote project: java-debug runs inside the host's jdtls (here a fake that
 * answers the java-debug commands), its port is reached through a tunnel, the launch names the host's
 * folder and JDK — never this machine's — and breakpoints and frames cross as host paths.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteJavaDebugFxTest {

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private LspManager lsp;
    private DapManager dap;
    private FakeDebugAdapter adapter;
    private List<FakeLanguageServer> fakes;
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<List<DapModels.StackFrameInfo>> stops = new CopyOnWriteArrayList<>();
    private final Map<String, Function<ExecuteCommandParams, Object>> replies = new java.util.HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base); // forwards ports, as an OpenSSH server does
        adapter = new FakeDebugAdapter(false);
        lsp = new LspManager((f, d) -> {}, (t, m) -> {});
        fakes = LspTestHooks.useFakeSessions(lsp);
        lsp.configure(true, Map.of("java", "jdtls"));
        dap = new DapManager(lsp);
        onFx(() -> {
            dap.configure(true, base.resolve("no-plugin-here").toString());
            dap.setServerProvidesJavaDebug(true);
            dap.setListener(new DapManager.Listener() {
                @Override
                public void onState(DapManager.State state) {}

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
        replies.put("vscode.java.startDebugSession", p -> adapter.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        onFx(dap::shutdown);
        lsp.shutdownAll();
        adapter.close();
        sftp.close();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
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
        assertTrue(done.await(10, TimeUnit.SECONDS));
    }

    private Path open(String relative, String source) throws Exception {
        Path onHost = sftp.serverPath(relative);
        Files.createDirectories(onHost.getParent());
        Files.writeString(onHost, source);
        Path file = sftp.remotePath(relative);
        Path root = sftp.remotePath("app");
        onFx(() -> lsp.openDocument(file, root, "java", source));
        for (FakeLanguageServer fake : fakes) {
            fake.executeCommandHandler = params -> {
                Function<ExecuteCommandParams, Object> reply = replies.get(params.getCommand());
                return reply == null ? null : reply.apply(params);
            };
        }
        return file;
    }

    @Test
    void aProjectMainClassIsDebuggedOnTheHost() throws Exception {
        String hostFile = sftp.serverPath("app/src/main/java/demo/Main.java").toString();
        Path file = open(
                "app/src/main/java/demo/Main.java",
                "package demo;\npublic class Main {\n    public static void main(String[] a) {\n    }\n}\n");
        Files.writeString(sftp.serverPath("app/pom.xml"), "<project/>\n");
        String hostClasses = sftp.serverPath("app/target/classes").toString();
        replies.put(
                "vscode.java.resolveMainClass",
                p -> List.of(Map.of("mainClass", "demo.Main", "projectName", "app", "filePath", hostFile)));
        replies.put("vscode.java.resolveClasspath", p -> List.of(List.of(), List.of(hostClasses)));
        replies.put("vscode.java.resolveJavaExecutable", p -> "/host/jdk-21/bin/java");
        dap.setBreakpointsSupplier(() ->
                List.of(new DapModels.FileBreakpoints(file, List.of(new DapModels.LineBreakpoint(3, null, null)))));

        onFx(() -> dap.startLaunch(
                file, "java", (o, c) -> c.accept(null), "/opt/local-only/bin/java", sftp.remotePath("app")));

        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals(List.of(), errors);
        Map<String, Object> launch = session.launchArgs;
        assertEquals("demo.Main", launch.get("mainClass"), "the host file was matched to its main class");
        assertEquals(sftp.serverPath("app").toString(), launch.get("cwd"));
        assertEquals("/host/jdk-21/bin/java", launch.get("javaExec"), "the host's JDK, not this machine's");
        assertTrue(session.configured.await(10, TimeUnit.SECONDS));
        assertEquals(hostFile, session.breakpoints.get(0).getSource().getPath());

        session.framePath = hostFile;
        session.frameLine = 4;
        session.stop(1, "breakpoint");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (stops.isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(file, stops.get(0).get(0).file(), "the frame is the remote file");
    }

    /**
     * A Java file in no project is compiled for the debugger — on its host, with the host's javac, into a
     * folder of the host's cache — and launched from there; the launch names the host's folders.
     */
    @Test
    void aLooseRemoteFileIsCompiledOnItsHostAndDebuggedFromThere() throws Exception {
        Path file = open("loose/Hello.java", "public class Hello {\n    public static void main(String[] a) {}\n}\n");
        replies.put("vscode.java.resolveMainClass", p -> List.of());
        // The host's JDK, as its tools name it (the test host's login PATH has no javac of its own).
        String jdk = System.getProperty("java.home");
        ExecTargets.setRemoteTools((id, tool) -> "jdk.home".equals(tool) ? jdk : null);

        onFx(() -> dap.startLaunch(file, "java", (o, c) -> c.accept(null), "/opt/local-only/bin/java", null));

        FakeDebugAdapter.Session session;
        try {
            session = adapter.awaitSession();
        } catch (AssertionError e) {
            throw new AssertionError("no session; errors " + errors, e);
        }
        session.awaitRequest("launch");
        assertEquals(List.of(), errors);
        Map<String, Object> launch = session.launchArgs;
        assertEquals("Hello", launch.get("mainClass"));
        assertEquals(sftp.serverPath("loose").toString(), launch.get("cwd"));
        assertEquals(jdk + "/bin/java", launch.get("javaExec"), "the host's JDK, not this machine's setting");
        String classes = String.valueOf(((List<?>) launch.get("classPaths")).get(0));
        Path cache = sftp.home().resolve(".cache/editora/debug-classes");
        assertTrue(classes.startsWith(cache.toString()), "compiled into the host's cache: " + classes);
        assertTrue(Files.isRegularFile(Path.of(classes, "Hello.class")), "by the host's javac");
        assertTrue(
                sftp.commands().stream().anyMatch(c -> c.contains("/bin/javac'") && c.contains("'-g'")),
                "javac ran on the host: " + sftp.commands());
    }

    /**
     * A saved configuration on a remote host runs on the host's configured JDK, as Run runs it — Debug used
     * whatever jdtls resolved there (review D9).
     */
    @Test
    void aConfigurationOnAHostRunsOnTheHostsConfiguredJdk() throws Exception {
        String hostFile = sftp.serverPath("app/src/main/java/demo/Main.java").toString();
        Path file = open(
                "app/src/main/java/demo/Main.java",
                "package demo;\npublic class Main {\n    public static void main(String[] a) {\n    }\n}\n");
        replies.put(
                "vscode.java.resolveClasspath",
                p -> List.of(
                        List.of(), List.of(sftp.serverPath("app/target/classes").toString())));
        replies.put("vscode.java.resolveJavaExecutable", p -> "/host/jdk-21/bin/java");

        onFx(() -> {
            dap.setHostJavaExec("/opt/jdk-25/bin/java");
            dap.startLaunchMainClass(
                    file,
                    new DapManager.MainClassOption("demo.Main", "app", hostFile),
                    sftp.remotePath("app"),
                    "/opt/local-only/bin/java");
        });

        FakeDebugAdapter.Session session = adapter.awaitSession();
        session.awaitRequest("launch");
        assertEquals("/opt/jdk-25/bin/java", session.launchArgs.get("javaExec"), "the host's configured JDK");
    }
}
