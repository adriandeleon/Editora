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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javafx.application.Platform;

import static com.editora.i18n.Messages.tr;

/**
 * UI-facing façade for running {@code .http} requests with the <b>built-in JDK {@code HttpClient}</b>
 * (no external CLI): each run gets its own daemon worker and results post back on the JavaFX thread. A run
 * gets a fresh {@link CookieManager} (a per-run cookie jar) and resolves against a {@link CapturedResponses}
 * session — the caller's per-buffer one, so a request can reference the response of one run earlier
 * ({@code {{name.response.body.$.x}}}), alone or in Run All. Per-request
 * directives ({@code @no-redirect}/{@code @no-cookie-jar}/{@code @timeout}), external bodies, multipart, and
 * the {@code >>}/{@code >>!} response-redirect operators are honored here. Each call returns an
 * {@link HttpExchange} carrying the fully resolved request alongside its {@link HttpResult}.
 *
 * <p>Bodies are <b>bytes</b> end to end: a raw {@code < file} body is streamed from the file, the response is
 * read as bytes ({@link HttpResult#rawBody()}) — decoded from {@code gzip}/{@code deflate} first, see
 * {@link ContentDecoding} — and {@code >>} writes exactly those bytes; text is decoded only for display. A text
 * body is encoded with the charset its {@code Content-Type} declares (UTF-8 when it declares none). A response is
 * read up to {@link #DEFAULT_MAX_RESPONSE_BYTES} and then cut with a visible warning. Every run returns a
 * {@link Handle}: {@link Handle#cancel()} tears the exchange down, and an overall deadline (plus, under
 * {@code @timeout}, a limit on the silence between reads) stops a response that never ends, so neither can hold
 * up the requests run after it.
 *
 * <p>The protocol follows the JetBrains client ({@link HttpVersions}): HTTP/1.1 for {@code http://}, HTTP/2
 * negotiation for {@code https://}, or what the request line asks for. One client is kept per run and per
 * directive set, so the requests of a Run All reuse their connections.
 *
 * <p>A run always ends with a delivered result: anything thrown while preparing or sending a request — even an
 * {@link OutOfMemoryError} on a huge response — becomes that request's failure, and Run All carries on with the
 * next request.
 */
