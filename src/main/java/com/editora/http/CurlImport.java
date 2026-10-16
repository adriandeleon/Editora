package com.editora.http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Converts a {@code curl …} command line into a {@code .http} request block. Tokenizes the command the way a
 * POSIX shell would (single and double quotes, backslash escapes, {@code $'…'} ANSI-C strings, {@code \}-newline
 * continuations) and also accepts Windows {@code cmd} quoting ({@code ^"…^"}, {@code ^} continuations), the
 * two forms browsers' "Copy as cURL" produce. It maps the common flags — {@code -X}, {@code -H}, {@code -d} and
 * the {@code --data*} family ({@code @file} becomes {@code < ./file}), {@code --data-urlencode}, {@code --json},
 * {@code -F} (a multipart body), {@code -u} (Basic, or Digest with {@code --digest}), {@code -A}, {@code -b},
 * {@code -e}, {@code -I}, {@code -G}, {@code --url} — skips every other option together with its argument (so
 * {@code -o /dev/null} is not taken for the URL), and infers {@code POST} + a Content-Type when a body is given
 * without one. Pure, so it is unit-tested.
 */
public final class CurlImport {

    /** The boundary of a multipart body built from {@code -F} parts. */
    static final String BOUNDARY = "WebAppBoundary";

    /** Short options that take an argument (the value is the rest of the token, or the next token). */
    private static final String SHORT_WITH_ARG = "AbcCdDeEFHKmoPQrtTuUwxXyYz";

    /** Long options that take an argument, beyond the ones mapped explicitly below. */
    private static final Set<String> LONG_WITH_ARG = Set.of(
            "--abstract-unix-socket",
            "--alt-svc",
            "--aws-sigv4",
            "--cacert",
            "--capath",
            "--cert",
            "--cert-type",
            "--ciphers",
            "--config",
            "--connect-timeout",
            "--connect-to",
            "--continue-at",
            "--cookie-jar",
            "--create-file-mode",
            "--curves",
            "--dns-interface",
            "--dns-ipv4-addr",
            "--dns-ipv6-addr",
            "--dns-servers",
            "--doh-url",
            "--dump-header",
            "--ech",
            "--egd-file",
            "--engine",
            "--etag-compare",
            "--etag-save",
            "--expect100-timeout",
            "--ftp-account",
            "--ftp-alternative-to-user",
            "--ftp-method",
            "--ftp-port",
            "--ftp-ssl-ccc-mode",
            "--happy-eyeballs-timeout-ms",
            "--haproxy-clientip",
            "--hostpubmd5",
            "--hostpubsha256",
            "--hsts",
            "--interface",
            "--ip-tos",
            "--ipfs-gateway",
            "--keepalive-cnt",
            "--keepalive-time",
            "--key",
            "--key-type",
            "--krb",
            "--libcurl",
            "--limit-rate",
            "--local-port",
            "--login-options",
            "--mail-auth",
            "--mail-from",
            "--mail-rcpt",
            "--max-filesize",
            "--max-redirs",
            "--max-time",
            "--netrc-file",
            "--noproxy",
            "--oauth2-bearer",
            "--output",
            "--output-dir",
            "--pass",
            "--pinnedpubkey",
            "--preproxy",
            "--proto",
            "--proto-default",
            "--proto-redir",
            "--proxy",
            "--proxy-cacert",
            "--proxy-capath",
            "--proxy-cert",
            "--proxy-cert-type",
            "--proxy-ciphers",
            "--proxy-crlfile",
            "--proxy-header",
            "--proxy-key",
            "--proxy-key-type",
            "--proxy-pass",
            "--proxy-pinnedpubkey",
            "--proxy-service-name",
            "--proxy-tls13-ciphers",
            "--proxy-tlsauthtype",
            "--proxy-tlspassword",
            "--proxy-tlsuser",
            "--proxy-user",
            "--proxy1.0",
            "--pubkey",
            "--quote",
            "--random-file",
            "--range",
            "--rate",
            "--request-target",
            "--resolve",
            "--retry",
            "--retry-delay",
            "--retry-max-time",
            "--sasl-authzid",
            "--service-name",
            "--socks4",
            "--socks4a",
            "--socks5",
            "--socks5-gssapi-service",
            "--socks5-hostname",
            "--speed-limit",
            "--speed-time",
            "--stderr",
            "--telnet-option",
            "--tftp-blksize",
            "--time-cond",
            "--tls-max",
            "--tls13-ciphers",
            "--tlsauthtype",
            "--tlspassword",
            "--tlsuser",
            "--trace",
            "--trace-ascii",
            "--trace-config",
            "--unix-socket",
            "--upload-file",
            "--variable",
            "--write-out");

