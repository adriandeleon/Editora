package com.editora.mermaid;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Pure parser for <b>maid</b> ({@code probelabs/maid}) diagnostics. maid is invoked with
 * {@code --format json}, emitting error objects with {@code line}/{@code column}/{@code severity}/
 * {@code code}/{@code message}; this also tolerates maid's human-readable text form
 * ({@code error[CODE]: message at file:line:col}) as a fallback. Line/column are 1-based, as maid
 * reports them. All static + side-effect-free, so it is unit-tested.
 */
public final class MaidOutput {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** One diagnostic: 1-based {@code line}/{@code column}, span {@code length} (chars; ≥1 for underlining),
     *  a {@code severity}, {@code code}, {@code message}. */
    public record Diagnostic(int line, int column, int length, String severity, String code, String message) {
        public boolean isError() {
            return severity == null || severity.isBlank() || "error".equalsIgnoreCase(severity);
        }
    }

    // error[CODE]: message ... at <file>:line:col   (text fallback)
    private static final Pattern TEXT = Pattern.compile(
            "error\\[(?<code>[^\\]]*)\\]:\\s*(?<msg>.*?)\\s+at\\s+\\S+?:(?<line>\\d+):(?<col>\\d+)",
            Pattern.CASE_INSENSITIVE);

    private MaidOutput() {}

    /** Parses maid output (JSON preferred, text fallback) into diagnostics; never throws, never null. */
    public static List<Diagnostic> parse(String output) {
        if (output == null || output.isBlank()) {
            return List.of();
        }
        String trimmed = output.strip();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            List<Diagnostic> json = parseJson(trimmed);
            if (!json.isEmpty()) {
                return json;
            }
        }
        return parseText(output);
    }

    /** Tolerant JSON parse: collects error objects from a root array, or {@code errors}/{@code diagnostics}. */
    static List<Diagnostic> parseJson(String json) {
        List<Diagnostic> out = new ArrayList<>();
        try {
            JsonNode root = JSON.readTree(json);
            collect(root, out);
        } catch (Exception ignored) {
            // not JSON / malformed — caller falls back to text
        }
        return out;
    }

    private static void collect(JsonNode node, List<Diagnostic> out) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (JsonNode e : node) {
                collect(e, out);
            }
            return;
        }
        if (node.has("line") && (node.has("message") || node.has("msg"))) {
            out.add(new Diagnostic(
                    node.path("line").asInt(1),
                    node.path("column").asInt(node.path("col").asInt(1)),
                    Math.max(1, node.path("length").asInt(1)),
                    text(node, "severity", "error"),
                    text(node, "code", ""),
                    text(node, "message", text(node, "msg", ""))));
            return;
        }
        // Recurse into common container fields. "warnings" is a real top-level array in maid's own output
        // (alongside "errors"/"warningCount") and was missing here, so a maid warning produced no squiggle
        // and no tooltip; severity already flows through Diagnostic.severity/isError.
        for (String field : List.of("errors", "warnings", "diagnostics", "results", "issues")) {
            if (node.has(field)) {
                collect(node.get(field), out);
            }
        }
    }

    private static String text(JsonNode node, String field, String fallback) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? fallback : v.asText(fallback);
    }

    static List<Diagnostic> parseText(String output) {
        List<Diagnostic> out = new ArrayList<>();
        Matcher m = TEXT.matcher(output);
        while (m.find()) {
            out.add(new Diagnostic(
                    Integer.parseInt(m.group("line")),
                    Integer.parseInt(m.group("col")),
                    1,
                    "error",
                    m.group("code"),
                    m.group("msg").strip()));
        }
        return out;
    }
}
