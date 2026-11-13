package ci;

import static lib.Assert.assertContains;
import static lib.Assert.assertEquals;
import static lib.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import lib.TempDir;
import lib.Test;

class ShardTestsTest implements AutoCloseable {

    private final TempDir dir = new TempDir();
    private final Path root = dir.resolve("src");
    private final Path reports = dir.resolve("reports");

    ShardTestsTest() throws IOException {
        Files.createDirectories(reports);
    }

    @Override
    public void close() {
        dir.close();
    }

    private void source(String path) throws IOException {
        source(path, "class X { @Test void t() {} }");
    }

    private void source(String path, String text) throws IOException {
        Path full = root.resolve(path);
        Files.createDirectories(full.getParent());
        Files.writeString(full, text);
    }

    private void report(String name) throws IOException {
        Files.writeString(reports.resolve("TEST-" + name + ".xml"), "<testsuite/>");
    }

    private static List<String> names(String format, int count) {
        return IntStream.range(0, count).mapToObj(i -> String.format(format, i)).toList();
    }

    @Test
    void onlyTheFilesSurefireWouldPickAreListed() throws IOException {
        for (String path : List.of("a/FooTest.java", "a/TestBar.java", "a/BazTests.java", "a/QuxTestCase.java")) {
            source(path);
        }
        source("a/FxTestSupport.java");
        source("a/Fixture.java");
        assertEquals(List.of("a/BazTests.java", "a/FooTest.java", "a/QuxTestCase.java", "a/TestBar.java"),
                ShardTests.testClasses(root));
    }

    @Test
    void everyClassLandsOnExactlyOneShard() {
        var classes = new ArrayList<>(names("p/C%03dTest.java", 41));
        classes.addAll(names("p/U%03dFxTest.java", 23));
        for (int count : new int[] {1, 2, 3, 4, 7}) {
            var dealt = new ArrayList<String>();
            for (int index = 1; index <= count; index++) {
                dealt.addAll(ShardTests.shard(classes, index, count));
            }
            assertEquals(classes.stream().sorted().toList(), dealt.stream().sorted().toList(), count + " shards");
            assertEquals(dealt.size(), new HashSet<>(dealt).size(), count + " shards");
        }
    }

    @Test
    void theSlowFxClassesAreSpreadEvenly() {
        var classes = new ArrayList<>(names("p/C%03dTest.java", 40));
        classes.addAll(names("p/U%03dFxTest.java", 22));
        var fx = IntStream.rangeClosed(1, 4)
                .map(i -> (int) ShardTests.shard(classes, i, 4).stream()
                        .filter(c -> c.endsWith("FxTest.java"))
                        .count())
                .boxed()
                .toList();
        assertTrue(fx.stream().max(Integer::compare).get() - fx.stream().min(Integer::compare).get() <= 1,
                fx.toString());
    }

    @Test
    void theLongClassesAreDealtByDurationNotByCount() {
        var classes = new ArrayList<>(names("p/C%03dTest.java", 40));
        var seconds = new HashMap<String, Double>();
        seconds.put("p/C000Test.java", 60.0);
        seconds.put("p/C001Test.java", 30.0);
        seconds.put("p/C002Test.java", 30.0);
        var first = ShardTests.shard(classes, 1, 2, seconds);
        var second = ShardTests.shard(classes, 2, 2, seconds);
        assertEquals(List.of("p/C000Test.java"), first.stream().filter(seconds::containsKey).toList());
        assertEquals(List.of("p/C001Test.java", "p/C002Test.java"),
                second.stream().filter(seconds::containsKey).toList());
        assertEquals(40, first.size() + second.size());
        assertTrue(Math.abs(ShardTests.load(first, seconds) - ShardTests.load(second, seconds)) <= 0.11,
                ShardTests.load(first, seconds) + " and " + ShardTests.load(second, seconds));
    }

