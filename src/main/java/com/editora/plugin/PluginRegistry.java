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

import javafx.application.Platform;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
    /** Hard cap on the registry index size (a hostile registry must not be able to OOM us). */
    private static final long MAX_INDEX_BYTES = 8L * 1024 * 1024;
    /** Detached signature is tiny; cap its download hard. */
    private static final long MAX_SIG_BYTES = 4096;

    /**
     * The outcome of a fetch: either {@code entries} (possibly empty) or a non-null {@code error}.
     * {@code signed} is true when the index's detached signature verified against the bundled registry key.
     */
    public record Result(List<RegistryEntry> entries, String error, boolean signed) {
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
    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ConcurrentHashMap<String, Result> cache = new ConcurrentHashMap<>();

    /** Clears the per-URL cache so the next {@link #fetch} re-downloads. */
    public void invalidate() {
        cache.clear();
    }

    /**
     * Reads an input stream fully but aborts if it exceeds {@code max} bytes, so a hostile/oversized HTTP
     * response can't exhaust memory. Throws {@link java.io.IOException} when the cap is exceeded.
     */
    public static byte[] readCapped(java.io.InputStream in, long max) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new java.io.IOException("response exceeds " + max + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
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
        Result cached = url == null ? null : cache.get(url);
        if (cached != null) {
            Platform.runLater(() -> onResult.accept(cached));
            return;
        }
        exec.submit(() -> {
            Result r = fetchSync(url);
            if (r.ok()) {
                cache.put(url, r);
            }
            Platform.runLater(() -> onResult.accept(r));
        });
    }

    private Result fetchSync(String url) {
        if (!isHttps(url)) {
            return new Result(List.of(), "registry url must be https", false);
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return new Result(List.of(), "HTTP " + resp.statusCode(), false);
            }
            byte[] body;
            try (java.io.InputStream in = resp.body()) {
                body = readCapped(in, MAX_INDEX_BYTES);
            }
            RegistryIndex idx = RegistryIndex.parse(mapper, new ByteArrayInputStream(body));
            boolean signed = verifyIndexSignature(url, body);
            return new Result(List.copyOf(idx.plugins), null, signed);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fetch plugin registry " + url, e);
            return new Result(
                    List.of(),
                    e.getClass().getSimpleName() + (e.getMessage() == null ? "" : ": " + e.getMessage()),
                    false);
        }
    }

    /**
     * Fetches the detached signature at {@code <indexUrl>.sig} and verifies it over the exact index bytes
     * with the bundled registry key. Returns false (unverified) on any miss/failure — never throws.
     */
    private boolean verifyIndexSignature(String indexUrl, byte[] indexBytes) {
        if (!PluginSignature.hasBundledKey()) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(indexUrl.strip() + ".sig"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return false;
            }
            String sig;
            try (java.io.InputStream in = resp.body()) {
                sig = new String(readCapped(in, MAX_SIG_BYTES), java.nio.charset.StandardCharsets.UTF_8).strip();
            }
            return PluginSignature.verify(indexBytes, sig, PluginSignature.bundledPublicKey());
        } catch (Exception e) {
            return false;
        }
    }
}
