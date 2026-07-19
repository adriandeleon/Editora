package com.editora.test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Folds {@code go test -json} events into suite deltas. Each line is a JSON object
 * {@code {"Action","Package","Test","Elapsed","Output"}}. A {@code run} for a test starts it; {@code output}
 * lines accumulate (they carry the human-readable {@code go test} text — assertions, {@code file_test.go:NN}
 * frames — which also feeds the failure detail + clickable frames); {@code pass}/{@code fail}/{@code skip}
 * settle it with its elapsed time. Package-level events are ignored (the suite status rolls up from its tests).
 *
 * <p>{@link #consoleLine} returns the decoded {@code Output} text so the raw Build Output console stays
 * human-readable despite the {@code -json} flag; non-output events are suppressed there.
 */
public final class GoTestJsonParser implements TestResultParser {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, StringBuilder> output = new HashMap<>(); // testId -> accumulated output

    @Override
    public List<ParsedSuite> onLine(String line, boolean stderr) {
        JsonNode event = read(line);
        if (event == null) {
            return List.of();
        }
        String action = text(event, "Action");
        String pkg = text(event, "Package");
        String test = text(event, "Test");
        if (action == null || pkg == null || test == null) {
            return List.of(); // package-level event or a non-test line — nothing to place in the tree
        }
        String id = pkg + "#" + test;
        switch (action) {
            case "run" -> {
                return List.of(suite(pkg, ParsedTest.of(pkg, test, TestStatus.RUNNING, 0)));
            }
            case "output" -> {
                output.computeIfAbsent(id, k -> new StringBuilder()).append(textOrEmpty(event, "Output"));
                return List.of();
            }
            case "pass", "fail", "skip" -> {
                TestStatus status =
                        switch (action) {
                            case "fail" -> TestStatus.FAILED;
                            case "skip" -> TestStatus.SKIPPED;
                            default -> TestStatus.PASSED;
                        };
                long durationMs = Math.round(event.path("Elapsed").asDouble(0) * 1000.0);
                String captured = drain(id);
                String failureMessage = status == TestStatus.FAILED ? captured : null;
                String stackTrace = status == TestStatus.FAILED ? captured : null;
                ParsedTest result = new ParsedTest(
                        pkg, test, status, durationMs, null, failureMessage, stackTrace, captured, null, 0);
                return List.of(suite(pkg, result));
            }
            default -> {
                return List.of();
            }
        }
    }

    @Override
    public String consoleLine(String raw, boolean stderr) {
        JsonNode event = read(raw);
        if (event == null) {
            return raw; // not a -json line (e.g. a go build error) — show it verbatim
        }
        if (!"output".equals(text(event, "Action"))) {
            return null; // suppress run/pass/fail/skip bookkeeping events from the raw console
        }
        String out = textOrEmpty(event, "Output");
        // go's Output fields already carry their own trailing newline; the console appends one, so strip it.
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    private static ParsedSuite suite(String pkg, ParsedTest test) {
        return new ParsedSuite(pkg, List.of(test));
    }

    private String drain(String id) {
        StringBuilder sb = output.remove(id);
        if (sb == null || sb.isEmpty()) {
            return null;
        }
        return sb.toString();
    }

    private static JsonNode read(String line) {
        if (line == null || line.isBlank() || line.charAt(0) != '{') {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOrEmpty(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? "" : v;
    }
}
