package com.editora.http;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

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
 * <p>The quoting is POSIX (bash, zsh, sh), and the UI says so: fish reads {@code \\} inside single quotes as one
 * backslash, PowerShell escapes a quote by doubling it and continues lines with a backtick, and the Windows
 * command prompt has no single quotes at all.
 *
 * <p>The command reproduces what Editora sent, not its display text: a {@code < file} body is
 * {@code --data-binary @file}, a multipart form becomes {@code -F} / {@code --form-string} fields, the Digest
 * shorthand becomes {@code --digest -u}, redirects are followed ({@code -L}) unless the request said
 * {@code @no-redirect}, and an explicit HTTP version is kept. A POST with a body has no {@code -X}: curl infers it,
 * and then turns it into a GET after a 301/302/303 as the run did ({@code -X POST -L} re-posted to the new
 * location). A body sent without a {@code Content-Type} gets {@code -H 'Content-Type:'}, or curl would add its own
 * {@code application/x-www-form-urlencoded}.
 */
public final class CurlExport {

    private static final Pattern PLAIN_METHOD = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");

    private CurlExport() {}

    /** The cURL command for an executed request, from its resolved values and how it was sent. */
    public static String toCurl(HttpExchange ex) {
        return toCurl(ex.method(), ex.url(), ex.headers(), ex.requestBody(), ex.details());
    }

    /** The cURL command for a request from its parts; {@code details} null means known only by its text. */
    static String toCurl(
            String method, String url, List<String[]> headers, String body, HttpExchange.@Nullable Details details) {
        HttpExchange.Details d = details == null ? HttpExchange.Details.NONE : details;
        StringBuilder sb = new StringBuilder("curl");
        String m = method == null ? "" : method.strip();
        boolean form = !d.formParts().isEmpty();
        boolean hasBody = form || d.bodyFile() != null || (body != null && !body.isEmpty());
        if (m.equalsIgnoreCase("HEAD")) {
            sb.append(" -I"); // "-X HEAD" makes curl wait for a body that never comes
        } else if (!m.isEmpty() && !m.equalsIgnoreCase("GET") && !(hasBody && m.equalsIgnoreCase("POST"))) {
            sb.append(" -X ").append(PLAIN_METHOD.matcher(m).matches() ? m : quote(m));
        }
        if (d.followRedirects()) {
            sb.append(" -L");
        }
        String version = versionFlag(d.httpVersion());
        if (version != null) {
            sb.append(' ').append(version);
        }
        sb.append(' ').append(quote(url == null ? "" : url));
        boolean typed = false;
        if (headers != null) {
            for (String[] h : headers) {
                if (form && h[0].equalsIgnoreCase("Content-Type")) {
                    continue; // curl writes its own multipart Content-Type, with the boundary it chose
                }
                typed |= h[0].equalsIgnoreCase("Content-Type");
                sb.append(" \\\n  -H ").append(quote(h[0] + ": " + h[1]));
            }
        }
        if (hasBody && !form && !typed) {
            sb.append(" \\\n  -H ").append(quote("Content-Type:")); // none was sent: curl must not add its own
        }
        if (d.digestUser() != null) {
            // Without the password (a restored entry never has it) curl asks for it instead of sending a wrong one.
            sb.append(" \\\n  --digest -u ")
                    .append(quote(d.digestPass() == null ? d.digestUser() : d.digestUser() + ":" + d.digestPass()));
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

    /**
     * The curl flag for the HTTP version the request line asked for, or null: {@code HTTP/1.1}, {@code HTTP/1.0},
     * {@code HTTP/2} and {@code HTTP/2 (Prior Knowledge)} as written.
     */
    static @Nullable String versionFlag(@Nullable String requested) {
        if (requested == null || requested.isBlank()) {
            return null;
        }
        String v = requested.strip().toUpperCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (v.equals("HTTP/1.1")) {
            return "--http1.1";
        }
        if (v.equals("HTTP/1.0")) {
            return "--http1.0";
        }
        if (v.startsWith("HTTP/2") && v.contains("PRIOR")) {
            return "--http2-prior-knowledge";
        }
        return v.startsWith("HTTP/2") ? "--http2" : null;
    }

    /**
     * One multipart field: a file as {@code -F name=@"path";filename=…;type=…}; an inline value with a type or a
     * filename as {@code -F name="value";type=…;filename="…"} (double-quoted, so curl reads no {@code @}, {@code <}
     * or {@code ;} in it); a bare inline value verbatim with {@code --form-string}.
     */
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
        String value = p.value() == null ? "" : p.value();
        if (p.contentType() != null || p.filename() != null) {
            StringBuilder f = new StringBuilder(name)
                    .append("=\"")
                    .append(curlQuoted(value))
                    .append('"');
            if (p.contentType() != null) {
                f.append(";type=").append(p.contentType());
            }
            if (p.filename() != null) {
                f.append(";filename=\"").append(curlQuoted(p.filename())).append('"');
            }
            return "-F " + quote(f.toString());
        }
        // --form-string: the value is never read as "@file" / "<file" and ";type=" is not parsed out of it.
        return "--form-string " + quote(name + "=" + value);
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
