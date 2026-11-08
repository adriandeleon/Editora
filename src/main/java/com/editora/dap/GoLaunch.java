package com.editora.dap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * What Delve is asked to debug for a Go file, as VS Code's Go extension asks it: the file's package when it belongs to
 * a module ({@code mode: debug} on the folder, as {@code go run .}), the file alone when it does not ({@code go run
 * file.go}), and in a {@code _test.go} file the test at the caret, or the package's tests ({@code mode: test}). Also
 * the {@code attach} request for a running Go program. Pure but for {@link #moduleRoot}, which looks for
 * {@code go.mod}.
 */
public final class GoLaunch {

    private GoLaunch() {}

    /** A test function's declaration: {@code func TestParse(t *testing.T) {}}, a benchmark, a fuzz test, an example. */
    private static final Pattern TEST_FUNC = Pattern.compile("^func\\s+((Test|Benchmark|Fuzz|Example)\\w*)\\s*\\(");

    /** A top-level function or method declaration. */
    private static final Pattern FUNC = Pattern.compile("^func[\\s(]");

    /** Whether {@code file} is a Go test file ({@code parse_test.go}). */
    public static boolean isTestFile(@Nullable Path file) {
        Path name = file == null ? null : file.getFileName();
        return name != null && name.toString().endsWith("_test.go");
    }

    /**
     * The test function the 0-based {@code line} of {@code text} is in — its declaration line or its body — or null
     * outside any. Read from the text: a test is a top-level {@code func Test…}, {@code Benchmark…}, {@code Fuzz…} or
     * {@code Example…}, and gofmt starts both it and its closing brace in the first column.
     */
    public static @Nullable String testAt(String text, int line) {
        if (text == null || line < 0) {
            return null;
        }
        String[] lines = text.split("\n", -1);
        if (line >= lines.length) {
            return null;
        }
        for (int i = line; i >= 0; i--) {
            String l = lines[i];
            if (i < line && l.startsWith("}")) {
                return null; // the caret is below a function's end, not in one
            }
            Matcher m = TEST_FUNC.matcher(l);
            if (m.find()) {
                return m.group(1);
            }
            if (FUNC.matcher(l).find()) {
                return null; // in another function
            }
        }
        return null;
    }

    /**
     * The test binary's arguments that run test function {@code name} alone: a benchmark is run without the tests
     * ({@code -test.run ^$ -test.bench ^BenchmarkX$}), a test, fuzz test (its seed corpus) or example by name.
     */
    public static List<String> testArgs(String name) {
        String exact = "^" + name + "$";
        if (name.startsWith("Benchmark")) {
            return List.of("-test.run", "^$", "-test.bench", exact);
        }
        return List.of("-test.run", exact);
    }

    /** The folder holding {@code file}'s {@code go.mod}, the file's own or an enclosing one's; null outside a module. */
    public static @Nullable Path moduleRoot(Path file) {
        Path abs = file.toAbsolutePath();
        for (Path dir = abs.getParent(); dir != null; dir = dir.getParent()) {
            if (Files.isRegularFile(dir.resolve("go.mod"))) {
                return dir;
            }
        }
        return null;
    }

    /**
     * The {@code launch} request for Go file {@code file} (text {@code text}, caret on 0-based {@code caretLine}):
     * in a test file its test at the caret (all its package's tests outside one) with {@code args} after the test
     * flags; otherwise its package ({@code inModule}) or the file alone, with {@code args} as the program's. The
     * program runs in the file's folder.
     */
    public static Map<String, Object> launch(
            Path file,
            String text,
            int caretLine,
            boolean inModule,
            List<String> args,
            Map<String, String> env,
            boolean stopOnEntry) {
        Path dir = file.toAbsolutePath().getParent();
        String folder = dir == null ? "" : dir.toString();
        String output = debugBinary();
        if (isTestFile(file)) {
            String test = testAt(text, caretLine);
            List<String> testArgs = new ArrayList<>(test == null ? List.of() : testArgs(test));
            testArgs.addAll(args);
            return ExternalAdapter.launchRequest(
                    "go", folder, testArgs, folder, env, stopOnEntry, Map.of("mode", "test", "output", output));
        }
        String program = inModule ? folder : file.toAbsolutePath().toString();
        return ExternalAdapter.launchRequest(
                "go", program, args, folder, env, stopOnEntry, Map.of("mode", "debug", "output", output));
    }

    /**
     * Where Delve writes the program it builds: a file of its own in the temporary folder. Left to Delve, it is
     * {@code __debug_bin…} in the package — which Delve deletes when it ends normally, but not when Stop ends it, and
     * which a file tree, a watcher and {@code git status} all see meanwhile.
     */
    static String debugBinary() {
        String name = "editora-dlv-"
                + Long.toUnsignedString(
                        java.util.concurrent.ThreadLocalRandom.current().nextLong(), 36)
                + (java.io.File.pathSeparatorChar == ';' ? ".exe" : "");
        return Path.of(System.getProperty("java.io.tmpdir"), name).toString();
    }

    /**
     * What the session of {@code file} is called: the test at the caret ({@code TestParse}), a test file's package
     * tests ({@code parse_test.go}), or the file.
     */
    public static String sessionName(Path file, String text, int caretLine) {
        String name = String.valueOf(file.getFileName());
        if (isTestFile(file)) {
            String test = testAt(text, caretLine);
            return test == null ? name : test;
        }
        return name;
    }

    /** The {@code attach} request for Go process {@code pid} of this machine ({@code dlv attach}). */
    public static Map<String, Object> attach(long pid) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "go");
        m.put("request", "attach");
        m.put("mode", "local");
        m.put("processId", pid);
        return m;
    }
}
