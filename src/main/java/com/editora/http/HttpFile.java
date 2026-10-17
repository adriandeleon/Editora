package com.editora.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.editora.i18n.Messages.tr;

/**
 * Pure parser for {@code .http}/{@code .rest} request files (the JetBrains/VS Code REST format), so it is
 * unit-tested without a toolkit. A file is a sequence of <b>sections</b> separated by {@code ###} lines; each
 * section holds at most one request: a method+URL line, optional headers, a blank line, and an optional body.
 * Lines starting with {@code #}/{@code //} are comments — above the request, among the headers, and inside an
 * inline body (the JetBrains spec: a body comment starts the line, with or without indent) — and
 * {@code @name = value} lines above a request are variable declarations.
 *
 * <p>Beyond the basics it recognizes the IntelliJ extensions used by the executor: an external body
 * reference ({@code < ./file} raw, {@code <@ ./file} substituted), response-redirect operators
 * ({@code >> file} / {@code >>! file} force), and per-request comment directives above the request line
 * ({@code # @no-redirect}, {@code # @no-cookie-jar}, {@code # @no-log}, {@code # @no-auto-encoding},
 * {@code # @timeout 2 m}, {@code # @connection-timeout 500 ms}, {@code # @name x}), and the response checks
 * {@code # @assert …} / {@code # @capture …} ({@link HttpChecks}). JavaScript response handlers
 * ({@code > {% … %}} / {@code > file.js}) are read into {@link Parsed#handlers()}, and pre-request scripts
 * ({@code < {% … %}} / {@code < file.js} above the request line) into {@link Parsed#preScripts()}, for
 * {@link ResponseScripts} to run.
 */
public final class HttpFile {

    /** The standard methods; any other letters-only first word followed by a target is a custom method. */
    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT");

    /** JetBrains request kinds that are not plain HTTP and that this client cannot send. */
    private static final Set<String> UNSUPPORTED_KINDS = Set.of("GRAPHQL", "WEBSOCKET", "GRPC");

    /** A trailing {@code HTTP/1.1}, {@code HTTP/2}, {@code HTTP/2 (Prior Knowledge)} on the request target. */
    private static final Pattern VERSION = Pattern.compile(
            "\\s+(HTTP/(\\d+)(?:\\.(\\d+))?(\\s*\\(\\s*prior\\s+knowledge\\s*\\))?)\\s*$", Pattern.CASE_INSENSITIVE);

    private static final Pattern DURATION = Pattern.compile("(\\d{1,12})\\s*(ms|s|m)?", Pattern.CASE_INSENSITIVE);

    private HttpFile() {}

    /**
     * Per-request comment directives, read only from the comment lines above the request line; a {@code 0}
     * timeout means "use the default". Timeouts are in milliseconds. {@code checks} are the {@code @assert} and
     * {@code @capture} lines, in order.
     */
    public record Directives(
            boolean noRedirect,
            boolean noCookieJar,
            boolean noLog,
            boolean noAutoEncoding,
            long timeoutMillis,
            long connectionTimeoutMillis,
            List<HttpChecks.Check> checks) {
        public static final Directives NONE = new Directives(false, false, false, false, 0, 0, List.of());

        public Directives {
            checks = checks == null ? List.of() : List.copyOf(checks);
        }
    }

    /**
     * A JavaScript response handler: inline ({@code > {% … %}}, {@code source} set, {@code path} null) or a file
     * ({@code > ./check.js}, {@code path} as written, relative to the request file). {@code line} is the 0-based line
     * the handler starts on — in the file for a request from {@link #parse}, in the block otherwise — and
     * {@code column} the column its inline source starts at (just after {@code {%}), so an error in the script can
     * be placed in the {@code .http} file. {@code closed} is false for an inline script without its {@code %}}.
     */
    public record Handler(String source, String path, int line, int column, boolean closed) {

        public boolean isFile() {
            return path != null;
        }

        Handler shifted(int lines) {
            return new Handler(source, path, line + lines, column, closed);
        }
    }

    /** An external request body: the {@code path} (relative to the request file), whether to substitute
     *  {@code {{vars}}} in it ({@code <@} vs {@code <}), and an optional {@code encoding}. */
    public record BodyRef(String path, boolean substitute, String encoding) {}

