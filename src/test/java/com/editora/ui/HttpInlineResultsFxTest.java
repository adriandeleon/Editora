package com.editora.ui;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;

import com.editora.config.HttpResponseHistory;
import com.editora.config.Settings;
import com.editora.editor.EditorBuffer;
import com.editora.editor.http.HttpLineResult;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A request's last result after its line in the editor (U11): a run puts it on the right line, an edit to that line
 * removes it, a click shows that response, the setting and a Clear take them away, a restored history brings them
 * back, and the text never enters the document.
 */
@Tag("fx")
class HttpInlineResultsFxTest {

    private static final long WAIT_SECONDS = 30;

    private HttpServer server;
    private int port;
    private Host host;
    private HttpResponseHistory store;
    private HttpClientCoordinator coordinator;
    private final List<EditorBuffer> buffers = new ArrayList<>();
    private final List<Stage> stages = new ArrayList<>();

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
        com.editora.i18n.Messages.init("en");
    }

    @BeforeEach
    void setUp(@TempDir Path config) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/json", ex -> answer(ex, 200, "{\"ok\":true}"));
        server.createContext("/missing", ex -> answer(ex, 404, "nope"));
        server.start();
        port = server.getAddress().getPort();
        host = new Host();
        host.settings.setHttpClientSupport(true);
        store = new HttpResponseHistory(config);
        coordinator = new HttpClientCoordinator(host, new Ops());
    }

    private static void answer(com.sun.net.httpserver.HttpExchange ex, int status, String text)
            throws java.io.IOException {
        byte[] body = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/plain");
        ex.sendResponseHeaders(status, body.length);
        ex.getResponseBody().write(body);
        ex.close();
    }

    @AfterEach
    void tearDown() throws Exception {
        coordinator.shutdown();
        server.stop(0);
        FxTestSupport.runOnFx(() -> {
            stages.forEach(Stage::hide);
            buffers.forEach(EditorBuffer::dispose);
        });
        HttpHistorySync.awaitIdleForTest();
    }

    private String text() {
        return "### one\nGET http://127.0.0.1:" + port + "/json\n\n### two\nGET http://127.0.0.1:" + port
                + "/missing\n";
    }

    /** A saved, shown {@code .http} buffer with its response panel, as the window sets it up. */
    private EditorBuffer httpBuffer(Path file, String text) throws Exception {
        if (!Files.exists(file)) {
            Files.writeString(file, text);
        }
        EditorBuffer b = FxTestSupport.callOnFx(() -> {
            EditorBuffer created = new EditorBuffer();
            created.setPath(file);
            created.setContent(text);
            created.markClean();
            host.buffers.add(created);
            host.active = created;
            created.setHttpEnabled(true); // the ▶ scan, as applySupport does for a local file
            coordinator.ensureHttpPreview(created);
            Stage stage = new Stage();
            stage.setScene(new Scene(new StackPane(created.getNode()), 900, 500));
            stage.show();
            stages.add(stage);
            return created;
        });
        HttpEnvCache.awaitIdleForTest();
        buffers.add(b);
        return b;
    }

    private static <T> T await(Callable<T> read, Predicate<T> done, String what) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (true) {
            T value = FxTestSupport.callOnFx(read);
            if (done.test(value)) {
                return value;
            }
            assertTrue(System.nanoTime() < deadline, what + ": " + value);
            Thread.sleep(20);
        }
    }

    private static List<String> shown(EditorBuffer b) throws Exception {
        return FxTestSupport.callOnFx(() ->
                b.httpResults().stream().map(r -> r.line() + ": " + r.text()).toList());
    }

    @Test
    void aRunPutsItsResultOnItsLineAndAnEditToTheLineTakesItAway(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), text());
        String before = FxTestSupport.callOnFx(b::getContent);

        FxTestSupport.runOnFx(() -> coordinator.runRequest(b, 1));
        host.awaitStatus(s -> s.contains("200 OK") && !s.startsWith("Running"));
        List<HttpLineResult> one = await(b::httpResults, r -> r.size() == 1, "the first request's result");
        assertEquals(1, one.get(0).line(), "after the method/URL line");
        assertTrue(one.get(0).text().startsWith("200 OK · "), one.get(0).text());
        assertTrue(one.get(0).text().endsWith(" ms"), one.get(0).text());
        assertEquals(HttpInlineResult.OK, one.get(0).glyphClass());
        assertTrue(one.get(0).tooltip().startsWith("Ran at "), one.get(0).tooltip());

        FxTestSupport.runOnFx(() -> {
            host.active = b;
            coordinator.runFile();
        });
        List<HttpLineResult> both =
                await(b::httpResults, r -> r.size() == 2 && r.get(1).text().startsWith("404"), "both results");
        assertEquals(4, both.get(1).line());
        assertEquals(HttpInlineResult.CLIENT_ERROR, both.get(1).glyphClass());

        // Inlays, never text: the document, a copy and a save see the request lines as written.
        assertEquals(before, FxTestSupport.callOnFx(b::getContent));
        assertFalse(FxTestSupport.callOnFx(() -> b.getArea().getText()).contains("200 OK"));
        assertEquals(
                List.of("200 OK", "404 Not Found"),
                FxTestSupport.callOnFx(() -> statusLabels(b)),
                "drawn as labels after the lines");

        // A line added at the top carries both down at once.
        FxTestSupport.runOnFx(() -> b.getArea().insertText(0, "# note\n"));
        assertEquals(
                List.of(2, 5),
                FxTestSupport.callOnFx(
                        () -> b.httpResults().stream().map(HttpLineResult::line).toList()));

        // Editing the second request's URL drops its result at the next scan; the first keeps its own.
        FxTestSupport.runOnFx(() -> b.getArea().insertText(5, b.getArea().getParagraphLength(5), "x"));
        await(() -> shown(b).size(), n -> n == 1, "the edited request's result goes");
        assertEquals(2, FxTestSupport.callOnFx(() -> b.httpResults().get(0).line()));
        assertFalse(FxTestSupport.callOnFx(() -> b.getArea().getText()).contains("404 Not Found"));
    }

    @Test
    void clickingAResultShowsThatResponseInThePanel(@TempDir Path dir) throws Exception {
        EditorBuffer b = httpBuffer(dir.resolve("api.http"), text());
        FxTestSupport.runOnFx(() -> {
            host.active = b;
            coordinator.runFile();
        });
        await(b::httpResults, r -> r.size() == 2, "both results");
        HttpClientPanel p = coordinator.panelForTest(b);
        assertEquals(
                404,
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().status()),
                "the failure");

        FxTestSupport.runOnFx(() -> b.setMarkdownViewMode(EditorBuffer.MarkdownViewMode.EDITOR));
        FxTestSupport.runOnFx(() -> {
            b.getNode().applyCss();
            b.getNode().layout();
        });
        FxTestSupport.drainFx();
        Node first = FxTestSupport.callOnFx(() -> resultLabel(b, "200 OK"));
        assertNotNull(first, "the first request's result is a node in its paragraph");
        FxTestSupport.runOnFx(() -> Event.fireEvent(b.getArea(), click(b, first)));

        assertEquals(
                200,
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().status()));
        assertEquals(
                EditorBuffer.MarkdownViewMode.SPLIT,
                FxTestSupport.callOnFx(b::getMarkdownViewMode),
                "the response view is shown, as a run shows it");
        assertTrue(FxTestSupport.callOnFx(() -> b.activateHttpResult(4)));
        assertEquals(
                404,
                FxTestSupport.callOnFx(() -> p.getSelectedExchange().result().status()));
    }

    @Test
    void theSettingAndAClearTakeThemAwayAndARestoredHistoryBringsThemBack(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("api.http");
        EditorBuffer b = httpBuffer(file, text());
        FxTestSupport.runOnFx(() -> {
            host.active = b;
            coordinator.runFile();
        });
        await(b::httpResults, r -> r.size() == 2, "both results");

        FxTestSupport.runOnFx(() -> {
            host.settings.setHttpInlineResults(false);
            coordinator.applySupport();
        });
        assertEquals(List.of(), shown(b), "off: none");
        assertEquals(List.of(), FxTestSupport.callOnFx(() -> statusLabels(b)));
        FxTestSupport.runOnFx(() -> {
            host.settings.setHttpInlineResults(true);
            coordinator.applySupport();
        });
        assertEquals(2, shown(b).size(), "on again: from the same History");

        // Reopened: the saved history puts them back.
        HttpHistorySync.awaitIdleForTest();
        FxTestSupport.runOnFx(() -> {
            coordinator.onBufferClosed(b);
            host.buffers.remove(b);
        });
        HttpHistorySync.awaitIdleForTest();
        EditorBuffer again = httpBuffer(file, text());
        List<HttpLineResult> restored = await(again::httpResults, r -> r.size() == 2, "the restored results");
        assertTrue(restored.get(0).text().startsWith("200 OK"));
        assertTrue(restored.get(1).text().startsWith("404 Not Found"));

        FxTestSupport.runOnFx(() -> coordinator.panelForTest(again).clearForTest());
        assertEquals(List.of(), shown(again), "a cleared History leaves none");
    }

    /** The coloured status labels the editor draws after its lines, top to bottom. */
    private static List<String> statusLabels(EditorBuffer b) {
        b.getNode().applyCss();
        b.getNode().layout();
        List<Node> labels = new ArrayList<>(b.getArea().lookupAll(".inlay"));
        labels.sort(java.util.Comparator.comparingDouble(
                n -> n.localToScene(n.getBoundsInLocal()).getMinY()));
        List<String> out = new ArrayList<>();
        for (Node n : labels) {
            if (n instanceof Label l
                    && l.getStyleClass().stream()
                            .anyMatch(c -> c.startsWith("http-result-") && !c.equals("http-result-tests-failed"))) {
                out.add(l.getText());
            }
        }
        return out;
    }

    private static Node resultLabel(EditorBuffer b, String text) {
        return b.getArea().lookupAll(".inlay").stream()
                .filter(n -> n instanceof Label l && l.getText().equals(text))
                .findFirst()
                .orElse(null);
    }

    /** A click in the middle of {@code label}, as the area gets it (the label is mouse-transparent). */
    private static MouseEvent click(EditorBuffer b, Node label) {
        var bounds = label.localToScene(label.getBoundsInLocal());
        Point2D inArea = b.getArea().sceneToLocal(bounds.getCenterX(), bounds.getCenterY());
        Point2D onScreen = b.getArea().localToScreen(inArea);
        return new MouseEvent(
                b.getArea(),
                b.getArea(),
                MouseEvent.MOUSE_CLICKED,
                inArea.getX(),
                inArea.getY(),
                onScreen == null ? 0 : onScreen.getX(),
                onScreen == null ? 0 : onScreen.getY(),
                MouseButton.PRIMARY,
                1,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                true,
                false,
                true,
                new PickResult(b.getArea(), inArea.getX(), inArea.getY()));
    }

    private static final class Host extends CoordinatorHostStub {
        final Settings settings = new Settings();
        final List<EditorBuffer> buffers = new CopyOnWriteArrayList<>();
        final BlockingQueue<String> statuses = new LinkedBlockingQueue<>();
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
        public void setStatus(String message) {
            statuses.add(message);
        }

        String awaitStatus(Predicate<String> wanted) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (true) {
                String status = statuses.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                assertNotNull(status, "no such status message arrived");
                if (wanted.test(status)) {
                    return status;
                }
            }
        }
    }

    private final class Ops implements HttpClientCoordinator.WindowOps {
        @Override
        public void openTab(EditorBuffer buffer) {}

        @Override
        public void updateRunGating() {}

        @Override
        public HttpResponseHistory historyStore() {
            return store;
        }
    }
}
