package com.editora.http;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure parser for {@code .http}/{@code .rest} request files (the JetBrains/VS Code REST format), so it is
 * unit-tested without a toolkit. A file is a sequence of <b>requests</b> separated by {@code ###} lines;
 * each request is a method+URL line, optional headers, a blank line, and an optional body. Lines starting
 * with {@code #}/{@code //} are comments and {@code @name = value} lines are variable declarations.
 *
 * <p>Used to draw a gutter ▶ on each request's start line and to <b>extract</b> a single request (plus the
 * file's {@code @variable} preamble) into a temp file that {@code ijhttp} can run on its own — since
 * {@code ijhttp} only executes whole files.
 */
public final class HttpFile {

    private static final Set<String> METHODS =
            Set.of("GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS", "TRACE", "CONNECT");

    private HttpFile() {}

    /**
     * One request: its 0-based {@code startLine} (the method/URL line — where the ▶ goes) through
     * {@code endLine} (the last non-blank line of its section), the {@code method} (defaulting to
     * {@code GET} when omitted), the raw {@code url} (may contain {@code {{vars}}}), an optional
     * {@code name} (from a {@code ### name} separator or a {@code # @name x} comment), and the raw
     * {@code block} text (the request, from start to end line).
     */
    public record Request(int startLine, int endLine, String method, String url, String name, String block) {}

    /** Parses {@code text} into its requests, in document order. */
    public static List<Request> parse(String text) {
        List<Request> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String[] lines = text.split("\n", -1);
        int n = lines.length;
        int i = 0;
        while (i < n) {
            String name = null;
            if (isSeparator(lines[i])) {
                name = separatorName(lines[i]);
                i++;
            }
            int sectionEnd = i;
            while (sectionEnd < n && !isSeparator(lines[sectionEnd])) {
                sectionEnd++;
            }
            int reqLine = -1;
            for (int k = i; k < sectionEnd; k++) {
                String t = lines[k].strip();
                if (t.isEmpty()) {
                    continue;
                }
                if (isComment(t)) {
                    String cn = commentName(t);
                    if (cn != null && name == null) {
                        name = cn;
                    }
                    continue;
                }
                if (isVarDecl(t)) {
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
                String[] mu = methodUrl(lines[reqLine]);
                out.add(new Request(reqLine, last, mu[0], mu[1], name, join(lines, reqLine, last)));
            }
            i = sectionEnd;
        }
        return out;
    }

    /** The index of the request whose {@code [startLine, endLine]} span contains {@code line}, else -1. */
    public static int requestIndexAt(String text, int line) {
        List<Request> requests = parse(text);
        for (int idx = 0; idx < requests.size(); idx++) {
            Request r = requests.get(idx);
            if (line >= r.startLine() && line <= r.endLine()) {
                return idx;
            }
        }
        return -1;
    }

    /** A single request broken into its parts for the built-in executor. {@code headers} is a list of
     *  {@code [name, value]}; {@code url}/{@code value}s/{@code body} may still contain {@code {{vars}}}. */
    public record Parsed(String method, String url, List<String[]> headers, String body) {}

    /** Parses one request {@code block} (from {@link Request#block()}) into method/URL/headers/body. */
    public static Parsed parseRequest(String block) {
        String[] lines = (block == null ? "" : block).split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            String t = lines[i].strip();
            if (t.isEmpty() || isComment(t) || isVarDecl(t)) {
                i++;
            } else {
                break;
            }
        }
        if (i >= lines.length) {
            return new Parsed("GET", "", List.of(), "");
        }
        String[] mu = methodUrl(lines[i]);
        StringBuilder url = new StringBuilder(mu[1]);
        i++;
        // URL continuation lines (indented, typically starting with ?/& — IntelliJ splits long query strings).
        while (i < lines.length
                && !lines[i].isEmpty()
                && Character.isWhitespace(lines[i].charAt(0))
                && !lines[i].strip().isEmpty()) {
            url.append(lines[i].strip());
            i++;
        }
        List<String[]> headers = new ArrayList<>();
        for (; i < lines.length && !lines[i].strip().isEmpty(); i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.add(new String[] {
                    lines[i].substring(0, colon).strip(),
                    lines[i].substring(colon + 1).strip()
                });
            }
        }
        while (i < lines.length && lines[i].strip().isEmpty()) {
            i++; // skip the blank line between headers and body
        }
        StringBuilder body = new StringBuilder();
        for (; i < lines.length; i++) {
            String t = lines[i].stripLeading();
            if (t.startsWith(">") || t.startsWith("<>")) {
                break; // a response-handler / redirect line ends the body (not executed in v1)
            }
            if (body.length() > 0) {
                body.append('\n');
            }
            body.append(lines[i]);
        }
        return new Parsed(mu[0], url.toString(), headers, body.toString().strip());
    }

    /** Every {@code @name = value} declaration as {@code [name, rawValue]} pairs, in order. */
    public static List<String[]> fileVariablePairs(String text) {
        List<String[]> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\n", -1)) {
            String s = line.strip();
            if (isVarDecl(s)) {
                int eq = s.indexOf('=');
                out.add(new String[] {
                    s.substring(1, eq).strip(), s.substring(eq + 1).strip()
                });
            }
        }
        return out;
    }

    /** The file's variable preamble: every {@code @name = value} line, in order, joined by newlines. */
    public static String fileVariables(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (isVarDecl(line.strip())) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(line.strip());
            }
        }
        return sb.toString();
    }

    /**
     * The temp-file content for running request {@code index} on its own: the file's {@code @variable}
     * preamble (so {@code {{vars}}} still resolve) followed by that request's block, or {@code null} when
     * the index is out of range.
     */
    public static String extract(String text, int index) {
        List<Request> requests = parse(text);
        if (index < 0 || index >= requests.size()) {
            return null;
        }
        String vars = fileVariables(text);
        String block = requests.get(index).block();
        return vars.isEmpty() ? block : vars + "\n\n" + block;
    }

    // --- helpers ----------------------------------------------------------------------------------

    private static boolean isSeparator(String line) {
        return line.stripLeading().startsWith("###");
    }

    private static String separatorName(String line) {
        String s = line.stripLeading();
        s = s.substring(3); // drop "###"
        // trim any extra leading '#' and surrounding whitespace
        while (s.startsWith("#")) {
            s = s.substring(1);
        }
        return s.strip();
    }

    private static boolean isComment(String stripped) {
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

    private static boolean isVarDecl(String stripped) {
        if (!stripped.startsWith("@")) {
            return false;
        }
        int eq = stripped.indexOf('=');
        return eq > 1; // "@x = ..." (at least one name char before '=')
    }

    /** Splits a request line into {@code [method, url]}; method defaults to GET, a trailing HTTP/x is dropped. */
    static String[] methodUrl(String line) {
        String s = line.strip();
        int sp = s.indexOf(' ');
        String first = sp < 0 ? s : s.substring(0, sp);
        if (sp > 0 && METHODS.contains(first)) {
            String rest = s.substring(sp + 1).strip();
            return new String[] {first, dropHttpVersion(rest)};
        }
        return new String[] {"GET", dropHttpVersion(s)};
    }

    private static String dropHttpVersion(String url) {
        int v = url.lastIndexOf(" HTTP/");
        return v >= 0 ? url.substring(0, v).strip() : url;
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
