package ci;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import lib.Repo;

/**
 * Split the test classes into shards, and check afterwards that every one of them ran.
 *
 * <p>CI runs the Linux suite on several runners at once: each takes one shard, and a last job puts the
 * coverage back together (see {@code .github/workflows/ci.yml}). Surefire has no notion of a shard, so this
 * writes the list of classes a shard runs, in the form Surefire's {@code includesFile} reads:
 *
 * <pre>
 *   java scripts/ci/ShardTests.java list 2 4 &gt; target/shard-includes.txt
 *   ./mvnw -Dsurefire.includesFile=target/shard-includes.txt test
 * </pre>
 *
 * <p>The classes are the files under {@code src/test/java} that Surefire would pick by itself ({@code Test*},
 * {@code *Test}, {@code *Tests}, {@code *TestCase}). They are dealt out by how long they take, the longest
 * first and each to the shard with the least so far, so the shards finish together: a run waits for its
 * slowest shard. Dealt in turn from a sorted list, as they were at first, one shard of four carried 95 seconds
 * more than the average. The JavaFX classes ({@code *FxTest}, which take most of the time) are dealt
 * separately from the rest, so a lane that runs only one kind (the macOS FX shards) is balanced too.
 *
 * <p>The durations are in {@code scripts/ci/test-timings.txt}: the classes that take a second or more on a
 * Linux runner. A class that is not listed counts as {@value #DEFAULT_SECONDS} s, so a new class needs no line
 * and a stale file only costs balance. To refresh it from the reports of one or more runs:
 *
 * <pre>
 *   gh run download &lt;run-id&gt; --pattern 'test-shard-*' --dir target/ci-reports
 *   java scripts/ci/ShardTests.java timings target/ci-reports/test-shard-*&#47;surefire-reports &gt; scripts/ci/test-timings.txt
 * </pre>
 *
 * <p>A list of includes replaces Surefire's own, so a class missing from every list would silently stop
 * running. {@code check} is the guard, run on the reports of all shards together:
 *
 * <pre>
 *   java scripts/ci/ShardTests.java check target/surefire-reports
 * </pre>
 *
 * It fails when a class that declares a test has no report.
 */
public final class ShardTests {

    private static final Pattern SUREFIRE_DEFAULT = Pattern.compile("^(Test.*|.*Test|.*Tests|.*TestCase)$");
    private static final Pattern DECLARES_A_TEST =
            Pattern.compile("@(Test|ParameterizedTest|RepeatedTest|TestFactory|TestTemplate)\\b");
    private static final Pattern ABSTRACT = Pattern.compile("^\\s*(public\\s+)?abstract\\s+class\\b", Pattern.MULTILINE);

    private ShardTests() {}

    static Path testRoot() {
        return Repo.root().resolve("src/test/java");
    }

