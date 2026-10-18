package ci;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;
import lib.TempDir;
import lib.Test;

class ReportFlakesTest implements AutoCloseable {

    private static final String REPORT = """
            <?xml version="1.0"?>
            <testsuite name="a.B" tests="3">
              <testcase name="steady" classname="a.B"/>
              <testcase name="racy(Path)" classname="a.B">
                <flakyFailure message="expected: &lt;true&gt; but was: &lt;false&gt;" type="AssertionFailedError">trace</flakyFailure>
                <flakyError type="RuntimeException">java.lang.RuntimeException: boom
                at a.B.racy</flakyError>
              </testcase>
              <testcase name="broken" classname="a.B"><failure message="always"/></testcase>
            </testsuite>
            """;

    private final TempDir dir = new TempDir();
    private String out;
    private String err;

    @Override
    public void close() {
        dir.close();
    }

    private void write(String text) throws IOException {
        Files.writeString(dir.resolve("TEST-a.B.xml"), text);
    }

    private int runMain() {
        var outBytes = new ByteArrayOutputStream();
        var errBytes = new ByteArrayOutputStream();
        int code = ReportFlakes.run(new String[] {dir.path().toString()}, new PrintStream(outBytes),
                new PrintStream(errBytes));
        out = outBytes.toString();
        err = errBytes.toString();
        return code;
    }

    @Test
    void onlyATestThatPassedOnARerunIsListed() throws IOException {
        write(REPORT);
        assertEquals(List.of(new ReportFlakes.Flake("a.B", "racy(Path)", 2, "expected: <true> but was: <false>")),
                ReportFlakes.flakyTests(dir.path()));
    }

    @Test
    void aFlakeIsReportedInTheSummaryAndAsAWarningWithoutFailing() throws IOException {
        write(REPORT);
        assertEquals(0, runMain());
        assertContains(out, "| `a.B.racy(Path)` | 2 |");
        assertContains(err, "::warning title=Flaky test::a.B.racy(Path)");
    }

    @Test
    void noFlakesSaysSo() throws IOException {
        write("<testsuite name=\"a.B\"><testcase name=\"steady\" classname=\"a.B\"/></testsuite>");
        assertEquals(0, runMain());
        assertContains(out, "None");
        assertEquals("", err);
    }
}
