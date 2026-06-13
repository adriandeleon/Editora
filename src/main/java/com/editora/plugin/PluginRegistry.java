package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import javafx.application.Platform;

/**
 * Fetches a remote plugin registry's {@code index.json} (the {@code HttpClientService} idiom: one daemon
 * executor + {@link Platform#runLater} results). HTTPS-only. Results are cached per URL until
 * {@link #invalidate()} (an explicit refresh). No new dependency — the JDK {@code java.net.http} client
 * (already {@code requires java.net.http}) + the existing Jackson.
 */
public final class PluginRegistry {

    private static final Logger LOG = Logger.getLogger(PluginRegistry.class.getName());
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    /** The outcome of a fetch: either {@code entries} (possibly empty) or a non-null {@code error}. */
    public record Result(List<RegistryEntry> entries, String error) {
        public boolean ok() {
            return error == null;
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-registry");
        t.setDaemon(true);
        return t;
    });
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ConcurrentHashMap<String, List<RegistryEntry>> cache = new ConcurrentHashMap<>();

    /** Clears the per-URL cache so the next {@link #fetch} re-downloads. */
    public void invalidate() {
        cache.clear();
    }

    /** Whether {@code url} is a usable HTTPS registry URL. Pure — unit-tested. */
    public static boolean isHttps(String url) {
        if (url == null) {
            return false;
        }
        String u = url.strip().toLowerCase(Locale.ROOT);
        return u.startsWith("https://");
    }

    /**
     * Fetches + parses the registry at {@code url} off-thread, posting a {@link Result} on the FX thread.
     * Rejects non-HTTPS URLs and HTTP error statuses with a clear error message (no exception leaks).
     */
    public void fetch(String url, Consumer<Result> onResult) {
        List<RegistryEntry> cached = url == null ? null : cache.get(url);
        if (cached != null) {
            Platform.runLater(() -> onResult.accept(new Result(cached, null)));
            return;
        }
        exec.submit(() -> {
            Result r = fetchSync(url);
            if (r.ok()) {
                cache.put(url, r.entries());
            }
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    private Result fetchSync(String url) {
        if (!isHttps(url)) {
            return new Result(List.of(), "registry url must be https");
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() / 100 != 2) {
                return new Result(List.of(), "HTTP " + resp.statusCode());
            }
            RegistryIndex idx = RegistryIndex.parse(mapper, new ByteArrayInputStream(resp.body()));
            return new Result(List.copyOf(idx.plugins), null);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fetch plugin registry " + url, e);
            return new Result(List.of(), e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage()));
        }
    }
}