    @Test
    void theFxClassesAreBalancedOnTheirOwn() {
        // The macOS FX lane runs only the FX classes of each list, so those must balance without the rest.
        var classes = new ArrayList<>(names("p/C%03dTest.java", 30));
        classes.addAll(names("p/U%03dFxTest.java", 30));
        var seconds = new HashMap<String, Double>();
        seconds.put("p/C000Test.java", 500.0);
        for (int i = 0; i < 30; i++) {
            seconds.put(String.format("p/U%03dFxTest.java", i), 10.0);
        }
        for (int index = 1; index <= 3; index++) {
            long fx = ShardTests.shard(classes, index, 3, seconds).stream()
                    .filter(c -> c.endsWith("FxTest.java"))
                    .count();
            assertEquals(10L, fx, "shard " + index);
        }
    }

    @Test
    void aTimingsFileIsReadAndAMissingOneIsEmpty() throws IOException {
        Path file = dir.resolve("timings.txt");
        assertEquals(Map.of(), ShardTests.timings(file));
        Files.writeString(file, "# a comment\n\n12.5 a/SlowTest.java\n  3.0   b/OtherFxTest.java\n");
        assertEquals(Map.of("a/SlowTest.java", 12.5, "b/OtherFxTest.java", 3.0), ShardTests.timings(file));
    }

    @Test
    void timingsAreTheMeanOfEveryMeasurementOfAClassStillThere() throws IOException {
        Path run1 = Files.createDirectories(dir.resolve("run1"));
        Path run2 = Files.createDirectories(dir.resolve("run2"));
        Files.writeString(run1.resolve("TEST-a.SlowTest.xml"), "<testsuite time=\"10.0\"/>");
        Files.writeString(run2.resolve("TEST-a.SlowTest.xml"), "<testsuite time=\"20.0\"/>");
        Files.writeString(run1.resolve("TEST-a.NestedTest$One.xml"), "<testsuite time=\"2.0\"/>");
        Files.writeString(run1.resolve("TEST-a.NestedTest$Two.xml"), "<testsuite time=\"3.0\"/>");
        Files.writeString(run1.resolve("TEST-a.QuickTest.xml"), "<testsuite time=\"0.4\"/>");
        Files.writeString(run1.resolve("TEST-a.GoneTest.xml"), "<testsuite time=\"50.0\"/>");
        var classes = List.of("a/NestedTest.java", "a/QuickTest.java", "a/SlowTest.java");
        assertEquals(List.of("5.0 a/NestedTest.java", "15.0 a/SlowTest.java"),
                ShardTests.timingLines(classes, List.of(run1, run2)));
    }

    @Test
    void theRealSuiteSplitsIntoShardsOfSimilarDuration() {
        var classes = ShardTests.testClasses(ShardTests.testRoot());
        assertTrue(classes.size() > 1000, classes.size() + " classes");
        var seconds = ShardTests.timings(ShardTests.timingsFile());
        assertTrue(seconds.size() > 100, "scripts/ci/test-timings.txt lists " + seconds.size() + " classes");
        for (int count : new int[] {3, 6}) {
            var shards = IntStream.rangeClosed(1, count)
                    .mapToObj(i -> ShardTests.shard(classes, i, count, seconds))
                    .toList();
            assertEquals(classes.size(), shards.stream().mapToInt(List::size).sum());
            var loads = shards.stream().map(s -> ShardTests.load(s, seconds)).toList();
            double most = loads.stream().max(Double::compare).get();
            double least = loads.stream().min(Double::compare).get();
            // A class longer than a whole share cannot be split: refresh the timings, or split the class.
            assertTrue(most - least <= 0.05 * most, count + " shards: " + loads);
        }
    }

    @Test
    void aClassWithATestAndNoReportIsNamed() throws IOException {
        source("a/RanTest.java");
        source("a/LostTest.java");
        source("a/NestedOnlyTest.java");
        source("a/AbstractBaseTest.java", "public abstract class AbstractBaseTest { @Test void t() {} }");
        source("a/TestHelpers.java", "final class TestHelpers { static void help() {} }");
        report("a.RanTest");
        report("a.NestedOnlyTest$Inner");
        var classes = ShardTests.testClasses(root);
        assertEquals(List.of("a.LostTest"), ShardTests.unreported(classes, reports, root));
    }

    @Test
    void aShardThatDoesNotExistIsRefused() {
        var err = new ByteArrayOutputStream();
        assertEquals(2, ShardTests.run(new String[] {"list", "5", "4"}, new PrintStream(new ByteArrayOutputStream()),
                new PrintStream(err)));
        assertContains(err.toString(), "does not exist");
    }
}
