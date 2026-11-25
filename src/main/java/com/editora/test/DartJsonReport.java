package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;

/**
 * Reads {@code dart test}'s JSON reporter ({@code --file-reporter json:file}), one event a line: a suite per test
 * file, a test per {@code testStart} named by its full name (groups and test, as {@code --name} matches it), its line,
 * its {@code error} events' messages and stacks, its {@code print}s; the hidden {@code loading …} test only when it
 * failed (the file did not compile). Pure.
 */
public final class DartJsonReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private DartJsonReport() {}

    private static final class Test {
        final String suite;
        final String name;
        final @Nullable String file;
        final int line;
        final long start;
        final StringBuilder errors = new StringBuilder();
        final StringBuilder prints = new StringBuilder();

        @Nullable
        String message;

        @Nullable
        ParsedTest done;

        Test(String suite, String name, @Nullable String file, int line, long start) {
            this.suite = suite;
            this.name = name;
            this.file = file;
            this.line = line;
            this.start = start;
        }
    }

    /** The suites of report {@code text}, whose run was in {@code dir}; empty when it is no report. */
    public static List<ParsedSuite> parse(String text, Path dir) {
        Map<Integer, String> suites = new HashMap<>();
        Map<Integer, Test> tests = new LinkedHashMap<>();
        for (String line : text.split("\\R")) {
            JsonNode e;
            try {
                e = MAPPER.readTree(line);
            } catch (Exception notJson) {
                continue;
            }
            if (e == null) {
                continue;
            }
            switch (e.path("type").asText("")) {
                case "suite" -> {
                    String path = e.path("suite").path("path").asText("");
                    suites.put(e.path("suite").path("id").asInt(), JestJsonReport.relative(dir, path));
                }
                case "testStart" -> {
                    JsonNode t = e.path("test");
                    String suite = suites.getOrDefault(t.path("suiteID").asInt(), "tests");
                    String url = t.path("url").asText("");
                    String file = url.startsWith("file:")
                            ? fromUri(url)
                            : dir.resolve(suite).toString();
                    tests.put(
                            t.path("id").asInt(),
                            new Test(
                                    suite,
                                    t.path("name").asText(""),
                                    file,
                                    t.path("line").asInt(0),
                                    e.path("time").asLong()));
                }
                case "error" -> {
                    Test t = tests.get(e.path("testID").asInt());
                    if (t != null) {
                        String error = e.path("error").asText("").strip();
                        if (t.message == null) {
                            t.message = JestJsonReport.firstLine(error);
                        }
                        t.errors
                                .append(t.errors.isEmpty() ? "" : "\n\n")
                                .append(error)
                                .append('\n')
                                .append(e.path("stackTrace").asText("").strip());
                    }
                }
                case "print" -> {
                    Test t = tests.get(e.path("testID").asInt());
                    if (t != null && !"skip".equals(e.path("messageType").asText(""))) {
                        t.prints.append(e.path("message").asText("")).append('\n');
                    } else if (t != null && t.message == null) {
                        t.message = e.path("message").asText(null); // "Skip: why"
                    }
                }
                case "testDone" -> {
                    Test t = tests.get(e.path("testID").asInt());
                    if (t == null) {
                        continue;
                    }
                    String result = e.path("result").asText("");
                    boolean hidden = e.path("hidden").asBoolean(false);
                    if (hidden && "success".equals(result)) {
                        tests.remove(e.path("testID").asInt()); // "loading file": not a test
                        continue;
                    }
                    TestStatus status = e.path("skipped").asBoolean(false)
                            ? TestStatus.SKIPPED
                            : switch (result) {
                                case "success" -> TestStatus.PASSED;
                                case "failure" -> TestStatus.FAILED;
                                default -> TestStatus.ERROR;
                            };
                    t.done = new ParsedTest(
                            t.suite,
                            t.name,
                            status,
                            Math.max(0, e.path("time").asLong() - t.start),
                            null,
                            t.message,
                            t.errors.isEmpty() ? null : t.errors.toString(),
                            t.prints.isEmpty() ? null : t.prints.toString(),
                            t.file,
                            t.line);
                }
                default -> {}
            }
        }
        Map<String, List<ParsedTest>> bySuite = new LinkedHashMap<>();
        for (Test t : tests.values()) {
            if (t.done != null) {
                bySuite.computeIfAbsent(t.suite, k -> new ArrayList<>()).add(t.done);
            }
        }
        List<ParsedSuite> out = new ArrayList<>();
        bySuite.forEach((suite, list) -> out.add(new ParsedSuite(suite, list)));
        return out;
    }

    private static @Nullable String fromUri(String uri) {
        try {
            return Path.of(java.net.URI.create(uri)).toString();
        } catch (RuntimeException notAFile) {
            return null;
        }
    }
}
