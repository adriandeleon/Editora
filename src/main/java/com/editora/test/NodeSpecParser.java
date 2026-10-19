package com.editora.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads node:test's default <b>spec</b> reporter — what {@code node --test} prints when its output is not a
 * terminal (Node 23+), so a plain {@code "test": "node --test"} script fills Test Results without anyone adding
 * {@code --test-reporter=tap}. Pure; colour escapes are ignored.
 *
 * <pre>
 * ✔ adds (0.9ms)
 * ▶ group
 *   ✖ inner bad (0.1ms)
 * ✖ group (0.7ms)
 * ℹ tests 2
 * ✖ failing tests:
 *
 * test at a.test.js:5:68
 * ✖ inner bad (0.1ms)
 *   Error: boom
 *       at TestContext.&lt;anonymous&gt; (/p/a.test.js:5:98)
 * </pre>
 *
 * <p>A {@code ▶ name} line opens a suite, closed by the result line with its name at the same indent (no row);
 * every other result is a test named after its open suites ({@code group › inner bad}, as {@link TapParser}
 * names them). The {@code failing tests:} section (and, from older Nodes, the indented detail right under a
 * failure) gives each failure its message, frames and {@code file:line}.
 */
public final class NodeSpecParser implements TestResultParser {

    private static final Pattern RESULT =
            Pattern.compile("^( *)([✔✖﹣▶])\\s+(.+?)(?:\\s+\\((\\d+(?:\\.\\d+)?)ms\\))?(\\s+#\\s*(SKIP|TODO)\\b.*)?$");
    private static final Pattern TEST_AT = Pattern.compile("^test at (.+?):(\\d+)(?::\\d+)?$");
    private static final String FAILING = "✖ failing tests:";

    private final String suite;
    /** Open suites: name per indent level (two spaces a level). */
    private final List<String> open = new ArrayList<>();

    private final Map<String, Integer> occurrences = new HashMap<>();
    /** Failed tests by their bare name, in order, for the failing-tests section to attach detail to. */
    private final Map<String, List<String>> failedByBareName = new HashMap<>();

    private boolean inFailures;
    private String detailFor;
    private String detailFile;
    private int detailLine;
    private int detailIndent = -1;
    private final List<String> detail = new ArrayList<>();

    public NodeSpecParser() {
        this(TapParser.SUITE);
    }

    public NodeSpecParser(String suite) {
        this.suite = suite;
    }

    /** Whether lines look like node:test's spec reporter (a timed ✔/✖ result or its {@code ℹ tests N} summary). */
    public static boolean looksLikeSpec(List<String> lines) {
        for (String raw : lines) {
            String line = NodeTestFailure.stripAnsi(raw);
            if (line.strip().matches("^ℹ tests \\d+$")) {
                return true;
            }
            Matcher m = RESULT.matcher(line);
            if (m.matches() && m.group(4) != null && m.group(2).charAt(0) != '▶') {
                return true;
            }
        }
        return false;
    }

    @Override
    public List<ParsedSuite> onLine(String rawLine, boolean stderr) {
        String line = NodeTestFailure.stripAnsi(rawLine);
        if (line.strip().equals(FAILING)) {
            List<ParsedSuite> out = flushDetail();
            inFailures = true;
            return out;
        }
        if (inFailures) {
            return failureSection(line);
        }
        Matcher m = RESULT.matcher(line);
        if (!m.matches()) {
            if (detailFor != null && !line.strip().startsWith("ℹ")) {
                addDetail(line); // an older Node's inline detail under the failure
            }
            return List.of();
        }
        List<ParsedSuite> out = new ArrayList<>(flushDetail());
        int level = m.group(1).length() / 2;
        char mark = m.group(2).charAt(0);
        String bare = m.group(3).strip();
        if (mark == '▶') {
            truncate(level);
            open.add(bare);
            return out;
        }
        if (level < open.size() && open.get(level).equals(bare)) {
            truncate(level); // the suite's own closing line
            return out;
        }
        truncate(level);
        String name = open.isEmpty()
                ? bare
                : String.join(TapParser.PARENT_SEPARATOR, open) + TapParser.PARENT_SEPARATOR + bare;
        int seen = occurrences.merge(name, 1, Integer::sum);
        if (seen > 1) {
            name = name + " (" + seen + ")";
        }
        TestStatus status = m.group(6) != null || mark == '﹣'
                ? TestStatus.SKIPPED
                : mark == '✔' ? TestStatus.PASSED : TestStatus.FAILED;
        long ms = m.group(4) == null ? 0 : Math.round(Double.parseDouble(m.group(4)));
        out.add(new ParsedSuite(suite, List.of(ParsedTest.of(suite, name, status, ms))));
        if (status == TestStatus.FAILED) {
            failedByBareName.computeIfAbsent(bare, k -> new ArrayList<>()).add(name);
            detailFor = name;
            detailFile = null;
            detailLine = 0;
        }
        return out;
    }

    private List<ParsedSuite> failureSection(String line) {
        Matcher at = TEST_AT.matcher(line.strip());
        if (at.matches()) {
            List<ParsedSuite> out = flushDetail();
            detailFile = at.group(1);
            detailLine = Integer.parseInt(at.group(2));
            detailFor = null;
            return out;
        }
        Matcher m = RESULT.matcher(line);
        if (m.matches() && m.group(2).charAt(0) == '✖' && detailFile != null && detailFor == null) {
            List<String> names = failedByBareName.get(m.group(3).strip());
            detailFor = names == null || names.isEmpty() ? m.group(3).strip() : names.remove(0);
            return List.of();
        }
        if (detailFor != null) {
            addDetail(line);
        }
        return List.of();
    }

    private void addDetail(String line) {
        if (line.isBlank()) {
            detail.add("");
            return;
        }
        int indent = 0;
        while (indent < line.length() && line.charAt(indent) == ' ') {
            indent++;
        }
        if (detailIndent < 0) {
            detailIndent = indent;
        }
        detail.add(line.substring(Math.min(indent, detailIndent)));
    }

    @Override
    public List<ParsedSuite> onExit(int code) {
        return flushDetail();
    }

    /** The detail gathered for the last failure, as an update of its row; empty when there is none. */
    private List<ParsedSuite> flushDetail() {
        String name = detailFor;
        List<String> lines = new ArrayList<>(detail);
        detail.clear();
        detailIndent = -1;
        detailFor = null;
        if (name == null || lines.stream().allMatch(String::isBlank)) {
            return List.of();
        }
        StringBuilder message = new StringBuilder();
        StringBuilder stack = new StringBuilder();
        if (detailFile != null) {
            stack.append("    at ")
                    .append(name)
                    .append(" (")
                    .append(detailFile)
                    .append(':')
                    .append(detailLine)
                    .append(")\n");
        }
        boolean frames = false;
        for (String l : lines) {
            String t = l.strip();
            if (t.startsWith("at ")) {
                frames = true;
                stack.append("    ").append(t).append('\n');
            } else if (!frames) {
                message.append(l).append('\n'); // the error's own properties come after its frames: left out
            }
        }
        String file = detailFile == null ? null : detailFile.replace('\\', '/');
        ParsedTest failed = new ParsedTest(
                suite,
                name,
                TestStatus.FAILED,
                0,
                null,
                message.toString().strip(),
                stack.isEmpty() ? null : stack.toString(),
                null,
                file == null ? null : file.substring(file.lastIndexOf('/') + 1),
                detailLine);
        return List.of(new ParsedSuite(suite, List.of(failed)));
    }

    private void truncate(int size) {
        while (open.size() > size) {
            open.remove(open.size() - 1);
        }
    }
}
