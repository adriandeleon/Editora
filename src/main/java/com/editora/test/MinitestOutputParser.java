package com.editora.test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Reads Minitest's verbose console ({@code -v}, {@code bin/rails test -v}) as it runs: a test per
 * {@code Class#test_name = 0.00 s = F} line, as it finishes, in a suite per class; then each {@code Failure:},
 * {@code Error:} or {@code Skipped:} block — numbered at the end of a plain run, right after the test in Rails' —
 * gives its test the message, the trace and the {@code [file:line]}. Pure; state is the block being read.
 */
public final class MinitestOutputParser implements TestResultParser {

    /** {@code Class#test = 0.00 s = .}: a class may hold a {@code #} ({@code describe "#name"}), a test starts so. */
    private static final Pattern RESULT = Pattern.compile("^(.+?)#(test.*) = (\\d+(?:\\.\\d+)?) s = ([.FES])$");

    private static final Pattern BLOCK = Pattern.compile("^\\s*(?:\\d+\\) )?(Failure|Error|Skipped):$");

    /** The block's test: {@code Class#test [file:line]:} (a failure, a skip) or {@code Class#test:} (an error). */
    private static final Pattern WHO = Pattern.compile("^(.+?)#(test.*?)(?: \\[(.+):(\\d+)\\])?:$");

    private static final Pattern SUMMARY = Pattern.compile("^\\d+ (?:runs|tests), \\d+ assertions.*");

    private final Path dir;
    private final Map<String, Long> durations = new HashMap<>();
    private @Nullable String blockKind;
    private @Nullable String blockClass;
    private @Nullable String blockTest;
    private @Nullable String blockFile;
    private int blockLine;
    private final List<String> blockText = new ArrayList<>();

    public MinitestOutputParser(Path dir) {
        this.dir = dir;
    }

    @Override
    public List<ParsedSuite> onLine(String raw, boolean stderr) {
        String line = NodeTestFailure.stripAnsi(raw).stripTrailing();
        Matcher r = RESULT.matcher(line);
        if (r.matches()) {
            List<ParsedSuite> out = new ArrayList<>(flush());
            long ms = Math.round(Double.parseDouble(r.group(3)) * 1000);
            durations.put(r.group(1) + "#" + r.group(2), ms);
            TestStatus status = switch (r.group(4)) {
                case "F" -> TestStatus.FAILED;
                case "E" -> TestStatus.ERROR;
                case "S" -> TestStatus.SKIPPED;
                default -> TestStatus.PASSED;
            };
            out.add(new ParsedSuite(r.group(1), List.of(ParsedTest.of(r.group(1), r.group(2), status, ms))));
            return out;
        }
        Matcher b = BLOCK.matcher(line);
        if (b.matches()) {
            List<ParsedSuite> out = flush();
            blockKind = b.group(1);
            return out;
        }
        if (blockKind != null && blockTest == null) {
            Matcher w = WHO.matcher(line.strip());
            if (w.matches()) {
                blockClass = w.group(1);
                blockTest = w.group(2);
                blockFile = w.group(3);
                blockLine = w.group(4) == null ? 0 : Integer.parseInt(w.group(4));
                return List.of();
            }
            blockKind = null; // not a block after all
            return List.of();
        }
        if (blockTest != null) {
            if (SUMMARY.matcher(line.strip()).matches() || line.startsWith("Finished in ")) {
                return flush();
            }
            blockText.add(line);
        }
        return List.of();
    }

    @Override
    public List<ParsedSuite> onExit(int code) {
        return flush();
    }

    /** The block read so far, as its test's result; nothing when there is none. */
    private List<ParsedSuite> flush() {
        String cls = blockClass;
        String test = blockTest;
        String kind = blockKind;
        blockKind = null;
        blockClass = null;
        blockTest = null;
        if (cls == null || test == null || kind == null) {
            blockText.clear();
            return List.of();
        }
        while (!blockText.isEmpty() && blockText.getLast().isBlank()) {
            blockText.removeLast();
        }
        // Rails ends a failure with how to rerun it: not part of the message.
        if (!blockText.isEmpty() && blockText.getLast().strip().startsWith("bin/rails test ")) {
            blockText.removeLast();
        }
        String text = String.join("\n", blockText).strip();
        blockText.clear();
        String message = text.isEmpty() ? null : JestJsonReport.firstLine(text);
        TestStatus status = switch (kind) {
            case "Error" -> TestStatus.ERROR;
            case "Skipped" -> TestStatus.SKIPPED;
            default -> TestStatus.FAILED;
        };
        String file =
                blockFile == null ? null : dir.resolve(blockFile).normalize().toString();
        String trace = blockFile == null ? text : text + "\n" + blockFile + ":" + blockLine;
        ParsedTest result = new ParsedTest(
                cls,
                test,
                status,
                durations.getOrDefault(cls + "#" + test, 0L),
                null,
                message,
                trace.isBlank() ? null : trace,
                null,
                file,
                blockLine);
        return List.of(new ParsedSuite(cls, List.of(result)));
    }
}
