package ci;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.w3c.dom.Element;

/**
 * Fail when a test was skipped that the allow-list does not expect.
 *
 * <p>A test guarded by an assumption (a tool on the PATH, a filesystem feature) reports "skipped", not
 * "failed", so a runner that loses the tool keeps a green build while the feature goes untested. This reads
 * the Surefire XML reports and compares every skipped test against an allow-list.
 *
 * <pre>
 *   java scripts/ci/CheckSkips.java target/surefire-reports scripts/ci/expected-skips-linux.txt
 * </pre>
 *
 * <p>Allow-list lines are {@code fully.qualified.Class} (any test in it may skip) or
 * {@code fully.qualified.Class#method} (parameter lists and invocation indexes are ignored). {@code #} at the
 * start of a line is a comment. An entry that matches no skipped test is reported but does not fail the run:
 * the same list serves both JDK lanes.
 */
public final class CheckSkips {

    /** What {@link #check} found: the unexpected skips, the allow-list entries nothing used, the skip count. */
    record Outcome(List<String> unexpected, List<String> unused, int total) {}

    private CheckSkips() {}

    static Set<String> loadAllowed(Path path) {
        try {
            var allowed = new TreeSet<String>();
            for (String line : Files.readAllLines(path)) {
                line = line.strip();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    allowed.add(line);
                }
            }
            return allowed;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** {@code save(Alias, Path)[4]} -> {@code save}. */
    static String methodName(String name) {
        return name.split("[(\\[]", 2)[0].strip();
    }

    /** {@code Class#method} of every skipped test. */
    static Set<String> skippedTests(Path reportDir) {
        var found = new TreeSet<String>();
        for (Path report : SurefireReports.files(reportDir)) {
            for (Element testCase : SurefireReports.testCases(report)) {
                if (!SurefireReports.children(testCase, "skipped").isEmpty()) {
                    found.add(testCase.getAttribute("classname") + "#" + methodName(testCase.getAttribute("name")));
                }
            }
        }
        return found;
    }

    static Outcome check(Path reportDir, Path allowPath) {
        var allowed = loadAllowed(allowPath);
        var skipped = skippedTests(reportDir);
        var unexpected = new TreeSet<String>();
        var used = new TreeSet<String>();
        for (String test : skipped) {
            String className = test.substring(0, test.indexOf('#'));
            if (!allowed.contains(className) && !allowed.contains(test)) {
                unexpected.add(test);
            }
            used.add(className);
            used.add(test);
        }
        var unused = new TreeSet<>(allowed);
        unused.removeAll(used);
        return new Outcome(List.copyOf(unexpected), List.copyOf(unused), skipped.size());
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 2) {
            err.println("usage: java scripts/ci/CheckSkips.java <surefire-reports-dir> <allow-list>");
            return 2;
        }
        Path reports = Path.of(args[0]);
        if (SurefireReports.files(reports).isEmpty()) {
            err.println("no Surefire reports in " + args[0]);
            return 2;
        }
        Outcome outcome = check(reports, Path.of(args[1]));
        out.println(outcome.total() + " skipped test(s); " + outcome.unexpected().size() + " not on the allow-list");
        for (String entry : outcome.unused()) {
            out.println("  note: allow-list entry did not skip here: " + entry);
        }
        for (String entry : outcome.unexpected()) {
            out.println("  UNEXPECTED SKIP: " + entry);
        }
        if (!outcome.unexpected().isEmpty()) {
            out.println("A skipped test is usually a tool missing from the runner. Install it in ci.yml, or add the "
                    + "test to the allow-list with the reason.");
            return 1;
        }
        return 0;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
