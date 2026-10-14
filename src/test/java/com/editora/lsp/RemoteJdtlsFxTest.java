package com.editora.lsp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import javafx.application.Platform;

import com.editora.process.ExecTargets;
import com.editora.vfs.EmbeddedSftpFixture;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jdtls on a remote host: its {@code -data} workspace is in the host's cache folder, it is told about the
 * host's JDKs (not this machine's), it gets no local debug-bundle paths, and the join-on-completion option
 * reaches it through its launcher. The "jdtls" is a Python stand-in started through the in-process SSH
 * server, next to a {@code jdtls.py} as the real launcher has.
 */
@Tag("fx")
@DisabledOnOs(OS.WINDOWS)
class RemoteJdtlsFxTest {

    /** Logs its argv, then every message; answers initialize. */
    private static final String SERVER = """
            #!/usr/bin/env python3
            import json, os, sys
            log = open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "..", "jdtls.log"), "a", buffering=1)
            log.write(json.dumps({"argv": sys.argv[1:]}) + "\\n")
            def read():
                length = 0
                while True:
                    line = sys.stdin.buffer.readline()
                    if not line:
                        sys.exit(0)
                    line = line.strip()
                    if not line:
                        break
                    if line.lower().startswith(b"content-length:"):
                        length = int(line.split(b":")[1])
                return json.loads(sys.stdin.buffer.read(length))
            def send(msg):
                body = json.dumps(msg).encode("utf-8")
                sys.stdout.buffer.write(b"Content-Length: %d\\r\\n\\r\\n" % len(body) + body)
                sys.stdout.buffer.flush()
            while True:
                msg = read()
                log.write(json.dumps(msg) + "\\n")
                if msg.get("method") == "initialize":
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": {"capabilities": {"textDocumentSync": 1}}})
                elif msg.get("method") == "exit":
                    sys.exit(0)
                elif "id" in msg and "method" in msg:
                    send({"jsonrpc": "2.0", "id": msg["id"], "result": None})
            """;

    @BeforeAll
    static void bootToolkit() throws Exception {
        FxToolkit.registerPrimaryStage();
    }

    @TempDir
    Path base;

    private EmbeddedSftpFixture sftp;
    private LspManager manager;
    private Path log;
    private Path launcher;
    private Path hostJdk;

    @BeforeEach
    void setUp() throws Exception {
        ExecTargets.setRemoteEnabled(() -> true);
        sftp = EmbeddedSftpFixture.startWithShell(base);
        Path app = Files.createDirectories(sftp.serverPath("app/src"));
        Files.writeString(app.resolve("Main.java"), "class Main {}\n");
        Files.writeString(sftp.serverPath("app/pom.xml"), "<project/>\n");
        Path bin = Files.createDirectories(sftp.serverPath("jdtls/bin"));
        launcher = Files.writeString(bin.resolve("jdtls"), SERVER);
        Files.setPosixFilePermissions(launcher, PosixFilePermissions.fromString("rwxr-xr-x"));
        Files.writeString(bin.resolve("jdtls.py"), "# the real launcher's script\n");
        log = sftp.serverPath("jdtls.log");
        // A JDK where sdkman keeps them, in the host account's home.
        hostJdk = fakeJdk(sftp.home().resolve(".sdkman/candidates/java/17.0.9-tem"), "17.0.9");
        ExecTargets.setRemoteTools((id, tool) -> switch (tool) {
            case "lsp.java" -> launcher.toString();
            default -> null;
        });
        manager = new LspManager((file, diagnostics) -> {}, (type, message) -> {});
        manager.configure(true, Map.of("java", "/opt/local-only/jdtls"));
        manager.setDebugBundles(List.of("/opt/local-only/com.microsoft.java.debug.plugin.jar"));
    }

    @AfterEach
    void tearDown() throws Exception {
        manager.shutdownAll();
        sftp.close();
        ExecTargets.setRemoteTools(null);
        ExecTargets.setRemoteEnabled(null);
    }

    private static Path fakeJdk(Path home, String version) throws Exception {
        Path bin = Files.createDirectories(home.resolve("bin"));
        Files.writeString(bin.resolve("javac"), "");
        Files.writeString(home.resolve("release"), "IMPLEMENTOR=\"test\"\nJAVA_VERSION=\"" + version + "\"\n");
        return home.toRealPath();
    }