public final class HttpClientService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /** The most response bytes kept; a longer response is cut here and flagged {@link HttpResult#truncated()}. */
    public static final int DEFAULT_MAX_RESPONSE_BYTES = 50 * 1024 * 1024;

    /** How long a response body may keep arriving after its headers before the run is stopped. */
    private static final Duration DEFAULT_BODY_DEADLINE = Duration.ofMinutes(5);

    /** Up to this size an uploaded {@code < file} body is also shown as text in the history and cURL views. */
    private static final long SHOWN_FILE_BODY_BYTES = 1024 * 1024;

    /** The JDK client's limit on the exchanges of one request (the original one plus the redirects followed). */
    private static final int REDIRECT_LIMIT = Integer.getInteger("jdk.httpclient.redirects.retrylimit", 5);

    /** The {@link HttpResult#error()} of a request the user cancelled. */
    public static final String CANCELLED = "request cancelled";

    static {
        allowHostHeader();
    }

    /**
     * Lets a request set its own {@code Host} header (testing a virtual host by IP). The JDK reads this property
     * once, when its HTTP classes first load, so {@code App.main} sets it at startup; this is the fallback for
     * other entry points and only works if no HTTP client was used before.
     */
    public static void allowHostHeader() {
        String allowed = System.getProperty("jdk.httpclient.allowRestrictedHeaders", "");
        if (!allowed.toLowerCase(Locale.ROOT).matches("(^|.*,)\\s*host\\s*(,.*|$)")) {
            System.setProperty("jdk.httpclient.allowRestrictedHeaders", allowed.isBlank() ? "host" : allowed + ",host");
        }
    }

    // One worker per run (not a single shared thread): a request that hangs must not queue up the next run.
    private final ExecutorService exec = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "http-client");
        t.setDaemon(true);
        return t;
    });

    /** Watches response bodies for the overall deadline and the {@code @timeout} silence limit. */
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "http-client-timer");
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

    /**
     * The clients of one run, one per directive set and version, so consecutive requests reuse connections;
     * all are shut down when the run ends.
     */
    private static final class Session implements AutoCloseable {
        private final CookieManager cookies;
        private final Map<ClientKey, HttpClient> clients = new HashMap<>();

        Session(CookieManager cookies) {
            this.cookies = cookies;
        }

        HttpClient client(ClientKey key) {
            return clients.computeIfAbsent(key, k -> {
                HttpClient.Builder b = HttpClient.newBuilder()
                        .connectTimeout(k.connectTimeout())
                        .version(k.version())
                        .followRedirects(k.noRedirect() ? HttpClient.Redirect.NEVER : HttpClient.Redirect.NORMAL);
                if (!k.noCookieJar()) {
                    b.cookieHandler(cookies);
                }
                return b.build();
            });
        }

        @Override
        public void close() {
            clients.values().forEach(HttpClient::shutdownNow); // releases connections and selector threads
            clients.clear();
        }
    }

    private record ClientKey(
            boolean noRedirect, boolean noCookieJar, Duration connectTimeout, HttpClient.Version version) {}

    /** Runs a single request off-thread (its own cookie jar, no prior context), posting on the FX thread. */
    public Handle run(
            HttpFile.Parsed request, Map<String, String> vars, Path baseDir, Consumer<HttpExchange> onResult) {
        return run(request, vars, baseDir, new CapturedResponses(), onResult);
    }

    /**
     * Runs a single request off-thread (its own cookie jar), resolving {@code {{name.response…}}} against
     * {@code captured} — the buffer's session — and recording this request's result there under its name.
     */
    public Handle run(
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            Consumer<HttpExchange> onResult) {
        Handle handle = new Handle();
        active.add(handle);
        CapturedResponses responses = captured == null ? new CapturedResponses() : captured;
        exec.submit(() -> {
            HttpExchange ex;
            try (Session session = new Session(new CookieManager())) {
                ex = executeSafely(handle, request, vars, baseDir, responses, session);
                responses.put(request.name(), ex.result());
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
        return runAll(requests, vars, baseDir, new CapturedResponses(), onResult);
    }

    /** As {@link #runAll(List, Map, Path, Consumer)}, resolving and recording responses in {@code session}. */
    public Handle runAll(
            List<HttpFile.Parsed> requests,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses responses,
            Consumer<List<HttpExchange>> onResult) {
        Handle handle = new Handle();
        active.add(handle);
        CapturedResponses captured = responses == null ? new CapturedResponses() : responses;
        exec.submit(() -> {
            List<HttpExchange> out = new ArrayList<>();
            try (Session session = new Session(new CookieManager())) {
                for (HttpFile.Parsed req : requests) {
                    HttpExchange ex = executeSafely(handle, req, vars, baseDir, captured, session);
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

    /**
     * {@link #execute}, but whatever it throws becomes this request's failure: the run must always deliver a
     * result, or the viewer stays on "Running…" forever and a Run All silently drops every later request.
     */
    private HttpExchange executeSafely(
            Handle handle,
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            Session session) {
        try {
            return execute(handle, request, vars, baseDir, captured, session);
        } catch (Throwable failure) { // also an OutOfMemoryError: the arrays it was filling are garbage by now
            String method = request == null
                            || request.method() == null
                            || request.method().isEmpty()
                    ? "GET"
                    : request.method();
            String url = request == null || request.url() == null ? "" : request.url();
            String message = handle.isCancelled()
                    ? CANCELLED
                    : failure instanceof OutOfMemoryError
                            ? tr("status.http.error.memory")
                            : tr("status.http.error.internal", describe(failure));
            return new HttpExchange(
                    method + " " + url, method, url, List.of(), "", HttpResult.failure(message, List.of()));
        }
    }

    private static String describe(Throwable t) {
        String m = t.getMessage();
        return t.getClass().getSimpleName() + (m == null || m.isBlank() ? "" : ": " + m);
    }

    private HttpExchange execute(
            Handle handle,
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            Session session) {
        // The clock is a real instant in the user's zone: $timestamp/$isoTimestamp are UTC whatever the zone.
        ZonedDateTime now = ZonedDateTime.now();
        // The variables this request sees: the environment's, then the @var lines above it (in order), resolved
        // now — so they may use an earlier response or $dotenv beside the file. What cannot be resolved is
        // collected and reported, never sent silently as an empty value.
        HttpVars.Unresolved unresolved = new HttpVars.Unresolved();
        HttpVars.Scope scope = HttpVars.scope(vars, request.variables(), now, captured, baseDir);
        Function<String, String> sub = s -> HttpVars.substitute(s, scope, now, captured, baseDir, unresolved);
        String method = request.method().isEmpty() ? "GET" : request.method();

        List<String[]> headers = new ArrayList<>();
        for (String[] h : request.headers()) {
            headers.add(new String[] {h[0], sub.apply(h[1])});
        }
        // "GET /path" + "Host: example.com" (origin form): the URL comes from the Host header; a target without
        // a scheme is http:// (JetBrains).
        HttpTargets.Target target = HttpTargets.resolve(sub.apply(request.url()).trim(), headers);
        String url = UrlEncoding.withScheme(target.url(), null);
        headers = new ArrayList<>(target.headers());
        if (!request.directives().noAutoEncoding()) {
            url = UrlEncoding.encodeIllegal(url); // default auto-encoding (off under @no-auto-encoding)
        }
        headers = HttpAuth.normalizeHeaders(headers);
        // Trim whitespace/newlines the JDK client would reject, and collect any header it still can't send —
        // otherwise a header (a pasted Authorization with a trailing \r\n) is dropped silently and the request
        // goes out unauthenticated. From here on `headers` is the sendable, trimmed set.
        HttpHeaders.Partition hp = HttpHeaders.partition(headers);
        headers = new ArrayList<>(hp.sendable());
        List<String> warnings = new ArrayList<>();
        // e.g. a body written without the required blank line before it, an ignored script, a bad @timeout
        warnings.addAll(request.warnings());
        warnings.addAll(hp.warnings());
        String requestedVersion = request.httpVersion();
        HttpVersions.Choice version = HttpVersions.choose(requestedVersion, url);
        if (version.warning() != null) {
            warnings.add(version.warning());
        }
        String contentType = headerValue(headers, "Content-Type");

        BodyContent bc = resolveBody(request, baseDir, sub, contentType, headers, warnings);
        // Digest shorthand (Authorization: Digest user pass): send anonymously, then answer a 401 challenge.
        DigestAuth.Credentials digest = digestCredentials(headers);
        if (digest != null) {
            headers.removeIf(h -> h[0].equalsIgnoreCase("Authorization"));
        }
        boolean noLog = request.directives().noLog();
        String label = method + " " + (noLog ? withoutQueryValues(url) : url);
        HttpExchange.Details details = new HttpExchange.Details(
                bc.file() == null ? null : bc.file().toAbsolutePath().toString(),
                bc.parts(),
                digest == null ? null : digest.user(),
                digest == null ? null : digest.pass(),
                !request.directives().noRedirect(),
                requestedVersion,
                null,
                noLog);
        if (request.error() != null) {
            // A GraphQL/WebSocket/gRPC request: sending it as plain HTTP would only produce a confusing error.
            warnings.addAll(unresolved.warnings());
            HttpResult notSent = HttpResult.failure(request.error(), warnings);
            return new HttpExchange(label, method, url, headers, bc.display(), notSent, details);
        }
        if (bc.problem() != null) {
            warnings.addAll(unresolved.warnings());
            // The body the request names cannot be read. Sending the request without it would look like a
            // server problem (and could create an empty resource), so it is not sent at all.
            warnings.add(bc.problem());
            HttpResult notSent = HttpResult.failure("request not sent: " + bc.problem(), warnings);
            return new HttpExchange(label, method, url, headers, bc.display(), notSent, details);
        }
        if (handle.isCancelled()) {
            warnings.addAll(unresolved.warnings());
            return new HttpExchange(
                    label, method, url, headers, bc.display(), HttpResult.failure(CANCELLED, warnings), details);
        }
        boolean explicitTimeout = request.directives().timeoutMillis() > 0;
        Duration timeout =
                explicitTimeout ? Duration.ofMillis(request.directives().timeoutMillis()) : REQUEST_TIMEOUT;
        Duration connect = request.directives().connectionTimeoutMillis() > 0
                ? Duration.ofMillis(request.directives().connectionTimeoutMillis())
                : CONNECT_TIMEOUT;
        try {
            HttpClient client = session.client(new ClientKey(
                    request.directives().noRedirect(), request.directives().noCookieJar(), connect, version.version()));
            // Under @timeout the limit also applies to the silence between body reads, as a read timeout would.
            Duration idle = explicitTimeout ? timeout : null;

            long t0 = System.nanoTime();
            Received resp = send(handle, client, url, method, headers, timeout, idle, bc.publisher(), null);
            if (digest != null && resp.statusCode() == 401) {
                List<String> challenges = resp.headers().allValues("WWW-Authenticate");
                Map<String, String> challenge = DigestAuth.strongest(challenges);
                if (challenge != null) {
                    String authz = DigestAuth.authorization(
                            digest,
                            method,
                            requestTarget(url),
                            challenge,
                            randomHex(),
                            "00000001",
                            bc.bytesForDigest());
                    resp = send(handle, client, url, method, headers, timeout, idle, bc.publisher(), authz);
                } else if (DigestAuth.offersDigest(challenges)) {
                    warnings.add(tr("status.http.warn.digestUnsupported", String.join(" | ", challenges)));
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
            if (resp.undecoded() != null) {
                warnings.add(tr("status.http.warn.undecoded", resp.undecoded()));
            }
            if (resp.truncated()) {
                warnings.add("response truncated at " + megabytes(maxResponseBytes)
                        + " — the rest of the body was not downloaded");
            }
            if (resp.stopped() == Stop.DEADLINE) {
                warnings.add("response still arriving after " + bodyDeadline.toSeconds() + " s — stopped; showing the "
                        + resp.body().length + " bytes received so far");
            } else if (resp.stopped() == Stop.IDLE) {
                warnings.add(tr("status.http.warn.idle", seconds(timeout), resp.body().length));
            }
            String finalUrl = resp.redirects().isEmpty() ? null : resp.uri().toString();
            warnings.addAll(RedirectNotes.warnings(
                    url, resp, headers, !request.directives().noRedirect()));
            boolean partial = resp.truncated() || resp.stopped() != null;
            List<String> written = new ArrayList<>();
            // Before the result is built: what the redirects saved (and refused to replace) is part of it.
            ResponseRedirects.write(
                    request.redirects(),
                    baseDir,
                    resp.statusCode(),
                    resp.body(),
                    partial,
                    guard,
                    written,
                    warnings,
                    sub,
                    handle::isCancelled);
            warnings.addAll(unresolved.warnings()); // after the >> paths, which may name variables too
            HttpResult result = HttpResult.ofBytes(
                    resp.statusCode(),
                    respHeaders,
                    resp.body(),
                    respType,
                    ms,
                    warnings,
                    partial,
                    written,
                    HttpVersions.label(resp.version()));
            return new HttpExchange(label, method, url, headers, bc.display(), result, details.withFinalUrl(finalUrl));
        } catch (Exception e) {
            warnings.addAll(unresolved.warnings());
            String message = handle.isCancelled()
                    ? CANCELLED
                    : withUrl(HttpErrors.message(e, url, timeout, connect, explicitTimeout), url);
            return new HttpExchange(
                    label, method, url, headers, bc.display(), HttpResult.failure(message, warnings), details);
        }
    }

    private static String megabytes(int bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : bytes + " bytes";
    }

    private static String seconds(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
    }

    /** The URL with each query value replaced by {@code …}: the history label of an {@code @no-log} request. */
    static String withoutQueryValues(String url) {
        int q = url.indexOf('?');
        if (q < 0) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url.substring(0, q + 1));
        String[] pairs = url.substring(q + 1).split("&", -1);
        for (int i = 0; i < pairs.length; i++) {
            if (i > 0) {
                sb.append('&');
            }
            int eq = pairs[i].indexOf('=');
            sb.append(eq < 0 ? pairs[i] : pairs[i].substring(0, eq + 1) + "…");
        }
        return sb.toString();
    }

    /**
     * The request body: its publisher, a display string (for the history/cURL view), and how it went out (a file
     * streamed as is, multipart fields) for the cURL export. A non-null {@code problem} means the body the request
     * refers to could not be read and the request must not be sent.
     */
    private record BodyContent(
            HttpRequest.BodyPublisher publisher,
            String display,
            String problem,
            byte[] bytes,
            Path file,
            List<HttpExchange.FormPart> parts) {

        static BodyContent of(byte[] bytes, String display) {
            return new BodyContent(
                    HttpRequest.BodyPublishers.ofByteArray(bytes), display, null, bytes, null, List.of());
        }

        static BodyContent unreadable(String problem) {
            return new BodyContent(
                    HttpRequest.BodyPublishers.noBody(), "(" + problem + ")", problem, new byte[0], null, List.of());
        }

        /** The bytes a {@code qop=auth-int} Digest answer hashes. */
        byte[] bytesForDigest() {
            if (bytes != null) {
                return bytes;
            }
            try {
                return file == null ? new byte[0] : Files.readAllBytes(file);
            } catch (IOException e) {
                return new byte[0];
            }
        }
    }

    /** Why a body stopped arriving before its end. */
    private enum Stop {
        DEADLINE,
        IDLE
    }

    /** A response read to the end (or to the size cap / a timer): status, headers, decoded bytes, and its path. */
    private record Received(
            int statusCode,
            java.net.http.HttpHeaders headers,
            byte[] body,
            boolean truncated,
            Stop stopped,
            String undecoded,
            HttpClient.Version version,
            URI uri,
            List<URI> redirects) {}

    private static BodyContent resolveBody(
            HttpFile.Parsed request,
            Path baseDir,
            Function<String, String> sub,
            String contentType,
            List<String[]> headers,
            List<String> warnings) {
        // multipart/form-data: assemble the parts (inline parts substituted, < ./file parts slurped).
        if (Multipart.isMultipart(contentType)) {
            String boundary = Multipart.boundaryOf(contentType);
            if (boundary.isEmpty()) {
                boundary = "EditoraBoundary" + Long.toHexString(System.nanoTime());
                setHeader(headers, "Content-Type", contentType + "; boundary=" + boundary);
            }
            List<String> problems = new ArrayList<>();
            List<Multipart.Part> parsed = Multipart.parse(request.body(), boundary);
            byte[] bytes = Multipart.build(parsed, boundary, baseDir, sub, problems);
            if (!problems.isEmpty()) {
                return BodyContent.unreadable(String.join("; ", problems));
            }
            return new BodyContent(
                    HttpRequest.BodyPublishers.ofByteArray(bytes),
                    "(multipart/form-data, " + bytes.length + " bytes)",
                    null,
                    bytes,
                    null,
                    formParts(parsed, baseDir, sub));
        }
        Charset charset = HttpResult.charsetOf(contentType);
        // external body: < ./file (raw) or <@ ./file (substituted), optional encoding.
        if (request.bodyRef() != null && baseDir != null) {
            try {
                HttpFile.BodyRef ref = request.bodyRef();
                Charset cs = ref.encoding() == null ? StandardCharsets.UTF_8 : Charset.forName(ref.encoding());
                Path bodyFile = HttpPaths.contained(baseDir, ref.path());
                if (bodyFile == null) {
                    return BodyContent.unreadable("body file outside the request folder: " + ref.path());
                }
                if (!ref.substitute()) {
                    // "< file": the file's bytes are the body, untouched and streamed — a decode/re-encode round
                    // trip through String corrupts anything that is not valid UTF-8 (an image, a protobuf), and a
                    // multi-gigabyte upload must not be read into memory first.
                    long size = Files.size(bodyFile);
                    String shown = shownFileBody(bodyFile, size, ref.path(), cs);
                    return new BodyContent(
                            HttpRequest.BodyPublishers.ofFile(bodyFile), shown, null, null, bodyFile, List.of());
                }
                String content = sub.apply(new String(Files.readAllBytes(bodyFile), cs));
                return BodyContent.of(encode(content, charset, warnings), content);
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
            return new BodyContent(HttpRequest.BodyPublishers.noBody(), "", null, new byte[0], null, List.of());
        }
        return BodyContent.of(encode(body, charset, warnings), body);
    }

    /** What the history shows for a {@code < file} body: its text when small and textual, else a note. */
    private static String shownFileBody(Path file, long size, String shownPath, Charset cs) throws IOException {
        if (size > SHOWN_FILE_BODY_BYTES) {
            return "(body from " + shownPath + ", " + HttpResponseFormat.humanSize(size) + ")";
        }
        byte[] raw = Files.readAllBytes(file);
        return HttpResult.looksBinary(raw, null)
                ? "(binary body from " + shownPath + ", " + raw.length + " bytes)"
                : new String(raw, cs);
    }

    /**
     * {@code text} in {@code charset} — the one the request's {@code Content-Type} declares, else UTF-8 — with a
     * warning when a character has no form in it (it goes out as {@code ?}).
     */
    static byte[] encode(String text, Charset charset, List<String> warnings) {
        try {
            ByteBuffer b = charset.newEncoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .encode(CharBuffer.wrap(text));
            byte[] out = new byte[b.remaining()];
            b.get(out);
            return out;
        } catch (CharacterCodingException | UnsupportedOperationException e) {
            warnings.add(tr("status.http.warn.unmappable", charset.name()));
            return text.getBytes(charset);
        }
    }

    private static final Pattern DISPOSITION_PARAM =
            Pattern.compile("(?i)(?:^|;)\\s*(name|filename)\\s*=\\s*(\"(?:[^\"\\\\]|\\\\.)*\"|[^;]*)");

    /** The multipart parts as form fields for the cURL export: names, inline values (substituted), files. */
    private static List<HttpExchange.FormPart> formParts(
            List<Multipart.Part> parts, Path baseDir, Function<String, String> sub) {
        List<HttpExchange.FormPart> out = new ArrayList<>();
        for (Multipart.Part p : parts) {
            String name = null;
            String filename = null;
            String type = null;
            for (Map.Entry<String, String> h : p.headers().entrySet()) {
                if (h.getKey().equalsIgnoreCase("Content-Disposition")) {
                    Matcher m = DISPOSITION_PARAM.matcher(sub.apply(h.getValue()));
                    while (m.find()) {
                        String v = m.group(2).strip();
                        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
                            v = v.substring(1, v.length() - 1);
                        }
                        if (m.group(1).equalsIgnoreCase("name")) {
                            name = v;
                        } else {
                            filename = v;
                        }
                    }
                } else if (h.getKey().equalsIgnoreCase("Content-Type")) {
                    type = sub.apply(h.getValue());
                }
            }
            if (p.filePath() != null) {
                Path file = HttpPaths.contained(baseDir, p.filePath());
                out.add(new HttpExchange.FormPart(
                        name,
                        null,
                        file == null ? p.filePath() : file.toAbsolutePath().toString(),
                        filename,
                        type));
            } else {
                out.add(new HttpExchange.FormPart(name, sub.apply(p.inlineBody()), null, null, type));
            }
        }
        return out;
    }

    private static String withUrl(String message, String url) {
        return url.isBlank() ? message : message + "  (" + url + ")";
    }

    /**
     * Builds and sends one request and reads its body as bytes; {@code authOverride} (when non-null) sets the
     * Authorization header. The send is asynchronous so {@code handle} can cancel it; the body is decoded
     * ({@link ContentDecoding}) and streamed so it can be cut at the size cap, and a body still arriving at the
     * deadline — or silent for longer than {@code idle} — is closed and returned as far as it got.
     */
    private Received send(
            Handle handle,
            HttpClient client,
            String url,
            String method,
            List<String[]> headers,
            Duration timeout,
            Duration idle,
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
        List<URI> redirects = new ArrayList<>();
        for (var prev = resp.previousResponse();
                prev.isPresent();
                prev = prev.get().previousResponse()) {
            redirects.add(0, prev.get().uri());
        }
        AtomicReference<Stop> stopped = new AtomicReference<>();
        try (InputStream raw = resp.body()) {
            handle.stream = raw;
            if (handle.isCancelled()) {
                throw new IOException(CANCELLED);
            }
            ContentDecoding.Decoded decoded = ContentDecoding.decode(
                    raw, resp.headers().firstValue("Content-Encoding").orElse(null));
            AtomicLong lastRead = new AtomicLong(System.nanoTime());
            ScheduledFuture<?> watchdog = watch(raw, stopped, lastRead, idle);
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean truncated = readCapped(decoded.stream(), out, maxResponseBytes, handle, stopped, lastRead);
                return new Received(
                        resp.statusCode(),
                        resp.headers(),
                        out.toByteArray(),
                        truncated,
                        stopped.get(),
                        decoded.undecoded(),
                        resp.version(),
                        resp.uri(),
                        redirects);
            } finally {
                watchdog.cancel(false);
            }
        } finally {
            handle.stream = null;
        }
    }

    /**
     * Closes {@code in} — which unblocks a read that would otherwise wait forever — once the body deadline passes,
     * or once nothing has arrived for {@code idle} (when set), recording which in {@code stopped}.
     */
    private ScheduledFuture<?> watch(
            InputStream in, AtomicReference<Stop> stopped, AtomicLong lastRead, Duration idle) {
        long start = System.nanoTime();
        long deadline = bodyDeadline.toNanos();
        long idleNanos = idle == null ? Long.MAX_VALUE : idle.toNanos();
        long period = Math.max(
                10, Math.min(250, Math.min(bodyDeadline.toMillis(), idle == null ? 250 : idle.toMillis()) / 4));
        return timers.scheduleAtFixedRate(
                () -> {
                    long now = System.nanoTime();
                    Stop why = now - start >= deadline
                            ? Stop.DEADLINE
                            : now - lastRead.get() >= idleNanos ? Stop.IDLE : null;
                    if (why != null && stopped.compareAndSet(null, why)) {
                        closeQuietly(in);
                    }
                },
                period,
                period,
                TimeUnit.MILLISECONDS);
    }

    /**
     * Copies {@code in} to {@code out} up to {@code max} bytes; returns true when the stream had more. A read
     * that fails because the user cancelled is rethrown; one that fails because a timer closed the stream ends
     * the copy with what arrived.
     */
    private static boolean readCapped(
            InputStream in,
            ByteArrayOutputStream out,
            int max,
            Handle handle,
            AtomicReference<Stop> stopped,
            AtomicLong lastRead)
            throws IOException {
        byte[] buf = new byte[16 * 1024];
        try {
            int n;
            while ((n = in.read(buf)) >= 0) {
                lastRead.set(System.nanoTime());
                int room = max - out.size();
                if (n > room) {
                    out.write(buf, 0, room);
                    return true;
                }
                out.write(buf, 0, n);
            }
        } catch (IOException e) {
            if (handle.isCancelled() || stopped.get() == null) {
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

    /** Warnings about where redirects led. Pure apart from the message catalog. */
    static final class RedirectNotes {

        /** Request headers that are not specific to one site, so their following a redirect is unremarkable. */
        private static final Set<String> GENERIC = Set.of(
                "accept",
                "accept-language",
                "accept-encoding",
                "cache-control",
                "content-type",
                "user-agent",
                "pragma",
                "if-none-match",
                "if-modified-since",
                "origin",
                "referer",
                // stripped by the JDK client on a cross-host redirect
                "authorization",
                "cookie",
                "proxy-authorization");

        private RedirectNotes() {}

        private static List<String> warnings(String url, Received resp, List<String[]> headers, boolean follow) {
            return warnings(url, resp.uri(), resp.redirects(), resp.statusCode(), resp.headers(), headers, follow);
        }

        static List<String> warnings(
                String url,
                URI finalUri,
                List<URI> redirects,
                int status,
                java.net.http.HttpHeaders responseHeaders,
                List<String[]> requestHeaders,
                boolean follow) {
            List<String> out = new ArrayList<>();
            URI start;
            try {
                start = URI.create(url);
            } catch (RuntimeException e) {
                return out;
            }
            if (!redirects.isEmpty() && !sameOrigin(start, finalUri)) {
                List<String> carried = new ArrayList<>();
                for (String[] h : requestHeaders) {
                    if (!GENERIC.contains(h[0].toLowerCase(Locale.ROOT)) && !carried.contains(h[0])) {
                        carried.add(h[0]);
                    }
                }
                out.add(
                        carried.isEmpty()
                                ? tr("status.http.warn.redirectedHost", authority(finalUri))
                                : tr(
                                        "status.http.warn.redirectedHostHeaders",
                                        authority(finalUri),
                                        String.join(", ", carried)));
            }
            String location = responseHeaders.firstValue("Location").orElse(null);
            if (follow && status >= 300 && status < 400 && location != null) {
                // The client makes at most REDIRECT_LIMIT exchanges, so it follows REDIRECT_LIMIT - 1 redirects and
                // hands back the next 3xx: the chain counts the followed ones, plus this one.
                if (redirects.size() >= REDIRECT_LIMIT - 1) {
                    out.add(tr("status.http.warn.redirectLimit", redirects.size() + 1));
                } else if ("https".equalsIgnoreCase(finalUri.getScheme())
                        && location.regionMatches(true, 0, "http:", 0, 5)) {
                    out.add(tr("status.http.warn.redirectDowngrade", location));
                }
            }
            return out;
        }

        private static boolean sameOrigin(URI a, URI b) {
            return a.getScheme() != null
                    && a.getScheme().equalsIgnoreCase(b.getScheme())
                    && a.getHost() != null
                    && a.getHost().equalsIgnoreCase(b.getHost())
                    && port(a) == port(b);
        }

        private static int port(URI u) {
            return u.getPort() >= 0 ? u.getPort() : "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
        }

        private static String authority(URI u) {
            return u.getHost() + (u.getPort() >= 0 ? ":" + u.getPort() : "");
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

    /** Stops the daemon worker (window close). Without this, each closed window leaks its http-client thread. */
    public void shutdown() {
        active.forEach(Handle::cancel);
        exec.shutdownNow();
        timers.shutdownNow();
    }
}
