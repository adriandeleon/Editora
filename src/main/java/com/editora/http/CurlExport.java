package com.editora.http;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Renders a (resolved) request as a {@code curl} command for the response viewer's "Copy as cURL" action.
 * Pure, so it is unit-tested.
 *
 * <p>The command is meant to be pasted into a shell, and its parts come from a {@code .http} file that may have
 * arrived in a cloned repository, so <b>every</b> part — the URL, the method, each header name and value, the
 * body — is single-quoted with POSIX escaping. A quote in a URL ({@code ?q=a'$(cmd)'}) is legal there and must
 * not end the quoting. The body goes out with {@code --data-raw}, so one that starts with {@code @} is sent as
 * text instead of making curl read a local file.
 *
 * <p>The command reproduces what Editora sent, not its display text: a {@code < file} body is
 * {@code --data-binary @file}, a multipart form becomes {@code -F} / {@code --form-string} fields, the Digest
 * shorthand becomes {@code --digest -u}, redirects are followed ({@code -L}) unless the request said
 * {@code @no-redirect}, and an explicit HTTP version is kept.
 */
public final class CurlExport {

    private static final Pattern PLAIN_METHOD = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");

    /** The details of a request known only by its text: nothing beyond method, URL, headers and body. */
    private static final HttpExchange.Details TEXT_ONLY =
            new HttpExchange.Details(null, List.of(), null, null, false, null, null, false);

    private CurlExport() {}

    /** The cURL command for an executed request, from its resolved values and how it was sent. */
    public static String toCurl(HttpExchange ex) {
        return toCurl(ex.method(), ex.url(), ex.headers(), ex.requestBody(), ex.details());
    }

    /** The cURL command for a request known only by its text (no redirect, upload or auth details). */
    public static String toCurl(String method, String url, List<String[]> headers, String body) {
        return toCurl(method, url, headers, body, TEXT_ONLY);
    }

    static String toCurl(String method, String url, List<String[]> headers, String body, HttpExchange.Details details) {
        HttpExchange.Details d = details == null ? TEXT_ONLY : details;
        StringBuilder sb = new StringBuilder("curl");
        String m = method == null ? "" : method.strip();
        if (m.equalsIgnoreCase("HEAD")) {
            sb.append(" -I"); // "-X HEAD" makes curl wait for a body that never comes
        } else if (!m.isEmpty() && !m.equalsIgnoreCase("GET")) {
            sb.append(" -X ").append(PLAIN_METHOD.matcher(m).matches() ? m : quote(m));
        }
        if (d.followRedirects()) {
            sb.append(" -L");
        }
        if ("HTTP/1.1".equalsIgnoreCase(d.httpVersion())) {
            sb.append(" --http1.1");
        } else if (d.httpVersion() != null
                && d.httpVersion().toUpperCase(Locale.ROOT).startsWith("HTTP/2")) {
            sb.append(" --http2");
        }
        sb.append(' ').append(quote(url == null ? "" : url));
        boolean form = !d.formParts().isEmpty();
        if (headers != null) {
            for (String[] h : headers) {
                if (form && h[0].equalsIgnoreCase("Content-Type")) {
                    continue; // curl writes its own multipart Content-Type, with the boundary it chose
                }
                sb.append(" \\\n  -H ").append(quote(h[0] + ": " + h[1]));
            }
        }
        if (d.digestUser() != null) {
            sb.append(" \\\n  --digest -u ")
                    .append(quote(d.digestUser() + ":" + (d.digestPass() == null ? "" : d.digestPass())));
        }
        if (form) {
            for (HttpExchange.FormPart p : d.formParts()) {
                sb.append(" \\\n  ").append(formField(p));
            }
        } else if (d.bodyFile() != null) {
            sb.append(" \\\n  --data-binary ").append(quote("@" + d.bodyFile()));
        } else if (body != null && !body.isEmpty()) {
            sb.append(" \\\n  --data-raw ").append(quote(body));
        }
        return sb.toString();
    }

    /** One multipart field: a file as {@code -F name=@path;filename=…;type=…}, an inline value verbatim. */
    private static String formField(HttpExchange.FormPart p) {
        String name = p.name() == null ? "" : p.name();
        if (p.file() != null) {
            StringBuilder f = new StringBuilder(name)
                    .append("=@\"")
                    .append(curlQuoted(p.file()))
                    .append('"');
            if (p.filename() != null) {
                f.append(";filename=\"").append(curlQuoted(p.filename())).append('"');
            }
            if (p.contentType() != null) {
                f.append(";type=").append(p.contentType());
            }
            return "-F " + quote(f.toString());
        }
        // --form-string: the value is never read as "@file" / "<file" and ";type=" is not parsed out of it.
        return "--form-string " + quote(name + "=" + (p.value() == null ? "" : p.value()));
    }

    /** Escapes {@code "} and {@code \} for a double-quoted value inside a curl {@code -F} field. */
    private static String curlQuoted(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** A POSIX single-quoted shell word: {@code '} → {@code '\''}. */
    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }
}
