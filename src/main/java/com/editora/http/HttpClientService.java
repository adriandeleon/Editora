package com.editora.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

/**
 * UI-facing façade for running {@code .http} requests with the <b>built-in JDK {@code HttpClient}</b>
 * (no external CLI): each run gets its own daemon worker and results post back on the JavaFX thread. A run
 * gets a fresh {@link CookieManager} (a per-run cookie jar) and a {@link CapturedResponses} session, so a
 * later request can reference an earlier one's response ({@code {{name.response.body.$.x}}}). Per-request
 * directives ({@code @no-redirect}/{@code @no-cookie-jar}/{@code @timeout}), external bodies, multipart, and
 * the {@code >>}/{@code >>!} response-redirect operators are honored here. Each call returns an
 * {@link HttpExchange} carrying the fully resolved request alongside its {@link HttpResult}.
 *
 * <p>Bodies are <b>bytes</b> end to end: a raw {@code < file} body is uploaded as its bytes, the response is
 * read as bytes ({@link HttpResult#rawBody()}), and {@code >>} writes exactly those bytes — text is decoded
 * only for display. A response is read up to {@link #DEFAULT_MAX_RESPONSE_BYTES} and then cut with a visible
 * warning. Every run returns a {@link Handle}: {@link Handle#cancel()} tears the exchange down, and an
 * overall deadline stops a response that never ends (a server-sent-event stream), so neither can hold up
 * the requests run after it.
 */
public final class HttpClientService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** The most response bytes kept; a longer response is cut here and flagged {@link HttpResult#truncated()}. */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 50 * 1024 * 1024;

    /** How long a response body may keep arriving after its headers before the run is stopped. */
    private static final Duration DEFAULT_BODY_DEADLINE = Duration.ofMinutes(5);

    /** The {@link HttpResult#error()} of a request the user cancelled. */
    public static final String CANCELLED = "request cancelled";

