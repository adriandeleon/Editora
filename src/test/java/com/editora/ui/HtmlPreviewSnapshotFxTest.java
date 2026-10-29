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
                        dir,
                        "<p>first snapshot</p>",
                        new Browsers.Browser(Browsers.SYSTEM_DEFAULT, "System Default"),
                        value -> {
                            result.set(value);
                            ready.countDown();
                        });
                service.edited(file, "<p>startup edit</p>");
            });
            assertTrue(ready.await(10, TimeUnit.SECONDS));

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
            String first = get(client, result.get().url());
            assertTrue(first.contains("startup edit"));
            assertFalse(first.contains("disk"));

            FxTestSupport.runOnFx(() -> service.edited(file, "<p>second snapshot</p>"));
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
                        dir,
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
                    dir,
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

    @Test
    void editsOfAnyFileOfTheSiteReloadAndAStylesheetOnlySwaps(@TempDir Path dir) throws Exception {
        Path page = Files.writeString(dir.resolve("index.html"), "<link rel=stylesheet href=css/site.css>");
        Path css = Files.createDirectories(dir.resolve("css")).resolve("site.css");
        Files.writeString(css, "body{}");
        Path other = Files.writeString(dir.resolve("about.html"), "<p>about</p>");
        HtmlPreviewService service = new HtmlPreviewService(url -> {});
        try {
            String url = open(service, page, dir, new Browsers.Browser(Browsers.SYSTEM_DEFAULT, "System Default"))
                    .url();
            HttpClient client = HttpClient.newBuilder().build();
            String poll = url.replace("/index.html", "/__editora_livereload?v=");
            assertTrue(status(client, poll + "1") == 204, "nothing changed yet");

            FxTestSupport.runOnFx(() -> service.edited(css, "body{background:red}")); // an unsaved CSS edit
            assertTrue(get(client, url.replace("index.html", "css/site.css")).contains("red"), "served live");
            assertTrue(get(client, poll + "1").equals("2 css"), "a stylesheet edit swaps, it does not reload");
            FxTestSupport.runOnFx(() -> service.edited(css, "body{background:red}")); // the save: same text
            assertTrue(status(client, poll + "2") == 204, "a save of what is already served changes nothing");

            FxTestSupport.runOnFx(() -> service.edited(other, "<p>about us</p>"));
            assertTrue(get(client, poll + "2").equals("3"), "another page of the site reloads it");
            FxTestSupport.runOnFx(() -> service.closed(css));
            assertTrue(get(client, url.replace("index.html", "css/site.css")).equals("body{}"), "disk again");
            assertFalse(service.serves(dir.getParent().resolve("x.css")));
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aBrowserThatIsGoneFallsBackToTheDefaultAndSaysSo(@TempDir Path dir) throws Exception {
        Path page = Files.writeString(dir.resolve("index.html"), "<p>x</p>");
        java.util.List<String> opened = new java.util.concurrent.CopyOnWriteArrayList<>();
        HtmlPreviewService service = new HtmlPreviewService(opened::add);
        try {
            HtmlPreviewService.Result r = open(service, page, dir, new Browsers.Browser("brave-gone", "Brave"));
            assertTrue(r.outcome() == HtmlPreviewService.Outcome.FALLBACK, String.valueOf(r));
            FxTestSupport.runOnFx(() -> {});
            assertTrue(opened.size() == 1 && opened.get(0).equals(r.url()), "the default browser opened it");
            String status = HtmlPreviewCoordinator.statusFor(r, "Brave");
            assertTrue(status.startsWith("Brave wasn't found"), status);
        } finally {
            service.shutdown();
        }
    }

    @Test
    void aRefusedRootIsAServerFailureNotABrowserOne() throws Exception {
        Path home = Path.of(System.getProperty("user.home"));
        HtmlPreviewService service = new HtmlPreviewService(url -> {});
        try {
            HtmlPreviewService.Result r = open(
                    service,
                    home.resolve("editora-test-page.html"),
                    home,
                    new Browsers.Browser(Browsers.SYSTEM_DEFAULT, "System Default"));
            assertTrue(r.outcome() == HtmlPreviewService.Outcome.SERVER_FAILED, String.valueOf(r));
            assertFalse(service.isServing(), "nothing left registered");
            String status = HtmlPreviewCoordinator.statusFor(r, "System Default");
            assertTrue(status.startsWith("Couldn't start the preview server"), status);
        } finally {
            service.shutdown();
        }
    }

    private static HtmlPreviewService.Result open(
            HtmlPreviewService service, Path page, Path root, Browsers.Browser browser) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        var result = new java.util.concurrent.atomic.AtomicReference<HtmlPreviewService.Result>();
        FxTestSupport.runOnFx(() -> service.preview(page, root, "<p>live</p>", browser, r -> {
            result.set(r);
            done.countDown();
        }));
        assertTrue(done.await(10, TimeUnit.SECONDS));
        return result.get();
    }

    private static int status(HttpClient client, String url) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(URI.create(url))
                                .timeout(Duration.ofSeconds(5))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    private static String get(HttpClient client, String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(5))
                .GET()
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString()).body();
    }
}
