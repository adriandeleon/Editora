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
 * Reads ExUnit's console under {@code mix test --trace} as it runs: a suite per test module ({@code CalcTest
 * [test/calc_test.exs]}), a test per {@code * test adds (0.01ms) [L#4]} line — its line, the time it took, skipped —
 * as it finishes; then the numbered failure block ExUnit prints right after it gives it its message and stack. Tests a
 * filter excluded are left out. Pure; state is the module and block being read.
 */
public final class ExUnitTraceParser implements TestResultParser {

    private static final Pattern MODULE = Pattern.compile("^(\\S.*) \\[(.+)\\]$");

    private static final Pattern RESULT = Pattern.compile(
            "^\\s+\\* (.+) \\((?:(\\d+(?:\\.\\d+)?)(ms|s)|(skipped|excluded|invalid))\\) \\[L#(\\d+)\\]$");

    /** {@code   1) test fails (CalcTest)}. */
    private static final Pattern FAILURE = Pattern.compile("^\\s+\\d+\\) (.+) \\(([^()]+)\\)$");

    private static final Pattern LOCATION = Pattern.compile("^\\s+(\\S+\\.exs?):(\\d+)$");

    private final Path dir;
    private String module = "tests";
    private @Nullable String moduleFile;
    private final Map<String, Long> durations = new HashMap<>();
    private final Map<String, Integer> lines = new HashMap<>();
    private @Nullable String failedTest;
    private @Nullable String failedModule;
    private final List<String> block = new ArrayList<>();

    public ExUnitTraceParser(Path dir) {
        this.dir = dir;
    }

    @Override
    public List<ParsedSuite> onLine(String raw, boolean stderr) {
        String text = NodeTestFailure.stripAnsi(raw);
        int cr = text.lastIndexOf('\r'); // "* test x [L#4]\r* test x (0.1ms) [L#4]": what the terminal ends on
        String line = (cr >= 0 ? text.substring(cr + 1) : text).stripTrailing();
        Matcher r = RESULT.matcher(line);
        if (r.matches()) {
            List<ParsedSuite> out = new ArrayList<>(flush());
            String name = r.group(1);
            String word = r.group(4);
            if ("excluded".equals(word)) {
                return out;
            }
            long ms = r.group(2) == null
                    ? 0
                    : Math.round(Double.parseDouble(r.group(2)) * ("s".equals(r.group(3)) ? 1000 : 1));
            int at = Integer.parseInt(r.group(5));
            durations.put(module + "#" + name, ms);
            lines.put(module + "#" + name, at);
            TestStatus status =
                    word == null ? TestStatus.PASSED : "skipped".equals(word) ? TestStatus.SKIPPED : TestStatus.ERROR;
            out.add(new ParsedSuite(
                    module,
                    List.of(new ParsedTest(module, name, status, ms, null, null, null, null, file(moduleFile), at))));
            return out;
        }
        Matcher f = FAILURE.matcher(line);
        if (f.matches()) {
            List<ParsedSuite> out = flush();
            failedTest = f.group(1);
            failedModule = f.group(2);
            return out;
        }
        if (failedTest != null) {
            if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) {
                block.add(line);
                return List.of();
            }
            List<ParsedSuite> out = flush(); // the block ended: a module, "Finished in …"
            module(line);
            return out;
        }
        module(line);
        return List.of();
    }

    private void module(String line) {
        Matcher m = MODULE.matcher(line);
        if (m.matches() && !line.startsWith("Running ExUnit")) {
            module = m.group(1);
            moduleFile = m.group(2);
        }
    }

    private @Nullable String file(@Nullable String relative) {
        return relative == null ? null : dir.resolve(relative).normalize().toString();
    }

    @Override
    public List<ParsedSuite> onExit(int code) {
        return flush();
    }

    private List<ParsedSuite> flush() {
        String test = failedTest;
        String mod = failedModule;
        failedTest = null;
        failedModule = null;
        if (test == null || mod == null) {
            block.clear();
            return List.of();
        }
        String location = null;
        int at = lines.getOrDefault(mod + "#" + test, 0);
        List<String> rest = new ArrayList<>();
        for (String l : block) {
            Matcher loc = LOCATION.matcher(l);
            if (location == null && rest.isEmpty() && loc.matches()) {
                location = loc.group(1);
                at = Integer.parseInt(loc.group(2));
            } else {
                rest.add(l.strip().isEmpty() ? "" : l.replaceFirst("^ {5}", ""));
            }
        }
        block.clear();
        String text = String.join("\n", rest).strip();
        String message = text.isEmpty() ? null : JestJsonReport.firstLine(text);
        ParsedTest result = new ParsedTest(
                mod,
                test,
                TestStatus.FAILED,
                durations.getOrDefault(mod + "#" + test, 0L),
                null,
                message,
                text.isEmpty() ? null : text,
                null,
                file(location != null ? location : mod.equals(module) ? moduleFile : null),
                at);
        return List.of(new ParsedSuite(mod, List.of(result)));
    }
}
