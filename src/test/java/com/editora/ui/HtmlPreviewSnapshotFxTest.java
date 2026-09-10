package com.editora.ui;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import com.editora.web.Browsers;
import com.editora.web.HtmlPreviewService;
import com.editora.web.LivePreviewServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("fx")
class HtmlPreviewSnapshotFxTest {

    @BeforeAll
    static void boot() throws Exception {
        FxTestSupport.bootToolkit();
    }

    @Test
    void httpWorkerServesImmutableSnapshotsUpdatedFromFx(@TempDir Path dir) throws Exception {
        Path file = Files.writeString(dir.resolve("index.html"), "disk");
        HtmlPreviewService service = new HtmlPreviewService(url -> {});
        CountDownLatch ready = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<HtmlPreviewService.Result>();
        try {
            FxTestSupport.runOnFx(() -> {
                service.preview(
                        file,
                        "<p>first snapshot</p>",
                        new Browsers.Browser(Browsers.SYSTEM_DEFAULT, "System Default"),
                        value -> {
                            result.set(value);
                            ready.countDown();
                        });
                service.updateText(file, "<p>startup edit</p>");
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String first = get(client, result.get().url());
            assertTrue(first.contains("startup edit"));
            assertFalse(first.contains("disk"));

            FxTestSupport.runOnFx(() -> service.updateText(file, "<p>second snapshot</p>"));
            String second = get(client, result.get().url());
            assertTrue(second.contains("second snapshot"));
            assertFalse(second.contains("first snapshot"));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void stoppedQueuedPreviewCannotRestartTheServerOrOpenABrowser(@TempDir Path dir) throws Exception {
        java.util.concurrent.atomic.AtomicInteger opened = new java.util.concurrent.atomic.AtomicInteger();
        HtmlPreviewService service = new HtmlPreviewService(url -> opened.incrementAndGet());
        ExecutorService worker = FxTestSupport.field(service, "exec");
        LivePreviewServer server = FxTestSupport.field(service, "server");
        CountDownLatch blocked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        worker.submit(() -> {
            blocked.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(blocked.await(5, TimeUnit.SECONDS));
        try {
            FxTestSupport.runOnFx(() -> {
                service.preview(
                        dir.resolve("index.html"),
                        "text",
                        new Browsers.Browser(Browsers.SYSTEM_DEFAULT, "System Default"),
                        ignored -> {});
                service.stopServer();
            });
            release.countDown();
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            FxTestSupport.runOnFx(() -> {});
            assertFalse(server.isRunning());
            assertTrue(opened.get() == 0);

            CountDownLatch restarted = new CountDownLatch(1);
            FxTestSupport.runOnFx(() -> service.preview(
                    dir.resolve("index.html"),
                    "new text",
                    new Browsers.Browser(Browsers.SYSTEM_DEFAULT, "System Default"),
                    ignored -> restarted.countDown()));
            assertTrue(restarted.await(10, TimeUnit.SECONDS));
            assertTrue(server.isRunning());
            FxTestSupport.runOnFx(service::stopServer);
            worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
            assertFalse(server.isRunning());
        } finally {
            release.countDown();
            service.shutdown();
        }
    }

    private static String get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
