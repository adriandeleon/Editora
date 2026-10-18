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
import java.util.HashSet;
import java.util.List;
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
    void theRealSuiteSplitsIntoFourShardsOfSimilarSize() {
        var classes = ShardTests.testClasses(ShardTests.testRoot());
        assertTrue(classes.size() > 1000, classes.size() + " classes");
        var sizes = IntStream.rangeClosed(1, 4)
                .map(i -> ShardTests.shard(classes, i, 4).size())
                .boxed()
                .toList();
        assertEquals(classes.size(), sizes.stream().mapToInt(Integer::intValue).sum());
        assertTrue(sizes.stream().max(Integer::compare).get() - sizes.stream().min(Integer::compare).get() <= 2,
                sizes.toString());
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