    private CurlImport() {}

    /** What the flags describe, collected before the request block is written. */
    private static final class Command {
        String method;
        String url;
        final List<String[]> headers = new ArrayList<>();
        final List<String> data = new ArrayList<>(); // -d / --data-* pieces, joined with '&'
        final List<String> json = new ArrayList<>(); // --json pieces, concatenated
        final List<String[]> form = new ArrayList<>(); // -F name=value pieces: [spec, isString]
        String bodyFile; // -d @file / --json @file
        String user;
        boolean digest;
        boolean head;
        boolean get;
        String version; // --http1.1 / --http2 → the request line's version
        String uploadFile; // -T / --upload-file
        final List<String> query = new ArrayList<>(); // --url-query pieces
        final java.util.Set<String> removed = new java.util.HashSet<>(); // "-H 'Name:'": headers not to send
    }

    /**
     * Builds a {@code METHOD url} + headers + body request block from a {@code curl} command, or returns {@code null}
     * when {@code curl} is not one: no {@code curl}/{@code curl.exe} word, or no URL. Anything before the
     * {@code curl} word — a shell prompt ({@code $ curl …}), {@code sudo} — is skipped, and so is a path to it
     * ({@code /usr/bin/curl}); PowerShell's backtick line continuations are ignored.
     */
    public static String toHttpRequest(String curl) {
        List<String> tokens = tokenize(curl);
        int start = curlWord(tokens);
        if (start < 0) {
            return null;
        }
        Command c = new Command();
        boolean optionsDone = false;
        for (int i = start + 1; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (t.equals("`")) {
                continue; // a PowerShell line continuation
            }
            if (optionsDone || !t.startsWith("-") || t.equals("-")) {
                if (c.url == null) {
                    c.url = t;
                }
                continue; // a later bare word (a second URL) is ignored — best effort
            }
            if (t.equals("--")) {
                optionsDone = true;
                continue;
            }
            if (t.startsWith("--")) {
                if (takesValue(t)) {
                    if (i + 1 < tokens.size()) {
                        apply(c, t, tokens.get(++i));
                    }
                } else {
                    flag(c, t);
                }
                continue;
            }
            // A cluster of short options: "-sSL", "-XPOST", "-sX POST", "-H'X: 1'".
            for (int k = 1; k < t.length(); k++) {
                char ch = t.charAt(k);
                String opt = "-" + ch;
                if (SHORT_WITH_ARG.indexOf(ch) >= 0) {
                    String value =
                            k + 1 < t.length() ? t.substring(k + 1) : i + 1 < tokens.size() ? tokens.get(++i) : null;
                    if (value != null) {
                        apply(c, opt, value);
                    }
                    break;
                }
                flag(c, opt);
            }
        }
        if (c.url == null || c.url.isBlank()) {
            return null;
        }
        // Pasted text never reads a variable: every {{ of the command is written as the \{{ escape, sent as {{.
        return HttpVars.escape(render(c));
    }

