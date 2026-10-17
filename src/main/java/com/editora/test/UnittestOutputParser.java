package com.editora.test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Folds the verbose output of {@code python -m unittest -v} into Test Results — the runner Python ships with,
 * used when pytest is not installed. Each result line ({@code test_add (pkg.test_m.T.test_add) ... ok}) becomes a
 * result as it is printed; the {@code FAIL:}/{@code ERROR:} blocks at the end add the traceback to their test.
 * Handles the pre-3.11 form ({@code (pkg.test_m.T)}), a docstring line between the name and the result, subtest
 * suffixes, and {@code skipped 'why'}. A stream parser: one instance per run.
 */
public final class UnittestOutputParser implements TestResultParser {

    private static final String RESULT = "(ok|FAIL|ERROR|skipped(?: .*)?|expected failure|unexpected success)";

    /** {@code name (dotted.id)} then anything (a subtest's parameters), then {@code ... result}. */
    private static final Pattern LINE = Pattern.compile("^(\\w+) \\(([\\w.]+)\\)(.*?) \\.\\.\\. " + RESULT + "$");

    /**
     * A header whose result follows on a later line: after the test's docstring, or — ending in {@code ...} —
     * after its subtests' own lines.
     */
    private static final Pattern HEADER = Pattern.compile("^(\\w+) \\(([\\w.]+)\\)(?: \\.\\.\\.)?$");

    private static final Pattern TAIL = Pattern.compile("^.* \\.\\.\\. " + RESULT + "$");

    private static final Pattern BLOCK = Pattern.compile("^(FAIL|ERROR): (\\w+) \\(([\\w.]+)\\)");

    private String pendingName;
    private String pendingId;

    /** The failure block being read: its test, and its lines once past the dashed rule. */
    private String blockName;

    private String blockId;
    private boolean blockError;
    private boolean inTrace;
    private final List<String> trace = new ArrayList<>();

    @Override
    public List<ParsedSuite> onLine(String line, boolean stderr) {
        String l = line == null ? "" : line.strip(); // a subtest's line is indented
        if (blockName != null) {
            if (isRule(l, '=') || (inTrace && isRule(l, '-'))) {
                List<ParsedSuite> done = closeBlock();
                return startBlockOr(l, done);
            }
            if (!inTrace && isRule(l, '-')) {
                inTrace = true;
            } else if (inTrace) {
                trace.add(line);
            }
            return List.of();
        }
        Matcher m = LINE.matcher(l);
        if (m.matches()) {
            pendingName = null;
            return result(m.group(1), m.group(2), m.group(4));
        }
        m = HEADER.matcher(l);
        if (m.matches()) {
            pendingName = m.group(1);
            pendingId = m.group(2);
            return List.of();
        }
        if (pendingName != null) {
            m = TAIL.matcher(l);
            if (m.matches()) {
                String name = pendingName;
                pendingName = null;
                return result(name, pendingId, m.group(1));
            }
        }
        return startBlockOr(l, List.of());
    }

    @Override
    public List<ParsedSuite> onExit(int code) {
        return blockName == null ? List.of() : closeBlock();
    }

    private List<ParsedSuite> startBlockOr(String l, List<ParsedSuite> done) {
        Matcher m = BLOCK.matcher(l);
        if (m.find()) {
            blockError = m.group(1).equals("ERROR");
            blockName = m.group(2);
            blockId = m.group(3);
            inTrace = false;
            trace.clear();
        }
        return done;
    }

    private List<ParsedSuite> closeBlock() {
        String cls = className(blockName, blockId);
        String stack = String.join("\n", trace).strip();
        String message = null;
        int end = trace.size(); // the exception line comes before the test's captured "Stdout:"/"Stderr:"
        for (int i = 0; i < trace.size(); i++) {
            String t = trace.get(i).strip();
            if (t.equals("Stdout:") || t.equals("Stderr:")) {
                end = i;
                break;
            }
        }
        for (int i = end - 1; i >= 0; i--) {
            if (!trace.get(i).isBlank()) {
                message = trace.get(i).strip();
                break;
            }
        }
        ParsedTest t = new ParsedTest(
                cls,
                blockName,
                blockError ? TestStatus.ERROR : TestStatus.FAILED,
                0,
                null,
                message,
                stack.isEmpty() ? null : stack,
                null,
                null,
                0);
        blockName = null;
        inTrace = false;
        trace.clear();
        return List.of(new ParsedSuite(cls, List.of(t)));
    }

    private static List<ParsedSuite> result(String name, String id, String outcome) {
        TestStatus status =
                switch (outcome) {
                    case "ok", "expected failure" -> TestStatus.PASSED;
                    case "FAIL", "unexpected success" -> TestStatus.FAILED;
                    case "ERROR" -> TestStatus.ERROR;
                    default -> TestStatus.SKIPPED; // skipped 'why'
                };
        String message = outcome.startsWith("skipped ") ? outcome.substring("skipped ".length()) : null;
        String cls = className(name, id);
        ParsedTest t = new ParsedTest(cls, name, status, 0, null, message, null, null, null, 0);
        return List.of(new ParsedSuite(cls, List.of(t)));
    }

    /** {@code pkg.test_m.T.test_add} (3.11+) or {@code pkg.test_m.T} (older) → {@code pkg.test_m.T}. */
    static String className(String name, String id) {
        return id.endsWith("." + name) ? id.substring(0, id.length() - name.length() - 1) : id;
    }

    private static boolean isRule(String l, char c) {
        if (l.length() < 20) {
            return false;
        }
        for (int i = 0; i < l.length(); i++) {
            if (l.charAt(i) != c) {
                return false;
            }
        }
        return true;
    }
}
