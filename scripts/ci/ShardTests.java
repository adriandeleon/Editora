package ci;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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
 * {@code *Test}, {@code *Tests}, {@code *TestCase}). They are dealt out in turn from a sorted list, the JavaFX
 * classes ({@code *FxTest}, which take most of the time) separately from the rest, so every shard gets its
 * share of both and classes with neighbouring names land on different runners.
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

    /** The classes of shard {@code index} (1-based) of {@code count}. */
    static List<String> shard(List<String> classes, int index, int count) {
        var fx = classes.stream().filter(c -> c.endsWith("FxTest.java")).toList();
        var rest = classes.stream().filter(c -> !c.endsWith("FxTest.java")).toList();
        var dealt = new ArrayList<String>();
        for (int i = index - 1; i < fx.size(); i += count) {
            dealt.add(fx.get(i));
        }
        for (int i = index - 1; i < rest.size(); i += count) {
            dealt.add(rest.get(i));
        }
        dealt.sort(null);
        return dealt;
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
            out.println(String.join("\n", shard(testClasses(testRoot()), index, count)));
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
        err.println("       java scripts/ci/ShardTests.java check <surefire-reports-dir>");
        return 2;
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }
}
