package com.editora.http;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import static com.editora.i18n.Messages.tr;

/**
 * The response checks a {@code .http} request can declare without JavaScript, in comment directives above its request
 * line: {@code # @assert <subject> <operator> [value]} and {@code # @capture <name> = <subject>}. They are evaluated
 * in Java after the response arrives, so they need neither Node.js nor a trusted folder: they run no code, and a
 * value is only ever compared or stored, never resolved.
 *
 * <p>A subject is {@code status}, {@code time} (milliseconds), {@code body}, {@code header <Name>} or a JSONPath into
 * the body ({@code $.items[0].id}, see {@link JsonPath}). Operators: {@code ==}, {@code !=}, {@code <}, {@code <=},
 * {@code >}, {@code >=} (numbers when both sides are numbers, else text), {@code contains} (text, or an element of an
 * array), {@code matches} (a regular expression found anywhere; anchor it with {@code ^…$}), {@code exists} and
 * {@code !exists} (no value). A value is a literal — {@code 200}, {@code true}, {@code null}, {@code "quoted text"} or
 * bare text — never a {@code {{variable}}}: a check cannot read a secret.
 *
 * <p>Each {@code @assert} becomes one test of the response, named by its text; a {@code @capture} stores what it reads
 * as a session variable ({@link CapturedResponses#setGlobal}), which later requests use as {@code {{name}}}. Pure apart
 * from the regex deadline, so it is unit-tested.
 */
