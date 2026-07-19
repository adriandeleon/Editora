package com.editora.test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for <a href="https://testanything.org">TAP</a> output — the only common denominator for
 * npm test runners (jest/mocha/vitest/node:test all differ; several can emit TAP with a reporter flag). Parses
 * {@code ok N - desc} / {@code not ok N - desc} lines (a {@code # SKIP}/{@code # TODO} directive → skipped) and
 * attaches the indented {@code --- … ...} YAML diagnostic block to the preceding failure's message. All tests
 * group under one {@code "npm test"} suite. Engaged only when {@link #looksLikeTap} matches; otherwise the
 * coordinator degrades to the raw console. Never throws.
 */
public final class TapParser implements TestResultParser {

    static final String SUITE = "npm test";

    private static final Pattern LINE = Pattern.compile("^(ok|not ok)\\s+(\\d+)\\s*-?\\s*(.*)$");
    private static final Pattern DIRECTIVE = Pattern.compile("\\s*#\\s*(SKIP|TODO)\\b.*$", Pattern.CASE_INSENSITIVE);

    private String lastName;
    private TestStatus lastStatus;
    private boolean inYaml;
    private StringBuilder yaml;

    /** True if the first lines look like TAP (a {@code 1..N} plan, an {@code ok}/{@code not ok}, or the header). */
    public static boolean looksLikeTap(List<String> firstLines) {
        for (String raw : firstLines) {
            String line = raw.strip();
            if (line.matches("^\\d+\\.\\.\\d+.*")
                    || line.startsWith("ok ")
                    || line.startsWith("not ok ")
                    || line.equals("ok")
                    || line.startsWith("TAP version")) {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ParsedSuite> onLine(String line, boolean stderr) {
        String trimmed = line.strip();

        if (inYaml) {
            if (trimmed.equals("...")) {
                inYaml = false;
                String msg = yaml.toString().strip();
                return msg.isEmpty()
                        ? List.of()
                        : List.of(new ParsedSuite(
                                SUITE,
                                List.of(new ParsedTest(
                                        SUITE, lastName, lastStatus, 0, null, msg, msg, null, null, 0))));
            }
            yaml.append(line).append('\n');
            return List.of();
        }

        Matcher m = LINE.matcher(trimmed);
        if (m.matches()) {
            boolean ok = "ok".equals(m.group(1));
            String number = m.group(2);
            String rest = m.group(3);
            boolean skip = DIRECTIVE.matcher(rest).find();
            String name = DIRECTIVE.matcher(rest).replaceAll("").strip();
            if (name.isEmpty()) {
                name = "test " + number;
            }
            TestStatus status = skip ? TestStatus.SKIPPED : (ok ? TestStatus.PASSED : TestStatus.FAILED);
            lastName = name;
            lastStatus = status;
            return List.of(new ParsedSuite(SUITE, List.of(ParsedTest.of(SUITE, name, status, 0))));
        }
        if (trimmed.equals("---") && lastName != null && lastStatus == TestStatus.FAILED) {
            inYaml = true;
            yaml = new StringBuilder();
        }
        return List.of();
    }
}
