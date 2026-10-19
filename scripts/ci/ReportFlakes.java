package ci;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.w3c.dom.Element;

/**
 * List the tests that failed and then passed when Surefire ran them again.
 *
 * <p>CI reruns a failing test ({@code -Dsurefire.rerunFailingTestsCount}), so a test that only fails now and
 * then no longer turns the build red. That must not hide it: a flaky test is a race in the test or in the
 * application. This reads the Surefire XML reports and prints every test that needed a rerun, as a GitHub
 * warning annotation and as Markdown for the job summary.
 *
 * <pre>
 *   java scripts/ci/ReportFlakes.java target/surefire-reports &gt;&gt; "$GITHUB_STEP_SUMMARY"
 * </pre>
 *
 * Always exits 0: the tests passed in the end. It is the report that matters.
 */
public final class ReportFlakes {

    /** One test that needed a rerun: how many attempts failed, and the first failure's message. */
    record Flake(String className, String name, int attempts, String message) {}

    private ReportFlakes() {}

    static String firstLine(String text) {
        String stripped = text == null ? "" : text.strip();
        return stripped.isEmpty() ? "" : stripped.lines().findFirst().orElse("").strip();
    }

    /** Every flaky test in the reports, sorted. */
    static List<Flake> flakyTests(Path reportDir) {
        var found = new ArrayList<Flake>();
        for (Path report : SurefireReports.files(reportDir)) {
            for (Element testCase : SurefireReports.testCases(report)) {
                var attempts = new ArrayList<Element>();
                for (var node = testCase.getFirstChild(); node != null; node = node.getNextSibling()) {
                    if (node instanceof Element child
                            && (child.getTagName().equals("flakyFailure") || child.getTagName().equals("flakyError"))) {
                        attempts.add(child);
                    }
                }
                if (!attempts.isEmpty()) {
                    String message = attempts.getFirst().getAttribute("message");
                    if (message.isEmpty()) {
                        message = firstLine(attempts.getFirst().getTextContent());
                    }
                    found.add(new Flake(testCase.getAttribute("classname"), testCase.getAttribute("name"),
                            attempts.size(), firstLine(message)));
                }
            }
        }
        found.sort(Comparator.comparing(Flake::className)
                .thenComparing(Flake::name)
                .thenComparingInt(Flake::attempts)
                .thenComparing(Flake::message));
        return found;
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length != 1) {
            err.println("usage: java scripts/ci/ReportFlakes.java <surefire-reports-dir>");
            return 2;
        }
        Path dir = Path.of(args[0]);
        if (SurefireReports.files(dir).isEmpty()) {
            return 0; // the build stopped before any test ran: nothing to say
        }
        var flakes = flakyTests(dir);
        out.println("### Flaky tests");
        if (flakes.isEmpty()) {
            out.println("None: no test needed a rerun.");
            return 0;
        }
        out.println(flakes.size() + " test(s) failed and then passed on a rerun. Each one is a race worth fixing.");
        out.println();
        out.println("| Test | Failed attempts | First failure |");
        out.println("|---|---|---|");
        for (Flake flake : flakes) {
            String test = flake.className() + "." + flake.name();
            out.println("| `" + test + "` | " + flake.attempts() + " | "
                    + truncate(flake.message().replace('|', '/'), 200) + " |");
            // Annotations go to the log, not the summary file.
            err.println("::warning title=Flaky test::" + test + " passed only on a rerun: "
                    + truncate(flake.message(), 200));
        }
        return 0;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
