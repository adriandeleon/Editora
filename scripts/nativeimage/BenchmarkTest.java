package nativeimage;

import static lib.Assert.assertEquals;
import static lib.Assert.assertFalse;
import static lib.Assert.assertNotNull;
import static lib.Assert.assumeTrue;

import java.util.List;
import java.util.Map;
import lib.Test;
import lib.TempDir;

/** Protocol regressions: a failed child must never become a successful timing sample. */
class BenchmarkTest implements AutoCloseable {

    private static final String PASS = "{\"kind\":\"result\",\"name\":\"PASS\",\"bytes\":0,\"value\":0}";

    private final TempDir temp = new TempDir();

    @Override
    public void close() {
        temp.close();
    }

    /** Runs a {@code sh -c} child, which stands in for the editor or the application under test. */
    private Map<String, Object> child(String script, String kind, double timeout) throws Exception {
        assumeTrue(!System.getProperty("os.name").startsWith("Windows"), "the stand-in children are sh scripts");
        return Benchmark.run(List.of("sh", "-c", script), Map.of(), temp.resolve("run.log"), kind, timeout);
    }

    private Map<String, Object> child(String script) throws Exception {
        return child(script, "editor", 2);
    }

    private static String quoted(String text) {
        return "'" + text.replace("'", "'\\''") + "'";
    }

    @Test
    void exitZeroWithoutAssertionProtocolIsFailure() throws Exception {
        assertEquals(false, child("echo 'window shown'").get("ok"));
    }

    @Test
    void passThenNonzeroExitIsFailure() throws Exception {
        assertEquals(false, child("printf '%s\\n' " + quoted(PASS) + "; exit 2").get("ok"));
    }

    @Test
    void passWithZeroExit() throws Exception {
        assertEquals(true, child("printf '%s\\n' " + quoted(PASS)).get("ok"));
    }

    @Test
    void stderrCannotCorruptEditorProtocol() throws Exception {
        // Each printf is its own write: the warning lands between the two halves of the JSON line.
        String script = "printf '%s' '{\"kind\":'; printf 'warning\\n' >&2; "
                + "printf '%s\\n' '\"result\",\"name\":\"PASS\",\"bytes\":0,\"value\":0}'";
        assertEquals(true, child(script).get("ok"));
    }

    @Test
    void malformedProtocolIsRetainedAsFailure() throws Exception {
        Map<String, Object> result = child("printf '%s\\n' '{\"kind\":broken}'");
        assertEquals(false, result.get("ok"));
        assertNotNull(result.get("protocol_error"), "protocol error recorded");
    }

    @Test
    void stageShowIsNotStartupSuccess() throws Exception {
        assertEquals(false, child("echo '[perf] window-shown 10 (+10)'", "startup", 2).get("ok"));
        assertEquals(true, child("echo '[perf] first-paint 20 (+10)'", "startup", 2).get("ok"));
    }

    @Test
    void timeoutIsFailure() throws Exception {
        Map<String, Object> result = child("sleep 5", "editor", .1);
        assertEquals(true, result.get("timeout"));
        assertFalse((Boolean) result.get("ok"), "a timed-out run is not ok");
    }

    @Test
    void percentilesAndEvenMedian() {
        assertEquals(
                Map.of("n", 4, "median", 2.5, "p95", 4L, "p99", 4L),
                Benchmark.summarize(List.of(4L, 1L, 2L, 3L)));
    }
}