    /** A response-redirect operator: write the response body to {@code path} (which may contain
     *  {@code {{vars}}}); {@code force} ({@code >>!}) overwrites an existing file, plain {@code >>} does not. */
    public record Redirect(String path, boolean force) {}

    /**
     * One request: its 0-based {@code startLine} (the method/URL line — where the ▶ goes) through
     * {@code endLine} (the last non-blank line of its section), the {@code method} (defaulting to
     * {@code GET} when omitted), the raw {@code url} (may contain {@code {{vars}}}), an optional
     * {@code name} (a {@code # @name x} comment, else the {@code ### name} separator label), the per-request
     * {@code directives}, and the raw {@code block} text (the request, from start to end line).
     *
     * <p>{@code variables} are the {@code @name = value} declarations in effect for this request — those above
     * it, in document order, outside any request body — so a later redeclaration applies from there on.
     * {@code sectionStart}/{@code sectionEnd} span the request's whole section (its {@code ###} line through the
     * line before the next separator), which is what a caret maps to. {@code warnings} are problems found in
     * the lines above the request line (an unreadable timeout, an unclosed script). {@code preScripts} are its
     * pre-request scripts, in order, with their lines in the file.
     */
    public record Request(
            int startLine,
            int endLine,
            String method,
            String url,
            String name,
            Directives directives,
            String block,
            List<String[]> variables,
            int sectionStart,
            int sectionEnd,
            List<String> warnings,
            List<Handler> preScripts) {

        public Request {
            variables = variables == null ? List.of() : List.copyOf(variables);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            preScripts = preScripts == null ? List.of() : List.copyOf(preScripts);
        }
    }

    /**
     * A single request broken into its parts for the built-in executor. {@code headers} is a list of
     * {@code [name, value]}; {@code url}/{@code value}s/{@code body} may still contain {@code {{vars}}}. The
     * {@code name}/{@code directives}/{@code variables} are carried from the {@link Request} (only via
     * {@link #parseRequest(Request)}); {@code bodyRef} is non-null for an external body; {@code redirects}
     * holds any {@code >>}/{@code >>!} targets.
     *
     * <p>{@code warnings} lists the parse problems, in order. {@code httpVersion} is the request line's version — {@code null}, {@code HTTP/1.1}, {@code HTTP/2} or
     * {@code HTTP/2 (Prior Knowledge)} (anything else is kept as written, e.g. {@code HTTP/1.0}); it is never
     * part of {@code url}. A non-null {@code error} means the request cannot be sent at all (a GraphQL,
     * WebSocket or gRPC request).
     */
    public record Parsed(
            String method,
            String url,
            List<String[]> headers,
            String body,
            String name,
            BodyRef bodyRef,
            List<Redirect> redirects,
            Directives directives,
            String httpVersion,
            List<String[]> variables,
            List<String> warnings,
            String error,
            List<Handler> handlers,
            List<Handler> preScripts) {

        public Parsed {
            variables = variables == null ? List.of() : List.copyOf(variables);
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
            handlers = handlers == null ? List.of() : List.copyOf(handlers);
            preScripts = preScripts == null ? List.of() : List.copyOf(preScripts);
        }

        /**
         * This request with {@code pairs} — values a pre-request script set with {@code request.variables.set} —
         * after its own {@code @name = value} lines, so they win over every other variable. Each value is written
         * escaped ({@link HttpVars#escape}): it is inserted as is, never resolved, so a script cannot make the request
         * read a secret.
         */
        public Parsed withScriptVariables(java.util.Map<String, String> pairs) {
            if (pairs == null || pairs.isEmpty()) {
                return this;
            }
            List<String[]> vars = new ArrayList<>(variables);
            // The third element marks a script's value, so a host made of it is "chosen by a script" (sessionReads).
            pairs.forEach((k, v) -> vars.add(new String[] {k, HttpVars.escape(v == null ? "" : v), "script"}));
            return new Parsed(
                    method,
                    url,
                    headers,
                    body,
                    name,
                    bodyRef,
                    redirects,
                    directives,
                    httpVersion,
                    vars,
                    warnings,
                    error,
                    handlers,
                    preScripts);
        }

        /**
         * This request with the session's global headers ({@code client.global.headers}) that it does not set itself,
         * added after its own; each value escaped, so it is sent as is.
         */
        public Parsed withGlobalHeaders(java.util.Map<String, String> globalHeaders) {
            if (globalHeaders == null || globalHeaders.isEmpty()) {
                return this;
            }
            List<String[]> all = new ArrayList<>();
            for (String[] h : headers) {
                all.add(h);
            }
            globalHeaders.forEach((name, value) -> {
                boolean own = headers.stream().anyMatch(h -> h[0] != null && h[0].equalsIgnoreCase(name));
                if (!own) {
                    all.add(new String[] {name, HttpVars.escape(value == null ? "" : value)});
                }
            });
            return new Parsed(
                    method,
                    url,
                    all,
                    body,
                    name,
                    bodyRef,
                    redirects,
                    directives,
                    httpVersion,
                    variables,
                    warnings,
                    error,
                    handlers,
                    preScripts);
        }

        /** The {@code @assert}/{@code @capture} checks of this request. */
        public List<HttpChecks.Check> checks() {
            return directives == null ? List.of() : directives.checks();
        }

        /**
         * This request with {@code first} put before its own warnings — an env file that could not be read is named
         * above the "was sent as empty text" lines it causes. Itself when {@code first} is empty.
         */
        public Parsed withWarningsFirst(List<String> first) {
            if (first == null || first.isEmpty()) {
                return this;
            }
            List<String> all = new ArrayList<>(first);
            all.addAll(warnings);
            return new Parsed(
                    method,
                    url,
                    headers,
                    body,
                    name,
                    bodyRef,
                    redirects,
                    directives,
                    httpVersion,
                    variables,
                    all,
                    error,
                    handlers,
                    preScripts);
        }
    }

