package ci;

import static lib.Assert.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import lib.TempDir;
import lib.Test;

class CheckSkipsTest implements AutoCloseable {

    private static final String REPORT = """
            <?xml version="1.0"?>
            <testsuite name="a.B" tests="3">
              <testcase name="runs" classname="a.B"/>
              <testcase name="needsTool(Path)[2]" classname="a.B"><skipped message="no tool"/></testcase>
              <testcase name="alsoSkipped" classname="a.B"><skipped/></testcase>
            </testsuite>
            """;

    private final TempDir dir = new TempDir();
    private final Path reports = dir.resolve("reports");
    private final Path allow = dir.resolve("allow.txt");

    CheckSkipsTest() throws IOException {
        Files.createDirectory(reports);
        Files.writeString(reports.resolve("TEST-a.B.xml"), REPORT);
    }

    @Override
    public void close() {
        dir.close();
    }

    private void writeAllow(String text) throws IOException {
        Files.writeString(allow, text);
    }

    private static int main(Path reports, Path allow) {
        var sink = new PrintStream(new ByteArrayOutputStream());
        return CheckSkips.run(new String[] {reports.toString(), allow.toString()}, sink, sink);
    }

    @Test
    void anUnlistedSkipFails() throws IOException {
        writeAllow("# nothing\n");
        var outcome = CheckSkips.check(reports, allow);
        assertEquals(List.of("a.B#alsoSkipped", "a.B#needsTool"), outcome.unexpected());
        assertEquals(2, outcome.total());
        assertEquals(1, main(reports, allow));
    }

    @Test
    void aClassEntryAllowsEveryTestInIt() throws IOException {
        writeAllow("a.B\n");
        assertEquals(0, main(reports, allow));
    }

    @Test
    void aMethodEntryIgnoresParametersAndAllowsOnlyThatMethod() throws IOException {
        writeAllow("a.B#needsTool\n");
        assertEquals(List.of("a.B#alsoSkipped"), CheckSkips.check(reports, allow).unexpected());
    }

    @Test
    void anEntryThatDidNotSkipIsReportedButPasses() throws IOException {
        writeAllow("a.B\nc.D\n");
        var outcome = CheckSkips.check(reports, allow);
        assertEquals(List.of(), outcome.unexpected());
        assertEquals(List.of("c.D"), outcome.unused());
    }

    @Test
    void aMissingReportDirectoryIsAnErrorNotAPass() throws IOException {
        writeAllow("a.B\n");
        assertEquals(2, main(dir.resolve("none"), allow));
    }
}
