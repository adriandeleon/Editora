package com.editora.http;

import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Function;

import javafx.application.Platform;

/**
 * UI-facing façade for running {@code .http} requests with the <b>built-in JDK {@code HttpClient}</b>
 * (no external CLI): work runs on one daemon executor and results post back on the JavaFX thread. A run
 * gets a fresh {@link CookieManager} (a per-run cookie jar) and a {@link CapturedResponses} session, so a
 * later request can reference an earlier one's response ({@code {{name.response.body.$.x}}}). Per-request
 * directives ({@code @no-redirect}/{@code @no-cookie-jar}/{@code @timeout}), external bodies, multipart, and
 * the {@code >>}/{@code >>!} response-redirect operators are honored here. Each call returns an
 * {@link HttpExchange} carrying the fully resolved request alongside its {@link HttpResult}.
 */
public final class HttpClientService {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);

    private final ExecutorService exec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "http-client");
        t.setDaemon(true);
        return t;
    });

    /** Runs a single request off-thread (its own cookie jar, no prior context), posting on the FX thread. */
    public void run(HttpFile.Parsed request, Map<String, String> vars, Path baseDir, Consumer<HttpExchange> onResult) {
        exec.submit(() -> {
            CookieManager cookies = new CookieManager();
            HttpExchange ex = execute(request, vars, baseDir, new CapturedResponses(), cookies);
            Platform.runLater(() -> onResult.accept(ex));
        });
    }

    /** Runs {@code requests} sequentially off-thread, sharing one cookie jar + captured-response session so
     *  later requests can reference earlier responses; posts all exchanges together on the FX thread. */
    public void runAll(
            List<HttpFile.Parsed> requests,
            Map<String, String> vars,
            Path baseDir,
            Consumer<List<HttpExchange>> onResult) {
        exec.submit(() -> {
            CookieManager cookies = new CookieManager();
            CapturedResponses captured = new CapturedResponses();
            List<HttpExchange> out = new ArrayList<>();
            for (HttpFile.Parsed req : requests) {
                HttpExchange ex = execute(req, vars, baseDir, captured, cookies);
                captured.put(req.name(), ex.result());
                out.add(ex);
            }
            Platform.runLater(() -> onResult.accept(out));
        });
    }

    private HttpExchange execute(
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
        // Digest shorthand (Authorization: Digest user pass): send anonymously, then answer a 401 challenge.
        DigestAuth.Credentials digest = digestCredentials(headers);
        if (digest != null) {
            headers.removeIf(h -> h[0].equalsIgnoreCase("Authorization"));
        }
        try {
            HttpClient client = clientFor(request.directives(), cookies);
            Duration timeout = request.directives().timeoutSeconds() > 0
                    ? Duration.ofSeconds(request.directives().timeoutSeconds())
                    : REQUEST_TIMEOUT;

            long t0 = System.nanoTime();
            HttpResponse<String> resp = send(client, url, method, headers, timeout, bc.publisher(), null);
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
                    resp = send(client, url, method, headers, timeout, bc.publisher(), authz);
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
            long size = resp.body() == null ? 0 : resp.body().getBytes(StandardCharsets.UTF_8).length;
            HttpResult result =
                    new HttpResult(resp.statusCode(), respHeaders, resp.body(), respType, ms, size, null, warnings);
            writeRedirects(request, baseDir, result);
            return new HttpExchange(label, method, url, headers, bc.display(), result);
        } catch (Exception e) {
            HttpResult err = new HttpResult(0, List.of(), "", "", 0, 0, errorMessage(url, e), warnings);
            return new HttpExchange(label, method, url, headers, bc.display(), err);
        }
    }

    /** The request body publisher + a display string (for the cURL/history view). */
    private record BodyContent(HttpRequest.BodyPublisher publisher, String display) {}

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
            byte[] bytes = Multipart.build(Multipart.parse(request.body(), boundary), boundary, baseDir, sub);
            return new BodyContent(
                    HttpRequest.BodyPublishers.ofByteArray(bytes), "(multipart/form-data, " + bytes.length + " bytes)");
        }
        // external body: < ./file (raw) or <@ ./file (substituted), optional encoding.
        if (request.bodyRef() != null && baseDir != null) {
            try {
                HttpFile.BodyRef ref = request.bodyRef();
                Charset cs = ref.encoding() == null ? StandardCharsets.UTF_8 : Charset.forName(ref.encoding());
                String content = new String(Files.readAllBytes(baseDir.resolve(ref.path())), cs);
                if (ref.substitute()) {
                    content = sub.apply(content);
                }
                return new BodyContent(HttpRequest.BodyPublishers.ofString(content), content);
            } catch (Exception e) {
                return new BodyContent(
                        HttpRequest.BodyPublishers.noBody(),
                        "(missing body file: " + request.bodyRef().path() + ")");
            }
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

    /** Builds and sends one request; {@code authOverride} (when non-null) sets the Authorization header. */
    private static HttpResponse<String> send(
            HttpClient client,
            String url,
            String method,
            List<String[]> headers,
            Duration timeout,
            HttpRequest.BodyPublisher publisher,
            String authOverride)
            throws java.io.IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        for (String[] h : headers) {
            b.header(h[0], h[1]); // pre-validated by HttpHeaders.partition — un-sendable ones were surfaced
        }
        if (authOverride != null) {
            b.header("Authorization", authOverride);
        }
        b.method(method, publisher);
        return client.send(b.build(), HttpResponse.BodyHandlers.ofString());
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

    /** Writes the response body to each {@code >>}/{@code >>!} target (force overwrites; plain skips existing). */
    private static void writeRedirects(HttpFile.Parsed request, Path baseDir, HttpResult result) {
        if (baseDir == null || result.failed()) {
            return;
        }
        Path baseReal = baseDir.toAbsolutePath().normalize();
        for (HttpFile.Redirect r : request.redirects()) {
            try {
                Path target = baseReal.resolve(r.path()).normalize();
                if (!target.startsWith(baseReal)) {
                    continue; // a ">> ../../x" must not write outside the request file's folder
                }
                if (!r.force() && Files.exists(target)) {
                    continue;
                }
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, result.body() == null ? "" : result.body());
            } catch (Exception ignore) {
                // best-effort: a failed redirect write never aborts the response
            }
        }
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
        exec.shutdownNow();
    }
}
