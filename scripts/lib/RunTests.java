package lib;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Runs the tests of the scripts: every {@code *Test.java} under {@code scripts/}, through the JDK's source
 * launcher, so nothing is built and no test library is needed.
 *
 * <pre>
 *   java scripts/lib/RunTests.java                     every test
 *   java scripts/lib/RunTests.java release ci.Shard    only the classes whose name starts with one of these
 * </pre>
 *
 * Exits 1 when a test failed. A test that called {@link Assert#assumeTrue} with a false condition is reported
 * as skipped.
 */
public final class RunTests {

    public static void main(String[] args) throws IOException {
        Path scripts = Repo.scripts();
        List<String> names;
        try (Stream<Path> files = Files.walk(scripts)) {
            names = files.filter(p -> p.getFileName().toString().endsWith("Test.java"))
                    .filter(p -> !p.getFileName().toString().equals("Test.java"))
                    .map(p -> scripts.relativize(p).toString().replace(p.getFileSystem().getSeparator(), "."))
                    .map(n -> n.substring(0, n.length() - ".java".length()))
                    .filter(n -> args.length == 0 || Stream.of(args).anyMatch(n::startsWith))
                    .sorted()
                    .toList();
        }
        if (names.isEmpty()) {
            System.err.println("no test classes match " + String.join(" ", args));
            System.exit(2);
        }
        int passed = 0;
        int skipped = 0;
        var failures = new ArrayList<String>();
        for (String name : names) {
            Class<?> type;
            try {
                type = Class.forName(name, true, RunTests.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError e) {
                failures.add(name);
                System.out.println("FAIL  " + name + " (does not load: " + e + ")");
                continue;
            }
            List<Method> tests = Stream.of(type.getDeclaredMethods())
                    .filter(m -> m.isAnnotationPresent(Test.class))
                    .sorted(Comparator.comparing(Method::getName))
                    .toList();
            for (Method method : tests) {
                String id = type.getName() + "." + method.getName();
                long start = System.nanoTime();
                Throwable problem = run(type, method);
                long ms = (System.nanoTime() - start) / 1_000_000;
                if (problem == null) {
                    passed++;
                    System.out.printf("ok    %s (%d ms)%n", id, ms);
                } else if (problem instanceof Assert.Skipped) {
                    skipped++;
                    System.out.printf("skip  %s: %s%n", id, problem.getMessage());
                } else {
                    failures.add(id);
                    System.out.printf("FAIL  %s (%d ms)%n", id, ms);
                    problem.printStackTrace(System.out);
                }
            }
        }
        System.out.printf("%n%d passed, %d failed, %d skipped%n", passed, failures.size(), skipped);
        for (String failure : failures) {
            System.out.println("  FAILED " + failure);
        }
        System.exit(failures.isEmpty() ? 0 : 1);
    }

    /** Runs one test on a fresh instance; returns what it threw, or null. */
    private static Throwable run(Class<?> type, Method method) {
        Object instance = null;
        Throwable problem = null;
        try {
            var constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            instance = constructor.newInstance();
            method.setAccessible(true);
            method.invoke(instance);
        } catch (InvocationTargetException e) {
            problem = e.getCause();
        } catch (ReflectiveOperationException e) {
            problem = e;
        }
        if (instance instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                if (problem == null) {
                    problem = e;
                }
            }
        }
        return problem;
    }
}