public final class HttpChecks {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** A capture name: the shape of a {@code {{variable}}} name. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_.-]*");

    /** How long one {@code matches} may run against a response before it is stopped (a runaway pattern). */
    static final long REGEX_DEADLINE_MILLIS = 2000;

    private HttpChecks() {}

    /** What a subject reads from the response. */
    public enum Subject {
        STATUS,
        TIME,
        BODY,
        HEADER,
        JSON
    }

    /** A comparison. {@code EXISTS}/{@code NOT_EXISTS} take no value. */
    public enum Op {
        EQ("=="),
        NE("!="),
        LT("<"),
        LE("<="),
        GT(">"),
        GE(">="),
        CONTAINS("contains"),
        MATCHES("matches"),
        EXISTS("exists"),
        NOT_EXISTS("!exists");

        private final String token;

        Op(String token) {
            this.token = token;
        }

        public String token() {
            return token;
        }

        boolean unary() {
            return this == EXISTS || this == NOT_EXISTS;
        }
    }

    /**
     * One check, as written in {@code text} (the directive without its {@code #}/{@code @assert}): an assertion when
     * {@code captureName} is null, else a capture into that name. {@code arg} is the header name or the JSONPath;
     * {@code expected} is the literal (null for a unary operator or a capture).
     */
    public record Check(String text, Subject subject, String arg, Op op, String expected, String captureName) {

        public boolean isCapture() {
            return captureName != null;
        }
    }

    /** The outcome of a request's checks: one test per assertion, the captured values (null: clear it), warnings. */
    public record Outcome(List<HttpTests.Test> tests, Map<String, String> captures, List<String> warnings) {}

    // --- parsing --------------------------------------------------------------------------------------------------

    /**
     * Parses the directive {@code body} — what follows {@code @assert} or {@code @capture} (per {@code capture}) — into
     * a check, or adds a warning naming the problem to {@code warnings} and returns null.
     */
    static Check parse(String body, boolean capture, List<String> warnings) {
        String directive = capture ? "@capture" : "@assert";
        String text = body == null ? "" : body.strip();
        try {
            return capture ? parseCapture(text) : parseAssert(text);
        } catch (IllegalArgumentException bad) {
            warnings.add(tr("http.warn.badCheck", directive + (text.isEmpty() ? "" : " " + text), bad.getMessage()));
            return null;
        }
    }

    private static Check parseAssert(String text) {
        if (text.isEmpty()) {
            throw new IllegalArgumentException(tr("http.check.error.empty"));
        }
        Scanner s = new Scanner(text);
        Subject subject = s.subject();
        String arg = s.arg;
        s.skipSpaces();
        Op op = s.op();
        String expected = null;
        s.skipSpaces();
        String rest = s.rest();
        if (op.unary()) {
            if (!rest.isEmpty()) {
                throw new IllegalArgumentException(tr("http.check.error.extra", rest));
            }
        } else {
            if (rest.isEmpty()) {
                throw new IllegalArgumentException(tr("http.check.error.noValue", op.token()));
            }
            expected = rest;
            if (op == Op.MATCHES) {
                try {
                    Pattern.compile(literalText(expected));
                } catch (PatternSyntaxException e) {
                    throw new IllegalArgumentException(tr("http.check.error.regex", e.getDescription()));
                }
            }
        }
        return new Check(text, subject, arg, op, expected, null);
    }

    private static Check parseCapture(String text) {
        int eq = text.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException(tr("http.check.error.captureShape"));
        }
        String name = text.substring(0, eq).strip();
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(tr("http.check.error.captureName", name));
        }
        Scanner s = new Scanner(text.substring(eq + 1).strip());
        Subject subject = s.subject();
        if (subject == Subject.TIME) {
            throw new IllegalArgumentException(tr("http.check.error.captureSubject", "time"));
        }
        s.skipSpaces();
        String rest = s.rest();
        if (!rest.isEmpty()) {
            throw new IllegalArgumentException(tr("http.check.error.extra", rest));
        }
        return new Check(text, subject, s.arg, null, null, name);
    }

    /** Reads a check left to right. */
    private static final class Scanner {
        private final String s;
        private int i;
        private String arg;

        Scanner(String s) {
            this.s = s;
        }

        void skipSpaces() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        String word() {
            int start = i;
            while (i < s.length() && !Character.isWhitespace(s.charAt(i))) {
                i++;
            }
            return s.substring(start, i);
        }

        String rest() {
            return s.substring(i).strip();
        }

        Subject subject() {
            if (i < s.length() && s.charAt(i) == '$') {
                arg = jsonPath();
                return Subject.JSON;
            }
            String w = word();
            switch (w.toLowerCase(Locale.ROOT)) {
                case "status":
                    return Subject.STATUS;
                case "time":
                    return Subject.TIME;
                case "body":
                    return Subject.BODY;
                case "header":
                    skipSpaces();
                    arg = word();
                    if (arg.isEmpty()) {
                        throw new IllegalArgumentException(tr("http.check.error.headerName"));
                    }
                    return Subject.HEADER;
                default:
                    throw new IllegalArgumentException(tr("http.check.error.subject", w));
            }
        }

        /** A JSONPath up to the first whitespace outside brackets and quotes. */
        private String jsonPath() {
            int start = i;
            int depth = 0;
            char quote = 0;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (quote != 0) {
                    if (c == quote) {
                        quote = 0;
                    }
                } else if (c == '"' || c == '\'') {
                    quote = c;
                } else if (c == '[') {
                    depth++;
                } else if (c == ']') {
                    depth--;
                } else if (Character.isWhitespace(c) && depth <= 0) {
                    break;
                }
                i++;
            }
            return s.substring(start, i);
        }

        Op op() {
            // Longest token first: "<=" before "<", "!exists" before "!=".
            String[] tokens = {"==", "!=", "<=", ">=", "<", ">"};
            for (String t : tokens) {
                if (s.startsWith(t, i)) {
                    i += t.length();
                    return switch (t) {
                        case "==" -> Op.EQ;
                        case "!=" -> Op.NE;
                        case "<=" -> Op.LE;
                        case ">=" -> Op.GE;
                        case "<" -> Op.LT;
                        default -> Op.GT;
                    };
                }
            }
            String w = word();
            for (Op op : List.of(Op.CONTAINS, Op.MATCHES, Op.EXISTS, Op.NOT_EXISTS)) {
                if (op.token().equalsIgnoreCase(w)) {
                    return op;
                }
            }
            throw new IllegalArgumentException(
                    w.isEmpty() ? tr("http.check.error.noOperator") : tr("http.check.error.operator", w));
        }
    }

    // --- evaluating -----------------------------------------------------------------------------------------------

    /** Evaluates {@code checks} against {@code result}, a response that arrived (not a failure). */
    public static Outcome evaluate(List<Check> checks, HttpResult result) {
        List<HttpTests.Test> tests = new ArrayList<>();
        Map<String, String> captures = new LinkedHashMap<>();
        List<String> warnings = new ArrayList<>();
        if (checks == null || checks.isEmpty() || result == null) {
            return new Outcome(tests, captures, warnings);
        }
        JsonNode[] json = new JsonNode[1];
        boolean[] parsed = new boolean[1];
        for (Check c : checks) {
            JsonNode actual = read(c, result, json, parsed);
            if (c.isCapture()) {
                if (actual == null || actual.isMissingNode()) {
                    captures.put(c.captureName(), null);
                    warnings.add(tr("http.warn.captureMissing", c.captureName(), describe(c)));
                } else {
                    captures.put(c.captureName(), actual.isNull() ? "" : text(actual));
                }
            } else {
                String failure = compare(c, actual);
                tests.add(new HttpTests.Test(c.text(), failure == null, failure == null ? "" : failure));
            }
        }
        return new Outcome(tests, captures, warnings);
    }

    /** The subject's value: a JSON node, or null when it is not there (a missing header, a path that misses). */
    private static JsonNode read(Check c, HttpResult r, JsonNode[] json, boolean[] parsed) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        return switch (c.subject()) {
            case STATUS -> f.numberNode(r.status());
            case TIME -> f.numberNode(r.elapsedMs());
            case BODY -> f.textNode(r.body() == null ? "" : r.body());
            case HEADER -> {
                String v = header(r, c.arg());
                yield v == null ? null : f.textNode(v);
            }
            case JSON -> {
                if (!parsed[0]) {
                    parsed[0] = true;
                    try {
                        String body = r.body();
                        json[0] = body == null || body.isBlank() ? null : MAPPER.readTree(body);
                    } catch (Exception notJson) {
                        json[0] = null;
                    }
                }
                if (json[0] == null) {
                    yield null;
                }
                JsonNode node = JsonPath.node(json[0], c.arg());
                yield node == null || node.isMissingNode() ? null : node;
            }
        };
    }

    private static String header(HttpResult r, String name) {
        if (r.headers() == null) {
            return null;
        }
        for (String[] h : r.headers()) {
            if (h != null && h.length >= 2 && h[0] != null && h[0].equalsIgnoreCase(name)) {
                return h[1];
            }
        }
        return null;
    }

    /** Null when the assertion holds, else why it does not. */
    static String compare(Check c, JsonNode actual) {
        Op op = c.op();
        if (op == Op.EXISTS) {
            return actual != null ? null : tr("http.check.fail.missing", describe(c));
        }
        if (op == Op.NOT_EXISTS) {
            return actual == null ? null : tr("http.check.fail.present", describe(c), shown(actual));
        }
        if (actual == null) {
            return tr("http.check.fail.missing", describe(c));
        }
        String expected = c.expected();
        Literal lit = literal(expected);
        switch (op) {
            case EQ, NE -> {
                boolean equal = equal(actual, lit);
                if (equal == (op == Op.EQ)) {
                    return null;
                }
                return tr("http.check.fail.compare", op.token(), lit.shown(), shown(actual));
            }
            case LT, LE, GT, GE -> {
                BigDecimal a = number(actual);
                BigDecimal e = lit.number();
                if (a == null || e == null) {
                    return tr("http.check.fail.notNumber", a == null ? shown(actual) : lit.shown());
                }
                int cmp = a.compareTo(e);
                boolean ok =
                        switch (op) {
                            case LT -> cmp < 0;
                            case LE -> cmp <= 0;
                            case GT -> cmp > 0;
                            default -> cmp >= 0;
                        };
                return ok ? null : tr("http.check.fail.compare", op.token(), lit.shown(), shown(actual));
            }
            case CONTAINS -> {
                if (actual.isArray()) {
                    for (JsonNode el : actual) {
                        if (equal(el, lit)) {
                            return null;
                        }
                    }
                    return tr("http.check.fail.contains", lit.shown(), shown(actual));
                }
                return text(actual).contains(lit.text())
                        ? null
                        : tr("http.check.fail.contains", lit.shown(), shown(actual));
            }
            case MATCHES -> {
                try {
                    boolean found = Pattern.compile(lit.text())
                            .matcher(new Deadline(text(actual), System.nanoTime() + REGEX_DEADLINE_MILLIS * 1_000_000L))
                            .find();
                    return found ? null : tr("http.check.fail.matches", lit.text(), shown(actual));
                } catch (Deadline.Expired | StackOverflowError slow) {
                    return tr("http.check.fail.regexTimeout", REGEX_DEADLINE_MILLIS / 1000);
                }
            }
            default -> {
                return null;
            }
        }
    }

    private static boolean equal(JsonNode actual, Literal lit) {
        if (lit.isNull()) {
            return actual.isNull();
        }
        BigDecimal e = lit.number();
        if (e != null && !lit.quoted()) {
            BigDecimal a = number(actual);
            if (a != null) {
                return a.compareTo(e) == 0;
            }
        }
        if (actual.isContainerNode()) {
            try {
                return MAPPER.readTree(lit.text()).equals(actual);
            } catch (Exception notJson) {
                return actual.toString().equals(lit.text());
            }
        }
        return actual.asText().equals(lit.text());
    }

    private static BigDecimal number(JsonNode n) {
        if (n.isNumber()) {
            return n.decimalValue();
        }
        if (n.isTextual()) {
            return decimal(n.asText().strip());
        }
        return null;
    }

    private static BigDecimal decimal(String s) {
        if (s == null || s.isEmpty() || s.length() > 64) {
            return null;
        }
        try {
            return new BigDecimal(s);
        } catch (NumberFormatException notNumber) {
            return null;
        }
    }

    /** A node's text: a value's text, or a container's JSON. */
    private static String text(JsonNode n) {
        return n.isValueNode() ? n.asText() : n.toString();
    }

    /** A node as a failure message shows it: quoted text, plain numbers, JSON; long values cut. */
    private static String shown(JsonNode n) {
        String s = n.isTextual() ? "\"" + n.asText() + "\"" : n.toString();
        return s.length() > 120 ? s.substring(0, 117) + "…" : s;
    }

    /** What a check reads, for a message: {@code status}, {@code header Location}, {@code $.a.b}. */
    static String describe(Check c) {
        return switch (c.subject()) {
            case STATUS -> "status";
            case TIME -> "time";
            case BODY -> "body";
            case HEADER -> "header " + c.arg();
            case JSON -> c.arg();
        };
    }

    /** A literal as written: quoted text, or bare text that may be a number, {@code true}/{@code false}, {@code null}. */
    private record Literal(String text, boolean quoted) {
        boolean isNull() {
            return !quoted && text.equals("null");
        }

        BigDecimal number() {
            return decimal(text.strip());
        }

        String shown() {
            return quoted || number() == null && !isNull() && !text.equals("true") && !text.equals("false")
                    ? "\"" + text + "\""
                    : text;
        }
    }

    private static Literal literal(String expected) {
        String s = expected == null ? "" : expected.strip();
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return new Literal(s.substring(1, s.length() - 1), true);
        }
        return new Literal(s, false);
    }

    private static String literalText(String expected) {
        return literal(expected).text();
    }

    /**
     * A character sequence that throws once a deadline passes: the regex engine reads its input through
     * {@link #charAt}, so a pattern that backtracks without end stops instead of holding the run's worker. The clock
     * is read every 256 reads, wherever they fall.
     */
    private static final class Deadline implements CharSequence {

        static final class Expired extends RuntimeException {
            Expired() {
                super(null, null, false, false);
            }
        }

        private final CharSequence inner;
        private final long deadlineNanos;
        private int reads;

        Deadline(CharSequence inner, long deadlineNanos) {
            this.inner = inner;
            this.deadlineNanos = deadlineNanos;
        }

        @Override
        public char charAt(int index) {
            if ((++reads & 0xff) == 0 && System.nanoTime() > deadlineNanos) {
                throw new Expired();
            }
            return inner.charAt(index);
        }

        @Override
        public int length() {
            return inner.length();
        }

        @Override
        public CharSequence subSequence(int start, int end) {
            return new Deadline(inner.subSequence(start, end), deadlineNanos);
        }

        @Override
        public String toString() {
            return inner.toString();
        }
    }
}
