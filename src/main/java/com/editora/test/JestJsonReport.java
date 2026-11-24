package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Reads Jest's {@code --json --outputFile} report, and Vitest's {@code json} reporter's (the same shape): a suite per
 * test file, named by its path from the run's folder, and a test per assertion result, named by its describe blocks
 * and title joined by {@link #SEPARATOR} — which {@link ToolTestKind#filtered} splits again for {@code -t}. Its
 * declared line when Jest gives it ({@code --testLocationInResults}); a failure's message and stack, colours taken
 * out. Pure.
 */
public final class JestJsonReport {

    /** Joins a test's describe blocks and title in its row's name. */
    public static final String SEPARATOR = " › ";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JestJsonReport() {}

    /** The suites of report {@code json}, whose run was in {@code dir}; empty when it is no report. */
    public static List<ParsedSuite> parse(String json, Path dir) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception notJson) {
            return List.of();
        }
        List<ParsedSuite> out = new ArrayList<>();
        if (root == null) {
            return out;
        }
        for (JsonNode file : root.path("testResults")) {
            String path = file.path("name").asText("");
            String suite = relative(dir, path);
            List<ParsedTest> tests = new ArrayList<>();
            for (JsonNode a : file.path("assertionResults")) {
                List<String> names = new ArrayList<>();
                for (JsonNode t : a.path("ancestorTitles")) {
                    names.add(t.asText(""));
                }
                names.add(a.path("title").asText(""));
                String failure = joined(a.path("failureMessages"));
                String message = failure == null ? null : firstLine(failure);
                tests.add(new ParsedTest(
                        suite,
                        String.join(SEPARATOR, names),
                        status(a.path("status").asText("")),
                        Math.round(a.path("duration").asDouble(0)),
                        null,
                        message,
                        failure,
                        null,
                        hint(path),
                        a.path("location").path("line").asInt(0)));
            }
            if (tests.isEmpty()) {
                String message = file.path("message").asText(""); // the file failed to load: no tests ran
                if (!message.isBlank() && "failed".equals(file.path("status").asText(""))) {
                    String clean = NodeTestFailure.stripAnsi(message).strip();
                    tests.add(new ParsedTest(
                            suite, suite, TestStatus.ERROR, 0, null, firstLine(clean), clean, null, hint(path), 0));
                }
            }
            out.add(new ParsedSuite(suite, tests));
        }
        return out;
    }

    private static TestStatus status(String s) {
        return switch (s) {
            case "passed" -> TestStatus.PASSED;
            case "failed" -> TestStatus.FAILED;
            default -> TestStatus.SKIPPED; // pending, skipped, todo, disabled
        };
    }

    private static @Nullable String joined(JsonNode messages) {
        List<String> all = new ArrayList<>();
        for (JsonNode m : messages) {
            String text = NodeTestFailure.stripAnsi(m.asText("")).strip();
            if (!text.isEmpty()) {
                all.add(text);
            }
        }
        return all.isEmpty() ? null : String.join("\n\n", all);
    }

    /** {@code path} as this system writes it; null for none. */
    private static @Nullable String hint(String path) {
        if (path.isEmpty()) {
            return null;
        }
        try {
            return Path.of(path).toString();
        } catch (RuntimeException notAPath) {
            return path;
        }
    }

    static String firstLine(String text) {
        int nl = text.indexOf('\n');
        return (nl >= 0 ? text.substring(0, nl) : text).strip();
    }

    /** {@code path} from {@code dir}, with forward slashes; itself when it is not under it. */
    static String relative(Path dir, String path) {
        if (path.isEmpty()) {
            return "tests";
        }
        try {
            Path p = Path.of(path).normalize();
            Path d = dir.normalize();
            if (!p.startsWith(d)) {
                p = p.toAbsolutePath();
                d = d.toAbsolutePath();
            }
            return p.startsWith(d) ? d.relativize(p).toString().replace('\\', '/') : path.replace('\\', '/');
        } catch (RuntimeException notAPath) {
            return path;
        }
    }
}