    // One worker per run (not a single shared thread): a request that hangs must not queue up the next run.
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "http-client");
        t.setDaemon(true);
        return t;
    });

    private final Set<Handle> active = ConcurrentHashMap.newKeySet();
    private final int maxResponseBytes;
    private final Duration bodyDeadline;

    /** Preserves a file a {@code >>!} redirect is about to replace; see {@link ResponseRedirects.Guard}. */
    private volatile ResponseRedirects.Guard guard = ResponseRedirects.Guard.UNPROTECTED;

    public HttpClientService() {
        this(DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_BODY_DEADLINE);
    }

    /** Test seam: a small size cap / short body deadline. */
    HttpClientService(int maxResponseBytes, Duration bodyDeadline) {
        this.maxResponseBytes = maxResponseBytes;
        this.bodyDeadline = bodyDeadline;
    }

    /**
     * Sets who preserves the previous content of a file before a {@code >>!} redirect replaces it. Called on
     * the worker thread of the run; it may block while a history revision becomes durable.
     */
    public void setRedirectGuard(ResponseRedirects.Guard guard) {
        this.guard = guard == null ? ResponseRedirects.Guard.UNPROTECTED : guard;
    }

    /**
     * A running request (or run-all): {@link #cancel()} aborts the in-flight exchange — the pending send, or
     * the body still streaming in — and skips any requests not yet started. The result callback still fires,
     * with a {@link #CANCELLED} failure, so the viewer can leave its "running" state.
     */
    public static final class Handle {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final CompletableFuture<Void> delivered = new CompletableFuture<>();
        private volatile CompletableFuture<?> pending;
        private volatile InputStream stream;

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                CompletableFuture<?> f = pending;
                if (f != null) {
                    f.cancel(true);
                }
                closeQuietly(stream);
            }
        }

        public boolean isCancelled() {
            return cancelled.get();
        }

        /** Completes once the result callback has run on the FX thread (also for a cancelled run). */
        public CompletableFuture<Void> delivered() {
            return delivered;
        }

        private void deliver(Runnable callback) {
            Platform.runLater(() -> {
                try {
                    callback.run();
                } finally {
                    delivered.complete(null);
                }
            });
        }
    }

    /** Runs a single request off-thread (its own cookie jar, no prior context), posting on the FX thread. */
    public Handle run(
            HttpFile.Parsed request, Map<String, String> vars, Path baseDir, Consumer<HttpExchange> onResult) {
        Handle handle = new Handle();
        active.add(handle);
        exec.submit(() -> {
            HttpExchange ex;
            try {
                ex = execute(handle, request, vars, baseDir, new CapturedResponses(), new CookieManager());
            } finally {
                active.remove(handle);
            }
            handle.deliver(() -> onResult.accept(ex));
        });
        return handle;
    }

    /** Runs {@code requests} sequentially off-thread, sharing one cookie jar + captured-response session so
     *  later requests can reference earlier responses; posts all exchanges together on the FX thread. */
    public Handle runAll(
            List<HttpFile.Parsed> requests,
            Map<String, String> vars,
            Path baseDir,
            Consumer<List<HttpExchange>> onResult) {
        Handle handle = new Handle();
        active.add(handle);
        exec.submit(() -> {
            List<HttpExchange> out = new ArrayList<>();
            try {
                CookieManager cookies = new CookieManager();
                CapturedResponses captured = new CapturedResponses();
                for (HttpFile.Parsed req : requests) {
                    HttpExchange ex = execute(handle, req, vars, baseDir, captured, cookies);
                    captured.put(req.name(), ex.result());
                    out.add(ex);
                    if (handle.isCancelled()) {
                        break; // the cancelled request is reported; the rest are not started
                    }
                }
            } finally {
                active.remove(handle);
            }
            handle.deliver(() -> onResult.accept(out));
        });
        return handle;
    }

    private HttpExchange execute(
            Handle handle,
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            CookieManager cookies) {
        LocalDateTime now = LocalDateTime.now();
        Function<String, String> sub = s -> HttpVars.substitute(s, vars, now, captured, baseDir);
        String url = sub.apply(request.url()).trim();
        if (!request.directives().noAutoEncoding()) {
            url = UrlEncoding.encodeIllegal(url); // default auto-encoding (off under @no-auto-encoding)
        }
        String method = request.method().isEmpty() ? "GET" : request.method();

        List<String[]> headers = new ArrayList<>();
        for (String[] h : request.headers()) {
            headers.add(new String[] {h[0], sub.apply(h[1])});
        }
        headers = HttpAuth.normalizeHeaders(headers);
        // Trim whitespace/newlines the JDK client would reject, and collect any header it still can't send —
        // otherwise a header (a pasted Authorization with a trailing \r\n) is dropped silently and the request
        // goes out unauthenticated. From here on `headers` is the sendable, trimmed set.
        HttpHeaders.Partition hp = HttpHeaders.partition(headers);
        headers = hp.sendable();
        List<String> warnings = new ArrayList<>();
        if (request.warning() != null) {
            warnings.add(request.warning()); // e.g. a body written without the required blank line before it
        }
        warnings.addAll(hp.warnings());
        String contentType = headerValue(headers, "Content-Type");

        BodyContent bc = resolveBody(request, baseDir, sub, contentType, headers);
        String label = method + " " + url;
        if (bc.problem() != null) {
            // The body the request names cannot be read. Sending the request without it would look like a
            // server problem (and could create an empty resource), so it is not sent at all.
            warnings.add(bc.problem());
            HttpResult notSent = HttpResult.failure("request not sent: " + bc.problem(), warnings);
            return new HttpExchange(label, method, url, headers, bc.display(), notSent);
        }
        if (handle.isCancelled()) {
            return new HttpExchange(label, method, url, headers, bc.display(), HttpResult.failure(CANCELLED, warnings));
        }
        // Digest shorthand (Authorization: Digest user pass): send anonymously, then answer a 401 challenge.
        DigestAuth.Credentials digest = digestCredentials(headers);
        if (digest != null) {
            headers.removeIf(h -> h[0].equalsIgnoreCase("Authorization"));
        }
        HttpClient client = clientFor(request.directives(), cookies);
        try {
            Duration timeout = request.directives().timeoutSeconds() > 0
                    ? Duration.ofSeconds(request.directives().timeoutSeconds())
                    : REQUEST_TIMEOUT;

            long t0 = System.nanoTime();
            Received resp = send(handle, client, url, method, headers, timeout, bc.publisher(), null);
            if (digest != null && resp.statusCode() == 401) {
                String challenge = resp.headers().firstValue("WWW-Authenticate").orElse("");
                if (challenge.regionMatches(true, 0, "Digest", 0, 6)) {
                    String authz = DigestAuth.authorization(
                            digest,
                            method,
                            requestTarget(url),
                            DigestAuth.parseChallenge(challenge),
                            randomHex(),
                            "00000001");
                    resp = send(handle, client, url, method, headers, timeout, bc.publisher(), authz);
                }
            }
            long ms = (System.nanoTime() - t0) / 1_000_000;

            List<String[]> respHeaders = new ArrayList<>();
            resp.headers().map().forEach((k, values) -> {
                for (String v : values) {
                    respHeaders.add(new String[] {k, v});
                }
            });
            String respType = resp.headers().firstValue("content-type").orElse("");
            if (resp.truncated()) {
                warnings.add("response truncated at " + megabytes(maxResponseBytes)
                        + " — the rest of the body was not downloaded");
            }
            if (resp.expired()) {
                warnings.add("response still arriving after " + bodyDeadline.toSeconds() + " s — stopped; showing the "
                        + resp.body().length + " bytes received so far");
            }
            boolean partial = resp.truncated() || resp.expired();
            List<String> written = new ArrayList<>();
            // Before the result is built: what the redirects saved (and refused to replace) is part of it.
            ResponseRedirects.write(
                    request.redirects(), baseDir, resp.statusCode(), resp.body(), partial, guard, written, warnings);
            HttpResult result = HttpResult.ofBytes(
                    resp.statusCode(), respHeaders, resp.body(), respType, ms, warnings, partial, written);
            return new HttpExchange(label, method, url, headers, bc.display(), result);
        } catch (Exception e) {
            String message = handle.isCancelled() ? CANCELLED : errorMessage(url, e);
            return new HttpExchange(label, method, url, headers, bc.display(), HttpResult.failure(message, warnings));
        } finally {
            client.shutdownNow(); // release the connection (and its selector thread) — also after a cancel
        }
    }

    private static String megabytes(int bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : bytes + " bytes";
    }

    /**
     * The request body publisher + a display string (for the cURL/history view). A non-null {@code problem}
     * means the body the request refers to could not be read and the request must not be sent.
     */
    private record BodyContent(HttpRequest.BodyPublisher publisher, String display, String problem) {
        BodyContent(HttpRequest.BodyPublisher publisher, String display) {
            this(publisher, display, null);
        }

        static BodyContent unreadable(String problem) {
            return new BodyContent(HttpRequest.BodyPublishers.noBody(), "(" + problem + ")", problem);
        }
    }

    /** A response read to the end (or to the size cap / body deadline): status, headers and raw bytes. */
    private record Received(
            int statusCode, java.net.http.HttpHeaders headers, byte[] body, boolean truncated, boolean expired) {}

    private static BodyContent resolveBody(
            HttpFile.Parsed request,
            Path baseDir,
            Function<String, String> sub,
            String contentType,
            List<String[]> headers) {
        // multipart/form-data: assemble the parts (inline parts substituted, < ./file parts slurped).
        if (Multipart.isMultipart(contentType)) {
            String boundary = Multipart.boundaryOf(contentType);
            if (boundary.isEmpty()) {
                boundary = "EditoraBoundary" + Long.toHexString(System.nanoTime());
                setHeader(headers, "Content-Type", contentType + "; boundary=" + boundary);
            }
            List<String> problems = new ArrayList<>();
            byte[] bytes = Multipart.build(Multipart.parse(request.body(), boundary), boundary, baseDir, sub, problems);
            if (!problems.isEmpty()) {
                return BodyContent.unreadable(String.join("; ", problems));
            }
            return new BodyContent(
                    HttpRequest.BodyPublishers.ofByteArray(bytes), "(multipart/form-data, " + bytes.length + " bytes)");
        }
        // external body: < ./file (raw) or <@ ./file (substituted), optional encoding.
        if (request.bodyRef() != null && baseDir != null) {
            try {
                HttpFile.BodyRef ref = request.bodyRef();
                Charset cs = ref.encoding() == null ? StandardCharsets.UTF_8 : Charset.forName(ref.encoding());
                Path bodyFile = HttpPaths.contained(baseDir, ref.path());
                if (bodyFile == null) {
                    return BodyContent.unreadable("body file outside the request folder: " + ref.path());
                }
                byte[] raw = Files.readAllBytes(bodyFile);
                if (!ref.substitute()) {
                    // "< file": the file's bytes are the body, untouched — a decode/re-encode round trip
                    // through String corrupts anything that is not valid UTF-8 (an image, a protobuf).
                    String shown = HttpResult.looksBinary(raw, null)
                            ? "(binary body from " + ref.path() + ", " + raw.length + " bytes)"
                            : new String(raw, cs);
                    return new BodyContent(HttpRequest.BodyPublishers.ofByteArray(raw), shown);
                }
                String content = sub.apply(new String(raw, cs));
                return new BodyContent(HttpRequest.BodyPublishers.ofString(content), content);
            } catch (Exception e) {
                return BodyContent.unreadable(
                        "body file not found: " + request.bodyRef().path());
            }
        }
        if (request.bodyRef() != null) {
            return BodyContent.unreadable("body file needs a saved request file to resolve against: "
                    + request.bodyRef().path());
        }
        String body = sub.apply(request.body());
        if (body == null || body.isBlank()) {
            return new BodyContent(HttpRequest.BodyPublishers.noBody(), "");
        }
        return new BodyContent(HttpRequest.BodyPublishers.ofString(body), body);
    }

    private static HttpClient clientFor(HttpFile.Directives directives, CookieManager cookies) {
        Duration connect = directives.connectionTimeoutSeconds() > 0
                ? Duration.ofSeconds(directives.connectionTimeoutSeconds())
                : CONNECT_TIMEOUT;
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(connect)
                .followRedirects(directives.noRedirect() ? HttpClient.Redirect.NEVER : HttpClient.Redirect.NORMAL);
        if (!directives.noCookieJar()) {
            b.cookieHandler(cookies);
        }
        return b.build();
    }

    /**
     * Builds and sends one request and reads its body as bytes; {@code authOverride} (when non-null) sets the
     * Authorization header. The send is asynchronous so {@code handle} can cancel it; the body is streamed so
     * it can be cut at the size cap, and a body still arriving at the deadline is closed and returned as far
     * as it got.
     */
    private Received send(
            Handle handle,
            HttpClient client,
            String url,
            String method,
            List<String[]> headers,
            Duration timeout,
            HttpRequest.BodyPublisher publisher,
            String authOverride)
            throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        for (String[] h : headers) {
            b.header(h[0], h[1]); // pre-validated by HttpHeaders.partition — un-sendable ones were surfaced
        }
        if (authOverride != null) {
            b.header("Authorization", authOverride);
        }
        b.method(method, publisher);
        CompletableFuture<HttpResponse<InputStream>> future =
                client.sendAsync(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        handle.pending = future;
        if (handle.isCancelled()) {
            future.cancel(true); // cancel() raced the assignment above
        }
        HttpResponse<InputStream> resp;
        try {
            // HttpRequest.timeout bounds the wait for the response headers; the margin only covers its slack.
            resp = future.get(timeout.toMillis() + 5_000, TimeUnit.MILLISECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw cause instanceof IOException io ? io : new IOException(cause.getMessage(), cause);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new HttpTimeoutException("request timed out");
        } catch (CancellationException e) {
            throw new IOException(CANCELLED, e);
        }
        AtomicBoolean expired = new AtomicBoolean();
        try (InputStream in = resp.body()) {
            handle.stream = in;
            if (handle.isCancelled()) {
                throw new IOException(CANCELLED);
            }
            // Closing the stream from the timer unblocks a read that would otherwise wait forever.
            CompletableFuture<Void> watchdog = CompletableFuture.runAsync(
                    () -> {
                        expired.set(true);
                        closeQuietly(in);
                    },
                    CompletableFuture.delayedExecutor(bodyDeadline.toMillis(), TimeUnit.MILLISECONDS));
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean truncated = readCapped(in, out, maxResponseBytes, handle, expired);
                return new Received(resp.statusCode(), resp.headers(), out.toByteArray(), truncated, expired.get());
            } finally {
                watchdog.cancel(false);
            }
        } finally {
            handle.stream = null;
        }
    }

    /**
     * Copies {@code in} to {@code out} up to {@code max} bytes; returns true when the stream had more. A read
     * that fails because the user cancelled is rethrown; one that fails because the body deadline closed the
     * stream ends the copy with what arrived.
     */
    private static boolean readCapped(
            InputStream in, ByteArrayOutputStream out, int max, Handle handle, AtomicBoolean expired)
            throws IOException {
        byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                int room = max - out.size();
                if (n > room) {
                    out.write(buf, 0, room);
                    return true;
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            if (handle.isCancelled() || !expired.get()) {
                throw e;
            }
        }
        if (handle.isCancelled()) {
            throw new IOException(CANCELLED); // a closed stream may also just report end-of-stream
        }
        return false;
    }

    private static void closeQuietly(InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException | RuntimeException ignore) {
                // already closed / torn down
            }
        }
    }

    private static DigestAuth.Credentials digestCredentials(List<String[]> headers) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Authorization")) {
                return DigestAuth.shorthand(h[1]);
            }
        }
        return null;
    }

    /** The request target (path + query) for the Digest {@code uri} parameter. */
    private static String requestTarget(String url) {
        try {
            URI u = URI.create(url);
            String path = u.getRawPath();
            if (path == null || path.isEmpty()) {
                path = "/";
            }
            return u.getRawQuery() == null ? path : path + "?" + u.getRawQuery();
        } catch (Exception e) {
            return url;
        }
    }

    private static String randomHex() {
        return String.format(
                "%016x", java.util.concurrent.ThreadLocalRandom.current().nextLong());
    }

    private static String headerValue(List<String[]> headers, String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return "";
    }

    private static void setHeader(List<String[]> headers, String name, String value) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                h[1] = value;
                return;
            }
        }
        headers.add(new String[] {name, value});
    }

    private static String errorMessage(String url, Exception e) {
        String m = e.getMessage();
        String base = m == null || m.isBlank() ? e.getClass().getSimpleName() : m;
        return url.isBlank() ? base : base + "  (" + url + ")";
    }

    /** Stops the daemon worker (window close). Without this, each closed window leaks its http-client thread. */
    public void shutdown() {
        active.forEach(Handle::cancel);
        exec.shutdownNow();
    }
}
