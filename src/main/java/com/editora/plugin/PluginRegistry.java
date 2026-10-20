package com.editora.plugin;

import java.io.ByteArrayInputStream;
import java.net.URI;
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
import org.jspecify.annotations.Nullable;

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
    /** The longest the index body may take once its headers arrived (a server can drip bytes for ever). */
    private static final Duration BODY_DEADLINE = Duration.ofSeconds(60);

    /**
     * The outcome of a fetch: either {@code entries} (possibly empty) or a non-null {@code error}.
     * {@code signed} is true when the index's detached signature verified against the bundled registry key.
     */
    public record Result(
            List<RegistryEntry> entries,
            @Nullable String error,
            boolean signed,
            @Nullable PluginProblem problem) {
        public boolean ok() {
            return error == null;
        }

        static Result failed(PluginProblem p) {
            return new Result(List.of(), p.english(), false, p);
        }
    }

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "plugin-registry");
        t.setDaemon(true);
        return t;
    });
    /** Built by the first fetch, not with the registry (constructed for every window at startup). */
    private final com.editora.io.LazyHttpClient client;

    /** The key an index signature must verify against: the bundled registry key in production. */
    private final java.util.function.Supplier<java.security.PublicKey> registryKey;

    public PluginRegistry() {
        this(com.editora.io.LazyHttpClient.following(CONNECT_TIMEOUT), PluginSignature::bundledPublicKey);
    }

    /**
     * Test seam: the client the index and its signature are fetched through, and the key the signature is
     * checked against — so a test can serve a registry from a loopback server, signed with a key pair it
     * generated. The HTTPS rule and the size caps are not part of the seam.
     */
    PluginRegistry(
            com.editora.io.LazyHttpClient client, java.util.function.Supplier<java.security.PublicKey> registryKey) {
        this.client = client;
        this.registryKey = registryKey;
    }

    private final ObjectMapper mapper = new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    private final ConcurrentHashMap<String, Result> cache = new ConcurrentHashMap<>();
    /** URL → the callbacks waiting for its fetch: a second Browse while one runs joins it instead of queueing. */
    private final java.util.Map<String, List<Consumer<Result>>> inFlight = new java.util.HashMap<>();

    /** Stops the worker and releases the HTTP client (window dispose). */
    public void shutdown() {
        exec.shutdownNow();
        client.close();
    }

    /** Clears the per-URL cache so the next {@link #fetch} re-downloads. */
    public void invalidate() {
        cache.clear();
    }

    /**
     * Reads an input stream fully but aborts if it exceeds {@code max} bytes, so a hostile/oversized HTTP
     * response can't exhaust memory. Throws {@link java.io.IOException} when the cap is exceeded.
     */
    public static byte[] readCapped(java.io.InputStream in, long max) throws java.io.IOException {
        return readCapped(in, max, null);
    }

    /** The exception {@link #readCapped} throws when the body is over its cap. */
    public static final class TooLargeException extends java.io.IOException {
        TooLargeException(long max) {
            super("response exceeds " + max + " bytes");
        }
    }

    /**
     * As {@link #readCapped(java.io.InputStream, long)}, and also aborts once {@code deadline} (null: none) has
     * passed since the read began. The JDK's request timeout ends when the headers arrive; a server that then
     * sends a byte every few seconds kept Browse "Fetching…" for minutes.
     */
    public static byte[] readCapped(java.io.InputStream in, long max, @Nullable Duration deadline)
            throws java.io.IOException {
        long end = deadline == null ? Long.MAX_VALUE : System.nanoTime() + deadline.toNanos();
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) {
                throw new TooLargeException(max);
            }
            out.write(buf, 0, n);
            if (deadline != null && System.nanoTime() - end > 0) {
                throw new java.io.IOException(PluginProblem.DEADLINE + deadline.toSeconds() + " s");
            }
        }
        return out.toByteArray();
    }

    /** Whether {@code url} is a usable HTTPS registry URL. Pure — unit-tested. */
    public static boolean isHttps(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String u = url.strip();
        if (!u.toLowerCase(Locale.ROOT).startsWith("https://")) {
            return false;
        }
        try {
            String host = URI.create(u).getHost(); // "https://not a url" and "https:///x" are not addresses
            return host != null && !host.isBlank();
        } catch (IllegalArgumentException notAUri) {
            return false;
        }
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
        String key = String.valueOf(url);
        synchronized (inFlight) {
            List<Consumer<Result>> waiting = inFlight.get(key);
            if (waiting != null) {
                waiting.add(onResult); // a second click while the first fetch runs: one fetch, both answered
                return;
            }
            inFlight.put(key, new java.util.ArrayList<>(List.of(onResult)));
        }
        exec.submit(() -> {
            Result r;
            try {
                r = fetchSync(url);
            } catch (Throwable t) { // NOSONAR: the waiting callers must hear back
                r = Result.failed(new PluginProblem(PluginProblem.Kind.FAILED, List.of(), String.valueOf(t)));
            }
            if (r.ok()) {
                cache.put(url, r);
            }
            List<Consumer<Result>> callers;
            synchronized (inFlight) {
                callers = inFlight.remove(key);
            }
            Result result = r;
            Platform.runLater(() -> callers.forEach(c -> c.accept(result)));
        });
    }

    /** Whether a fetch of {@code url} is running now. */
    public boolean fetching(String url) {
        synchronized (inFlight) {
            return inFlight.containsKey(String.valueOf(url));
        }
    }

    /** {@link #fetch} without the worker and the FX hand-off. Never throws. */
    Result fetchSync(String url) {
        if (!isHttps(url)) {
            return Result.failed(PluginProblem.of(PluginProblem.Kind.NOT_HTTPS));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url.strip()))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.get().send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return Result.failed(
                        PluginProblem.of(PluginProblem.Kind.HTTP_STATUS, String.valueOf(resp.statusCode())));
            }
            byte[] body;
            try (java.io.InputStream in = resp.body()) {
                body = readCapped(in, MAX_INDEX_BYTES, BODY_DEADLINE);
            }
            RegistryIndex idx;
            try {
                idx = RegistryIndex.parse(mapper, new ByteArrayInputStream(body));
            } catch (java.io.IOException | RuntimeException bad) {
                return Result.failed(new PluginProblem(
                        PluginProblem.Kind.INDEX_INVALID, List.of(), PluginManager.readableError(bad)));
            }
            if (idx.schemaVersion > RegistryIndex.SCHEMA_VERSION) {
                // A format this build does not know may mean fields it would ignore (a requirement, a warning).
                return Result.failed(
                        PluginProblem.of(PluginProblem.Kind.INDEX_TOO_NEW, String.valueOf(idx.schemaVersion)));
            }
            boolean signed = verifyIndexSignature(url, body);
            return new Result(List.copyOf(idx.plugins), null, signed, null);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to fetch plugin registry " + url, e);
            return Result.failed(PluginProblem.network(url, e));
        }
    }

    /**
     * Fetches the detached signature at {@code <indexUrl>.sig} and verifies it over the exact index bytes
     * with the bundled registry key. Returns false (unverified) on any miss/failure — never throws.
     */
    private boolean verifyIndexSignature(String indexUrl, byte[] indexBytes) {
        java.security.PublicKey key = registryKey.get();
        if (key == null) {
            return false;
        }
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(indexUrl.strip() + ".sig"))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<java.io.InputStream> resp = client.get().send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() / 100 != 2) {
                resp.body().close();
                return false;
            }
            String sig;
            try (java.io.InputStream in = resp.body()) {
                sig = new String(readCapped(in, MAX_SIG_BYTES, BODY_DEADLINE), java.nio.charset.StandardCharsets.UTF_8)
                        .strip();
            }
            return PluginSignature.verify(indexBytes, sig, key);
        } catch (Exception e) {
            return false;
        }
    }
}
