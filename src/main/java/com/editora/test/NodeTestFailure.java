package com.editora.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A failed node:test (or other TAP) result's detail, read from the YAML block TAP puts under it — or from the
 * lines the spec reporter prints under a failure. Pure.
 *
 * <p>The YAML node:test writes is small and regular: {@code key: value} lines at one indent, where a value is a
 * plain scalar, a single-quoted string, or a {@code |-}/{@code |} block of deeper-indented lines. Its
 * {@code error} (or a generic TAP {@code message}) is the failure message, {@code stack} the frames (without
 * their {@code at}), {@code location} the test's own {@code file:line:column}. Anything else is left out of
 * the message — {@code duration_ms} and {@code type} used to be all a failure showed.
 *
 * @param message the failure message, or null
 * @param type the error's name or code ({@code AssertionError}), or null
 * @param stack the stack as console-style {@code at …} lines (the test's location first), or null
 * @param file the test file from {@code location}, or null
 * @param line its 1-based line, or 0
 * @param suite whether the block is a suite's ({@code type: 'suite'}), whose row is not a test
 */
public record NodeTestFailure(String message, String type, String stack, String file, int line, boolean suite) {

    private static final Pattern KEY = Pattern.compile("^([A-Za-z_][\\w-]*):\\s?(.*)$");
    private static final Pattern LOCATION = Pattern.compile("^(.+?):(\\d+)(?::\\d+)?$");
    /** An SGR (colour) escape — stripped before a line is read. */
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;?]*[A-Za-z]");

    /** {@code text} without colour escapes. */
    public static String stripAnsi(String text) {
        return text == null || text.indexOf('\u001B') < 0
                ? text
                : ANSI.matcher(text).replaceAll("");
    }

    /** Reads a YAML diagnostic block (the lines between {@code ---} and {@code ...}, as printed). */
    public static NodeTestFailure fromYaml(List<String> lines, String testName) {
        Map<String, String> values = yamlValues(lines);
        String message = firstNonBlank(values.get("error"), values.get("message"));
        String type = firstNonBlank(values.get("name"), values.get("code"));
        String location = values.get("location");
        String file = null;
        int line = 0;
        if (location != null) {
            Matcher m = LOCATION.matcher(location.strip());
            if (m.matches()) {
                file = m.group(1);
                line = Integer.parseInt(m.group(2));
            }
        }
        StringBuilder stack = new StringBuilder();
        if (location != null) {
            stack.append("    at ")
                    .append(testName == null ? "test" : testName)
                    .append(" (")
                    .append(location.strip())
                    .append(")\n");
        }
        String frames = values.get("stack");
        if (frames != null) {
            for (String f : frames.split("\n")) {
                if (!f.isBlank()) {
                    String t = f.strip();
                    stack.append("    ")
                            .append(t.startsWith("at ") ? t : "at " + t)
                            .append('\n');
                }
            }
        }
        if (message == null && values.isEmpty()) {
            message = String.join("\n", lines).strip(); // not YAML we understand: show it as written
        }
        boolean suite = "suite".equals(values.get("type"));
        return new NodeTestFailure(
                message == null || message.isBlank() ? null : message.strip(),
                type,
                stack.isEmpty() ? null : stack.toString(),
                file,
                line,
                suite);
    }

    /** The {@code key: value} pairs of a block, block scalars joined with newlines, quotes removed. */
    static Map<String, String> yamlValues(List<String> lines) {
        Map<String, String> out = new LinkedHashMap<>();
        int base = -1;
        String blockKey = null;
        List<String> block = new ArrayList<>();
        int blockIndent = -1;
        for (String raw : lines) {
            String line = stripAnsi(raw);
            int indent = indent(line);
            if (blockKey != null) {
                if (line.isBlank() || indent > base) {
                    if (!line.isBlank() && blockIndent < 0) {
                        blockIndent = indent;
                    }
                    block.add(line.isBlank() ? "" : line.substring(Math.min(line.length(), Math.max(blockIndent, 0))));
                    continue;
                }
                out.put(blockKey, String.join("\n", block).strip());
                blockKey = null;
                block.clear();
                blockIndent = -1;
            }
            if (line.isBlank()) {
                continue;
            }
            if (base < 0) {
                base = indent;
            }
            if (indent != base) {
                continue; // a nested mapping (expected/actual objects): not needed
            }
            Matcher m = KEY.matcher(line.strip());
            if (!m.matches()) {
                continue;
            }
            String value = m.group(2).strip();
            if (value.equals("|-") || value.equals("|") || value.equals(">-") || value.equals(">")) {
                blockKey = m.group(1);
            } else {
                out.put(m.group(1), unquote(value));
            }
        }
        if (blockKey != null) {
            out.put(blockKey, String.join("\n", block).strip());
        }
        return out;
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && v.startsWith("'") && v.endsWith("'")) {
            return v.substring(1, v.length() - 1).replace("''", "'");
        }
        if (v.length() >= 2 && v.startsWith("\"") && v.endsWith("\"")) {
            return v.substring(1, v.length() - 1).replace("\\\"", "\"").replace("\\n", "\n");
        }
        return v;
    }

    private static int indent(String line) {
        int i = 0;
        while (i < line.length() && line.charAt(i) == ' ') {
            i++;
        }
        return i;
    }

    private static String firstNonBlank(String a, String b) {
        return a != null && !a.isBlank() ? a : b != null && !b.isBlank() ? b : null;
    }

    /** The failed result as a {@link ParsedTest} under {@code suite}. */
    public ParsedTest toParsedTest(String suiteName, String name, TestStatus status) {
        return new ParsedTest(
                suiteName, name, status, 0, type, message, stack, null, file == null ? null : fileName(file), line);
    }

    private static String fileName(String path) {
        String p = path.replace('\\', '/');
        return p.substring(p.lastIndexOf('/') + 1);
    }
}
