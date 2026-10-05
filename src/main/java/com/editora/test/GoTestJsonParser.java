package com.editora.test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Folds {@code go test -json} events into suite deltas. Each line is a JSON object
 * {@code {"Action","Package","Test","Elapsed","Output"}}. A {@code run} for a test starts it; {@code output}
 * lines accumulate (they carry the human-readable {@code go test} text — assertions, {@code file_test.go:NN}
 * frames — which also feeds the failure detail + clickable frames); {@code pass}/{@code fail}/{@code skip}
 * settle it with its elapsed time.
 *
 * <p>A package-level {@code pass}/{@code skip} is ignored (the suite status rolls up from its tests), but a
 * package-level {@code fail} is not always explained by a failed test: the package may not have compiled
 * ({@code FailedBuild}, Go 1.24+ {@code build-output} events keyed by {@code ImportPath}), {@code TestMain}
 * may have exited non-zero, or the test binary may have died mid-test. Those would otherwise leave a failed
 * run with no failed leaf, so a test still running when its package fails is settled as an error, and a
 * package that fails without any failed test gets one synthetic error leaf ({@link #BUILD_FAILED} /
 * {@link #PACKAGE_FAILED}) carrying the package and compiler output.
 *
 * <p>{@link #consoleLine} returns the decoded {@code Output} text so the raw Output console stays
 * human-readable despite the {@code -json} flag; events without output are suppressed there.
 */
public final class GoTestJsonParser implements TestResultParser {

    /** Leaf name for a package whose test binary did not compile. Bracketed, so never a real Go test name. */
    public static final String BUILD_FAILED = "[build failed]";
    /** Leaf name for a package that failed with no failed test (TestMain / os.Exit / a crash before any test). */
    public static final String PACKAGE_FAILED = "[package failed]";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Map<String, StringBuilder> output = new HashMap<>(); // testId -> accumulated output
    private final Map<String, StringBuilder> packageOutput = new HashMap<>(); // package -> package-level output
    private final Map<String, StringBuilder> buildOutput = new HashMap<>(); // ImportPath -> compiler output
    private final Map<String, Set<String>> running = new HashMap<>(); // package -> tests started, not yet settled
    private final Set<String> failedPackages = new HashSet<>(); // packages that have a failed test leaf

    /** Whether {@code testName} is one of this parser's synthetic leaves rather than a runnable Go test. */
    public static boolean isSynthetic(String testName) {
        return testName != null && testName.startsWith("[");
    }

    @Override
    public List<ParsedSuite> onLine(String line, boolean stderr) {
        JsonNode event = read(line);
        if (event == null) {
            return List.of();
        }
        String action = text(event, "Action");
        if (action == null) {
            return List.of();
        }
        if ("build-output".equals(action)) {
            String importPath = text(event, "ImportPath");
            if (importPath != null) {
                buildOutput
                        .computeIfAbsent(importPath, k -> new StringBuilder())
                        .append(textOrEmpty(event, "Output"));
            }
            return List.of();
        }
        String pkg = text(event, "Package");
        if (pkg == null) {
            return List.of(); // a non-test line — nothing to place in the tree
        }
        String test = text(event, "Test");
        if (test == null) {
            return onPackageEvent(action, pkg, event);
        }
        String id = pkg + "#" + test;
        switch (action) {
            case "run" -> {
                running.computeIfAbsent(pkg, k -> new LinkedHashSet<>()).add(test);
                return List.of(suite(pkg, ParsedTest.of(pkg, test, TestStatus.RUNNING, 0)));
            }
            case "output" -> {
                output.computeIfAbsent(id, k -> new StringBuilder()).append(textOrEmpty(event, "Output"));
                return List.of();
            }
            case "pass", "fail", "skip" -> {
                TestStatus status =
                        switch (action) {
                            case "fail" -> TestStatus.FAILED;
                            case "skip" -> TestStatus.SKIPPED;
                            default -> TestStatus.PASSED;
                        };
                Set<String> stillRunning = running.get(pkg);
                if (stillRunning != null) {
                    stillRunning.remove(test);
                }
                if (status == TestStatus.FAILED) {
                    failedPackages.add(pkg);
                }
                long durationMs = Math.round(event.path("Elapsed").asDouble(0) * 1000.0);
                String captured = drain(output, id);
                String failureMessage = status == TestStatus.FAILED ? captured : null;
                String stackTrace = status == TestStatus.FAILED ? captured : null;
                ParsedTest result = new ParsedTest(
                        pkg, test, status, durationMs, null, failureMessage, stackTrace, captured, null, 0);
                return List.of(suite(pkg, result));
            }
            default -> {
                return List.of();
            }
        }
    }

    /** A package-level event (no {@code Test}): collect its output, and account for a {@code fail}. */
    private List<ParsedSuite> onPackageEvent(String action, String pkg, JsonNode event) {
        switch (action) {
            case "output" -> {
                packageOutput.computeIfAbsent(pkg, k -> new StringBuilder()).append(textOrEmpty(event, "Output"));
                return List.of();
            }
            case "fail" -> {
                return onPackageFail(pkg, event);
            }
            case "pass", "skip" -> {
                packageOutput.remove(pkg);
                running.remove(pkg);
                return List.of();
            }
            default -> {
                return List.of();
            }
        }
    }

    private List<ParsedSuite> onPackageFail(String pkg, JsonNode event) {
        String pkgOut = drain(packageOutput, pkg);
        List<ParsedTest> settled = new ArrayList<>();
        Set<String> stillRunning = running.remove(pkg);
        if (stillRunning != null) {
            // The binary died (panic outside a test, os.Exit, a kill) while these were running: no per-test
            // event will ever arrive, so settle them here rather than leave them spinning.
            for (String test : stillRunning) {
                String detail = join(drain(output, pkg + "#" + test), pkgOut);
                settled.add(new ParsedTest(pkg, test, TestStatus.ERROR, 0, null, detail, detail, detail, null, 0));
                failedPackages.add(pkg);
            }
        }
        if (!failedPackages.contains(pkg)) {
            String failedBuild = text(event, "FailedBuild");
            String compiler = failedBuild == null ? null : drain(buildOutput, failedBuild);
            boolean build = failedBuild != null || (pkgOut != null && pkgOut.contains(BUILD_FAILED));
            String detail = join(compiler, pkgOut);
            long durationMs = Math.round(event.path("Elapsed").asDouble(0) * 1000.0);
            settled.add(new ParsedTest(
                    pkg,
                    build ? BUILD_FAILED : PACKAGE_FAILED,
                    TestStatus.ERROR,
                    durationMs,
                    null,
                    detail,
                    detail,
                    detail,
                    null,
                    0));
        }
        return settled.isEmpty() ? List.of() : List.of(new ParsedSuite(pkg, settled));
    }

    @Override
    public String consoleLine(String raw, boolean stderr) {
        JsonNode event = read(raw);
        if (event == null) {
            return raw; // not a -json line (e.g. a go build error) — show it verbatim
        }
        String action = text(event, "Action");
        // Go 1.24+ reports compiler errors as build-output events; without them a build failure shows as a
        // bare "FAIL pkg [build failed]" with the actual error nowhere.
        if (!"output".equals(action) && !"build-output".equals(action)) {
            return null; // suppress run/pass/fail/skip bookkeeping events from the raw console
        }
        String out = textOrEmpty(event, "Output");
        // go's Output fields already carry their own trailing newline; the console appends one, so strip it.
        return out.endsWith("\n") ? out.substring(0, out.length() - 1) : out;
    }

    private static ParsedSuite suite(String pkg, ParsedTest test) {
        return new ParsedSuite(pkg, List.of(test));
    }

    private static String drain(Map<String, StringBuilder> from, String key) {
        StringBuilder sb = from.remove(key);
        if (sb == null || sb.isEmpty()) {
            return null;
        }
        return sb.toString();
    }

    private static String join(String first, String second) {
        if (first == null) {
            return second;
        }
        return second == null ? first : first + second;
    }

    private static JsonNode read(String line) {
        if (line == null || line.isBlank() || line.charAt(0) != '{') {
            return null;
        }
        try {
            JsonNode node = MAPPER.readTree(line);
            return node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String textOrEmpty(JsonNode node, String field) {
        String v = text(node, field);
        return v == null ? "" : v;
    }
}
