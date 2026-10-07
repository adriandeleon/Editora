package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import javafx.scene.control.TextArea;

import com.editora.config.ConfigManager;
import com.editora.config.HistoryRevision;
import com.editora.config.PathKeys;
import com.editora.editor.EditorBuffer;
import com.editora.history.HistoryBlobStore;
import com.editora.http.HttpClientService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A {@code >>!} response redirect in a real window: the file it replaces is recorded in Local File History
 * before the replace, a file open with unsaved changes is not replaced underneath its buffer, and the response
 * view names every file the run wrote.
 */
@Tag("fx")
class HttpRedirectOverwriteFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @TempDir
    Path dir;

    private HttpServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fresh", exchange -> {
            byte[] body = "fresh response".getBytes(UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void theReplacedFileIsInLocalHistoryAndTheResponseViewNamesIt() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> {
                fx.shared.getSettings().setLocalHistory(true);
                fx.shared.getSettings().setHttpClientSupport(true);
            });
            Path target = Files.writeString(dir.resolve("out.txt"), "hand-written notes\n");
            EditorBuffer request = openRequest(fx, ">>! out.txt");

            async.await(run(fx, request).delivered());

            assertEquals("fresh response", Files.readString(target), ">>! still replaces the file");
            assertEquals("hand-written notes\n", historyBody(fx, target), "the replaced content is recoverable");
            String shown = FxTestSupport.callOnFx(() -> {
                HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
                TextArea head = FxTestSupport.field(http.panelForTest(request), "headersArea");
                return head.getText();
            });
            assertTrue(shown.contains("out.txt") && shown.contains("Local History"), shown);
        }
    }

    @Test
    void aFileOpenWithUnsavedChangesIsNotReplacedUnderneathItsBuffer() throws Exception {
        try (AsyncTestScope async = new AsyncTestScope()) {
            FxWindowFixture fx = async.own(FxWindowFixture.create());
            FxTestSupport.runOnFx(() -> fx.shared.getSettings().setHttpClientSupport(true));
            Path target = Files.writeString(dir.resolve("draft.txt"), "saved draft");
            EditorBuffer draft = open(fx, target);
            FxTestSupport.runOnFx(() -> draft.replaceWholeDocument("saved draft plus unsaved work"));
            EditorBuffer request = openRequest(fx, ">>! draft.txt");

            async.await(run(fx, request).delivered());

            assertEquals("saved draft", Files.readString(target), "the file under a dirty buffer is left alone");
            String shown = FxTestSupport.callOnFx(() -> {
                HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
                TextArea head = FxTestSupport.field(http.panelForTest(request), "headersArea");
                return head.getText();
            });
            assertTrue(shown.contains("draft.txt") && shown.contains("unsaved changes"), shown);
        }
    }

    private EditorBuffer openRequest(FxWindowFixture fx, String redirect) throws Exception {
        Path file = Files.writeString(
                dir.resolve("api.http"),
                "GET http://127.0.0.1:" + server.getAddress().getPort() + "/fresh\n\n" + redirect + "\n");
        return open(fx, file);
    }

    private static HttpClientService.Handle run(FxWindowFixture fx, EditorBuffer request) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            HttpClientCoordinator http = FxTestSupport.field(fx.controller, "httpClient");
            http.runRequest(request, 0);
            Map<EditorBuffer, HttpClientService.Handle> running = FxTestSupport.field(http, "running");
            HttpClientService.Handle handle = running.get(request);
            assertNotNull(handle, "the request should have started");
            return handle;
        });
    }

    private static EditorBuffer open(FxWindowFixture fx, Path file) throws Exception {
        return FxTestSupport.callOnFx(() -> {
            EditorBuffer buffer = new EditorBuffer();
            buffer.setPath(file);
            buffer.setContent(Files.readString(file));
            buffer.setDiskSnapshot(Files.getLastModifiedTime(file).toMillis(), Files.size(file));
            FxTestSupport.call(
                    fx.controller, "addBuffer", new Class<?>[] {EditorBuffer.class, boolean.class}, buffer, true);
            return buffer;
        });
    }

    private static String historyBody(FxWindowFixture fx, Path file) throws Exception {
        ConfigManager config = FxTestSupport.field(fx.controller, "config");
        List<HistoryRevision> revisions = config.getHistory().get(PathKeys.normalizedKey(file));
        assertNotNull(revisions, "the replaced file must have a history revision");
        HistoryBlobStore blobs = FxTestSupport.field(fx.shared.historyService(), "blobs");
        return blobs.get(revisions.get(0).sha256());
    }
}
