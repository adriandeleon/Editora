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
            "--url-query",
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
        final java.util.Set<String> removed = new java.util.HashSet<>(); // -H 'Name:': headers curl must not add
    }

    /** Builds a {@code METHOD url} + headers + body request block from a {@code curl} command. */
    public static String toHttpRequest(String curl) {
        List<String> tokens = tokenize(curl);
        Command c = new Command();
        boolean optionsDone = false;
        for (int i = 0; i < tokens.size(); i++) {
            String t = tokens.get(i);
            if (i == 0 && t.equals("curl")) {
                continue;
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
        return render(c);
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
                    "--url" -> true;
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
            case "--http2", "--http2-prior-knowledge" -> c.version = "HTTP/2";
            default -> {} // -L, -k, -s, --compressed, … : nothing to carry over
        }
    }

    /** An option with its argument. */
    private static void apply(Command c, String opt, String v) {
        switch (opt) {
            case "-X", "--request" -> c.method = v;
            case "-H", "--header" -> {
                // "Name:" with nothing after it removes a header curl would add (Copy as cURL writes
                // -H 'Content-Type:' for a body sent without one): nothing to send.
                if (v.matches("[^:;@]+:\\s*")) {
                    c.removed.add(v.substring(0, v.indexOf(':')).strip().toLowerCase(java.util.Locale.ROOT));
                } else if (!v.startsWith("@")) {
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
            sb.append('\n').append(body).append('\n');
        }
        return sb.toString();
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
            if (!literal && (value.startsWith("@") || value.startsWith("<"))) {
                fileAsText = value.startsWith("<"); // name=<file: the file's content as a plain field
                // The file may be double-quoted (curl's -F syntax, and Editora's own Copy as cURL): then it may
                // hold ';' and '\"', and the attributes start after the closing quote.
                String rest = value.substring(1);
                if (rest.startsWith("\"")) {
                    int close = closingQuote(rest);
                    file = curlUnescape(rest.substring(1, close < 0 ? rest.length() : close));
                    rest = close < 0 ? "" : rest.substring(close + 1);
                } else {
                    int semi = rest.indexOf(';');
                    file = semi < 0 ? rest : rest.substring(0, semi);
                    rest = semi < 0 ? "" : rest.substring(semi);
                }
                String[] attrs = (";" + rest).split(";");
                for (int k = 1; k < attrs.length; k++) {
                    String a = attrs[k].strip();
                    if (a.startsWith("type=")) {
                        type = a.substring(5);
                    } else if (a.startsWith("filename=")) {
                        filename = unquote(a.substring(9));
                    }
                }
                if (!fileAsText && filename == null) {
                    int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
                    filename = file.substring(slash + 1);
                }
            } else if (!literal) {
                // A text value with attributes: -F 'name="value";type=…;filename="…"' (curl's syntax, and Editora's
                // own Copy as cURL for a typed or named inline part), or unquoted -F 'name=value;type=…'.
                String rest;
                if (value.startsWith("\"")) {
                    int close = closingQuote(value);
                    rest = close < 0 ? "" : value.substring(close + 1);
                    value = curlUnescape(value.substring(1, close < 0 ? value.length() : close));
                } else {
                    int semi = value.indexOf(';');
                    String after = semi < 0 ? "" : value.substring(semi + 1).strip();
                    boolean attrs = after.startsWith("type=") || after.startsWith("filename=");
                    rest = attrs ? value.substring(semi) : "";
                    value = attrs ? value.substring(0, semi) : value;
                }
                for (String a : rest.split(";")) {
                    String attr = a.strip();
                    if (attr.startsWith("type=")) {
                        type = attr.substring(5);
                    } else if (attr.startsWith("filename=")) {
                        filename = unquote(attr.substring(9));
                    }
                }
            }
            sb.append("--").append(b).append('\n');
            sb.append("Content-Disposition: form-data; name=\"").append(name).append('"');
            if (filename != null) {
                sb.append("; filename=\"").append(filename).append('"');
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

    /** Removes {@code cmd}'s carets: {@code ^} + newline continues the line, {@code ^x} is a literal {@code x}. */
    private static String fromCmd(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '^' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '\r' || next == '\n') {
                    i++;
                    if (next == '\r' && i + 1 < s.length() && s.charAt(i + 1) == '\n') {
                        i++;
                    }
                    sb.append(' ');
                    continue;
                }
                sb.append(next);
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
