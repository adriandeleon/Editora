package com.editora.test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for {@code cargo test}'s stable libtest <em>text</em> output. Per-result lines
 * ({@code test module::name ... ok|FAILED|ignored}) drive the tree live; the trailing {@code failures:} block
 * ({@code ---- module::name stdout ----} sections) carries panic messages/backtraces, flushed in
 * {@link #onExit} onto the matching failed tests. All tests group under one {@code "cargo test"} suite —
 * libtest has no class level and default output has no per-test timing. Custom harnesses / nextest differ;
 * this is deliberately tolerant and never throws.
 */
public final class CargoTestParser implements TestResultParser {

    static final String SUITE = "cargo test";

    private static final Pattern RESULT =
            Pattern.compile("^test\\s+(\\S.*?)\\s+\\.\\.\\.\\s+(ok|FAILED|ignored)\\b.*$");
    private static final Pattern SECTION = Pattern.compile("^----\\s+(\\S.*?)\\s+stdout\\s+----$");

    private final Map<String, StringBuilder> failureText = new LinkedHashMap<>();
    private String capturing; // the test name whose stdout section we are inside, or null

    @Override
    public List<ParsedSuite> onLine(String line, boolean stderr) {
        String trimmed = line.strip();

        if (capturing != null) {
            Matcher next = SECTION.matcher(trimmed);
            if (next.matches()) {
                capturing = next.group(1);
                failureText.computeIfAbsent(capturing, k -> new StringBuilder());
                return List.of();
            }
            if (trimmed.equals("failures:") || trimmed.startsWith("test result:")) {
                capturing = null; // end of the stdout sections — fall through to normal handling
            } else {
                failureText.get(capturing).append(line).append('\n');
                return List.of();
            }
        }

        Matcher section = SECTION.matcher(trimmed);
        if (section.matches()) {
            capturing = section.group(1);
            failureText.computeIfAbsent(capturing, k -> new StringBuilder());
            return List.of();
        }
        Matcher result = RESULT.matcher(trimmed);
        if (result.matches()) {
            String name = result.group(1);
            TestStatus status =
                    switch (result.group(2)) {
                        case "FAILED" -> TestStatus.FAILED;
                        case "ignored" -> TestStatus.SKIPPED;
                        default -> TestStatus.PASSED;
                    };
            return List.of(new ParsedSuite(SUITE, List.of(ParsedTest.of(SUITE, name, status, 0))));
        }
        return List.of();
    }

    @Override
    public List<ParsedSuite> onExit(int code) {
        if (failureText.isEmpty()) {
            return List.of();
        }
        List<ParsedTest> tests = new ArrayList<>();
        for (Map.Entry<String, StringBuilder> e : failureText.entrySet()) {
            String msg = e.getValue().toString().strip();
            tests.add(new ParsedTest(
                    SUITE,
                    e.getKey(),
                    TestStatus.FAILED,
                    0,
                    null,
                    msg.isEmpty() ? null : msg,
                    msg.isEmpty() ? null : msg,
                    null,
                    null,
                    0));
        }
        return List.of(new ParsedSuite(SUITE, tests));
    }
}