    private static void runOnFx(Runnable task) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                task.run();
            } finally {
                done.countDown();
            }
        });
        assertTrue(done.await(20, TimeUnit.SECONDS));
    }

    private JsonObject logged(String key) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log)) {
                for (String line : Files.readAllLines(log)) {
                    JsonObject json = JsonParser.parseString(line).getAsJsonObject();
                    if (json.has(key)
                            || key.equals(
                                    json.has("method") ? json.get("method").getAsString() : "")) {
                        return json;
                    }
                }
            }
            Thread.sleep(50);
        }
        throw new AssertionError("jdtls never logged " + key);
    }

    @Test
    void jdtlsRunsOnTheHostWithItsWorkspaceAndJdksThere() throws Exception {
        Path root = sftp.remotePath("app");
        Path file = root.resolve("src/Main.java");
        runOnFx(() -> manager.openDocument(file, root, "java", "class Main {}\n"));

        List<String> argv = logged("argv").getAsJsonArray("argv").asList().stream()
                .map(e -> e.getAsString())
                .toList();
        Path workspace = sftp.home()
                .resolve(".cache/editora/jdtls-workspaces")
                .resolve(LspServerRegistry.workspaceDirName(root));
        int data = argv.indexOf("-data");
        assertTrue(data >= 0, "no -data: " + argv);
        assertEquals(workspace.toString(), argv.get(data + 1));
        assertTrue(Files.isDirectory(workspace), "the workspace is made on the host");
        assertTrue(argv.contains("--jvm-arg=-Djava.lsp.joinOnCompletion=true"), argv.toString());

        JsonObject options = logged("initialize").getAsJsonObject("params").getAsJsonObject("initializationOptions");
        String runtimes = options.getAsJsonObject("settings")
                .getAsJsonObject("java")
                .getAsJsonObject("configuration")
                .getAsJsonArray("runtimes")
                .toString();
        // Only a scan on the host finds the JDK in the host account's home (this machine's scan never looks there).
        assertTrue(runtimes.contains(hostJdk.toString()), runtimes);
        assertTrue(runtimes.contains("JavaSE-17"), runtimes);
        assertFalse(options.has("bundles"), "this machine's debug jar means nothing on the host: " + options);
    }

    @Test
    void theHostsJdksAreFoundWithOneCommand() throws Exception {
        Path configured = fakeJdk(sftp.serverPath("opt/jdk-21"), "21.0.2");
        Path notAJdk = sftp.serverPath("opt/jre-only");
        Files.createDirectories(notAJdk.resolve("bin"));
        Files.writeString(notAJdk.resolve("release"), "JAVA_VERSION=\"11.0.1\"\n");
        ExecTargets.setRemoteTools((id, tool) -> "jdk.home".equals(tool) ? configured.toString() : null);

        List<JavaRuntimes.Jdk> jdks = RemoteJavaRuntimes.discover(sftp.remotePath("app"));

        assertTrue(jdks.contains(new JavaRuntimes.Jdk(21, configured.toString())), jdks.toString());
        assertTrue(jdks.contains(new JavaRuntimes.Jdk(17, hostJdk.toString())), jdks.toString());
        // By its path: the machine playing the host may well have a real JDK 11 of its own (CI runners do).
        assertFalse(
                jdks.stream().anyMatch(j -> j.path().startsWith(notAJdk.toString())),
                "a runtime without javac is no JDK: " + jdks);
    }

    @Test
    void theLauncherIsRecognisedOnTheHost() throws Exception {
        Path root = sftp.remotePath("app");
        Path impostor = Files.writeString(
                Files.createDirectories(sftp.serverPath("elsewhere")).resolve("jdtls"), "#!/bin/sh\n");
        Files.setPosixFilePermissions(impostor, PosixFilePermissions.fromString("rwxr-xr-x"));
        assertTrue(JavaServerEnvironment.isRemoteJdtlsLauncher(root, launcher.toString()));
        assertFalse(JavaServerEnvironment.isRemoteJdtlsLauncher(root, impostor.toString()), "no jdtls.py beside it");
        assertFalse(JavaServerEnvironment.isRemoteJdtlsLauncher(root, "no-such-jdtls"));
    }
}
