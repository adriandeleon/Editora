package com.editora.test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Best-effort parser for <a href="https://testanything.org">TAP</a> output — the only common denominator for
 * npm test runners (jest/mocha/vitest/node:test all differ; several can emit TAP with a reporter flag). Parses
 * {@code ok N - desc} / {@code not ok N - desc} lines (the number is optional; a {@code # SKIP}/{@code # TODO}
 * directive → skipped) and attaches the indented {@code --- … ...} YAML diagnostic block to the preceding
 * failure's message. All tests group under one {@code "npm test"} suite. Engaged only when
 * {@link #looksLikeTap} matches; otherwise the coordinator degrades to the raw console. Never throws.
 *
 * <p>Every result gets its <b>own</b> row. A subtest is named after its parents ({@code # Subtest:} lines,
 * nested by indentation — {@code Parser › handles empty input}), and a name that still repeats gets an
 * occurrence suffix ({@code should be equal (2)}). Keyed by the bare description, two tests with the same
 * name shared one row whose status was whichever arrived last — a failure shown as passed.
 */
public final class TapParser implements TestResultParser {

    static final String SUITE = "npm test";

    private static final Pattern LINE = Pattern.compile("^(not ok|ok)(?:\\s+(\\d+))?(?:\\s*-\\s*|\\s+|$)(.*)$");
    private static final Pattern DIRECTIVE = Pattern.compile("\\s*#\\s*(SKIP|TODO)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBTEST = Pattern.compile("^#\\s*Subtest:\\s*(.+)$");
    private static final String PARENT_SEPARATOR = " \u203a ";

    /** The {@code # Subtest:} names still open, outermost first; index = nesting level. */
    private final java.util.List<String> openSubtests = new java.util.ArrayList<>();
    /** How many results have used each full name so far. */
    private final java.util.Map<String, Integer> occurrences = new java.util.HashMap<>();

    private int unnamed;
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

        int level = nestingLevel(line);
        Matcher subtest = SUBTEST.matcher(trimmed);
        if (subtest.matches()) {
            truncate(openSubtests, level);
            openSubtests.add(subtest.group(1).strip());
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
                name = "test " + (number != null ? number : Integer.toString(++unnamed));
            }
            // The parents are the subtests open above this level; the result closes its own.
            List<String> parents = openSubtests.subList(0, Math.min(level, openSubtests.size()));
            if (!parents.isEmpty()) {
                name = String.join(PARENT_SEPARATOR, parents) + PARENT_SEPARATOR + name;
            }
            truncate(openSubtests, level);
            int seen = occurrences.merge(name, 1, Integer::sum);
            if (seen > 1) {
                name = name + " (" + seen + ")";
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

    /** TAP nests a subtest's lines four spaces deeper than its parent's. */
    private static int nestingLevel(String line) {
        int spaces = 0;
        while (spaces < line.length() && line.charAt(spaces) == ' ') {
            spaces++;
        }
        return spaces / 4;
    }

    private static void truncate(List<String> list, int size) {
        while (list.size() > size) {
            list.remove(list.size() - 1);
        }
    }
}