    /** What one pass over a file finds: its requests and every declaration outside a body, in order. */
    private record Scan(List<Request> requests, List<String[]> declarations) {}

    /** Parses {@code text} into its requests, in document order. */
    public static List<Request> parse(String text) {
        return scan(text).requests();
    }

    private static Scan scan(String text) {
        List<Request> out = new ArrayList<>();
        List<String[]> declared = new ArrayList<>(); // every declaration so far, in document order
        if (text == null || text.isEmpty()) {
            return new Scan(out, declared);
        }
        String[] lines = text.split("\n", -1);
        int n = lines.length;
        int i = 0;
        while (i < n) {
            int sectionStart = i;
            String name = null;
            if (isSeparator(lines[i])) {
                name = separatorName(lines[i]);
                i++;
            }
            int sectionEnd = i;
            while (sectionEnd < n && !isSeparator(lines[sectionEnd])) {
                sectionEnd++;
            }
            String referenceName = null;
            int reqLine = -1;
            List<String> warnings = new ArrayList<>();
            List<Handler> preScripts = new ArrayList<>();
            for (int k = i; k < sectionEnd; k++) {
                String t = lines[k].strip();
                if (t.isEmpty()) {
                    continue;
                }
                if (isComment(t)) {
                    String cn = commentName(t);
                    if (cn != null && referenceName == null) {
                        referenceName = cn;
                    }
                    continue;
                }
                if (isVarDecl(t)) {
                    declared.add(varPair(t));
                    continue;
                }
                if (isPreRequestScript(t)) {
                    // "< {% … %}" or "< ./script.js" above the request: JavaScript run before the request.
                    int end = scriptEnd(lines, k, sectionEnd, t); // unclosed: the rest of the section
                    preScripts.add(handler(lines, k, end));
                    k = end;
                    continue;
                }
                reqLine = k;
                break;
            }
            if (reqLine >= 0) {
                int last = sectionEnd - 1;
                while (last > reqLine && lines[last].strip().isEmpty()) {
                    last--;
                }
                if (referenceName != null) {
                    name = referenceName; // @name is what {{name.response…}} refers to, whatever the title says
                }
                RequestLine rl = requestLine(lines[reqLine]);
                Directives directives = directivesOf(lines, i, reqLine, warnings);
                out.add(new Request(
                        reqLine,
                        last,
                        rl.method(),
                        rl.url(),
                        name,
                        directives,
                        join(lines, reqLine, last),
                        declared,
                        sectionStart,
                        sectionEnd - 1,
                        warnings,
                        preScripts));
            }
            i = sectionEnd;
        }
        return new Scan(out, declared);
    }

