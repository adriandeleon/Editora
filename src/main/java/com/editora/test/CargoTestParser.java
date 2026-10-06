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
 * {@link #onExit} onto the matching failed tests. libtest has no class level and default output has no
 * per-test timing. Custom harnesses / nextest differ; this is deliberately tolerant and never throws.
 *
 * <p>Each test binary is its own suite, named from cargo's {@code Running … (target/…/name-hash)} header
 * ({@code alpha (src/lib.rs)}); output with no header groups under {@code "cargo test"}. With one suite for
 * everything, {@code tests::it_works} of two workspace crates shared a row and the later result won.
 */
public final class CargoTestParser implements TestResultParser {

    static final String SUITE = "cargo test";

    private static final Pattern RESULT =
            Pattern.compile("^test\\s+(\\S.*?)\\s+\\.\\.\\.\\s+(ok|FAILED|ignored)\\b.*$");
    private static final Pattern SECTION = Pattern.compile("^----\\s+(\\S.*?)\\s+stdout\\s+----$");
    private static final Pattern RUNNING = Pattern.compile("^Running\\s+(.*?)\\s*\\((.+)\\)$");
    private static final Pattern DOC_TESTS = Pattern.compile("^Doc-tests\\s+(\\S+)$");
    private static final Pattern HASH_SUFFIX = Pattern.compile("-[0-9a-f]{16}$");
    /** libtest appends this to a {@code #[should_panic]} test's result line, but not to its failure section. */
    private static final String SHOULD_PANIC = " - should panic";

    /** A failure section's owner: the suite it was printed under plus the test name. */
    private record Key(String suite, String name) {}

    private final Map<Key, StringBuilder> failureText = new LinkedHashMap<>();
    private String suite = SUITE;
    private Key capturing; // the test whose stdout section we are inside, or null

    @Override
    public List<ParsedSuite> onLine(String line, boolean stderr) {
        String trimmed = line.strip();

        String header = suiteOf(trimmed);
        if (header != null) {
            suite = header;
            capturing = null;
            return List.of();
        }
        if (capturing != null) {
            Matcher next = SECTION.matcher(trimmed);
            if (next.matches()) {
                capturing = new Key(suite, next.group(1));
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
            capturing = new Key(suite, section.group(1));
            failureText.computeIfAbsent(capturing, k -> new StringBuilder());
            return List.of();
        }
        Matcher result = RESULT.matcher(trimmed);
        if (result.matches()) {
            String name = result.group(1);
            if (name.endsWith(SHOULD_PANIC)) {
                name = name.substring(0, name.length() - SHOULD_PANIC.length()); // else it is counted twice
            }
            TestStatus status =
                    switch (result.group(2)) {
                        case "FAILED" -> TestStatus.FAILED;
                        case "ignored" -> TestStatus.SKIPPED;
                        default -> TestStatus.PASSED;
                    };
            return List.of(new ParsedSuite(suite, List.of(ParsedTest.of(suite, name, status, 0))));
        }
        return List.of();
    }

    /**
     * The suite a {@code Running unittests src/lib.rs (target/debug/deps/alpha-0123456789abcdef)} or
     * {@code Doc-tests alpha} header starts, or {@code null} for any other line.
     */
    static String suiteOf(String trimmed) {
        Matcher doc = DOC_TESTS.matcher(trimmed);
        if (doc.matches()) {
            return doc.group(1) + " (doc-tests)";
        }
        Matcher running = RUNNING.matcher(trimmed);
        if (!running.matches()) {
            return null;
        }
        String binary = running.group(2).replace('\\', '/');
        binary = binary.substring(binary.lastIndexOf('/') + 1);
        if (binary.endsWith(".exe")) {
            binary = binary.substring(0, binary.length() - ".exe".length());
        }
        binary = HASH_SUFFIX.matcher(binary).replaceFirst("");
        String target = running.group(1).strip();
        if (target.startsWith("unittests ")) {
            target = target.substring("unittests ".length()).strip();
        }
        return target.isEmpty() ? binary : binary + " (" + target + ")";
    }

    @Override
    public List<ParsedSuite> onExit(int code) {
        if (failureText.isEmpty()) {
            return List.of();
        }
        Map<String, List<ParsedTest>> bySuite = new LinkedHashMap<>();
        for (Map.Entry<Key, StringBuilder> e : failureText.entrySet()) {
            String msg = e.getValue().toString().strip();
            Key key = e.getKey();
            bySuite.computeIfAbsent(key.suite(), k -> new ArrayList<>())
                    .add(new ParsedTest(
                            key.suite(),
                            key.name(),
                            TestStatus.FAILED,
                            0,
                            null,
                            msg.isEmpty() ? null : msg,
                            msg.isEmpty() ? null : msg,
                            null,
                            null,
                            0));
        }
        List<ParsedSuite> out = new ArrayList<>();
        bySuite.forEach((name, tests) -> out.add(new ParsedSuite(name, tests)));
        return out;
    }
}
