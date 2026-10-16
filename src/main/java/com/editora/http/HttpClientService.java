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
 * <p>Secrets: a request that reads the process environment or {@code .env} ({@link DynamicVars#isSecretSource}) asks
 * the run's {@link SecretGate} first, on the worker and before anything is sent. What it asks about comes from a
 * dry resolution of the request by the same code as the send ({@link #preview}), so no form or indirection the
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
    private static final int REDIRECT_LIMIT = Integer.getInteger("jdk.httpclient.redirects.retrylimit", 5);

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
        private volatile CompletableFuture<?> pending;
        private volatile InputStream stream;
        /** The request body of the exchange in flight, whose files a cancel closes (they stay open otherwise). */
        private volatile RequestBodies.Content body;

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
     * The trust gate for secrets: asked, on the run's worker and before anything is sent, whether the run may read
     * {@code reads} (the secret sources its requests resolve, as written: {@code $env TOKEN}, {@code $dotenv KEY})
     * and send what they resolve to {@code hosts}. It may block (a dialog on the FX thread). Returns null to allow,
     * else the message the requests that read secrets are reported with, unsent.
     */
    @FunctionalInterface
    public interface SecretGate {
        /** Reads anything without asking (tests, tools; the editor always passes its own gate). */
        SecretGate UNCHECKED = (reads, hosts) -> null;

        String check(List<String> reads, List<String> hosts);

        /**
         * As {@link #check(List, List)}, for a run that may be cancelled while the question waits: a gate that asks the
         * user (a dialog on the FX thread) checks {@code cancelled} again just before asking, so a run cancelled or
         * replaced meanwhile never puts up a prompt. The default asks unless already cancelled.
         */
        default String check(List<String> reads, List<String> hosts, java.util.function.BooleanSupplier cancelled) {
            return cancelled.getAsBoolean() ? CANCELLED : check(reads, hosts);
        }
    }

    /**
     * What a request would read and where it would go, from a dry resolution that runs the same code as a send
     * ({@link #prepare}) but keeps every secret source as written: the {@code reads} (see
     * {@link HttpVars.Unresolved#secretReads()}) and the {@code hosts} ({@code host:port}) it would contact.
     * {@code hostFromResponse}: the host comes (directly or through an {@code @var} line) from an earlier response, so
     * it is only known once that response is in; {@code hostUnfixed}: it may differ at the send (it also uses a secret
     * or a {@code $…} built-in). {@code sendable}: false for a request that is never sent (GraphQL/WebSocket/gRPC, an
     * unreadable body file), which therefore asks nothing.
     */
    public record Preview(
            List<String> reads, List<String> hosts, boolean hostFromResponse, boolean hostUnfixed, boolean sendable) {

        public Preview(List<String> reads, List<String> hosts) {
            this(reads, hosts, false, false, true);
        }
    }

    /**
     * The dry resolution of {@code request}: URL, headers, inline and {@code <@} bodies, multipart parts (their
     * {@code <@} files included), {@code >>} targets, the {@code @var} lines above it and the environment values they
     * use — every secret source read on the way, and the host it would be sent to. No secret value is read into the
     * result. Reads the request's body files, so call it off the FX thread.
     */
    public static Preview preview(
            HttpFile.Parsed request, Map<String, String> vars, Path baseDir, CapturedResponses captured) {
        ZonedDateTime now = ZonedDateTime.now();
        HttpVars.Unresolved sink = HttpVars.Unresolved.dry();
        HttpVars.Scope scope = HttpVars.scope(vars, request.variables(), now, captured, baseDir, true);
        Prepared p = prepare(request, baseDir, s -> HttpVars.substitute(s, scope, now, captured, baseDir, sink));
        // What the host is made of: the request line up to its path, and a Host header (origin form).
        HttpVars.Unresolved hostSink = HttpVars.Unresolved.dry();
        HttpVars.substitute(hostPart(request.url()), scope, now, captured, baseDir, hostSink);
        for (String[] h : request.headers()) {
            if (h[0] != null && h[0].strip().equalsIgnoreCase("Host")) {
                HttpVars.substitute(h[1], scope, now, captured, baseDir, hostSink);
            }
        }
        boolean fromResponse = !hostSink.responseReads().isEmpty();
        boolean unfixed = fromResponse
                || !hostSink.secretReads().isEmpty()
                || !hostSink.dynamicReads().isEmpty();
        boolean sendable = request.error() == null && p.body().problem() == null;
        return new Preview(sink.secretReads(), List.of(hostOf(p.targetUrl())), fromResponse, unfixed, sendable);
    }

    /**
     * The report of a request refused for its secrets: what it would have sent, from the dry resolution — every
     * secret source as written — so neither the response view nor the saved history holds a value that was refused.
     */
    private static HttpExchange notSent(
            HttpFile.Parsed request,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured,
            String why,
            List<String> extraWarnings) {
        ZonedDateTime now = ZonedDateTime.now();
        HttpVars.Unresolved sink = HttpVars.Unresolved.dry();
        HttpVars.Scope scope = HttpVars.scope(vars, request.variables(), now, captured, baseDir, true);
        Prepared p = prepare(request, baseDir, s -> HttpVars.substitute(s, scope, now, captured, baseDir, sink));
        List<String> warnings = new ArrayList<>(p.warnings());
        warnings.addAll(sink.warnings());
        warnings.addAll(extraWarnings);
        String url = p.targetUrl(); // not percent-encoded: the {{references}} read as written
        String label = p.method() + " " + (request.directives().noLog() ? withoutQueryValues(url) : url);
        return new HttpExchange(
                label,
                p.method(),
                url,
                p.headers(),
                p.body().display(),
                HttpResult.failure(why, warnings),
                p.details());
    }

    /**
     * The part of a request-line template that can decide its host: up to the first {@code /}, {@code ?} or
     * {@code #} after the scheme, a {@code {{reference}}} counted whole (it may hold a scheme, a host or a userinfo).
     * {@code GET {{base}}/x} gives {@code {{base}}}; {@code GET https://h.test/{{id}}} gives {@code https://h.test}.
     */
    static String hostPart(String template) {
        String u = template == null ? "" : template.strip();
        int scheme = u.indexOf("://");
        int i = scheme < 0 ? 0 : scheme + 3;
        while (i < u.length()) {
            if (u.startsWith("{{", i)) {
                int close = u.indexOf("}}", i + 2);
                if (close < 0) {
                    break;
                }
                i = close + 2;
                continue;
            }
            if ("/?#".indexOf(u.charAt(i)) >= 0) {
                break;
            }
            i++;
        }
        return u.substring(0, i);
    }

    /** {@code host[:port]} of {@code url}, read leniently (a {@code {{reference}}} kept in a dry host stays as is). */
    static String hostOf(String url) {
        String u = url == null ? "" : url.strip();
        int scheme = u.indexOf("://");
        if (scheme < 0) {
            return u;
        }
        int start = scheme + 3;
        int end = start;
        while (end < u.length() && "/?#".indexOf(u.charAt(end)) < 0) {
            end++;
        }
        String authority = u.substring(start, end);
        int at = authority.lastIndexOf('@');
        return at < 0 ? authority : authority.substring(at + 1);
    }

    /**
     * What a run may read and where it may send it: null for an unchecked run; else the approved {@code reads} and the
     * {@code hosts} the prompt named ({@code anyHost} when it said a host is only known at the send — chosen by an
     * earlier response, or made of a secret or a {@code $…} value), or nothing with the gate's {@code refusal}.
     */
    private record Approval(java.util.Set<String> reads, java.util.Set<String> hosts, boolean anyHost, String refusal) {

        static Approval refused(String refusal) {
            return new Approval(java.util.Set.of(), java.util.Set.of(), false, refusal);
        }
    }

    /**
     * Asks {@code gate} once about every secret the {@code requests} would read; nothing is asked when none do, for a
     * request that is never sent, or once {@code handle} is cancelled (a run cancelled or replaced before its prompt).
     */
    private static Approval approve(
            Handle handle,
            SecretGate gate,
            List<HttpFile.Parsed> requests,
            Map<String, String> vars,
            Path baseDir,
            CapturedResponses captured) {
        if (gate == null || gate == SecretGate.UNCHECKED) {
            return null;
        }
        java.util.Set<String> reads = new java.util.LinkedHashSet<>();
        java.util.Set<String> hosts = new java.util.LinkedHashSet<>();
        boolean fromResponse = false;
        boolean anyHost = false;
        for (HttpFile.Parsed request : requests) {
            try {
                Preview p = preview(request, vars, baseDir, captured);
                if (p.sendable() && !p.reads().isEmpty()) {
                    reads.addAll(p.reads());
                    if (!p.hostFromResponse()) {
                        hosts.addAll(p.hosts());
                    }
                    fromResponse |= p.hostFromResponse();
                    anyHost |= p.hostUnfixed();
                }
            } catch (Throwable unreadable) {
                // nothing is approved for it: if its send does read a secret, the send is refused
            }
        }
        if (reads.isEmpty()) {
            return new Approval(java.util.Set.of(), java.util.Set.of(), false, null);
        }
        if (handle.isCancelled()) {
            return Approval.refused(CANCELLED);
        }
        List<String> named = new ArrayList<>(hosts);
        if (fromResponse) {
            named.add(tr("dialog.httpTrust.dynamicHost")); // never a stale host from an earlier run's response
        }
        String refusal;
        try {
            refusal = gate.check(List.copyOf(reads), List.copyOf(named), handle::isCancelled);
        } catch (RuntimeException failure) {
            refusal = tr("status.http.secretsRefused", String.join(", ", reads));
        }
        return refusal == null ? new Approval(reads, hosts, anyHost, null) : Approval.refused(refusal);
    }

    /**
     * Why a send that read {@code unresolved}'s secrets may not go out under {@code approval}, or null: a read the
     * gate did not approve, or a host the prompt did not name.
     */
    private static String refusal(Approval approval, HttpVars.Unresolved unresolved, String targetUrl) {
        if (approval == null || unresolved.secretReads().isEmpty()) {
            return null;
        }
        if (approval.refusal() != null) {
            return approval.refusal();
        }
        if (!approval.reads().containsAll(unresolved.secretReads())) {
            List<String> unapproved = new ArrayList<>(unresolved.secretReads());
            unapproved.removeAll(approval.reads());
            return tr("status.http.secretsUnapproved", String.join(", ", unapproved));
        }
        String host = hostOf(targetUrl);
        if (!approval.anyHost() && !approval.hosts().contains(host)) {
            return tr("status.http.secretsHostUnapproved", String.join(", ", unresolved.secretReads()), host);
        }
        return null;
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
        Handle handle = new Handle();
        active.add(handle);
        CapturedResponses responses = captured == null ? new CapturedResponses() : captured;
        exec.submit(() -> {
            HttpExchange ex;
            try (Session session = new Session(new CookieManager())) {
                Approval approval = approve(handle, gate, List.of(request), vars, baseDir, responses);
                ex = executeSafely(handle, request, vars, baseDir, responses, session, approval);
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
            java.util.function.IntConsumer onStart,
            Consumer<List<HttpExchange>> onResult) {
        Handle handle = new Handle();
        active.add(handle);
        CapturedResponses captured = responses == null ? new CapturedResponses() : responses;
        exec.submit(() -> {
            List<HttpExchange> out = new ArrayList<>();
            try (Session session = new Session(new CookieManager())) {
                Approval approval = approve(handle, gate, requests, vars, baseDir, captured);
                for (HttpFile.Parsed req : requests) {
                    if (onStart != null) {
                        int index = out.size();
                        Platform.runLater(() -> onStart.accept(index));
                    }
                    HttpExchange ex = executeSafely(handle, req, vars, baseDir, captured, session, approval);
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
            Session session,
            Approval approval) {
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
            Approval approval,
            HttpVars.Unresolved unresolved) {
        // The clock is a real instant in the user's zone: $timestamp/$isoTimestamp are UTC whatever the zone.
        ZonedDateTime now = ZonedDateTime.now();
        // The variables this request sees: the environment's, then the @var lines above it (in order), resolved
        // now — so they may use an earlier response or $dotenv beside the file. What cannot be resolved is
        // collected (in the caller's `unresolved`, so a failure still reports it) and never sent silently as "".
        HttpVars.Scope scope = HttpVars.scope(vars, request.variables(), now, captured, baseDir);
        Function<String, String> sub = s -> HttpVars.substitute(s, scope, now, captured, baseDir, unresolved);
        Prepared p = prepare(request, baseDir, sub);
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
                        : refusal(approval, unresolved, p.targetUrl());
        if (whyNot != null) {
            if (!unresolved.secretReads().isEmpty()) {
                // Nothing goes out, so nothing it read is shown or kept: it is reported from the dry resolution, with
                // every secret as written — in the panel, the status bar, History, the history file and Copy as cURL.
                return notSent(request, vars, baseDir, captured, whyNot, notSentWarnings);
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

    /**
     * What a request resolves to before anything is sent: method, URL, headers, body, Digest credentials, the label
     * and the details — every {@code {{…}}} of it substituted through {@code sub}, the {@code >>} targets included,
     * so whatever the run reads (the secrets the trust gate asks about) is known before the first byte goes out.
     * {@link #preview} runs exactly this with a dry {@code sub}.
     */
    private static Prepared prepare(HttpFile.Parsed request, Path baseDir, Function<String, String> sub) {
        String method = request.method().isEmpty() ? "GET" : request.method();

        // The URL first, then the headers: the unresolved-variable warnings follow the order of the file.
        String requestUrl = sub.apply(request.url()).trim();
        List<String[]> headers = new ArrayList<>();
        for (String[] h : request.headers()) {
            headers.add(new String[] {h[0], sub.apply(h[1])});
        }
        // "GET /path" + "Host: example.com" (origin form): the URL comes from the Host header; a target without
        // a scheme is http:// (JetBrains).
        HttpTargets.Target target = HttpTargets.resolve(requestUrl, headers);
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

        RequestBodies.Content bc = RequestBodies.resolve(request, baseDir, sub, contentType, headers, warnings);
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
        for (HttpFile.Redirect r : request.redirects()) {
            sub.apply(r.path()); // resolved again when written; here so that what it reads is known up front
        }
        return new Prepared(
                method,
                url,
                UrlEncoding.withScheme(target.url(), null),
                headers,
                warnings,
                version,
                bc,
                digest,
                label,
                details);
    }

    /** {@link #prepare}'s result; {@code targetUrl} is the URL before auto-encoding (for showing its host). */
    private record Prepared(
            String method,
            String url,
            String targetUrl,
            List<String[]> headers,
            List<String> warnings,
            HttpVersions.Choice version,
            RequestBodies.Content body,
            DigestAuth.Credentials digest,
            String label,
            HttpExchange.Details details) {}

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
            String overLayered,
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

    /** Stops the daemon worker (window close). Without this, each closed window leaks its http-client thread. */
    public void shutdown() {
        active.forEach(Handle::cancel);
        exec.shutdownNow();
        timers.shutdownNow();
    }
}