    /**
     * The index of the request whose section contains {@code line} — from its {@code ###} line (so its title,
     * {@code # @name} and directive lines count) through the line before the next separator — else -1.
     */
    public static int requestIndexAt(String text, int line) {
        List<Request> requests = parse(text);
        for (int idx = 0; idx < requests.size(); idx++) {
            Request r = requests.get(idx);
            if (line >= r.sectionStart() && line <= r.sectionEnd()) {
                return idx;
            }
        }
        return -1;
    }

    /** Parses one request {@code block} (from {@link Request#block()}) into method/URL/headers/body, plus an
     *  external-body reference + redirect operators. The {@code name}/{@code directives} default; use
     *  {@link #parseRequest(Request)} to carry those from the enclosing request. */
    public static Parsed parseRequest(String block) {
        String[] lines = (block == null ? "" : block).split("\n", -1);
        List<String> warnings = new ArrayList<>();
        List<Handler> preScripts = new ArrayList<>();
        int i = 0;
        while (i < lines.length) {
            String t = lines[i].strip();
            if (t.isEmpty() || isComment(t) || isVarDecl(t)) {
                i++;
            } else if (isPreRequestScript(t)) {
                int end = scriptEnd(lines, i, lines.length, t);
                preScripts.add(handler(lines, i, end));
                i = end + 1;
            } else {
                break;
            }
        }
        if (i >= lines.length) {
            return new Parsed(
                    "GET",
                    "",
                    List.of(),
                    "",
                    null,
                    null,
                    List.of(),
                    Directives.NONE,
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    preScripts);
        }
        // URL continuation lines: indented, and not shaped like a header ("  ?a=1", "  &b=2", "  /api" — IntelliJ
        // splits long targets this way). An indented "Accept: x" right after the request line is a header.
        StringBuilder target = new StringBuilder(lines[i].strip());
        i++;
        while (i < lines.length && isUrlContinuation(lines[i])) {
            target.append(lines[i].strip());
            i++;
        }
        RequestLine rl = requestLine(target.toString()); // the version may sit on the last continuation line

        List<String[]> headers = new ArrayList<>();
        String malformed = null;
        boolean trailerStarted = false;
        for (; i < lines.length && !lines[i].strip().isEmpty(); i++) {
            String t = lines[i].strip();
            if (isComment(t)) {
                continue; // a `# …` / `// …` line between headers is a comment, not a header named "#Authorization"
            }
            if (isTrailerLine(t)) {
                trailerStarted = true; // a handler/redirect right after the headers: the request has no body
                break;
            }
            int colon = lines[i].indexOf(':');
            boolean headerShaped =
                    colon > 0 && isHeaderFieldName(lines[i].substring(0, colon).strip());
            if (!headerShaped && Character.isWhitespace(lines[i].charAt(0)) && !headers.isEmpty()) {
                // A header continuation line: the spec lets a value go on, indented, on the next line.
                String[] prev = headers.get(headers.size() - 1);
                prev[1] = prev[1].isEmpty() ? t : prev[1] + " " + t;
            } else if (headerShaped) {
                headers.add(new String[] {
                    lines[i].substring(0, colon).strip(),
                    lines[i].substring(colon + 1).strip()
                });
            } else if (malformed == null) {
                // A non-blank line in the header block that isn't a valid `Name: value` — almost always a
                // request body written without the required blank line before it (a common typo). Per the
                // JetBrains .http convention we still send no body (a colon-bearing body line like `{"a": 1}`
                // would otherwise be parsed as a bogus header); surface a warning so the empty request doesn't
                // look like a server problem, rather than guessing where the body starts.
                malformed = tr("http.warn.noBlankLine", t);
            }
        }
        if (malformed != null) {
            warnings.add(0, malformed);
        }
        if (!trailerStarted) {
            while (i < lines.length && lines[i].strip().isEmpty()) {
                i++; // skip the blank line between headers and body
            }
        }
        // An external-body reference (< ./file raw, <@ ./file substituted) replaces an inline body. A body that
        // merely STARTS with '<' — XML, HTML, a SOAP envelope — is inline text (see isBodyFileRef).
        BodyRef bodyRef = null;
        if (!trailerStarted && i < lines.length) {
            String t = lines[i].stripLeading();
            if (isBodyFileRef(t)) {
                bodyRef = parseBodyRef(t);
                i++;
            }
        }
        StringBuilder body = new StringBuilder();
        List<Redirect> redirects = new ArrayList<>();
        List<Handler> handlers = new ArrayList<>();
        boolean bodyDone = trailerStarted;
        for (; i < lines.length; i++) {
            String t = lines[i].strip();
            if (isRedirect(t)) {
                bodyDone = true; // a response-redirect operator (>> file / >>! file)
                redirects.add(parseRedirect(t));
                continue;
            }
            if (isHandler(t)) {
                bodyDone = true; // a response handler ends the body
                int end = scriptEnd(lines, i, lines.length, t);
                Handler h = handler(lines, i, end);
                if (h.source() != null && !h.closed()) {
                    warnings.add(tr("http.warn.handlerUnclosed"));
                }
                handlers.add(h);
                i = end; // a multi-line {% … %} script is read whole
                continue;
            }
            if (isResponseRef(t)) {
                bodyDone = true; // "<> ./previous.json": a response comparison reference
                continue;
            }
            if (bodyDone) {
                continue; // anything else after the end of the body is not part of it
            }
            if (isComment(t)) {
                continue; // JetBrains spec: a body line starting with # or // (indent allowed) is a comment
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        return new Parsed(
                rl.method(),
                rl.url(),
                headers,
                trimBlankLines(body.toString()),
                null,
                bodyRef,
                redirects,
                Directives.NONE,
                rl.version(),
                List.of(),
                warnings,
                rl.error(),
                handlers,
                preScripts);
    }

    /** Like {@link #parseRequest(String)} but carries the request's {@code name}, {@code directives},
     *  {@code variables} and the warnings found above its request line. */
    public static Parsed parseRequest(Request req) {
        Parsed p = parseRequest(req.block());
        List<String> warnings = new ArrayList<>(req.warnings());
        warnings.addAll(p.warnings());
        return new Parsed(
                p.method(),
                p.url(),
                p.headers(),
                p.body(),
                req.name(),
                p.bodyRef(),
                p.redirects(),
                req.directives(),
                p.httpVersion(),
                req.variables(),
                warnings,
                p.error(),
                p.handlers().stream().map(h -> h.shifted(req.startLine())).toList(),
                req.preScripts());
    }

    /**
     * {@code body} without its leading and trailing blank lines. Only whole blank lines go: the first line keeps its
     * indentation (a YAML or indented-text body would otherwise change meaning) and inner lines are untouched.
     */
    static String trimBlankLines(String body) {
        String[] lines = body.split("\n", -1);
        int first = 0;
        int last = lines.length - 1;
        while (first <= last && lines[first].isBlank()) {
            first++;
        }
        while (last >= first && lines[last].isBlank()) {
            last--;
        }
        return first > last ? "" : String.join("\n", java.util.Arrays.copyOfRange(lines, first, last + 1));
    }

    /**
     * Whether {@code name} is a valid HTTP header field-name (RFC 7230 {@code token}: one or more {@code tchar}
     * — alphanumerics or {@code !#$%&'*+-.^_`|~}). Used to tell a real header from a body line that lacked its
     * blank separator: {@code Content-Type} is a token, {@code {"a"} (from {@code {"a": 1}}) is not.
     */
    private static boolean isHeaderFieldName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int j = 0; j < name.length(); j++) {
            char c = name.charAt(j);
            boolean tchar = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!tchar) {
                return false;
            }
        }
        return true;
    }

    // --- helpers ----------------------------------------------------------------------------------

    static boolean isSeparator(String line) {
        return line.stripLeading().startsWith("###");
    }

    /**
     * The title a {@code ###} separator {@code line} gives its request ({@code ### Get one user} → "Get one user"), or
     * null when {@code line} is not a separator or names nothing. A request's {@link Request#name()} prefers its
     * {@code # @name}; this is the title as written, for what the response view shows.
     */
    public static String separatorTitle(String line) {
        return line == null || !isSeparator(line) ? null : separatorName(line);
    }

    /** The label after {@code ###}, or null for a bare separator. */
    private static String separatorName(String line) {
        String s = line.stripLeading();
        s = s.substring(3); // drop "###"
        // trim any extra leading '#' and surrounding whitespace
        while (s.startsWith("#")) {
            s = s.substring(1);
        }
        s = s.strip();
        return s.isEmpty() ? null : s;
    }

    static boolean isComment(String stripped) {
        return (stripped.startsWith("#") && !stripped.startsWith("###")) || stripped.startsWith("//");
    }

    /** A {@code # @name foo} / {@code // @name foo} comment names the next request. */
    private static String commentName(String stripped) {
        String s = stripped.startsWith("//") ? stripped.substring(2) : stripped.substring(1);
        s = s.strip();
        if (s.startsWith("@name")) {
            String rest = s.substring("@name".length()).strip();
            if (rest.startsWith("=")) {
                rest = rest.substring(1).strip();
            }
            return rest.isEmpty() ? null : rest;
        }
        return null;
    }

    /**
     * Scans the comment lines {@code [from, to)} — the lines above the request line, never its body — for the
     * per-request directives, adding a warning to {@code warnings} for a timeout it cannot read.
     */
    static Directives directivesOf(String[] lines, int from, int to, List<String> warnings) {
        boolean noRedirect = false;
        boolean noCookieJar = false;
        boolean noLog = false;
        boolean noAutoEncoding = false;
        long timeout = 0;
        long connectionTimeout = 0;
        List<HttpChecks.Check> checks = new ArrayList<>();
        for (int k = from; k < to; k++) {
            String s = lines[k].strip();
            if (!isComment(s)) {
                continue;
            }
            String c = (s.startsWith("//") ? s.substring(2) : s.substring(1)).strip();
            if (!c.startsWith("@")) {
                continue;
            }
            String tok = c.substring(1);
            String low = tok.toLowerCase(Locale.ROOT);
            if (low.startsWith("no-redirect")) {
                noRedirect = true;
            } else if (low.startsWith("no-cookie-jar")) {
                noCookieJar = true;
            } else if (low.startsWith("no-log")) {
                noLog = true;
            } else if (low.startsWith("no-auto-encoding")) {
                noAutoEncoding = true;
            } else if (low.startsWith("connection-timeout")) {
                connectionTimeout =
                        timeoutDirective("connection-timeout", tok.substring("connection-timeout".length()), warnings);
            } else if (low.startsWith("timeout")) {
                timeout = timeoutDirective("timeout", tok.substring("timeout".length()), warnings);
            } else if (isDirective(low, "assert") || isDirective(low, "capture")) {
                boolean capture = low.startsWith("capture");
                HttpChecks.Check check = HttpChecks.parse(tok.substring(capture ? 7 : 6), capture, warnings);
                if (check != null) {
                    checks.add(check);
                }
            }
        }
        return new Directives(noRedirect, noCookieJar, noLog, noAutoEncoding, timeout, connectionTimeout, checks);
    }

    /** {@code low} (a lower-cased directive without its {@code @}) is {@code word}, alone or followed by a space. */
    private static boolean isDirective(String low, String word) {
        return low.equals(word) || (low.startsWith(word) && Character.isWhitespace(low.charAt(word.length())));
    }

    private static long timeoutDirective(String directive, String value, List<String> warnings) {
        long ms = parseDurationMillis(value);
        if (ms < 0) {
            warnings.add(tr("http.warn.badTimeout", "@" + directive, value.strip()));
            return 0;
        }
        return ms;
    }

    /**
     * A JetBrains timeout value in milliseconds: {@code 600} (seconds), {@code 100 ms}, {@code 5 s}, {@code 2 m};
     * {@code -1} when it cannot be read (blank, zero, negative, or another unit).
     */
    static long parseDurationMillis(String value) {
        if (value == null) {
            return -1;
        }
        Matcher m = DURATION.matcher(value.strip());
        if (!m.matches()) {
            return -1;
        }
        long n = Long.parseLong(m.group(1));
        String unit = m.group(2) == null ? "s" : m.group(2).toLowerCase(Locale.ROOT);
        long ms = switch (unit) {
            case "ms" -> n;
            case "m" -> n * 60_000L;
            default -> n * 1000L;
        };
        return ms > 0 ? ms : -1;
    }

    /**
     * Whether {@code line} (leading whitespace already stripped) is an external-body reference. Per the
     * JetBrains grammar that is {@code <} followed by <b>whitespace</b> and a path, or {@code <@}, an optional
     * encoding, whitespace and a path. Anything else beginning with {@code <} — {@code <?xml version="1.0"?>},
     * {@code <html>}, {@code <soap:Envelope>} — is the first line of an inline body; treating it as a file
     * name sent XML/HTML/SOAP requests with no body at all.
     */
    static boolean isBodyFileRef(String line) {
        if (!line.startsWith("<")) {
            return false;
        }
        int i = 1;
        if (line.startsWith("<@")) {
            i = 2;
            while (i < line.length() && !Character.isWhitespace(line.charAt(i))) {
                i++; // the optional encoding, written right after "<@"
            }
        }
        return i < line.length()
                && Character.isWhitespace(line.charAt(i))
                && !line.substring(i).isBlank();
    }

    static BodyRef parseBodyRef(String line) {
        boolean substitute = line.startsWith("<@");
        String rest = line.substring(substitute ? 2 : 1);
        // "<@latin1 ./file": an encoding attached to "<@". The detached "<@ latin1 ./file" is also accepted
        // when the first word cannot be the start of a path.
        boolean attached = substitute && !rest.isEmpty() && !Character.isWhitespace(rest.charAt(0));
        rest = rest.strip();
        String encoding = null;
        if (substitute && (attached || (!rest.startsWith(".") && !rest.startsWith("/")))) {
            int sp = rest.indexOf(' ');
            if (sp > 0) {
                encoding = rest.substring(0, sp);
                rest = rest.substring(sp + 1).strip();
            }
        }
        return new BodyRef(rest, substitute, encoding);
    }

    /** {@code < {% … %}} or {@code < ./script.js} above a request line: a JetBrains pre-request script. */
    static boolean isPreRequestScript(String stripped) {
        if (!stripped.startsWith("<") || stripped.startsWith("<>") || stripped.startsWith("<@")) {
            return false;
        }
        String rest = stripped.substring(1).strip();
        return rest.startsWith("{%")
                || (stripped.length() > 1
                        && Character.isWhitespace(stripped.charAt(1))
                        && rest.toLowerCase(Locale.ROOT).endsWith(".js"));
    }

    /** {@code >> file} / {@code >>! file}: a response redirect. */
    private static boolean isRedirect(String stripped) {
        return stripped.startsWith(">>") && stripped.length() > 2;
    }

    /** {@code > {% … %}} or {@code > ./handler.js}: a response handler ({@code >} then whitespace or {@code {%}). */
    private static boolean isHandler(String stripped) {
        if (!stripped.startsWith(">") || stripped.startsWith(">>")) {
            return false;
        }
        String rest = stripped.substring(1);
        return rest.startsWith("{%") || (!rest.isEmpty() && Character.isWhitespace(rest.charAt(0)));
    }

    /** {@code <> ./previous-response.json}: a response reference (comparison), not part of the request. */
    private static boolean isResponseRef(String stripped) {
        return stripped.startsWith("<>") && (stripped.length() == 2 || Character.isWhitespace(stripped.charAt(2)));
    }

    /** A line that ends the request's header/body part: a handler, a redirect, or a response reference. */
    private static boolean isTrailerLine(String stripped) {
        return isRedirect(stripped) || isHandler(stripped) || isResponseRef(stripped);
    }

    /**
     * The last line of a script that starts on line {@code from} (stripped text {@code first}): an inline
     * {@code {% … %}} runs to the line holding {@code %}}; a script file reference is that one line.
     */
    private static int scriptEnd(String[] lines, int from, int to, String first) {
        int open = first.indexOf("{%");
        if (open < 0 || first.indexOf("%}", open + 2) >= 0) {
            return from;
        }
        for (int k = from + 1; k < to; k++) {
            if (lines[k].contains("%}")) {
                return k;
            }
        }
        return to - 1; // unterminated: the rest of the section is the script
    }

    /**
     * The handler on lines {@code [from, end]}: an inline {@code {% … %}} script with its source (the text between
     * the markers, line breaks kept so its line numbers match the file's), or a file reference.
     */
    private static Handler handler(String[] lines, int from, int end) {
        String first = lines[from];
        int open = first.indexOf("{%");
        if (open < 0) {
            String path = first.strip().substring(1).strip();
            return new Handler(null, path, from, 0, true);
        }
        int column = open + 2;
        int close = first.indexOf("%}", column);
        if (close >= 0) {
            return new Handler(first.substring(column, close), null, from, column, true);
        }
        StringBuilder src = new StringBuilder(first.substring(column));
        for (int k = from + 1; k <= end; k++) {
            src.append('\n');
            int c = lines[k].indexOf("%}");
            if (k == end && c >= 0) {
                src.append(lines[k], 0, c);
                return new Handler(src.toString(), null, from, column, true);
            }
            src.append(lines[k]);
        }
        return new Handler(src.toString(), null, from, column, false);
    }

    /** An indented line right after the request line that continues the URL rather than starting the headers. */
    private static boolean isUrlContinuation(String line) {
        if (line.isEmpty() || !Character.isWhitespace(line.charAt(0))) {
            return false;
        }
        String t = line.strip();
        if (t.isEmpty() || isComment(t) || isTrailerLine(t)) {
            return false;
        }
        int colon = t.indexOf(':');
        return !(colon > 0 && isHeaderFieldName(t.substring(0, colon).strip()));
    }

    private static Redirect parseRedirect(String line) {
        boolean force = line.startsWith(">>!");
        return new Redirect(line.substring(force ? 3 : 2).strip(), force);
    }

    static boolean isVarDecl(String stripped) {
        if (!stripped.startsWith("@")) {
            return false;
        }
        int eq = stripped.indexOf('=');
        return eq > 1; // "@x = ..." (at least one name char before '=')
    }

    static String[] varPair(String stripped) {
        int eq = stripped.indexOf('=');
        return new String[] {
            stripped.substring(1, eq).strip(), stripped.substring(eq + 1).strip()
        };
    }

    /** A request line split into its method, target and version, plus a reason it cannot be sent. */
    record RequestLine(String method, String url, String version, String error) {}

    /**
     * Splits a request line into method, target and version. The method defaults to {@code GET}; a known
     * method is accepted in any case and upper-cased, and any other letters-only first word followed by a
     * target is a custom method ({@code PROPFIND}, {@code QUERY}). {@code GRAPHQL}, {@code WEBSOCKET} and
     * {@code GRPC} are recognized and reported as unsupported. Words are split on any whitespace.
     */
    static RequestLine requestLine(String line) {
        String s = line.strip();
        String version = null;
        Matcher v = VERSION.matcher(s);
        if (v.find() && v.start() > 0) {
            version = normalizeVersion(v);
            s = s.substring(0, v.start()).strip();
        }
        int sp = firstWhitespace(s);
        if (sp > 0) {
            String word = s.substring(0, sp);
            String upper = word.toUpperCase(Locale.ROOT);
            String rest = s.substring(sp).strip();
            if (METHODS.contains(upper) || UNSUPPORTED_KINDS.contains(upper) || isMethodToken(word)) {
                String error = UNSUPPORTED_KINDS.contains(upper) ? tr("http.error.unsupportedKind", upper) : null;
                return new RequestLine(upper, rest, version, error);
            }
        }
        return new RequestLine("GET", s, version, null);
    }

    private static String normalizeVersion(Matcher v) {
        String major = v.group(2);
        String minor = v.group(3);
        boolean prior = v.group(4) != null;
        if ("2".equals(major) && (minor == null || "0".equals(minor))) {
            return prior ? "HTTP/2 (Prior Knowledge)" : "HTTP/2";
        }
        return "HTTP/" + major + (minor == null ? "" : "." + minor);
    }

    private static int firstWhitespace(String s) {
        for (int k = 0; k < s.length(); k++) {
            if (Character.isWhitespace(s.charAt(k))) {
                return k;
            }
        }
        return -1;
    }

    /** A custom method: letters (and {@code -}/{@code _}) only — a URL's first word has {@code :}, {@code /},
     *  {@code .} or braces. */
    private static boolean isMethodToken(String word) {
        if (word.isEmpty() || !Character.isLetter(word.charAt(0))) {
            return false;
        }
        for (int k = 0; k < word.length(); k++) {
            char c = word.charAt(k);
            if (!((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '-' || c == '_')) {
                return false;
            }
        }
        return true;
    }

    private static String join(String[] lines, int from, int to) {
        StringBuilder sb = new StringBuilder();
        for (int k = from; k <= to; k++) {
            if (k > from) {
                sb.append('\n');
            }
            sb.append(lines[k]);
        }
        return sb.toString();
    }
}
