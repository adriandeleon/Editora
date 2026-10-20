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
import java.util.function.IntConsumer;

import javafx.application.Platform;

import org.jspecify.annotations.Nullable;

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
 * <p>Secrets: a request that reads the process environment or {@code .env} ({@link DynamicVars#isSecretSource}) asks
 * the run's {@link SecretGate} first, on the worker and before anything is sent. What it asks about comes from a
 * dry resolution of the request by the same code as the send ({@link SecretApproval#preview}), so no form or indirection the
 * resolver accepts can reach a server unasked; each send then refuses a read the gate did not approve.
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

    /** The JDK client's limit on the exchanges of one request (the original one plus the redirects followed). */
    static final int REDIRECT_LIMIT = Integer.getInteger("jdk.httpclient.redirects.retrylimit", 5);

    /**
     * The {@link HttpResult#error()} of a request the user cancelled: a marker compared as is (and saved so in the
     * history), shown translated by {@link HttpResult#shownError()}.
     */
    public static final String CANCELLED = "request cancelled";

    static {
        allowHostHeader();
    }

    /**
     * Lets a request set its own {@code Host} header (testing a virtual host by IP). The JDK reads this property
     * once, when its HTTP classes first load, so {@code App.main} sets it at startup; this is the fallback for
     * other entry points and only works if no HTTP client was used before.
     *
     * <p>The property applies to every {@code java.net.http} client in the process, not only this one. That is
     * acceptable because no other caller takes a header name from user content or sets {@code Host};
     * {@code RestrictedHostHeaderTest} fails if one starts to.
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
        private volatile @Nullable CompletableFuture<?> pending;
        private volatile @Nullable InputStream stream;
        /** The request body of the exchange in flight, whose files a cancel closes (they stay open otherwise). */
        private volatile RequestBodies.@Nullable Content body;

        public void cancel() {
            if (cancelled.compareAndSet(false, true)) {
                CompletableFuture<?> f = pending;
                if (f != null) {
                    f.cancel(true);
                }
                closeQuietly(stream);
                RequestBodies.Content b = body;
                if (b != null) {
                    b.release();
                }
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

    /**
     * Runs a single request off-thread (its own cookie jar), resolving {@code {{name.response…}}} against
     * {@code captured} — the buffer's session, a fresh one when null — and recording this request's result there
     * under its name. {@code gate} is asked on the worker, before anything is sent, when the request reads a secret
     * ({@link SecretGate#UNCHECKED} asks nothing); refused, the request is reported unsent. The result posts on the FX
     * thread.
     */
    public Handle run(
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            SecretGate gate,
            Consumer<HttpExchange> onResult) {
        return run(request, RunEnvironment.of(vars), baseDir, captured, gate, onResult);
    }

    /**
     * As {@link #run(HttpFile.Parsed, Map, Path, CapturedResponses, SecretGate, Consumer)}, with the environment read
     * on the worker as the run starts ({@code environment}'s problems put first among the request's warnings).
     */
    public Handle run(
            HttpFile.Parsed parsed,
            RunEnvironment environment,
            Path baseDir,
            CapturedResponses captured,
            SecretGate gate,
            Consumer<HttpExchange> onResult) {
        return run(parsed, environment, baseDir, captured, gate, ResponseScripts.OFF, onResult);
    }

    /**
     * As {@link #run(HttpFile.Parsed, RunEnvironment, Path, CapturedResponses, SecretGate, Consumer)}, running the
     * request's {@code @assert}/{@code @capture} checks and its response handlers through {@code scripts} once the
     * response has arrived (see {@link #afterResponse}).
     */
    public Handle run(
            HttpFile.Parsed parsed,
            RunEnvironment environment,
            Path baseDir,
            CapturedResponses captured,
            SecretGate gate,
            ResponseScripts scripts,
            Consumer<HttpExchange> onResult) {
        ResponseScripts handlers = scripts == null ? ResponseScripts.OFF : scripts;
        Handle handle = new Handle();
        active.add(handle);
        CapturedResponses responses = captured == null ? new CapturedResponses() : captured;
        exec.submit(() -> {
            HttpExchange ex;
            try (Session session = new Session(new CookieManager())) {
                RunEnvironment.Snapshot env = read(environment);
                HttpFile.Parsed request = parsed.withWarningsFirst(env.problems());
                Map<String, String> vars = env.variables();
                SecretApproval.Grant approval =
                        SecretApproval.ask(gate, handle::isCancelled, List.of(request), vars, baseDir, responses);
                beforeRun(handlers, List.of(request), handle);
                ex = sendOne(handle, request, vars, baseDir, responses, session, approval, handlers);
                responses.put(request.name(), ex.result());
            } finally {
                active.remove(handle);
            }
            handle.deliver(() -> onResult.accept(ex));
        });
        return handle;
    }

    /**
     * Runs {@code requests} in order off-thread, sharing one cookie jar and the captured-response session
     * {@code responses} (the buffer's; a fresh one when null), so later requests can reference earlier responses.
     * {@code gate} is asked once, before the first request is sent, about every secret the requests read; refused,
     * the requests that read one are reported unsent and the others still run. {@code onStart} (on the FX thread,
     * may be null) is told each request's index as it starts ("2 of 5"); all exchanges post together at the end.
     */
    public Handle runAll(
            List<HttpFile.Parsed> requests,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses responses,
            SecretGate gate,
            IntConsumer onStart,
            Consumer<List<HttpExchange>> onResult) {
        return runAll(requests, RunEnvironment.of(vars), baseDir, responses, gate, onStart, onResult);
    }

    /**
     * As {@link #runAll(List, Map, Path, CapturedResponses, SecretGate, IntConsumer, Consumer)}, with the environment
     * read on the worker as the run starts ({@code environment}'s problems put first among each request's warnings).
     */
    public Handle runAll(
            List<HttpFile.Parsed> parsed,
            RunEnvironment environment,
            Path baseDir,
            CapturedResponses responses,
            SecretGate gate,
            IntConsumer onStart,
            Consumer<List<HttpExchange>> onResult) {
        return runAll(parsed, environment, baseDir, responses, gate, ResponseScripts.OFF, onStart, onResult);
    }

    /**
     * As {@link #runAll(List, RunEnvironment, Path, CapturedResponses, SecretGate, IntConsumer, Consumer)}, running
     * each request's checks and response handlers through {@code scripts} before the next request starts, so what a
     * handler stores is what the next request sends.
     */
    public Handle runAll(
            List<HttpFile.Parsed> parsed,
            RunEnvironment environment,
            Path baseDir,
            CapturedResponses responses,
            SecretGate gate,
            ResponseScripts scripts,
            IntConsumer onStart,
            Consumer<List<HttpExchange>> onResult) {
        ResponseScripts handlers = scripts == null ? ResponseScripts.OFF : scripts;
        Handle handle = new Handle();
        active.add(handle);
        CapturedResponses captured = responses == null ? new CapturedResponses() : responses;
        exec.submit(() -> {
            List<HttpExchange> out = new ArrayList<>();
            try (Session session = new Session(new CookieManager())) {
                RunEnvironment.Snapshot env = read(environment);
                List<HttpFile.Parsed> requests = parsed.stream()
                        .map(r -> r.withWarningsFirst(env.problems()))
                        .toList();
                Map<String, String> vars = env.variables();
                SecretApproval.Grant approval =
                        SecretApproval.ask(gate, handle::isCancelled, requests, vars, baseDir, captured);
                beforeRun(handlers, requests, handle);
                for (HttpFile.Parsed req : requests) {
                    if (onStart != null) {
                        int index = out.size();
                        Platform.runLater(() -> onStart.accept(index));
                    }
                    HttpExchange ex = sendOne(handle, req, vars, baseDir, captured, session, approval, handlers);
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
     * One request of a run, start to end: its pre-request scripts ({@link #preRequest}), which may keep it unsent; then
     * the send, with the {@code request.variables} they set and the session's global headers; then its checks and
     * response handlers ({@link #afterResponse}). The pre-request scripts' tests and log come first in the result.
     */
    private HttpExchange sendOne(
            Handle handle,
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            Session session,
            SecretApproval.@Nullable Grant approval,
            ResponseScripts scripts) {
        ResponseScripts.PreResult pre = preRequest(handle, request, vars, baseDir, captured, scripts);
        if (pre.notSent() != null) {
            HttpExchange unsent;
            try {
                // From the dry resolution, every secret source as written: a request that was not sent keeps none.
                unsent = SecretApproval.unsentReport(request, vars, baseDir, captured, pre.notSent(), List.of());
            } catch (RuntimeException | OutOfMemoryError failed) {
                unsent = new HttpExchange(
                        request.method() + " " + request.url(),
                        request.method(),
                        request.url(),
                        request.headers(),
                        "",
                        HttpResult.failure(pre.notSent(), List.of()),
                        null);
            }
            return unsent.withResult(unsent.result().withTests(pre.tests(), pre.warnings()));
        }
        HttpFile.Parsed ready =
                request.withScriptVariables(pre.variables()).withGlobalHeaders(captured.globalHeaders());
        HttpExchange ex = executeSafely(handle, ready, vars, baseDir, captured, session, approval);
        ex = afterResponse(handle, ready, ex, vars, captured, scripts);
        if (pre.tests().isEmpty() && pre.warnings().isEmpty()) {
            return ex;
        }
        HttpResult r = ex.result();
        List<String> warnings = new ArrayList<>(pre.warnings());
        warnings.addAll(r.warnings() == null ? List.of() : r.warnings());
        return ex.withResult(new HttpResult(
                r.status(),
                r.headers(),
                r.body(),
                r.contentType(),
                r.elapsedMs(),
                r.sizeBytes(),
                r.error(),
                warnings,
                r.rawBody(),
                r.truncated(),
                r.written(),
                r.protocol(),
                pre.tests().plus(r.tests())));
    }

    /**
     * Runs {@code request}'s pre-request scripts through {@code scripts}, with the request as far as it resolves
     * before them (every secret source kept as written), and applies their session changes to {@code captured}.
     */
    private static ResponseScripts.PreResult preRequest(
            Handle handle,
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            ResponseScripts scripts) {
        if (request.preScripts().isEmpty()) {
            return ResponseScripts.PreResult.NONE;
        }
        try {
            String url = request.url();
            List<String[]> headers = request.headers();
            String body = request.body();
            try {
                PreparedRequest p = PreparedRequest.dry(
                                request.withGlobalHeaders(captured.globalHeaders()), vars, baseDir, captured)
                        .prepared();
                url = p.targetUrl();
                headers = p.headers();
                body = p.body().display();
            } catch (RuntimeException unresolvable) {
                // the script sees the request as written
            }
            ResponseScripts.PreResult r = scripts.runPre(
                    request,
                    new ResponseScripts.PreInput(
                            vars,
                            captured.globals(),
                            captured.globalHeaders(),
                            url,
                            headers,
                            body,
                            handle::isCancelled));
            r.changes().forEach(c -> c.applyTo(captured));
            return r;
        } catch (RuntimeException | OutOfMemoryError failed) {
            return new ResponseScripts.PreResult(
                    tr("status.http.preScriptNotRun", String.valueOf(failed)),
                    Map.of(),
                    HttpTests.NONE,
                    List.of(),
                    List.of());
        }
    }

    /** Lets {@code scripts} ask its question once, before the run's first request; a failure asks nothing. */
    private static void beforeRun(ResponseScripts scripts, List<HttpFile.Parsed> requests, Handle handle) {
        try {
            scripts.beforeRun(requests, handle::isCancelled);
        } catch (RuntimeException ignored) {
            // the handlers then report what they report on their own
        }
    }

    /**
     * The exchange with what its request's checks found: the {@code @assert} lines and {@code @capture} lines
     * ({@link HttpChecks}, which also store their values in {@code captured}), then the response handlers run by
     * {@code scripts} (whose {@code client.global} changes are applied to {@code captured} too). A request that got no
     * response — not sent, failed, cancelled — runs none of them. Never throws: whatever goes wrong becomes a warning.
     */
    static HttpExchange afterResponse(
            Handle handle,
            HttpFile.Parsed request,
            HttpExchange ex,
            Map<String, String> vars,
            CapturedResponses captured,
            ResponseScripts scripts) {
        if (ex == null || ex.result() == null || ex.result().failed() || handle.isCancelled()) {
            return ex;
        }
        if (request.checks().isEmpty() && request.handlers().isEmpty()) {
            return ex;
        }
        List<String> warnings = new ArrayList<>();
        HttpTests tests = HttpTests.NONE;
        try {
            HttpChecks.Outcome checks = HttpChecks.evaluate(request.checks(), ex.result());
            checks.captures().forEach(captured::setGlobal);
            warnings.addAll(checks.warnings());
            tests = new HttpTests(checks.tests(), List.of(), null);
            if (!request.handlers().isEmpty()) {
                ResponseScripts.Result r = scripts.run(
                        request, ex, new ResponseScripts.Input(vars, captured.globals(), handle::isCancelled));
                r.changes().forEach(c -> c.applyTo(captured));
                tests = tests.plus(r.tests());
                warnings.addAll(r.warnings());
            }
        } catch (RuntimeException | OutOfMemoryError failed) {
            warnings.add(tr("http.warn.handlerFailed", String.valueOf(failed)));
        }
        return ex.withResult(ex.result().withTests(tests, warnings));
    }

    /** {@code environment} read now; one that fails to read gives no variables (the run still reports each one). */
    private static RunEnvironment.Snapshot read(RunEnvironment environment) {
        try {
            RunEnvironment.Snapshot s = environment == null ? null : environment.read();
            return s == null ? new RunEnvironment.Snapshot(Map.of(), List.of()) : s;
        } catch (RuntimeException unreadable) {
            return new RunEnvironment.Snapshot(Map.of(), List.of());
        }
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
            Session session,
            SecretApproval.@Nullable Grant approval) {
        HttpVars.Unresolved unresolved = new HttpVars.Unresolved();
        try {
            return execute(handle, request, vars, baseDir, captured, session, approval, unresolved);
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
            // What was known before it broke — a parse warning, a variable that did not resolve — still helps.
            List<String> warnings = new ArrayList<>(request == null ? List.of() : request.warnings());
            warnings.addAll(unresolved.warnings());
            return new HttpExchange(
                    method + " " + url, method, url, List.of(), "", HttpResult.failure(message, warnings), null);
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
            Session session,
            SecretApproval.@Nullable Grant approval,
            HttpVars.Unresolved unresolved) {
        // The clock is a real instant in the user's zone: $timestamp/$isoTimestamp are UTC whatever the zone.
        ZonedDateTime now = ZonedDateTime.now();
        // The variables this request sees: the environment's, then the @var lines above it (in order), resolved
        // now — so they may use an earlier response or $dotenv beside the file. What cannot be resolved is
        // collected (in the caller's `unresolved`, so a failure still reports it) and never sent silently as "".
        HttpVars.Scope scope = HttpVars.scope(vars, request.variables(), now, captured, baseDir);
        Function<String, String> sub = s -> HttpVars.substitute(s, scope, now, captured, baseDir, unresolved);
        PreparedRequest p = PreparedRequest.of(request, baseDir, sub);
        String method = p.method();
        String url = p.url();
        List<String[]> headers = p.headers();
        List<String> warnings = p.warnings();
        String requestedVersion = request.httpVersion();
        HttpVersions.Choice version = p.version();
        RequestBodies.Content bc = p.body();
        DigestAuth.Credentials digest = p.digest();
        String label = p.label();
        HttpExchange.Details details = p.details();
        // May this send happen? The one check, before anything is sent or reported: a GraphQL/WebSocket/gRPC request
        // (plain HTTP would only produce a confusing error), a body that cannot be read (sending without it would
        // look like a server problem, or create an empty resource), a secret the gate did not approve, or a host the
        // prompt did not name.
        List<String> notSentWarnings =
                request.error() == null && bc.problem() != null ? List.of(bc.problem()) : List.of();
        String whyNot = request.error() != null
                ? request.error()
                : bc.problem() != null
                        ? tr("http.request.notSent", bc.problem())
                        : SecretApproval.refusalFor(approval, unresolved, p.targetUrl());
        if (whyNot != null) {
            if (!unresolved.secretReads().isEmpty()) {
                // Nothing goes out, so nothing it read is shown or kept: it is reported from the dry resolution, with
                // every secret as written — in the panel, the status bar, History, the history file and Copy as cURL.
                return SecretApproval.unsentReport(request, vars, baseDir, captured, whyNot, notSentWarnings);
            }
            warnings.addAll(unresolved.warnings());
            warnings.addAll(notSentWarnings);
            HttpResult notSent = HttpResult.failure(whyNot, warnings);
            return new HttpExchange(label, method, url, headers, bc.display(), notSent, details);
        }
        details = details.withSecretValues(unresolved.secretValues());
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
        handle.body = bc;
        if (handle.isCancelled()) {
            bc.release(); // cancel() ran before the body was registered
        }
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
                            digest, method, requestTarget(url), challenge, randomHex(), "00000001", bc.digestFeed());
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
            if (resp.overLayered() != null) {
                warnings.add(tr("status.http.warn.tooManyEncodings", ContentDecoding.MAX_LAYERS, resp.overLayered()));
            }
            if (resp.truncated()) {
                warnings.add(tr("http.warn.truncated", megabytes(maxResponseBytes)));
            }
            if (resp.stopped() == Stop.DEADLINE) {
                warnings.add(tr(
                        "http.warn.deadline",
                        String.valueOf(bodyDeadline.toSeconds()),
                        String.valueOf(resp.body().length)));
            } else if (resp.stopped() == Stop.IDLE) {
                warnings.add(tr("status.http.warn.idle", seconds(timeout), resp.body().length));
            }
            String finalUrl = resp.redirects().isEmpty() ? null : resp.uri().toString();
            warnings.addAll(RedirectNotes.warnings(
                    url,
                    resp.uri(),
                    resp.redirects(),
                    resp.statusCode(),
                    resp.headers(),
                    headers,
                    !request.directives().noRedirect()));
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
            return new HttpExchange(
                    label,
                    method,
                    url,
                    headers,
                    bc.display(),
                    result,
                    details.withFinalUrl(finalUrl).withSecretValues(unresolved.secretValues()));
        } catch (Exception e) {
            warnings.addAll(unresolved.warnings());
            String message = handle.isCancelled()
                    ? CANCELLED
                    : withUrl(HttpErrors.message(e, url, timeout, connect, explicitTimeout), url);
            return new HttpExchange(
                    label, method, url, headers, bc.display(), HttpResult.failure(message, warnings), details);
        } finally {
            handle.body = null;
            bc.release(); // the upload's files, whether it finished, failed or was cancelled
        }
    }

    private static String megabytes(int bytes) {
        return bytes >= 1024 * 1024 ? (bytes / (1024 * 1024)) + " MB" : bytes + " bytes";
    }

    private static String seconds(Duration d) {
        long ms = d.toMillis();
        return ms % 1000 == 0 ? (ms / 1000) + " s" : ms + " ms";
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
            @Nullable Stop stopped,
            @Nullable String undecoded,
            @Nullable String overLayered,
            HttpClient.Version version,
            URI uri,
            List<URI> redirects) {}

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
            @Nullable Duration idle,
            HttpRequest.BodyPublisher publisher,
            @Nullable String authOverride)
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
        AtomicReference<@Nullable Stop> stopped = new AtomicReference<>();
        try (InputStream raw = resp.body()) {
            handle.stream = raw;
            if (handle.isCancelled()) {
                throw new IOException(CANCELLED);
            }
            ContentDecoding.Decoded decoded = ContentDecoding.decode(
                    raw, resp.headers().firstValue("Content-Encoding").orElse(null));
            AtomicLong lastRead = new AtomicLong(System.nanoTime());
            ScheduledFuture<?> watchdog = watch(raw, stopped, lastRead, idle);
            try (InputStream body = decoded.stream()) { // closing it ends the decoders' inflaters
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                boolean truncated = readCapped(body, out, maxResponseBytes, handle, stopped, lastRead);
                return new Received(
                        resp.statusCode(),
                        resp.headers(),
                        out.toByteArray(),
                        truncated,
                        stopped.get(),
                        decoded.tooManyLayers() ? null : decoded.undecoded(),
                        decoded.tooManyLayers() ? decoded.undecoded() : null,
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
            InputStream in, AtomicReference<@Nullable Stop> stopped, AtomicLong lastRead, @Nullable Duration idle) {
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
            AtomicReference<@Nullable Stop> stopped,
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

    private static void closeQuietly(@Nullable InputStream in) {
        if (in != null) {
            try {
                in.close();
            } catch (IOException | RuntimeException ignore) {
                // already closed / torn down
            }
        }
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

    /** Stops the daemon worker (window close). Without this, each closed window leaks its http-client thread. */
    public void shutdown() {
        active.forEach(Handle::cancel);
        exec.shutdownNow();
        timers.shutdownNow();
    }
}
