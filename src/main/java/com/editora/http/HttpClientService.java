package com.editora.http;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

import javafx.application.Platform;

/**
 * UI-facing façade for running {@code .http} requests with the <b>built-in JDK {@code HttpClient}</b>
 * (no external CLI): work runs on one daemon executor and results post back on the JavaFX thread. The
 * caller supplies a parsed request ({@link HttpFile.Parsed}) and the resolved variable map; this resolves
 * {@code {{vars}}} (via {@link HttpVars}), executes, and returns an {@link HttpResult}.
 */
public final class HttpClientService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "http-client");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** Runs a single request off-thread, posting its {@link HttpResult} on the FX thread. */
    public void run(HttpFile.Parsed request, Map<String, String> vars, Consumer<HttpResult> onResult) {
        exec.submit(() -> {
            HttpResult r = execute(request, vars);
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    /** Runs {@code requests} sequentially off-thread, posting all results together on the FX thread. */
    public void runAll(List<HttpFile.Parsed> requests, Map<String, String> vars,
            Consumer<List<HttpResult>> onResult) {
        exec.submit(() -> {
            List<HttpResult> results = new ArrayList<>();
            for (HttpFile.Parsed req : requests) {
                results.add(execute(req, vars));
            }
            Platform.runLater(() -> onResult.accept(results));
        });
    }

    private HttpResult execute(HttpFile.Parsed request, Map<String, String> vars) {
        LocalDateTime now = LocalDateTime.now();
        String url = HttpVars.substitute(request.url(), vars, now).trim();
        try {
            String body = HttpVars.substitute(request.body(), vars, now);
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(REQUEST_TIMEOUT);
            for (String[] h : request.headers()) {
                try {
                    b.header(h[0], HttpVars.substitute(h[1], vars, now));
                } catch (IllegalArgumentException ignore) {
                    // a header the JDK client forbids setting (Host, Content-Length, …) — skip it
                }
            }
            HttpRequest.BodyPublisher publisher = body == null || body.isBlank()
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body);
            b.method(request.method().isEmpty() ? "GET" : request.method(), publisher);

            long t0 = System.nanoTime();
            HttpResponse<String> resp = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            long ms = (System.nanoTime() - t0) / 1_000_000;

            List<String[]> headers = new ArrayList<>();
            resp.headers().map().forEach((k, values) -> {
                for (String v : values) {
                    headers.add(new String[]{k, v});
                }
            });
            String contentType = resp.headers().firstValue("content-type").orElse("");
            long size = resp.body() == null ? 0 : resp.body().getBytes(StandardCharsets.UTF_8).length;
            return new HttpResult(resp.statusCode(), headers, resp.body(), contentType, ms, size, null);
        } catch (Exception e) {
            return new HttpResult(0, List.of(), "", "", 0, 0, errorMessage(url, e));
        }
    }

    private static String errorMessage(String url, Exception e) {
        String m = e.getMessage();
        String base = m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
        return url.isBlank() ? base : base + "  (" + url + ")";
    }
}
