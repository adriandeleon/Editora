package com.editora.http;

import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jspecify.annotations.Nullable;

/**
 * What a request resolves to before anything is sent — method, URL, headers, body, Digest credentials, the label and
 * the details — with every {@code {{…}}} substituted through one function. A send ({@link HttpClientService}) and the
 * trust gate's dry resolution ({@link SecretApproval}) build it the same way ({@link #of}), so what the gate asks about
 * is exactly what the send reads. {@code targetUrl} is the URL before auto-encoding (for showing its host).
 */
record PreparedRequest(
        String method,
        String url,
        String targetUrl,
        List<String[]> headers,
        List<String> warnings,
        HttpVersions.Choice version,
        RequestBodies.Content body,
        DigestAuth.@Nullable Credentials digest,
        String label,
        HttpExchange.Details details) {

    /**
     * A dry resolution of a request: built by {@link #of} with every secret source kept as written ({@link
     * HttpVars.Unresolved#dry()}), so it can be shown or saved; {@code sink} holds what it read, {@code scope} and
     * {@code now} what it resolved against.
     */
    record Dry(PreparedRequest prepared, HttpVars.Unresolved sink, HttpVars.Scope scope, ZonedDateTime now) {}

    /** The dry resolution of {@code request} (see {@link Dry}). Reads the request's body files: off the FX thread. */
    static Dry dry(HttpFile.Parsed request, Map<String, String> vars, Path baseDir, CapturedResponses captured) {
        ZonedDateTime now = ZonedDateTime.now();
        HttpVars.Unresolved sink = HttpVars.Unresolved.dry();
        HttpVars.Scope scope = HttpVars.scope(vars, request.variables(), now, captured, baseDir, true);
        PreparedRequest p = of(request, baseDir, s -> HttpVars.substitute(s, scope, now, captured, baseDir, sink));
        return new Dry(p, sink, scope, now);
    }

    /**
     * What a request resolves to before anything is sent: method, URL, headers, body, Digest credentials, the label
     * and the details — every {@code {{…}}} of it substituted through {@code sub}, the {@code >>} targets included,
     * so whatever the run reads (the secrets the trust gate asks about) is known before the first byte goes out.
     * {@link #dry} runs exactly this with a dry {@code sub}.
     */
    static PreparedRequest of(HttpFile.Parsed request, Path baseDir, Function<String, String> sub) {
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
        return new PreparedRequest(
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

    private static DigestAuth.@Nullable Credentials digestCredentials(List<String[]> headers) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase("Authorization")) {
                return DigestAuth.shorthand(h[1]);
            }
        }
        return null;
    }

    private static String headerValue(List<String[]> headers, String name) {
        for (String[] h : headers) {
            if (h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return "";
    }
}
