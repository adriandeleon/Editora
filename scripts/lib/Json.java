package lib;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the scripts, which run on a bare JDK (it has no JSON API).
 *
 * <p>Values are plain Java objects: an object is a {@code Map<String, Object>} (insertion-ordered), an array a
 * {@code List<Object>}, a number a {@code Long} when it has no fraction or exponent and a {@code Double}
 * otherwise, then {@code String}, {@code Boolean} and {@code null}.
 *
 * <p>The writer follows Python's {@code json.dumps}: {@link #write} uses its default {@code ", "} and
 * {@code ": "} separators, {@link #pretty} its {@code indent=2} layout, and both escape non-ASCII characters.
 * Files written by the earlier Python scripts therefore compare equal byte for byte.
 */
public final class Json {

    /** Thrown for text that is not one complete JSON value. */
    public static final class ParseException extends IllegalArgumentException {
        ParseException(String message, int at) {
            super(message + " at offset " + at);
        }
    }

    private Json() {}

    public static Object parse(String text) {
        var reader = new Reader(text);
        reader.space();
        Object value = reader.value();
        reader.space();
        if (reader.at < text.length()) {
            throw new ParseException("trailing characters", reader.at);
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("not a JSON object: " + value);
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> array(Object value) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException("not a JSON array: " + value);
        }
        return (List<Object>) value;
    }

    /** One line, as Python's {@code json.dumps(value)} writes it. */
    public static String write(Object value) {
        var out = new StringBuilder();
        write(out, value, -1, 0);
        return out.toString();
    }

    /** Indented by two spaces, as Python's {@code json.dumps(value, indent=2)} writes it (no final newline). */
    public static String pretty(Object value) {
        var out = new StringBuilder();
        write(out, value, 2, 0);
        return out.toString();
    }

    private static void write(StringBuilder out, Object value, int indent, int depth) {
        switch (value) {
            case null -> out.append("null");
            case String s -> string(out, s);
            case Boolean b -> out.append(b);
            case Double d -> out.append(number(d));
            case Float f -> out.append(number(f.doubleValue()));
            case Number n -> out.append(n);
            case Map<?, ?> map -> {
                if (map.isEmpty()) {
                    out.append("{}");
                    return;
                }
                out.append('{');
                boolean first = true;
                for (var entry : map.entrySet()) {
                    separator(out, first, indent, depth + 1);
                    first = false;
                    string(out, String.valueOf(entry.getKey()));
                    out.append(": ");
                    write(out, entry.getValue(), indent, depth + 1);
                }
                close(out, indent, depth);
                out.append('}');
            }
            case Iterable<?> list -> {
                if (!list.iterator().hasNext()) {
                    out.append("[]");
                    return;
                }
                out.append('[');
                boolean first = true;
                for (Object item : list) {
                    separator(out, first, indent, depth + 1);
                    first = false;
                    write(out, item, indent, depth + 1);
                }
                close(out, indent, depth);
                out.append(']');
            }
            default -> throw new IllegalArgumentException("cannot write " + value.getClass().getName() + " as JSON");
        }
    }

    private static void separator(StringBuilder out, boolean first, int indent, int depth) {
        if (indent < 0) {
            if (!first) {
                out.append(", ");
            }
            return;
        }
        if (!first) {
            out.append(',');
        }
        out.append('\n').append(" ".repeat(indent * depth));
    }

    private static void close(StringBuilder out, int indent, int depth) {
        if (indent >= 0) {
            out.append('\n').append(" ".repeat(indent * depth));
        }
    }

    /** Python's float repr for the usual magnitudes: shortest round-trip digits, always with a fraction. */
    private static String number(double d) {
        if (Double.isNaN(d)) {
            return "NaN";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "Infinity" : "-Infinity";
        }
        String text = Double.toString(d);
        if (text.contains("E")) {
            text = new BigDecimal(text).toPlainString();
            if (!text.contains(".")) {
                text += ".0";
            }
        }
        return text;
    }

    private static void string(StringBuilder out, String s) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < 0x20 || c > 0x7e) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class Reader {
        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        void space() {
            while (at < text.length() && " \t\r\n".indexOf(text.charAt(at)) >= 0) {
                at++;
            }
        }

        Object value() {
            if (at >= text.length()) {
                throw new ParseException("unexpected end", at);
            }
            char c = text.charAt(at);
            return switch (c) {
                case '{' -> object();
                case '[' -> array();
                case '"' -> string();
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield number();
                    }
                    throw new ParseException("unexpected character '" + c + "'", at);
                }
            };
        }

        private Map<String, Object> object() {
            var map = new LinkedHashMap<String, Object>();
            at++;
            space();
            if (peek() == '}') {
                at++;
                return map;
            }
            while (true) {
                space();
                if (peek() != '"') {
                    throw new ParseException("expected a key", at);
                }
                String key = string();
                space();
                expect(':');
                space();
                map.put(key, value());
                space();
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect('}');
                return map;
            }
        }

        private List<Object> array() {
            var list = new ArrayList<Object>();
            at++;
            space();
            if (peek() == ']') {
                at++;
                return list;
            }
            while (true) {
                space();
                list.add(value());
                space();
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return list;
            }
        }

        private String string() {
            at++;
            var out = new StringBuilder();
            while (true) {
                if (at >= text.length()) {
                    throw new ParseException("unterminated string", at);
                }
                char c = text.charAt(at++);
                if (c == '"') {
                    return out.toString();
                }
                if (c != '\\') {
                    out.append(c);
                    continue;
                }
                if (at >= text.length()) {
                    throw new ParseException("unterminated escape", at);
                }
                char e = text.charAt(at++);
                switch (e) {
                    case '"', '\\', '/' -> out.append(e);
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'u' -> {
                        if (at + 4 > text.length()) {
                            throw new ParseException("short unicode escape", at);
                        }
                        try {
                            out.append((char) Integer.parseInt(text.substring(at, at + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ParseException("bad unicode escape", at);
                        }
                        at += 4;
                    }
                    default -> throw new ParseException("bad escape '\\" + e + "'", at - 1);
                }
            }
        }

        private Object number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            boolean integral = true;
            while (at < text.length()) {
                char c = text.charAt(at);
                if (c >= '0' && c <= '9') {
                    at++;
                } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    integral = false;
                    at++;
                } else {
                    break;
                }
            }
            String digits = text.substring(start, at);
            try {
                return integral ? (Object) Long.parseLong(digits) : (Object) Double.parseDouble(digits);
            } catch (NumberFormatException ex) {
                throw new ParseException("bad number '" + digits + "'", start);
            }
        }

        private Object literal(String word, Object value) {
            if (!text.startsWith(word, at)) {
                throw new ParseException("expected " + word, at);
            }
            at += word.length();
            return value;
        }

        private char peek() {
            if (at >= text.length()) {
                throw new ParseException("unexpected end", at);
            }
            return text.charAt(at);
        }

        private void expect(char c) {
            if (peek() != c) {
                throw new ParseException("expected '" + c + "'", at);
            }
            at++;
        }
    }
}
