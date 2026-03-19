package org.adriandeleon.editora.persistence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class JsonSupport {
    private JsonSupport() {
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String json) {
        Object value = new Parser(json).parseValue();
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        throw new IllegalArgumentException("JSON root must be an object");
    }

    static String toJson(Object value) {
        StringBuilder builder = new StringBuilder();
        writeValue(builder, value, 0);
        builder.append(System.lineSeparator());
        return builder.toString();
    }

    private static void writeValue(StringBuilder builder, Object value, int indent) {
        if (value == null) {
            builder.append("null");
            return;
        }
        if (value instanceof String string) {
            writeString(builder, string);
            return;
        }
        if (value instanceof Number || value instanceof Boolean) {
            builder.append(value);
            return;
        }
        if (value instanceof Map<?, ?> map) {
            writeObject(builder, map, indent);
            return;
        }
        if (value instanceof List<?> list) {
            writeArray(builder, list, indent);
            return;
        }
        throw new IllegalArgumentException("Unsupported JSON value type: " + value.getClass().getName());
    }

    private static void writeObject(StringBuilder builder, Map<?, ?> map, int indent) {
        builder.append('{');
        if (map.isEmpty()) {
            builder.append('}');
            return;
        }
        builder.append(System.lineSeparator());
        int index = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(builder, indent + 2);
            writeString(builder, String.valueOf(entry.getKey()));
            builder.append(": ");
            writeValue(builder, entry.getValue(), indent + 2);
            if (++index < map.size()) {
                builder.append(',');
            }
            builder.append(System.lineSeparator());
        }
        indent(builder, indent);
        builder.append('}');
    }

    private static void writeArray(StringBuilder builder, List<?> list, int indent) {
        builder.append('[');
        if (list.isEmpty()) {
            builder.append(']');
            return;
        }
        builder.append(System.lineSeparator());
        for (int index = 0; index < list.size(); index++) {
            indent(builder, indent + 2);
            writeValue(builder, list.get(index), indent + 2);
            if (index + 1 < list.size()) {
                builder.append(',');
            }
            builder.append(System.lineSeparator());
        }
        indent(builder, indent);
        builder.append(']');
    }

    private static void writeString(StringBuilder builder, String value) {
        builder.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> builder.append("\\\"");
                case '\\' -> builder.append("\\\\");
                case '\b' -> builder.append("\\b");
                case '\f' -> builder.append("\\f");
                case '\n' -> builder.append("\\n");
                case '\r' -> builder.append("\\r");
                case '\t' -> builder.append("\\t");
                default -> {
                    if (current < 0x20) {
                        builder.append(String.format("\\u%04x", (int) current));
                    } else {
                        builder.append(current);
                    }
                }
            }
        }
        builder.append('"');
    }

    private static void indent(StringBuilder builder, int spaces) {
        builder.append(" ".repeat(Math.max(0, spaces)));
    }

    private static final class Parser {
        private final String source;
        private int index;

        private Parser(String source) {
            this.source = source == null ? "" : source;
        }

        private Object parseValue() {
            skipWhitespace();
            if (index >= source.length()) {
                throw new IllegalArgumentException("Unexpected end of JSON input");
            }
            return switch (source.charAt(index)) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> parseString();
                case 't' -> parseLiteral("true", Boolean.TRUE);
                case 'f' -> parseLiteral("false", Boolean.FALSE);
                case 'n' -> parseLiteral("null", null);
                default -> parseNumber();
            };
        }

        private Map<String, Object> parseObject() {
            expect('{');
            skipWhitespace();
            Map<String, Object> result = new LinkedHashMap<>();
            if (peek('}')) {
                expect('}');
                return result;
            }
            while (true) {
                String key = parseString();
                skipWhitespace();
                expect(':');
                result.put(key, parseValue());
                skipWhitespace();
                if (peek('}')) {
                    expect('}');
                    return result;
                }
                expect(',');
            }
        }

        private List<Object> parseArray() {
            expect('[');
            skipWhitespace();
            List<Object> result = new ArrayList<>();
            if (peek(']')) {
                expect(']');
                return result;
            }
            while (true) {
                result.add(parseValue());
                skipWhitespace();
                if (peek(']')) {
                    expect(']');
                    return result;
                }
                expect(',');
            }
        }

        private String parseString() {
            expect('"');
            StringBuilder builder = new StringBuilder();
            while (index < source.length()) {
                char current = source.charAt(index++);
                if (current == '"') {
                    return builder.toString();
                }
                if (current != '\\') {
                    builder.append(current);
                    continue;
                }
                if (index >= source.length()) {
                    throw new IllegalArgumentException("Unterminated JSON escape");
                }
                char escaped = source.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> builder.append(escaped);
                    case 'b' -> builder.append('\b');
                    case 'f' -> builder.append('\f');
                    case 'n' -> builder.append('\n');
                    case 'r' -> builder.append('\r');
                    case 't' -> builder.append('\t');
                    case 'u' -> builder.append(parseUnicodeEscape());
                    default -> throw new IllegalArgumentException("Unsupported JSON escape: \\" + escaped);
                }
            }
            throw new IllegalArgumentException("Unterminated JSON string");
        }

        private char parseUnicodeEscape() {
            if (index + 4 > source.length()) {
                throw new IllegalArgumentException("Incomplete unicode escape");
            }
            String hex = source.substring(index, index + 4);
            index += 4;
            return (char) Integer.parseInt(hex, 16);
        }

        private Object parseLiteral(String literal, Object value) {
            if (!source.startsWith(literal, index)) {
                throw new IllegalArgumentException("Expected JSON literal " + literal);
            }
            index += literal.length();
            return value;
        }

        private Number parseNumber() {
            int start = index;
            if (peek('-')) {
                index++;
            }
            while (index < source.length() && Character.isDigit(source.charAt(index))) {
                index++;
            }
            if (peek('.')) {
                index++;
                while (index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
            }
            if (peek('e') || peek('E')) {
                index++;
                if (peek('+') || peek('-')) {
                    index++;
                }
                while (index < source.length() && Character.isDigit(source.charAt(index))) {
                    index++;
                }
            }
            String token = source.substring(start, index);
            if (token.contains(".") || token.contains("e") || token.contains("E")) {
                return Double.parseDouble(token);
            }
            return Long.parseLong(token);
        }

        private void skipWhitespace() {
            while (index < source.length() && Character.isWhitespace(source.charAt(index))) {
                index++;
            }
        }

        private void expect(char expected) {
            skipWhitespace();
            if (index >= source.length() || source.charAt(index) != expected) {
                throw new IllegalArgumentException("Expected '" + expected + "' at position " + index);
            }
            index++;
        }

        private boolean peek(char expected) {
            return index < source.length() && source.charAt(index) == expected;
        }
    }
}

