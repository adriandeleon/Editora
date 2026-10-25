package com.editora.test;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

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
 *
 * <p>node:test closes each {@code describe} with a result line of its own; a result whose subtest had results
 * nested under it is that suite, not a test, and gets no row ("3 of 5 failed" for two failures in four tests).
 * A failure's YAML is read by {@link NodeTestFailure}: its {@code error} is the message, {@code stack} and
 * {@code location} the frames — not the {@code duration_ms}/{@code type} lines it used to show.
 */
public final class TapParser implements TestResultParser {

    static final String SUITE = "npm test";

    private static final Pattern LINE = Pattern.compile("^(not ok|ok)(?:\\s+(\\d+))?(?:\\s*-\\s*|\\s+|$)(.*)$");
    private static final Pattern DIRECTIVE = Pattern.compile("\\s*#\\s*(SKIP|TODO)\\b.*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern SUBTEST = Pattern.compile("^#\\s*Subtest:\\s*(.+)$");
    static final String PARENT_SEPARATOR = " \u203a ";

    private final String suite;
    /** The {@code # Subtest:} names still open, outermost first; index = nesting level. */
    private final java.util.List<String> openSubtests = new java.util.ArrayList<>();
    /** Per nesting level: how many results were reported one level deeper since that level's last result. */
    private final java.util.List<Integer> nested = new java.util.ArrayList<>();
    /** How many results have used each full name so far. */
    private final java.util.Map<String, Integer> occurrences = new java.util.HashMap<>();

    private int unnamed;
    private @Nullable String lastName;
    private @Nullable TestStatus lastStatus;
    private boolean inYaml;
    private final java.util.List<String> yaml = new java.util.ArrayList<>();
    private boolean sawSubtests;

    public TapParser() {
        this(SUITE);
    }

    /** A parser whose results group under {@code suite} (a test file's name, for a file's own run). */
    public TapParser(String suite) {
        this.suite = suite;
    }

    /** True if the first lines look like TAP (a {@code 1..N} plan, an {@code ok}/{@code not ok}, or the header). */
    public static boolean looksLikeTap(List<String> firstLines) {
        for (String raw : firstLines) {
            String line = NodeTestFailure.stripAnsi(raw).strip();
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

    /** Whether this run's TAP came from node:test (its {@code # Subtest:} lines) — it can filter by name. */
    public boolean nodeTest() {
        return sawSubtests;
    }

    @Override
    public List<ParsedSuite> onLine(String rawLine, boolean stderr) {
        String line = NodeTestFailure.stripAnsi(rawLine);
        String trimmed = line.strip();

        if (inYaml) {
            if (trimmed.equals("...")) {
                inYaml = false;
                NodeTestFailure failure = NodeTestFailure.fromYaml(yaml, lastName);
                yaml.clear();
                if (lastName == null || lastStatus != TestStatus.FAILED || failure.suite()) {
                    return List.of();
                }
                return List.of(new ParsedSuite(suite, List.of(failure.toParsedTest(suite, lastName, lastStatus))));
            }
            yaml.add(line);
            return List.of();
        }

        int level = nestingLevel(line);
        Matcher subtest = SUBTEST.matcher(trimmed);
        if (subtest.matches()) {
            sawSubtests = true;
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
            // A result whose subtest had results of its own is a suite (node:test's describe): no row.
            boolean isSuite = count(level) > 0;
            set(level, 0);
            if (level > 0) {
                set(level - 1, count(level - 1) + 1);
            }
            // The parents are the subtests open above this level; the result closes its own.
            List<String> parents = openSubtests.subList(0, Math.min(level, openSubtests.size()));
            if (!parents.isEmpty()) {
                name = String.join(PARENT_SEPARATOR, parents) + PARENT_SEPARATOR + name;
            }
            truncate(openSubtests, level);
            if (isSuite) {
                lastName = null;
                lastStatus = null;
                return List.of();
            }
            int seen = occurrences.merge(name, 1, Integer::sum);
            if (seen > 1) {
                name = name + " (" + seen + ")";
            }
            TestStatus status = skip ? TestStatus.SKIPPED : (ok ? TestStatus.PASSED : TestStatus.FAILED);
            lastName = name;
            lastStatus = status;
            return List.of(new ParsedSuite(suite, List.of(ParsedTest.of(suite, name, status, 0))));
        }
        if (trimmed.equals("---")) {
            inYaml = true; // read even for a pass: a suite's block must not be taken for a result line
            yaml.clear();
        }
        return List.of();
    }

    private int count(int level) {
        return level < nested.size() ? nested.get(level) : 0;
    }

    private void set(int level, int value) {
        while (nested.size() <= level) {
            nested.add(0);
        }
        nested.set(level, value);
        for (int i = level + 1; i < nested.size(); i++) {
            nested.set(i, 0);
        }
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