    /**
     * How many lines of the body in {@code request} (as {@link #toHttpRequest} wrote it) the {@code .http} format does
     * not send as written: a {@code #} or {@code //} line is a comment, a {@code ###} line starts another request, and
     * from a {@code >}, {@code >>} or {@code <>} line on everything is a handler, a redirect or a reference. The
     * import cannot keep such a body as it was (the format has no escape for them), so it says so instead.
     */
    public static int alteredBodyLines(String request) {
        if (request == null) {
            return 0;
        }
        String[] lines = request.split("\n", -1);
        int i = 1;
        while (i < lines.length && !lines[i].isBlank()) {
            i++; // the headers
        }
        int altered = 0;
        boolean ended = false;
        for (i++; i < lines.length; i++) {
            String t = lines[i].strip();
            if (ended || t.startsWith(">") || t.startsWith("<>") || HttpFile.isSeparator(t)) {
                ended = ended || !t.isEmpty(); // the rest is a handler, a reference, or another request
                altered += t.isEmpty() ? 0 : 1;
            } else if (HttpFile.isComment(t)) {
                altered++;
            }
        }
        return altered;
    }

    /** The index of the {@code curl} (or {@code curl.exe}, or a path to either) word in {@code tokens}, else -1. */
    static int curlWord(List<String> tokens) {
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            String name = t.substring(Math.max(t.lastIndexOf('/'), t.lastIndexOf('\\')) + 1);
            if (name.equalsIgnoreCase("curl") || name.equalsIgnoreCase("curl.exe")) {
                return i;
            }
            if (t.startsWith("-")) {
                return -1; // options before any curl word: not a curl command
            }
        }
        return -1;
    }

    private static boolean takesValue(String longOpt) {
        return switch (longOpt) {
            case "--request",
                    "--header",
                    "--data",
                    "--data-raw",
                    "--data-binary",
                    "--data-ascii",
                    "--data-urlencode",
                    "--json",
                    "--form",
                    "--form-string",
                    "--user",
                    "--user-agent",
                    "--cookie",
                    "--referer",
                    "--url",
                    "--url-query" -> true;
            default -> LONG_WITH_ARG.contains(longOpt);
        };
    }

    /** An option without an argument. */
    private static void flag(Command c, String opt) {
        switch (opt) {
            case "-I", "--head" -> c.head = true;
            case "-G", "--get" -> c.get = true;
            case "--digest" -> c.digest = true;
            case "--basic" -> c.digest = false;
            case "--http1.0", "-0" -> c.version = "HTTP/1.0";
            case "--http1.1" -> c.version = "HTTP/1.1";
            case "--http2" -> c.version = "HTTP/2";
            case "--http2-prior-knowledge" -> c.version = "HTTP/2 (Prior Knowledge)";
            default -> {} // -L, -k, -s, --compressed, … : nothing to carry over
        }
    }

    /** An option with its argument. */
    private static void apply(Command c, String opt, String v) {
        switch (opt) {
            case "-X", "--request" -> c.method = v;
            case "-H", "--header" -> {
                if (v.startsWith("@")) {
                    break; // a file of headers
                }
                String trimmed = v.strip();
                if (trimmed.endsWith(":") && trimmed.indexOf(':') == trimmed.length() - 1) {
                    // "Name:" removes a header curl would add (Copy as cURL writes "Content-Type:" for a body sent
                    // without one), so the import sends none either.
                    String name = trimmed.substring(0, trimmed.length() - 1).strip();
                    c.removed.add(name.toLowerCase(Locale.ROOT));
                    c.headers.removeIf(h -> h[0].equalsIgnoreCase(name));
                } else if (trimmed.endsWith(";") && trimmed.indexOf(':') < 0) {
                    c.headers.add(new String[] {
                        trimmed.substring(0, trimmed.length() - 1).strip(), ""
                    }); // "X;": empty
                } else {
                    c.headers.add(splitHeader(v));
                }
            }
            case "-d", "--data", "--data-ascii", "--data-binary" -> {
                if (v.startsWith("@") && v.length() > 1 && !v.equals("@-")) {
                    c.bodyFile = v.substring(1); // curl reads the body from that file
                } else {
                    c.data.add(v);
                }
            }
            case "--data-raw" -> c.data.add(v); // no "@file" meaning
            case "--data-urlencode" -> c.data.add(urlencodeData(v));
            case "--json" -> {
                if (v.startsWith("@") && v.length() > 1 && !v.equals("@-")) {
                    c.bodyFile = v.substring(1);
                }
                c.json.add(v.startsWith("@") ? "" : v);
            }
            case "-F", "--form" -> c.form.add(new String[] {v, "false"});
            case "--form-string" -> c.form.add(new String[] {v, "true"});
            case "-u", "--user" -> c.user = v;
            case "-A", "--user-agent" -> c.headers.add(new String[] {"User-Agent", v});
            case "-b", "--cookie" -> {
                if (v.contains("=")) {
                    c.headers.add(new String[] {"Cookie", v}); // without '=' it names a cookie file
                }
            }
            case "-e", "--referer" -> c.headers.add(new String[] {"Referer", v});
            case "--url" -> c.url = v;
            // curl adds it to the URL's query, URL-encoding the content unless it starts with "+"
            case "--url-query" -> c.query.add(v.startsWith("+") ? v.substring(1) : urlencodeData(v));
            case "-T", "--upload-file" -> {
                if (!v.equals("-") && !v.equals(".")) {
                    c.uploadFile = v; // curl PUTs the file (stdin is not something a .http file can say)
                }
            }
            default -> {} // an option Editora has no counterpart for; its argument is consumed with it
        }
    }

    private static String render(Command c) {
        if (c.uploadFile != null && c.bodyFile == null && c.data.isEmpty() && c.json.isEmpty() && c.form.isEmpty()) {
            c.bodyFile = c.uploadFile;
            if (c.method == null) {
                c.method = "PUT";
            }
        }
        boolean hasData = !c.data.isEmpty() || c.bodyFile != null || !c.json.isEmpty();
        boolean hasForm = !c.form.isEmpty();
        String url = c.url == null ? "" : c.url;
        if (!c.query.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + String.join("&", c.query);
        }
        String data = String.join("&", c.data);
        if (c.get && !c.data.isEmpty()) {
            url = url + (url.contains("?") ? "&" : "?") + data; // -G: the data is the query string
            data = "";
        }
        String method = c.method;
        if (method == null) {
            method = c.head ? "HEAD" : (hasData && !c.get) || hasForm ? "POST" : "GET";
        }
        List<String[]> headers = new ArrayList<>(c.headers);
        if (c.user != null) {
            headers.add(userHeader(c.user, c.digest));
        }
        if (!c.json.isEmpty()) {
            addIfAbsent(headers, "Content-Type", "application/json");
            addIfAbsent(headers, "Accept", "application/json");
        } else if (hasForm) {
            headers.removeIf(h -> h[0].equalsIgnoreCase("Content-Type")
                    && !h[1].toLowerCase(Locale.ROOT).contains("boundary="));
            addIfAbsent(headers, "Content-Type", "multipart/form-data; boundary=" + BOUNDARY);
        } else if (hasData && c.bodyFile != null && c.bodyFile.equals(c.uploadFile)) {
            // -T sends the file as it is: curl adds no Content-Type, and neither does the import
        } else if (hasData && !(c.get && c.bodyFile == null)) {
            if (!c.removed.contains("content-type")) {
                addIfAbsent(headers, "Content-Type", "application/x-www-form-urlencoded");
            }
        }
        if (!hasForm) {
            // "-H 'Content-Type:'" said curl must not add one: neither does the import (a multipart body needs its own)
            headers.removeIf(h -> c.removed.contains(h[0].toLowerCase(Locale.ROOT)));
        }
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(url);
        if (c.version != null) {
            sb.append(' ').append(c.version);
        }
        sb.append('\n');
        for (String[] h : headers) {
            sb.append(h[0]).append(": ").append(h[1]).append('\n');
        }
        String body = null;
        if (hasForm) {
            body = multipart(c.form, Multipart.boundaryOf(headerValue(headers, "Content-Type")));
        } else if (c.bodyFile != null) {
            body = "< " + relative(c.bodyFile);
        } else if (!c.json.isEmpty()) {
            body = String.join("", c.json);
        } else if (!data.isEmpty() || (!c.data.isEmpty() && !c.get)) {
            body = data;
        }
        if (body != null) {
            sb.append('\n').append(inert(body)).append('\n');
        }
        return sb.toString();
    }

    /**
     * {@code body} with every line from the first one the format would not send as body — a {@code >} handler or
     * redirect, a {@code <>} reference, a {@code ###} separator — turned into a {@code #} comment. Those lines were
     * never sent; as comments they cannot become a response handler (code that runs), a pre-request script or a
     * directive of another request either: pasted text never runs anything.
     */
    static String inert(String body) {
        String[] lines = body.split("\n", -1);
        boolean ended = false;
        for (int i = 0; i < lines.length; i++) {
            String t = lines[i].strip();
            ended = ended || t.startsWith(">") || t.startsWith("<>") || HttpFile.isSeparator(t);
            if (ended && !t.isEmpty()) {
                lines[i] = "# " + lines[i];
            }
        }
        return String.join("\n", lines);
    }

    /** The {@code -F} parts as a {@code .http} multipart body. */
    private static String multipart(List<String[]> parts, String boundary) {
        String b = boundary == null || boundary.isEmpty() ? BOUNDARY : boundary;
        StringBuilder sb = new StringBuilder();
        for (String[] part : parts) {
            String spec = part[0];
            boolean literal = Boolean.parseBoolean(part[1]);
            int eq = spec.indexOf('=');
            String name = eq < 0 ? spec : spec.substring(0, eq);
            String value = eq < 0 ? "" : spec.substring(eq + 1);
            String type = null;
            String filename = null;
            String file = null;
            boolean fileAsText = false;
            String attributes = "";
            if (!literal && (value.startsWith("@") || value.startsWith("<"))) {
                fileAsText = value.startsWith("<"); // name=<file: the file's content as a plain field
                // The file may be double-quoted (curl's -F syntax, and Editora's own Copy as cURL): then it may
                // hold ';' and '\"', and the attributes start after the closing quote.
                String rest = value.substring(1);
                if (rest.startsWith("\"")) {
                    int close = closingQuote(rest);
                    file = curlUnescape(rest.substring(1, close < 0 ? rest.length() : close));
                    attributes = close < 0 ? "" : rest.substring(close + 1);
                } else {
                    int semi = rest.indexOf(';');
                    file = semi < 0 ? rest : rest.substring(0, semi);
                    attributes = semi < 0 ? "" : rest.substring(semi);
                }
            } else if (!literal && value.startsWith("\"")) {
                // name="text";type=…: a quoted value may hold ';' itself, and its attributes follow the quote
                int close = closingQuote(value);
                attributes = close < 0 ? "" : value.substring(close + 1);
                value = curlUnescape(value.substring(1, close < 0 ? value.length() : close));
            } else if (!literal) {
                // name=text;type=application/json: curl reads ";type=" / ";filename=" / ";headers=" off a value
                int cut = firstAttribute(value);
                if (cut >= 0) {
                    attributes = value.substring(cut);
                    value = value.substring(0, cut);
                }
            }
            for (String a : attributes(attributes)) {
                if (a.startsWith("type=")) {
                    type = a.substring(5);
                } else if (a.startsWith("filename=")) {
                    filename = unquote(a.substring(9));
                }
            }
            if (file != null && !fileAsText && filename == null) {
                int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                filename = file.substring(slash + 1);
            }
            sb.append("--").append(b).append('\n');
            sb.append("Content-Disposition: form-data; name=\"").append(name).append('"');
            if (filename != null) {
                sb.append("; filename=\"")
                        .append(filename.replace("\\", "\\\\").replace("\"", "\\\""))
                        .append('"');
            }
            sb.append('\n');
            if (type != null) {
                sb.append("Content-Type: ").append(type).append('\n');
            }
            sb.append('\n');
            sb.append(file != null ? "< " + relative(file) : value).append('\n');
        }
        sb.append("--").append(b).append("--");
        return sb.toString();
    }

    /** Where the first {@code ;type=}, {@code ;filename=} or {@code ;headers=} attribute starts in an unquoted value. */
    private static int firstAttribute(String value) {
        int best = -1;
        for (String a : new String[] {";type=", ";filename=", ";headers="}) {
            int at = value.indexOf(a);
            if (at >= 0 && (best < 0 || at < best)) {
                best = at;
            }
        }
        return best;
    }

    /** The {@code ;}-separated attributes of an {@code -F} part, a {@code "quoted"} value keeping its {@code ;}. */
    static List<String> attributes(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (quoted && ch == '\\' && i + 1 < s.length()) {
                cur.append(ch).append(s.charAt(++i));
            } else if (ch == '"') {
                quoted = !quoted;
                cur.append(ch);
            } else if (ch == ';' && !quoted) {
                if (!cur.toString().isBlank()) {
                    out.add(cur.toString().strip());
                }
                cur.setLength(0);
            } else {
                cur.append(ch);
            }
        }
        if (!cur.toString().isBlank()) {
            out.add(cur.toString().strip());
        }
        return out;
    }

    /** {@code --data-urlencode} forms: {@code content}, {@code =content}, {@code name=content}. */
    private static String urlencodeData(String v) {
        int eq = v.indexOf('=');
        if (eq < 0) {
            return encode(v);
        }
        String name = v.substring(0, eq);
        String content = encode(v.substring(eq + 1));
        return name.isEmpty() ? content : name + "=" + content;
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** A path as a {@code .http} file reference: relative ones get the {@code ./} JetBrains writes. */
    private static String relative(String path) {
        if (path.startsWith("/") || path.startsWith("./") || path.startsWith("../") || path.matches("^[A-Za-z]:.*")) {
            return path;
        }
        return "./" + path;
    }

    private static String unquote(String s) {
        return s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")
                ? curlUnescape(s.substring(1, s.length() - 1))
                : s;
    }

    /** The index of the {@code "} closing {@code s} (which starts with one), skipping {@code \"}; -1 if none. */
    private static int closingQuote(String s) {
        for (int i = 1; i < s.length(); i++) {
            if (s.charAt(i) == '\\') {
                i++;
            } else if (s.charAt(i) == '"') {
                return i;
            }
        }
        return -1;
    }

    /** Undoes curl's {@code \"} and {@code \\} escapes inside a double-quoted {@code -F} value. */
    private static String curlUnescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static void addIfAbsent(List<String[]> headers, String name, String value) {
        if (headers.stream().noneMatch(h -> h[0].equalsIgnoreCase(name))) {
            headers.add(new String[] {name, value});
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

    private static String[] splitHeader(String h) {
        int colon = h.indexOf(':');
        return colon > 0
                ? new String[] {
                    h.substring(0, colon).strip(), h.substring(colon + 1).strip()
                }
                : new String[] {h.strip(), ""};
    }

    private static String[] userHeader(String userPass, boolean digest) {
        int colon = userPass.indexOf(':');
        String user = colon >= 0 ? userPass.substring(0, colon) : userPass;
        String pass = colon >= 0 ? userPass.substring(colon + 1) : "";
        // Editora's Digest shorthand (and IntelliJ's): "Authorization: Digest user pass".
        return new String[] {"Authorization", digest ? "Digest " + user + " " + pass : HttpAuth.basic(user, pass)};
    }

    // --- tokenizing ---------------------------------------------------------------------------------

    /**
     * Splits a curl command into tokens like a POSIX shell: {@code '…'} is literal, {@code "…"} honours
     * {@code \"}, {@code \\}, {@code \$} and {@code \`}, {@code $'…'} expands ANSI-C escapes, a backslash outside
     * quotes escapes the next character, and {@code \}-newline continues the line. A Windows {@code cmd}
     * command ({@code ^"…^"} quoting, {@code ^} continuations) is first turned into that form.
     */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) {
            return out;
        }
        String src = isCmdStyle(s) ? fromCmd(s) : s;
        StringBuilder cur = new StringBuilder();
        boolean inTok = false;
        int i = 0;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '\\' && i + 1 < n && (src.charAt(i + 1) == '\n' || src.charAt(i + 1) == '\r')) {
                i += 2; // line continuation
                if (i < n && src.charAt(i - 1) == '\r' && src.charAt(i) == '\n') {
                    i++;
                }
                continue;
            }
            if (c == '\\') {
                inTok = true;
                if (i + 1 < n) {
                    cur.append(src.charAt(i + 1)); // an escaped character outside quotes
                    i += 2;
                } else {
                    cur.append(c); // a trailing backslash is kept
                    i++;
                }
                continue;
            }
            if (c == '$' && i + 1 < n && src.charAt(i + 1) == '\'') {
                inTok = true;
                i = ansiC(src, i + 2, cur);
                continue;
            }
            if (c == '\'' || c == '"') {
                inTok = true;
                char q = c;
                i++;
                while (i < n && src.charAt(i) != q) {
                    char d = src.charAt(i);
                    if (q == '"' && d == '\\' && i + 1 < n && "\"\\$`\n".indexOf(src.charAt(i + 1)) >= 0) {
                        if (src.charAt(i + 1) != '\n') {
                            cur.append(src.charAt(i + 1));
                        }
                        i += 2;
                    } else {
                        cur.append(d);
                        i++;
                    }
                }
                i++; // closing quote
                continue;
            }
            if (Character.isWhitespace(c)) {
                if (inTok) {
                    out.add(cur.toString());
                    cur.setLength(0);
                    inTok = false;
                }
                i++;
                continue;
            }
            inTok = true;
            cur.append(c);
            i++;
        }
        if (inTok) {
            out.add(cur.toString());
        }
        return out;
    }

    /** Reads a {@code $'…'} body starting after the opening quote into {@code cur}; returns the index after it. */
    private static int ansiC(String s, int i, StringBuilder cur) {
        int n = s.length();
        while (i < n && s.charAt(i) != '\'') {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= n) {
                cur.append(c);
                i++;
                continue;
            }
            char e = s.charAt(i + 1);
            i += 2;
            switch (e) {
                case 'n' -> cur.append('\n');
                case 't' -> cur.append('\t');
                case 'r' -> cur.append('\r');
                case 'a' -> cur.append('\u0007');
                case 'b' -> cur.append('\b');
                case 'e', 'E' -> cur.append('\u001b');
                case 'f' -> cur.append('\f');
                case 'v' -> cur.append('\u000b');
                case 'x' -> i = hexEscape(s, i, 2, cur);
                case 'u' -> i = hexEscape(s, i, 4, cur);
                case 'U' -> i = hexEscape(s, i, 8, cur);
                default -> cur.append(e); // \\, \', \", \?
            }
        }
        return i + 1; // past the closing quote
    }

    private static int hexEscape(String s, int i, int max, StringBuilder cur) {
        int end = i;
        while (end < s.length() && end - i < max && Character.digit(s.charAt(end), 16) >= 0) {
            end++;
        }
        if (end == i) {
            cur.append(max == 2 ? "\\x" : max == 4 ? "\\u" : "\\U");
            return i;
        }
        cur.appendCodePoint(Integer.parseInt(s.substring(i, end), 16));
        return end;
    }

    /** A command copied as Windows {@code cmd}: carets escape the next character and continue lines. */
    static boolean isCmdStyle(String s) {
        return s.contains("^\"") || s.matches("(?s).*\\^\\r?\\n.*");
    }

    /**
     * Removes {@code cmd}'s carets: {@code ^} + newline continues the line, {@code ^x} is a literal {@code x}. Inside a
     * {@code ^"…^"} string, {@code ^} + newline escapes that newline and the literal newline Chrome writes after it is
     * the one kept, so it adds nothing; between arguments it is a space.
     */
    private static String fromCmd(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean quoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '^' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '\r' || next == '\n') {
                    i++;
                    if (next == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        i++;
                    }
                    if (!quoted) {
                        sb.append(' ');
                    }
                    continue;
                }
                if (next == '"' && trailingBackslashes(sb) % 2 == 0) {
                    quoted = !quoted; // ^" opens or closes a string; \^" (an odd run of \) is a quote inside one
                }
                sb.append(next);
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static int trailingBackslashes(CharSequence sb) {
        int n = 0;
        for (int i = sb.length() - 1; i >= 0 && sb.charAt(i) == '\\'; i--) {
            n++;
        }
        return n;
    }
}
