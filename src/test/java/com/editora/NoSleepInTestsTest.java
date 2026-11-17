package com.editora;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests do not sleep (QUAL-07, Pauta 6.0.1). A test waits on the thing it is waiting for: a latch or a future the
 * code completes, {@code drainFx} after the work landed, a listener, {@code AsyncTestScope}'s barriers, or, for
 * state nothing announces, {@link com.editora.testkit.Await}, which re-checks after each FX pulse or each change a
 * fake signals. A fake that has to be slow holds on a latch the test releases; a file time that has to differ is
 * set with {@code Files.setLastModifiedTime}.
 *
 * <p>This fails on a sleep on {@code Thread} or a {@code TimeUnit}, or a {@code LockSupport} park with a time
 * anywhere in the test sources. A sleep inside a script a test runs (Python's {@code time.sleep}, a shell
 * {@code sleep 30}) is not Java and is not matched.
 */
@Tag("guard")
class NoSleepInTestsTest {

    private static final Pattern SLEEP = Pattern.compile("\\bThread\\s*\\.\\s*sleep\\s*\\("
            + "|\\b(?:TimeUnit\\s*\\.\\s*)?(?:NANOSECONDS|MICROSECONDS|MILLISECONDS|SECONDS|MINUTES|HOURS|DAYS)"
            + "\\s*\\.\\s*sleep\\s*\\("
            + "|\\bpark(?:Nanos|Until)\\s*\\(");

    @Test
    void noTestSleeps() throws Exception {
        Path root = Path.of("src/test/java");
        assertTrue(Files.isDirectory(root), "Run from the Maven project root");
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file :
                    files.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                if (file.endsWith("NoSleepInTestsTest.java")) {
                    continue; // names what it looks for
                }
                List<String> lines = Files.readAllLines(file);
                for (int i = 0; i < lines.size(); i++) {
                    if (SLEEP.matcher(lines.get(i)).find()) {
                        found.add(root.relativize(file).toString().replace('\\', '/') + ":" + (i + 1) + ": "
                                + lines.get(i).strip());
                    }
                }
            }
        }
        assertTrue(
                found.isEmpty(),
                () -> found.size() + " sleeps in the tests; wait on the thing itself (docs/testing.md, \"Waiting\"):\n"
                        + String.join("\n", found));
    }
}