    /** Paths below {@code root}, with {@code /} separators, of the files Surefire's default includes match; sorted. */
    static List<String> testClasses(Path root) {
        try (Stream<Path> files = Files.walk(root)) {
            return files.filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.endsWith(".java")
                                && SUREFIRE_DEFAULT.matcher(name.substring(0, name.length() - ".java".length()))
                                        .matches();
                    })
                    .map(p -> root.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What a class with no line in the timings file counts as: new, or too quick to be listed. */
    static final double DEFAULT_SECONDS = 0.1;

    /** {@code timings} lists the classes that take at least this long. */
    static final double LISTED_FROM_SECONDS = 1.0;

    static Path timingsFile() {
        return Repo.root().resolve("scripts/ci/test-timings.txt");
    }

    /** {@code path → seconds} from a timings file ({@code <seconds> <path>} lines, {@code #} comments); empty without one. */
    static Map<String, Double> timings(Path file) {
        if (!Files.isRegularFile(file)) {
            return Map.of();
        }
        var seconds = new HashMap<String, Double>();
        try {
            for (String line : Files.readAllLines(file)) {
                String text = line.strip();
                if (text.isEmpty() || text.startsWith("#")) {
                    continue;
                }
                String[] parts = text.split("\\s+", 2);
                seconds.put(parts[1], Double.parseDouble(parts[0]));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (RuntimeException e) {
            throw new IllegalStateException(file + ": expected '<seconds> <path>' lines: " + e.getMessage(), e);
        }
        return seconds;
    }

    /** The classes of shard {@code index} (1-based) of {@code count}, every class taking the same time. */
    static List<String> shard(List<String> classes, int index, int count) {
        return shard(classes, index, count, Map.of());
    }

    /** The classes of shard {@code index} (1-based) of {@code count}, dealt by the {@code seconds} each takes. */
    static List<String> shard(List<String> classes, int index, int count, Map<String, Double> seconds) {
        var fx = classes.stream().filter(c -> c.endsWith("FxTest.java")).toList();
        var rest = classes.stream().filter(c -> !c.endsWith("FxTest.java")).toList();
        var dealt = new ArrayList<String>();
        dealt.addAll(deal(fx, count, seconds).get(index - 1));
        dealt.addAll(deal(rest, count, seconds).get(index - 1));
        dealt.sort(null);
        return dealt;
    }

    /**
     * {@code classes} in {@code count} shards: the longest first, each to the shard with the least so far (the
     * first of them when several have the same). With every class taking the same time that deals them in turn.
     */
    static List<List<String>> deal(List<String> classes, int count, Map<String, Double> seconds) {
        var order = new ArrayList<>(classes);
        order.sort(Comparator.comparingDouble((String c) -> -seconds.getOrDefault(c, DEFAULT_SECONDS))
                .thenComparing(Comparator.naturalOrder()));
        var shards = new ArrayList<List<String>>();
        for (int i = 0; i < count; i++) {
            shards.add(new ArrayList<>());
        }
        double[] load = new double[count];
        for (String c : order) {
            int lightest = 0;
            for (int i = 1; i < count; i++) {
                if (load[i] < load[lightest]) {
                    lightest = i;
                }
            }
            shards.get(lightest).add(c);
            load[lightest] += seconds.getOrDefault(c, DEFAULT_SECONDS);
        }
        return shards;
    }

    /** The seconds the classes of a shard are expected to take together. */
    static double load(List<String> shard, Map<String, Double> seconds) {
        return shard.stream().mapToDouble(c -> seconds.getOrDefault(c, DEFAULT_SECONDS)).sum();
    }

    /**
     * The timings file for the reports in {@code reportDirs}: each directory is one measurement of the classes
     * it reports on (one shard of one run), and a class measured several times gets the mean. Only classes still
     * in {@code classes} that take {@link #LISTED_FROM_SECONDS} or more are listed.
     */
    static List<String> timingLines(List<String> classes, List<Path> reportDirs) {
        var total = new HashMap<String, Double>();
        var measurements = new HashMap<String, Integer>();
        for (Path dir : reportDirs) {
            var here = new HashMap<String, Double>();
            for (Path report : SurefireReports.files(dir)) {
                String name = report.getFileName().toString();
                name = name.substring("TEST-".length(), name.length() - ".xml".length());
                String path = name.split("\\$", 2)[0].replace('.', '/') + ".java"; // a nested class reports as Outer$Nested
                here.merge(path, SurefireReports.seconds(report), Double::sum);
            }
            here.forEach((path, s) -> {
                total.merge(path, s, Double::sum);
                measurements.merge(path, 1, Integer::sum);
            });
        }
        var lines = new ArrayList<String>();
        var known = new HashSet<>(classes);
        for (var entry : new TreeMap<>(total).entrySet()) {
            double mean = entry.getValue() / measurements.get(entry.getKey());
            if (mean >= LISTED_FROM_SECONDS && known.contains(entry.getKey())) {
                lines.add(String.format(Locale.ROOT, "%.1f %s", mean, entry.getKey()));
            }
        }
        return lines;
    }

    /** The classes that declare a test of their own and have no Surefire report. */
    static List<String> unreported(List<String> classes, Path reportDir, Path root) {
        Set<String> reported = new HashSet<>();
        for (Path report : SurefireReports.files(reportDir)) {
            String name = report.getFileName().toString();
            name = name.substring("TEST-".length(), name.length() - ".xml".length());
            reported.add(name.split("\\$", 2)[0]); // a nested class reports as Outer$Nested
        }
        var missing = new ArrayList<String>();
        for (String path : classes) {
            String name = path.substring(0, path.length() - ".java".length()).replace('/', '.');
            if (reported.contains(name)) {
                continue;
            }
            String source;
            try {
                source = Files.readString(root.resolve(path));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            if (DECLARES_A_TEST.matcher(source).find() && !ABSTRACT.matcher(source).find()) {
                missing.add(name);
            }
        }
        return missing;
    }

    static int run(String[] args, PrintStream out, PrintStream err) {
        if (args.length == 3 && args[0].equals("list")) {
            int index = Integer.parseInt(args[1]);
            int count = Integer.parseInt(args[2]);
            if (index < 1 || index > count) {
                err.println("shard " + index + " of " + count + " does not exist");
                return 2;
            }
            out.println(String.join("\n", shard(testClasses(testRoot()), index, count, timings(timingsFile()))));
            return 0;
        }
        if (args.length == 2 && args[0].equals("loads")) {
            int count = Integer.parseInt(args[1]);
            var classes = testClasses(testRoot());
            var seconds = timings(timingsFile());
            for (int index = 1; index <= count; index++) {
                var shard = shard(classes, index, count, seconds);
                out.println(String.format(
                        Locale.ROOT, "shard %d of %d: %d classes, %.0f s", index, count, shard.size(), load(shard, seconds)));
            }
            return 0;
        }
        if (args.length >= 2 && args[0].equals("timings")) {
            var dirs = new ArrayList<Path>();
            for (int i = 1; i < args.length; i++) {
                dirs.add(Path.of(args[i]));
            }
            var lines = timingLines(testClasses(testRoot()), dirs);
            if (lines.isEmpty()) {
                err.println("no Surefire report of a test class taking " + LISTED_FROM_SECONDS + " s or more in " + dirs);
                return 1;
            }
            out.println("# Seconds each test class takes on a Linux CI runner, for scripts/ci/ShardTests.java to deal the");
            out.println("# classes out by. A class under " + LISTED_FROM_SECONDS + " s is left out and counts as "
                    + DEFAULT_SECONDS + " s. Written by `ShardTests.java timings`");
            out.println("# (see that file's comment for the commands); do not edit by hand.");
            out.println(String.join("\n", lines));
            return 0;
        }
        if (args.length == 2 && args[0].equals("check")) {
            var classes = testClasses(testRoot());
            var missing = unreported(classes, Path.of(args[1]), testRoot());
            if (!missing.isEmpty()) {
                out.println(missing.size() + " test class(es) ran on no shard:");
                for (String name : missing) {
                    out.println("  " + name);
                    err.println("::error title=Test class did not run::" + name + " has no Surefire report");
                }
                return 1;
            }
            out.println("All " + classes.size() + " test classes are accounted for.");
            return 0;
        }
        err.println("usage: java scripts/ci/ShardTests.java list <index> <count>");
        err.println("       java scripts/ci/ShardTests.java loads <count>");
        err.println("       java scripts/ci/ShardTests.java timings <surefire-reports-dir>...");
        err.println("       java scripts/ci/ShardTests.java check <surefire-reports-dir>");
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
