package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Reads RSpec's {@code --format json} report: a suite per spec file, a test per example named by its full
 * description (which {@code --example} matches on a rerun), its line, and a failure's exception class, message and
 * backtrace. Pure.
 */
public final class RspecJsonReport {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RspecJsonReport() {}

    /** The suites of report {@code json}, whose run was in {@code dir}; empty when it is no report. */
    public static List<ParsedSuite> parse(String json, Path dir) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (Exception notJson) {
            return List.of();
        }
        Map<String, List<ParsedTest>> byFile = new LinkedHashMap<>();
        if (root == null) {
            return List.of();
        }
        for (JsonNode e : root.path("examples")) {
            String file = e.path("file_path").asText("");
            String suite = file.startsWith("./") ? file.substring(2) : file;
            String abs = file.isEmpty() ? null : dir.resolve(suite).normalize().toString();
            JsonNode ex = e.path("exception");
            String type = ex.isObject() ? ex.path("class").asText(null) : null;
            String message = ex.isObject() ? ex.path("message").asText("").strip() : null;
            StringBuilder trace = new StringBuilder();
            if (ex.isObject()) {
                trace.append(type).append(": ").append(message);
                for (JsonNode frame : ex.path("backtrace")) {
                    trace.append("\n# ").append(frame.asText(""));
                }
            }
            String status = e.path("status").asText("");
            TestStatus s = switch (status) {
                case "passed" -> TestStatus.PASSED;
                case "failed" -> TestStatus.FAILED;
                default -> TestStatus.SKIPPED; // pending
            };
            if (s == TestStatus.SKIPPED && message == null) {
                message = e.path("pending_message").asText(null);
            }
            byFile.computeIfAbsent(suite.isEmpty() ? "spec" : suite, k -> new ArrayList<>())
                    .add(new ParsedTest(
                            suite,
                            e.path("full_description")
                                    .asText(e.path("description").asText("")),
                            s,
                            Math.round(e.path("run_time").asDouble(0) * 1000),
                            type,
                            message == null || message.isEmpty() ? null : JestJsonReport.firstLine(message),
                            ex.isObject() ? trace.toString() : null,
                            null,
                            abs,
                            e.path("line_number").asInt(0)));
        }
        List<ParsedSuite> out = new ArrayList<>();
        byFile.forEach((suite, tests) -> out.add(new ParsedSuite(suite, tests)));
        return out;
    }
}
