package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javafx.scene.control.ComboBox;

import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.http.HttpExchange;
import com.editora.i18n.Messages;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Request chaining and variables through the coordinator, the way the gutter ▶ runs requests one at a time:
 * a request can use the response of one run earlier, the caret anywhere in a request's section runs it, the
 * environment change forgets responses, and {@code @var} lines read the {@code .env} beside the file.
 */
@Tag("fx")
class HttpClientChainingFxTest {

    private HttpServer server;
    private int port;
    private final List<String> hits = new CopyOnWriteArrayList<>();
    private Host host;
    private HttpClientCoordinator coordinator;
    private final List<EditorBuffer> buffers = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        Messages.init("en");
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            hits.add(ex.getRequestURI().getPath() + (auth == null ? "" : " " + auth));
            byte[] body = "{\"token\":\"T123\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        coordinator = new HttpClientCoordinator(host, new Ops());
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> buffers.forEach(EditorBuffer::dispose));
    }

    private String url(String path) {
        return "http://127.0.0.1:" + port + path;
    }

    private EditorBuffer httpBuffer(Path file, String text) throws Exception {
        Files.writeString(file, text);
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            coordinator.ensureHttpPreview(created);
            return created;
        });
        buffers.add(b);
        return b;
    }

    /** Runs the request at {@code line} as the gutter ▶ does and waits for it to finish. */
    private HttpExchange runLine(EditorBuffer b, int line) throws Exception {
        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, line));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (FxTestSupport.callOnFx(() -> coordinator.isRunningForTest(b))) {
            assertTrue(System.nanoTime() < deadline, "the run should finish");
            Thread.sleep(10);
        }
        FxTestSupport.drainFx();
        return FxTestSupport.callOnFx(() -> coordinator.panelForTest(b).getSelectedExchange());
    }

    private String file() {
        return "### Login\n# @name login\nPOST " + url("/login") + "\n\n" + "### Me\nGET " + url("/me")
                + "\nAuthorization: Bearer {{login.response.body.$.token}}\n";
    }

    @Test
    void aRequestRunAloneUsesTheResponseOfAnEarlierRun(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("chain.http"), file());
        runLine(b, 2); // the POST line
        runLine(b, 5); // the GET line
        assertEquals(List.of("/login", "/me Bearer T123"), hits);
    }

    @Test
    void theCaretOnTheTitleOrNameLineRunsThatRequest(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("caret.http"), file());
        runLine(b, 0); // "### Login"
        runLine(b, 1); // "# @name login"
        assertEquals(List.of("/login", "/login"), hits);
    }

    @Test
    void anUnresolvedReferenceIsWarnedAndAnEnvironmentChangeForgetsResponses(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("http-client.env.json"), "{\"dev\":{},\"prod\":{}}");
        EditorBuffer b = httpBuffer(dir.resolve("env.http"), file());
        ComboBox<String> env = FxTestSupport.field(coordinator.panelForTest(b), "envCombo");
        FxTestSupport.runOnFx(() -> env.setValue("dev"));
        runLine(b, 2);
        FxTestSupport.runOnFx(() -> env.setValue("prod"));
        HttpExchange me = runLine(b, 5);
        assertEquals(List.of("/login", "/me Bearer"), hits, "a dev token is not sent to prod");
        assertTrue(
                me.result().warnings().stream().anyMatch(w -> w.startsWith("{{login.response.body.$.token}}")),
                me.result().warnings().toString());
    }

    @Test
    void aVariableLineReadsTheDotenvBesideTheFile(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve(".env"), "TOKEN=abc123\n");
        EditorBuffer b = httpBuffer(
                dir.resolve("dotenv.http"),
                "@token = {{$dotenv TOKEN}}\n\nGET " + url("/d") + "\nAuthorization: Bearer {{token}}\n");
        runLine(b, 2);
        assertEquals(List.of("/d Bearer abc123"), hits);
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        volatile EditorBuffer active;

        @Override
        public Settings settings() {
            return settings;
        }

        @Override
        public void forEachBuffer(Consumer<EditorBuffer> action) {
            buffers.forEach(action);
        }

        @Override
        public EditorBuffer activeBuffer() {
            return active;
        }

        @Override
        public void setStatus(String message) {}
    }

    private static final class Ops implements HttpClientCoordinator.WindowOps {
        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public String savedEnvironment() {
            return "";
        }

        /** The folder is trusted: these tests are about resolution, not the secrets gate. */
        @Override
        public boolean allowSecretReads(Path file, List<String> hosts, List<String> reads) {
            return true;
        }
    }
}
