package com.editora.jstools;

import java.util.ArrayList;
import java.util.List;

import com.editora.text.LspDiagnostic;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * ESLint's {@code --format json} report for one file read from stdin: an array with one result, whose
 * {@code messages} become problems (source {@code eslint}, the rule as the code) and whose {@code output} — present
 * with {@code --fix-dry-run} when a fix changed something — is the fixed text.
 *
 * <p>A message is {@code {ruleId, severity (1 warning, 2 error), message, line, column, endLine?, endColumn?, fix?,
 * suggestions?}}: lines and columns count from 1, a {@code fix} is {@code {range: [start, end], text}} in UTF-16
 * offsets of the text that was checked (Java's string offsets), and each suggestion is {@code {desc, fix}}. Pure.
 */
public final class EslintReport {

    private EslintReport() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The source every ESLint problem carries. */
    public static final String SOURCE = "eslint";

    /** One replacement in the checked text: {@code [start, end)} becomes {@code text}. */
    public record Fix(int start, int end, String text) {

        /** Applies this fix to {@code text}; null when its range does not fit (the text is not the one checked). */
        public @Nullable String applyTo(String source) {
            if (start < 0 || end < start || end > source.length()) {
                return null;
            }
            return source.substring(0, start) + text + source.substring(end);
        }
    }

    /** A suggestion ESLint offers for a problem but never applies by itself: its description and its edit. */
    public record Suggestion(String description, Fix fix) {}

    /** One problem: as shown, and the rule's automatic fix (null when none) and suggestions. */
    public record Problem(
            LspDiagnostic diagnostic, @Nullable Fix fix, List<Suggestion> suggestions) {
        public Problem {
            suggestions = List.copyOf(suggestions);
        }

        /** The rule that reported it ({@code no-unused-vars}), or null for a parse error. */
        public @Nullable String rule() {
            return diagnostic.code();
        }
    }

    /**
     * A parsed report.
     *
     * @param problems the file's problems
     * @param output the fixed text ({@code --fix-dry-run}), or null when no fix changed anything
     */
    public record Result(List<Problem> problems, @Nullable String output) {
        public Result {
            problems = List.copyOf(problems);
        }

        static final Result EMPTY = new Result(List.of(), null);

        /** The diagnostics alone. */
        public List<LspDiagnostic> diagnostics() {
            return problems.stream().map(Problem::diagnostic).toList();
        }
    }

    /**
     * Parses ESLint's report of {@code text}. Throws {@link IllegalArgumentException} when {@code json} is not one
     * (ESLint wrote something else to stdout: a crash, a plugin's console output).
     */
    public static Result parse(String json, String text) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json.strip());
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("not ESLint's JSON report: " + e.getOriginalMessage(), e);
        }
        if (root == null || !root.isArray()) {
            throw new IllegalArgumentException("not ESLint's JSON report");
        }
        if (root.isEmpty()) {
            return Result.EMPTY;
        }
        JsonNode result = root.get(0);
        List<Problem> problems = new ArrayList<>();
        LineIndex lines = new LineIndex(text);
        for (JsonNode m : result.path("messages")) {
            if (ignoredFileWarning(m)) {
                continue; // the configuration ignores this file: nothing to show (ESLint 8 has no --no-warn-ignored)
            }
            problems.add(problem(m, lines));
        }
        JsonNode output = result.get("output");
        return new Result(problems, output != null && output.isTextual() ? output.asText() : null);
    }

    /** ESLint's "File ignored because of a matching ignore pattern" (or "…outside of base path") warning. */
    static boolean ignoredFileWarning(JsonNode m) {
        return m.path("ruleId").isNull()
                && !m.path("fatal").asBoolean(false)
                && m.path("message").asText("").startsWith("File ignored");
    }

    private static Problem problem(JsonNode m, LineIndex lines) {
        int line = Math.max(0, m.path("line").asInt(1) - 1);
        int col = Math.max(0, m.path("column").asInt(1) - 1);
        int endLine = m.hasNonNull("endLine") ? Math.max(line, m.path("endLine").asInt() - 1) : line;
        int endCol = m.hasNonNull("endColumn") ? Math.max(0, m.path("endColumn").asInt() - 1) : -1;
        if (endCol < 0 || (endLine == line && endCol <= col)) {
            endCol = lines.wordEnd(line, col); // a parse error names a position only: mark the token there
            endLine = line;
        }
        String rule = m.hasNonNull("ruleId") ? m.get("ruleId").asText() : null;
        LspDiagnostic.Severity severity =
                m.path("severity").asInt(2) >= 2 ? LspDiagnostic.Severity.ERROR : LspDiagnostic.Severity.WARNING;
        String message = m.path("message").asText("").strip();
        LspDiagnostic d =
                new LspDiagnostic(line, col, endLine, endCol, severity, message, rule, SOURCE, null, false, false);
        List<Suggestion> suggestions = new ArrayList<>();
        for (JsonNode s : m.path("suggestions")) {
            Fix fix = fix(s.get("fix"));
            if (fix != null) {
                suggestions.add(new Suggestion(s.path("desc").asText(message), fix));
            }
        }
        return new Problem(d, fix(m.get("fix")), suggestions);
    }

    private static @Nullable Fix fix(@Nullable JsonNode f) {
        if (f == null || !f.isObject()) {
            return null;
        }
        JsonNode range = f.get("range");
        if (range == null || !range.isArray() || range.size() != 2 || !f.has("text")) {
            return null;
        }
        return new Fix(range.get(0).asInt(), range.get(1).asInt(), f.get("text").asText(""));
    }

    /** Line starts of the checked text, for a position's token end. */
    private static final class LineIndex {
        private final String text;
        private final List<Integer> starts = new ArrayList<>();

        LineIndex(String text) {
            this.text = text;
            starts.add(0);
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) == '\n') {
                    starts.add(i + 1);
                }
            }
        }

        /** The column after the word (or the one character) at {@code line:col}; {@code col + 1} past the text. */
        int wordEnd(int line, int col) {
            if (line >= starts.size()) {
                return col + 1;
            }
            int start = starts.get(line);
            int lineEnd = line + 1 < starts.size() ? starts.get(line + 1) - 1 : text.length();
            int i = start + col;
            if (i >= lineEnd) {
                return col + 1;
            }
            int j = i;
            while (j < lineEnd && Character.isJavaIdentifierPart(text.charAt(j))) {
                j++;
            }
            return (j == i ? i + 1 : j) - start;
        }
    }
}
